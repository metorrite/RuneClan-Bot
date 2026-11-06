package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.events.EventListing;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.hubevents.HubEvents;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Clan-vs-clan results on the clans hub (T-223): clan standings on open events only, and a clan's history on its listed page. Never a player. */
class DirectoryClanVsClanSectionTest {
    private static final long HOST = 1, ALLY = 2;
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    private static Event event(long id, String template, Scope scope, Status status, String config) {
        return new Event(id, HOST, template, "Clan clash " + id, "", status, scope, ScoringMode.TEAM, NOW, NOW, 0, config, 1, NOW, null, false);
    }

    private static Standing standing(String subject, long points) {
        return new Standing(subject, points, 1, Map.of(), NOW);
    }

    @Test
    void clanStandingsShowOnlyForOpenClanEventsTheHostDidntHide() {
        String teams = "\"cvcTeams\":{\"1\":10,\"2\":20}";
        assertTrue(EventListing.clanStandingsShown(event(1, "clan-vs-clan", Scope.PUBLIC, Status.RUNNING, "{" + teams + "}")));
        assertFalse(EventListing.clanStandingsShown(event(1, "clan-vs-clan", Scope.ALLIANCE, Status.RUNNING, "{" + teams + "}")), "only events open to everyone");
        assertFalse(EventListing.clanStandingsShown(event(1, "bingo", Scope.PUBLIC, Status.RUNNING, "{}")), "a plain event follows the host's own switch");
        assertFalse(EventListing.clanStandingsShown(event(1, "clan-vs-clan", Scope.PUBLIC, Status.DONE, "{" + teams + ",\"standings\":{\"visibility\":\"STAFF\"}}")));
        assertFalse(EventListing.clanStandingsShown(event(1, "clan-vs-clan", Scope.PUBLIC, Status.RUNNING, "{" + teams + ",\"standings\":{\"visibility\":\"HIDDEN_UNTIL_END\"}}")));
        assertTrue(EventListing.clanStandingsShown(event(1, "clan-vs-clan", Scope.PUBLIC, Status.DONE, "{" + teams + ",\"standings\":{\"visibility\":\"HIDDEN_UNTIL_END\"}}")));
    }

    @Test
    void aListedClansPageListsItsFinishedOpenClanEventsWithItsPlace() {
        HubEvents events = mock(HubEvents.class);
        ClanProfileRepository profiles = mock(ClanProfileRepository.class);
        when(profiles.get(anyLong())).thenReturn(Optional.empty());
        Event won = event(5, "clan-vs-clan", Scope.PUBLIC, Status.DONE, "{\"cvcTeams\":{\"1\":10,\"2\":20}}");
        Event hidden = event(6, "clan-vs-clan", Scope.PUBLIC, Status.DONE, "{\"cvcTeams\":{\"1\":11,\"2\":21},\"standings\":{\"visibility\":\"STAFF\"}}");
        Event takenDown = event(7, "clan-vs-clan", Scope.PUBLIC, Status.DONE, "{\"cvcTeams\":{\"1\":12,\"2\":22},\"hub\":{\"takedown\":{\"reason\":\"spam\"}}}");
        Event without = event(8, "clan-vs-clan", Scope.PUBLIC, Status.DONE, "{\"cvcTeams\":{\"1\":13,\"22\":23}}");
        when(events.events(ALLY, List.of(Status.DONE), DirectoryClanVsClanSection.SHOWN * 3)).thenReturn(List.of(won, hidden, takenDown, without));
        when(events.standings(HOST, 5)).thenReturn(List.of(standing("t:20", 90), standing("t:10", 40)));

        DataArray history = new DirectoryClanVsClanSection(events, profiles).history(ALLY);

        assertEquals(2, history.length(), "the taken-down event and the one the clan wasn't in are left out");
        DataObject first = history.getObject(0);
        assertEquals("5", first.getString("eventId"));
        assertEquals(1, first.getInt("place"));
        assertEquals(2, first.getInt("clans"));
        assertTrue(history.getObject(1).isNull("place"), "the host kept the standings to staff: taking part, but no place");
        assertFalse(history.toString().contains("u:"), "no player is ever named");
    }

    @Test
    void anUnlistedClanPageSaysNothing() {
        HubEvents events = mock(HubEvents.class);
        when(events.events(anyLong(), any(), anyInt())).thenReturn(List.of());
        ClanProfile unlisted = mock(ClanProfile.class);
        when(unlisted.visibility()).thenReturn(ClanProfile.Visibility.UNLISTED);
        DataObject json = DataObject.empty();
        new DirectoryClanVsClanSection(events, mock(ClanProfileRepository.class)).addToClan(unlisted, json);
        assertFalse(json.hasKey("clanVsClan"));
    }
}
