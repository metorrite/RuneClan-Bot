package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.commands.poll.PollSession;
import com.runeclans.bot.commands.signup.SignupEntry;
import com.runeclans.bot.commands.signup.SignupType;
import com.runeclans.bot.poll.PollStore;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot's hub-backed poll and signup stores and the feed planner, against a scripted hub and the local database (the bot's id maps). */
class HubCommunityStoresDatabaseTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ALICE = Long.parseLong("5".repeat(18));
    private static final long BOB = Long.parseLong("4".repeat(18));
    private static final long CHANNEL = Long.parseLong("3".repeat(18));

    private ConnectionSupplier db;
    private HubCommunityMaps maps;
    private FakeCommunityHub fake;
    private HubPollStore polls;
    private HubSignupStore signups;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        maps = new HubCommunityMaps(db);
        clean();
        fake = new FakeCommunityHub();
        HubCommunityClient client = new HubCommunityClient(fake);
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);
        polls = new HubPollStore(client, maps, clock);
        signups = new HubSignupStore(client, maps, clock);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            for (String table : List.of("hub_poll_map", "hub_signup_map", "hub_group_map", "hub_form_map")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                    s.setLong(1, GUILD);
                    s.executeUpdate();
                }
            }
        }
    }

    private static DataObject poll(UUID id, String status, String... optionIds) {
        DataArray options = DataArray.empty();
        for (int i = 0; i < optionIds.length; i++) {
            options.add(DataObject.empty().put("id", optionIds[i]).put("position", i + 1).put("label", "Option " + (i + 1)).put("votes", i));
        }
        return DataObject.empty().put("id", id.toString()).put("title", "Boss night?").put("status", status).put("anonymous", true)
                .put("multipleVotes", true).put("options", options).put("createdByDiscordId", Long.toString(ALICE));
    }

    // ---------- polls ----------

    @Test
    void aPollIsMadeOnTheHubAndGetsALocalIdAboveTheBotsOwn() {
        UUID hubId = UUID.randomUUID();
        fake.ok("POST /v1/clans/" + GUILD + "/polls", poll(hubId, "OPEN", "a", "b"));
        PollStore.Created made = polls.create(GUILD, CHANNEL, "Boss night?", true, true, List.of("Option 1", "Option 2"), ALICE, null);

        assertTrue(made.pollId() >= HubCommunityMaps.FIRST_LOCAL_ID);
        assertEquals(2, made.options().size());
        assertEquals(1, made.options().getFirst().optionNumber());
        DataObject sent = DataObject.fromJson(fake.calls.getFirst().body());
        assertEquals(Long.toString(ALICE), sent.getString("byDiscordId"));
        assertEquals(2, sent.getArray("options").length());
        assertEquals(Optional.of(hubId), maps.pollHubId(made.pollId()));
        assertEquals(CHANNEL, maps.poll(made.pollId()).orElseThrow().channelId());

        // the session comes from the answer just kept: no second read
        PollSession session = polls.getPollById(made.pollId());
        assertEquals("ACTIVE", session.status());
        assertEquals(ALICE, session.createdByUserId());
        assertEquals(1, fake.calls.size());
    }

    @Test
    void aVoteSendsAllTheMembersPicksAndAnEmptySetRemovesThem() {
        UUID hubId = UUID.randomUUID();
        fake.ok("POST /v1/clans/" + GUILD + "/polls", poll(hubId, "OPEN", "opt-a", "opt-b"))
                .ok("GET /v1/polls/" + hubId + "?playerId=", poll(hubId, "OPEN", "opt-a", "opt-b").put("myVotes", DataArray.empty().add("opt-b")))
                .ok("POST /v1/polls/" + hubId + "/votes", poll(hubId, "OPEN", "opt-a", "opt-b"))
                .ok("DELETE /v1/polls/" + hubId + "/votes", DataObject.empty());
        long local = polls.create(GUILD, CHANNEL, "t", false, true, List.of("A", "B"), ALICE, null).pollId();

        assertEquals(Set.of(2L), polls.getUserVotes(local, BOB));
        assertTrue(polls.addVote(local, 1, BOB));
        FakeCommunityHub.Call vote = fake.calls("POST", "/v1/polls/" + hubId + "/votes").getFirst();
        DataObject body = DataObject.fromJson(vote.body());
        assertEquals(Long.toString(BOB), body.getString("discordUserId"));
        assertEquals(2, body.getArray("optionIds").length());
        assertEquals("opt-b", body.getArray("optionIds").getString(0));
        assertEquals("opt-a", body.getArray("optionIds").getString(1));

        polls.removeAllVotesForUser(local, BOB);
        assertEquals("/v1/polls/" + hubId + "/votes?playerId=" + BOB, fake.calls("DELETE", "/v1/polls/").getFirst().path());
    }

    @Test
    void aClosedOrFullRefusalReachesTheMemberInTheHubsWords() {
        UUID hubId = UUID.randomUUID();
        fake.ok("POST /v1/clans/" + GUILD + "/polls", poll(hubId, "OPEN", "a"))
                .answer("POST /v1/polls/" + hubId + "/votes", 409, DataObject.empty().put("error", "closed").put("message", "This poll is closed."));
        long local = polls.create(GUILD, CHANNEL, "t", false, false, List.of("A"), ALICE, null).pollId();
        HubCommunityProblem p = assertThrows(HubCommunityProblem.class, () -> polls.setVotes(local, BOB, Set.of(1L)));
        assertEquals("This poll is closed.", p.getMessage());
        HubCommunityProblem unknown = assertThrows(HubCommunityProblem.class, () -> polls.setVotes(local, BOB, Set.of(9L)));
        assertEquals(400, unknown.status());
    }

    @Test
    void aVoteWhileTheHubIsBusyIsHeldNotLost() {
        UUID hubId = UUID.randomUUID();
        fake.ok("POST /v1/clans/" + GUILD + "/polls", poll(hubId, "OPEN", "a"));
        long local = polls.create(GUILD, CHANNEL, "t", false, false, List.of("A"), ALICE, null).pollId();
        fake.holdWrites = true;
        HubCommunityProblem p = assertThrows(HubCommunityProblem.class, () -> polls.setVotes(local, BOB, Set.of(1L)));
        assertEquals(HubCommunityProblem.Kind.QUEUED, p.kind());
        assertTrue(fake.calls("POST", "/v1/polls/" + hubId + "/votes").getFirst().queued());
    }

    @Test
    void aPollWithNoHubClanListsNothing() {
        assertTrue(polls.getActivePolls(GUILD).isEmpty(), "a 404 for the server's clan is an empty list");
        assertNull(polls.getPollById(HubCommunityMaps.FIRST_LOCAL_ID - 1));
    }

    // ---------- signups ----------

    private static DataObject sheet(UUID id, String status, String... discordIds) {
        DataArray entries = DataArray.empty();
        for (String d : discordIds) entries.add(DataObject.empty().put("discordId", d));
        return DataObject.empty().put("id", id.toString()).put("title", "Raids").put("kind", "QUEUE").put("status", status).put("entries", entries)
                .put("seats", DataObject.empty().put("taken", discordIds.length)).put("rev", 7);
    }

    private long makeSheet(UUID hubId) {
        fake.ok("POST /v1/clans/" + GUILD + "/signups", sheet(hubId, "ACTIVE"));
        return signups.createSignup(GUILD, "Raids", SignupType.QUEUE, null, null, null, ALICE, null);
    }

    @Test
    void aQueueEntrySendsTheRsnOnlyWhenItIsOneAndKeepsTheShownName() {
        UUID hubId = UUID.randomUUID();
        long local = makeSheet(hubId);
        fake.ok("POST /v1/signups/" + hubId + "/entries", DataObject.empty())
                .ok("GET /v1/signups/" + hubId, sheet(hubId, "ACTIVE", Long.toString(BOB), Long.toString(ALICE)));

        assertTrue(signups.addEntry(local, BOB, "Bob The Builder Jr", null, BOB, SignupType.QUEUE));
        assertFalse(DataObject.fromJson(fake.calls("POST", "/v1/signups/").getLast().body()).hasKey("rsn"), "too long for an RSN: kept in the bot only");
        assertTrue(signups.addEntry(local, ALICE, "Alice", null, ALICE, SignupType.QUEUE));
        assertEquals("Alice", DataObject.fromJson(fake.calls("POST", "/v1/signups/").getLast().body()).getString("rsn"));

        List<SignupEntry> entries = signups.getEntries(local);
        assertEquals(List.of("Bob The Builder Jr", "Alice"), entries.stream().map(SignupEntry::username).toList());
    }

    @Test
    void anEntryTheHubTurnsDownIsFalseNotAnError() {
        UUID hubId = UUID.randomUUID();
        long local = makeSheet(hubId);
        fake.answer("POST /v1/signups/" + hubId + "/entries", 409, DataObject.empty().put("error", "already_in").put("message", "You're already in."));
        assertFalse(signups.addEntry(local, BOB, "Bob", null, BOB, SignupType.QUEUE));
        fake.answer("POST /v1/signups/" + hubId + "/entries", 404, DataObject.empty().put("error", "not_found").put("message", "Gone."));
        assertThrows(HubCommunityProblem.class, () -> signups.addEntry(local, BOB, "Bob", null, BOB, SignupType.QUEUE));
    }

    @Test
    void skipSwapsOnTheHubAndNeedsTwoPeople() {
        UUID hubId = UUID.randomUUID();
        long local = makeSheet(hubId);
        fake.ok("GET /v1/signups/" + hubId, sheet(hubId, "ACTIVE", Long.toString(BOB))).ok("POST /v1/signups/" + hubId + "/next", DataObject.empty());
        signups.forget(hubId);
        assertNull(signups.skipFirst(local));
        assertTrue(fake.calls("POST", "/v1/signups/" + hubId + "/next").isEmpty());

        fake.ok("GET /v1/signups/" + hubId, sheet(hubId, "ACTIVE", Long.toString(BOB), Long.toString(ALICE)));
        signups.forget(hubId);
        signups.skipFirst(local);
        FakeCommunityHub.Call skip = fake.calls("POST", "/v1/signups/" + hubId + "/next").getFirst();
        assertTrue(DataObject.fromJson(skip.body()).getBoolean("skip"));
    }

    @Test
    void pausingASheetClosesItOnTheHubWithItsRev() {
        UUID hubId = UUID.randomUUID();
        long local = makeSheet(hubId);
        fake.ok("GET /v1/signups/" + hubId, sheet(hubId, "ACTIVE")).ok("PATCH /v1/signups/" + hubId, sheet(hubId, "CLOSED"));
        signups.setSignupStatus(local, "PAUSED");
        FakeCommunityHub.Call patch = fake.calls("PATCH", "/v1/signups/").getFirst();
        assertEquals("CLOSED", DataObject.fromJson(patch.body()).getString("status"));
        assertEquals("7", patch.headers().get("If-Match"));
        assertEquals("PAUSED", signups.getSignupStatus(local));
    }

    // ---------- the feed ----------

    @Test
    void feedEntriesBecomeOneDiscordJobEachAndUnshownItemsAreSkipped() {
        HubCommunityFeed feed = new HubCommunityFeed(HubCommunity.on(), maps, polls, signups, null);
        UUID shown = UUID.randomUUID();
        fake.ok("POST /v1/clans/" + GUILD + "/polls", poll(shown, "OPEN", "a"));
        long local = polls.create(GUILD, CHANNEL, "t", false, false, List.of("A"), ALICE, null).pollId();

        assertTrue(feed.plan("poll.voted", DataObject.empty().put("id", shown.toString())).isEmpty(), "no Discord message yet: nothing to refresh");
        polls.saveMessageId(local, CHANNEL + 1);
        HubCommunityFeed.Job vote = feed.plan("poll.voted", DataObject.empty().put("id", shown.toString())).orElseThrow();
        assertEquals(HubCommunityJobs.REFRESH, vote.kind());
        assertEquals(GUILD, vote.guildId());
        assertEquals("hubcommunity:poll:" + local, vote.dedupe());
        HubCommunityFeed.Job closed = feed.plan("poll.closed", DataObject.empty().put("id", shown.toString())).orElseThrow();
        assertTrue(closed.payload().getBoolean("ended"));
        assertTrue(feed.plan("poll.voted", DataObject.empty().put("id", UUID.randomUUID().toString())).isEmpty(), "a website-only poll");

        UUID group = UUID.randomUUID();
        DataObject inner = DataObject.empty().put("guildId", Long.toString(GUILD));
        assertEquals(HubCommunityJobs.GROUP_POST, feed.plan("group.created", DataObject.empty().put("id", group.toString()).put("item", "group").put("data", inner)).orElseThrow().kind());
        assertEquals(HubCommunityJobs.GROUP_SYNC, feed.plan("group.joined", DataObject.empty().put("id", group.toString()).put("item", "group").put("data", inner)).orElseThrow().kind());
        assertTrue(feed.plan("group.created", DataObject.empty().put("id", group.toString()).put("data", DataObject.empty())).isEmpty(), "a clan with no Discord server");

        UUID form = UUID.randomUUID();
        DataObject submitted = DataObject.empty().put("id", UUID.randomUUID().toString())
                .put("data", DataObject.empty().put("guildId", Long.toString(GUILD)).put("formId", form.toString()).put("discordId", Long.toString(BOB)));
        assertTrue(feed.plan("form.submitted", submitted).isEmpty(), "a form with no Discord settings");
        maps.saveForm(new HubCommunityMaps.FormRow(form, GUILD, null, CHANNEL, null));
        assertEquals(com.runeclans.bot.forms.HubFormJob.KIND, feed.plan("form.submitted", submitted).orElseThrow().kind());
    }

    @Test
    void theFeedDoesNothingWhileTheSwitchIsOff() {
        HubCommunityFeed feed = new HubCommunityFeed(HubCommunity.off(), maps, polls, signups, null);
        feed.onHubFeed("poll.voted", DataObject.empty().put("id", UUID.randomUUID().toString())); // a null outbox would throw if it got this far
        assertTrue(fake.calls.isEmpty());
    }
}
