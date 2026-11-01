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

    private final List<DataObject> sent = new ArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicBoolean on = new AtomicBoolean(true);
    private final List<PlayerAccount> accounts = new ArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T12:00:00Z"));

    private HubLinkSync sync() {
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        return new HubLinkSync((path, body) -> {
            assertEquals(HubLinkSync.PATH, path);
            DataObject b = DataObject.fromJson(body);
            sent.add(b);
            if (status.get() >= 500) return new RuneClansHubClient.Answer(status.get(), null);
            DataArray items = b.getArray("items");
            return new RuneClansHubClient.Answer(status.get(), DataObject.empty().put("counts", DataObject.empty().put("added", items.length())));
        }, on::get, () -> accounts, Runnable::run, clock);
    }

    private static PlayerAccount account(long id, String rsn) {
        return new PlayerAccount(id, rsn, id, PlayerLinkService.METHOD_MAKEOVER_MAGE, OffsetDateTime.parse("2026-09-01T10:00:00Z"), 1L);
    }

    @Test
    void sendsEachChangeWithTheIdAsText() {
        HubLinkSync sync = sync();
        sync.linked(ALICE, "Zezima");
        assertEquals(1, sent.size());
        DataObject item = sent.get(0).getArray("items").getObject(0);
        assertEquals("add", item.getString("op"));
        assertEquals(Long.toString(ALICE), item.getString("discordId"));
        assertEquals("Zezima", item.getString("rsn"));
        assertFalse(sent.get(0).getBoolean("dryRun"));
        assertEquals(0, sync.held());
    }

    @Test
    void offSendsNothingButOneDryRunOfTheCatchUp() {
        on.set(false);
        accounts.add(account(ALICE, "Zezima"));
        HubLinkSync sync = sync();
        sync.linked(ALICE, "Zezima");
        assertEquals(0, sent.size());
        sync.round();
        sync.round();
        assertEquals(1, sent.size(), "one dry run only");
        assertTrue(sent.get(0).getBoolean("dryRun"));
    }

    @Test
    void turningOnCatchesUpOnceThenDaily() {
        for (int i = 0; i < 150; i++) accounts.add(account(ALICE + i, "N" + i));
        HubLinkSync sync = sync();
        sync.round();
        assertEquals(2, sent.size(), "150 accounts go in two batches");
        assertEquals(100, sent.get(0).getArray("items").length());
        assertEquals("2026-09-01T10:00Z", sent.get(0).getArray("items").getObject(0).getString("linkedAt"));
        sync.round();
        assertEquals(2, sent.size());
        now.set(now.get().plus(Duration.ofHours(25)));
        sync.round();
        assertEquals(4, sent.size());
    }

    @Test
    void aHubOutageKeepsTheNewestChangeForTheNextRound() {
        HubLinkSync sync = sync();
        accounts.clear();
        status.set(503);
        sync.linked(ALICE, "Zezima");
        sync.unlinked(ALICE, "zezima");
        assertEquals(1, sync.held(), "one change per person and name, the newest");
        status.set(200);
        sent.clear();
        sync.round();
        DataObject last = sent.get(sent.size() - 1).getArray("items").getObject(0);
        assertEquals("end", last.getString("op"));
        assertEquals(0, sync.held());
    }

    @Test
    void aRefusalIsDroppedNotRetried() {
        HubLinkSync sync = sync();
        status.set(400);
        sync.linked(BOB, "Durial");
        assertEquals(0, sync.held());
    }

    @Test
    void offWithoutAHubIsANoOp() {
        HubLinkSync off = HubLinkSync.off();
        off.linked(ALICE, "Zezima");
        off.round();
        assertFalse(off.on());
    }
}
