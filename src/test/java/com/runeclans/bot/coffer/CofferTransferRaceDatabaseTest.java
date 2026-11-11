package com.runeclans.bot.coffer;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A coffer transfer is accepted once (bug hunt B-18): two Accept clicks handled at the same time move the gp once, a click after it was
 * accepted or rejected changes nothing and says so, and a reject can't undo an accept. Skipped without a local Postgres.
 */
class CofferTransferRaceDatabaseTest {
    private static final long GUILD = 987654321012361L;
    private static final long ANN = 31L;
    private static final long BOB = 32L;

    private ConnectionSupplier db;
    private CofferRepository coffer;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        coffer = new CofferRepository(db, new CofferDatabaseInitializer(db));
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            for (String table : new String[]{"coffer_donation", "coffer_holder", "coffer_transfer", "coffer_giveaway"}) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                    s.setLong(1, GUILD);
                    s.executeUpdate();
                }
            }
        }
    }

    private long pendingTransfer(long amount) {
        return coffer.insertPendingTransfer(GUILD, ANN, BOB, amount);
    }

    @RepeatedTest(5)
    void twoAcceptsAtOnceMoveTheGpOnce() throws Exception {
        coffer.insertDonation(GUILD, "Donor", 10_000_000L, ANN);
        long id = pendingTransfer(1_000_000L);

        int clicks = 8;
        ExecutorService pool = Executors.newFixedThreadPool(clicks);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<CofferRepository.AcceptResult>> results = new ArrayList<>();
        for (int i = 0; i < clicks; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return coffer.acceptTransfer(id);
            }));
        }
        go.countDown();
        int accepted = 0;
        for (Future<CofferRepository.AcceptResult> r : results) {
            CofferRepository.AcceptResult result = r.get(30, TimeUnit.SECONDS);
            if (result == CofferRepository.AcceptResult.ACCEPTED) accepted++;
            else assertEquals(CofferRepository.AcceptResult.ALREADY_RESOLVED, result);
        }
        pool.shutdown();

        assertEquals(1, accepted, "exactly one click wins");
        assertEquals(9_000_000L, coffer.getHolderBalance(GUILD, ANN), "the sender pays once");
        assertEquals(1_000_000L, coffer.getHolderBalance(GUILD, BOB), "the recipient is paid once");
    }

    @Test
    void anAcceptAfterARejectChangesNothing() {
        coffer.insertDonation(GUILD, "Donor", 5_000_000L, ANN);
        long id = pendingTransfer(1_000_000L);

        assertTrue(coffer.rejectTransfer(id));
        assertEquals(CofferRepository.AcceptResult.ALREADY_RESOLVED, coffer.acceptTransfer(id));
        assertEquals(5_000_000L, coffer.getHolderBalance(GUILD, ANN));
        assertEquals(0L, coffer.getHolderBalance(GUILD, BOB));
        assertFalse(coffer.rejectTransfer(id), "a second reject says nothing changed");
    }

    @Test
    void aRejectAfterAnAcceptChangesNothing() {
        coffer.insertDonation(GUILD, "Donor", 5_000_000L, ANN);
        long id = pendingTransfer(1_000_000L);

        assertEquals(CofferRepository.AcceptResult.ACCEPTED, coffer.acceptTransfer(id));
        assertFalse(coffer.rejectTransfer(id));
        assertEquals(List.of(1_000_000L), coffer.getRecentTransfers(GUILD, 50).stream().map(t -> t.amount()).toList(), "it stays completed");
    }

    @Test
    void aTransferTheSenderCanNoLongerCoverStaysPending() {
        coffer.insertDonation(GUILD, "Donor", 500_000L, ANN);
        long id = pendingTransfer(1_000_000L);

        assertEquals(CofferRepository.AcceptResult.INSUFFICIENT_BALANCE, coffer.acceptTransfer(id));
        assertEquals("PENDING", coffer.getPendingTransfer(id).status());

        coffer.insertDonation(GUILD, "Donor", 500_000L, ANN);
        assertEquals(CofferRepository.AcceptResult.ACCEPTED, coffer.acceptTransfer(id), "it can be accepted once the sender holds enough");
        assertEquals(0L, coffer.getHolderBalance(GUILD, ANN));
    }

    @Test
    void anUnknownTransferIsAlreadyResolved() {
        assertEquals(CofferRepository.AcceptResult.ALREADY_RESOLVED, coffer.acceptTransfer(Long.MAX_VALUE));
    }
}
