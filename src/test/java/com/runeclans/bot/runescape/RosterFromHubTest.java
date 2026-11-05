package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runeclans.RuneClansHubClient.Answer;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-912: a registered clan's roster is the one the hub keeps (no extra Jagex call); only an unknown clan asks the hub's live fetch. */
class RosterFromHubTest {
    private static final String CLAN_ID = "11111111-2222-3333-4444-555555555555";

    private RuneClansHubClient hub;
    private RuneScapeApiClient client;

    @BeforeEach
    void setUp() {
        hub = mock(RuneClansHubClient.class);
        when(hub.enabled()).thenReturn(true);
        client = new RuneScapeApiClient(hub);
    }

    private static DataObject member(String rsn, boolean active) {
        return DataObject.empty().put("rsn", rsn).put("rank", "Recruit").put("totalXp", 10L).put("kills", 0L).put("active", active);
    }

    private void registered(DataObject roster) {
        when(hub.call("GET", "/v1/clans/by-name/The%20Younglings", null)).thenReturn(new Answer(200, DataObject.empty().put("clanId", CLAN_ID)));
        when(hub.call("GET", "/v1/clans/" + CLAN_ID + "/roster", null)).thenReturn(new Answer(200, roster));
    }

    @Test
    void aRegisteredClanIsReadFromTheKeptRosterWithoutTheLiveFetch() {
        registered(DataObject.empty().put("poll", DataObject.empty()).put("members", DataArray.empty().add(member("Jonny", true)).add(member("Gone", false))));

        List<RuneScapeApiClient.ClanMember> roster = client.fetchClanRoster("The Younglings");

        assertEquals(List.of("Jonny"), roster.stream().map(RuneScapeApiClient.ClanMember::rsn).toList(), "only active members");
        verify(hub, never()).call(eq("POST"), eq("/v1/clans/live-roster"), anyString());
    }

    @Test
    void aRegisteredClanTheHubHasNotFetchedYetIsEmptyWithNoLiveFetch() {
        registered(DataObject.empty().putNull("poll").put("members", DataArray.empty()));

        assertTrue(client.fetchClanRoster("The Younglings").isEmpty());
        verify(hub, never()).call(eq("POST"), eq("/v1/clans/live-roster"), anyString());
    }

    @Test
    void aHubThatFailsIsAnEmptyRosterWithNoLiveFetch() {
        when(hub.call(eq("GET"), anyString(), any())).thenReturn(new Answer(503, DataObject.empty()));

        assertTrue(client.fetchClanRoster("The Younglings").isEmpty());
        verify(hub, never()).call(eq("POST"), eq("/v1/clans/live-roster"), anyString());
    }

    @Test
    void onlyAClanTheHubDoesNotKnowAsksTheLiveList() {
        when(hub.call(eq("GET"), anyString(), any())).thenReturn(new Answer(404, DataObject.empty().put("error", "not_found")));
        when(hub.call(eq("POST"), eq("/v1/clans/live-roster"), anyString()))
                .thenReturn(new Answer(200, DataObject.empty().put("members", DataArray.empty().add(member("Live", true)))));

        assertEquals(1, client.fetchClanRoster("The Younglings").size());
    }

    @Test
    void noHubIsNoRoster() {
        when(hub.call(anyString(), anyString(), any())).thenReturn(null);
        assertTrue(client.fetchClanRoster("The Younglings").isEmpty());
    }
}
