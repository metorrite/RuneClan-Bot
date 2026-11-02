package com.runeclans.bot.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Refresh button's wait on a player profile ({@link DirectoryPlayerRefreshRoutes}): the website's minutes kept in range, and the clock. */
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
}
