package com.runeclans.bot.events;

import com.runeclans.bot.hubevents.HubEventClient;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Skill of the week is the hub's (T-941): the bot reads the clan's week as the server itself, saves the setting as the staff member, takes a member's
 * vote and auto-enrol choice as them, shows the public widget with nobody named, and has nothing for a server without a clan on RuneClans.
 */
class SkillWeekServiceTest {
    private static final long G = 1L;
    private static final UUID CLAN = UUID.randomUUID();

    /** A scripted hub, recording each call's method, path and headers. */
    private final List<String> calls = new ArrayList<>();
    private final Map<String, DataObject> answers = new LinkedHashMap<>();
    private SkillWeekService service;

    @BeforeEach
    void setUp() {
        HubEventClient.Hub hub = new HubEventClient.Hub() {
            @Override
            public RuneClansHubClient.Answer call(String method, String path, String body, Map<String, String> headers) {
                calls.add(method + " " + path + " " + headers.getOrDefault(HubEventClient.ACTOR_HEADER, "-") + " " + headers.getOrDefault(HubEventClient.STAFF_HEADER, "-") + (body == null ? "" : " " + body));
                String key = method + " " + path.replaceAll("\\?.*", "");
                return answers.containsKey(key) ? new RuneClansHubClient.Answer(200, answers.get(key), null)
                        : new RuneClansHubClient.Answer(409, DataObject.empty().put("message", "This clan doesn't vote."), null);
            }

            @Override
            public byte[] bytes(String path, Map<String, String> headers) {
                return null;
            }
        };
        HubEvents events = mock(HubEvents.class);
        when(events.client()).thenReturn(new HubEventClient(hub, true));
        when(events.enabled()).thenReturn(true);
        when(events.clanOf(G)).thenReturn(Optional.of(CLAN));
        when(events.clanOf(2L)).thenReturn(Optional.empty());
        when(events.self(G)).thenReturn(Actor.of(77L, G, true));
        service = new SkillWeekService(events);
        DataObject standing = DataObject.empty().put("personId", "p1").put("name", "Alice").put("xp", 12_345).put("mine", true);
        answers.put("GET /v1/clans/" + CLAN + "/skill-week", DataObject.empty().put("enabled", true).put("autoEnrol", true).put("optedOut", true).put("announceChannelId", "555")
                .put("current", DataObject.empty().put("skill", "Mining").put("standings", DataArray.empty().add(standing)))
                .put("last", DataObject.empty().put("skill", "Fishing").put("winners", DataArray.empty().add(DataObject.fromJson(standing.toString()))))
                .put("vote", DataObject.empty().put("open", true).put("mine", 3)));
        answers.put("POST /v1/clans/" + CLAN + "/skill-week", DataObject.empty().put("enabled", false));
        answers.put("POST /v1/clans/" + CLAN + "/skill-week/auto", DataObject.empty().put("autoEnrol", true).put("optedOut", false));
    }

    @Test
    void theDashboardViewIsReadAsTheServerItselfWithTheShownCountClamped() {
        DataObject view = service.view(G, 999);
        assertTrue(view.getBoolean("available"));
        assertEquals("Alice", view.getObject("current").getArray("standings").getObject(0).getString("name"), "staff see every name");
        assertEquals("GET /v1/clans/" + CLAN + "/skill-week?shown=" + SkillWeekService.MAX_SHOWN + " 77 " + G, calls.get(0));
    }

    @Test
    void thePublicWidgetNamesNobodyAndShowsNoXp() {
        DataObject view = service.publicView(G, 5);
        assertFalse(view.hasKey("announceChannelId"));
        DataObject row = view.getObject("current").getArray("standings").getObject(0);
        assertEquals("A member", row.getString("name"));
        assertTrue(row.isNull("xp"));
        assertFalse(row.getBoolean("mine"));
        assertEquals("A member", view.getObject("last").getArray("winners").getObject(0).getString("name"));
    }

    @Test
    void savingGoesAsTheStaffMemberWhoPressedSave() {
        DataObject out = service.save(G, 42L, DataObject.empty().put("enabled", false));
        assertFalse(out.getBoolean("enabled"));
        assertTrue(calls.get(0).startsWith("POST /v1/clans/" + CLAN + "/skill-week 42 " + G), calls.get(0));
    }

    @Test
    void aMembersChoicesAndVoteGoAsThemselves() {
        DataObject auto = service.autoView(G, 42L);
        assertTrue(auto.getBoolean("autoEnrol"));
        assertTrue(auto.getBoolean("optedOut"));
        assertTrue(calls.get(0).contains("skill-week?shown=1 42 -"), calls.get(0));
        DataObject back = service.setOptOut(G, 42L, false);
        assertFalse(back.getBoolean("optedOut"));
        assertTrue(calls.get(1).startsWith("POST /v1/clans/" + CLAN + "/skill-week/auto 42 -"), calls.get(1));
        assertEquals(3, service.voteView(G, 42L).getInt("mine"));
        EventProblem refused = assertThrows(EventProblem.class, () -> service.castVote(G, 42L, 3));
        assertEquals(409, refused.status());
    }

    @Test
    void aServerWithoutAClanOnRuneClansHasNoSkillOfTheWeek() {
        EventProblem none = assertThrows(EventProblem.class, () -> service.view(2L, 5));
        assertEquals(409, none.status());
        assertTrue(calls.isEmpty());
    }
}
