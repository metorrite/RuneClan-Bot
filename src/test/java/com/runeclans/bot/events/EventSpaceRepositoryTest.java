package com.runeclans.bot.events;

import com.runeclans.bot.hubevents.HubEventMap;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is recorded about an event's roles and channels, on the developer's local Postgres. Skipped where there is none. Events are the hub's (T-934): the
 * rows name them by their local id in {@link HubEventMap}, which is also where a server's events are looked up. Everything is made in made-up servers
 * and removed afterwards.
 */
class EventSpaceRepositoryTest {
    private static final long G = 999_000_000_000_000_031L;
    private static final long OTHER = 999_000_000_000_000_032L;
    private static final UUID CLAN = UUID.randomUUID(), OTHER_CLAN = UUID.randomUUID();

    private static ConnectionSupplier supplier;
    private static HubEventMap map;
    private EventSpaceRepository space;
    private long eventId;
    private long otherEventId;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        map = new HubEventMap(supplier);
    }

    @BeforeEach
    void setUp() {
        space = new EventSpaceRepository(supplier, map);
        eventId = map.localId(UUID.randomUUID(), CLAN, G);
        otherEventId = map.localId(UUID.randomUUID(), OTHER_CLAN, OTHER);
    }

    @AfterEach
    void cleanUp() throws Exception {
        space.clear(eventId);
        space.clear(otherEventId);
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_map WHERE guild_id IN (?, ?)")) {
            s.setLong(1, G);
            s.setLong(2, OTHER);
            s.executeUpdate();
        }
    }

    @Test
    void rolesAndChannelsAreRecordedAndReadBack() {
        space.saveRole(eventId, "participant", 111, "First event Participant");
        space.saveRole(eventId, "team:5", 112, "First event Red");
        space.saveChannel(eventId, "category", 221, "First event", true, null);
        space.saveChannel(eventId, "general", 222, "first-event-general", true, "category");
        space.saveChannel(eventId, "board", 223, "bingo-board", false, null);

        assertEquals(List.of("participant", "team:5"), space.roles(eventId).stream().map(EventSpaceRepository.RoleRow::key).toList());
        assertEquals(112L, space.roles(eventId).get(1).roleId());
        List<EventSpaceRepository.ChannelRow> channels = space.channels(eventId);
        assertEquals(3, channels.size());
        assertEquals("category", channels.get(1).parent());
        assertFalse(channels.stream().filter(c -> c.key().equals("board")).findFirst().orElseThrow().owned(), "a chosen channel is recorded, not owned");
        assertTrue(space.hasSpace(eventId));
        assertFalse(space.hasSpace(otherEventId));
        assertEquals(List.of(eventId), space.eventsWithSpace(G));
    }

    @Test
    void savingAgainReplacesTheRecordAndANewRoleStartsWithNoHolders() {
        space.saveRole(eventId, "participant", 111, "Players");
        space.addMember(eventId, "participant", 7);
        space.addMember(eventId, "participant", 8);
        space.saveRole(eventId, "participant", 111, "Players renamed");
        assertEquals(Set.of(7L, 8L), space.members(eventId).get("participant"), "the same role keeps its holders");
        assertEquals("Players renamed", space.roles(eventId).get(0).name());

        space.saveRole(eventId, "participant", 999, "Players again");
        assertTrue(space.members(eventId).isEmpty(), "someone deleted the role on Discord, so nobody holds the new one yet");
        assertEquals(1, space.roles(eventId).size());
    }

    @Test
    void whoHoldsARoleIsRecordedAndForgottenOneByOne() {
        space.saveRole(eventId, "participant", 111, "Players");
        space.saveRole(eventId, "organiser", 112, "Organisers");
        space.addMember(eventId, "participant", 7);
        space.addMember(eventId, "participant", 7);
        space.addMember(eventId, "organiser", 7);
        space.addMember(eventId, "participant", 8);
        assertEquals(Map.of("participant", Set.of(7L, 8L), "organiser", Set.of(7L)), space.members(eventId));
        space.removeMember(eventId, "participant", 8);
        assertEquals(Set.of(7L), space.members(eventId).get("participant"));
        assertEquals(List.of(eventId), space.eventsHeldBy(G, 7));
        assertTrue(space.eventsHeldBy(G, 8).isEmpty());
        assertTrue(space.eventsHeldBy(OTHER, 7).isEmpty(), "another server's events don't count");
    }

    @Test
    void aChannelIsFoundBackToItsEventWithinTheServerOnly() {
        space.saveChannel(eventId, "general", 222, "first-event-general", true, "category");
        space.saveChannel(otherEventId, "general", 333, "their-general", true, "category");
        assertEquals(eventId, space.channelById(G, 222).orElseThrow().eventId());
        assertTrue(space.channelById(G, 333).isEmpty(), "that channel belongs to another server's event");
        assertTrue(space.channelById(G, 12345).isEmpty());
        assertEquals(Map.of(222L, eventId), space.channelsOfOtherEvents(G, otherEventId));
        assertTrue(space.channelsOfOtherEvents(G, eventId).isEmpty());
    }

    @Test
    void forgettingEverythingClearsTheRolesChannelsAndHolders() {
        space.saveRole(eventId, "participant", 111, "Players");
        space.addMember(eventId, "participant", 7);
        space.saveChannel(eventId, "general", 222, "first-event-general", true, "category");
        space.clear(eventId);
        assertTrue(space.roles(eventId).isEmpty());
        assertTrue(space.channels(eventId).isEmpty());
        assertTrue(space.members(eventId).isEmpty());
        assertFalse(space.hasSpace(eventId));
    }

    @Test
    void anEventTheHubForgotIsNoLongerAmongTheServersEventsWithChannels() {
        space.saveChannel(eventId, "general", 222, "first-event-general", true, "category");
        map.forget(eventId);
        assertTrue(space.eventsWithSpace(G).isEmpty(), "the sweep no longer meets it");
        assertTrue(space.channels(eventId).size() == 1, "its rows stay until the ops clear them (the deletion is announced on the feed)");
    }
}
