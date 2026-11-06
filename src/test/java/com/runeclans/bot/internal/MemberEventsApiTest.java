package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.events.CustomTemplate;
import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.events.EventKinds;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventTemplates;
import com.runeclans.bot.events.TeamRules;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a member does with events from the website: join and leave by the same rules as {@code /events join}, and only from inside the server's
 * Discord (a member who isn't in it is told to join first, with the clan's link when it has one). Runs over the developer's local Postgres
 * (skipped without one).
 */
class MemberEventsApiTest {
    private static final long G = 999_000_000_000_000_032L;
    private static final long INSIDE = 501L;
    private static final long OUTSIDE = 599L;
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;
    private ClanProfileRepository profiles;
    private com.runeclans.bot.configure.GuildSettingsService guildSettings;
    private MemberEventsApi api;
    private Guild guild;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_CLAN;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        guildSettings = Mockito.mock(com.runeclans.bot.configure.GuildSettingsService.class);
        Mockito.when(guildSettings.getEffective(G)).thenReturn(new com.runeclans.bot.configure.GuildSettings(G, null, null, null, null, null, null, null, null, false, null, null, null));
        profiles = Mockito.mock(ClanProfileRepository.class);
        Mockito.when(profiles.get(G)).thenReturn(Optional.empty());
        api = new MemberEventsApi(service, repository, profiles, new com.runeclans.bot.events.EventSpaceRepository(supplier, new com.runeclans.bot.hubevents.HubEventMap(supplier)),
                new com.runeclans.bot.clanprofile.JoinLinks(guildSettings, Mockito.mock(com.runeclans.bot.clanprofile.ClanRecruitingRepository.class, Mockito.RETURNS_DEEP_STUBS)),
                new com.runeclans.bot.events.EventUpdateRepository(supplier));
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Mockito.when(guild.getName()).thenReturn("Younglings");
        Mockito.when(guild.getMemberById(INSIDE)).thenReturn(Mockito.mock(Member.class)); // anyone else isn't in the server
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private long signupsOpen(String name, String config) {
        long id = service.createEvent(G, 9L, new EventService.EventInput(name, "", "custom", EventRules.Scope.PRIVATE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0, DataObject.fromJson(config)));
        service.setObjectives(G, id, List.of(new EventRules.ObjectiveSpec("anything", "Anything", "", 1, EventRules.Evidence.EITHER, 1, 1, List.of())));
        service.moveStage(G, id, EventRules.Status.SIGNUPS);
        return id;
    }

    private static DataObject body(long eventId) {
        return DataObject.empty().put("eventId", Long.toString(eventId));
    }

    @Test
    void aMemberOutsideTheServerIsToldToJoinItAndGivenTheClansLinkWhenItHasOne() {
        long id = signupsOpen("Join me", "{}");
        EventProblem plain = assertThrows(EventProblem.class, () -> api.join(guild, OUTSIDE, body(id)));
        assertEquals(403, plain.status());
        assertTrue(plain.getMessage().contains("Younglings Discord server"), plain.getMessage());
        assertFalse(plain.getMessage().contains("http"), "no link to give");

        ClanProfile profile = Mockito.mock(ClanProfile.class);
        Mockito.when(profile.applyUrl()).thenReturn("https://discord.gg/example");
        Mockito.when(profiles.get(G)).thenReturn(Optional.of(profile));
        EventProblem withLink = assertThrows(EventProblem.class, () -> api.join(guild, OUTSIDE, body(id)));
        assertTrue(withLink.getMessage().contains("https://discord.gg/example"), withLink.getMessage());
        assertTrue(repository.participants(id).isEmpty(), "nothing was added");
    }

