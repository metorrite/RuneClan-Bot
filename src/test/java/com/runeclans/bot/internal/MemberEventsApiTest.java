package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Claim;
import com.runeclans.bot.events.EventRepository.ClaimKind;
import com.runeclans.bot.events.EventRepository.ClaimStatus;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRoster;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventService.Joined;
import com.runeclans.bot.events.PrizeRules.BuyInStatus;
import com.runeclans.bot.events.TeamRules;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.hubevents.HubEvents;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A member's events on the website ({@code /internal/me/events}): read from and joined on the hub (T-932), so the website's Event Manager events show
 * and a join reaches the hub. Taking part needs the member to be in the server's Discord (a member who isn't is told to join first, with the clan's
 * link when it has one). The hub applies the signup rules; this only shapes the member's own view.
 */
class MemberEventsApiTest {
    private static final long G = 999_000_000_000_000_032L;
    private static final long INSIDE = 501L;
    private static final long OUTSIDE = 599L;
    private static final String VERIFY = "{\"verification\":{\"required\":true,\"gate\":\"TEAMS\",\"instructions\":\"Send your bank\"}}";

    private HubEvents events;
    private ClanProfileRepository profiles;
    private com.runeclans.bot.configure.GuildSettingsService guildSettings;
    private MemberEventsApi api;
    private Guild guild;

