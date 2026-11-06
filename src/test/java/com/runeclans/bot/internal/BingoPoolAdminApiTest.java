package com.runeclans.bot.internal;

import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The dashboard's bingo tile pool is the hub's (T-941): each route hands the request on as the asking staff member and returns the hub's answer. */
class BingoPoolAdminApiTest {
    private static final long GUILD = 1L;
    private static final String TILE = "0f0e0d0c-0b0a-4908-8706-050403020100";

    private HubEvents events;
    private BingoPoolAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        events = mock(HubEvents.class);
        api = new BingoPoolAdminApi(events);
        guild = mock(Guild.class);
        actor = mock(Member.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(actor.getIdLong()).thenReturn(7L);
        when(events.bingoPool(eq(GUILD), any())).thenReturn(DataObject.empty().put("tiles", net.dv8tion.jda.api.utils.data.DataArray.empty()));
        when(events.bingoPool(anyString(), eq(GUILD), anyString(), any(), any())).thenReturn(DataObject.empty().put("ok", true));
    }

    private DataObject call(String method, String body, String... parts) {
        return api.route(guild, actor, method, parts, () -> DataObject.fromJson(body));
    }

    @Test
    void otherPathsAreNotThisFeatures() {
        assertNull(api.route(guild, actor, "GET", new String[]{"events"}, () -> null));
        assertNull(call("GET", "{}", "bingo-pool", "dance"));
    }

    @Test
    void theOverviewIsTheHubsReadAsTheStaffMember() {
        DataObject o = call("GET", "{}", "bingo-pool");
        assertEquals(0, o.getArray("tiles").length());
        ArgumentCaptor<Actor> who = ArgumentCaptor.forClass(Actor.class);
        verify(events).bingoPool(eq(GUILD), who.capture());
        assertEquals(7L, who.getValue().discordId());
        assertTrue(who.getValue().staff());
    }

    @Test
    void writesGoToTheHubsPoolRoutesAsTheyAre() {
        call("POST", "{\"label\":\"Kill Nex\"}", "bingo-pool", "tiles");
        verify(events).bingoPool(eq("POST"), eq(GUILD), eq("/tiles"), eq(DataObject.fromJson("{\"label\":\"Kill Nex\"}")), any());
        call("PUT", "{\"enabled\":false}", "bingo-pool", "tiles", TILE);
        verify(events).bingoPool(eq("PUT"), eq(GUILD), eq("/tiles/" + TILE), any(), any());
        call("POST", "{}", "bingo-pool", "tiles", TILE, "delete");
        verify(events).bingoPool(eq("DELETE"), eq(GUILD), eq("/tiles/" + TILE), eq(null), any());
        call("POST", "{\"pack\":\"boss-drops\"}", "bingo-pool", "seed");
        verify(events).bingoPool(eq("POST"), eq(GUILD), eq("/seed"), any(), any());
        call("POST", "{}", "bingo-pool", "clear");
        verify(events).bingoPool(eq("POST"), eq(GUILD), eq("/clear"), any(), any());
    }

    @Test
    void aTileIdThatIsNotTheHubsIsNotFound() {
        assertEquals(404, assertThrows(ApiError.class, () -> call("PUT", "{}", "bingo-pool", "tiles", "9")).status);
        verify(events, never()).bingoPool(anyString(), anyLong(), anyString(), any(), any());
    }

    @Test
    void theHubsRefusalIsTheDashboardsAnswer() {
        when(events.bingoPool(anyString(), eq(GUILD), anyString(), any(), any())).thenThrow(new EventProblem(400, "There is already a tile called that.", List.of()));
        ApiError refused = assertThrows(ApiError.class, () -> call("POST", "{\"label\":\"x\"}", "bingo-pool", "tiles"));
        assertEquals(400, refused.status);
        assertTrue(refused.getMessage().contains("already"));
    }
}
