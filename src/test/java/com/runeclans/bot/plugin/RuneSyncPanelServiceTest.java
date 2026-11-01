package com.runeclans.bot.plugin;

import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.discord.DiscordLookups.MemberLookup;
import com.runeclans.bot.discord.DiscordLookups.Outcome;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuneSyncPanelServiceTest {
    private static final long GUILD = 100L;
    private static final long ME = 7L;
    private static final long OTHER = 8L;
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final RuneSyncRepository repository = mock(RuneSyncRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final EventService eventService = mock(EventService.class);
    private final PointsRepository points = mock(PointsRepository.class);
    private final PluginRepository plugins = mock(PluginRepository.class);
    private final RuneSyncPanelService service = new RuneSyncPanelService(repository, events, eventService, points, plugins);

    private static final String BOARD = "{\"board\":{\"size\":2,\"keys\":[\"a\",\"b\",\"c\",\"d\"]}}";

    private static Event bingo(long id, ScoringMode mode) {
        return new Event(id, GUILD, "bingo", "Autumn bingo", "", Status.RUNNING, Scope.PRIVATE, mode, NOW, NOW.plusSeconds(3600), 0, BOARD, 1L, NOW, null, false);
    }

    private static Participant teamPlayer(long eventId, long userId, long teamId) {
        return new Participant(eventId, userId, GUILD, teamId, null, false, null, NOW, null, null, null, null, null, null);
    }

    private void boardObjectives(long eventId) {
        when(eventService.objectives(eventId)).thenReturn(List.of(
                new ObjectiveSpec("a", "Torva boots", "", 10, Evidence.AUTO, 1, 0, List.of()),
                new ObjectiveSpec("b", "Elite clue", "", 10, Evidence.AUTO, 1, 0, List.of()),
                new ObjectiveSpec("c", "99 Fishing", "", 10, Evidence.AUTO, 1, 0, List.of()),
                new ObjectiveSpec("d", "Pet", "", 10, Evidence.AUTO, 1, 0, List.of())));
        when(points.settings(GUILD)).thenReturn(new PointsRepository.Settings(GUILD, false, "points", null));
    }

    @Test
    void aSoloBingoSendsTheMembersOwnBoard() {
        when(events.events(eq(GUILD), eq(List.of(Status.RUNNING, Status.SIGNUPS)), eq(RuneSyncPanelService.EVENTS_PER_CLAN)))
                .thenReturn(List.of(bingo(1, ScoringMode.INDIVIDUAL)));
        when(events.participant(1, ME)).thenReturn(Optional.of(player(1, ME)));
        boardObjectives(1);
        when(eventService.standings(GUILD, 1)).thenReturn(List.of(
                new Standing("u:" + OTHER, 40, 4, Map.of("a", 1, "b", 1, "c", 1, "d", 1), NOW),
                new Standing("u:" + ME, 20, 2, Map.of("a", 1, "c", 1), NOW)));

        DataObject progress = service.clan(GUILD, "Younglings", ME).getArray("progress").getObject(0);
        DataObject bingo = progress.getObject("bingo");
        DataObject board = bingo.getObject("board");

        assertEquals(2, board.getInt("size"));
        assertEquals(4, board.getArray("cells").length());
        assertEquals("Torva boots", board.getArray("cells").getObject(0).getString("label"));
        assertTrue(board.getArray("cells").getObject(0).getBoolean("done"));
        assertFalse(board.getArray("cells").getObject(1).getBoolean("done"), "the member's own board, not the leader's");
        assertTrue(board.getArray("cells").getObject(2).getBoolean("done"));
        assertEquals(2, bingo.getInt("tilesDone"));
        assertFalse(bingo.hasKey("teamBoard"), "no team board in a solo event");
        assertFalse(progress.hasKey("teamStatus"));
    }

    @Test
    void aTeamBingoSendsTheTeamsBoardAndTeamStatusWithPresenceOnlyWhenShared() {
        long mate = 9L;
        long rival = 10L;
        when(events.events(eq(GUILD), eq(List.of(Status.RUNNING, Status.SIGNUPS)), eq(RuneSyncPanelService.EVENTS_PER_CLAN)))
                .thenReturn(List.of(bingo(2, ScoringMode.TEAM)));
        when(events.participant(2, ME)).thenReturn(Optional.of(teamPlayer(2, ME, 5)));
        boardObjectives(2);
        when(eventService.standings(GUILD, 2)).thenReturn(List.of(
                new Standing("t:5", 30, 3, Map.of("a", 1, "b", 1, "d", 1), NOW),
                new Standing("t:6", 10, 1, Map.of("c", 1), NOW)));
        when(events.teams(2)).thenReturn(List.of(new EventRepository.Team(5, 2, "Red", null), new EventRepository.Team(6, 2, "Blue", null)));
        when(events.participants(2)).thenReturn(List.of(teamPlayer(2, ME, 5), teamPlayer(2, mate, 5), teamPlayer(2, rival, 6)));
        when(plugins.consent(ME)).thenReturn(java.util.EnumSet.of(PluginCategory.PRESENCE));
        when(plugins.consent(mate)).thenReturn(java.util.EnumSet.noneOf(PluginCategory.class));
        when(repository.clientStatus(ME)).thenReturn(Optional.of(new RuneSyncRepository.ClientStatus("RuneSync 0.0.8", Instant.now(), false, null, 0, 0, 0)));

        DataObject progress = service.clan(GUILD, "Younglings", ME).getArray("progress").getObject(0);
        DataObject teamBoard = progress.getObject("bingo").getObject("teamBoard");
        DataObject status = progress.getObject("teamStatus");

        assertFalse(progress.getObject("bingo").hasKey("board"), "in a team event the team's board is the one played");
        assertTrue(teamBoard.getArray("cells").getObject(1).getBoolean("done"));
        assertFalse(teamBoard.getArray("cells").getObject(2).getBoolean("done"), "the other team's tile isn't ours");
        assertEquals("Red", status.getString("name"));
        assertEquals(2, status.getArray("members").length(), "only the member's own team");
        assertTrue(status.getArray("members").getObject(0).getBoolean("inGame"), "shared presence, seen just now");
        assertTrue(status.getArray("members").getObject(1).isNull("inGame"), "no Presence consent: not shared");
        assertFalse(status.toString().contains("Blue"));
    }

    private static Event event(long id, String name, Status status) {
        return new Event(id, GUILD, "competition", name, "", status, Scope.PRIVATE, ScoringMode.INDIVIDUAL, NOW, NOW.plusSeconds(3600), 0, "{}", 1L, NOW, null, false);
    }

    private static Participant player(long eventId, long userId) {
        return new Participant(eventId, userId, GUILD, null, null, false, null, NOW, null, null, null, null, null, null);
    }

    @Test
    void aClanShowsItsEventsAndOnlyTheMembersOwnPointsAndProgress() {
        when(events.events(eq(GUILD), eq(List.of(Status.RUNNING, Status.SIGNUPS)), eq(RuneSyncPanelService.EVENTS_PER_CLAN)))
                .thenReturn(List.of(event(1, "Boss night", Status.RUNNING), event(2, "Skill week", Status.SIGNUPS), event(3, "Other running", Status.RUNNING)));
        when(events.participant(1, ME)).thenReturn(Optional.of(player(1, ME)));
        when(events.participant(2, ME)).thenReturn(Optional.empty());
        when(events.participant(3, ME)).thenReturn(Optional.empty());
        when(eventService.standings(GUILD, 1)).thenReturn(List.of(
                new Standing("u:" + OTHER, 90, 3, Map.of("a", 1, "b", 1, "c", 1), NOW),
                new Standing("u:" + ME, 40, 1, Map.of("a", 2), NOW)));
        when(eventService.objectives(1)).thenReturn(List.of(
                new ObjectiveSpec("a", "Kill a boss", "", 20, Evidence.AUTO, 1, 0, List.of()),
                new ObjectiveSpec("b", "Get a drop", "", 30, Evidence.AUTO, 1, 0, List.of())));
        when(points.settings(GUILD)).thenReturn(new PointsRepository.Settings(GUILD, true, "points", null));
        when(points.balance(GUILD, ME)).thenReturn(1250L);

        DataObject clan = service.clan(GUILD, "Younglings", ME);

        assertEquals(3, clan.getArray("events").length());
        assertTrue(clan.getArray("events").getObject(0).getBoolean("joined"));
        assertFalse(clan.getArray("events").getObject(1).getBoolean("joined"));
        assertEquals(1250L, clan.getObject("points").getLong("balance"));
        assertEquals(1, clan.getArray("progress").length(), "progress only for running events the member is in");
        DataObject mine = clan.getArray("progress").getObject(0);
        assertEquals(40, mine.getLong("points"), "the member's own points, not the leader's");
        assertEquals(1, mine.getInt("objectivesDone"));
        assertEquals(2, mine.getInt("objectivesTotal"));
        assertFalse(clan.toString().contains(Long.toString(OTHER)), "no other player appears");
    }

    @Test
    void pointsAreLeftOutWhenTheClanHasThemOff() {
        when(events.events(eq(GUILD), eq(List.of(Status.RUNNING, Status.SIGNUPS)), eq(RuneSyncPanelService.EVENTS_PER_CLAN))).thenReturn(List.of());
        when(points.settings(GUILD)).thenReturn(new PointsRepository.Settings(GUILD, false, "points", null));

        DataObject clan = service.clan(GUILD, "Younglings", ME);

        assertFalse(clan.getObject("points").getBoolean("enabled"));
        assertFalse(clan.getObject("points").hasKey("balance"));
    }

    @Test
    void withoutDiscordNoClanIsAvailable() {
        when(repository.clanPanelOn(anyLong())).thenReturn(true);
        when(repository.clanPanelGuilds()).thenReturn(Set.of(GUILD));
        assertEquals(Boolean.FALSE, service.available(ME));
        assertFalse(service.panel(ME, NOW).getBoolean("available"));
    }

    // ---------- T-812: members the bot's cache doesn't hold (offline or Invisible) ----------

    /** Discord as a stand-in: the bot's servers, and what a member lookup answers for each, counting the asks. */
    private static final class FakeDiscord implements RuneSyncPanelService.Discord {
        final List<Guild> guilds;
        Outcome answer = Outcome.FOUND;
        int asks;

        FakeDiscord(Guild... guilds) {
            this.guilds = List.of(guilds);
        }

        @Override
        public List<Guild> guilds() {
            return guilds;
        }

        @Override
        public List<MemberLookup> members(List<Guild> asked, long userId) {
            asks++;
            List<MemberLookup> out = new ArrayList<>();
            for (Guild g : asked) out.add(new MemberLookup(answer, answer == Outcome.FOUND ? mock(Member.class) : null));
            return out;
        }
    }

    private static Guild guild(long id, String name) {
        Guild g = mock(Guild.class);
        when(g.getIdLong()).thenReturn(id);
        when(g.getName()).thenReturn(name);
        when(g.getId()).thenReturn(Long.toString(id));
        return g;
    }

    @Test
    void anOfflineMemberIsFoundThroughDiscordInPanelServersOnly() {
        Guild panelOn = guild(GUILD, "Younglings"), panelOff = guild(200L, "Other clan");
        FakeDiscord discord = new FakeDiscord(panelOn, panelOff);
        service.discord(discord);
        when(repository.clanPanelGuilds()).thenReturn(Set.of(GUILD));
        when(points.settings(GUILD)).thenReturn(new PointsRepository.Settings(GUILD, false, "points", null));

        assertEquals(Boolean.TRUE, service.available(ME), "found through Discord, not the online-only cache");
        assertEquals(List.of(panelOn), service.clans(ME, NOW).guilds(), "only servers that allow the panel are asked or listed");
        DataObject panel = service.panel(ME, NOW);
        assertTrue(panel.getBoolean("available"));
        assertEquals("Younglings", panel.getArray("clans").getObject(0).getString("name"));
    }

    @Test
    void aKnownAnswerIsReusedForFiveMinutesThenAskedAgain() {
        FakeDiscord discord = new FakeDiscord(guild(GUILD, "Younglings"));
        service.discord(discord);
        when(repository.clanPanelGuilds()).thenReturn(Set.of(GUILD));
        service.clans(ME, NOW);
        service.clans(ME, NOW.plus(RuneSyncPanelService.CLANS_CACHE).minusSeconds(1));
        assertEquals(1, discord.asks, "inside five minutes the known answer is reused");
        discord.answer = Outcome.ABSENT;
        assertTrue(service.clans(ME, NOW.plus(RuneSyncPanelService.CLANS_CACHE).plusSeconds(1)).guilds().isEmpty(), "after it, Discord is asked again");
        assertEquals(2, discord.asks);
    }

    @Test
    void aSlowDiscordIsUnknownNeverNotAllowedAndIsNotRemembered() {
        FakeDiscord discord = new FakeDiscord(guild(GUILD, "Younglings"));
        discord.answer = Outcome.SLOW;
        service.discord(discord);
        when(repository.clanPanelGuilds()).thenReturn(Set.of(GUILD));
        when(points.settings(GUILD)).thenReturn(new PointsRepository.Settings(GUILD, false, "points", null));

        assertNull(service.available(ME), "unknown, not \"not allowed\"");
        assertTrue(service.panel(ME, NOW).isNull("available"));
        discord.answer = Outcome.FOUND;
        assertEquals(Boolean.TRUE, service.available(ME), "the slow answer wasn't kept: the next ask tries again");
        assertTrue(service.panel(ME, NOW.plusSeconds(1)).getBoolean("available"), "nor was the slow panel answer");
    }

    @Test
    void whenDiscordIsSlowTheLastKnownAnswerStands() {
        FakeDiscord discord = new FakeDiscord(guild(GUILD, "Younglings"));
        service.discord(discord);
        when(repository.clanPanelGuilds()).thenReturn(Set.of(GUILD));
        assertEquals(1, service.clans(ME, NOW).guilds().size());
        discord.answer = Outcome.SLOW;
        RuneSyncPanelService.Clans later = service.clans(ME, NOW.plus(RuneSyncPanelService.CLANS_CACHE).plusSeconds(1));
        assertEquals(1, later.guilds().size(), "still allowed");
        assertFalse(later.unsure());
    }

    @Test
    void noServerAllowsThePanelSoDiscordIsNeverAsked() {
        FakeDiscord discord = new FakeDiscord(guild(GUILD, "Younglings"));
        service.discord(discord);
        when(repository.clanPanelGuilds()).thenReturn(Set.of());
        assertEquals(Boolean.FALSE, service.available(ME));
        assertEquals(0, discord.asks);
    }
}
