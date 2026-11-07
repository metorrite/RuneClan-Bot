package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.hubevents.HubEventClient;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** What the team builder reads: strengths from the bot's own player links, and players, teams and a suggestion from the hub's event. */
class EventTeamsTest {
    private static final long G = 51L;
    private static final long ID = 7L;
    private HubEvents hub;
    private PlayerLinkRepository links;
    private EventTeams teams;

    @BeforeEach
    void setUp() {
        hub = Mockito.mock(HubEvents.class);
        links = Mockito.mock(PlayerLinkRepository.class);
        teams = new EventTeams(hub, links);
    }

    private static StatsSnapshotRow stats(long xp, int level) {
        return new StatsSnapshotRow(1L, OffsetDateTime.now(), level, xp, 138, 0, 0, 0);
    }

    private static EventRepository.Event event(ScoringMode mode, String config) {
        return new EventRepository.Event(ID, G, "custom", "Teams", "", EventRules.Status.SIGNUPS, EventRules.Scope.PRIVATE, mode, null, null, 0, config, 9L, null, null, false);
    }

    private static EventRepository.Participant player(long userId, boolean waiting, EventRoster.Verification verification, Integer rating) {
        return new EventRepository.Participant(ID, userId, G, null, TeamRules.Kind.HOME_CLAN, waiting, PrizeRules.BuyInStatus.NONE, null, verification, null, null, "", rating, EventRepository.RosterSource.SELF);
    }

    @Test
    void aPlayersStrengthIsTheLatestStatsOfTheirLinkedNameWithTheMostXp() {
        when(links.getAllLinks(G)).thenReturn(List.of(
                new PlayerLink(1, G, 501, "Main", "x", null, null), new PlayerLink(2, G, 501, "Alt", "x", null, null),
                new PlayerLink(3, G, 502, "Zed", "x", null, null), new PlayerLink(4, G, 999, "Elsewhere", "x", null, null)));
        when(links.getLatestSnapshots(ArgumentMatchers.anyCollection())).thenReturn(Map.of("main", stats(100, 1500), "alt", stats(900, 2800), "zed", stats(500, 2000)));

        var strengths = teams.strengths(G, List.of(501L, 502L, 503L));
        assertEquals("Alt", strengths.get(501L).rsn(), "the linked name with the most XP is their main");
        assertEquals(2800, strengths.get(501L).totalLevel());
        assertEquals(500L, strengths.get(502L).totalXp());
        assertFalse(strengths.containsKey(503L), "no link, no strength");
        assertTrue(teams.strengths(G, List.of()).isEmpty());
    }

    @Test
    void thePlayersAreTheHubsRosterWithoutTheWaitlistOrPeopleTheBotCannotName() {
        when(hub.participants(ID)).thenReturn(List.of(player(501, false, EventRoster.Verification.NOT_REQUIRED, 5), player(502, true, EventRoster.Verification.NOT_REQUIRED, null),
                player(0, false, EventRoster.Verification.NOT_REQUIRED, null), player(503, false, EventRoster.Verification.NOT_REQUIRED, null)));
        var players = teams.players(G, event(ScoringMode.TEAM, "{}"));
        assertEquals(List.of(501L, 503L), players.stream().map(TeamBuilder.Player::userId).toList());
        assertEquals(5, players.get(0).rating());
        assertNull(players.get(1).rating());
        when(hub.teams(ID)).thenReturn(List.of(new EventRepository.Team(1, ID, "Red", 501L)));
        assertEquals("Red", teams.teams(ID).get(0).name());
        assertEquals(501L, teams.teams(ID).get(0).captainId());
    }

    @Test
    void aSuggestionIsMadeForPlayersWhoMayBePlacedAndRefusedForASoloEvent() {
        EventRepository.Event team = event(ScoringMode.TEAM, "{\"verification\":{\"required\":true,\"gate\":\"TEAMS\"}}");
        when(hub.event(G, ID)).thenReturn(team);
        when(hub.settings(team)).thenReturn(EventSettings.parse(net.dv8tion.jda.api.utils.data.DataObject.fromJson(team.config())));
        when(hub.participants(ID)).thenReturn(List.of(player(501, false, EventRoster.Verification.VERIFIED, 5), player(502, false, EventRoster.Verification.VERIFIED, 5),
                player(503, false, EventRoster.Verification.VERIFIED, 1), player(504, false, EventRoster.Verification.PENDING, null)));
        when(hub.teamBlock(ArgumentMatchers.any(), ArgumentMatchers.any())).thenAnswer(i -> ((EventRepository.Participant) i.getArgument(1)).verification() == EventRoster.Verification.VERIFIED ? null : "not yet");
        when(hub.teams(ID)).thenReturn(List.of(new EventRepository.Team(1, ID, "Red", null), new EventRepository.Team(2, ID, "Blue", null)));

        var suggestion = teams.suggest(G, ID);
        assertEquals(3, suggestion.moves().size(), "504 hasn't passed verification, so isn't placed");
        assertTrue(suggestion.moves().stream().noneMatch(m -> m.userId() == 504));
        // the two strongest are split, the weakest goes where it levels things (Red, first on a tie)
        assertEquals(List.of(new TeamBuilder.Move(501, 1L), new TeamBuilder.Move(502, 2L), new TeamBuilder.Move(503, 1L)), suggestion.moves());

        EventRepository.Event solo = event(ScoringMode.INDIVIDUAL, "{}");
        when(hub.event(G, 8L)).thenReturn(solo);
        assertEquals(409, assertThrows(EventProblem.class, () -> teams.suggest(G, 8L)).status());
    }
}
