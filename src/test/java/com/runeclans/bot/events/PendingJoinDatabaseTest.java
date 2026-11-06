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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pending joins and panels of co-host servers (T-221), keyed by hub events' local ids (T-934), on the developer's local Postgres. Skipped where there is none. */
class PendingJoinDatabaseTest {
    private static final long HOST = 999_000_000_000_000_081L, ALLY = 999_000_000_000_000_082L, OTHER_HOST = 999_000_000_000_000_083L;
    private static final UUID CLAN = UUID.randomUUID(), OTHER_CLAN = UUID.randomUUID();

    private static ConnectionSupplier supplier;
    private static HubEventMap map;
    private EventCohostRepository cohosts;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        map = new HubEventMap(supplier);
    }

    @BeforeEach
    void setUp() {
        cohosts = new EventCohostRepository(supplier, map);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection()) {
            for (String table : List.of("event_pending_join", "event_cohost")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE event_id IN (SELECT local_id FROM younglings.hub_event_map WHERE guild_id IN (?, ?))")) {
                    s.setLong(1, HOST);
                    s.setLong(2, OTHER_HOST);
                    s.executeUpdate();
                }
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_map WHERE guild_id IN (?, ?)")) {
                s.setLong(1, HOST);
                s.setLong(2, OTHER_HOST);
                s.executeUpdate();
            }
        }
    }

    /** A hub event of {@code host}, as the bot meets it: a local id in the map. */
    private long event(long host) {
        return map.localId(UUID.randomUUID(), host == HOST ? CLAN : OTHER_CLAN, host);
    }

    @Test
    void pendingJoinsAreFoundOnlyForTheHostTheyWaitForAndOnlyUntilTheyExpire() {
        long mine = event(HOST), theirs = event(OTHER_HOST);
        Instant now = Instant.parse("2026-10-22T10:00:00Z");
        cohosts.savePendingJoin(mine, 7, ALLY, now.plusSeconds(3_600));
        cohosts.savePendingJoin(theirs, 7, ALLY, now.plusSeconds(3_600));
        assertEquals(List.of(mine), cohosts.pendingJoins(7, HOST, now).stream().map(EventCohostRepository.PendingJoin::eventId).toList());
        assertTrue(cohosts.pendingJoins(7, HOST, now.plusSeconds(3_601)).isEmpty(), "expired");

        cohosts.savePendingJoin(mine, 7, ALLY, now.plusSeconds(7_200)); // pressing Join again renews it
        assertEquals(1, cohosts.pendingJoins(7, HOST, now.plusSeconds(3_601)).size());
        cohosts.deletePendingJoins(mine, ALLY);
        assertTrue(cohosts.pendingJoins(7, HOST, now).isEmpty());
        assertEquals(1, cohosts.purgeExpiredPendingJoins(now.plusSeconds(4_000)), "the other host's one has expired by then");
    }
}
