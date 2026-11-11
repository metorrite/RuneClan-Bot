package com.runeclans.bot.giveaway;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A giveaway ends once (bug hunt B-48): the sweep, the dashboard's End now job and /giveaway end reaching it together draw and announce
 * one set of winners; the others are told it already ended. Skipped without a local Postgres.
 */
class GiveawayEndRaceDatabaseTest {
    private static final long GUILD = 987654321012371L;
    private static final long CHANNEL = 987654321012372L;

    private ConnectionSupplier db;
    private GiveawayRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        repository = new GiveawayRepository(db);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.giveaway WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        }
    }

    private long giveaway() {
        return repository.create(GUILD, CHANNEL, "1m gp", 41L, 1, Instant.now().minusSeconds(1), null, null, false, false);
    }

    @Test
    void anOpenGiveawayIsClaimedOnce() {
        long id = giveaway();
        assertTrue(repository.claimEnd(id));
        assertFalse(repository.claimEnd(id), "the second claim finds it ended");
        assertEquals(Giveaway.Status.ENDED, repository.get(GUILD, id).orElseThrow().status());
    }

    @RepeatedTest(5)
    void endingTogetherEndsItOnce() throws Exception {
        long id = giveaway();
        repository.addEntry(id, GUILD, 51L);
        Guild guild = mock(Guild.class); // no channel: nothing is posted, the draw falls back to everyone who entered
        when(guild.getIdLong()).thenReturn(GUILD);
        GiveawayService service = new GiveawayService(repository);
        Giveaway open = repository.get(GUILD, id).orElseThrow(); // every caller saw it open, as the sweep and the job both do

        int callers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Giveaway>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return service.end(guild, open);
            }));
        }
        go.countDown();
        int ended = 0;
        for (Future<Giveaway> r : results) {
            try {
                r.get(30, TimeUnit.SECONDS);
                ended++;
            } catch (ExecutionException e) {
                assertInstanceOf(GiveawayService.GiveawayException.class, e.getCause());
            }
        }
        pool.shutdown();

        assertEquals(1, ended, "one caller draws and announces");
        assertEquals(List.of(51L), repository.get(GUILD, id).orElseThrow().winners());
    }

    @Test
    void anEndedGiveawayCantBeEndedAgain() {
        long id = giveaway();
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        GiveawayService service = new GiveawayService(repository);
        Giveaway open = repository.get(GUILD, id).orElseThrow();
        service.end(guild, open);
        assertThrows(GiveawayService.GiveawayException.class, () -> service.end(guild, open), "a stale copy that still says open is refused");
    }
}
