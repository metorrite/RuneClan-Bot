package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient.Answer;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Refresh route's "is this name ours": it answers from the hub's last list without waiting, and keeps that list fresh in the background. */
class TrackedNamesTest {
    private static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-07T12:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
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
    void theHubsListIsReadInTheBackgroundAndRefreshedWhenStale() {
        AtomicInteger reads = new AtomicInteger();
        List<String> names = new ArrayList<>(List.of("Zezima"));
        MovingClock clock = new MovingClock();
        HubPollingList list = new HubPollingList(path -> {
            reads.incrementAndGet();
            DataArray characters = DataArray.empty();
            for (String n : names) characters.add(DataObject.empty().put("rsn", n).put("group", "linked"));
            return new Answer(200, DataObject.empty().put("characters", characters).put("nextCursor", null));
        }, clock);
        PlayerLinkService links = mock(PlayerLinkService.class);
        when(links.getAllAccountRsns()).thenReturn(List.of("Own Rsn"));
        List<Runnable> queued = new ArrayList<>();
        TrackedNames tracked = TrackedNames.forTest(list, links, queued::add);

        assertFalse(tracked.tracked("zezima"), "before the first read only /rsn names are known: the request doesn't wait");
        assertTrue(tracked.tracked("own rsn"));
        assertEquals(1, queued.size(), "one background read, however many asks");
        queued.remove(0).run();
        assertTrue(tracked.tracked("Zezima"), "the list read in the background answers the next ask");
        assertTrue(queued.isEmpty(), "a fresh list isn't read again");

        names.add("Newcomer");
        clock.now = clock.now.plus(HubPollingList.KEEP).plus(Duration.ofSeconds(1));
        assertFalse(tracked.tracked("Newcomer"));
        assertEquals(1, queued.size(), "a stale list is read again");
        queued.remove(0).run();
        assertTrue(tracked.tracked("Newcomer"));
        assertEquals(2, reads.get());
    }
}
