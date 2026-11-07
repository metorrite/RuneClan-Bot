package com.runeclans.bot.access;

import com.runeclans.bot.notice.BotOwners;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RuneClan Admins read from the hub (T-981): the bot's owners, the hub's built-in and added admins, the cache and a hub that fails. */
class OverallAdminsTest {
    private static final long BOT_OWNER = Long.parseLong("1".repeat(18));
    private static final long HUB_OWNER = Long.parseLong("2".repeat(18));
    private static final long ADDED = Long.parseLong("3".repeat(18));
    private static final long STRANGER = Long.parseLong("4".repeat(18));
    private final JDA jda = mock(JDA.class);

    private static BotOwners owners() {
        BotOwners owners = mock(BotOwners.class);
        when(owners.isOwner(any(), anyLong())).thenReturn(false);
        when(owners.isOwner(any(), eq(BOT_OWNER))).thenReturn(true);
        return owners;
    }

    @Test
    void ownersAndTheHubsListAreAdmins() {
        OverallAdmins admins = OverallAdmins.of(() -> new OverallAdmins.HubList(Set.of(HUB_OWNER), Set.of(ADDED)), owners());
        assertTrue(admins.isOverallAdmin(jda, BOT_OWNER));
        assertTrue(admins.isBuiltIn(jda, BOT_OWNER));
        assertTrue(admins.isOverallAdmin(jda, HUB_OWNER));
        assertTrue(admins.isBuiltIn(jda, HUB_OWNER));
        assertTrue(admins.isOverallAdmin(jda, ADDED));
        assertFalse(admins.isBuiltIn(jda, ADDED));
        assertFalse(admins.isOverallAdmin(jda, STRANGER));
        assertFalse(admins.isOverallAdmin(jda, 0));
    }

    @Test
    void theListIsCachedThenRefreshedInTheBackgroundAndAFailureKeepsTheLastOne() {
        AtomicReference<Set<Long>> hub = new AtomicReference<>(Set.of(ADDED));
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        OverallAdmins admins = OverallAdmins.of(() -> {
            reads.incrementAndGet();
            if (failure.get() != null) throw failure.get();
            return new OverallAdmins.HubList(Set.of(), hub.get());
        }, owners());
        AtomicLong now = new AtomicLong(1_000_000);
        admins.clock = now::get;
        List<Runnable> queued = new ArrayList<>();
        admins.refresher = queued::add;

        assertTrue(admins.isOverallAdmin(jda, ADDED), "the first read is in line");
        assertEquals(1, reads.get());
        hub.set(Set.of());
        now.addAndGet(OverallAdmins.CACHE_MILLIS - 1);
        assertTrue(admins.isOverallAdmin(jda, ADDED), "still fresh");
        assertTrue(queued.isEmpty());

        now.addAndGet(1);
        assertTrue(admins.isOverallAdmin(jda, ADDED), "stale: the last list answers while a new one is read");
        assertEquals(1, queued.size());
        assertTrue(admins.isOverallAdmin(jda, ADDED));
        assertEquals(1, queued.size(), "one refresh at a time");
        queued.remove(0).run();
        assertFalse(admins.isOverallAdmin(jda, ADDED), "removed on the hub");

        // the hub fails: the last known list stays, and nobody is added
        hub.set(Set.of(ADDED));
        failure.set(new IllegalStateException("status 503"));
        now.addAndGet(OverallAdmins.CACHE_MILLIS);
        admins.isOverallAdmin(jda, ADDED);
        queued.remove(0).run();
        assertFalse(admins.isOverallAdmin(jda, ADDED));
        now.addAndGet(OverallAdmins.RETRY_MILLIS - 1);
        admins.isOverallAdmin(jda, ADDED);
        assertTrue(queued.isEmpty(), "waits before trying again");
        failure.set(null);
        now.addAndGet(1);
        admins.isOverallAdmin(jda, ADDED);
        queued.remove(0).run();
        assertTrue(admins.isOverallAdmin(jda, ADDED));

        // a change the bot heard of reads again at once
        hub.set(Set.of());
        admins.forget();
        admins.isOverallAdmin(jda, ADDED);
        queued.remove(0).run();
        assertFalse(admins.isOverallAdmin(jda, ADDED));
    }

    @Test
    void aFirstReadThatFailsMeansOnlyTheOwners() {
        OverallAdmins admins = OverallAdmins.of(() -> {
            throw new IllegalStateException("no answer");
        }, owners());
        assertTrue(admins.isOverallAdmin(jda, BOT_OWNER));
        assertFalse(admins.isOverallAdmin(jda, ADDED));
    }

    @Test
    void theHubsAnswerIsReadAsDiscordIdsOnly() {
        OverallAdmins.HubList list = OverallAdmins.read(DataObject.fromJson(
                "{\"builtIn\":[\"" + HUB_OWNER + "\"],\"admins\":[\"" + ADDED + "\",\"nope\",\"12\",\"" + "9".repeat(25) + "\"]}"));
        assertEquals(Set.of(HUB_OWNER), list.builtIn());
        assertEquals(Set.of(ADDED), list.added());
        assertEquals(OverallAdmins.HubList.EMPTY, OverallAdmins.read(DataObject.empty()));
    }

    @Test
    void withTheHubClientOffOnlyTheOwnersCount() {
        OverallAdmins admins = new OverallAdmins(null, owners());
        admins.refresher = Runnable::run;
        assertTrue(admins.isOverallAdmin(jda, BOT_OWNER));
        assertFalse(admins.isOverallAdmin(jda, ADDED));
    }
}
