package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.hubevents.HubEventMap;
import com.runeclans.bot.hubevents.HubEvents;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The live board in Discord, drawn by the hub (T-934): when it is posted, where, and what is said when it can't be. */
class EventBoardPanelsTest {
    private static final long GUILD = 5L;
    private static final long EVENT = 11L;
    private static final long CHANNEL = 777L;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private HubEvents events;
    private HubEventMap map;
    private EventBoardPanels panels;
    private EventSpaceRepository space;
    private Guild guild;
    private GuildMessageChannel channel;
    private final List<String> calls = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        events = mock(HubEvents.class);
        map = mock(HubEventMap.class);
        when(events.map()).thenReturn(map);
        space = mock(EventSpaceRepository.class);
        panels = new EventBoardPanels(events, space, new EventChanges());

        JDA jda = mock(JDA.class);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        Field field = EventBoardPanels.class.getDeclaredField("jda");
        field.setAccessible(true);
        field.set(panels, jda);

        channel = mock(GuildMessageChannel.class);
        when(channel.getIdLong()).thenReturn(CHANNEL);
        when(channel.getName()).thenReturn("bingo");
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        net.dv8tion.jda.api.entities.SelfMember self = mock(net.dv8tion.jda.api.entities.SelfMember.class);
        when(self.hasPermission(any(GuildChannel.class), any(Permission[].class))).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);

        when(channel.sendMessage(any(CharSequence.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0).toString());
            return mock(MessageCreateAction.class, inner -> {
                calls.add(inner.getMethod().getName());
                return inner.getMethod().getReturnType().isInstance(inner.getMock()) ? inner.getMock() : null;
            });
        });

        when(events.objectives(EVENT)).thenReturn(List.of(new ObjectiveSpec("t1", "Nex: Torva boots", "", 5, Evidence.EITHER, 1, 1, List.of()),
                new ObjectiveSpec("t2", "Araxxor: Noxious blade", "", 5, Evidence.EITHER, 1, 1, List.of())));
        when(events.standings(GUILD, EVENT)).thenReturn(List.of());
        when(events.participants(EVENT)).thenReturn(List.of());
        when(events.boardPng(anyLong(), anyLong(), anyLong(), anyBoolean())).thenReturn(PNG);
        when(map.event(EVENT)).thenReturn(Optional.empty());
        when(map.boardPanels(EVENT)).thenReturn(Map.of());
    }

    private Event event(String template, Status status, String config) {
        Event event = new Event(EVENT, GUILD, template, "Bingo night", "", status, Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 50, config, 1L, Instant.now(), null, false);
        when(events.event(GUILD, EVENT)).thenReturn(event);
        return event;
    }

    private static final String BOARD = "\"board\":{\"keys\":[\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\",\"t7\",\"t8\",\"t9\"],\"size\":3}";

    @Test
    void aRunningBingoWithABoardChannelPostsTheHubsPicture() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD + "}");

        String result = panels.refresh(GUILD, EVENT);

        assertEquals("Posted the board in #bingo.", result);
        verify(channel).sendMessage(any(CharSequence.class));
        verify(events).boardPng(GUILD, EVENT, 0, false);
        assertTrue(calls.contains("addFiles"), "the board picture is attached: " + calls);
    }

    @Test
    void theBoardChannelTheBotMadeIsUsedWhenTheSettingsNameNone() {
        event(BingoTemplate.KEY, Status.RUNNING, "{" + BOARD + "}");
        when(space.channels(EVENT)).thenReturn(List.of(new EventSpaceRepository.ChannelRow(EVENT, "board", CHANNEL, "bingo", true, "category")));
        assertEquals("Posted the board in #bingo.", panels.refresh(GUILD, EVENT));
    }

    @Test
    void whenTheHubCannotDrawThePictureTheBoardIsPostedAsText() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD + "}");
        when(events.boardPng(anyLong(), anyLong(), anyLong(), anyBoolean())).thenThrow(new EventProblem(503, "The board picture couldn't be drawn right now."));
        when(events.standings(GUILD, EVENT)).thenReturn(List.of(new Standing("u:501", 5, 1, Map.of("t1", 1), null)));

        String result = panels.refresh(GUILD, EVENT);

        assertTrue(result.startsWith("Posted the board in #bingo."), result);
        assertTrue(result.contains("as text"), result);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("Bingo night"), "the event's name is there");
        assertTrue(sent.get(0).contains("⬜ **1.** Nex: Torva boots · 1/1"), "the tiles are listed with how many have them: " + sent.get(0));
        assertTrue(!calls.contains("addFiles"), "no picture is attached: " + calls);
    }

    @Test
    void everyReasonNothingIsPostedIsSaidInWords() {
        event(BingoTemplate.KEY, Status.RUNNING, "{" + BOARD + "}");
        assertEquals("Shared board: there is no live board channel yet. Choose or make one on the Channels tab.", panels.refresh(GUILD, EVENT));

        event(BingoTemplate.KEY, Status.SIGNUPS, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD + "}");
        assertEquals("The board is posted once the event starts.", panels.refresh(GUILD, EVENT));

        event("boss-night", Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"}");
        assertEquals("Only bingo events have a board.", panels.refresh(GUILD, EVENT));

        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"999\"," + BOARD + "}");
        assertTrue(panels.refresh(GUILD, EVENT).startsWith("The board channel isn't a channel the bot can post in"));

        when(events.event(GUILD, EVENT)).thenThrow(new EventProblem(404, "That event doesn't exist (any more)."));
        assertEquals("That event or server wasn't found.", panels.refresh(GUILD, EVENT));

        verify(channel, never()).sendMessage(any(CharSequence.class));
        verify(map, never()).setPanel(anyLong(), anyLong(), eq(0L));
    }

    @Test
    void withoutPermissionItSaysSoInsteadOfPostingNothing() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD + "}");
        net.dv8tion.jda.api.entities.SelfMember self = mock(net.dv8tion.jda.api.entities.SelfMember.class);
        when(self.hasPermission(any(GuildChannel.class), any(Permission[].class))).thenReturn(false);
        when(guild.getSelfMember()).thenReturn(self);

        assertEquals("The bot needs to see, send messages and attach files in #bingo.", panels.refresh(GUILD, EVENT));
    }

    // ---------- each player's or team's own board ----------

    private EventRepository.Participant player(long userId) {
        return new EventRepository.Participant(EVENT, userId, GUILD, null, com.runeclans.bot.events.TeamRules.Kind.HOME_CLAN, false, PrizeRules.BuyInStatus.NONE, Instant.now(),
                EventRoster.Verification.PENDING, null, null, "", null, EventRepository.RosterSource.STAFF);
    }

    @Test
    void withOwnBoardsEachPlayerGetsTheirOwnPictureInTheirOwnChannelAndWhoHasNoPlaceIsSaid() {
        event(BingoTemplate.KEY, Status.RUNNING, "{" + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"OWN\"}") + "}");
        when(events.participants(EVENT)).thenReturn(List.of(player(501), player(502)));
        when(space.channels(EVENT)).thenReturn(List.of(new EventSpaceRepository.ChannelRow(EVENT, "player:501", CHANNEL, "bingo-501", true, "category")));

        String result = panels.refresh(GUILD, EVENT);

        assertEquals("Own boards: 1 posted, 0 updated, 0 unchanged, 1 with nowhere to post (set up the event's channels, or choose a live board channel).", result);
        verify(channel).sendMessage(any(CharSequence.class));
        verify(events).boardPng(GUILD, EVENT, 501L, true);
        assertTrue(sent.get(0).contains("0 of 9 tiles done"), sent.get(0));
    }

    @Test
    void ownBoardsNeedNoLiveBoardChannelAndAPlayerWithoutAChannelUsesItWhenThereIsOne() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"OWN\"}") + "}");
        when(events.participants(EVENT)).thenReturn(List.of(player(501), player(502)));

        String result = panels.refresh(GUILD, EVENT);

        assertEquals("Own boards: 2 posted, 0 updated, 0 unchanged.", result, "no channels of their own: both go in the live board channel");
        verify(channel, org.mockito.Mockito.times(2)).sendMessage(any(CharSequence.class));
    }

    @Test
    void anUnchangedOwnBoardIsNotFetchedOrSentAgain() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"OWN\"}") + "}");
        when(events.participants(EVENT)).thenReturn(List.of(player(501)));
        List<String> keys = BingoBoardView.boardKeys(net.dv8tion.jda.api.utils.data.DataObject.fromJson("{" + BOARD + "}"));
        String same = EventBoardPanels.hashOf(keys, null);
        when(map.boardPanels(EVENT)).thenReturn(Map.of("u:501", new HubEventMap.BoardPanel(CHANNEL, 900L, same)));

        assertEquals("Own boards: 0 posted, 0 updated, 1 unchanged.", panels.refresh(GUILD, EVENT));
        verify(events, never()).boardPng(anyLong(), anyLong(), anyLong(), anyBoolean());
        assertNotEquals(same, EventBoardPanels.hashOf(keys, new Standing("u:501", 5, 1, Map.of("t1", 1), null)), "a tile done changes the fingerprint");
    }

    @Test
    void anOwnBoardsEventPostsNoSharedBoardAndABothEventPostsBoth() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"OWN\"}") + "}");
        when(events.participants(EVENT)).thenReturn(List.of(player(501)));
        assertEquals("Own boards: 1 posted, 0 updated, 0 unchanged.", panels.refresh(GUILD, EVENT), "no 'Posted the board' sentence: only their own");

        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"BOTH\"}") + "}");
        String both = panels.refresh(GUILD, EVENT);
        assertTrue(both.startsWith("Posted the board in #bingo."), both);
        assertTrue(both.contains("Own boards: 1 posted"), both);
    }

    @Test
    void ownBoardsWithNobodyInTheEventSaySo() {
        event(BingoTemplate.KEY, Status.RUNNING, "{" + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"OWN\"}") + "}");
        assertEquals("No own boards yet: nobody is in the event.", panels.refresh(GUILD, EVENT));
    }

    @Test
    void theBoardSaysXpTilesCountRuneSyncXpOnlyWhileTheyAreCounted() {
        Instant at = Instant.parse("2026-10-20T18:00:00Z");
        Event bingo = new Event(EVENT, GUILD, BingoTemplate.KEY, "Bingo", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, at, at.plusSeconds(86_400), 0, "{}", 1, at, null, false);
        List<ObjectiveSpec> tiles = List.of(new ObjectiveSpec("t1", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of()));
        try {
            assertEquals("", EventBoardPanels.xpTilesNote(bingo, tiles), "off (the default): the board is as before");
            PluginObjectiveMatcher.offerXpTiles(true);
            assertTrue(EventBoardPanels.xpTilesNote(bingo, tiles).contains("XP tiles count RuneSync XP only"));
            assertEquals("", EventBoardPanels.xpTilesNote(bingo, List.of(new ObjectiveSpec("t2", "Nex: Torva boots", "", 1, Evidence.EITHER, 1, 1, List.of()))), "no XP tile");
        } finally {
            PluginObjectiveMatcher.offerXpTiles(false);
        }
    }
}
