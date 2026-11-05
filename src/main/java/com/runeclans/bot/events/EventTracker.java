package com.runeclans.bot.events;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.RuneScapeStatsService;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Feeds running competitions from the players' linked RuneScape accounts. Every few minutes it asks the shared polling queue for a recent
 * snapshot of each player (a snapshot that is already fresh is reused, nothing extra goes out), takes the first reading as the player's
 * starting point, and adds the XP gained since to the event as {@link Source#IMPORT} progress. Runs only where the other automatic
 * polling runs ({@code RUNESCAPE_AUTO_POLL_ENABLED}), so a development copy of the database stays quiet.
 * <p>
 * Limits worth knowing: gains show up a few minutes late, the starting point is the first reading after the event is seen running (or after
 * a player joins), and a player with no linked account is skipped until they link one. Competitions that count from RuneSync
 * ({@code xpSource = plugin}) are left to {@link PluginXpTracker}. In auto competitions ({@code xpSource = auto}, T-557) the players RuneSync
 * counts are left to it too, but their RuneMetrics gain is still read and handed to {@link PluginXpTracker#check} to compare.
 */
@BService
public class EventTracker {
    private static final Logger log = LoggerFactory.getLogger(EventTracker.class);
    private static final Duration PERIOD = Duration.ofMinutes(5);

    private final EventRepository repository;
    private final EventService service;
    private final PlayerLinkRepository links;
    private final RuneScapeStatsService stats;
    private final BotConfig config;
    private final PluginXpTracker pluginXp;
    private final ScheduledExecutorService executor = BotScheduler.lane("event-tracker");

    public EventTracker(EventRepository repository, EventService service, PlayerLinkRepository links, RuneScapeStatsService stats, BotConfig config,
                        PluginXpTracker pluginXp) {
        this.repository = repository;
        this.service = service;
        this.links = links;
        this.stats = stats;
        this.config = config;
        this.pluginXp = pluginXp;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!config.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled, so running competitions will not be fed automatically.");
            return;
        }
        executor.scheduleWithFixedDelay(this::sweep, 2, PERIOD.toMinutes(), TimeUnit.MINUTES);
    }

    /** Every running competition, including clan-vs-clan events whose clans play one (T-222). */
    private List<Event> competitions() {
        List<Event> all = new java.util.ArrayList<>(repository.runningEvents(CompetitionTemplate.KEY));
        for (Event e : repository.runningEvents(ClanVsClan.KEY)) if (ClanVsClan.plays(e, CompetitionTemplate.KEY)) all.add(e);
        return all;
    }

    /** One pass over every running competition. Never throws: one event's trouble must not stop the next. */
    public void sweep() {
        try {
            for (Event event : competitions()) {
                try {
                    feed(event);
                } catch (RuntimeException e) {
                    log.warn("Could not feed competition {} in guild {}", event.id(), event.guildId(), e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("The competition sweep failed", e);
        }
    }

    /** Brings one competition up to date: reads its players' latest snapshots, then adds each player's new gain. Returns how many players got new progress. */
    public int feed(Event event) {
        DataObject settings = DataObject.fromJson(event.config());
        if (CompetitionTemplate.countsFromPlugin(settings)) return 0; // counted from RuneSync by PluginXpTracker instead
        int skill = CompetitionTemplate.skillOf(settings);
        Map<Participant, String> players = new LinkedHashMap<>();
        for (Participant p : repository.participants(event.id())) {
            if (p.waiting()) continue;
            List<PlayerLink> linked = links.getLinksForUser(p.guildId(), p.userId());
            if (!linked.isEmpty()) players.put(p, linked.get(0).rsn());
        }
        if (players.isEmpty()) return 0;

        // the hub keeps every tracked player current (the bot polls nobody), so the latest snapshots are read as they are
        // what has already been added from tracking, per player, read from the progress log so there is nothing separate to fall out of step
        List<EventRepository.Progress> progress = repository.progress(event.id());
        Map<Long, Long> counted = new java.util.HashMap<>();
        for (EventRepository.Progress p : progress) {
            if (p.source() == Source.IMPORT && p.objectiveKey().equals(CompetitionTemplate.OBJECTIVE_KEY)) counted.merge(p.userId(), p.amount(), Long::sum);
        }
        boolean auto = CompetitionTemplate.countsAuto(settings);
        Map<Long, CompetitionTracking.Counter> owners = auto ? CompetitionTracking.owners(progress) : Map.of();
        java.util.Set<Long> runeSync = auto ? pluginXp.runeSyncPlayers(event.id()) : java.util.Set.of();
        int updated = 0;
        for (Map.Entry<Participant, String> entry : players.entrySet()) {
            long userId = entry.getKey().userId();
            CompetitionTracking.Counter owner = owners.get(userId);
            boolean theirs = owner == CompetitionTracking.Counter.RUNESYNC || owner == null && runeSync.contains(userId);
            if (feedPlayer(event, skill, entry.getKey(), entry.getValue(), counted.getOrDefault(userId, 0L), theirs)) updated++;
        }
        return updated;
    }

    /** Adds a player's new gain; with {@code checkOnly} (an auto competition's RuneSync player) only compares it with what RuneSync counted. */
    private boolean feedPlayer(Event event, int skill, Participant player, String rsn, long alreadyCounted, boolean checkOnly) {
        var snapshot = stats.getLatestSnapshot(rsn);
        if (snapshot == null) return false;
        OptionalLong xp = CompetitionTracking.xpOf(skill, snapshot.totalXp(), stats.getSkillsForSnapshot(snapshot.snapshotId()));
        if (xp.isEmpty()) return false;

        OptionalLong baseline = repository.baseline(event.id(), player.userId());
        if (baseline.isEmpty()) {
            repository.setBaseline(event.id(), player.userId(), xp.getAsLong());
            return false;
        }
        if (checkOnly) {
            pluginXp.check(event.id(), player.userId(), alreadyCounted, Math.max(0, xp.getAsLong() - baseline.getAsLong()));
            return false;
        }
        long add = CompetitionTracking.toRecord(baseline.getAsLong(), xp.getAsLong(), alreadyCounted);
        if (add == 0) return false;
        try {
            service.recordProgress(event.guildId(), event.id(), CompetitionTemplate.OBJECTIVE_KEY, player.userId(), add, Source.IMPORT,
                    "xp:" + player.userId() + ":" + snapshot.snapshotId(), null, "Tracked from " + rsn);
        } catch (EventProblem e) {
            // already complete, or the player isn't scored yet (no team): nothing to add right now; try again next time unless it is full
            if (e.status() != 409) log.warn("Competition {}: could not add progress for {}: {}", event.id(), player.userId(), e.getMessage());
            return false;
        }
        return true;
    }
}
