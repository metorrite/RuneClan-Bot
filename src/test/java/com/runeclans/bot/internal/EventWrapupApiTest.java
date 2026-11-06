package com.runeclans.bot.internal;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.events.EventSettings;
import com.runeclans.bot.events.EventSpaceOps;
import com.runeclans.bot.events.EventWrapup;
import com.runeclans.bot.events.EventWrapup.Step;
import com.runeclans.bot.events.EventWrapupOps;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wrap-up's dashboard routes: what the page shows (the hub's record of which Discord steps are done, T-941), that every deleting action needs an
 * explicit "yes", that the settings go to the hub, and that the export is the hub's spreadsheet. The Discord work itself is mocked; the pure rules are
 * in {@link com.runeclans.bot.events.EventWrapupTest}.
 */
class EventWrapupApiTest {
    private static final long G = 999_000_000_000_000_071L;
    private static final long ID = 42L;
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    private HubEvents events;
    private EventWrapupOps wrapupOps;
    private EventWrapupApi api;
    private Guild guild;
    private Member actor;
    private Event event;

    @BeforeEach
    void setUp() {
        events = Mockito.mock(HubEvents.class);
        wrapupOps = Mockito.mock(EventWrapupOps.class);
        EventSpaceOps spaceOps = Mockito.mock(EventSpaceOps.class);
        Mockito.when(wrapupOps.counts(Mockito.anyLong())).thenReturn(new EventWrapupOps.Counts(3, 1, 2, 0));
        Mockito.when(wrapupOps.view(ID)).thenReturn(new EventWrapupOps.View(EventWrapup.State.NONE, Map.of(), null, null));
        api = new EventWrapupApi(events, wrapupOps, spaceOps);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        actor = Mockito.mock(Member.class);
        Mockito.when(actor.getId()).thenReturn("9");
        Mockito.when(actor.getIdLong()).thenReturn(9L);
        status(EventRules.Status.RUNNING);
        Mockito.when(events.exportPath(ID)).thenReturn("/v1/events/abc/export.csv");
    }

    private void status(EventRules.Status status) {
        event = new Event(ID, G, "custom", "Wrap", "", status, EventRules.Scope.PRIVATE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0, "{}", 9L, NOW, null, false);
        Mockito.when(events.event(G, ID)).thenReturn(event);
        Mockito.when(events.settingsOf(event)).thenReturn(EventSettings.defaults());
    }

    private DataObject call(String method, String path, DataObject body) {
        String[] parts = (("events/" + ID + "/wrapup") + (path.isEmpty() ? "" : "/" + path)).split("/");
        return api.route(guild, actor, method, parts, () -> body);
    }

    @Test
    void thePageShowsTheDefaultsTheCountsAndWhatIsNotDoneYet() {
        DataObject view = call("GET", "", DataObject.empty());
        assertFalse(view.getBoolean("over"), "still going");
        assertEquals(7, view.getObject("settings").getInt("rolesDays"));
        assertEquals(-1, view.getObject("settings").getInt("channelsDays"));
        assertEquals(3, view.getObject("counts").getInt("channels"));
        assertTrue(view.getObject("state").isNull("finishedAt"));
        assertTrue(view.isNull("job"));
        assertTrue(view.isNull("export"), "the bot keeps no export of its own");
        assertEquals("/v1/events/abc/export.csv", view.getString("exportPath"), "the hub's spreadsheet export");
    }

    @Test
    void thePageShowsTheHubsRecordOfTheSteps() {
        status(EventRules.Status.DONE);
        Instant later = NOW.plusSeconds(7 * 86_400);
        Mockito.when(wrapupOps.view(ID)).thenReturn(new EventWrapupOps.View(new EventWrapup.State(NOW, NOW, null, NOW), Map.of(Step.ROLES, later), "archived", null));
        DataObject view = call("GET", "", DataObject.empty());
        assertTrue(view.getBoolean("over"));
        assertEquals(NOW.toString(), view.getObject("state").getString("lockedAt"));
        assertEquals("archived", view.getObject("state").getString("channelsHow"));
        assertTrue(view.getObject("schedule").isNull("lockAt"), "locking is already done");
        assertEquals(later.toString(), view.getObject("schedule").getString("rolesAt"), "the roles are still to go, a week after the end");
        assertTrue(view.getObject("schedule").isNull("channelsAt"));
    }

    @Test
    void routesOfOtherPartsOfTheDashboardAreNotThisOnes() {
        assertNull(api.route(guild, actor, "GET", new String[]{"events", Long.toString(ID), "space"}, DataObject::empty));
        assertNull(call("POST", "dance", DataObject.empty()));
        assertNull(call("POST", "export", DataObject.empty()), "the zip export is gone: the hub's export.csv is linked instead");
        assertNull(call("GET", "export/file", DataObject.empty()));
    }

    @Test
    void everyActionThatDeletesNeedsAnExplicitYes() {
        status(EventRules.Status.DONE);
        for (String action : new String[]{"roles", "archive", "delete"}) {
            ApiError refused = assertThrows(ApiError.class, () -> call("POST", action, DataObject.empty()), action);
            assertEquals(400, refused.status, action);
        }
        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", "compress", DataObject.empty().put("deleteOriginals", true))).status, "compressing and deleting the originals");
        Mockito.verify(wrapupOps, Mockito.never()).startRoles(Mockito.any(), Mockito.any());
        Mockito.verify(wrapupOps, Mockito.never()).startArchive(Mockito.any(), Mockito.any());
        Mockito.verify(wrapupOps, Mockito.never()).startDelete(Mockito.any(), Mockito.any());

        call("POST", "roles", DataObject.empty().put("confirm", true));
        Mockito.verify(wrapupOps).startRoles(Mockito.eq(guild), Mockito.any());
        call("POST", "lock", DataObject.empty());
        Mockito.verify(wrapupOps).startLock(Mockito.eq(guild), Mockito.any());
        call("POST", "compress", DataObject.empty().put("all", true));
        Mockito.verify(wrapupOps).startCompress(Mockito.eq(guild), Mockito.any(), Mockito.eq(true), Mockito.eq(false));
    }

    @Test
    void theSettingsGoToTheHubAsTheStaffMember() {
        DataObject body = DataObject.fromJson("{\"lockOnFinish\":false,\"rolesDays\":3}");
        DataObject saved = call("PUT", "settings", body);
        assertNotNull(saved);
        Mockito.verify(events).setWrapupSettings(Mockito.eq(G), Mockito.eq(ID), Mockito.eq(body), Mockito.argThat(a -> a.discordId() == 9L && a.staff()));
    }
}
