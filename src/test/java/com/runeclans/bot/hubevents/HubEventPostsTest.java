package com.runeclans.bot.hubevents;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.alerts.LiveSwitch;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.EventBoardUpdates;
import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.events.EventUpdatePostJob;
import com.runeclans.bot.events.EventUpdateRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The posts the hub's event engine asks for (T-941): a draw's result and skill of the week's announcements go to the Discord outbox once each, where
 * the event's updates go; a prize award entry that added nothing, an entry with no words, and a bot that is not live post nothing. Over a real
 * database (skipped without one): the map and the once-only post record are the real ones.
 */
class HubEventPostsTest {
    static final long G = 999_000_000_000_000_937L;
    static final UUID CLAN = UUID.randomUUID();
    static final UUID EVENT = UUID.randomUUID();
    private static ConnectionSupplier supplier;
    private static HubEventMap map;
    private static EventUpdateRepository postRecord;
    private FakeEventHub hub;
    private EventBoardUpdates updates;
    private DiscordOutbox outbox;
    private HubEventPosts posts;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        map = new HubEventMap(supplier);
        postRecord = new EventUpdateRepository(supplier);
    }

    @BeforeEach
    void setUp() {
        LiveSwitch.set(true);
        hub = new FakeEventHub();
        HubEvents events = new HubEvents(new HubEventClient(hub, true), map, new EventChanges());
        hub.ok("GET /v1/events/" + EVENT, HubEventsTest.eventJson("RUNNING"));
        updates = mock(EventBoardUpdates.class);
        when(updates.channelFor(any(), any())).thenReturn(555L);
        outbox = mock(DiscordOutbox.class);
        posts = new HubEventPosts(events, postRecord, updates, outbox);
    }

    @AfterEach
    void cleanUp() throws Exception {
        AutoPostRouter.reset();
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_map WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private static DataObject entry(String id, DataObject data) {
        return DataObject.empty().put("item", "event_draw").put("id", id).put("rev", 1).put("data", data.put("eventId", EVENT.toString()).put("clanId", CLAN.toString()).put("guildId", Long.toString(G)));
    }

    @Test
    void aDrawIsPostedOnceWhereTheEventsUpdatesGo() {
        DataObject draw = entry("d1", DataObject.empty().put("key", "draw:1").put("text", "🎲 The winners are in!").put("round", 1));
        assertTrue(posts.dispatch(HubEventPosts.DRAW, draw));
        long local = map.eventByHubId(EVENT).orElseThrow().localId();
        ArgumentCaptor<DataObject> payload = ArgumentCaptor.forClass(DataObject.class);
        verify(outbox).enqueue(eq(EventUpdatePostJob.KIND), eq(G), eq(0L), eq("event-update:" + local + ":draw:1"), payload.capture());
        assertEquals("555", String.valueOf(payload.getValue().get("channelId")).replaceAll("\\D", ""));
        assertTrue(payload.getValue().toString().contains("winners are in"));

        assertFalse(posts.dispatch(HubEventPosts.DRAW, draw), "the same draw again (a feed page read twice) is not posted twice");
        verify(outbox, times(1)).enqueue(anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void anAwardEntryPostsOnlyWhenPrizesWereRecorded() {
        assertFalse(posts.dispatch(HubEventPosts.AWARD, entry("a1", DataObject.empty().put("change", "generated").put("added", 0))), "nothing new to say");
        assertFalse(posts.dispatch(HubEventPosts.AWARD, entry("a2", DataObject.empty().put("change", "moved").put("added", 1))), "a moved award isn't announced");
        assertTrue(posts.dispatch(HubEventPosts.AWARD, entry("a3", DataObject.empty().put("change", "generated").put("added", 3))));
        ArgumentCaptor<DataObject> payload = ArgumentCaptor.forClass(DataObject.class);
        verify(outbox).enqueue(eq(EventUpdatePostJob.KIND), eq(G), eq(0L), anyString(), payload.capture());
        assertTrue(payload.getValue().toString().contains("Spring bingo") && payload.getValue().toString().contains("3 awards"), payload.getValue().toString());
    }

    @Test
    void skillOfTheWeekIsPostedInTheClansChosenChannelAndNowhereWithoutOne() {
        assertFalse(posts.dispatch(HubEventPosts.SKILL_WEEK, entry("s0", DataObject.empty().put("key", "sotw:open:1").put("text", "This week: Mining!"))), "no channel chosen");
        assertTrue(posts.dispatch(HubEventPosts.SKILL_WEEK, entry("s1", DataObject.empty().put("key", "sotw:open:2").put("text", "This week: Mining!").put("channelId", "777"))));
        ArgumentCaptor<DataObject> payload = ArgumentCaptor.forClass(DataObject.class);
        verify(outbox).enqueue(eq(EventUpdatePostJob.KIND), eq(G), eq(0L), anyString(), payload.capture());
        assertEquals("777", String.valueOf(payload.getValue().get("channelId")).replaceAll("\\D", ""));
        verify(updates, never()).channelFor(any(), any());
    }

    @Test
    void anEntryWithoutWordsOrOnABotThatIsNotLivePostsNothing() {
        assertFalse(posts.dispatch(HubEventPosts.DRAW, entry("d2", DataObject.empty().put("key", "draw:2"))), "no text");
        LiveSwitch.set(false);
        assertFalse(posts.dispatch(HubEventPosts.DRAW, entry("d3", DataObject.empty().put("key", "draw:3").put("text", "words"))), "not live");
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), anyString(), any());
        posts.onHubFeed("clan_event.progress", entry("p1", DataObject.empty())); // not this listener's: nothing, and no error
    }
}
