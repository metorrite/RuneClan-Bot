package com.runeclans.bot.signup;

import com.runeclans.bot.commands.signup.SignupType;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** B-39: a capped sheet never ends with more entries than its cap, however many joins race for the last places. Skipped without a local Postgres. */
class SignupCapRaceDatabaseTest {
    private static final long GUILD = 987654321012361L;
    private static final long OWNER = 31L;

    private ConnectionSupplier db;
    private SignupRepository signups;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        signups = new SignupRepository(db, new SignupDatabaseInitializer(db));
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            for (String sql : new String[]{
                    "DELETE FROM younglings.signup_entry WHERE signup_id IN (SELECT signup_id FROM younglings.signup WHERE guild_id = ?)",
                    "DELETE FROM younglings.signup_message WHERE signup_id IN (SELECT signup_id FROM younglings.signup WHERE guild_id = ?)",
                    "DELETE FROM younglings.signup WHERE guild_id = ?"}) {
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, GUILD);
                    s.executeUpdate();
                }
            }
        }
    }

    @Test
    void racingJoinsForTheLastPlacesNeverGoPastTheCap() throws Exception {
        int cap = 3;
        long signupId = signups.createSignup(GUILD, "Boss team", SignupType.QUEUE, null, null, cap, OWNER, null);
        assertTrue(signups.addEntry(signupId, 100L, "First", null, 100L, SignupType.QUEUE, cap));

        int joiners = 8;
        ExecutorService pool = Executors.newFixedThreadPool(joiners);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < joiners; i++) {
                long user = 200L + i;
                results.add(pool.submit(() -> {
                    go.await();
                    return signups.addEntry(signupId, user, "Player " + user, null, user, SignupType.QUEUE, cap);
                }));
            }
            go.countDown();
            int added = 0;
            for (Future<Boolean> result : results) if (result.get(30, TimeUnit.SECONDS)) added++;
            assertEquals(cap - 1, added, "only the free places are taken");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(cap, signups.getEntryCount(signupId));
    }

    @Test
    void aFullSheetRefusesTheNextJoinAndAnUncappedOneTakesIt() {
        long capped = signups.createSignup(GUILD, "Pair", SignupType.QUEUE, null, null, 1, OWNER, null);
        assertTrue(signups.addEntry(capped, 100L, "First", null, 100L, SignupType.QUEUE, 1));
        assertFalse(signups.addEntry(capped, 101L, "Second", null, 101L, SignupType.QUEUE, 1));

        long open = signups.createSignup(GUILD, "Anyone", SignupType.QUEUE, null, null, null, OWNER, null);
        assertTrue(signups.addEntry(open, 100L, "First", null, 100L, SignupType.QUEUE, null));
        assertTrue(signups.addEntry(open, 101L, "Second", null, 101L, SignupType.QUEUE));
        assertEquals(2, signups.getEntryCount(open));
    }
}
