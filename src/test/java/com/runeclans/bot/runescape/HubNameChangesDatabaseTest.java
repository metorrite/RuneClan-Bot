package com.runeclans.bot.runescape;

import com.runeclans.bot.internal.AdminToolsStore;
import com.runeclans.bot.internal.SiteRankStore;
import com.runeclans.bot.testsupport.TestDatabaseUrl;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-1160: the bot follows the hub's {@code player.renamed} / {@code player.rename_undone} feed entries against a real Postgres. Skipped unless
 * {@code RUNECLAN_BOT_TEST_DATABASE_URL} points at an empty scratch database.
 */
@EnabledIf("com.runeclans.bot.testsupport.TestDatabaseUrl#configured")
class HubNameChangesDatabaseTest {
    private static final long HOME = 1L;
    private static final long ELSEWHERE = 2L;
    private static final long OWNER = 500L;
    private static final String CLAN = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_CLAN = "22222222-2222-2222-2222-222222222222";

    private static ConnectionSupplier connections;
    private static HubNameChangeStore store;
    private static PlayerLinkRepository links;

    private final List<String> discord = new ArrayList<>();
    private HubNameChangeReads reads;
    private HubNameChanges.Follower follower;

    @BeforeAll
    static void connect() {
        String url = TestDatabaseUrl.jdbc();
        connections = Mockito.mock(ConnectionSupplier.class);
        try {
            Mockito.when(connections.getConnection()).thenAnswer(call -> DriverManager.getConnection(url));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        var initializer = new RuneScapeDatabaseInitializer(connections);
        new AdminToolsStore(connections);
        new SiteRankStore(connections, initializer, Mockito.mock(ClanPointsRepository.class));
        new com.runeclans.bot.member.MemberProfileRepository(connections);
        store = new HubNameChangeStore(connections, initializer);
        links = new PlayerLinkRepository(connections, initializer);
    }

    @AfterAll
    static void wipe() throws SQLException {
        clean();
    }

    @BeforeEach
    void setUp() throws SQLException {
        clean();
        discord.clear();
        Map<Long, String> clanNames = Map.of(HOME, "Younglings", ELSEWHERE, "Other Clan");
        reads = Mockito.mock(HubNameChangeReads.class);
        Mockito.when(reads.clanId("Younglings")).thenReturn(Optional.of(CLAN));
        Mockito.when(reads.clanId("Other Clan")).thenReturn(Optional.of(OTHER_CLAN));
        follower = new HubNameChanges.Follower(store, clanNames::get, reads, Supplier::get,
                new HubNameChanges.Discord() {
                    @Override
                    public void announce(long guildId, String from, String to) {
                        discord.add("announce " + guildId + " " + from + " -> " + to);
                    }

                    @Override
                    public void followRoles(long guildId, long discordUserId, String rsn) {
                        discord.add("roles " + guildId + " " + discordUserId + " " + rsn);
                    }

                    @Override
                    public void followNickname(long guildId, long discordUserId, String from, String to) {
                        discord.add("nickname " + guildId + " " + discordUserId + " " + from + " -> " + to);
                    }
                });
    }

    private static void clean() throws SQLException {
        try (Connection c = DriverManager.getConnection(TestDatabaseUrl.jdbc()); Statement s = c.createStatement()) {
            s.execute("TRUNCATE younglings.player_link, younglings.player_account, younglings.player_link_exclusion, younglings.clan_member, "
                    + "younglings.clan_member_points, younglings.clan_points_award, younglings.clan_roster_event, younglings.rsn_rename_candidate, "
                    + "younglings.hub_name_change_applied, younglings.admin_note, younglings.site_rank_hold, younglings.member_recap_seen RESTART IDENTITY");
        }
    }

    private static void sql(String sql, Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(TestDatabaseUrl.jdbc()); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            s.execute();
        }
    }