    @BeforeEach
    void setUp() {
        events = Mockito.mock(HubEvents.class);
        guildSettings = Mockito.mock(com.runeclans.bot.configure.GuildSettingsService.class);
        when(guildSettings.getEffective(G)).thenReturn(new com.runeclans.bot.configure.GuildSettings(G, null, null, null, null, null, null, null, null, false, null, null, null));
        profiles = Mockito.mock(ClanProfileRepository.class);
        when(profiles.get(G)).thenReturn(Optional.empty());
        api = new MemberEventsApi(events, profiles, Mockito.mock(com.runeclans.bot.events.EventSpaceRepository.class),
                new com.runeclans.bot.clanprofile.JoinLinks(guildSettings, Mockito.mock(com.runeclans.bot.clanprofile.ClanRecruitingRepository.class, Mockito.RETURNS_DEEP_STUBS)),
                Mockito.mock(com.runeclans.bot.events.EventUpdateRepository.class));
        guild = Mockito.mock(Guild.class);
        when(guild.getIdLong()).thenReturn(G);
        when(guild.getName()).thenReturn("Younglings");
        when(guild.getMemberById(INSIDE)).thenReturn(Mockito.mock(Member.class)); // anyone else isn't in the server
        when(events.mine(anyLong(), anyLong(), anyInt())).thenReturn(List.of());
        when(events.events(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(events.setup(anyLong())).thenReturn(EventRepository.Setup.NONE);
        com.runeclans.bot.hubevents.HubEventMap map = Mockito.mock(com.runeclans.bot.hubevents.HubEventMap.class);
        when(events.map()).thenReturn(map);
        when(map.event(7L)).thenReturn(Optional.of(new com.runeclans.bot.hubevents.HubEventMap.Row(7, HUB_ID, java.util.UUID.randomUUID(), G, null, null, null, null, null, 0)));
    }

    private static final java.util.UUID HUB_ID = java.util.UUID.randomUUID();

    private static Event event(long id, String name, Status status, String config) {
        return new Event(id, G, "custom", name, "", status, EventRules.Scope.PRIVATE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0, config, 9L, Instant.EPOCH, null, false);
    }

    private static Participant player(long eventId, long userId, EventRoster.Verification verification, String note) {
        return new Participant(eventId, userId, G, null, TeamRules.Kind.HOME_CLAN, false, BuyInStatus.NONE, Instant.EPOCH, verification, null, null, note, null, EventRepository.RosterSource.SELF);
    }

    /** An event on the hub, listed compact (no settings) and read in full. */
    private Event onHub(long id, String name, Status status, String config) {
        Event full = event(id, name, status, config);
        Event compact = event(id, name, status, "{}");
        when(events.full(compact)).thenReturn(Optional.of(full));
        when(events.fullWithSetup(compact)).thenReturn(Optional.of(new HubEvents.Full(full, EventRepository.Setup.NONE)));
        when(events.event(G, id)).thenReturn(full);
        return compact;
    }

    private static DataObject body(long eventId) {
        return DataObject.empty().put("eventId", Long.toString(eventId));
    }

    @Test
    void aMemberOutsideTheServerIsToldToJoinItAndGivenTheClansLinkWhenItHasOne() {
        EventProblem plain = assertThrows(EventProblem.class, () -> api.join(guild, OUTSIDE, body(1)));
        assertEquals(403, plain.status());
        assertTrue(plain.getMessage().contains("Younglings Discord server"), plain.getMessage());
        assertFalse(plain.getMessage().contains("http"), "no link to give");

        ClanProfile profile = Mockito.mock(ClanProfile.class);
        when(profile.applyUrl()).thenReturn("https://discord.gg/example");
        when(profiles.get(G)).thenReturn(Optional.of(profile));
        EventProblem withLink = assertThrows(EventProblem.class, () -> api.join(guild, OUTSIDE, body(1)));
        assertTrue(withLink.getMessage().contains("https://discord.gg/example"), withLink.getMessage());
        verify(events, never()).join(anyLong(), anyLong(), anyLong(), any(), Mockito.anyBoolean(), any(Actor.class));
    }

    @Test
    void theServersInviteLinkIsUsedBeforeTheApplyLink() {
        ClanProfile profile = Mockito.mock(ClanProfile.class);
        when(profile.applyUrl()).thenReturn("https://discord.gg/apply");
        when(profiles.get(G)).thenReturn(Optional.of(profile));
        when(guildSettings.getEffective(G)).thenReturn(new com.runeclans.bot.configure.GuildSettings(G, null, null, null, null, null, null, null, null, false, null, null, null, null, "https://discord.gg/invite1"));

        String message = api.notInServerMessage(guild);

        assertTrue(message.contains("https://discord.gg/invite1"), message);
        assertFalse(message.contains("apply"), message);
    }

    @Test
    void aJoinGoesToTheHubAsTheMemberAndTheirEventsAreTheHubs() {
        Event listed = onHub(7, "Hub bingo", Status.SIGNUPS, VERIFY);
        when(events.events(eq(G), eq(List.of(Status.SIGNUPS)), anyInt())).thenReturn(List.of(listed));
        DataObject before = api.mine(guild, INSIDE);
        assertEquals(1, before.getArray("open").length(), "an event made on the website is offered");
        assertTrue(before.getArray("open").getObject(0).getBoolean("verification"), "its settings come from the event read in full");
        assertEquals(HUB_ID.toString(), before.getArray("open").getObject(0).getString("hubId"), "the website dedupes its own hub reads on the hub id");

        when(events.join(eq(G), eq(7L), eq(INSIDE), any(), eq(false), any(Actor.class))).thenReturn(new Joined(true, 3));
        when(events.mine(G, INSIDE, 30)).thenReturn(List.of(new HubEvents.Mine(listed, player(7, INSIDE, EventRoster.Verification.PENDING, ""))));
        DataObject joined = api.join(guild, INSIDE, body(7));
        verify(events).join(eq(G), eq(7L), eq(INSIDE), eq(null), eq(false), eq(Actor.member(INSIDE, G)));
        assertTrue(joined.getObject("result").getBoolean("waiting"));
        assertEquals(3, joined.getObject("result").getInt("position"));
        assertEquals(1, joined.getArray("events").length(), "their events now list it");
        assertEquals(0, joined.getArray("open").length(), "and it is no longer offered to them");
        DataObject verification = joined.getArray("events").getObject(0).getObject("verification");
        assertTrue(verification.getBoolean("required"));
        assertEquals("PENDING", verification.getString("state"));
        assertEquals("Send your bank", verification.getString("instructions"));
        assertEquals(400, assertThrows(EventProblem.class, () -> api.join(guild, INSIDE, DataObject.empty())).status(), "say which event");
    }

    @Test
    void theHubsRefusalIsPassedOn() {
        when(events.join(eq(G), eq(8L), eq(INSIDE), any(), eq(false), any(Actor.class))).thenThrow(new EventProblem(409, "Signups have closed."));
        EventProblem refused = assertThrows(EventProblem.class, () -> api.join(guild, INSIDE, body(8)));
        assertEquals(409, refused.status());
        assertTrue(refused.getMessage().contains("Signups have closed"));
    }

    @Test
    void aMemberCanOnlyLeaveWhileTheEventIsTakingSignups() {
        onHub(9, "Leave", Status.SIGNUPS, "{}");
        api.leave(guild, INSIDE, body(9));
        verify(events).leave(G, 9L, INSIDE, Actor.member(INSIDE, G));
        onHub(10, "Running", Status.RUNNING, "{}");
        EventProblem refused = assertThrows(EventProblem.class, () -> api.leave(guild, INSIDE, body(10)));
        assertEquals(409, refused.status());
        assertTrue(refused.getMessage().contains("taking signups"), refused.getMessage());
    }

    @Test
    void onlyTheFirstFewEventsGetTheExtraHubReadsAndOneThatFailsIsLeftOut() {
        List<HubEvents.Mine> many = new java.util.ArrayList<>();
        for (long id = 100; id < 100 + MemberEventsApi.DETAILED + 5; id++) many.add(new HubEvents.Mine(onHub(id, "E" + id, Status.SIGNUPS, "{}"), player(id, INSIDE, EventRoster.Verification.NOT_REQUIRED, "")));
        when(events.mine(G, INSIDE, 30)).thenReturn(many);
        Event broken = event(300, "Broken", Status.SIGNUPS, "{}");
        Event fine = onHub(301, "Fine", Status.SIGNUPS, "{}");
        when(events.events(eq(G), eq(List.of(Status.SIGNUPS)), anyInt())).thenReturn(List.of(broken, fine));
        when(events.fullWithSetup(broken)).thenThrow(new EventProblem(503, "RuneClans can't be reached."));
        DataObject mine = api.mine(guild, INSIDE);
        assertEquals(many.size(), mine.getArray("events").length(), "every event is listed");
        verify(events, Mockito.times(MemberEventsApi.DETAILED)).full(any());
        assertEquals(1, mine.getArray("open").length(), "the one the hub couldn't read is left out, the rest still show");
        assertEquals("Fine", mine.getArray("open").getObject(0).getString("name"));
    }

    @Test
    void draftsCancelledArchivedAndTakenDownEventsAreLeftOutAsOnTheWebsite() {
        Event running = onHub(400, "Running", Status.RUNNING, "{}");
        Event draft = onHub(401, "Draft", Status.DRAFT, "{}");
        Event cancelled = onHub(402, "Cancelled", Status.CANCELLED, "{}");
        Event archived = new Event(403, G, "custom", "Archived", "", Status.DONE, EventRules.Scope.PRIVATE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0, "{}", 9L, Instant.EPOCH, null, true);
        Event down = onHub(404, "Down", Status.RUNNING, "{}");
        when(events.mine(G, INSIDE, 30)).thenReturn(List.of(
                new HubEvents.Mine(running, player(400, INSIDE, EventRoster.Verification.NOT_REQUIRED, "")),
                new HubEvents.Mine(draft, player(401, INSIDE, EventRoster.Verification.NOT_REQUIRED, "")),
                new HubEvents.Mine(cancelled, player(402, INSIDE, EventRoster.Verification.NOT_REQUIRED, "")),
                new HubEvents.Mine(archived, player(403, INSIDE, EventRoster.Verification.NOT_REQUIRED, "")),
                new HubEvents.Mine(down, player(404, INSIDE, EventRoster.Verification.NOT_REQUIRED, ""), true)));
        Event open = onHub(405, "Open", Status.SIGNUPS, "{}");
        Event openDown = event(406, "Open but down", Status.SIGNUPS, "{}");
        when(events.fullWithSetup(openDown)).thenReturn(Optional.of(new HubEvents.Full(event(406, "Open but down", Status.SIGNUPS, "{\"hub\":{\"takedown\":\"spam\"}}"), EventRepository.Setup.NONE)));
        when(events.events(eq(G), eq(List.of(Status.SIGNUPS)), anyInt())).thenReturn(List.of(open, openDown));
        DataObject mine = api.mine(guild, INSIDE);
        assertEquals(1, mine.getArray("events").length(), "only the announced, live event");
        assertEquals("Running", mine.getArray("events").getObject(0).getString("name"));
        assertEquals(1, mine.getArray("open").length(), "an open event staff took down isn't offered");
        assertEquals("Open", mine.getArray("open").getObject(0).getString("name"));
    }

    @Test
    void aServerWithNoClanOnTheHubHasNoEvents() {
        when(events.mine(anyLong(), anyLong(), anyInt())).thenThrow(new EventProblem(404, HubEvents.NO_CLAN_TEXT));
        DataObject mine = api.mine(guild, INSIDE);
        assertEquals(0, mine.getArray("events").length());
        assertEquals(0, mine.getArray("open").length());
    }

    @Test
    void aClosedSignupSaysSo() {
        Event listed = onHub(11, "Closing", Status.SIGNUPS, "{}");
        when(events.events(eq(G), eq(List.of(Status.SIGNUPS)), anyInt())).thenReturn(List.of(listed));
        when(events.fullWithSetup(listed)).thenReturn(Optional.of(new HubEvents.Full(event(11, "Closing", Status.SIGNUPS, "{}"),
                new EventRepository.Setup(Instant.EPOCH, 9L, null, null, null, null, null, null, false, "", null, null))));
        assertTrue(api.mine(guild, INSIDE).getArray("open").getObject(0).getBoolean("closed"), "from the same read as the settings");
        verify(events, never()).setup(anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOfflineMemberTheBotHasNotCachedCanStillOpenTheEventPage() {
        long offline = 598L;
        com.runeclans.bot.discord.DiscordLookups.forgetAll();
        try {
            net.dv8tion.jda.api.requests.restaction.CacheRestAction<Member> asked = Mockito.mock(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class);
            Member found = Mockito.mock(Member.class);
            net.dv8tion.jda.api.entities.User user = Mockito.mock(net.dv8tion.jda.api.entities.User.class);
            when(user.getIdLong()).thenReturn(offline);
            when(found.getUser()).thenReturn(user);
            when(asked.submit()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(found));
            when(guild.retrieveMemberById(offline)).thenReturn(asked);
            onHub(12, "Open to read", Status.SIGNUPS, "{}");
            assertEquals("Open to read", api.detail(guild, offline, 12).getObject("event").getString("name"), "asked of Discord, not 'not in the server'");
        } finally {
            com.runeclans.bot.discord.DiscordLookups.forgetAll();
        }
    }

    @Test
    void anEventTakingSignupsCanBeReadByAMemberWhoHasntJoinedButARunningOneCannot() {
        onHub(12, "Open to read", Status.SIGNUPS, "{}");
        DataObject page = api.detail(guild, INSIDE, 12);
        assertEquals("Open to read", page.getObject("event").getString("name"));
        assertTrue(page.isNull("me"), "not in it yet");
        assertFalse(page.hasKey("standings"), "nothing of the players is shown to an outsider");

        onHub(13, "Running", Status.RUNNING, "{}");
        assertEquals(404, assertThrows(EventProblem.class, () -> api.detail(guild, INSIDE, 13)).status());
        assertEquals(403, assertThrows(EventProblem.class, () -> api.detail(guild, OUTSIDE, 13)).status(), "not in the Discord server");
    }

    @Test
    void aPlayerSeesTheirVerificationTheirOwnSubmissionsAndTheStandings() {
        onHub(14, "Mine", Status.RUNNING, VERIFY);
        when(events.participant(14, INSIDE)).thenReturn(Optional.of(player(14, INSIDE, EventRoster.Verification.PENDING, "")));
        when(events.participants(14)).thenReturn(List.of(player(14, INSIDE, EventRoster.Verification.PENDING, ""), player(14, 777, EventRoster.Verification.VERIFIED, "")));
        when(events.objectives(14)).thenReturn(List.of(new EventRules.ObjectiveSpec("anything", "Anything", "", 1, EventRules.Evidence.EITHER, 1, 1, List.of())));
        when(events.standings(G, 14)).thenReturn(List.of(new Standing("u:777", 2, 1, Map.of("anything", 2), null), new Standing("u:" + INSIDE, 1, 1, Map.of("anything", 1), null)));
        when(events.claimsOf(G, 14, INSIDE, 20)).thenReturn(List.of(new Claim(5, 14, null, INSIDE, 1, "my bank", null, ClaimStatus.PENDING, null, null, null, Instant.EPOCH, null,
                ClaimKind.VERIFICATION, null, null)));

        DataObject page = api.detail(guild, INSIDE, 14);
        assertTrue(page.getObject("me").getObject("verification").getBoolean("waitingProof"));
        assertEquals("PENDING", page.getObject("me").getObject("verification").getString("state"));
        assertEquals("Send your bank", page.getObject("event").getString("verificationInstructions"));
        assertEquals(1, page.getArray("submissions").length());
        assertEquals("Verification", page.getArray("submissions").getObject(0).getString("label"));
        assertEquals(1, page.getArray("objectives").getObject(0).getInt("done"));
        assertEquals(2, page.getObject("standings").getInt("of"));
        assertEquals(2, page.getObject("standings").getInt("myRank"));
        assertTrue(page.getBoolean("canSubmit"));
        assertEquals(0, page.getArray("channels").length(), "no channels were set up");
        assertTrue(page.isNull("board"), "a custom event has no board");
    }
}
