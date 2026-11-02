package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.polling.PollOutcome;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Refresh button on a player profile ({@link DirectoryPlayerRefreshRoutes}): the website's minutes kept in range, the clock, who may be
 * refreshed at all, the site-wide cap, and a failed poll not locking the player out.
 */
class DirectoryPlayerRefreshRoutesTest {
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final Duration WAIT = Duration.ofMinutes(30);

    @Test
    void theWebsitesMinutesAreKeptInRange() {
        assertEquals(30, DirectoryPlayerRefreshRoutes.waitMinutes(null));
        assertEquals(30, DirectoryPlayerRefreshRoutes.waitMinutes("soon"));
        assertEquals(30, DirectoryPlayerRefreshRoutes.waitMinutes("-5"));
        assertEquals(90, DirectoryPlayerRefreshRoutes.waitMinutes(" 90 "));
        assertEquals(DirectoryPlayerRefreshRoutes.MIN_MINUTES, DirectoryPlayerRefreshRoutes.waitMinutes("0"));
        assertEquals(DirectoryPlayerRefreshRoutes.MAX_MINUTES, DirectoryPlayerRefreshRoutes.waitMinutes("99999"));
    }

    @Test
    void aPlayerNeverPolledOrRefreshedCanBeRefreshedAtOnce() {
        assertNull(DirectoryPlayerRefreshRoutes.nextAt(null, null, WAIT, NOW));
    }

    @Test
    void theWaitCountsFromTheLastPoll() {
        assertEquals(NOW.plusSeconds(60), DirectoryPlayerRefreshRoutes.nextAt(NOW.minus(WAIT).plusSeconds(60), null, WAIT, NOW));
        assertNull(DirectoryPlayerRefreshRoutes.nextAt(NOW.minus(WAIT), null, WAIT, NOW), "exactly the wait later is allowed");
        assertNull(DirectoryPlayerRefreshRoutes.nextAt(NOW.minus(Duration.ofHours(5)), null, WAIT, NOW));
    }

    @Test
    void aRefreshThatSavedNothingStillStartsTheWait() {
        // RuneMetrics couldn't read the profile, so the newest snapshot is old, but the last press was 10 minutes ago
        Instant oldPoll = NOW.minus(Duration.ofDays(2));
        Instant pressed = NOW.minus(Duration.ofMinutes(10));
        assertEquals(pressed.plus(WAIT), DirectoryPlayerRefreshRoutes.nextAt(oldPoll, pressed, WAIT, NOW));
        assertEquals(pressed.plus(WAIT), DirectoryPlayerRefreshRoutes.nextAt(null, pressed, WAIT, NOW));
    }

    @Test
    void aShorterWaitSetInTheSwitchesAppliesAtOnce() {
        Instant polled = NOW.minus(Duration.ofMinutes(10));
        assertEquals(polled.plus(WAIT), DirectoryPlayerRefreshRoutes.nextAt(polled, null, WAIT, NOW));
        assertNull(DirectoryPlayerRefreshRoutes.nextAt(polled, null, Duration.ofMinutes(5), NOW));
    }

    @Test
    void theWebsitesMinutesAtTheEdgesOfTheRange() {
        assertEquals(5, DirectoryPlayerRefreshRoutes.waitMinutes("4"));
        assertEquals(5, DirectoryPlayerRefreshRoutes.waitMinutes("5"));
        assertEquals(1440, DirectoryPlayerRefreshRoutes.waitMinutes("1440"));
        assertEquals(1440, DirectoryPlayerRefreshRoutes.waitMinutes("1441"));
        assertEquals(30, DirectoryPlayerRefreshRoutes.waitMinutes("1234567"), "more than six digits isn't a number we take");
        assertEquals(30, DirectoryPlayerRefreshRoutes.waitMinutes("1.5"));
    }

