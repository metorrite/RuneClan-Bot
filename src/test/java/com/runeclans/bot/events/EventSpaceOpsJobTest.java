package com.runeclans.bot.events;

import com.runeclans.bot.jobs.JobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** T-698: an event's background job, started on one bot process, is seen and respected by another (they share the job table). */
class EventSpaceOpsJobTest {
    private static final long EVENT = 9;

    /** A clock the test can move forward, shared by both "processes". */
    private static final class MovableClock extends Clock {
        private volatile java.time.Instant now = java.time.Instant.now();

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final JobRepository jobs = JobRepository.inMemory(clock);
    private final EventSpaceOps one = ops();
    private final EventSpaceOps two = ops();

    private EventSpaceOps ops() {
        return new EventSpaceOps(mock(com.runeclans.bot.hubevents.HubEvents.class), mock(EventSpaceRepository.class), mock(EventSpaces.class), new EventChanges(), null, jobs);
    }

    @Test
    void aJobStartedOnOneProcessShowsItsProgressOnAnotherAndBlocksASecondStart() throws Exception {
        assertNull(two.job(EVENT));
        CountDownLatch stepped = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        one.start(EVENT, "setup", job -> {
            job.total(3);
            job.step("Made the role Bingo.");
            stepped.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            job.finish("All set up.");
        });
        assertTrue(stepped.await(5, TimeUnit.SECONDS));

        EventSpaceOps.Job seen = two.job(EVENT);
        assertEquals("setup", seen.kind());
        assertEquals("running", seen.state());
        assertEquals(1, seen.done());
        assertEquals(3, seen.total());
        assertEquals("Made the role Bingo.", seen.message());
        EventProblem refused = assertThrows(EventProblem.class, () -> two.start(EVENT, "removal", job -> job.finish("never")));
        assertEquals(409, refused.status());

        release.countDown();
        for (int i = 0; i < 100 && two.job(EVENT).running(); i++) Thread.sleep(20);
        assertEquals("done", two.job(EVENT).state());
        assertEquals("All set up.", two.job(EVENT).message());
        CountDownLatch ran = new CountDownLatch(1);
        two.start(EVENT, "removal", job -> {
            job.finish("Removed.");
            ran.countDown();
        });
        assertTrue(ran.await(5, TimeUnit.SECONDS));
    }

    @Test
    void aJobThatThrowsIsStoredAsFailed() throws Exception {
        one.start(EVENT, "setup", job -> {
            throw new IllegalStateException("Discord said no.");
        });
        for (int i = 0; i < 100 && (two.job(EVENT) == null || two.job(EVENT).running()); i++) Thread.sleep(20);
        assertEquals("failed", two.job(EVENT).state());
        assertEquals("Discord said no.", two.job(EVENT).message());
    }

    @Test
    void aJobThatWentQuietAndWasTakenOverCannotOverwriteTheNewOne() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch oldDone = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean keptWorking = new java.util.concurrent.atomic.AtomicBoolean();
        EventSpaceOps.Job old = one.start(EVENT, "setup", job -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                job.step("A late step."); // its first report after the takeover ends the work
                keptWorking.set(true);
                job.finish("Late finish.");
            } finally {
                oldDone.countDown();
            }
        });
        clock.now = clock.now.plus(JobRepository.STALE).plusSeconds(1); // the old job went silent
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch said = new CountDownLatch(1);
        two.start(EVENT, "removal", job -> {
            job.say("Removing #general…");
            said.countDown();
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            job.finish("Removed.");
        });
        assertTrue(said.await(5, TimeUnit.SECONDS));
        release.countDown();
        assertTrue(oldDone.await(5, TimeUnit.SECONDS));

        assertTrue(old.superseded());
        org.junit.jupiter.api.Assertions.assertFalse(keptWorking.get(), "the old job stopped changing roles and channels");
        EventSpaceOps.Job seen = one.job(EVENT);
        assertEquals("removal", seen.kind());
        assertEquals("running", seen.state(), "the old job's finish didn't free the slot");
        assertEquals("Removing #general…", seen.message());
        hold.countDown();
    }

    @Test
    void whenTheDatabaseCannotSayTheProgressPollAnswersTryAgain() {
        JobRepository down = mock(JobRepository.class);
        org.mockito.Mockito.when(down.latest(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong())).thenThrow(new IllegalStateException("down"));
        EventSpaceOps ops = new EventSpaceOps(mock(com.runeclans.bot.hubevents.HubEvents.class), mock(EventSpaceRepository.class), mock(EventSpaces.class), new EventChanges(), null, down);
        EventProblem problem = assertThrows(EventProblem.class, () -> ops.job(EVENT));
        assertEquals(503, problem.status());
    }

    @Test
    void whenTheDatabaseCannotTakeTheSlotTheStartAnswersTryAgain() {
        JobRepository down = mock(JobRepository.class);
        org.mockito.Mockito.when(down.claim(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("lock timeout"));
        EventSpaceOps ops = new EventSpaceOps(mock(com.runeclans.bot.hubevents.HubEvents.class), mock(EventSpaceRepository.class), mock(EventSpaces.class), new EventChanges(), null, down);
        EventProblem problem = assertThrows(EventProblem.class, () -> ops.start(EVENT, "setup", job -> job.finish("never")));
        assertEquals(503, problem.status());
    }
}
