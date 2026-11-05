package com.runeclans.bot.ops;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ops alert rules (T-674): when each fires, reminds and recovers. */
class OpsAlertRulesTest {
    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final String HUB = "http://hub.railway.internal:8090/health/ready?token=hidden";

    private static OpsAlertRules.Sample at(int minute, Boolean hubOk, double queueAge, long total429) {
        Map<String, Boolean> health = hubOk == null ? Map.of() : Map.of(HUB, hubOk);
        return new OpsAlertRules.Sample(T0.plus(Duration.ofMinutes(minute)), health, queueAge, queueAge > 0 ? 3 : 0, total429);
    }

    @Test
    void healthFiresAfterThreeFailuresInARowAndRecovers() {
        OpsAlertRules rules = new OpsAlertRules(20);
        assertTrue(rules.evaluate(at(0, false, 0, 0)).isEmpty());
        assertTrue(rules.evaluate(at(1, false, 0, 0)).isEmpty());
        List<OpsAlertRules.Alert> third = rules.evaluate(at(2, false, 0, 0));
        assertEquals(1, third.size());
        assertTrue(third.get(0).text().contains("hub.railway.internal/health/ready"));
        assertFalse(third.get(0).text().contains("hidden"), "never the query");
        assertTrue(rules.evaluate(at(3, false, 0, 0)).isEmpty(), "no repeat within the hour");
        assertTrue(rules.evaluate(at(62, false, 0, 0)).get(0).text().startsWith("Still: "), "an hourly reminder");
        List<OpsAlertRules.Alert> back = rules.evaluate(at(63, true, 0, 0));
        assertTrue(back.get(0).recovered());
        assertTrue(rules.firing().isEmpty());
    }

    @Test
    void oneGoodCheckResetsTheCount() {
        OpsAlertRules rules = new OpsAlertRules(20);
        rules.evaluate(at(0, false, 0, 0));
        rules.evaluate(at(1, false, 0, 0));
        rules.evaluate(at(2, true, 0, 0));
        assertTrue(rules.evaluate(at(3, false, 0, 0)).isEmpty());
    }

    @Test
    void queueAgeOverFiveMinutes() {
        OpsAlertRules rules = new OpsAlertRules(20);
        assertTrue(rules.evaluate(at(0, null, 300, 0)).isEmpty(), "exactly 5 minutes is not over");
        List<OpsAlertRules.Alert> late = rules.evaluate(at(1, null, 301, 0));
        assertEquals("queue", late.get(0).rule());
        assertTrue(rules.evaluate(at(2, null, -1, 0)).isEmpty(), "an unreadable queue changes nothing");
        assertTrue(rules.evaluate(at(3, null, 0, 0)).get(0).recovered());
    }

    @Test
    void a429SpikeIsCountedOverFiveMinutes() {
        OpsAlertRules rules = new OpsAlertRules(10);
        assertTrue(rules.evaluate(at(0, null, 0, 100)).isEmpty(), "what came before the first look is not a spike");
        assertTrue(rules.evaluate(at(1, null, 0, 104)).isEmpty());
        assertTrue(rules.evaluate(at(2, null, 0, 109)).isEmpty());
        assertEquals("429", rules.evaluate(at(3, null, 0, 110)).get(0).rule());
        // slow drip: 1 a minute never reaches 10 within 5 minutes
        OpsAlertRules drip = new OpsAlertRules(10);
        for (int m = 0; m < 30; m++) assertTrue(drip.evaluate(at(m, null, 0, m)).isEmpty(), "minute " + m);
    }

    @Test
    void theWeeklyPostIsOnMondayMorningUtc() {
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), OpsAlerts.nextWeekly(Instant.parse("2026-10-26T09:00:00Z")));
        assertEquals(Instant.parse("2026-10-26T09:00:00Z"), OpsAlerts.nextWeekly(Instant.parse("2026-10-26T08:59:00Z")));
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), OpsAlerts.nextWeekly(Instant.parse("2026-10-28T15:00:00Z")));
    }
}