    @Test
    void theServersInviteLinkIsUsedBeforeTheApplyLink() {
        ClanProfile profile = Mockito.mock(ClanProfile.class);
        Mockito.when(profile.applyUrl()).thenReturn("https://discord.gg/apply");
        Mockito.when(profiles.get(G)).thenReturn(Optional.of(profile));
        Mockito.when(guildSettings.getEffective(G)).thenReturn(new com.runeclans.bot.configure.GuildSettings(G, null, null, null, null, null, null, null, null, false, null, null, null, null, "https://discord.gg/invite1"));

        String message = api.notInServerMessage(guild);

        assertTrue(message.contains("https://discord.gg/invite1"), message);
        assertFalse(message.contains("apply"), message);
    }

    @Test
    void aMemberJoinsAndLeavesWhileSignupsAreOpenByTheSameRulesAsTheCommand() {
        long id = signupsOpen("Join and leave", "{}");
        DataObject joined = api.join(guild, INSIDE, body(id));
        assertFalse(joined.getObject("result").getBoolean("waiting"));
        assertEquals(1, joined.getArray("events").length(), "their events now list it");
        assertEquals(0, joined.getArray("open").length(), "and it is no longer offered to them");
        assertEquals(409, assertThrows(EventProblem.class, () -> api.join(guild, INSIDE, body(id))).status(), "already in");

        DataObject left = api.leave(guild, INSIDE, body(id));
        assertEquals(0, left.getArray("events").length());
        assertEquals(1, left.getArray("open").length());
        assertEquals(404, assertThrows(EventProblem.class, () -> api.leave(guild, INSIDE, body(id))).status(), "they aren't in it any more");
        assertEquals(400, assertThrows(EventProblem.class, () -> api.join(guild, INSIDE, DataObject.empty())).status(), "say which event");
    }

    @Test
    void aClosedSignupRefusesTheMembersOwnJoinAndTheOpenListSaysSo() {
        long id = signupsOpen("Closing", "{}");
        assertFalse(api.mine(guild, INSIDE).getArray("open").getObject(0).getBoolean("closed"));
        service.closeSignups(G, id, 9L);
        assertTrue(api.mine(guild, INSIDE).getArray("open").getObject(0).getBoolean("closed"));
        EventProblem refused = assertThrows(EventProblem.class, () -> api.join(guild, INSIDE, body(id)));
        assertEquals(409, refused.status());
        assertTrue(refused.getMessage().contains("Signups have closed"), refused.getMessage());
    }

    @Test
    void aMemberCanOnlyLeaveWhileTheEventIsTakingSignups() {
        long id = signupsOpen("Leave early", "{}");
        api.join(guild, INSIDE, body(id));
        service.moveStage(G, id, EventRules.Status.RUNNING);
        EventProblem refused = assertThrows(EventProblem.class, () -> api.leave(guild, INSIDE, body(id)));
        assertEquals(409, refused.status());
        assertTrue(refused.getMessage().contains("taking signups"), refused.getMessage());
    }

    @Test
    void aMemberSeesWhereTheirVerificationStandsAndWhyIfTurnedDown() {
        long id = signupsOpen("Verified", "{\"verification\":{\"required\":true,\"gate\":\"TEAMS\",\"instructions\":\"Send a screenshot of your bank.\"}}");
        assertTrue(api.mine(guild, INSIDE).getArray("open").getObject(0).getBoolean("verification"), "players see it is asked for before joining");
        DataObject joined = api.join(guild, INSIDE, body(id));
        DataObject mine = joined.getArray("events").getObject(0).getObject("verification");
        assertTrue(mine.getBoolean("required"));
        assertEquals("PENDING", mine.getString("state"));
        assertEquals("Send a screenshot of your bank.", mine.getString("instructions"));

        service.setVerification(G, id, INSIDE, com.runeclans.bot.events.EventRoster.Verification.REJECTED, 9L, "The bank was cropped");
        DataObject turnedDown = api.mine(guild, INSIDE).getArray("events").getObject(0).getObject("verification");
        assertEquals("REJECTED", turnedDown.getString("state"));
        assertEquals("The bank was cropped", turnedDown.getString("note"));

        service.setVerification(G, id, INSIDE, com.runeclans.bot.events.EventRoster.Verification.VERIFIED, 9L, "");
        assertEquals("", api.mine(guild, INSIDE).getArray("events").getObject(0).getObject("verification").getString("note"), "a reason is only shown while it applies");
    }

