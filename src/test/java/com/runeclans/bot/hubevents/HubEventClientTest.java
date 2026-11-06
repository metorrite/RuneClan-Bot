package com.runeclans.bot.hubevents;

import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubEventClientTest {
    private final FakeEventHub hub = new FakeEventHub();
    private final HubEventClient client = new HubEventClient(hub, true);

    @Test
    void whoIsActingGoesInTheHeaders() {
        assertEquals(Map.of(), Actor.APP.headers(), "the application itself sends no actor");
        assertEquals(Map.of(HubEventClient.ACTOR_HEADER, "11", HubEventClient.GUILD_HEADER, "22"), Actor.member(11, 22).headers());
        assertEquals(Map.of(HubEventClient.ACTOR_HEADER, "11", HubEventClient.GUILD_HEADER, "22", HubEventClient.STAFF_HEADER, "22"), Actor.of(11, 22, true).headers());
        hub.ok("GET /v1/events/x", DataObject.empty().put("id", "x"));
        client.get("/v1/events/x", Actor.of(11, 22, true));
        assertEquals("22", hub.last().headers().get(HubEventClient.STAFF_HEADER));
    }

    @Test
    void theHubsRefusalBecomesTheBotsProblemWithItsWordsAndList() {
        hub.answer("POST /v1/events/x/participants", 409, DataObject.empty().put("error", "full").put("message", "The event is full.").put("problems", DataArray.fromCollection(List.of("No seats left."))));
        EventProblem p = assertThrows(EventProblem.class, () -> client.send("POST", "/v1/events/x/participants", DataObject.empty(), Actor.member(1, 2)));
        assertEquals(409, p.status());
        assertEquals("The event is full.", p.getMessage());
        assertEquals(List.of("No seats left."), p.problems());
    }

    @Test
    void aMissingEventIsEmptyForFindAndA404ForGet() {
        assertTrue(client.find("/v1/events/gone", Actor.APP).isEmpty());
        EventProblem p = assertThrows(EventProblem.class, () -> client.get("/v1/events/gone", Actor.APP));
        assertEquals(404, p.status());
        assertEquals("Not here.", p.getMessage(), "the hub's own words");
        hub.answer("GET /v1/events/silent", 404, null);
        assertTrue(assertThrows(EventProblem.class, () -> client.get("/v1/events/silent", Actor.APP)).getMessage().contains("doesn't exist"));
    }

    @Test
    void aHubThatCannotBeReachedOrIsBusyIsSaidInWordsForTheMember() {
        hub.down("GET /v1/events/down");
        hub.answer("GET /v1/events/busy", 429, null);
        hub.answer("GET /v1/events/broken", 500, DataObject.empty().put("message", "boom"));
        for (String path : List.of("/v1/events/down", "/v1/events/busy", "/v1/events/broken")) {
            EventProblem p = assertThrows(EventProblem.class, () -> client.get(path, Actor.APP));
            assertEquals(503, p.status());
            assertEquals(HubEventClient.DOWN_TEXT, p.getMessage());
        }
    }

    @Test
    void aBotWithoutAHubRefusesEveryCallWithoutTrying() {
        HubEventClient off = new HubEventClient(hub, false);
        EventProblem p = assertThrows(EventProblem.class, () -> off.get("/v1/events/x", Actor.APP));
        assertEquals(503, p.status());
        assertEquals(HubEventClient.OFF_TEXT, p.getMessage());
        assertTrue(hub.calls.isEmpty());
    }

    @Test
    void aBodylessSuccessIsAnEmptyObjectAndBytesComeBackAsTheyAre() {
        hub.answer("DELETE /v1/events/x/participants", 204, null);
        assertTrue(client.send("DELETE", "/v1/events/x/participants", null, Actor.member(1, 2)).keys().isEmpty());
        hub.png = new byte[]{1, 2, 3};
        assertEquals(3, client.bytes("/v1/events/x/board.png", Actor.member(1, 2)).length);
        hub.png = null;
        assertEquals(503, assertThrows(EventProblem.class, () -> client.bytes("/v1/events/x/board.png", Actor.APP)).status());
    }
}
