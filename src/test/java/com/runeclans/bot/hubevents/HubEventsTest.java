package com.runeclans.bot.hubevents;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRoster;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventService.Joined;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.testsupport.LocalDatabase;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HubEvents} against a scripted hub: the paths and headers it sends, and how the hub's JSON becomes the bot's records. */
class HubEventsTest {
    static final long G = 999_000_000_000_000_935L;
    static final UUID CLAN = UUID.randomUUID();
    static final UUID EVENT = UUID.randomUUID();
    static final UUID TEAM = UUID.randomUUID();
    private static ConnectionSupplier supplier;
    private static HubEventMap map;
    private FakeEventHub hub;
    private EventChanges changes;
    private final List<EventChanges.Change> published = new ArrayList<>();
    private HubEvents events;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        map = new HubEventMap(supplier);
    }

    @BeforeEach
    void setUp() {
        hub = new FakeEventHub();
        changes = new EventChanges();
        changes.subscribe(published::add);
        events = new HubEvents(new HubEventClient(hub, true), map, changes);
        hub.ok("GET /v1/guilds/" + G + "/site", DataObject.empty().put("clanId", CLAN.toString()).put("slug", "test"));
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_map WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    static DataObject eventJson(String status) {
        return DataObject.empty().put("id", EVENT.toString()).put("clanId", CLAN.toString()).put("template", "bingo").put("name", "Spring bingo").put("description", "Fun")
                .put("status", status).put("scope", "PRIVATE").put("scoringMode", "TEAM").put("startsAt", "2026-11-10T18:00:00Z").put("maxParticipants", 20).put("rev", 3)
                .put("createdByDiscordId", "111").put("config", DataObject.empty().put("verification", DataObject.empty().put("required", true).put("gate", "TEAMS")))
                .put("setup", DataObject.empty().put("signupsClosed", true).put("rosterConfirmed", false))
                .put("organisers", DataArray.fromCollection(List.of(UUID.randomUUID().toString()))).put("organiserDiscordIds", DataArray.fromCollection(List.of("222")));
    }

    static DataObject participant(String discordId, String verification, boolean waiting, UUID team) {
        return DataObject.empty().put("personId", UUID.randomUUID().toString()).put("discordId", discordId).put("teamId", team == null ? null : team.toString()).put("kind", "HOME_CLAN")
                .put("waiting", waiting).put("buyIn", "NONE").put("joinedAt", "2026-11-06T00:00:00Z").put("verification", verification).put("source", "SELF").put("rating", 4);
    }

    private long mapped() {
        return map.localId(EVENT, CLAN, G);
    }

    @Test
    void aServersClanComesFromItsSiteAndIsRememberedForAWhile() {
        assertEquals(CLAN, events.clanOf(G).orElseThrow());
        events.clanOf(G);
        assertEquals(1, hub.calls("GET", "/v1/guilds/").size(), "asked once");
        hub.answer("GET /v1/guilds/" + (G + 1) + "/site", 404, null);
        EventProblem p = assertThrows(EventProblem.class, () -> events.events(G + 1, List.of(), 10));
        assertEquals(HubEvents.NO_CLAN_TEXT, p.getMessage());
    }

    @Test
    void theClansEventsAreListedAndMappedToLocalIdsAsTheyAreMet() {
        hub.ok("GET /v1/clans/" + CLAN + "/events", DataObject.empty().put("events", DataArray.empty().add(eventJson("SIGNUPS"))));
        List<Event> listed = events.events(G, List.of(Status.SIGNUPS, Status.RUNNING), 10);
        assertEquals("?status=signups,running", hub.last().path().substring(hub.last().path().indexOf('?')));
        assertEquals(1, listed.size());
        Event e = listed.get(0);
        assertEquals(mapped(), e.id(), "the same local id every time");
        assertEquals(G, e.guildId());
        assertEquals(Status.SIGNUPS, e.status());
        assertEquals(ScoringMode.TEAM, e.scoringMode());
        assertEquals(111L, e.createdBy());
        assertTrue(DataObject.fromJson(e.config()).hasKey("verification"), "the config comes along");
    }

    @Test
    void anEventIsReadByItsLocalIdOnlyInItsOwnServer() {
        long local = mapped();
        hub.ok("GET /v1/events/" + EVENT, eventJson("RUNNING"));
        assertEquals("Spring bingo", events.event(G, local).name());
        assertTrue(hub.last().headers().isEmpty(), "read as the application");
        assertEquals(404, assertThrows(EventProblem.class, () -> events.event(G + 1, local)).status());
        assertEquals(List.of(222L), events.organisers(G, local));
        assertTrue(events.mayOrganise(events.event(G, local), 222L, false));
        assertFalse(events.mayOrganise(events.event(G, local), 333L, false));
        assertTrue(events.setup(local).signupsClosed());
    }

    @Test
    void joiningAndLeavingSpeakForTheMemberAndTellTheBot() {
        long local = mapped();
        hub.ok("POST /v1/events/" + EVENT + "/participants", DataObject.empty().put("waiting", true).put("position", 2));
        Joined joined = events.join(G, local, 55L, null, false, Actor.member(55L, G));
        assertTrue(joined.waiting());
        assertEquals(2, joined.position());
        FakeEventHub.Call call = hub.last();
        assertEquals("55", call.headers().get(HubEventClient.ACTOR_HEADER));
        assertFalse(call.json().hasKey("discordId"), "a member joins themselves");
        assertEquals(List.of(EventChanges.Kind.ROSTER, EventChanges.Kind.PLAYER_JOINED), published.stream().map(EventChanges.Change::kind).toList());
        assertEquals(55L, published.get(1).userId());

        published.clear();
        events.join(G, local, 56L, null, true, Actor.APP);
        assertEquals("56", hub.last().json().getString("discordId"), "a late entry names the player");
        assertEquals(List.of(EventChanges.Kind.ROSTER), published.stream().map(EventChanges.Change::kind).toList(), "a staff add is no self-join");

        hub.answer("DELETE /v1/events/" + EVENT + "/participants", 204, null);
        events.leave(G, local, 55L, Actor.member(55L, G));
        assertEquals("/v1/events/" + EVENT + "/participants", hub.last().path(), "oneself: no person in the path");
        events.leave(G, local, 56L, Actor.APP);
        assertEquals("/v1/events/" + EVENT + "/participants/discord:56", hub.last().path());
    }

    @Test
    void theHubsRefusalReachesTheCallerAsIs() {
        long local = mapped();
        hub.answer("POST /v1/events/" + EVENT + "/participants", 409, DataObject.empty().put("message", "Signups are closed."));
        EventProblem p = assertThrows(EventProblem.class, () -> events.join(G, local, 55L, null, false, Actor.member(55L, G)));
        assertEquals(409, p.status());
        assertEquals("Signups are closed.", p.getMessage());
        assertTrue(published.isEmpty(), "nothing changed, nothing told");
    }

    @Test
    void participantsAndTeamsCarryDiscordIdsAndLocalTeamIds() {
        long local = mapped();
        hub.ok("GET /v1/events/" + EVENT + "/participants", DataObject.empty().put("participants", DataArray.empty()
                .add(participant("55", "VERIFIED", false, TEAM)).add(participant(null, "PENDING", true, null))));
        hub.ok("GET /v1/events/" + EVENT + "/teams", DataObject.empty().put("teams", DataArray.empty()
                .add(DataObject.empty().put("id", TEAM.toString()).put("name", "Red").put("captainId", UUID.randomUUID().toString()).put("captainDiscordId", "55"))));
        List<Participant> players = events.participants(local);
        assertEquals(2, players.size());
        assertEquals(55L, players.get(0).userId());
        assertEquals(EventRoster.Verification.VERIFIED, players.get(0).verification());
        assertEquals(4, players.get(0).rating());
        assertEquals(0L, players.get(1).userId(), "a person without a Discord id is 0: Discord code skips them");
        var teams = events.teams(local);
        assertEquals("Red", teams.get(0).name());
        assertEquals(55L, teams.get(0).captainId());
        assertEquals(players.get(0).teamId(), teams.get(0).id(), "the player's team is the same local id");
        assertEquals(TEAM, map.hubId(HubEventMap.TEAM, teams.get(0).id()).orElseThrow());
    }

    @Test
    void whoMayBePutOnATeamFollowsTheEventsVerificationGate() {
        long local = mapped();
        hub.ok("GET /v1/events/" + EVENT, eventJson("SIGNUPS"));
        Event e = events.event(G, local);
        Participant verified = events.participants(DataArray.empty().add(participant("1", "VERIFIED", false, null)), map.event(local).orElseThrow()).get(0);
        Participant pending = events.participants(DataArray.empty().add(participant("2", "PENDING", false, null)), map.event(local).orElseThrow()).get(0);
        Participant waiting = events.participants(DataArray.empty().add(participant("3", "VERIFIED", true, null)), map.event(local).orElseThrow()).get(0);
        Participant rejected = events.participants(DataArray.empty().add(participant("4", "REJECTED", false, null)), map.event(local).orElseThrow()).get(0);
        assertNull(events.teamBlock(e, verified));
        assertTrue(events.teamBlock(e, pending).contains("hasn't been verified"));
        assertTrue(events.teamBlock(e, waiting).contains("waitlist"));
        assertTrue(events.teamBlock(e, rejected, "Zed").startsWith("Zed's verification was turned down"));
    }

    @Test
    void standingsNameMembersAndLocalTeams() {
        long local = mapped();
        long team = map.localId(HubEventMap.TEAM, TEAM, local);
        hub.ok("GET /v1/events/" + EVENT + "/standings", DataObject.empty().put("standings", DataArray.empty()
                .add(DataObject.empty().put("place", 1).put("subject", "t:" + TEAM).put("teamId", TEAM.toString()).put("points", 40).put("objectivesCompleted", 2))
                .add(DataObject.empty().put("place", 2).put("subject", "u:x").put("personId", "x").put("discordId", "55").put("points", 10))));
        var rows = events.standings(G, local);
        assertEquals("t:" + team, rows.get(0).subject());
        assertEquals(40, rows.get(0).points());
        assertEquals("u:55", rows.get(1).subject());
    }

    @Test
    void aClaimIsSentAsTheMemberAndDecidedAsTheOrganiserWithLocalIds() {
        long local = mapped();
        UUID claim = UUID.randomUUID();
        DataObject claimJson = DataObject.empty().put("id", claim.toString()).put("kind", "OBJECTIVE").put("objectiveKey", "zuk").put("personId", "p").put("discordId", "55")
                .put("amount", 1).put("note", "").put("proofUrl", "https://cdn/x.png").put("status", "PENDING").put("createdAt", "2026-11-06T00:00:00Z");
        hub.ok("POST /v1/events/" + EVENT + "/claims", DataObject.empty().put("claim", claimJson));
        long claimId = events.submitClaim(G, local, "zuk", 55L, 1, "note", "https://cdn/x.png");
        assertEquals("55", hub.last().headers().get(HubEventClient.ACTOR_HEADER));
        assertEquals("zuk", hub.last().json().getString("objectiveKey"));
        assertEquals(claim, map.hubId(HubEventMap.CLAIM, claimId).orElseThrow());

        DataObject decided = DataObject.fromJson(claimJson.toJson()).put("status", "APPROVED").put("decidedBy", "q").put("decidedByDiscordId", "222").put("decisionNote", "nice");
        hub.ok("POST /v1/events/" + EVENT + "/claims/" + claim + "/decide", DataObject.empty().put("claim", decided));
        map.setReceipt(local, claimId, 7L, 8L);
        var after = events.decideClaim(G, local, claimId, true, "nice", Actor.of(222L, G, false));
        assertTrue(hub.last().json().getBoolean("approve"));
        assertEquals(222L, after.decidedBy());
        assertEquals(8L, after.receiptMessageId(), "the receipt's whereabouts are the bot's");
        EventChanges.Change change = published.get(published.size() - 1);
        assertEquals(EventChanges.Kind.CLAIM_DECIDED, change.kind());
        assertEquals(claimId, change.claimId());
        assertTrue(change.approved());
    }

    @Test
    void aMembersOwnEventsComeWithTheirPlaceInEach() {
        hub.ok("GET /v1/people/me/events", DataObject.empty().put("events", DataArray.empty()
                .add(eventJson("RUNNING").put("me", participant("55", "VERIFIED", false, null).remove("discordId")))
                .add(eventJson("RUNNING").put("id", UUID.randomUUID().toString()).put("clanId", UUID.randomUUID().toString()))));
        var mine = events.mine(G, 55L, 10);
        assertEquals("55", hub.last().headers().get(HubEventClient.ACTOR_HEADER));
        assertEquals(1, mine.size(), "another clan's event isn't this server's");
        assertEquals(mapped(), mine.get(0).event().id());
        assertEquals(55L, mine.get(0).me().userId());
    }

    @Test
    void theBoardIsTheHubsPictureDrawnForTheMember() {
        long local = mapped();
        hub.png = new byte[]{9};
        assertEquals(1, events.boardPng(G, local, 55L, true).length);
        assertEquals("/v1/events/" + EVENT + "/board.png?mine=true", hub.last().path());
        assertEquals("55", hub.last().headers().get(HubEventClient.ACTOR_HEADER));
    }
}