    @Test
    void anEventWithoutVerificationShowsNothingToVerify() {
        long id = signupsOpen("Plain", "{}");
        DataObject joined = api.join(guild, INSIDE, body(id));
        DataObject verification = joined.getArray("events").getObject(0).getObject("verification");
        assertFalse(verification.getBoolean("required"));
        assertEquals("NOT_REQUIRED", verification.getString("state"));
        assertEquals("", verification.getString("instructions"));
    }

    // ---------- the event page (GET events/detail) ----------

    private static final String VERIFY = "{\"verification\":{\"required\":true,\"gate\":\"TEAMS\",\"instructions\":\"Send your bank\"}}";

    @Test
    void anEventTakingSignupsCanBeReadByAMemberWhoHasntJoinedButARunningOneCannot() {
        long id = signupsOpen("Open to read", "{}");
        DataObject page = api.detail(guild, INSIDE, id);
        assertEquals("Open to read", page.getObject("event").getString("name"));
        assertTrue(page.isNull("me"), "not in it yet");
        assertFalse(page.hasKey("standings"), "nothing of the players is shown to an outsider");

        service.join(G, id, G, 777, null, true, null);
        service.moveStage(G, id, EventRules.Status.RUNNING);
        EventProblem hidden = assertThrows(EventProblem.class, () -> api.detail(guild, INSIDE, id));
        assertEquals(404, hidden.status());
        assertEquals(403, assertThrows(EventProblem.class, () -> api.detail(guild, OUTSIDE, id)).status(), "not in the Discord server");
        assertEquals(404, assertThrows(EventProblem.class, () -> api.detail(guild, INSIDE, id + 100000)).status(), "no such event");
    }

    @Test
    void aPlayerSeesTheirVerificationTheirOwnSubmissionsAndTheStandings() {
        long id = signupsOpen("Mine", VERIFY);
        service.join(G, id, G, INSIDE, null, true, null);
        service.join(G, id, G, 777, null, true, null);
        long proof = service.submitVerification(G, id, INSIDE, "my bank", "https://cdn.discordapp.com/x.png");
        DataObject waiting = api.detail(guild, INSIDE, id);
        assertTrue(waiting.getObject("me").getObject("verification").getBoolean("waitingProof"));
        assertEquals("PENDING", waiting.getObject("me").getObject("verification").getString("state"));
        assertEquals("Send your bank", waiting.getObject("event").getString("verificationInstructions"));
        assertEquals(1, waiting.getArray("submissions").length());
        assertEquals("Verification", waiting.getArray("submissions").getObject(0).getString("label"));

        service.decideClaim(G, id, proof, true, "", 9L);
        service.moveStage(G, id, EventRules.Status.RUNNING);
        service.recordProgress(G, id, "anything", INSIDE, 1, EventRules.Source.MANUAL, null, 9L, "test");
        service.recordProgress(G, id, "anything", 777, 1, EventRules.Source.MANUAL, null, 9L, "test");
        DataObject page = api.detail(guild, INSIDE, id);
        assertEquals("VERIFIED", page.getObject("me").getObject("verification").getString("state"));
        assertEquals(1, page.getArray("objectives").length());
        assertEquals(1, page.getArray("objectives").getObject(0).getInt("done"));
        assertEquals(2, page.getObject("standings").getInt("of"));
        assertTrue(page.getObject("standings").getInt("myRank") >= 1);
        assertTrue(page.getBoolean("canSubmit"));
        assertEquals("APPROVED", page.getArray("submissions").getObject(0).getString("status"));
        assertEquals(0, page.getArray("channels").length(), "no channels were set up");
        assertTrue(page.isNull("board"), "a custom event has no board");
        assertFalse(page.hasKey("xpTiles"), "XP tiles aren't counted (off by default): the page is as before");
        assertFalse(page.getObject("me").hasKey("xpNotCounted"));
    }

}