    /** A clock the test moves by hand. */
    private static final class Hand extends Clock {
        Instant now = NOW;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The bot as the route sees it: who is tracked and shown, their last poll, and the polls asked for. */
    private static final class Fake implements DirectoryPlayerRefreshRoutes.Deps {
        final Set<String> tracked = new HashSet<>();
        final Set<String> shown = new HashSet<>();
        final Map<String, Instant> lastPoll = new HashMap<>();
        final List<String> polls = new ArrayList<>();
        final List<String> invalidated = new ArrayList<>();
        /** What the next poll answers; null leaves it running (the caller stops waiting). */
        PollOutcome answer = null;
        boolean queueFails = false;

        @Override
        public Instant lastPoll(String rsn) {
            return lastPoll.get(rsn);
        }

        @Override
        public boolean tracked(String rsn) {
            return tracked.stream().anyMatch(rsn::equalsIgnoreCase);
        }

        @Override
        public boolean shown(String rsn) {
            return shown.stream().anyMatch(rsn::equalsIgnoreCase);
        }

        @Override
        public CompletableFuture<PollOutcome> poll(String rsn) {
            if (queueFails) throw new IllegalStateException("queue is shut");
            polls.add(rsn);
            return answer == null ? new CompletableFuture<>() : CompletableFuture.completedFuture(answer);
        }

        @Override
        public void polled(String rsn) {
            invalidated.add(rsn);
        }
    }

    private static DirectoryPlayerRefreshRoutes.Refresher refresher(Fake fake, Hand clock) {
        return new DirectoryPlayerRefreshRoutes.Refresher(fake, clock, Duration.ofMillis(10));
    }

    private static DataObject ask(String rsn) {
        return DataObject.empty().put("rsn", rsn).put("waitMinutes", "30");
    }

    @Test
    void aNameThatIsNotARuneScapeNameIs400() {
        Fake fake = new Fake();
        assertEquals(400, refresher(fake, new Hand()).refresh(ask("not a name at all!!")).status());
        assertEquals(400, refresher(fake, new Hand()).refresh(DataObject.empty()).status());
        assertTrue(fake.polls.isEmpty());
    }

    @Test
    void aMadeUpNameIsNeverPolled() {
        Fake fake = new Fake();
        DirectoryPlayerRefreshRoutes.Reply reply = refresher(fake, new Hand()).refresh(ask("Made Up"));
        assertEquals(404, reply.status());
        assertTrue(fake.polls.isEmpty(), "nobody linked it, so RuneMetrics is not asked");
    }

    @Test
    void aTrackedNameWithNoStatsYetIsPolled() {
        Fake fake = new Fake();
        fake.tracked.add("New Link");
        DirectoryPlayerRefreshRoutes.Reply reply = refresher(fake, new Hand()).refresh(ask("New Link"));
        assertEquals(200, reply.status());
        assertEquals(List.of("New Link"), fake.polls);
        assertEquals("TIMED_OUT", reply.body().getString("status"), "the poll carries on after the short wait");
        assertFalse(reply.body().getBoolean("polled"));
        assertEquals(NOW.plus(WAIT).toString(), reply.body().getString("nextAt"));
    }

    @Test
    void aHiddenPlayerLooksLikeANameNobodyKnowsEvenWhenPolledLongAgo() {
        Fake fake = new Fake();
        fake.tracked.add("Hidden");
        fake.lastPoll.put("Hidden", NOW.minus(Duration.ofMinutes(10)));
        DirectoryPlayerRefreshRoutes.Reply reply = refresher(fake, new Hand()).refresh(ask("Hidden"));
        assertEquals(404, reply.status(), "not a 429 with nextAt, which would say the name exists");
        assertFalse(reply.body().hasKey("nextAt"));
        assertTrue(fake.polls.isEmpty());
    }

    @Test
    void tooSoonIs429WithWhenItIsAllowed() {
        Fake fake = new Fake();
        fake.shown.add("Shown");
        Instant polled = NOW.minus(Duration.ofMinutes(10));
        fake.lastPoll.put("Shown", polled);
        DirectoryPlayerRefreshRoutes.Reply reply = refresher(fake, new Hand()).refresh(ask("Shown"));
        assertEquals(429, reply.status());
        assertEquals(polled.plus(WAIT).toString(), reply.body().getString("nextAt"));
        assertEquals(30, reply.body().getInt("waitMinutes"));
        assertTrue(fake.polls.isEmpty());
    }

