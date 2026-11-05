package com.runeclans.bot.util;

import com.runeclans.bot.config.BotRole;
import com.runeclans.bot.util.LaneOwners.Owner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-702: every scheduler lane names the role that runs it, and a repeating task only works where its lane is owned. */
class LaneOwnersTest {
    private static final Pattern LANE = Pattern.compile("BotScheduler\\.lane\\(\"([^\"]+)\"\\)");

    @AfterEach
    void reset() {
        BotRole.install(BotRole.ALL);
        LaneOwners.leading(false);
    }

    @Test
    void everyLaneInTheCodeHasAnOwnerAndNoOwnerIsStale() throws IOException {
        Set<String> lanes = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = LANE.matcher(Files.readString(file));
                while (m.find()) lanes.add(m.group(1));
            }
        }
        assertFalse(lanes.isEmpty());
        Set<String> missing = new TreeSet<>(lanes);
        missing.removeAll(LaneOwners.all().keySet());
        assertTrue(missing.isEmpty(), "add these lanes to LaneOwners (and docs/SCALING-STATE.md section 5): " + missing);
        Set<String> stale = new TreeSet<>(LaneOwners.all().keySet());
        stale.removeAll(lanes);
        assertTrue(stale.isEmpty(), "no lane of these names any more: " + stale);
    }

    @Test
    void whoRunsWhat() {
        for (Owner owner : Owner.values()) assertTrue(LaneOwners.runs(owner, BotRole.ALL, false), "all runs everything, as before: " + owner);

        assertTrue(LaneOwners.runs(Owner.GATEWAY, BotRole.GATEWAY, false));
        assertFalse(LaneOwners.runs(Owner.API, BotRole.GATEWAY, true));
        assertTrue(LaneOwners.runs(Owner.EVERY, BotRole.GATEWAY, false));

        assertFalse(LaneOwners.runs(Owner.GATEWAY, BotRole.API, true), "no gateway work without a gateway");
        assertFalse(LaneOwners.runs(Owner.API, BotRole.API, false), "only the leader replica");
        assertTrue(LaneOwners.runs(Owner.API, BotRole.API, true));
        assertTrue(LaneOwners.runs(Owner.EVERY, BotRole.API, false));

        assertEquals(Owner.API, LaneOwners.owner("rsnlink-retention"));
        assertEquals(Owner.GATEWAY, LaneOwners.owner("discord-jobs"));
        assertEquals(Owner.GATEWAY, LaneOwners.owner("a-lane-nobody-listed"), "unknown lanes stay with the classic bot");
    }

    @Test
    void aRepeatingTaskOnALaneThisRoleDoesNotOwnStaysScheduledButDoesNothing() throws Exception {
        BotRole.install(BotRole.API);
        ScheduledExecutorService gatewayLane = BotScheduler.lane("discord-jobs");
        ScheduledExecutorService apiLane = BotScheduler.lane("rsnlink-retention");
        AtomicInteger gatewayRuns = new AtomicInteger();
        AtomicInteger apiRuns = new AtomicInteger();
        CountDownLatch apiTurns = new CountDownLatch(3);
        CountDownLatch oneOff = new CountDownLatch(1);
        ScheduledFuture<?> g = gatewayLane.scheduleWithFixedDelay(gatewayRuns::incrementAndGet, 0, 5, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> a = apiLane.scheduleAtFixedRate(() -> {
            apiRuns.incrementAndGet();
            apiTurns.countDown();
        }, 0, 5, TimeUnit.MILLISECONDS);
        try {
            Thread.sleep(60);
            assertEquals(0, apiRuns.get(), "not the leader yet");
            LaneOwners.leading(true);
            assertTrue(apiTurns.await(5, TimeUnit.SECONDS), "the leader runs api lanes");
            gatewayLane.execute(oneOff::countDown);
            assertTrue(oneOff.await(5, TimeUnit.SECONDS), "one-off work always runs where it was asked for");
            assertEquals(0, gatewayRuns.get(), "the api role runs no gateway lane");
            assertFalse(g.isDone(), "still scheduled");
        } finally {
            g.cancel(false);
            a.cancel(false);
            gatewayLane.shutdown();
            apiLane.shutdown();
        }
    }
}
