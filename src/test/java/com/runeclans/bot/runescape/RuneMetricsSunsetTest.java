package com.runeclans.bot.runescape;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuneMetricsSunsetTest {

    /** Plan D-1 (Drake 2026-11-02): no stop date in the code; only {@code RUNEMETRICS=off} stops asking. */
    @Test
    void theSwitchHasNoStopDateAndOnlyOffStopsIt() {
        Instant announced = Instant.parse("2026-11-10T00:00:00Z");
        assertEquals(RuneMetricsSwitch.Mode.AUTO, RuneMetricsSwitch.parse(null));
        assertEquals(RuneMetricsSwitch.Mode.AUTO, RuneMetricsSwitch.parse("maybe"));
        assertTrue(RuneMetricsSwitch.on(RuneMetricsSwitch.Mode.AUTO, announced.minusSeconds(1)));
        assertTrue(RuneMetricsSwitch.on(RuneMetricsSwitch.Mode.AUTO, announced), "the announced day changes nothing");
        assertTrue(RuneMetricsSwitch.on(RuneMetricsSwitch.Mode.AUTO, announced.plusSeconds(86_400L * 365)));
        assertTrue(RuneMetricsSwitch.on(RuneMetricsSwitch.parse(" ON "), announced));
        assertFalse(RuneMetricsSwitch.on(RuneMetricsSwitch.parse("off"), announced.minusSeconds(1)));
    }

    @Test
    void theBreakerPausesAfterManyFailuresInARowAndProbesAfterThePause() {
        RuneMetricsBreaker breaker = new RuneMetricsBreaker();
        long now = 1_000;
        for (int i = 1; i < RuneMetricsBreaker.FAILURES_TO_OPEN; i++) assertFalse(breaker.recordFailure(now));
        assertTrue(breaker.allow(now), "still asking below the threshold");
        assertTrue(breaker.recordFailure(now), "opening is reported once");
        assertFalse(breaker.allow(now + RuneMetricsBreaker.PAUSE_MS - 1));
        assertTrue(breaker.allow(now + RuneMetricsBreaker.PAUSE_MS), "one probe after the pause");
        assertFalse(breaker.recordFailure(now + RuneMetricsBreaker.PAUSE_MS), "a failed probe pauses again quietly");
        assertFalse(breaker.allow(now + RuneMetricsBreaker.PAUSE_MS + 1));
        assertTrue(breaker.recordAnswer(), "closing is reported once");
        assertTrue(breaker.allow(now + RuneMetricsBreaker.PAUSE_MS + 1));
        assertFalse(breaker.isOpen());
    }

    @Test
    void anAnswerResetsTheCount() {
        RuneMetricsBreaker breaker = new RuneMetricsBreaker();
        for (int i = 1; i < RuneMetricsBreaker.FAILURES_TO_OPEN; i++) breaker.recordFailure(0);
        assertFalse(breaker.recordAnswer());
        assertFalse(breaker.recordFailure(0), "the count started again");
    }
}
