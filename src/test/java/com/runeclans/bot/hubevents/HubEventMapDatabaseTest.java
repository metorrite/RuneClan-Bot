package com.runeclans.bot.hubevents;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import com.runeclans.bot.testsupport.LocalDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubEventMapDatabaseTest {
    private static final long G = 999_000_000_000_000_934L;
    private static ConnectionSupplier supplier;
    private static HubEventMap map;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        map = new HubEventMap(supplier);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_map WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    @Test
    void aHubEventGetsOneLocalIdForGoodAndTheMapRemembersItsServerAndClan() {
        UUID hub = UUID.randomUUID();
        UUID clan = UUID.randomUUID();
        long local = map.localId(hub, clan, G);
        assertTrue(local >= com.runeclans.bot.hubcommunity.HubCommunityMaps.FIRST_LOCAL_ID, "local ids share the hub community range, clear of the bot's own event ids");
        assertEquals(local, map.localId(hub, clan, G), "the same event, the same id");
        assertEquals(local, new HubEventMap(supplier).localId(hub, clan, G), "a fresh map finds it in the table");
        assertEquals(hub, map.hubId(local).orElseThrow());
        assertEquals(G, map.event(local).orElseThrow().guildId());
        assertEquals(clan, map.eventByHubId(hub).orElseThrow().clanId());
        assertEquals(clan, map.guilds().get(G));
        assertEquals(1, map.eventsOf(G).size());
    }

    @Test
    void theSignupPanelAndUpdatePostOfAnEventAreKept() {
        long local = map.localId(UUID.randomUUID(), UUID.randomUUID(), G);
        assertTrue(map.eventsWithSignup().stream().noneMatch(r -> r.localId() == local));
        map.setSignup(local, 77L);
        assertEquals(77L, map.event(local).orElseThrow().signupId());
        assertTrue(map.eventsWithSignup().stream().anyMatch(r -> r.localId() == local));
        map.setPanel(local, 1L, 2L);
        map.setUpdatePost(local, 3L, 4L);
        HubEventMap.Row row = map.event(local).orElseThrow();
        assertEquals(2L, row.panelMessageId());
        assertEquals(4L, row.updateMessageId());
        map.setSignup(local, null);
        assertTrue(map.eventsWithSignup().stream().noneMatch(r -> r.localId() == local));
    }

    @Test
    void aRevisionIsSeenOnce() {
        long local = map.localId(UUID.randomUUID(), UUID.randomUUID(), G);
        assertTrue(map.seen(local, 3));
        assertFalse(map.seen(local, 3), "the same revision again");
        assertFalse(map.seen(local, 2), "an older one");
        assertTrue(map.seen(local, 4));
    }

    @Test
    void teamsClaimsAndAwardsGetLocalIdsOfTheirOwnAndGoWithTheirEvent() {
        long local = map.localId(UUID.randomUUID(), UUID.randomUUID(), G);
        UUID team = UUID.randomUUID();
        long teamLocal = map.localId(HubEventMap.TEAM, team, local);
        assertEquals(teamLocal, map.localId(HubEventMap.TEAM, team, local));
        assertEquals(team, map.hubId(HubEventMap.TEAM, teamLocal).orElseThrow());
        assertTrue(map.hubId(HubEventMap.CLAIM, teamLocal).isEmpty(), "a team id isn't a claim id");
        long claimLocal = map.localId(HubEventMap.CLAIM, UUID.randomUUID(), local);
        map.setReceipt(local, claimLocal, 10L, 11L);
        assertEquals(11L, map.receipt(claimLocal).orElseThrow().messageId());
        map.forget(local);
        assertTrue(map.event(local).isEmpty());
        assertTrue(map.hubId(HubEventMap.TEAM, teamLocal).isEmpty(), "gone with the event");
        assertTrue(map.receipt(claimLocal).isEmpty());
    }

    @Test
    void theSignupFollowsSomePlayersAndRemembersWhoWasTurnedAwayOnce() {
        long local = map.localId(UUID.randomUUID(), UUID.randomUUID(), G);
        map.addSignupEntry(local, 5L);
        map.addSignupEntry(local, 5L);
        map.addSignupEntry(local, 6L);
        assertEquals(2, map.signupEntries(local).size());
        map.removeSignupEntry(local, 5L);
        assertEquals(java.util.List.of(6L), map.signupEntries(local));

        assertTrue(map.recordRefusal(local, 7L, "Too late."), "told the first time");
        assertFalse(map.recordRefusal(local, 7L, "Too late."), "not told the same thing twice");
        assertTrue(map.recordRefusal(local, 7L, "Barred."), "told when the reason changes");
        assertEquals(1, map.refusals(local).size());
        assertEquals("Barred.", map.refusals(local).get(0).reason());
        assertTrue(map.refusals(local).get(0).at().isBefore(Instant.now().plusSeconds(5)));
        map.clearRefusal(local, 7L);
        assertTrue(map.refusals(local).isEmpty());
    }
}
