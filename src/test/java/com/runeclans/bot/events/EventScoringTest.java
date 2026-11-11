package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventScoring.Entry;
import com.runeclans.bot.events.EventScoring.Standing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventScoringTest {
    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    private static ObjectiveSpec obj(String key, long points, long target, int repeat, String... prerequisites) {
        return new ObjectiveSpec(key, key, "", points, Evidence.EITHER, target, repeat, List.of(prerequisites));
    }

    private static Entry entry(String subject, String key, long amount, int minutes) {
        return new Entry(subject, key, amount, T0.plusSeconds(minutes * 60L));
    }

    @Test
    void anObjectiveCompletesWhenTheTargetIsReached() {
        var o = obj("kills", 50, 10, 1);
        assertEquals(0, EventScoring.completions(o, 9));
        assertEquals(1, EventScoring.completions(o, 10));
        assertEquals(1, EventScoring.completions(o, 99), "the repeat limit caps it");
        assertEquals(0, EventScoring.completions(o, -5));
    }

    @Test
    void repeatableObjectivesCountEveryMultipleUpToTheLimit() {
        var o = obj("drops", 10, 2, 3);
        assertEquals(2, EventScoring.completions(o, 5));
        assertEquals(3, EventScoring.completions(o, 100));
    }

    @Test
    void standingsAddUpPointsAndRankBestFirst() {
        var objectives = List.of(obj("a", 10, 1, 1), obj("b", 30, 1, 1), obj("c", 5, 2, 5));
        var log = List.of(entry("u:1", "a", 1, 1), entry("u:1", "b", 1, 2), entry("u:2", "a", 1, 3), entry("u:2", "c", 7, 4));
        List<Standing> s = EventScoring.standings(objectives, log);
        assertEquals(2, s.size());
        assertEquals("u:1", s.get(0).subject());
        assertEquals(40, s.get(0).points());
        assertEquals(2, s.get(0).objectivesCompleted());
        assertEquals("u:2", s.get(1).subject());
        assertEquals(25, s.get(1).points(), "10 for a plus 3 completions of c at 5");
        assertEquals(3, s.get(1).completions().get("c"));
    }

    @Test
    void tiesAreBrokenByWhoFinishedFirstAndThenByKey() {
        var objectives = List.of(obj("a", 10, 1, 1));
        var s = EventScoring.standings(objectives, List.of(entry("u:2", "a", 1, 5), entry("u:1", "a", 1, 9)));
        assertEquals("u:2", s.get(0).subject(), "finished earlier");
        var same = EventScoring.standings(objectives, List.of(entry("u:9", "a", 1, 5), entry("u:3", "a", 1, 5)));
        assertEquals("u:3", same.get(0).subject(), "then a stable order");
    }

    @Test
    void aReversedCompletionDoesNotMoveTheTieTime() {
        // B-76: A reaches 10 at minute 0, a mistaken 10 more at minute 120 is taken back at 125; B reaches 10 at minute 60
        var objectives = List.of(obj("x", 10, 1, 1), obj("y", 10, 1, 1));
        var s = EventScoring.standings(objectives, List.of(entry("u:a", "x", 1, 0), entry("u:a", "y", 1, 120), entry("u:a", "y", -1, 125), entry("u:b", "x", 1, 60)));
        assertEquals(10, s.get(0).points());
        assertEquals(10, s.get(1).points());
        assertEquals("u:a", s.get(0).subject(), "A held 10 points first");
        assertEquals(T0, s.get(0).lastCompletedAt());
    }

    @Test
    void aZeroPointObjectiveDoesNotMoveTheTieTime() {
        var objectives = List.of(obj("x", 10, 1, 1), obj("chat", 0, 1, 1));
        var s = EventScoring.standings(objectives, List.of(entry("u:a", "x", 1, 0), entry("u:a", "chat", 1, 90), entry("u:b", "x", 1, 60)));
        assertEquals("u:a", s.get(0).subject(), "the 0-point completion at minute 90 doesn't count as finishing later");
        assertEquals(T0, s.get(0).lastCompletedAt());
    }

    @Test
    void droppingBelowTheFinalTotalAndClimbingBackStartsTheClockAgain() {
        var objectives = List.of(obj("x", 10, 1, 1));
        var s = EventScoring.standings(objectives, List.of(entry("u:a", "x", 1, 0), entry("u:a", "x", -1, 30), entry("u:a", "x", 1, 90), entry("u:b", "x", 1, 60)));
        assertEquals("u:b", s.get(0).subject(), "B has held 10 since minute 60, A only since minute 90");
        assertEquals(T0.plusSeconds(90 * 60L), s.get(1).lastCompletedAt());
    }

    @Test
    void noPointsMeansNoTieTime() {
        var s = EventScoring.standings(List.of(obj("x", 10, 1, 1)), List.of(entry("u:a", "x", 1, 0), entry("u:a", "x", -1, 5)));
        assertEquals(0, s.get(0).points());
        assertEquals(null, s.get(0).lastCompletedAt());
    }

    @Test
    void aNegativeEntryReversesEarlierProgress() {
        var objectives = List.of(obj("a", 10, 1, 1));
        var s = EventScoring.standings(objectives, List.of(entry("u:1", "a", 1, 1), entry("u:1", "a", -1, 2)));
        assertEquals(0, s.get(0).points());
        var over = EventScoring.standings(objectives, List.of(entry("u:1", "a", -5, 1), entry("u:1", "a", 1, 2)));
        assertEquals(10, over.get(0).points(), "a reversal can't push the total below zero");
    }

    @Test
    void progressOnAnObjectiveThatNoLongerExistsIsIgnored() {
        var s = EventScoring.standings(List.of(obj("a", 10, 1, 1)), List.of(entry("u:1", "gone", 1, 1), entry("u:1", "a", 1, 2)));
        assertEquals(10, s.get(0).points());
    }

    @Test
    void theLogOrderDoesNotMatter() {
        var objectives = List.of(obj("a", 10, 3, 1));
        var one = EventScoring.standings(objectives, List.of(entry("u:1", "a", 1, 1), entry("u:1", "a", 1, 2), entry("u:1", "a", 1, 3)));
        var other = EventScoring.standings(objectives, List.of(entry("u:1", "a", 1, 3), entry("u:1", "a", 1, 1), entry("u:1", "a", 1, 2)));
        assertEquals(one.get(0).points(), other.get(0).points());
        assertEquals(one.get(0).lastCompletedAt(), other.get(0).lastCompletedAt());
    }

    @Test
    void anObjectiveIsAvailableOnceItsPrerequisitesHaveCompleted() {
        var a = obj("a", 10, 2, 1);
        var b = obj("b", 10, 1, 1, "a");
        Map<String, ObjectiveSpec> byKey = new HashMap<>();
        byKey.put("a", a);
        byKey.put("b", b);
        assertTrue(EventScoring.available(a, Map.of(), byKey), "no prerequisites");
        assertFalse(EventScoring.available(b, Map.of(), byKey));
        assertFalse(EventScoring.available(b, Map.of("a", 1L), byKey), "a needs 2");
        assertTrue(EventScoring.available(b, Map.of("a", 2L), byKey));
    }
}