    private static List<String> rows(String sql, Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(TestDatabaseUrl.jdbc()); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> cols = new ArrayList<>();
                    for (int k = 1; k <= n; k++) cols.add(String.valueOf(rs.getObject(k)));
                    out.add(String.join("|", cols));
                }
            }
            return out;
        }
    }

    private static void member(long guildId, String rsn, boolean active, String joined) throws SQLException {
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank, active, clan_joined_at, first_seen) VALUES (?, ?, 'Recruit', ?, ?::date, ?::timestamptz)",
                guildId, rsn, active, joined, joined + "T00:00:00Z");
    }

    private static void points(long guildId, String rsn, long total) throws SQLException {
        sql("INSERT INTO younglings.clan_member_points (guild_id, rsn, total_points) VALUES (?, ?, ?)", guildId, rsn, total);
    }

    private static DataObject renamed(long changeId, String from, String to, String... clanIds) {
        return DataObject.empty().put("changeId", changeId).put("playerId", "33333333-3333-3333-3333-333333333333").put("from", from).put("to", to)
                .put("clanIds", DataArray.fromCollection(List.of(clanIds))).put("auto", true).put("at", Instant.now().toString());
    }

    private static DataObject undone(long changeId, String from, String to) {
        return DataObject.empty().put("changeId", changeId).put("from", from).put("to", to).put("at", Instant.now().toString());
    }

    private static List<String> roster(long guildId) throws SQLException {
        return rows("SELECT rsn, active, clan_joined_at FROM younglings.clan_member WHERE guild_id = ? ORDER BY rsn", guildId);
    }

    @Test
    void aLinkedRenameMovesTheBotsRowsAndPostsOneLineAndUndoPutsThemBack() throws SQLException {
        member(HOME, "Old Name", true, "2026-01-05");
        links.createLink(HOME, OWNER, "Old Name", PlayerLinkService.METHOD_ADMIN_MANUAL);
        points(HOME, "Old Name", 50);
        sql("INSERT INTO younglings.admin_note (guild_id, rsn, note, author_id) VALUES (?, 'Old Name', 'helps at events', 'x')", HOME);
        sql("INSERT INTO younglings.site_rank_hold (guild_id, rsn_key, rsn, kind, held_by) VALUES (?, 'old name', 'Old Name', 'hold', 1)", HOME);

        follower.handle(HubNameChanges.KIND_RENAMED, renamed(11, "Old Name", "New Name", CLAN));

        assertEquals(List.of("New Name|true|2026-01-05"), roster(HOME), "one member, same join date");
        assertEquals("New Name", links.getLinkForRsn(HOME, "New Name").rsn());
        assertNull(links.getLinkForRsn(HOME, "Old Name"));
        assertEquals(List.of("New Name|50"), rows("SELECT rsn, total_points FROM younglings.clan_member_points"));
        assertEquals(List.of("New Name"), rows("SELECT rsn FROM younglings.admin_note"));
        assertEquals(List.of("new name|New Name"), rows("SELECT rsn_key, rsn FROM younglings.site_rank_hold"));
        assertEquals(List.of("Old Name|New Name|CONFIRMED"), rows("SELECT old_rsn, new_rsn, status FROM younglings.rsn_rename_candidate"));
        assertEquals(List.of("announce 1 Old Name -> New Name", "roles 1 500 New Name", "nickname 1 500 Old Name -> New Name"), discord);

        discord.clear();
        follower.handle(HubNameChanges.KIND_UNDONE, undone(11, "Old Name", "New Name"));

        assertEquals(List.of("Old Name|true|2026-01-05"), roster(HOME));
        assertEquals("Old Name", links.getLinkForRsn(HOME, "Old Name").rsn());
        assertEquals(List.of("Old Name|50"), rows("SELECT rsn, total_points FROM younglings.clan_member_points"));
        assertEquals(List.of("Old Name"), rows("SELECT rsn FROM younglings.admin_note"));
        assertEquals(List.of("old name|Old Name"), rows("SELECT rsn_key, rsn FROM younglings.site_rank_hold"));
        assertEquals(List.of("REJECTED"), rows("SELECT status FROM younglings.rsn_rename_candidate"));
        assertEquals(List.of("nickname 1 500 New Name -> Old Name"), discord, "the undo posts nothing; the next roster sync reports it");
    }

    @Test
    void theSameFeedEntryTwiceMovesAndPostsOnce() throws SQLException {
        member(HOME, "Old Name", true, "2026-01-05");

        follower.handle(HubNameChanges.KIND_RENAMED, renamed(12, "Old Name", "New Name", CLAN));
        follower.handle(HubNameChanges.KIND_RENAMED, renamed(12, "Old Name", "New Name", CLAN));

        assertEquals(List.of("announce 1 Old Name -> New Name"), discord);
        assertEquals(List.of("Old Name|New Name"), rows("SELECT old_rsn, new_rsn FROM younglings.rsn_rename_candidate"));

        follower.handle(HubNameChanges.KIND_UNDONE, undone(12, "Old Name", "New Name"));
        follower.handle(HubNameChanges.KIND_UNDONE, undone(12, "Old Name", "New Name"));
        assertEquals(List.of("Old Name|true|2026-01-05"), roster(HOME));
        follower.handle(HubNameChanges.KIND_RENAMED, renamed(12, "Old Name", "New Name", CLAN));
        assertEquals(List.of("Old Name|true|2026-01-05"), roster(HOME), "an undone change is not applied again by a late repeat");
    }

    @Test
    void whenTheRosterSyncSawTheChangeFirstTheLeaveAndJoinAreReplacedAndTotalsMerged() throws SQLException {
        member(HOME, "Old Name", false, "2026-01-05");
        member(HOME, "New Name", true, "2026-11-14");
        points(HOME, "Old Name", 50);
        points(HOME, "New Name", 5);
        sql("INSERT INTO younglings.clan_roster_event (guild_id, rsn, event_type) VALUES (?, 'Old Name', 'LEAVE'), (?, 'New Name', 'JOIN'), (?, 'Someone', 'JOIN')",
                HOME, HOME, HOME);

        follower.handle(HubNameChanges.KIND_RENAMED, renamed(13, "Old Name", "New Name", CLAN));

        assertEquals(List.of("New Name|true|2026-01-05", "Old Name|false|2026-01-05"), roster(HOME), "the new name keeps the first join date");
        assertEquals(List.of("New Name|55"), rows("SELECT rsn, total_points FROM younglings.clan_member_points"));
        assertEquals(List.of("Someone|JOIN"), rows("SELECT rsn, event_type FROM younglings.clan_roster_event"), "no leave and join any more");
        assertEquals(List.of("announce 1 Old Name -> New Name"), discord, "the rename line only; the leave and join were posted already");

        follower.handle(HubNameChanges.KIND_UNDONE, undone(13, "Old Name", "New Name"));

        assertEquals(List.of("New Name|true|2026-11-14", "Old Name|false|2026-01-05"), roster(HOME));
        assertEquals(List.of("New Name|5", "Old Name|50"), rows("SELECT rsn, total_points FROM younglings.clan_member_points ORDER BY rsn"));
        assertEquals(List.of("New Name|JOIN", "Old Name|LEAVE", "Someone|JOIN"), rows("SELECT rsn, event_type FROM younglings.clan_roster_event ORDER BY rsn"));
    }

    @Test
    void aRenameForAClanTheServerDoesNotTrackDoesNothingThere() throws SQLException {
        member(ELSEWHERE, "Old Name", true, "2026-01-05");
        points(ELSEWHERE, "Old Name", 7);

        follower.handle(HubNameChanges.KIND_RENAMED, renamed(14, "Old Name", "New Name", CLAN));

        assertEquals(List.of("Old Name|true|2026-01-05"), roster(ELSEWHERE));
        assertEquals(List.of("Old Name|7"), rows("SELECT rsn, total_points FROM younglings.clan_member_points"));
        assertEquals(List.of(), rows("SELECT id FROM younglings.rsn_rename_candidate"));
        assertTrue(discord.isEmpty());
    }

    @Test
    void aRowTheNewNameAlreadyHoldsStaysOnTheOldName() throws SQLException {
        member(HOME, "Old Name", true, "2026-01-05");
        sql("INSERT INTO younglings.clan_points_award (guild_id, rsn, award_type, points, awarded_for_date) VALUES "
                + "(?, 'Old Name', 'CAP', 10, '2026-11-01'), (?, 'Old Name', 'CAP', 10, '2026-11-08'), (?, 'New Name', 'CAP', 10, '2026-11-08')", HOME, HOME, HOME);

        follower.handle(HubNameChanges.KIND_RENAMED, renamed(15, "Old Name", "New Name", CLAN));

        assertEquals(List.of("2026-11-01|New Name", "2026-11-08|New Name", "2026-11-08|Old Name"),
                rows("SELECT awarded_for_date, rsn FROM younglings.clan_points_award ORDER BY awarded_for_date, rsn"));

        follower.handle(HubNameChanges.KIND_UNDONE, undone(15, "Old Name", "New Name"));

        assertEquals(List.of("2026-11-01|Old Name", "2026-11-08|New Name", "2026-11-08|Old Name"),
                rows("SELECT awarded_for_date, rsn FROM younglings.clan_points_award ORDER BY awarded_for_date, rsn"));
    }

    @Test
    void recapSeenMarksMoveLowerCasedAsTheTableKeepsThem() throws SQLException {
        member(HOME, "Old Name", true, "2026-01-05");
        sql("INSERT INTO younglings.member_recap_seen (guild_id, rsn, period_key, discord_user_id) VALUES (?, 'old name', '2026-10', 1)", HOME);

        follower.handle(HubNameChanges.KIND_RENAMED, renamed(16, "Old Name", "New Name", CLAN));
        assertEquals(List.of("new name|2026-10"), rows("SELECT rsn, period_key FROM younglings.member_recap_seen"));

        follower.handle(HubNameChanges.KIND_UNDONE, undone(16, "Old Name", "New Name"));
        assertEquals(List.of("old name|2026-10"), rows("SELECT rsn, period_key FROM younglings.member_recap_seen"));
    }

    @Test
    void aClanLookupTheHubDidNotAnswerKeepsNothingAndTheRetryDoesItAll() throws SQLException {
        member(HOME, "Old Name", true, "2026-01-05");
        member(ELSEWHERE, "Old Name", true, "2026-01-05");
        Mockito.when(reads.clanId("Other Clan")).thenThrow(new HubNameChangeReads.Unanswered("busy"));

        assertThrows(HubNameChangeReads.Unanswered.class,
                () -> follower.handle(HubNameChanges.KIND_RENAMED, renamed(17, "Old Name", "New Name", CLAN, OTHER_CLAN)));
        assertNull(store.state(17), "not recorded, so a retry is not a no-op");
        assertEquals(List.of("Old Name|true|2026-01-05"), roster(HOME));
        assertTrue(discord.isEmpty());

        Mockito.reset(reads);
        Mockito.when(reads.clanId("Younglings")).thenReturn(Optional.of(CLAN));
        Mockito.when(reads.clanId("Other Clan")).thenReturn(Optional.of(OTHER_CLAN));
        follower.handle(HubNameChanges.KIND_RENAMED, renamed(17, "Old Name", "New Name", CLAN, OTHER_CLAN));

        assertEquals(List.of("New Name|true|2026-01-05"), roster(HOME));
        assertEquals(List.of("New Name|true|2026-01-05"), roster(ELSEWHERE), "both servers moved");
    }

    @Test
    void aFailureInsideTheMoveRollsEverythingBack() throws SQLException {
        member(HOME, "Old Name", true, "2026-01-05");
        links.createLink(HOME, OWNER, "Old Name", PlayerLinkService.METHOD_ADMIN_MANUAL);
        sql("ALTER TABLE younglings.admin_note ADD CONSTRAINT t1160_no_new CHECK (rsn <> 'New Name')");
        sql("INSERT INTO younglings.admin_note (guild_id, rsn, note, author_id) VALUES (?, 'Old Name', 'x', 'x')", HOME);
        try {
            assertThrows(RuntimeException.class, () -> follower.handle(HubNameChanges.KIND_RENAMED, renamed(18, "Old Name", "New Name", CLAN)));
        } finally {
            sql("ALTER TABLE younglings.admin_note DROP CONSTRAINT t1160_no_new");
        }

        assertNull(store.state(18), "no journal row");
        assertEquals("Old Name", links.getLinkForRsn(HOME, "Old Name").rsn(), "the link moved before the failure went back too");
        assertEquals(List.of("Old Name|true|2026-01-05"), roster(HOME));
        assertTrue(discord.isEmpty());
    }

    @Test
    void undoingAMergedTotalWhenTheOldNameHasPointsAgainLosesNothing() throws SQLException {
        member(HOME, "Old Name", false, "2026-01-05");
        member(HOME, "New Name", true, "2026-11-14");
        points(HOME, "Old Name", 50);
        points(HOME, "New Name", 5);
        follower.handle(HubNameChanges.KIND_RENAMED, renamed(19, "Old Name", "New Name", CLAN));
        points(HOME, "Old Name", 3); // the old name earned points again before the undo

        follower.handle(HubNameChanges.KIND_UNDONE, undone(19, "Old Name", "New Name"));

        assertEquals(List.of("New Name|5", "Old Name|53"), rows("SELECT rsn, total_points FROM younglings.clan_member_points ORDER BY rsn"));
    }

    @Test
    void anOlderRealLeaveOrJoinIsNotTakenAsThePair() throws SQLException {
        member(HOME, "Old Name", false, "2026-01-05");
        member(HOME, "New Name", true, "2026-11-14");
        sql("INSERT INTO younglings.clan_roster_event (guild_id, rsn, event_type, event_at) VALUES (?, 'Old Name', 'LEAVE', NOW() - INTERVAL '20 days'), "
                + "(?, 'New Name', 'JOIN', NOW())", HOME, HOME);

        follower.handle(HubNameChanges.KIND_RENAMED, renamed(20, "Old Name", "New Name", CLAN));

        assertEquals(List.of("New Name|JOIN", "Old Name|LEAVE"), rows("SELECT rsn, event_type FROM younglings.clan_roster_event ORDER BY rsn"));
    }

    @Test
    void theCatchUpFollowsLinksAndUndosTheFeedMissed() throws SQLException {
        member(HOME, "Old Name", true, "2026-01-05");
        member(HOME, "Was", true, "2026-01-05");
        follower.handle(HubNameChanges.KIND_RENAMED, renamed(22, "Was", "Now", CLAN)); // followed; its undo is missed below
        discord.clear();
        Instant now = Instant.now();
        DataObject missed = DataObject.empty().put("id", 21).put("from", "Old Name").put("to", "New Name").put("status", "linked")
                .put("decidedAt", now.minusSeconds(600).toString());
        DataObject old = DataObject.empty().put("id", 23).put("from", "Ancient").put("to", "Later").put("status", "linked")
                .put("decidedAt", now.minusSeconds(5 * 86400).toString());
        DataObject undoneMissed = DataObject.empty().put("id", 22).put("from", "Was").put("to", "Now").put("status", "undone")
                .put("undoneAt", now.minusSeconds(60).toString());
        Mockito.when(reads.changes(CLAN, "linked")).thenReturn(List.of(missed, old));
        Mockito.when(reads.changes(CLAN, "undone")).thenReturn(List.of(undoneMissed));

        follower.catchUp(now);
        follower.catchUp(now);

        assertEquals(List.of("New Name|true|2026-01-05", "Was|true|2026-01-05"), roster(HOME));
        assertEquals(List.of("announce 1 Old Name -> New Name"), discord, "once, and nothing for the change outside the window");
        assertNull(store.state(23));
        assertEquals("undone", store.state(22));
    }

    @Test
    void theCatchUpDoesNothingWhenAClanLookupGoesUnanswered() throws SQLException {
        member(HOME, "Old Name", true, "2026-01-05");
        member(ELSEWHERE, "Someone", true, "2026-01-05");
        Mockito.when(reads.clanId("Other Clan")).thenThrow(new HubNameChangeReads.Unanswered("busy"));

        assertThrows(HubNameChangeReads.Unanswered.class, () -> follower.catchUp(Instant.now()));
        Mockito.verify(reads, Mockito.never()).changes(Mockito.anyString(), Mockito.anyString());
    }
}
