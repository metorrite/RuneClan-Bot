package com.runeclans.bot.notice;

import com.runeclans.bot.config.BotConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotOwnersTest {
    private static final long APP_OWNER = 11;
    private static final long CONFIGURED = 22;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private BotConfig config;
    private JDA jda;
    private BotOwners owners;

    @BeforeEach
    void setUp() {
        config = mock(BotConfig.class);
        when(config.getOwnerIds()).thenReturn(List.of(CONFIGURED));
        jda = mock(JDA.class);
        owners = new BotOwners(config);
        owners.clock = now::get;
        owners.lookups = Runnable::run;
    }

    @SuppressWarnings("unchecked")
    private void discordSays(long ownerId) {
        ApplicationInfo info = mock(ApplicationInfo.class);
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(ownerId);
        when(info.getOwner()).thenReturn(user);
        CacheRestAction<ApplicationInfo> action = mock(CacheRestAction.class);
        when(action.complete()).thenReturn(info);
        when(jda.retrieveApplicationInfo()).thenReturn(action);
    }

    @SuppressWarnings("unchecked")
    private void discordIsDown() {
        CacheRestAction<ApplicationInfo> action = mock(CacheRestAction.class);
        when(action.complete()).thenThrow(new RejectedExecutionException("The Requester has been stopped!"));
        when(jda.retrieveApplicationInfo()).thenReturn(action);
    }

    @Test
    void theApplicationOwnerAndTheConfiguredOwnersAreOwners() {
        discordSays(APP_OWNER);

        assertTrue(owners.isOwner(jda, APP_OWNER));
        assertTrue(owners.isOwner(jda, CONFIGURED));
        assertFalse(owners.isOwner(jda, 99));
    }

    @Test
    void aFailedLookupDoesNotForgetWhoWasKnown() {
        discordSays(APP_OWNER);
        assertTrue(owners.isOwner(jda, APP_OWNER));

        now.addAndGet(11 * 60_000L); // the next call looks again, and Discord is unreachable
        discordIsDown();

        assertTrue(owners.isOwner(jda, APP_OWNER));
    }

    @Test
    void aFailedFirstLookupIsRetriedSoonNotAfterTenMinutes() {
        discordIsDown();
        assertFalse(owners.isOwner(jda, APP_OWNER));
        assertTrue(owners.isOwner(jda, CONFIGURED), "configured owners never depend on Discord");

        discordSays(APP_OWNER);
        now.addAndGet(31_000);

        assertTrue(owners.isOwner(jda, APP_OWNER));
    }

    @Test
    void aSuccessfulLookupIsRememberedForAWhile() {
        discordSays(APP_OWNER);
        owners.isOwner(jda, APP_OWNER);
        owners.isOwner(jda, APP_OWNER);

        verify(jda, times(1)).retrieveApplicationInfo();
    }

    @Test
    void theKnownOwnerCheckNeverAsksDiscord() {
        assertTrue(owners.isKnownOwner(CONFIGURED), "configured owners are always known");
        assertFalse(owners.isKnownOwner(APP_OWNER), "not known until a lookup found them");
        verify(jda, times(0)).retrieveApplicationInfo();

        discordSays(APP_OWNER);
        owners.isOwner(jda, APP_OWNER);
        assertTrue(owners.isKnownOwner(APP_OWNER), "known once a lookup found them");
        assertFalse(owners.isKnownOwner(99));
    }

    /** B-40: a slow Discord never holds a web request for long, and many requests make one lookup. */
    @Test
    @SuppressWarnings("unchecked")
    void aSlowDiscordIsWaitedOnBrieflyOnlyBeforeTheFirstAnswerAndAskedOnce() throws Exception {
        java.util.concurrent.ExecutorService lane = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            owners.lookups = lane;
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            ApplicationInfo info = mock(ApplicationInfo.class);
            User user = mock(User.class);
            when(user.getIdLong()).thenReturn(APP_OWNER);
            when(info.getOwner()).thenReturn(user);
            CacheRestAction<ApplicationInfo> action = mock(CacheRestAction.class);
            when(action.complete()).thenAnswer(a -> {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return info;
            });
            when(jda.retrieveApplicationInfo()).thenReturn(action);

            long start = System.nanoTime();
            assertFalse(owners.isOwner(jda, APP_OWNER), "not known yet");
            assertTrue(owners.isOwner(jda, CONFIGURED), "configured owners never wait on Discord");
            long waited = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(waited < 3 * BotOwners.FIRST_WAIT.toMillis() + 1_000, "waited " + waited + " ms");
            verify(jda, times(1)).retrieveApplicationInfo();

            release.countDown();
            lane.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(owners.isOwner(jda, APP_OWNER), "the lookup finished behind the requests");

            // stale after ten minutes: answered at once from who is known while the list is read again
            java.util.concurrent.CountDownLatch again = new java.util.concurrent.CountDownLatch(1);
            when(action.complete()).thenAnswer(a -> {
                again.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return info;
            });
            now.addAndGet(11 * 60_000L);
            start = System.nanoTime();
            assertTrue(owners.isOwner(jda, APP_OWNER));
            assertTrue(owners.isOwner(jda, APP_OWNER));
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis() < BotOwners.FIRST_WAIT.toMillis(), "no wait once known");
            again.countDown();
            lane.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            verify(jda, times(2)).retrieveApplicationInfo();
        } finally {
            lane.shutdownNow();
        }
    }
}
