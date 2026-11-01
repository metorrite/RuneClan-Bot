package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.PlayerLinkRepository.PlayerAccount;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot's links in the hub (T-839), against a fake hub. Made-up ids, built at runtime. */
class HubLinkSyncTest {
    private static final long ALICE = Long.parseLong("1".repeat(18));
    private static final long BOB = Long.parseLong("2".repeat(18));

    /** Every POST body the fake hub got. */
    private final List<DataObject> posts = new ArrayList<>();
    private final AtomicInteger gets = new AtomicInteger();
    private final AtomicInteger postStatus = new AtomicInteger(200);
    private final AtomicInteger getStatus = new AtomicInteger(200);
    /** The fake hub's current bot links, as {discordId, rsn}. */
    private final List<DataObject> hubLinks = new ArrayList<>();
    private final AtomicBoolean on = new AtomicBoolean(true);
    private final List<PlayerAccount> accounts = new ArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T12:00:00Z"));

    private HubLinkSync sync() {
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        return new HubLinkSync((method, path, body) -> {
            assertEquals(HubLinkSync.PATH, path);
            if (method.equals("GET")) {
                gets.incrementAndGet();
                if (getStatus.get() != 200) return new RuneClansHubClient.Answer(getStatus.get(), null);
                DataArray links = DataArray.empty();
                hubLinks.forEach(links::add);
                return new RuneClansHubClient.Answer(200, DataObject.empty().put("links", links));
            }
            DataObject b = DataObject.fromJson(body);
            posts.add(b);
            if (postStatus.get() != 200) return new RuneClansHubClient.Answer(postStatus.get(), null);
            DataArray items = b.getArray("items");
            return new RuneClansHubClient.Answer(200, DataObject.empty().put("counts", DataObject.empty().put("added", items.length())));
        }, on::get, () -> accounts, Runnable::run, clock);
    }

    private static PlayerAccount account(long id, String rsn) {
        return new PlayerAccount(id, rsn, id, PlayerLinkService.METHOD_MAKEOVER_MAGE, OffsetDateTime.parse("2026-09-01T10:00:00Z"), 1L);
    }

    private static DataObject hubLink(long id, String rsn) {
        return DataObject.empty().put("discordId", Long.toString(id)).put("rsn", rsn);
    }

    private List<DataObject> items(DataObject post) {
        List<DataObject> out = new ArrayList<>();
        DataArray a = post.getArray("items");
        for (int i = 0; i < a.length(); i++) out.add(a.getObject(i));
        return out;
    }

    @Test
    void aNewLinkIsSentAsFreshWithTheIdAsText() {
        HubLinkSync sync = sync();
        sync.linked(ALICE, "Zezima");
        assertEquals(1, posts.size());
        DataObject item = items(posts.get(0)).get(0);
        assertEquals("add", item.getString("op"));
        assertEquals(Long.toString(ALICE), item.getString("discordId"));
        assertTrue(item.getBoolean("fresh"));
        assertFalse(posts.get(0).getBoolean("dryRun"));
        sync.renamedTo(ALICE, "Durial");
        assertFalse(items(posts.get(1)).get(0).hasKey("fresh"), "a rename is not a new choice");
    }

    @Test
    void offWritesNothingButHoldsChangesAndDryRunsOnce() {
        on.set(false);
        accounts.add(account(ALICE, "Zezima"));
        HubLinkSync sync = sync();
        sync.unlinked(BOB, "Durial");
        assertEquals(1, sync.held(), "an unlink made while off is kept for later");
        sync.round();
        sync.round();
        assertEquals(1, posts.size(), "one dry run only");
        assertTrue(posts.get(0).getBoolean("dryRun"));
        // switched on: the held unlink goes out first, then the catch-up
        on.set(true);
        posts.clear();
        sync.round();
        assertEquals("end", items(posts.get(0)).get(0).getString("op"));
        assertEquals(0, sync.held());
    }