    @Test
    void aSecondPressWaitsEvenWhenThePollSavedNothing() {
        Fake fake = new Fake();
        Hand clock = new Hand();
        fake.tracked.add("Private Pro");
        DirectoryPlayerRefreshRoutes.Refresher refresher = refresher(fake, clock);
        assertEquals(200, refresher.refresh(ask("Private Pro")).status());
        clock.now = NOW.plus(Duration.ofMinutes(29));
        assertEquals(429, refresher.refresh(ask("private pro")).status(), "same name, any spelling");
        clock.now = NOW.plus(WAIT);
        assertEquals(200, refresher.refresh(ask("Private Pro")).status());
        assertEquals(2, fake.polls.size());
    }

    @Test
    void aPollThatFailedDoesNotLockThePlayerOut() {
        Fake fake = new Fake();
        Hand clock = new Hand();
        fake.tracked.add("Unlucky");
        fake.answer = PollOutcome.failed(1);
        DirectoryPlayerRefreshRoutes.Refresher refresher = refresher(fake, clock);
        DirectoryPlayerRefreshRoutes.Reply first = refresher.refresh(ask("Unlucky"));
        assertEquals(200, first.status());
        assertEquals("FAILED", first.body().getString("status"));
        assertEquals(List.of("Unlucky"), fake.invalidated, "the cached profile is dropped whatever the outcome");
        clock.now = NOW.plusSeconds(5);
        assertEquals(200, refresher.refresh(ask("Unlucky")).status(), "the failed try is forgotten");

        fake.answer = PollOutcome.cancelled();
        assertEquals(200, refresher.refresh(ask("Unlucky")).status());
        assertEquals(200, refresher.refresh(ask("Unlucky")).status(), "a cancelled poll is forgotten too");
    }

    @Test
    void aQueueThatWontTakeThePollIs503AndForgotten() {
        Fake fake = new Fake();
        fake.tracked.add("Someone");
        fake.queueFails = true;
        DirectoryPlayerRefreshRoutes.Refresher refresher = refresher(fake, new Hand());
        assertEquals(503, refresher.refresh(ask("Someone")).status());
        fake.queueFails = false;
        assertEquals(200, refresher.refresh(ask("Someone")).status());
    }

    @Test
    void theWholeSiteGetsAtMostItsCapOfRefreshesAMinute() {
        Fake fake = new Fake();
        Hand clock = new Hand();
        DirectoryPlayerRefreshRoutes.Refresher refresher = refresher(fake, clock);
        for (int i = 0; i <= DirectoryPlayerRefreshRoutes.GLOBAL_PER_MINUTE; i++) fake.tracked.add("Player " + (char) ('a' + i % 26) + i);
        int ok = 0;
        DirectoryPlayerRefreshRoutes.Reply last = null;
        for (int i = 0; i <= DirectoryPlayerRefreshRoutes.GLOBAL_PER_MINUTE; i++) {
            last = refresher.refresh(ask("Player " + (char) ('a' + i % 26) + i));
            if (last.status() == 200) ok++;
        }
        assertEquals(DirectoryPlayerRefreshRoutes.GLOBAL_PER_MINUTE, ok);
        assertEquals(429, last.status());
        assertFalse(last.body().hasKey("nextAt"), "the cap says nothing about this player");
        clock.now = NOW.plus(Duration.ofMinutes(1)).plusSeconds(1);
        String name = "Player " + (char) ('a' + DirectoryPlayerRefreshRoutes.GLOBAL_PER_MINUTE % 26) + DirectoryPlayerRefreshRoutes.GLOBAL_PER_MINUTE;
        assertEquals(200, refresher.refresh(ask(name)).status(), "a minute later there is room again, and the refused name wasn't marked");
    }
}
