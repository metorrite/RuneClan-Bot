package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.runeclans.bot.runescape.TrackedNames;
import com.runeclans.bot.runescape.stats.HubRefresh;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The Refresh button on a player's runeclans.com profile (Drake 2026-11-02): {@code POST player-refresh {rsn, waitMinutes, discordId}} asks the
 * hub to poll the player now. The hub decides the wait (its own {@code player-refresh-minutes}, per player and per person) and answers in the
 * shapes the website reads:
 * <ul>
 *   <li>200 {@code {rsn, polled: false, status: QUEUED, nextAt, waitMinutes}}: queued; the poll lands within a minute and the page shows it on
 *       reload (the cached profile is dropped when the hub's {@code stats.updated} arrives)</li>
 *   <li>429 {@code {error, code, nextAt, waitMinutes}}: too soon for this player; {@code {error}} alone when the whole site asked for more than
 *       {@value #GLOBAL_PER_MINUTE} refreshes in a minute</li>
 *   <li>404 for a name {@code GET players/{rsn}} doesn't show (a hidden member, a private clan's member), a name the bot doesn't track, or one
 *       the hub doesn't track: the same answer as a made-up name, so nothing is polled for a name nobody linked</li>
 *   <li>400 for a name that isn't a RuneScape name; 503 when the hub can't be asked</li>
 * </ul>
 * Names in, no Discord identity read, like the rest of the directory.
 */
@BService
public class DirectoryPlayerRefreshRoutes implements DirectoryRouteModule {
    private static final Logger log = LoggerFactory.getLogger(DirectoryPlayerRefreshRoutes.class);

    static final int MIN_MINUTES = 5;
    static final int MAX_MINUTES = 1440;
    static final int DEFAULT_MINUTES = 30;
    /** The most refreshes the whole site may start in one minute, whoever asks: the hub's budget belongs to every tier. */
    static final int GLOBAL_PER_MINUTE = 5;

    /** What the route needs from the rest of the bot; the real one reads the database, a test passes its own. */
    interface Deps {
        /** When the player's newest stats snapshot was taken, or null when they were never polled. */
        Instant lastPoll(String rsn);

        /** Whether the bot tracks this name at all ({@link TrackedNames#tracked}). */
        boolean tracked(String rsn);

        /** Whether {@code GET players/{rsn}} shows this name ({@link DirectoryPlayerRoutes#shown}). */
        boolean shown(String rsn);

        /** The hub's Refresh for {@code rsn} on behalf of {@code discordId} (the website's signed-in user, null when unknown). */
        default HubRefresh.Result hubRefresh(String rsn, String discordId) {
            return new HubRefresh.Result(HubRefresh.Outcome.UNAVAILABLE, 0, "no_hub");
        }
    }

    /** A status and body to send. */
    record Reply(int status, DataObject body) {
    }

    private final Refresher refresher;

    public DirectoryPlayerRefreshRoutes(PlayerLinkRepository links, TrackedNames tracker,
                                        DirectoryPlayerRoutes players, RuneClansHubClient hub) {
        this.refresher = new Refresher(new Deps() {
            @Override
            public HubRefresh.Result hubRefresh(String rsn, String discordId) {
                long id = discordId != null && discordId.matches("\\d{17,20}") ? Long.parseLong(discordId) : 0L;
                return HubRefresh.ask(hub, rsn, id);
            }

            @Override
            public Instant lastPoll(String rsn) {
                StatsSnapshotRow latest = links.getLatestSnapshots(List.of(rsn)).get(rsn.toLowerCase(Locale.ROOT));
                return latest == null ? null : latest.snapshotAt().toInstant();
            }

            @Override
            public boolean tracked(String rsn) {
                return tracker.tracked(rsn);
            }

            @Override
            public boolean shown(String rsn) {
                return players.shown(rsn);
            }

        }, Clock.systemUTC());
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        if (!request.head().equals("player-refresh") || request.parts().length != 1) return false;
        if (!request.requirePost()) return true;
        DataObject body = request.body("That isn't a refresh request.");
        if (body == null) return true;
        Reply reply = refresher.refresh(body);
        request.send(reply.status(), reply.body());
        return true;
    }

    /** The refresh rules over {@link Deps} and a clock, so a test can drive them without the HTTP server. */
    static final class Refresher {
        private final Deps deps;
        private final Clock clock;
        private final Object lock = new Object();
        private final Deque<Instant> recent = new ArrayDeque<>();

        Refresher(Deps deps, Clock clock) {
            this.deps = deps;
            this.clock = clock;
        }

        /** The answer to one refresh request's body. */
        Reply refresh(DataObject body) {
            String rsn = DirectoryPlayerLogic.cleanRsn(body.getString("rsn", null));
            if (rsn == null) return error(400, "That isn't a RuneScape name.");
            Duration wait = Duration.ofMinutes(waitMinutes(body.getString("waitMinutes", null)));
            Instant now = clock.instant();
            // a name nobody linked is never polled, and a name the profile page hides stays hidden: both look like a name nobody knows
            if (deps.lastPoll(rsn) == null ? !deps.tracked(rsn) : !deps.shown(rsn)) return error(404, "No player by that name.");
            // the bot's own site-wide cap (the hub's is wider): a flood from the website never reaches the hub
            synchronized (lock) {
                if (!roomForOneMore(now)) return error(429, "Lots of players are refreshing right now. Try again in a minute.");
            }
            HubRefresh.Result r = deps.hubRefresh(rsn, body.getString("discordId", null));
            // the cached profile is dropped when the poll lands (the feed's stats.updated, HubStatsChanges), not now while it still shows the old numbers
            return switch (r.outcome()) {
                case QUEUED -> new Reply(200, DataObject.empty().put("rsn", rsn).put("polled", false).put("status", "QUEUED").put("nextAt", now.plus(wait).toString())
                        .put("waitMinutes", wait.toMinutes()));
                case WAIT -> new Reply(429, DataObject.empty().put("error", HubRefresh.describe(r, rsn).replace("**", "")).put("code", r.code() == null ? "refresh_wait" : r.code())
                        .put("nextAt", now.plusSeconds(r.retryAfterSeconds()).toString()).put("waitMinutes", wait.toMinutes()));
                case UNKNOWN -> error(404, "RuneClans doesn't track that player: not synced yet, or its owner removed their data.");
                case UNAVAILABLE -> error(503, "Couldn't refresh right now. Try again.");
            };
        }

        /** Whether the site-wide cap has room for one more refresh this minute; counts it when it has. Call while holding {@code lock}. */
        private boolean roomForOneMore(Instant now) {
            Instant minuteAgo = now.minus(Duration.ofMinutes(1));
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(minuteAgo)) recent.pollFirst();
            if (recent.size() >= GLOBAL_PER_MINUTE) return false;
            recent.addLast(now);
            return true;
        }

        private static Reply error(int status, String message) {
            return new Reply(status, DataObject.empty().put("error", message));
        }
    }

    /** The website's wait in whole minutes, kept in range; anything that isn't a number gives the default. */
    static int waitMinutes(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,6}")) return DEFAULT_MINUTES;
        return (int) Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, Long.parseLong(raw.strip())));
    }
}
