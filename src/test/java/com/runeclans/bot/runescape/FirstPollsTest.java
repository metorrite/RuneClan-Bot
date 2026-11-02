package com.runeclans.bot.runescape;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first poll of a newly linked player ({@link FirstPolls}): names with no snapshot, once each, offered again only after the retry time. */
class FirstPollsTest {
    private static final Instant START = Instant.parse("2026-11-02T01:00:00Z");

    /** A clock the test moves by hand. */
    private static final class Hand extends Clock {
        Instant now = START;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void namesWithNoSnapshotAreDueOnceEachInTheirFirstSpelling() {
        FirstPolls first = new FirstPolls(new Hand());
        Set<String> polled = Set.of("old timer");
        List<String> due = first.due(List.of("ABabyMe", "ababyme", "Old Timer", " New One ", ""), keys -> polled, 10);
        assertEquals(List.of("ABabyMe", "New One"), due);
    }

    @Test
    void aNameStillWithoutAStatsSnapshotIsOfferedAgainOnlyAfterTheRetryTime() {
        Hand clock = new Hand();
        FirstPolls first = new FirstPolls(clock);
        assertEquals(List.of("Private"), first.due(List.of("Private"), keys -> Set.of(), 10));
        clock.now = START.plus(FirstPolls.RETRY).minusSeconds(1);
        assertTrue(first.due(List.of("Private"), keys -> Set.of(), 10).isEmpty(), "RuneMetrics couldn't read it: don't ask every two minutes");
        clock.now = START.plus(FirstPolls.RETRY);
        assertEquals(List.of("Private"), first.due(List.of("Private"), keys -> Set.of(), 10));
    }

    @Test
    void aNameThatGotItsSnapshotIsForgottenAndNeverDueAgain() {
        Hand clock = new Hand();
        FirstPolls first = new FirstPolls(clock);
        Set<String> polled = new HashSet<>();
        assertEquals(List.of("Linker"), first.due(List.of("Linker"), keys -> polled, 10));
        polled.add("linker");
        clock.now = START.plus(Duration.ofDays(1));
        assertTrue(first.due(List.of("Linker"), keys -> polled, 10).isEmpty());
    }

    @Test
    void aNameUnlinkedAndLinkedAgainIsDueAtOnce() {
        Hand clock = new Hand();
        FirstPolls first = new FirstPolls(clock);
        assertEquals(List.of("Again"), first.due(List.of("Again"), keys -> Set.of(), 10));
        assertTrue(first.due(List.of("Someone"), keys -> Set.of(), 10).contains("Someone"), "Again is no longer tracked, so it is forgotten");
        assertEquals(List.of("Again"), first.due(List.of("Again", "Someone"), keys -> Set.of(), 10));
    }

    @Test
    void eachRunOffersAtMostItsCapAndTheRestFollowNextRun() {
        FirstPolls first = new FirstPolls(new Hand());
        List<String> names = List.of("One", "Two", "Three", "Four", "Five");
        assertEquals(List.of("One", "Two"), first.due(names, keys -> Set.of(), 2));
        assertEquals(List.of("Three", "Four"), first.due(names, keys -> Set.of(), 2), "the names already offered wait for the retry time");
        assertEquals(List.of("Five"), first.due(names, keys -> Set.of(), 2));
    }

    @Test
    void anEmptyListAsksNothing() {
        FirstPolls first = new FirstPolls(new Hand());
        assertTrue(first.due(List.of(), keys -> {
            throw new AssertionError("no names, no database read");
        }, 10).isEmpty());
    }
}
