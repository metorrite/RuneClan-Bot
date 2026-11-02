package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.runeclans.bot.runescape.polling.PollCoordinator;
import com.runeclans.bot.runescape.polling.PollOutcome;
import com.runeclans.bot.runescape.polling.PollRequest;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The Refresh button on a player's runeclans.com profile (Drake 2026-11-02): {@code POST player-refresh {rsn, waitMinutes}} polls the player
 * now, at the front of the queue, unless they were polled or refreshed less than {@code waitMinutes} ago (RuneClan Admin > Switches,
 * {@code player-refresh-minutes}: the website reads it and sends it; kept within {@value #MIN_MINUTES} to {@value #MAX_MINUTES}, default
 * {@value #DEFAULT_MINUTES}). Answers
 * <ul>
 *   <li>200 {@code {rsn, polled, status, nextAt, waitMinutes}}: queued; {@code polled} is true when fresh stats were saved within
 *       {@link #WAIT_FOR_POLL} (the website gives up after 8 s, so a slower poll carries on and the page shows it on reload)</li>
 *   <li>429 {@code {error, nextAt, waitMinutes}}: too soon</li>
 *   <li>400 for a name that isn't a RuneScape name</li>
 * </ul>
 * Names in, no Discord identity read, like the rest of the directory. The wait counts from whichever is later: the player's newest snapshot
 * (any poll, scheduled or not) or the last Refresh asked here (so a profile RuneMetrics can't read can't be refreshed over and over). That
 * memory lives in the bot's process and is dropped after the longest wait.
 */
@BService
public class DirectoryPlayerRefreshRoutes implements DirectoryRouteModule {
    static final int MIN_MINUTES = 5;
    static final int MAX_MINUTES = 1440;
    static final int DEFAULT_MINUTES = 30;
    static final Duration WAIT_FOR_POLL = Duration.ofSeconds(5);

    private final PlayerLinkRepository links;
    private final PollCoordinator polls;
    private final SiteCache cache;
    private final Map<String, Instant> asked = new ConcurrentHashMap<>();

    public DirectoryPlayerRefreshRoutes(PlayerLinkRepository links, PollCoordinator polls, SiteCache cache) {
        this.links = links;
        this.polls = polls;
        this.cache = cache;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        if (!request.head().equals("player-refresh") || request.parts().length != 1) return false;
        if (!request.requirePost()) return true;
        DataObject body = request.body("That isn't a refresh request.");
        if (body == null) return true;
        String rsn = DirectoryPlayerLogic.cleanRsn(body.getString("rsn", null));
        if (rsn == null) {
            request.sendError(400, "That isn't a RuneScape name.");
            return true;
        }
        Duration wait = Duration.ofMinutes(waitMinutes(body.getString("waitMinutes", null)));
        String key = rsn.toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        asked.values().removeIf(at -> at.isBefore(now.minus(Duration.ofMinutes(MAX_MINUTES))));

        StatsSnapshotRow latest = links.getLatestSnapshots(List.of(rsn)).get(key);
        Instant next;
        synchronized (asked) {
            next = nextAt(latest == null ? null : latest.snapshotAt().toInstant(), asked.get(key), wait, now);
            if (next == null) asked.put(key, now);
        }
        if (next != null) {
            request.send(429, DataObject.empty().put("error", "This player was updated recently. Try again later.").put("nextAt", next.toString())
                    .put("waitMinutes", wait.toMinutes()));
            return true;
        }

        CompletableFuture<PollOutcome> poll = polls.submit(PollRequest.interactive(rsn, "profile refresh"));
        poll.whenComplete((outcome, error) -> cache.invalidate(DirectoryPlayerRoutes.CACHE_PREFIX + key));
        PollOutcome outcome;
        try {
            outcome = poll.get(WAIT_FOR_POLL.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            outcome = PollOutcome.timedOut();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = PollOutcome.timedOut();
        } catch (java.util.concurrent.ExecutionException e) {
            outcome = PollOutcome.failed(0);
        }
        request.send(200, DataObject.empty().put("rsn", rsn).put("polled", outcome.hasFreshData()).put("status", outcome.status().name())
                .put("nextAt", now.plus(wait).toString()).put("waitMinutes", wait.toMinutes()));
        return true;
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
