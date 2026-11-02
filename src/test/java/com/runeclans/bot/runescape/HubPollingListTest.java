package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient.Answer;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-736: reading the hub's polling list for the poll tiers, its paging, its few-minute share, and what happens while it is off or down. */
class HubPollingListTest {
    private static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-10-29T12:00:00Z");

        @Override
        public ZoneId getZone() {
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

    private static Answer page(String cursor, String... rsnAndGroup) {
        DataArray chars = DataArray.empty();
        for (int i = 0; i < rsnAndGroup.length; i += 2) chars.add(DataObject.empty().put("rsn", rsnAndGroup[i]).put("group", rsnAndGroup[i + 1]));
        DataObject json = DataObject.empty().put("characters", chars);
        if (cursor == null) json.putNull("nextCursor");
        else json.put("nextCursor", cursor);
        return new Answer(200, json);
    }

    @Test
    void readsEveryPageIntoItsGroup() {
        List<String> asked = new ArrayList<>();
        Function<String, Answer> hub = path -> {
            asked.add(path);
            return path.contains("cursor=") ? page(null, "Loner", "linked", "Odd", "something new") : page("c1", "Clanner", "clan", "Friend", "community");
        };
        HubPollingList.Groups g = new HubPollingList(hub, new MovingClock()).groups();

        assertEquals(List.of("Clanner"), g.clan());
        assertEquals(List.of("Friend"), g.community());
        // a group this bot doesn't know yet is still tracked, at the lowest tier
        assertEquals(List.of("Loner", "Odd"), g.linked());
        assertEquals(List.of("/v1/polling/characters?limit=1000", "/v1/polling/characters?limit=1000&cursor=c1"), asked);
    }

    @Test
    void theTiersOfOneCycleShareOneReadForFiveMinutes() {
        int[] calls = {0};
        MovingClock clock = new MovingClock();
        HubPollingList list = new HubPollingList(path -> {
            calls[0]++;
            return page(null, "A", "linked");
        }, clock);
        list.groups();
        list.groups();
        clock.now = clock.now.plus(HubPollingList.KEEP).minusSeconds(1);
        list.groups();
        assertEquals(1, calls[0]);
        clock.now = clock.now.plusSeconds(1);
        list.groups();
        assertEquals(2, calls[0]);
    }

    @Test
    void offMeansEmpty() {
        HubPollingList list = new HubPollingList(path -> new Answer(404, null), new MovingClock());
        assertEquals(0, list.groups().size());
        assertEquals(0, HubPollingList.from(null).groups().size());
    }

    /** A key the hub no longer counts as first-party (403) is treated as off at once, not as an outage kept for six hours. */
    @Test
    void aRefusedKeyDropsTheListAtOnce() {
        MovingClock clock = new MovingClock();
        boolean[] refused = {false};
        HubPollingList list = new HubPollingList(path -> refused[0] ? new Answer(403, DataObject.empty()) : page(null, "A", "linked"), clock);
        assertEquals(1, list.groups().size());
        refused[0] = true;
        clock.now = clock.now.plus(HubPollingList.KEEP);
        assertEquals(0, list.groups().size());
    }

    @Test
    void aHubThatCantBeReachedKeepsTheLastListForSixHours() {
        MovingClock clock = new MovingClock();
        boolean[] down = {false};
        HubPollingList list = new HubPollingList(path -> down[0] ? null : page(null, "A", "community"), clock);
        assertEquals(List.of("A"), list.groups().community());

        down[0] = true;
        clock.now = clock.now.plus(Duration.ofHours(5));
        assertEquals(List.of("A"), list.groups().community());
        clock.now = clock.now.plus(Duration.ofHours(1)).plusSeconds(1);
        assertTrue(list.groups().community().isEmpty());
    }

    @Test
    void turningTheSwitchOffDropsTheListAtOnce() {
        MovingClock clock = new MovingClock();
        boolean[] off = {false};
        HubPollingList list = new HubPollingList(path -> off[0] ? new Answer(404, null) : page(null, "A", "linked"), clock);
        assertEquals(1, list.groups().size());
        off[0] = true;
        clock.now = clock.now.plus(HubPollingList.KEEP);
        assertEquals(0, list.groups().size());
    }

    @Test
    void aCursorThatNeverEndsStopsAtTheSafetyLimit() {
        int[] calls = {0};
        HubPollingList list = new HubPollingList(path -> {
            calls[0]++;
            return page("again", "A", "linked");
        }, new MovingClock());
        assertEquals(0, list.groups().size());
        assertEquals(HubPollingList.MAX_PAGES, calls[0]);
    }

    @Test
    void theLastListIsReadWithoutWaitingForASlowHubRead() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HubPollingList list = new HubPollingList(path -> {
            reading.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return page(null, "Clanner", "clan", "Friend", "community");
        }, new MovingClock());
        CompletableFuture<HubPollingList.Groups> slow = CompletableFuture.supplyAsync(list::groups);
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            // the web request's check (Refresh) must not wait behind the hub page read
            HubPollingList.Groups known = CompletableFuture.supplyAsync(list::lastKnown).get(1, TimeUnit.SECONDS);
            assertTrue(known.clan().isEmpty(), "nothing read yet");
        } finally {
            release.countDown();
        }
        assertEquals(List.of("Clanner"), slow.get(5, TimeUnit.SECONDS).clan());
        assertEquals(List.of("Clanner"), list.lastKnown().clan());
    }
}
