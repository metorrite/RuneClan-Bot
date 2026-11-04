package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.runeclans.bot.runescape.RosterPollScheduler;
import com.runeclans.bot.runescape.polling.PollCoordinator;
import com.runeclans.bot.runescape.polling.PollOutcome;
import com.runeclans.bot.runescape.polling.PollRequest;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Refresh button on a player's runeclans.com profile (Drake 2026-11-02): {@code POST player-refresh {rsn, waitMinutes}} polls the player
 * now, at the front of the queue, unless they were polled or refreshed less than {@code waitMinutes} ago (RuneClan Admin > Switches,
 * {@code player-refresh-minutes}: the website reads it and sends it; kept within {@value #MIN_MINUTES} to {@value #MAX_MINUTES}, default
 * {@value #DEFAULT_MINUTES}). Answers
 * <ul>
 *   <li>200 {@code {rsn, polled, status, nextAt, waitMinutes}}: queued; {@code polled} is true when fresh stats were saved within
 *       {@link #WAIT_FOR_POLL} (the website gives up after 8 s and the internal API has few workers, so a slower poll carries on in the queue
 *       and the page shows it on reload)</li>
 *   <li>429 {@code {error, nextAt, waitMinutes}}: too soon for this player; {@code {error}} alone when the whole site asked for more than
 *       {@value #GLOBAL_PER_MINUTE} refreshes in a minute</li>
 *   <li>404 for a name {@code GET players/{rsn}} doesn't show (a hidden member, a private clan's member) or a name the bot doesn't track,
 *       the same answer as a made-up name, so nothing is polled for a name nobody linked</li>
 *   <li>400 for a name that isn't a RuneScape name</li>
 * </ul>
 * Names in, no Discord identity read, like the rest of the directory. The wait counts from whichever is later: the player's newest snapshot
 * (any poll, scheduled or not) or the last Refresh asked here (so a profile RuneMetrics can't read can't be refreshed over and over). A Refresh
 * whose poll failed or never ran is forgotten, so the player can try again. That memory lives in the bot's process and is dropped after the
 * longest wait.
 */
@BService
public class DirectoryPlayerRefreshRoutes implements DirectoryRouteModule {
    private static final Logger log = LoggerFactory.getLogger(DirectoryPlayerRefreshRoutes.class);

    static final int MIN_MINUTES = 5;
    static final int MAX_MINUTES = 1440;
    static final int DEFAULT_MINUTES = 30;
    static final Duration WAIT_FOR_POLL = Duration.ofSeconds(2);
    /** The most refreshes the whole site may start in one minute, whoever asks: RuneMetrics' budget belongs to every tier. */
    static final int GLOBAL_PER_MINUTE = 5;

    /** What the route needs from the rest of the bot; the real one reads the database, a test passes its own. */
    interface Deps {
        /** When the player's newest stats snapshot was taken, or null when they were never polled. */
        Instant lastPoll(String rsn);

        /** Whether the bot tracks this name at all ({@link RosterPollScheduler#tracked}). */
        boolean tracked(String rsn);

        /** Whether {@code GET players/{rsn}} shows this name ({@link DirectoryPlayerRoutes#shown}). */
        boolean shown(String rsn);

        /** Queues the poll at the front. */
        CompletableFuture<PollOutcome> poll(String rsn);

        /** The poll finished (any outcome): drop the cached profile so the page shows it. */
        void polled(String rsn);

        /** True while the hub polls instead of the bot (T-851): the refresh goes to the hub's own Refresh. */
        default boolean hubPolls() {
            return false;
        }

        /** The hub's Refresh for {@code rsn} on behalf of {@code discordId} (the website's signed-in user, null when unknown). */
        default HubRefresh.Result hubRefresh(String rsn, String discordId) {
            return new HubRefresh.Result(HubRefresh.Outcome.UNAVAILABLE, 0, "no_hub");
        }
    }

    /** A status and body to send. */
    record Reply(int status, DataObject body) {
    }

    private final Refresher refresher;

    public DirectoryPlayerRefreshRoutes(PlayerLinkRepository links, PollCoordinator polls, SiteCache cache, RosterPollScheduler tracker,
                                        DirectoryPlayerRoutes players, RuneClansHubClient hub) {
        this.refresher = new Refresher(new Deps() {
            @Override
            public boolean hubPolls() {
                return polls.hubPolls();
            }

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

            @Override
            public CompletableFuture<PollOutcome> poll(String rsn) {
                return polls.submit(PollRequest.interactive(rsn, "profile refresh"));
            }

            @Override
            public void polled(String rsn) {
                cache.invalidate(DirectoryPlayerRoutes.CACHE_PREFIX + rsn.toLowerCase(Locale.ROOT));
            }
        }, Clock.systemUTC(), WAIT_FOR_POLL);
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
        private final Duration waitForPoll;
        private final Map<String, Instant> asked = new ConcurrentHashMap<>();
        private final Deque<Instant> recent = new ArrayDeque<>();

        Refresher(Deps deps, Clock clock, Duration waitForPoll) {
            this.deps = deps;
            this.clock = clock;
            this.waitForPoll = waitForPoll;
        }

        /** The answer to one refresh request's body. */
        Reply refresh(DataObject body) {
            String rsn = DirectoryPlayerLogic.cleanRsn(body.getString("rsn", null));
            if (rsn == null) return error(400, "That isn't a RuneScape name.");
            Duration wait = Duration.ofMinutes(waitMinutes(body.getString("waitMinutes", null)));
            String key = rsn.toLowerCase(Locale.ROOT);
            Instant now = clock.instant();
            if (deps.hubPolls()) return viaHub(rsn, body.getString("discordId", null), wait, now);
            asked.values().removeIf(at -> at.isBefore(now.minus(Duration.ofMinutes(MAX_MINUTES))));

            Instant lastPoll = deps.lastPoll(rsn);
            // a name nobody linked is never polled, and a name the profile page hides stays hidden: both look like a name nobody knows
            if (lastPoll == null ? !deps.tracked(rsn) : !deps.shown(rsn)) return error(404, "No player by that name.");

            Instant next;
            synchronized (asked) {
                next = nextAt(lastPoll, asked.get(key), wait, now);
                if (next == null) {
                    if (!roomForOneMore(now)) return error(429, "Lots of players are refreshing right now. Try again in a minute.");
                    asked.put(key, now);
                }
            }
            if (next != null) {
                return new Reply(429, DataObject.empty().put("error", "This player was updated recently. Try again later.").put("nextAt", next.toString())
                        .put("waitMinutes", wait.toMinutes()));
            }

            CompletableFuture<PollOutcome> poll;
            try {
                poll = deps.poll(rsn);
            } catch (RuntimeException e) {
                log.warn("Couldn't queue a profile refresh for {}", rsn, e);
                asked.remove(key, now);
                return error(503, "Couldn't refresh right now. Try again.");
            }
            poll.whenComplete((outcome, error) -> {
                deps.polled(rsn);
                if (error != null || outcome == null || outcome.status() == PollOutcome.Status.FAILED || outcome.status() == PollOutcome.Status.CANCELLED) {
                    // the poll never got an answer: let the player try again rather than lock them out for the whole wait
                    asked.remove(key, now);
                    if (error != null) log.warn("Profile refresh for {} failed", rsn, error);
                    else log.info("Profile refresh for {} ended {}", rsn, outcome == null ? "with no outcome" : outcome.status());
                }
            });
            PollOutcome outcome;
            try {
                outcome = poll.get(waitForPoll.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                outcome = PollOutcome.timedOut();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcome = PollOutcome.timedOut();
            } catch (ExecutionException e) {
                outcome = PollOutcome.failed(0);
            }
            return new Reply(200, DataObject.empty().put("rsn", rsn).put("polled", outcome.hasFreshData()).put("status", outcome.status().name())
                    .put("nextAt", now.plus(wait).toString()).put("waitMinutes", wait.toMinutes()));
        }

        /**
         * While the hub polls (T-851): the hub's Refresh decides the wait (its own {@code player-refresh-minutes}, per player and per person), and
         * answers in the same shapes the website reads: 200 queued ({@code polled} false, the poll lands within a minute), 429 with {@code nextAt},
         * 404 for a name the hub doesn't track (or the bot's own refusals: never tracked, or hidden), 503 when the hub can't be asked.
         */
        private Reply viaHub(String rsn, String discordId, Duration wait, Instant now) {
            // the same two refusals as the bot's own path: a name nobody tracks, and a name the profile page hides, both look unknown
            if (deps.lastPoll(rsn) == null ? !deps.tracked(rsn) : !deps.shown(rsn)) return error(404, "No player by that name.");
            HubRefresh.Result r = deps.hubRefresh(rsn, discordId);
            // the cached profile is dropped when the poll lands (the feed's stats.updated, HubStatsChanges), not now while it still shows the old numbers
            return switch (r.outcome()) {
                case QUEUED -> new Reply(200, DataObject.empty().put("rsn", rsn).put("polled", false).put("status", "QUEUED").put("nextAt", now.plus(wait).toString())
                        .put("waitMinutes", wait.toMinutes()));
                case WAIT -> new Reply(429, DataObject.empty().put("error", "This player was updated recently. Try again later.")
                        .put("nextAt", now.plusSeconds(r.retryAfterSeconds()).toString()).put("waitMinutes", wait.toMinutes()));
                case UNKNOWN -> error(404, "No player by that name.");
                case UNAVAILABLE -> error(503, "Couldn't refresh right now. Try again.");
            };
        }

        /** Whether the site-wide cap has room for one more refresh this minute; counts it when it has. Call while holding {@code asked}. */
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

    /** When the next Refresh is allowed, or null when it is allowed now: {@code wait} after the later of the last poll and the last Refresh. */
    static Instant nextAt(Instant lastPoll, Instant lastAsked, Duration wait, Instant now) {
        Instant last = lastPoll == null ? lastAsked : lastAsked == null || lastPoll.isAfter(lastAsked) ? lastPoll : lastAsked;
        if (last == null) return null;
        Instant next = last.plus(wait);
        return now.isBefore(next) ? next : null;
    }
}
