package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every board update rule (T-650) against a pretend Discord server: the board's timing (after every approval, every N minutes, once a day at reset),
 * the daily recap and each milestone switch, all off unless switched on, nothing posted by a bot that isn't live, and posts that go through the outbox
 * with no mentions allowed.
 */
class EventBoardUpdateRulesTest {
    private static final long GUILD = 5L;
    private static final long EVENT = 11L;
    private static final long BOARD_CHANNEL = 777L;
    private static final long ANNOUNCE_CHANNEL = Long.parseLong("4".repeat(17));
    private static final Instant T = Instant.parse("2026-10-27T14:00:00Z");
    private static final String BOARD = "\"boardChannelId\":\"" + BOARD_CHANNEL + "\",\"board\":{\"keys\":[\"a\",\"b\",\"c\",\"d\"],\"size\":2},"
            + "\"bonuses\":[{\"name\":\"Row 1\",\"keys\":[\"a\",\"b\"],\"points\":50},{\"name\":\"Row 2\",\"keys\":[\"c\",\"d\"],\"points\":50},"
            + "{\"name\":\"Blackout\",\"keys\":[\"a\",\"b\",\"c\",\"d\"],\"points\":250}]";

    private com.runeclans.bot.hubevents.HubEvents events;
    private EventSpaceRepository space;
    private EventBoardPanels panels;
    private EventBoardUpdates updates;
    private JDA jda;
    private Guild guild;
    /** The pretend server's channels: what was sent to each. */
    private final Map<Long, List<String>> sent = new java.util.HashMap<>();
    private final Map<Long, List<Collection<Message.MentionType>>> mentionsAllowed = new java.util.HashMap<>();
    /** What was handed to the outbox. */
    private final List<DiscordJob> queued = new ArrayList<>();
    /** The posts noted as made (the real table's primary key). */
    private final Set<String> claimed = new HashSet<>();
    private final List<EventRepository.Progress> progress = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        events = mock(com.runeclans.bot.hubevents.HubEvents.class);
        com.runeclans.bot.hubevents.HubEventMap map = mock(com.runeclans.bot.hubevents.HubEventMap.class);
        when(events.map()).thenReturn(map);
        when(map.boardPanels(anyLong())).thenReturn(java.util.Map.of());
        space = mock(EventSpaceRepository.class);
        EventUpdateRepository posts = mock(EventUpdateRepository.class);
        when(posts.claim(anyLong(), anyString(), anyString(), anyString())).thenAnswer(inv -> claimed.add(inv.getArgument(0) + "/" + inv.getArgument(1)));
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        when(outbox.enqueue(anyString(), anyLong(), anyLong(), anyString(), any(DataObject.class))).thenAnswer(inv -> {
            DiscordJob job = new DiscordJob(queued.size() + 1, inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4),
                    DiscordJob.State.QUEUED, 0, 0, null, null, null, Instant.now(), Instant.now());
            queued.add(job);
            return job;
        });
        EventChanges changes = new EventChanges();
        panels = new EventBoardPanels(events, space, changes);
        updates = new EventBoardUpdates(events, space, posts, outbox, changes);

        jda = mock(JDA.class);
        guild = mock(Guild.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        for (Object target : new Object[] {panels, updates}) {
            Field field = target.getClass().getDeclaredField("jda");
            field.setAccessible(true);
            field.set(target, jda);
        }
        SelfMember self = mock(SelfMember.class);
        when(self.hasPermission(any(GuildChannel.class), any(Permission[].class))).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);
        channel(BOARD_CHANNEL, "bingo-board");
        channel(ANNOUNCE_CHANNEL, "announcements");
        Member zaros = mock(Member.class);
        when(zaros.getEffectiveName()).thenReturn("Zaros");
        when(guild.getMemberById(501L)).thenReturn(zaros);

        when(events.boardPng(anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(new byte[] {1, 2, 3});
        when(events.objectives(EVENT)).thenReturn(List.of(tile("a", "Nex: Torva boots"), tile("b", "Araxxor: Noxious blade"), tile("c", "Zamorak: Codex"), tile("d", "Kerapac: Staff")));
        when(events.standings(GUILD, EVENT)).thenReturn(List.of(new Standing("u:501", 60, 2, Map.of(), null)));
        when(events.bonusesOf(any(Event.class))).thenAnswer(inv -> EventBonuses.parse(DataObject.fromJson(((Event) inv.getArgument(0)).config())));
        when(events.participants(EVENT)).thenReturn(List.of());
        when(events.progress(EVENT)).thenReturn(progress);
        when(space.channels(EVENT)).thenReturn(List.of());
    }

    @AfterEach
    void live() {
        AutoPostRouter.reset();
    }

    private static ObjectiveSpec tile(String key, String label) {
        return new ObjectiveSpec(key, label, "", 5, Evidence.EITHER, 1, 1, List.of());
    }

    private void channel(long id, String name) {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.getIdLong()).thenReturn(id);
        when(channel.getId()).thenReturn(String.valueOf(id));
        when(channel.getName()).thenReturn(name);
        when(channel.canTalk()).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, id)).thenReturn(channel);
        when(channel.sendMessage(any(CharSequence.class))).thenAnswer(inv -> {
            sent.computeIfAbsent(id, k -> new ArrayList<>()).add(inv.getArgument(0).toString());
            Message message = mock(Message.class);
            when(message.getId()).thenReturn("900");
            MessageCreateAction action = mock(MessageCreateAction.class, a -> {
                if (a.getMethod().getName().equals("setAllowedMentions")) mentionsAllowed.computeIfAbsent(id, k -> new ArrayList<>()).add(a.getArgument(0));
                if (a.getMethod().getName().equals("complete")) return message;
                return a.getMethod().getReturnType().isInstance(a.getMock()) ? a.getMock() : null;
            });
            return action;
        });
    }

    private void event(Status status, String updatesJson) {
        event(status, updatesJson, null);
    }

    private void event(Status status, String updatesJson, String visibility) {
        String config = "{" + BOARD + (updatesJson == null ? "" : ",\"updates\":" + updatesJson)
                + (visibility == null ? "" : ",\"standings\":{\"visibility\":\"" + visibility + "\"}") + "}";
        when(events.event(GUILD, EVENT)).thenReturn(eventOf(status, config));
        when(events.eventsInStatus(Status.RUNNING)).thenReturn(status == Status.RUNNING ? List.of(eventOf(status, config)) : List.of());
    }

    private static Event eventOf(Status status, String config) {
        return new Event(EVENT, GUILD, BingoTemplate.KEY, "Bingo night", "", status, Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, config, 1L, T, null, false);
    }

    private void done(String tile, Instant at) {
        progress.add(new EventRepository.Progress(progress.size() + 1, EVENT, tile, 501L, null, 1, EventRules.Source.MANUAL, null, 1L, "", at));
    }

    private int boardPosts() {
        return sent.getOrDefault(BOARD_CHANNEL, List.of()).size();
    }

    /** Runs every queued post the way the outbox would, on the pretend server. */
    private void runOutbox() throws Exception {
        EventUpdatePostJob handler = new EventUpdatePostJob();
        for (DiscordJob job : queued) handler.run(jda, job);
        queued.clear();
    }

    private static void notLive() {
        TrackingPostingToggle posting = mock(TrackingPostingToggle.class);
        when(posting.isEnabled()).thenReturn(false);
        new AutoPostRouter(mock(BotConfig.class), posting);
    }

    // ---------- when the board is redrawn ----------

    @Test
    void byDefaultTheBoardIsRedrawnRightAfterEachApprovalAsToday() {
        event(Status.RUNNING, null);
        panels.afterPause(GUILD, EVENT, true, T);
        assertEquals(1, boardPosts());
        panels.afterPause(GUILD, EVENT, true, T.plusSeconds(10));
        assertEquals(2, boardPosts(), "every approval redraws it");
        assertNull(panels.nextUpdate(EVENT, BoardUpdates.DEFAULTS));
    }

    @Test
    void batchedTheBoardIsRedrawnAtMostOnceEveryNMinutes() {
        event(Status.RUNNING, "{\"mode\":\"BATCHED\",\"everyMinutes\":30}");
        BoardUpdates.Settings rules = BoardUpdates.of(DataObject.fromJson("{" + BOARD + ",\"updates\":{\"mode\":\"BATCHED\",\"everyMinutes\":30}}"));
        panels.afterPause(GUILD, EVENT, true, T);
        assertEquals(0, boardPosts(), "held back for the next minute's check");
        panels.flushHeld(T.plusSeconds(60));
        assertEquals(1, boardPosts(), "the first batch goes out at once");

        panels.afterPause(GUILD, EVENT, true, T.plusSeconds(120));
        panels.afterPause(GUILD, EVENT, true, T.plusSeconds(180));
        assertEquals(T.plusSeconds(60).plus(Duration.ofMinutes(30)), panels.nextUpdate(EVENT, rules));
        panels.flushHeld(T.plus(Duration.ofMinutes(20)));
        assertEquals(1, boardPosts(), "not yet: 30 minutes haven't passed");
        panels.flushHeld(T.plus(Duration.ofMinutes(32)));
        assertEquals(2, boardPosts(), "both approvals in one redraw");
        panels.flushHeld(T.plus(Duration.ofMinutes(70)));
        assertEquals(2, boardPosts(), "nothing new, nothing redrawn");
    }

    @Test
    void dailyTheBoardIsRedrawnOnceAtReset() {
        event(Status.RUNNING, "{\"mode\":\"DAILY\"}");
        panels.afterPause(GUILD, EVENT, true, T);
        panels.afterPause(GUILD, EVENT, true, T.plus(Duration.ofHours(3)));
        panels.flushHeld(Instant.parse("2026-10-27T23:59:00Z"));
        assertEquals(0, boardPosts());
        panels.flushHeld(Instant.parse("2026-10-28T00:00:30Z"));
        assertEquals(1, boardPosts());
        panels.flushHeld(Instant.parse("2026-10-28T00:01:30Z"));
        assertEquals(1, boardPosts(), "once");
    }

    @Test
    void aStartOrASettingsChangeIsNeverHeldBack() {
        event(Status.RUNNING, "{\"mode\":\"DAILY\"}");
        panels.afterPause(GUILD, EVENT, false, T);
        assertEquals(1, boardPosts(), "the board still appears when the event starts");
    }

    @Test
    void switchingBackToLiveRedrawsAHeldBoardOnTheNextCheck() {
        event(Status.RUNNING, "{\"mode\":\"DAILY\"}");
        panels.afterPause(GUILD, EVENT, true, T);
        event(Status.RUNNING, null);
        panels.flushHeld(T.plusSeconds(60));
        assertEquals(1, boardPosts());
    }

    @Test
    void aBotThatIsNotLiveHoldsAndRedrawsNothing() {
        notLive();
        event(Status.RUNNING, "{\"mode\":\"BATCHED\",\"everyMinutes\":5}");
        panels.afterPause(GUILD, EVENT, true, T);
        panels.flushHeld(T.plus(Duration.ofHours(1)));
        assertEquals(0, boardPosts());
    }

    // ---------- milestones ----------

    @Test
    void noMilestoneIsPostedUnlessSwitchedOn() {
        event(Status.RUNNING, null);
        done("a", T);
        done("b", T.plusSeconds(1));
        assertEquals(0, updates.milestones(GUILD, EVENT, T.plusSeconds(5)));
        assertTrue(queued.isEmpty());
    }

    @Test
    void theFirstTimeAnyoneDoesATileIsAnnouncedOncePerTile() throws Exception {
        event(Status.RUNNING, "{\"firstTile\":true}");
        done("a", T);
        assertEquals(1, updates.milestones(GUILD, EVENT, T.plusSeconds(5)));
        done("b", T.plusSeconds(10));
        assertEquals(1, updates.milestones(GUILD, EVENT, T.plusSeconds(15)), "only the new tile");
        assertEquals(0, updates.milestones(GUILD, EVENT, T.plusSeconds(20)), "nothing twice");
        runOutbox();
        List<String> posts = sent.get(BOARD_CHANNEL);
        assertEquals(2, posts.size(), "no announcements channel: the live board channel");
        assertTrue(posts.get(0).contains("**Zaros** is the first to finish **Nex: Torva boots**"), posts.get(0));
        mentionsAllowed.get(BOARD_CHANNEL).forEach(allowed -> assertTrue(allowed.isEmpty(), "no mentions allowed"));
    }

    @Test
    void hiddenStandingsAnnounceNoMilestones() {
        for (String visibility : List.of("STAFF", "HIDDEN_UNTIL_END")) {
            event(Status.RUNNING, "{\"firstTile\":true,\"firstLine\":true,\"firstBlackout\":true}", visibility);
            progress.clear();
            for (String key : List.of("a", "b", "c", "d")) done(key, T.plusSeconds(key.charAt(0)));
            assertEquals(0, updates.milestones(GUILD, EVENT, T.plus(Duration.ofMinutes(1))), visibility);
            assertTrue(queued.isEmpty(), visibility);
            assertTrue(claimed.isEmpty(), "nothing is noted as posted, so nothing stale comes out later: " + visibility);
        }
    }

    @Test
    void theFirstLineIsAnnouncedOnlyWhenItsSwitchIsOn() throws Exception {
        event(Status.RUNNING, "{\"firstLine\":true}");
        done("a", T);
        assertEquals(0, updates.milestones(GUILD, EVENT, T.plusSeconds(5)), "a tile isn't a line, and tiles are switched off");
        done("b", T.plusSeconds(10));
        assertEquals(1, updates.milestones(GUILD, EVENT, T.plusSeconds(15)));
        runOutbox();
        assertTrue(sent.get(BOARD_CHANNEL).get(0).contains("first to finish a line (Row 1)"));
    }

    @Test
    void theFirstBlackoutIsAnnouncedInTheAnnouncementsChannel() throws Exception {
        event(Status.RUNNING, "{\"firstBlackout\":true}");
        when(space.channels(EVENT)).thenReturn(List.of(new EventSpaceRepository.ChannelRow(EVENT, "announcements", ANNOUNCE_CHANNEL, "announcements", true, "category")));
        for (String key : List.of("a", "b", "c", "d")) done(key, T.plusSeconds(key.charAt(0)));
        assertEquals(1, updates.milestones(GUILD, EVENT, T.plus(Duration.ofMinutes(1))));
        runOutbox();
        assertTrue(sent.get(ANNOUNCE_CHANNEL).get(0).contains("Blackout!"));
        assertEquals(0, boardPosts());
    }

    @Test
    void aChosenChannelWinsAndOldNewsIsNotPosted() throws Exception {
        event(Status.RUNNING, "{\"firstTile\":true,\"channelId\":\"" + ANNOUNCE_CHANNEL + "\"}");
        done("a", T.minus(Duration.ofHours(8)));
        done("b", T);
        assertEquals(1, updates.milestones(GUILD, EVENT, T.plusSeconds(5)), "the tile finished 8 hours ago is old news");
        runOutbox();
        assertEquals(1, sent.get(ANNOUNCE_CHANNEL).size());
    }

    @Test
    void milestonesOnlyDuringTheEventAndNotFromABotThatIsNotLive() {
        event(Status.SCORING, "{\"firstTile\":true}");
        done("a", T);
        assertEquals(0, updates.milestones(GUILD, EVENT, T.plusSeconds(5)));
        event(Status.RUNNING, "{\"firstTile\":true}");
        notLive();
        assertEquals(0, updates.milestones(GUILD, EVENT, T.plusSeconds(5)));
        assertTrue(claimed.isEmpty(), "nothing is noted, so the live bot can still post it");
    }

    // ---------- the daily recap ----------

    @Test
    void theRecapIsOffByDefault() {
        event(Status.RUNNING, null);
        done("a", Instant.parse("2026-10-26T20:00:00Z"));
        assertEquals(0, updates.recaps(Instant.parse("2026-10-27T00:00:30Z")));
    }

    @Test
    void theRecapIsPostedOnceAfterResetWithYesterdaysTilesAndTheTopThree() throws Exception {
        event(Status.RUNNING, "{\"recap\":true}");
        done("a", Instant.parse("2026-10-26T20:00:00Z"));
        done("b", Instant.parse("2026-10-26T21:00:00Z"));
        assertEquals(1, updates.recaps(Instant.parse("2026-10-27T00:00:30Z")));
        assertEquals(0, updates.recaps(Instant.parse("2026-10-27T00:01:30Z")), "once per reset");
        runOutbox();
        String recap = sent.get(BOARD_CHANNEL).get(0);
        assertTrue(recap.startsWith("### Bingo night · Daily recap, 26 Oct"), recap);
        assertTrue(recap.contains("**2 tiles finished**"));
        assertTrue(recap.contains("1. **Zaros** · 60 pts"));
        assertTrue(mentionsAllowed.get(BOARD_CHANNEL).get(0).isEmpty());
        assertTrue(claimed.contains(EVENT + "/recap:2026-10-26"));
    }

    @Test
    void aQuietDayLateCatchUpAndANotLiveBotPostNoRecap() {
        event(Status.RUNNING, "{\"recap\":true}");
        assertEquals(0, updates.recaps(Instant.parse("2026-10-27T00:00:30Z")), "nothing finished yesterday");
        done("a", Instant.parse("2026-10-27T20:00:00Z"));
        assertEquals(0, updates.recaps(Instant.parse("2026-10-28T05:00:00Z")), "more than two hours after reset is too late");
        notLive();
        assertEquals(0, updates.recaps(Instant.parse("2026-10-29T00:00:30Z")));
        assertTrue(queued.isEmpty());
    }

    @Test
    void thePostJobRefusesAChannelThatIsGone() {
        DiscordJob job = new DiscordJob(1, EventUpdatePostJob.KIND, GUILD, 0, "x", EventUpdatePostJob.payload(EVENT, 4242L, "line", "hi"), DiscordJob.State.QUEUED, 0, 0, null, null,
                null, T, T);
        try {
            new EventUpdatePostJob().run(jda, job);
            throw new AssertionError("it should refuse");
        } catch (com.runeclans.bot.discord.jobs.DiscordJobs.Refused e) {
            assertNotNull(e.getMessage());
        }
        assertFalse(new EventUpdatePostJob().idempotent(), "a post is never repeated after an unclear failure");
    }
}
