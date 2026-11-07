package com.runeclans.bot.events;

import com.runeclans.bot.commands.signup.SignupEntry;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.commands.signup.SignupSession;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import com.runeclans.bot.hubevents.HubEventClient;
import com.runeclans.bot.hubevents.HubEventMap;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.testsupport.LocalDatabase;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Discord signup of a hub event (T-934): what the bot sends the hub for the people on the signup, what it remembers of them, and how the
 * roster is mirrored back. The hub's own rules (deadline, quotas, verification) are the hub's tests.
 */
class EventSignupsHubTest {
    static final long G = 999_000_000_000_000_937L;
    static final long SIGNUP = 4242L;
    static final UUID CLAN = UUID.randomUUID();
    static final UUID EVENT = UUID.randomUUID();
    private static ConnectionSupplier supplier;
    private static HubEventMap map;

    /** A scripted hub: answers by "METHOD path" prefix, records calls. */
    static final class Hub implements HubEventClient.Hub {
        record Call(String method, String path, String body, Map<String, String> headers) {}
        final List<Call> calls = new ArrayList<>();
        final Map<String, RuneClansHubClient.Answer> answers = new LinkedHashMap<>();

        Hub ok(String key, DataObject json) {
            answers.put(key, new RuneClansHubClient.Answer(200, json));
            return this;
        }

        @Override
        public RuneClansHubClient.Answer call(String method, String path, String body, Map<String, String> headers) {
            calls.add(new Call(method, path, body, headers));
            String key = method + " " + path, best = null;
            for (String k : answers.keySet()) if (key.startsWith(k) && (best == null || k.length() > best.length())) best = k;
            return best == null ? new RuneClansHubClient.Answer(404, DataObject.empty().put("message", "Not here.")) : answers.get(best);
        }

        List<Call> of(String method, String part) {
            return calls.stream().filter(c -> c.method().equals(method) && c.path().contains(part)).toList();
        }
    }