    @Test
    void theCatchUpOnlyAddsAndNeverEnds() {
        accounts.add(account(ALICE, "Zezima"));
        HubLinkSync sync = sync();
        sync.round();
        assertEquals(1, posts.size());
        List<DataObject> adds = items(posts.get(0));
        assertEquals("add", adds.get(0).getString("op"));
        assertFalse(adds.get(0).hasKey("fresh"), "a catch-up never lifts a player's unlink");
        assertEquals("2026-09-01T10:00Z", adds.get(0).getString("linkedAt"));
        assertEquals(0, gets.get(), "it doesn't read the hub's links, so it can't end any");
        // an empty account table sends nothing at all
        accounts.clear();
        now.set(now.get().plus(Duration.ofHours(25)));
        sync.round();
        assertEquals(1, posts.size());
    }

    @Test
    void namesKeyLikeTheHub() {
        assertEquals("zezima the 2nd", HubLinkSync.nameKey("\u00a0Zezima  The_2nd "));
        HubLinkSync sync = sync();
        postStatus.set(503);
        sync.linked(ALICE, "Zezima The-2nd");
        sync.unlinked(ALICE, "zezima\u00a0the_2nd");
        assertEquals(1, sync.held(), "one name, as the hub sees it");
    }

    @Test
    void theCatchUpRunsInBatchesOnceThenDaily() {
        for (int i = 0; i < 150; i++) accounts.add(account(ALICE + i, "N" + i));
        HubLinkSync sync = sync();
        sync.round();
        assertEquals(2, posts.size(), "150 accounts go in two batches");
        assertEquals(100, items(posts.get(0)).size());
        sync.round();
        assertEquals(2, posts.size());
        now.set(now.get().plus(Duration.ofHours(25)));
        sync.round();
        assertEquals(4, posts.size());
    }

    @Test
    void aCatchUpThatCantReachTheHubWaitsBeforeTryingAgain() {
        accounts.add(account(ALICE, "Zezima"));
        postStatus.set(503);
        HubLinkSync sync = sync();
        sync.round();
        sync.round();
        assertEquals(1, posts.size(), "not every 30 seconds");
        postStatus.set(200);
        now.set(now.get().plus(HubLinkSync.CATCH_UP_RETRY));
        sync.round();
        assertEquals(2, posts.size());
    }

    @Test
    void aRefusedCatchUpBatchIsSkippedNotRetriedForever() {
        accounts.add(account(ALICE, "Zezima"));
        postStatus.set(400);
        HubLinkSync sync = sync();
        sync.round();
        assertEquals(1, posts.size());
        sync.round();
        assertEquals(1, posts.size(), "the catch-up counts as done; the next is tomorrow");
    }

    @Test
    void notFoundOrUnauthorisedKeepsTheChangesForLater() {
        HubLinkSync sync = sync();
        for (int status : new int[]{401, 403, 404, 429, 503}) {
            postStatus.set(status);
            sync.unlinked(ALICE, "Zezima");
            assertEquals(1, sync.held(), status + " keeps the unlink");
        }
        postStatus.set(200);
        sync.flush();
        assertEquals(0, sync.held());
    }

    @Test
    void aHubOutageKeepsTheNewestChangePerPersonAndName() {
        HubLinkSync sync = sync();
        postStatus.set(503);
        sync.linked(ALICE, "Zezima");
        sync.unlinked(ALICE, "zezima");
        assertEquals(1, sync.held());
        postStatus.set(200);
        posts.clear();
        sync.flush();
        assertEquals("end", items(posts.get(0)).get(0).getString("op"));
    }

    @Test
    void aMalformedChangeIsDropped() {
        HubLinkSync sync = sync();
        postStatus.set(400);
        sync.linked(BOB, "Durial");
        assertEquals(0, sync.held());
    }

    @Test
    void withoutAHubNothingHappens() {
        HubLinkSync off = HubLinkSync.off();
        off.linked(ALICE, "Zezima");
        off.round();
        assertFalse(off.on());
        assertEquals(0, off.held());
    }
}
