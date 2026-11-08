package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.internal.RankAdvisorRules.Due;
import com.runeclans.bot.internal.RankAdvisorRules.Hold;
import com.runeclans.bot.internal.RankAdvisorRules.Promoted;
import com.runeclans.bot.internal.RankAdvisorRules.Rule;
import com.runeclans.bot.internal.RankAdvisorRules.Tick;
import com.runeclans.bot.internal.RankAdvisorRules.Turn;
import com.runeclans.bot.runescape.ClanMemberRepository;
import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import com.runeclans.bot.testsupport.LocalDatabase;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The rank-up advisor's tables over a real database (skipped without one), and the roster sync's rank stamp (T-594). */
class SiteRankStoreDatabaseTest {
    private static final long G = Long.parseLong("9".repeat(17) + "4");
    private static final long OWNER = Long.parseLong("5".repeat(18));
    private static ConnectionSupplier supplier;
    private static SiteRankStore store;
    private static com.runeclans.bot.runescape.ClanPointsRepository points;
    private static ClanMemberRepository roster;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        RuneScapeDatabaseInitializer schema = new RuneScapeDatabaseInitializer(supplier);
        points = org.mockito.Mockito.mock(com.runeclans.bot.runescape.ClanPointsRepository.class);
        store = new SiteRankStore(supplier, schema, points);
        roster = new ClanMemberRepository(supplier, schema);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : List.of("clan_member", "clan_member_points", "site_rank_rule", "site_rank_settings", "site_rank_tick", "site_rank_hold",
                "site_rank_due", "site_rank_week", "site_rank_promoted")) {
            sql("DELETE FROM younglings." + table + " WHERE guild_id = " + G);
        }
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            s.executeUpdate();
        }
    }

    private static SiteRankStore.Row only(String rsn) {
        return store.roster(G).stream().filter(m -> m.rsn().equalsIgnoreCase(rsn)).findFirst().orElseThrow();
    }

    @Test
    void xpGainedCountsFromWhenTheSyncSawTheCurrentRank() throws Exception {
        roster.upsert(G, "Zezima", "Recruit", 1_000, 0);
        SiteRankStore.Row first = only("Zezima");
        assertEquals(0L, first.xpGained(), "a new member starts at nothing gained");
        assertNotNull(first.rankSince());

        roster.upsert(G, "Zezima", "Recruit", 4_000, 0);
        assertEquals(3_000L, only("Zezima").xpGained(), "the same rank keeps counting");

        roster.upsert(G, "Zezima", "Corporal", 5_000, 0);
        assertEquals(0L, only("Zezima").xpGained(), "a new rank starts again");

        sql("UPDATE younglings.clan_member SET rank_since = NULL, rank_xp = NULL WHERE guild_id = " + G);
        assertNull(only("Zezima").xpGained(), "a member from before the column has no starting point");
        roster.upsert(G, "Zezima", "Corporal", 6_000, 0);
        assertEquals(0L, only("Zezima").xpGained(), "the next sync is the starting point");
    }

    @Test
    void pointsAndTheStaffJoinDateAreRead() throws Exception {
        roster.upsert(G, "Zezima", "Recruit", 1_000, 0);
        // the points are the hub's (T-978)
        org.mockito.Mockito.when(points.totals(G)).thenReturn(java.util.Map.of("zezima",
                new com.runeclans.bot.runescape.ClanPointsRepository.MemberPointsRow("Zezima", 42, false, null)));
        sql("UPDATE younglings.clan_member SET clan_joined_at = DATE '2020-01-02' WHERE guild_id = " + G);
        SiteRankStore.Row row = only("Zezima");
        assertEquals(42, row.points());
        assertEquals(2020, row.joined().getYear());
    }

    @Test
    void rulesAreReplacedAsAWholeAndReadInLadderOrder() {
        store.saveRules(G, List.of(new Rule("Sergeant", null, 30, null, null), new Rule("Corporal", 1_000L, null, 2, 5L),
                new Rule("Captain", null, null, null, null)), OWNER);
        Map<String, Rule> rules = store.rules(G);
        assertEquals(List.of("Corporal", "Sergeant"), List.copyOf(rules.keySet()), "an empty rule isn't kept");
        assertEquals(new Rule("Corporal", 1_000L, null, 2, 5L), rules.get("Corporal"));

        store.saveRules(G, List.of(new Rule("General", null, 90, null, null)), OWNER);
        assertEquals(List.of("General"), List.copyOf(store.rules(G).keySet()));

        store.saveRules(G, List.of(new Rule("Captain", null, null, null, null, "Master CAs")), OWNER);
        assertEquals(new Rule("Captain", null, null, null, null, "Master CAs"), store.rules(G).get("Captain"), "a staff check alone is kept");
        assertTrue(store.serversWithRules().contains(G));
    }

    @Test
    void holdsMoveBetweenPendingAndIgnoredAndIgnoringDropsTheDueEntry() {
        LocalDate week = LocalDate.of(2026, 10, 28);
        store.noteDue(G, List.of(new Due("zezima", "Zezima", "Recruit", "Corporal", week)));
        store.hold(G, "zezima", "Zezima", RankAdvisorRules.PENDING, OWNER, "Owner name");
        assertEquals(List.of("pending"), store.holds(G).stream().map(Hold::kind).toList());
        assertEquals(1, store.due(G).size(), "pending review keeps them due");
        store.hold(G, "zezima", "Zezima", RankAdvisorRules.IGNORED, OWNER, null);
        assertEquals(List.of("ignored"), store.holds(G).stream().map(Hold::kind).toList(), "one hold per member");
        assertTrue(store.due(G).isEmpty(), "an ignored alt is never congratulated");
        store.hold(G, "zezima", "Zezima", null, OWNER, null);
        assertTrue(store.holds(G).isEmpty());
    }

    @Test
    void theWeekTurnsOnceAndIsPostedOnce() {
        LocalDate week = LocalDate.of(2026, 10, 28);
        store.noteDue(G, List.of(new Due("zezima", "Zezima", "Recruit", "Corporal", week.minusWeeks(1))));
        store.noteDue(G, List.of(new Due("zezima", "Zezima", "Recruit", "Corporal", week)));
        assertEquals(week.minusWeeks(1), store.due(G).getFirst().since(), "someone already due keeps their first week");
        assertNull(store.latestWeek(G));
        assertFalse(store.turned(G, week));
        Turn turn = new Turn(List.of(new Promoted("zezima", "Zezima", "Recruit", "Corporal", "Zez")), store.due(G));
        assertTrue(store.turn(G, week, turn));
        assertFalse(store.turn(G, week, turn), "a second turn of the same week writes nothing");
        assertTrue(store.turned(G, week));
        assertTrue(store.due(G).isEmpty());
        SiteRankStore.Week w = store.latestWeek(G);
        assertEquals(week, w.start());
        assertEquals(List.of("Zezima"), w.promoted().stream().map(Promoted::rsn).toList());
        assertEquals("Zez", w.promoted().getFirst().discordName());
        assertNull(w.postedAt());
        assertTrue(store.claimPost(G, week));
        assertFalse(store.claimPost(G, week), "never posted twice");
        store.saveJob(G, week, 42L);
        assertEquals(42L, store.latestWeek(G).jobId());
        store.forget(G);
        assertNull(store.latestWeek(G));
    }

    /** The week's auto post through {@link SiteRankAdvisor#post}: the outbox and Discord are mocks, the claim is the real table's. */
    private record PostSetup(SiteRankAdvisor advisor, DiscordOutbox outbox, Guild guild, SiteRankAdvisor.State state) {}

    private static PostSetup postSetup(boolean live) {
        LocalDate week = RankAdvisorRules.weekOf(OffsetDateTime.now(ZoneOffset.UTC));
        long channelId = Long.parseLong("3".repeat(18));
        store.saveCongrats(G, new SiteRankStore.Congrats(true, channelId, null), OWNER);
        store.turn(G, week, new Turn(List.of(new Promoted("e", "E", "Recruit", "Corporal", "everyone")), List.of()));
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        when(outbox.enqueue(anyString(), anyLong(), anyLong(), anyString(), any())).thenReturn(
                new DiscordJob(7L, RankCongratsJob.KIND, G, 0L, null, DataObject.empty(), DiscordJob.State.QUEUED, 0, 0, null, null, null, null, null));
        TrackingPostingToggle posting = mock(TrackingPostingToggle.class);
        when(posting.isEnabled()).thenReturn(live);
        Guild guild = mock(Guild.class);
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(guild.getChannelById(GuildMessageChannel.class, channelId)).thenReturn(channel);
        when(channel.canTalk()).thenReturn(true);
        when(channel.getIdLong()).thenReturn(channelId);
        SiteRankAdvisor advisor = new SiteRankAdvisor(store, null, null, null, null, outbox, posting);
        SiteRankAdvisor.State state = new SiteRankAdvisor.State(Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), week,
                store.latestWeek(G));
        return new PostSetup(advisor, outbox, guild, state);
    }

    @Test
    void aDevBotNeverClaimsOrQueuesTheWeeklyPost() {
        PostSetup s = postSetup(false);
        assertFalse(s.advisor().post(s.guild(), G, s.state(), OffsetDateTime.now(ZoneOffset.UTC)));
        verify(s.outbox(), never()).enqueue(anyString(), anyLong(), anyLong(), anyString(), any());
        assertNull(store.latestWeek(G).postedAt(), "the live bot can still post the week");
    }

    @Test
    void theWeeklyPostIsQueuedOnceWithoutPings() {
        PostSetup s = postSetup(true);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertTrue(s.advisor().post(s.guild(), G, s.state(), now));
        assertFalse(s.advisor().post(s.guild(), G, s.state(), now), "a second run with the same (stale) view sends nothing: the claim stops it");
        org.mockito.ArgumentCaptor<DataObject> payload = org.mockito.ArgumentCaptor.forClass(DataObject.class);
        verify(s.outbox(), times(1)).enqueue(eq(RankCongratsJob.KIND), eq(G), eq(0L), eq("rankweek:" + G + ":" + s.state().week()), payload.capture());
        assertFalse(payload.getValue().getString("text").contains("@everyone"), payload.getValue().getString("text"));
        assertNotNull(store.latestWeek(G).postedAt());
        assertEquals(7L, store.latestWeek(G).jobId());
    }

    private static SiteRankAdvisor advisorWithMocks() {
        return new SiteRankAdvisor(store, mock(SiteCockpitStore.class), mock(SiteStatsRepository.class), mock(AdminToolsStore.class),
                mock(com.runeclans.bot.clansite.SiteRoleGuard.class), mock(DiscordOutbox.class), mock(TrackingPostingToggle.class));
    }

    @Test
    void loadingThePageWritesNothing() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        LocalDate week = RankAdvisorRules.weekOf(now);
        roster.upsert(G, "Zezima", "Corporal", 1_000, 0);
        store.saveRules(G, List.of(new Rule("Sergeant", null, 30, null, null)), OWNER);
        store.noteDue(G, List.of(new Due("zezima", "Zezima", "Recruit", "Corporal", week.minusWeeks(1))));
        SiteCaller page = new SiteCaller(G, OWNER, null, null, null, null);
        assertNotNull(advisorWithMocks().view(page, now));
        assertFalse(store.turned(G, week), "only the sweep turns the week");
        assertEquals(1, store.due(G).size(), "the page notes nobody due");
        assertNull(store.latestWeek(G));
    }

    @Test
    void anEmptyRosterNeverTurnsTheWeekButTheNextSweepDoes() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        LocalDate week = RankAdvisorRules.weekOf(now);
        store.saveRules(G, List.of(new Rule("Sergeant", null, 30, null, null)), OWNER);
        store.noteDue(G, List.of(new Due("zezima", "Zezima", "Recruit", "Corporal", week.minusWeeks(1))));
        SiteRankAdvisor advisor = advisorWithMocks();
        advisor.sweep(null, now);
        assertFalse(store.turned(G, week), "a roster sync outage claims nothing");
        assertEquals(1, store.due(G).size());

        roster.upsert(G, "Zezima", "Corporal", 1_000, 0);
        advisor.sweep(null, now);
        assertTrue(store.turned(G, week));
        assertEquals(List.of("Zezima"), store.latestWeek(G).promoted().stream().map(Promoted::rsn).toList());
    }

    @Test
    void theCongratsPostIsOffUntilSaved() {
        assertFalse(store.congrats(G).on());
        assertNull(store.congrats(G).channelId());
        long channel = Long.parseLong("3".repeat(18));
        store.saveCongrats(G, new SiteRankStore.Congrats(true, channel, "Well done {name}"), OWNER);
        assertTrue(store.congrats(G).on());
        assertEquals(channel, store.congrats(G).channelId());
        assertEquals("Well done {name}", store.congrats(G).text());
    }

    @Test
    void ticksAreKeptPerRankAndCanBeTakenBack() {
        assertTrue(store.tick(G, "zezima", "Corporal", OWNER, "Owner name"), "a first tick is new");
        assertFalse(store.tick(G, "zezima", "Corporal", OWNER, "Owner name"), "a repeated tick isn't, so no second congrats post");
        assertTrue(store.tick(G, "zezima", "Sergeant", OWNER, null));
        List<Tick> ticks = store.ticks(G);
        assertEquals(2, ticks.size());
        store.untick(G, "zezima", "Corporal");
        assertEquals(List.of("Sergeant"), store.ticks(G).stream().map(Tick::rank).toList());
        store.forget(G);
        assertTrue(store.ticks(G).isEmpty());
        assertTrue(store.rules(G).isEmpty());
    }
}