    private Hub hub;
    private SignupService signupService;
    private EventChanges changes;
    private EventSignups signups;
    private Guild guild;
    private long local;
    private DataArray participants;
    private DataObject setup;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        map = new HubEventMap(supplier);
    }

    @BeforeEach
    void setUp() {
        hub = new Hub();
        changes = new EventChanges();
        HubEvents events = new HubEvents(new HubEventClient(hub, true), map, changes);
        signupService = Mockito.mock(SignupService.class);
        signups = new EventSignups(events, signupService, changes);
        guild = Mockito.mock(Guild.class, Mockito.RETURNS_DEEP_STUBS);
        when(guild.getIdLong()).thenReturn(G);
        JDA jda = Mockito.mock(JDA.class, Mockito.RETURNS_DEEP_STUBS);
        when(jda.getShardManager()).thenReturn(null);
        when(jda.getGuildById(G)).thenReturn(guild);
        when(guild.getJDA()).thenReturn(jda);
        local = map.localId(EVENT, CLAN, G);
        map.setSignup(local, SIGNUP);
        participants = DataArray.empty();
        setup = DataObject.empty().put("signupsClosed", false);
        hub.ok("GET /v1/guilds/" + G + "/site", DataObject.empty().put("clanId", CLAN.toString()));
        hub.answers.put("GET /v1/events/" + EVENT + "/participants", null);
        hub.answers.put("GET /v1/events/" + EVENT, null);
        hub.ok("GET /v1/events/" + EVENT + "/bans", DataObject.empty().put("bans", DataArray.empty()));
        hub.ok("POST /v1/events/" + EVENT + "/participants", DataObject.empty().put("waiting", false));
        hub.answers.put("DELETE /v1/events/" + EVENT + "/participants", new RuneClansHubClient.Answer(204, null));
        hub.ok("POST /v1/events/" + EVENT + "/signups/close", DataObject.empty());
        when(signupService.getSessionById(SIGNUP)).thenReturn(Mockito.mock(SignupSession.class));
        when(signupService.isSignupActive(SIGNUP)).thenReturn(true);
        when(signupService.removeUser(ArgumentMatchers.any(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())).thenReturn(true);
        when(signupService.addManualUser(ArgumentMatchers.any(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyLong())).thenReturn(true);
    }

    /** The hub's event answer is built from the test's current roster and setup, so every GET sees the latest. */
    private void hubState(String status) {
        DataObject event = DataObject.empty().put("id", EVENT.toString()).put("clanId", CLAN.toString()).put("name", "Bingo").put("status", status).put("scoringMode", "INDIVIDUAL")
                .put("config", DataObject.empty()).put("setup", setup).put("rev", 1);
        hub.ok("GET /v1/events/" + EVENT, event);
        hub.ok("GET /v1/events/" + EVENT + "/participants", DataObject.empty().put("participants", participants));
    }

    private void onRoster(long userId, String source) {
        participants.add(DataObject.empty().put("personId", UUID.randomUUID().toString()).put("discordId", Long.toString(userId)).put("kind", "HOME_CLAN").put("waiting", false)
                .put("buyIn", "NONE").put("joinedAt", "2026-11-06T00:00:00Z").put("verification", "NOT_REQUIRED").put("source", source));
    }

    private void onSignup(long... users) {
        List<SignupEntry> entries = new ArrayList<>();
        for (long u : users) {
            SignupEntry e = Mockito.mock(SignupEntry.class);
            when(e.userId()).thenReturn(u);
            entries.add(e);
        }
        when(signupService.getEntries(SIGNUP)).thenReturn(entries);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_map WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    @Test
    void whoSignsUpInDiscordIsAddedAsALateEntryByTheBotAndFollowedFromThenOn() {
        onRoster(2L, "STAFF");
        onSignup(1L, 2L);
        hubState("SIGNUPS");
        assertEquals(1, signups.sync(guild, local), "1 was new; 2 was already on the roster");
        Hub.Call add = hub.of("POST", "/participants").get(0);
        assertTrue(add.headers().isEmpty(), "the bot adds them itself (as itself once connected), so the deadline doesn't refuse a signup made in time");
        assertEquals("1", DataObject.fromJson(add.body()).getString("discordId"));
        assertEquals(List.of(1L, 2L), map.signupEntries(local).stream().sorted().toList(), "both are the signup's to follow now");
    }

    @Test
    void aSignupTheHubTurnsAwayIsRememberedWithItsReasonAndTheyAreToldOnce() {
        hub.answers.put("POST /v1/events/" + EVENT + "/participants", new RuneClansHubClient.Answer(409, DataObject.empty().put("message", "Your server's places are taken.")));
        onSignup(1L);
        hubState("SIGNUPS");
        assertEquals(0, signups.sync(guild, local));
        assertEquals(0, signups.sync(guild, local));
        List<EventRepository.Refusal> refused = signups.refusals(local);
        assertEquals(1, refused.size());
        assertEquals(1L, refused.get(0).userId());
        assertEquals("Your server's places are taken.", refused.get(0).reason());
        verify(guild.getJDA(), Mockito.times(1)).retrieveUserById(1L);
        assertTrue(map.signupEntries(local).isEmpty());
    }

    @Test
    void aBarredPlayerIsTakenOffTheSignupAndNotAdded() {
        hub.ok("GET /v1/events/" + EVENT + "/bans", DataObject.empty().put("bans", DataArray.empty().add(DataObject.empty().put("personId", "p").put("discordId", "3").put("reason", "Cheated.").put("at", "2026-11-06T00:00:00Z"))));
        onSignup(3L);
        hubState("SIGNUPS");
        assertEquals(0, signups.sync(guild, local));
        verify(signupService).removeUser(guild, SIGNUP, 3L);
        assertTrue(hub.of("POST", "/participants").isEmpty());
        assertEquals("Cheated.", signups.refusals(local).get(0).reason());
    }

    @Test
    void aFollowedPlayerTakenOffTheSignupLeavesTheRosterAndAStaffAddedOneStays() {
        onRoster(1L, "SIGNUP");
        onRoster(2L, "STAFF");
        map.addSignupEntry(local, 1L);
        onSignup();
        hubState("SIGNUPS");
        signups.sync(guild, local);
        List<Hub.Call> left = hub.of("DELETE", "/participants/");
        assertEquals(1, left.size());
        assertTrue(left.get(0).path().endsWith("/participants/discord:1"));
        assertTrue(map.signupEntries(local).isEmpty());
    }

    @Test
    void onceSignupsAreClosedOrTheEventHasStartedNobodyIsAddedFromTheSignup() {
        onSignup(1L);
        setup.put("signupsClosed", true);
        hubState("SIGNUPS");
        signups.sweep(guild.getJDA());
        assertTrue(hub.of("POST", "/participants").isEmpty(), "closed: removals only");
        hubState("RUNNING");
        signups.sweep(guild.getJDA());
        assertTrue(hub.of("POST", "/participants").isEmpty(), "started: the signup is only paused");
        verify(signupService, Mockito.atLeastOnce()).togglePause(SIGNUP);
        verify(signupService, never()).addManualUser(ArgumentMatchers.any(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyLong());
    }

    @Test
    void closingTellsTheHubFirstThenPausesThenSyncsOneLastTime() {
        onSignup(1L);
        hubState("SIGNUPS");
        EventSignups.Closed closed = signups.closeSignups(guild, local, HubEventClient.Actor.of(9L, G, true));
        int close = hub.calls.indexOf(hub.of("POST", "/signups/close").get(0));
        int add = hub.calls.indexOf(hub.of("POST", "/participants").get(0));
        assertTrue(close < add, "closed on the hub before the last sync");
        assertEquals("9", hub.of("POST", "/signups/close").get(0).headers().get(HubEventClient.ACTOR_HEADER));
        verify(signupService).togglePause(SIGNUP);
        assertEquals(1, closed.added());
    }

    @Test
    void aPlayerWhoJoinsOnTheWebsiteGetsAnEntryAndOneWhoLeavesLosesIt() {
        onRoster(5L, "SELF");
        onSignup();
        hubState("SIGNUPS");
        signups.useJda(guild.getJDA());
        signups.reconcile(G, local);
        verify(signupService).addManualUser(ArgumentMatchers.eq(guild), ArgumentMatchers.eq(SIGNUP), ArgumentMatchers.eq(5L), ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.eq(5L));
        assertEquals(List.of(5L), map.signupEntries(local));

        participants = DataArray.empty();
        onSignup(5L);
        hubState("SIGNUPS");
        signups.reconcile(G, local);
        verify(signupService).removeUser(guild, SIGNUP, 5L);
        assertTrue(map.signupEntries(local).isEmpty());
    }

    @Test
    void aFinishedOrCancelledEventLosesItsSignup() {
        hubState("DONE");
        signups.onStage(guild, local, EventRules.Status.DONE);
        verify(signupService).deleteSignup(guild, SIGNUP);
        assertTrue(map.eventsWithSignup().stream().noneMatch(r -> r.localId() == local));
    }
}
