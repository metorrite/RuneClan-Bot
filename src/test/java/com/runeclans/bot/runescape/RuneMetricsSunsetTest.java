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
}
