package com.runeclans.bot.access;

import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Who is an overall RuneClans admin, a RuneClan Admin (N5, runeclans.com/admin). The RuneClans hub keeps the list (T-981, its
 * {@code GET /v1/admins}): its built-in admins ({@code HUB_OWNER_DISCORD_IDS}) and the people they added by Discord user id on the website.
 * The bot's own owners ({@link BotOwners}: the Discord application's owner or team, plus {@code OWNER_IDS}) count as built-in too. Overall
 * admins use the whole admin panel and may open and change every clan site, its admin panel and its permissions. The list is changed only
 * on the website (RuneClan Admin > Admins), never here.
 * <p>
 * The hub's list is remembered for {@link #CACHE_MILLIS}; once it is older, the last known list answers while a fresh one is read in the
 * background, so a request never waits on the hub after the first read. A failed read keeps the last known list (a hub blip never makes
 * every overall admin a stranger, and never adds anyone); with the hub client off, only the bot's owners are admins.
 * <p>
 * The hub's {@code admins.changed} feed entry ({@link AdminListFeed}, B-32) reads the list again at once. After a removal the read is made
 * there and then, and if it fails the added admins are not trusted until a read works (the built-in ones still are): the entry doesn't say
 * who was removed, and a removed admin (perhaps a stolen account) must not keep access while the hub is unreachable.
 */
@BService
public class OverallAdmins {
    private static final Logger log = LoggerFactory.getLogger(OverallAdmins.class);
    static final long CACHE_MILLIS = 30_000;
    static final long RETRY_MILLIS = 5_000;

    /** The hub's list, as Discord ids. */
    public record HubList(Set<Long> builtIn, Set<Long> added) {
        public static final HubList EMPTY = new HubList(Set.of(), Set.of());
    }

    /** Where the list comes from; throws when it can't be read. */
    @FunctionalInterface
    public interface Source {
        HubList read();
    }

    private final BotOwners owners;
    /** Replaceable so tests can use a fake hub, move time and refresh in line. */
    Source source;
    LongSupplier clock = System::currentTimeMillis;
    Executor refresher = BotScheduler.lane("overall-admins");
    private volatile HubList known = HubList.EMPTY;
    private volatile boolean loaded;
    private volatile long freshUntil;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Object firstLoad = new Object();
    /** Bumped by a removal ({@link #changed}); a read started before it is dropped, so it can't put a removed admin back. */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    public OverallAdmins(RuneClansHubClient hub, BotOwners owners) {
        this.owners = owners;
        this.source = fromHub(hub);
    }

    /** For tests: the list from {@code source}, refreshed in line. */
    public static OverallAdmins of(Source source, BotOwners owners) {
        OverallAdmins admins = new OverallAdmins(null, owners);
        admins.source = source;
        admins.refresher = Runnable::run;
        return admins;
    }

    /** Whether {@code userId} is an overall admin: built in, or added on the website. */
    public boolean isOverallAdmin(JDA jda, long userId) {
        if (userId <= 0) return false;
        return isBuiltIn(jda, userId) || list().added().contains(userId);
    }

    /** Whether {@code userId} is a built-in overall admin: one of the bot's owners or of the hub's. */
    public boolean isBuiltIn(JDA jda, long userId) {
        return userId > 0 && (owners.isOwner(jda, userId) || list().builtIn().contains(userId));
    }

    /** Reads the hub's list again on the next question (after a change the bot heard of). */
    public void forget() {
        freshUntil = 0;
    }

    /**
     * The hub's list changed ({@code admins.changed}, B-32). After an add or a note change the next question reads it again; after a removal
     * it is read now, on the caller's thread (the feed's lane, never a web request), and a failed read stops trusting the added admins until
     * one works. Returns whether a fresh list was read.
     */
    public boolean changed(boolean removed) {
        if (!removed) {
            forget();
            return false;
        }
        synchronized (firstLoad) {
            generation.incrementAndGet();
            try {
                known = source.read();
                freshUntil = clock.getAsLong() + CACHE_MILLIS;
                loaded = true;
                return true;
            } catch (RuntimeException e) {
                log.warn("Couldn't read the RuneClan Admins after a removal ({}); only the built-in admins count until the hub answers.", e.getMessage());
                known = new HubList(known.builtIn(), Set.of());
                freshUntil = clock.getAsLong() + RETRY_MILLIS;
                return false;
            }
        }
    }

    private HubList list() {
        if (!loaded) {
            // One thread reads the hub the first time; the others wait for its answer instead of all calling.
            synchronized (firstLoad) {
                if (!loaded) refresh();
            }
            return known;
        }
        if (clock.getAsLong() >= freshUntil && refreshing.compareAndSet(false, true)) {
            try {
                refresher.execute(() -> {
                    try {
                        refresh();
                    } finally {
                        refreshing.set(false);
                    }
                });
            } catch (RuntimeException e) {
                refreshing.set(false);
            }
        }
        return known;
    }

    private void refresh() {
        long started = generation.get();
        HubList read;
        try {
            read = source.read();
        } catch (RuntimeException e) {
            log.warn("Couldn't read the RuneClan Admins from the hub ({}); keeping the list already known.", e.getMessage());
            synchronized (firstLoad) {
                if (generation.get() == started) freshUntil = clock.getAsLong() + RETRY_MILLIS;
                loaded = true;
            }
            return;
        }
        synchronized (firstLoad) {
            // a removal while this read was on its way already read a newer list: this one may still hold the removed admin
            if (generation.get() == started) {
                known = read;
                freshUntil = clock.getAsLong() + CACHE_MILLIS;
            }
            loaded = true;
        }
    }

    /** {@code GET /v1/admins} with the bot's first-party key: tried once, so a down hub costs one timeout, not the retries. */
    static Source fromHub(RuneClansHubClient hub) {
        return () -> {
            if (hub == null || !hub.enabled()) return HubList.EMPTY;
            RuneClansHubClient.Answer answer = RuneClansHubClient.withoutRetries(() -> hub.call("GET", "/v1/admins", null));
            if (answer == null || !answer.ok() || answer.json() == null) {
                throw new IllegalStateException(answer == null ? "no answer" : "status " + answer.status());
            }
            return read(answer.json());
        };
    }

    /** {@code {builtIn: [id], admins: [id]}}; anything that isn't a Discord id is left out. */
    static HubList read(DataObject json) {
        return new HubList(ids(json.optArray("builtIn").orElse(DataArray.empty())), ids(json.optArray("admins").orElse(DataArray.empty())));
    }

    private static Set<Long> ids(DataArray array) {
        Set<Long> out = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String raw = array.getString(i, "");
            if (raw.matches("[0-9]{15,20}")) {
                try {
                    out.add(Long.parseLong(raw));
                } catch (NumberFormatException ignored) {
                    // past a long: not a Discord id
                }
            }
        }
        return Set.copyOf(out);
    }
}
