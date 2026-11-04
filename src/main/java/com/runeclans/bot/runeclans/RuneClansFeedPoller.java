package com.runeclans.bot.runeclans;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.plugin.PluginEventSink;
import com.runeclans.bot.plugin.PluginEventType;
import com.runeclans.bot.plugin.PluginPayload;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * With {@code RUNECLANS_HUB_ENABLED} on, plugin events reach the bot from the RuneClans hub instead of its own ingest (runeclans-hub HUB-DESIGN.md
 * section 7): every few seconds ({@link #POLL_EVERY_SECONDS}) it reads JonnyBot's change feed and hands each {@code plugin.event} to the same {@link PluginEventSink}s
 * (points, event tiles) the bot's own ingest uses. It also, when the bot connects, registers the servers it is in, and when a player grants
 * JonnyBot with identity, adds them to those of its servers they are a member of.
 * <ul>
 *   <li>The cursor is stored ({@link RuneClansFeedCursor}); on the very first run the feed is skipped to its end, so switching on never replays
 *       old events into points.</li>
 *   <li>An event is passed on only when the player granted JonnyBot identity (that is how the bot knows whose it is); others are skipped.</li>
 *   <li>Off by default: with the flag off nothing here runs.</li>
 * </ul>
 */
@BService
public class RuneClansFeedPoller {
    private static final Logger log = LoggerFactory.getLogger(RuneClansFeedPoller.class);
    static final int PAGE = 200;
    /**
     * How often the feed is read. Short so a request changed on the website shows on its Discord card within about 5 seconds (T-734); an
     * empty read is one cheap call, far inside a first-party key's limit.
     */
    static final int POLL_EVERY_SECONDS = 3;

    private final RuneClansHubClient hub;
    private final Relay relay;
    private volatile JDA jda;
    private final ScheduledExecutorService executor = BotScheduler.lane("runeclans-feed");

    public RuneClansFeedPoller(RuneClansHubClient hub, RuneClansFeedCursor cursor, List<PluginEventSink> sinks, List<HubFeedListener> listeners) {
        this.hub = hub;
        this.relay = new Relay(hub, cursor::load, cursor::save, sinks, this::addToGuilds, listeners);
        if (hub.enabled()) start();
    }

    /**
     * Registers the bot's servers once the guild cache is loaded (on ready, not when JDA is built), on the feed's lane: one HTTP call per server
     * must not hold up JDA's event thread.
     */
    @BEventListener
    public void onReady(ReadyEvent event) {
        jda = event.getJDA();
        if (!hub.enabled()) return;
        executor.execute(() -> {
            List<Guild> guilds = Shards.guilds(event.getJDA());
            int registered = 0;
            for (Guild g : guilds) if (hub.putGuild(g.getIdLong(), g.getName())) registered++;
            log.info("RuneClans hub: registered {} of {} servers", registered, guilds.size());
        });
    }

    private void start() {
        executor.scheduleWithFixedDelay(() -> {
            try {
                relay.poll();
            } catch (RuntimeException e) {
                log.warn("RuneClans feed poll failed: {}", e.getClass().getSimpleName());
            }
        }, 20, POLL_EVERY_SECONDS, TimeUnit.SECONDS);
        // writes the hub couldn't take when they were made (hub down or busy), held in memory for up to 5 minutes
        executor.scheduleWithFixedDelay(() -> {
            try {
                hub.drainWrites();
            } catch (RuntimeException e) {
                log.warn("RuneClans hub write retry failed: {}", e.getClass().getSimpleName());
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    /** A player granted JonnyBot identity: add them to each of the bot's servers they are a (cached) member of. */
    private void addToGuilds(Map.Entry<String, Long> aliasAndDiscordId) {
        JDA current = jda;
        if (current == null) return;
        for (Guild g : Shards.guilds(current)) {
            if (g.getMemberById(aliasAndDiscordId.getValue()) != null) hub.putGuildMember(g.getIdLong(), aliasAndDiscordId.getKey());
        }
    }

    /** The feed-to-sinks logic, apart from the service wiring so tests can drive it with a fake hub. */
    static final class Relay {
        private static final Duration IDS_KEPT = Duration.ofMinutes(10);

        private final RuneClansHubClient hub;
        private final Supplier<Optional<String>> loadCursor;
        private final Consumer<String> saveCursor;
        private final List<PluginEventSink> sinks;
        private final Consumer<Map.Entry<String, Long>> granted;
        private final List<HubFeedListener> listeners;
        private final ConcurrentHashMap<String, CachedId> ids = new ConcurrentHashMap<>();
        /** The cursor as last loaded or saved: read from the database once, written only when the feed moved (it is polled every few seconds). */
        private String known;
        private boolean loaded;

        private record CachedId(Optional<Long> id, Instant until) {}

        Relay(RuneClansHubClient hub, Supplier<Optional<String>> loadCursor, Consumer<String> saveCursor, List<PluginEventSink> sinks,
              Consumer<Map.Entry<String, Long>> granted) {
            this(hub, loadCursor, saveCursor, sinks, granted, List.of());
        }

        Relay(RuneClansHubClient hub, Supplier<Optional<String>> loadCursor, Consumer<String> saveCursor, List<PluginEventSink> sinks,
              Consumer<Map.Entry<String, Long>> granted, List<HubFeedListener> listeners) {
            this.hub = hub;
            this.loadCursor = loadCursor;
            this.saveCursor = saveCursor;
            this.sinks = List.copyOf(sinks);
            this.granted = granted;
            this.listeners = List.copyOf(listeners);
        }

        /** Reads the feed until it has no more. Returns how many events were passed on. */
        int poll() {
            if (!loaded) {
                known = loadCursor.get().orElse(null);
                loaded = true;
            }
            boolean firstRun = known == null;
            String cursor = known;
            int passed = 0;
            for (int pages = 0; pages < 50; pages++) {
                RuneClansHubClient.Answer a = hub.feed(cursor, PAGE);
                if (a == null || !a.ok() || a.json() == null) return passed;
                DataArray data = a.json().getArray("data");
                if (!firstRun) {
                    passed += handle(data);
                    for (HubFeedListener l : listeners) {
                        try {
                            l.onHubFeedPage();
                        } catch (RuntimeException ex) {
                            log.warn("A hub feed listener failed at the end of a page: {}", ex.getClass().getSimpleName());
                        }
                    }
                }
                cursor = a.json().getString("nextCursor", cursor);
                if (cursor != null && !cursor.equals(known)) {
                    saveCursor.accept(cursor);
                    known = cursor;
                }
                if (!a.json().getBoolean("more", false)) break;
            }
            if (firstRun) log.info("RuneClans feed: started from its current end (nothing replayed)");
            return passed;
        }

        private int handle(DataArray entries) {
            Map<String, List<PluginPayload.Event>> byPlayer = new LinkedHashMap<>();
            Map<String, Long> owner = new LinkedHashMap<>();
            Map<String, String> rsnOf = new LinkedHashMap<>();
            for (int i = 0; i < entries.length(); i++) {
                DataObject e = entries.getObject(i);
                String kind = e.getString("kind", "");
                hub.onHubChange(kind, e.optObject("data").orElse(null));
                for (HubFeedListener l : listeners) {
                    try {
                        l.onHubFeed(kind, e.optObject("data").orElse(null));
                    } catch (RuntimeException ex) {
                        log.warn("A hub feed listener failed: {}", ex.getClass().getSimpleName());
                    }
                }
                String alias = e.getString("alias", null);
                if (alias == null) continue;
                if (kind.equals("player.grant_changed") && !e.getObject("data").getBoolean("revoked", false) && e.getObject("data").getBoolean("identity", false)) {
                    discordId(alias).ifPresent(id -> granted.accept(Map.entry(alias, id)));
                    continue;
                }
                if (!kind.equals("plugin.event")) continue;
                Optional<Long> id = discordId(alias);
                PluginPayload.Event event = toEvent(e.getObject("data"));
                if (id.isEmpty() || event == null) continue;
                String rsn = e.getObject("data").getString("rsn", null);
                String key = id.get() + "|" + rsn;
                byPlayer.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
                owner.put(key, id.get());
                rsnOf.put(key, rsn);
            }
            int passed = 0;
            for (var entry : byPlayer.entrySet()) {
                for (PluginEventSink sink : sinks) {
                    try {
                        sink.accepted(owner.get(entry.getKey()), rsnOf.get(entry.getKey()), entry.getValue());
                    } catch (RuntimeException ex) {
                        log.warn("A consumer of hub plugin events failed: {}", ex.getClass().getSimpleName());
                    }
                }
                passed += entry.getValue().size();
            }
            return passed;
        }

        private Optional<Long> discordId(String alias) {
            Instant now = Instant.now();
            CachedId c = ids.get(alias);
            if (c != null && c.until().isAfter(now)) return c.id();
            Optional<Long> id = hub.discordIdOf(alias);
            ids.put(alias, new CachedId(id, now.plus(IDS_KEPT)));
            return id;
        }

        /** A hub event (runeclans-hub API.md section 4) as the bot's own ingest would have stored it; the key is the hub's event id. */
        static PluginPayload.Event toEvent(DataObject e) {
            PluginEventType type = PluginEventType.parse(e.getString("type", null));
            Instant at;
            try {
                at = Instant.parse(e.getString("occurredAt", ""));
            } catch (RuntimeException ex) {
                return null;
            }
            if (type == null || !e.hasKey("data") || e.isNull("data")) return null;
            String sourceKind = null;
            String sourceName = null;
            Long sourceId = null;
            if (e.hasKey("source") && !e.isNull("source")) {
                DataObject s = e.getObject("source");
                sourceKind = s.getString("kind", null);
                sourceName = s.getString("name", null);
                if (s.hasKey("id") && !s.isNull("id")) sourceId = s.getLong("id");
            }
            return new PluginPayload.Event("hub:" + e.getString("id", ""), type, at, sourceKind, sourceName, sourceId, e.getObject("data").toString());
        }
    }
}
