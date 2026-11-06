package com.runeclans.bot.hubevents;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.testsupport.LocalDatabase;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubEventFeedTest {
    static final long G = 999_000_000_000_000_936L;
    static final UUID CLAN = UUID.randomUUID();
    static final UUID EVENT = UUID.randomUUID();
    private static ConnectionSupplier supplier;
    private static HubEventMap map;
    private FakeEventHub hub;
    private final List<EventChanges.Change> published = new ArrayList<>();
    private HubEventFeed feed;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        map = new HubEventMap(supplier);
    }

    @BeforeEach
    void setUp() {
        hub = new FakeEventHub();
        EventChanges changes = new EventChanges();
        changes.subscribe(published::add);
        HubEvents events = new HubEvents(new HubEventClient(hub, true), map, changes);
        feed = new HubEventFeed(events, map, changes) {
            @Override
            List<Long> candidateGuilds() {
                return List.of(G);
            }
        };
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_map WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    /** A feed entry as the poller hands it over: {@code {item, id, rev, data}} with the hub's {@code data} inside. */
    private static DataObject entry(UUID id, long rev, DataObject data) {
        return DataObject.empty().put("item", "event").put("id", id.toString()).put("rev", rev).put("data", data);
    }

    private static DataObject event(long rev) {
        return DataObject.empty().put("id", EVENT.toString()).put("clanId", CLAN.toString()).put("rev", rev).put("name", "Bingo").put("status", "SIGNUPS");
    }

    @Test
    void anEventMadeOnTheWebsiteIsMappedByTheServerTheEntryNamesAndEachRevisionIsActedOnOnce() {
        feed.onHubFeed("clan_event.created", entry(EVENT, 1, DataObject.empty().put("event", event(1)).put("clanId", CLAN.toString()).put("guildId", Long.toString(G))));
        HubEventMap.Row row = map.eventByHubId(EVENT).orElseThrow();
        assertEquals(G, row.guildId());
        assertEquals(1, published.size());
        assertEquals(EventChanges.Kind.SETUP, published.get(0).kind());
        assertEquals(row.localId(), published.get(0).eventId());
        assertTrue(hub.calls.isEmpty(), "no lookup needed");

        feed.onHubFeed("clan_event.updated", entry(EVENT, 1, DataObject.empty().put("event", event(1)).put("guildId", Long.toString(G))));
        assertEquals(1, published.size(), "the same revision again is not redrawn");
        feed.onHubFeed("clan_event.stage", entry(EVENT, 2, DataObject.empty().put("event", event(2)).put("to", "RUNNING").put("guildId", Long.toString(G))));
        assertEquals(2, published.size());
        assertEquals(EventChanges.Kind.STAGE, published.get(1).kind());
        assertEquals("RUNNING", published.get(1).note());
    }

    @Test
    void withoutAServerInTheEntryTheClansServerIsFoundAmongTheBotsOwn() {
        hub.ok("GET /v1/guilds/" + G + "/site", DataObject.empty().put("clanId", CLAN.toString()));
        feed.onHubFeed("clan_event.created", entry(EVENT, 1, DataObject.empty().put("event", event(1))));
        assertEquals(G, map.eventByHubId(EVENT).orElseThrow().guildId());
        feed.onHubFeed("clan_event.created", entry(UUID.randomUUID(), 1, DataObject.empty().put("event", event(1).put("id", UUID.randomUUID().toString()).put("clanId", UUID.randomUUID().toString()))));
        assertEquals(1, published.size(), "a clan of no server the bot is in is skipped");
    }

    @Test
    void aRosterChangeIsPassedOnAndAPlayersOwnJoinNamedWhenTheEntrySaysWho() {
        long local = map.localId(EVENT, CLAN, G);
        feed.onHubFeed("clan_event.participant", entry(EVENT, 2, DataObject.empty().put("event", event(2)).put("change", "joined").put("personId", "p").put("discordId", "55").put("guildId", Long.toString(G))));
        assertEquals(List.of(EventChanges.Kind.PLAYER_JOINED, EventChanges.Kind.ROSTER), published.stream().map(EventChanges.Change::kind).toList());
        assertEquals(55L, published.get(0).userId());
        published.clear();
        feed.onHubFeed("clan_event.participant", entry(EVENT, 3, DataObject.empty().put("event", event(3)).put("change", "left").put("personId", "p").put("guildId", Long.toString(G))));
        assertEquals(List.of(EventChanges.Kind.ROSTER), published.stream().map(EventChanges.Change::kind).toList(), "no Discord id: the roster change alone, for the mirror to reconcile");
        assertEquals(local, published.get(0).eventId());
        assertEquals(0L, published.get(0).userId());
    }

    @Test
    void aDecidedClaimReachesTheReceiptsWithItsLocalId() {
        long local = map.localId(EVENT, CLAN, G);
        UUID claim = UUID.randomUUID();
        long claimLocal = map.localId(HubEventMap.CLAIM, claim, local);
        DataObject json = DataObject.empty().put("id", claim.toString()).put("status", "DENIED").put("discordId", "55").put("decisionNote", "Wrong tile");
        feed.onHubFeed("clan_event.claim", entry(claim, 1, DataObject.empty().put("eventId", EVENT.toString()).put("change", "submitted").put("claim", json).put("guildId", Long.toString(G))));
        assertTrue(published.isEmpty(), "a submission is staff's to see on the website; nothing to redraw");
        feed.onHubFeed("clan_event.claim", entry(claim, 1, DataObject.empty().put("eventId", EVENT.toString()).put("change", "decided").put("claim", json).put("guildId", Long.toString(G))));
        assertEquals(1, published.size());
        EventChanges.Change change = published.get(0);
        assertEquals(EventChanges.Kind.CLAIM_DECIDED, change.kind());
        assertEquals(claimLocal, change.claimId());
        assertEquals(55L, change.userId());
        assertEquals("Wrong tile", change.note());
        assertTrue(!change.approved());
    }

    @Test
    void aDeletedEventIsForgottenAfterOneLastWord() {
        long local = map.localId(EVENT, CLAN, G);
        feed.onHubFeed("clan_event.deleted", entry(EVENT, 4, DataObject.empty().put("eventId", EVENT.toString()).put("clanId", CLAN.toString()).put("guildId", Long.toString(G))));
        assertEquals(EventChanges.Kind.STAGE, published.get(0).kind());
        assertEquals("deleted", published.get(0).note());
        assertEquals(local, published.get(0).eventId());
        assertTrue(map.event(local).isEmpty());
    }

    @Test
    void otherKindsAndEntriesWithoutAnIdAreIgnored() {
        feed.onHubFeed("poll.created", entry(EVENT, 1, DataObject.empty()));
        feed.onHubFeed("clan_event.created", DataObject.empty().put("data", DataObject.empty()));
        feed.onHubFeed(null, DataObject.empty());
        assertTrue(published.isEmpty());
        assertTrue(map.eventByHubId(EVENT).isEmpty());
    }
}
