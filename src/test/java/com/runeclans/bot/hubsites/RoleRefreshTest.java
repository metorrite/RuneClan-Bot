package com.runeclans.bot.hubsites;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The 6-hour role refresh (T-963): one server at a time, and nothing it runs ever stops the repeating task. */
class RoleRefreshTest {
    /** Runs a "later" task when told, so a test can see the pass go one server at a time. */
    private static final class Steps implements RoleReports.Timer {
        final List<Runnable> queued = new ArrayList<>();
        final List<Duration> delays = new ArrayList<>();

        @Override
        public void after(Duration delay, Runnable task) {
            delays.add(delay);
            queued.add(task);
        }

        void drain() {
            while (!queued.isEmpty()) queued.remove(0).run();
        }
    }

    @Test
    void reportsEveryServerOneAtATime() {
        Steps steps = new Steps();
        List<Long> reported = new ArrayList<>();
        HubSiteReporter.RoleRefresh refresh = new HubSiteReporter.RoleRefresh(() -> true, () -> List.of(1L, 2L, 3L), id -> {
            reported.add(id);
            return 2;
        }, steps);
        refresh.run();
        assertEquals(List.of(1L), reported, "the first server now, the rest each in its own lane task");
        steps.drain();
        assertEquals(List.of(1L, 2L, 3L), reported);
        assertEquals(HubSiteReporter.RoleRefresh.GAP, steps.delays.get(0));
        assertFalse(refresh.running());
    }

    @Test
    void aThrowingGuildListDoesNotStopTheNextRun() {
        Steps steps = new Steps();
        AtomicBoolean broken = new AtomicBoolean(true);
        AtomicInteger reports = new AtomicInteger();
        HubSiteReporter.RoleRefresh refresh = new HubSiteReporter.RoleRefresh(() -> true, () -> {
            if (broken.get()) throw new IllegalStateException("shards not ready");
            return List.of(1L);
        }, id -> reports.incrementAndGet(), steps);
        assertDoesNotThrow(refresh::run);
        assertFalse(refresh.running());
        broken.set(false);
        refresh.run();
        steps.drain();
        assertEquals(1, reports.get(), "the next run goes ahead");
    }

    @Test
    void aThrowingSwitchOrServerIsCaught() {
        Steps steps = new Steps();
        assertDoesNotThrow(new HubSiteReporter.RoleRefresh(() -> {
            throw new IllegalStateException("panel down");
        }, () -> List.of(1L), id -> 1, steps)::run);

        List<Long> reported = new ArrayList<>();
        HubSiteReporter.RoleRefresh refresh = new HubSiteReporter.RoleRefresh(() -> true, () -> List.of(1L, 2L), id -> {
            if (id == 1L) throw new IllegalStateException("discord timeout");
            reported.add(id);
            return 1;
        }, steps);
        assertDoesNotThrow(refresh::run);
        steps.drain();
        assertEquals(List.of(2L), reported, "one failing server doesn't stop the rest");
        assertFalse(refresh.running());
    }

    @Test
    void aPassStillGoingIsNotStartedTwice() {
        Steps steps = new Steps();
        AtomicInteger reports = new AtomicInteger();
        HubSiteReporter.RoleRefresh refresh = new HubSiteReporter.RoleRefresh(() -> true, () -> List.of(1L, 2L), id -> reports.incrementAndGet(), steps);
        refresh.run();
        refresh.run();
        steps.drain();
        assertEquals(2, reports.get());
    }
}
