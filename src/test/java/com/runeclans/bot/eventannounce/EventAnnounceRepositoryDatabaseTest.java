package com.runeclans.bot.eventannounce;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B-102 over real SQL: the role is saved before the post, and a claim whose post never went out can be given back. Skipped without a local database. */
class EventAnnounceRepositoryDatabaseTest {
    private static final long EVENT = 999_000_000_000_001_102L;
    private static final long POSTED = 999_000_000_000_001_103L;
    private static final long GUILD = 999_000_000_000_000_102L;
    private static ConnectionSupplier supplier;
    private EventAnnounceRepository repository;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventAnnounceRepository(supplier);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        try (Connection c = supplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.eventannounce_event WHERE event_id IN (?, ?)")) {
            s.setLong(1, EVENT);
            s.setLong(2, POSTED);
            s.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void aRoleSavedBeforeThePostIsPickedUpByTheSweep() {
        assertTrue(repository.claim(EVENT, GUILD));
        repository.setRole(EVENT, 77L);
        repository.setState(EVENT, EventAnnounceRules.State.COMPLETED, false); // a finished event: only the saved role keeps it on the sweep's list
        assertEquals(77L, repository.tracked(EVENT).orElseThrow().roleId());
        assertTrue(repository.open().stream().anyMatch(t -> t.eventId() == EVENT), "the sweep sees it, so the role is deleted when the event ends");
    }

    @Test
    void releaseGivesBackOnlyAnUnpostedClaim() {
        assertTrue(repository.claim(EVENT, GUILD));
        repository.release(EVENT);
        assertTrue(repository.tracked(EVENT).isEmpty());
        assertTrue(repository.claim(EVENT, GUILD), "the event can be claimed again");

        assertTrue(repository.claim(POSTED, GUILD));
        repository.setMessage(POSTED, 5L, 6L, null);
        repository.release(POSTED);
        assertTrue(repository.tracked(POSTED).isPresent(), "a posted announcement is never forgotten");
    }
}
