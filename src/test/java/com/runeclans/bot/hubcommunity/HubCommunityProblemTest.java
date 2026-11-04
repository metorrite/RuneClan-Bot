package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubCommunityProblemTest {
    private static RuneClansHubClient.Answer answer(int status, String error, String message) {
        DataObject json = DataObject.empty();
        if (error != null) json.put("error", error);
        if (message != null) json.put("message", message);
        return new RuneClansHubClient.Answer(status, json);
    }

    @Test
    void noAnswerBusyOrFailingIsDown() {
        for (RuneClansHubClient.Answer a : new RuneClansHubClient.Answer[] {null, answer(429, null, null), answer(500, "x", "y"), answer(503, null, null)}) {
            HubCommunityProblem p = HubCommunityProblem.of(a);
            assertEquals(HubCommunityProblem.Kind.DOWN, p.kind());
            assertEquals(503, p.httpStatus());
            assertEquals(HubCommunityProblem.DOWN_TEXT, p.getMessage());
        }
    }

    @Test
    void staleIfMatchIsChanged() {
        HubCommunityProblem p = HubCommunityProblem.of(answer(409, "changed_elsewhere", "Changed."));
        assertEquals(HubCommunityProblem.Kind.CHANGED, p.kind());
        assertEquals(409, p.httpStatus());
        assertEquals(HubCommunityProblem.CHANGED_TEXT, p.getMessage());
        assertEquals(HubCommunityProblem.Kind.CHANGED, HubCommunityProblem.of(answer(428, "if_match_required", null)).kind());
    }

    @Test
    void refusalKeepsTheHubsWordsAndCode() {
        HubCommunityProblem p = HubCommunityProblem.of(answer(409, "full", "This signup is full."));
        assertEquals(HubCommunityProblem.Kind.REFUSED, p.kind());
        assertEquals("full", p.code());
        assertEquals("This signup is full.", p.getMessage());
        assertEquals(409, p.httpStatus());
        assertEquals("That isn't there any more.", HubCommunityProblem.of(answer(404, null, null)).getMessage());
        assertEquals(202, HubCommunityProblem.queued().httpStatus());
        assertEquals(503, HubCommunityProblem.refused(0, "x").httpStatus());
    }

    @Test
    void clientSendsIfMatchAndTurnsFailuresIntoProblems() {
        FakeCommunityHub fake = new FakeCommunityHub().ok("PATCH /v1/signups/", DataObject.empty().put("rev", 4))
                .answer("POST /v1/polls/", 409, DataObject.empty().put("error", "closed").put("message", "This poll is closed."));
        HubCommunityClient client = new HubCommunityClient(fake);
        assertEquals(4, client.send("PATCH", "/v1/signups/x", DataObject.empty(), 3L).getInt("rev"));
        assertEquals(Map.of("If-Match", "3"), fake.calls.getFirst().headers());
        assertTrue(client.find("/v1/nothing").isEmpty(), "a 404 read is empty, not a problem");
        HubCommunityProblem refused = assertThrows(HubCommunityProblem.class, () -> client.memberWrite("POST", "/v1/polls/p/votes", DataObject.empty(), "k"));
        assertEquals("This poll is closed.", refused.getMessage());
        fake.holdWrites = true;
        HubCommunityProblem queued = assertThrows(HubCommunityProblem.class, () -> client.memberWrite("POST", "/v1/polls/p/votes", DataObject.empty(), "k"));
        assertEquals(HubCommunityProblem.Kind.QUEUED, queued.kind());
    }

    @Test
    void switchingStoreFollowsTheSwitchOnEveryCall() {
        Supplier<String> bot = () -> "bot";
        Supplier<String> hub = () -> "hub";
        AtomicBoolean on = new AtomicBoolean(false);
        @SuppressWarnings("unchecked")
        Supplier<String> store = CommunityStores.switching(Supplier.class, () -> on.get() ? hub : bot);
        assertEquals("bot", store.get());
        on.set(true);
        assertEquals("hub", store.get());
        assertTrue(store.equals(store));
        assertFalse(store.equals(bot));
        assertTrue(store.toString().startsWith("switching"));
    }

    @Test
    void switchingStorePassesTheStoresOwnExceptions() {
        Supplier<String> failing = () -> {
            throw HubCommunityProblem.refused(409, "No.");
        };
        @SuppressWarnings("unchecked")
        Supplier<String> store = CommunityStores.switching(Supplier.class, () -> failing);
        HubCommunityProblem p = assertThrows(HubCommunityProblem.class, store::get);
        assertEquals("No.", p.getMessage());
    }

    @Test
    void theSwitchIsOnOnlyWhenAskedFor() {
        assertFalse(HubCommunity.off().onHub());
        assertTrue(HubCommunity.on().onHub());
    }
}
