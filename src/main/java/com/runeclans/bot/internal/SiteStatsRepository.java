package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.stats.StatsBackend;
import com.runeclans.bot.runescape.stats.StatsBackends;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregate queries behind the website's public clan pages (roster, leaderboards, charts, member
 * profiles). Everything is derived from data the bot already records — the roster, the polled XP snapshots,
 * the adventure-log feed, clan points and join/leave events — so nothing here writes, and none of it needs a
 * schema change. Only active clan members are ever included.
 * <p>
 * Citadel weeks run Wednesday to Tuesday (the Citadel's reset), bucketed by when the bot recorded the
 * activity, the same convention the weekly report uses.
 */
@BConfiguration
public class SiteStatsRepository {
    private static final Logger log = LoggerFactory.getLogger(SiteStatsRepository.class);

    private final ConnectionSupplier connectionSupplier;


    /** Stats reads answer from the hub while it polls, else from this class's own SQL (T-851, {@link StatsBackends}). */
    private volatile StatsBackends stats = StatsBackends.none();

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. The bot's own tables answer until the hub polls. */
    @BService
    public static SiteStatsRepository siteStatsRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer, StatsBackends backends) {
        SiteStatsRepository repository = new SiteStatsRepository(connectionSupplier, initializer);
        repository.stats = backends;
        return repository;
    }

    /** Reads from {@code backends} while the hub polls (tests). */
    public SiteStatsRepository withStats(StatsBackends backends) {
        this.stats = backends;
        return this;
    }
    public SiteStatsRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    // ---------- rows ----------

    public record MemberRow(String rsn, String clanRank, int rankOrder, long totalXp, long kills, LocalDate clanJoinedAt,
                            OffsetDateTime firstSeen, long points, boolean promotionNeeded, boolean verified,
                            Integer totalLevel, Integer combatLevel, OffsetDateTime lastPolled) {}

    public record Gain(String rsn, long xp) {}

    public record WeekCount(LocalDate weekStart, int capped, int visited) {}

    public record WeekRoster(LocalDate weekStart, int joins, int leaves) {}

    public record Capper(String rsn, int weeksCapped, int totalCaps) {}

    public record SkillLeader(int skillId, String rsn, int level, long xp) {}

    public record DayPoint(LocalDate date, long totalXp, int totalLevel) {}

    public record CitadelTotals(int caps, int visits, List<LocalDate> cappedWeeks) {}

    public record Award(String type, long points, LocalDate date) {}

    public record CofferTotals(long donated, int donations, int donors, long held, int giveaways, long givenAway) {}

    public record CofferWeek(LocalDate weekStart, long donated) {}

    public record Donor(String name, long total, int donations) {}

    public record Giveaway(long amount, String description, OffsetDateTime at) {}

    public record ActivityRow(String rsn, String text, String details, String date, OffsetDateTime recordedAt, String type) {
        /** A row from the bot's own tables, which carry no event code. */
        public ActivityRow(String rsn, String text, String details, String date, OffsetDateTime recordedAt) {
            this(rsn, text, details, date, recordedAt, null);
        }
    }

    public record BigDay(String rsn, LocalDate date, long xp) {}

    public record ClubMember(String rsn, int skills) {}

    public record CapWeek(String rsn, LocalDate weekStart, boolean capped) {}

    public record RosterEventRow(String rsn, String type, OffsetDateTime at) {}

    public record RenameRow(String oldRsn, String newRsn, OffsetDateTime at) {}

    public record MyDonation(String donorName, long amount, OffsetDateTime at) {}

    public record SkillGain(int skillId, long xp) {}

    public record DayXp(LocalDate date, long xp) {}

    public record PointsEarned(String rsn, long points) {}

    /** Clan points a member was awarded for one day (T-710). */
    public record PointsDay(String rsn, LocalDate day, long points) {}

    public record SignupRow(long id, String title, String notification, Integer max, String status, OffsetDateTime createdAt, List<SignupEntry> entries) {}

    public record SignupEntry(long userId, String rsn, int position) {}

    public record PollOption(int number, String label, int votes) {}

    public record PollRow(long id, long channelId, Long messageId, String title, boolean anonymous, boolean multiple, String status,
                          OffsetDateTime createdAt, OffsetDateTime closedAt, List<PollOption> options) {}

    // ---------- queries ----------

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> List<T> query(String what, String sql, RowMapper<T> mapper, Object... params) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof OffsetDateTime time) statement.setTimestamp(i + 1, Timestamp.from(time.toInstant()));
                else statement.setObject(i + 1, p);
            }
            List<T> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) rows.add(mapper.map(rs));
            }
            return rows;
        } catch (SQLException e) {
            log.error("Failed to query {}", what, e);
            throw new RuntimeException("Failed to query " + what, e);
        }
    }

    /** Every active clan member with their rank, points and latest polled levels. */
    public List<MemberRow> members(long guildId) {
        StatsBackend hub = stats.hub();
        List<MemberRow> rows = membersFromRoster(guildId, hub == null);
        if (hub == null) return rows;
        // T-851: the roster (rank, points, verified) is the bot's; the polled levels come from the hub while it polls
        Map<String, PlayerLinkRepository.StatsSnapshotRow> latest = hub.membersLatest(guildId);
        List<MemberRow> out = new ArrayList<>(rows.size());
        for (MemberRow m : rows) {
            PlayerLinkRepository.StatsSnapshotRow s = latest.get(m.rsn().strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT));
            out.add(new MemberRow(m.rsn(), m.clanRank(), m.rankOrder(), m.totalXp(), m.kills(), m.clanJoinedAt(), m.firstSeen(), m.points(), m.promotionNeeded(), m.verified(),
                    s == null ? null : s.totalLevel(), s == null ? null : s.combatLevel(), s == null ? null : s.snapshotAt()));
        }
        return out;
    }

    /** The roster rows; {@code withLevels} joins the bot's own newest snapshot per member, left out while the hub holds the levels. */
    private List<MemberRow> membersFromRoster(long guildId, boolean withLevels) {
        String levels = withLevels ? """
                LEFT JOIN LATERAL (
                    SELECT total_level, combat_level, snapshot_at FROM younglings.player_stats_snapshot s
                    WHERE LOWER(s.rsn) = LOWER(m.rsn)
                    ORDER BY s.snapshot_at DESC LIMIT 1) s ON TRUE
                """ : """
                LEFT JOIN LATERAL (SELECT NULL::int AS total_level, NULL::int AS combat_level, NULL::timestamptz AS snapshot_at) s ON TRUE
                """;
        return query("site members", """
                SELECT m.rsn, m.clan_rank, COALESCE(rc.rank_order, -1) AS rank_order, m.total_xp, m.kills, m.clan_joined_at,
                       m.first_seen, COALESCE(p.total_points, 0) AS points, COALESCE(p.promotion_needed, FALSE) AS promotion_needed,
                       EXISTS (SELECT 1 FROM younglings.player_link l WHERE l.guild_id = m.guild_id AND LOWER(l.rsn) = LOWER(m.rsn)) AS verified,
                       s.total_level, s.combat_level, s.snapshot_at
                FROM younglings.clan_member m
                LEFT JOIN younglings.clan_rank_config rc ON rc.guild_id = m.guild_id AND LOWER(rc.rank_name) = LOWER(m.clan_rank)
                LEFT JOIN younglings.clan_member_points p ON p.guild_id = m.guild_id AND LOWER(p.rsn) = LOWER(m.rsn)
                """ + levels + """
                WHERE m.guild_id = ? AND m.active
                """, rs -> new MemberRow(rs.getString("rsn"), rs.getString("clan_rank"), rs.getInt("rank_order"), rs.getLong("total_xp"),
                rs.getLong("kills"), rs.getObject("clan_joined_at", LocalDate.class), rs.getObject("first_seen", OffsetDateTime.class),
                rs.getLong("points"), rs.getBoolean("promotion_needed"), rs.getBoolean("verified"),
                (Integer) rs.getObject("total_level"), (Integer) rs.getObject("combat_level"), rs.getObject("snapshot_at", OffsetDateTime.class)), guildId);
    }

    /**
     * The servers whose clan roster has {@code rsn} as an active member (name matched ignoring case), for a player's public profile
     * ({@link DirectoryPlayerRoutes}). Only server ids: whether each clan and member page may be shown is decided by the caller.
     */
    public List<Long> guildsWithActiveMember(String rsn) {
        return query("guilds with member", "SELECT DISTINCT guild_id FROM younglings.clan_member WHERE LOWER(rsn) = LOWER(?) AND active",
                rs -> rs.getLong("guild_id"), rsn);
    }

    /**
     * Total XP gained by each active member since {@code since}: their newest snapshot minus the snapshot at
     * (or, failing that, just after) the start. Members with no snapshots yet are left out.
     */
    public List<Gain> xpGains(long guildId, OffsetDateTime since) {
        return xpGainsBetween(guildId, since, OffsetDateTime.now().plusMinutes(1));
    }

    /** XP gained inside {@code [from, to]}: the newest snapshot at or before {@code to}, minus the one at (or, failing that, just after) {@code from}. */
    public List<Gain> xpGainsBetween(long guildId, OffsetDateTime from, OffsetDateTime to) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.xpGainsBetween(guildId, from, to);
        // One index probe per member (player_stats_snapshot_global_rsn_idx) instead of DISTINCT ON over every member's whole history.
        // ORDER BY m.k keeps the row order the DISTINCT ON form produced, so callers that sort by XP break ties the same way.
        return query("xp gains", """
                WITH members AS (
                    SELECT LOWER(rsn) AS k, rsn FROM younglings.clan_member WHERE guild_id = ? AND active)
                SELECT m.rsn, l.total_xp - COALESCE(b.total_xp, a.total_xp) AS gain
                FROM members m
                JOIN LATERAL (
                    SELECT s.total_xp FROM younglings.player_stats_snapshot s
                    WHERE LOWER(s.rsn) = m.k AND s.snapshot_at <= ? ORDER BY s.snapshot_at DESC LIMIT 1) l ON TRUE
                LEFT JOIN LATERAL (
                    SELECT s.total_xp FROM younglings.player_stats_snapshot s
                    WHERE LOWER(s.rsn) = m.k AND s.snapshot_at <= ? ORDER BY s.snapshot_at DESC LIMIT 1) b ON TRUE
                LEFT JOIN LATERAL (
                    SELECT s.total_xp FROM younglings.player_stats_snapshot s
                    WHERE LOWER(s.rsn) = m.k AND s.snapshot_at > ? ORDER BY s.snapshot_at ASC LIMIT 1) a ON TRUE
                WHERE COALESCE(b.total_xp, a.total_xp) IS NOT NULL
                ORDER BY m.k
                """, rs -> new Gain(rs.getString("rsn"), rs.getLong("gain")), guildId, to, from, from);
    }

    /** The first moment the bot has any XP snapshot for the clan — where the month-by-month leaderboards can start. */
    public OffsetDateTime firstSnapshotAt(long guildId) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.firstSnapshotAt(guildId);
        List<OffsetDateTime> rows = query("first snapshot", "SELECT MIN(s.snapshot_at) AS first FROM younglings.player_stats_snapshot s JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(s.rsn) WHERE m.guild_id = ?",
                rs -> rs.getObject("first", OffsetDateTime.class), guildId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Members ranked by Citadel weeks capped inside {@code [from, to)}. */
    public List<Capper> cappersBetween(long guildId, OffsetDateTime from, OffsetDateTime to, int limit) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.cappersBetween(guildId, from, to, limit);
        return query("cappers in window", """
                SELECT m.rsn, COUNT(DISTINCT (date_trunc('week', (COALESCE(a.occurred_at, a.recorded_at) AT TIME ZONE 'UTC') - INTERVAL '2 days 10 minutes'))) AS weeks_capped, COUNT(*) AS total_caps
                FROM younglings.player_activity a
                JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(a.rsn) AND m.active
                WHERE m.guild_id = ? AND a.activity_text LIKE 'Capped at my Clan Citadel%' AND COALESCE(a.occurred_at, a.recorded_at) >= ? AND COALESCE(a.occurred_at, a.recorded_at) < ?
                GROUP BY m.rsn ORDER BY weeks_capped DESC, total_caps DESC, m.rsn LIMIT ?
                """, rs -> new Capper(rs.getString("rsn"), rs.getInt("weeks_capped"), rs.getInt("total_caps")), guildId, from, to, limit);
    }

    /** A member's clan-point awards, newest first. */
    public List<Award> awards(long guildId, String rsn, int limit) {
        return query("point awards", """
                SELECT award_type, points, awarded_for_date FROM younglings.clan_points_award
                WHERE guild_id = ? AND LOWER(rsn) = LOWER(?) ORDER BY awarded_for_date DESC, id DESC LIMIT ?
                """, rs -> new Award(rs.getString("award_type"), rs.getLong("points"), rs.getObject("awarded_for_date", LocalDate.class)), guildId, rsn, limit);
    }

    // ---------- one member's coffer ----------

    /** Donations recorded under any of {@code names} (compared ignoring case), newest first. */
    public List<MyDonation> donationsBy(long guildId, List<String> names, int limit) {
        if (names.isEmpty()) return List.of();
        String[] lowered = names.stream().map(String::toLowerCase).toArray(String[]::new);
        return query("my donations", """
                SELECT donor_name, amount, submitted_at FROM younglings.coffer_donation
                WHERE guild_id = ? AND LOWER(donor_name) = ANY (?) ORDER BY submitted_at DESC LIMIT ?
                """, rs -> new MyDonation(rs.getString("donor_name"), rs.getLong("amount"), rs.getObject("submitted_at", OffsetDateTime.class)), guildId, lowered, limit);
    }

    public long holderBalance(long guildId, long userId) {
        List<Long> rows = query("coffer balance", "SELECT amount FROM younglings.coffer_holder WHERE guild_id = ? AND discord_user_id = ?", rs -> rs.getLong(1), guildId, userId);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    public List<Giveaway> giveawaysTo(long guildId, long userId, int limit) {
        return query("my giveaways", "SELECT amount, description, given_at FROM younglings.coffer_giveaway WHERE guild_id = ? AND recipient_discord_id = ? ORDER BY given_at DESC LIMIT ?",
                rs -> new Giveaway(rs.getLong("amount"), rs.getString("description"), rs.getObject("given_at", OffsetDateTime.class)), guildId, userId, limit);
    }

    // ---------- recap windows ----------

    /** Skill XP gained inside {@code [from, to]} by one member ({@code rsn}) or, with {@code rsn == null}, by every active member together. */
    public List<SkillGain> skillGainsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.skillGainsBetween(guildId, rsn, from, to);
        // Per-member index probes, as in xpGainsBetween. The inner LATERAL for latest keeps only members who have a snapshot.
        return query("skill gains", """
                WITH members AS (
                    SELECT LOWER(rsn) AS k FROM younglings.clan_member WHERE guild_id = ? AND active AND (?::text IS NULL OR LOWER(rsn) = LOWER(?))),
                base AS (
                    SELECT m.k, l.snapshot_id AS latest_id, COALESCE(b.snapshot_id, a.snapshot_id) AS snapshot_id
                    FROM members m
                    JOIN LATERAL (
                        SELECT s.snapshot_id FROM younglings.player_stats_snapshot s
                        WHERE LOWER(s.rsn) = m.k AND s.snapshot_at <= ? ORDER BY s.snapshot_at DESC LIMIT 1) l ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT s.snapshot_id FROM younglings.player_stats_snapshot s
                        WHERE LOWER(s.rsn) = m.k AND s.snapshot_at <= ? ORDER BY s.snapshot_at DESC LIMIT 1) b ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT s.snapshot_id FROM younglings.player_stats_snapshot s
                        WHERE LOWER(s.rsn) = m.k AND s.snapshot_at > ? ORDER BY s.snapshot_at ASC LIMIT 1) a ON TRUE)
                SELECT ls.skill_id, SUM(ls.xp - bs.xp) AS gain
                FROM base ba
                JOIN younglings.player_skill_snapshot ls ON ls.snapshot_id = ba.latest_id
                JOIN younglings.player_skill_snapshot bs ON bs.snapshot_id = ba.snapshot_id AND bs.skill_id = ls.skill_id
                WHERE ba.snapshot_id IS NOT NULL
                GROUP BY ls.skill_id HAVING SUM(ls.xp - bs.xp) > 0 ORDER BY gain DESC
                """, rs -> new SkillGain(rs.getInt("skill_id"), rs.getLong("gain")), guildId, rsn, rsn, to, from, from);
    }

    /** XP gained on each UTC day inside the window (one member, or the whole clan summed), oldest first. */
    public List<DayXp> dailyXpBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.dailyXpBetween(guildId, rsn, from, to);
        return query("daily xp", """
                WITH daily AS (
                    SELECT LOWER(s.rsn) AS k, (s.snapshot_at AT TIME ZONE 'UTC')::date AS d, MAX(s.total_xp) AS xp
                    FROM younglings.player_stats_snapshot s
                    JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(s.rsn) AND m.active
                    WHERE m.guild_id = ? AND (?::text IS NULL OR LOWER(s.rsn) = LOWER(?))
                      AND s.snapshot_at >= ?::timestamptz - INTERVAL '1 day' AND s.snapshot_at < ?
                    GROUP BY LOWER(s.rsn), (s.snapshot_at AT TIME ZONE 'UTC')::date),
                gains AS (SELECT d, xp - LAG(xp) OVER (PARTITION BY k ORDER BY d) AS gain FROM daily)
                SELECT d, SUM(gain) AS xp FROM gains
                WHERE gain IS NOT NULL AND gain >= 0 AND d >= (?::timestamptz AT TIME ZONE 'UTC')::date
                GROUP BY d ORDER BY d
                """, rs -> new DayXp(rs.getObject("d", LocalDate.class), rs.getLong("xp")), guildId, rsn, rsn, from, to, from);
    }

    /** Adventure-log entries first seen inside the window, for one member or everyone. */
    public List<ActivityRow> activitiesBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.activitiesBetween(guildId, rsn, from, to);
        return query("activities in window", """
                SELECT m.rsn, a.activity_text, a.activity_details, a.activity_date, a.recorded_at, a.activity_type
                FROM younglings.player_activity a
                JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(a.rsn) AND m.active
                WHERE m.guild_id = ? AND (?::text IS NULL OR LOWER(a.rsn) = LOWER(?)) AND a.recorded_at >= ? AND a.recorded_at < ?
                ORDER BY a.recorded_at
                """, rs -> new ActivityRow(rs.getString("rsn"), rs.getString("activity_text"), rs.getString("activity_details"),
                rs.getString("activity_date"), rs.getObject("recorded_at", OffsetDateTime.class), rs.getString("activity_type")), guildId, rsn, rsn, from, to);
    }

    /** Clan points awarded inside the window, per member (or just one). */
    public List<PointsEarned> pointsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        return query("points in window", """
                SELECT a.rsn, SUM(a.points) AS points FROM younglings.clan_points_award a
                WHERE a.guild_id = ? AND (?::text IS NULL OR LOWER(a.rsn) = LOWER(?))
                  AND a.awarded_for_date >= (?::timestamptz AT TIME ZONE 'UTC')::date AND a.awarded_for_date < (?::timestamptz AT TIME ZONE 'UTC')::date
                GROUP BY a.rsn
                """, rs -> new PointsEarned(rs.getString("rsn"), rs.getLong("points")), guildId, rsn, rsn, from, to);
    }

    /** Clan points awarded per member per day from {@code from} (a UTC date) on, for the leaderboard race's month of clan points (T-710). */
    public List<PointsDay> pointsByDay(long guildId, LocalDate from) {
        return query("points per day", """
                SELECT a.rsn, a.awarded_for_date, SUM(a.points) AS points FROM younglings.clan_points_award a
                WHERE a.guild_id = ? AND a.awarded_for_date >= ?
                GROUP BY a.rsn, a.awarded_for_date
                """, rs -> new PointsDay(rs.getString("rsn"), rs.getObject("awarded_for_date", LocalDate.class), rs.getLong("points")), guildId, from);
    }

    /** A member's total level at the start and end of the window ({@code null} if they have no snapshot there). */
    public Integer[] totalLevelsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.totalLevelsBetween(guildId, rsn, from, to);
        Integer end = firstInt("""
                SELECT total_level FROM younglings.player_stats_snapshot WHERE LOWER(rsn) = LOWER(?) AND snapshot_at <= ? ORDER BY snapshot_at DESC LIMIT 1
                """, rsn, to);
        Integer start = firstInt("""
                SELECT total_level FROM younglings.player_stats_snapshot WHERE LOWER(rsn) = LOWER(?) AND snapshot_at <= ? ORDER BY snapshot_at DESC LIMIT 1
                """, rsn, from);
        if (start == null) {
            start = firstInt("""
                    SELECT total_level FROM younglings.player_stats_snapshot WHERE LOWER(rsn) = LOWER(?) AND snapshot_at > ? ORDER BY snapshot_at ASC LIMIT 1
                    """, rsn, from);
        }
        return new Integer[]{start, end};
    }

    private Integer firstInt(String sql, Object... params) {
        List<Integer> rows = query("a level", sql, rs -> rs.getInt(1), params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    // ---------- activity, records, history ----------

    /** The newest adventure-log entries across active members, as the bot first saw them. */
    public List<ActivityRow> recentActivities(long guildId, int limit) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.recentActivities(guildId, limit);
        return query("recent activities", """
                SELECT m.rsn, a.activity_text, a.activity_details, a.activity_date, a.recorded_at, a.activity_type
                FROM younglings.player_activity a
                JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(a.rsn) AND m.active
                WHERE m.guild_id = ? ORDER BY a.recorded_at DESC, a.id DESC LIMIT ?
                """, rs -> new ActivityRow(rs.getString("rsn"), rs.getString("activity_text"), rs.getString("activity_details"),
                rs.getString("activity_date"), rs.getObject("recorded_at", OffsetDateTime.class), rs.getString("activity_type")), guildId, limit);
    }

    /** Every active member's activities whose text starts with {@code prefix} — for the PvM and drop tallies. */
    public List<ActivityRow> activitiesStartingWith(long guildId, String prefix) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.activitiesStartingWith(guildId, prefix);
        return query("activities by prefix", """
                SELECT m.rsn, a.activity_text, a.activity_details, a.activity_date, a.recorded_at, a.activity_type
                FROM younglings.player_activity a
                JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(a.rsn) AND m.active
                WHERE m.guild_id = ? AND a.activity_text LIKE ? ORDER BY a.recorded_at DESC, a.id DESC
                """, rs -> new ActivityRow(rs.getString("rsn"), rs.getString("activity_text"), rs.getString("activity_details"),
                rs.getString("activity_date"), rs.getObject("recorded_at", OffsetDateTime.class), rs.getString("activity_type")), guildId, prefix + "%");
    }

    /** The biggest single-day XP gains anyone has had, from the daily snapshot history. */
    public List<BigDay> biggestDays(long guildId, int limit) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.biggestDays(guildId, limit);
        return query("biggest days", """
                WITH daily AS (
                    SELECT LOWER(s.rsn) AS k, MIN(m.rsn) AS rsn, (s.snapshot_at AT TIME ZONE 'UTC')::date AS d, MAX(s.total_xp) AS xp
                    FROM younglings.player_stats_snapshot s
                    JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(s.rsn) AND m.active
                    WHERE m.guild_id = ? GROUP BY LOWER(s.rsn), (s.snapshot_at AT TIME ZONE 'UTC')::date),
                gains AS (SELECT rsn, d, xp - LAG(xp) OVER (PARTITION BY k ORDER BY d) AS gain FROM daily)
                SELECT rsn, d, gain FROM gains WHERE gain IS NOT NULL AND gain > 0 ORDER BY gain DESC LIMIT ?
                """, rs -> new BigDay(rs.getString("rsn"), rs.getObject("d", LocalDate.class), rs.getLong("gain")), guildId, limit);
    }

    /** Members with at least one 200M skill, by how many they have, from each member's newest snapshot. */
    public List<ClubMember> twoHundredMillionClub(long guildId, int limit) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.twoHundredMillionClub(guildId, limit);
        return query("200m club", """
                WITH latest AS (
                    SELECT DISTINCT ON (LOWER(s.rsn)) s.snapshot_id, m.rsn FROM younglings.player_stats_snapshot s
                    JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(s.rsn) AND m.active
                    WHERE m.guild_id = ? ORDER BY LOWER(s.rsn), s.snapshot_at DESC)
                SELECT l.rsn, COUNT(*) AS skills FROM latest l JOIN younglings.player_skill_snapshot k ON k.snapshot_id = l.snapshot_id
                WHERE k.xp >= 200000000 GROUP BY l.rsn ORDER BY skills DESC, l.rsn LIMIT ?
                """, rs -> new ClubMember(rs.getString("rsn"), rs.getInt("skills")), guildId, limit);
    }

    /** Which Citadel weeks (Wednesday starts) each active member visited or capped in the last {@code weeks} weeks. */
    public List<CapWeek> citadelGrid(long guildId, int weeks) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.citadelGrid(guildId, weeks);
        return query("citadel grid", """
                SELECT m.rsn, (date_trunc('week', (COALESCE(a.occurred_at, a.recorded_at) AT TIME ZONE 'UTC') - INTERVAL '2 days 10 minutes') + INTERVAL '2 days')::date AS week_start,
                       BOOL_OR(a.activity_text LIKE 'Capped at my Clan Citadel%') AS capped
                FROM younglings.player_activity a
                JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(a.rsn) AND m.active
                WHERE m.guild_id = ? AND (a.activity_text LIKE 'Visited my Clan Citadel%' OR a.activity_text LIKE 'Capped at my Clan Citadel%')
                  AND COALESCE(a.occurred_at, a.recorded_at) >= NOW() - (? * INTERVAL '7 days')
                GROUP BY m.rsn, week_start
                """, rs -> new CapWeek(rs.getString("rsn"), rs.getObject("week_start", LocalDate.class), rs.getBoolean("capped")), guildId, weeks + 1);
    }

    /** Every Citadel week (as a week start) in which a member capped — the streak calculator's input. */
    public List<CapWeek> allCapWeeks(long guildId) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.allCapWeeks(guildId);
        return query("all cap weeks", """
                SELECT DISTINCT m.rsn, (date_trunc('week', (COALESCE(a.occurred_at, a.recorded_at) AT TIME ZONE 'UTC') - INTERVAL '2 days 10 minutes') + INTERVAL '2 days')::date AS week_start
                FROM younglings.player_activity a
                JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(a.rsn) AND m.active
                WHERE m.guild_id = ? AND a.activity_text LIKE 'Capped at my Clan Citadel%'
                """, rs -> new CapWeek(rs.getString("rsn"), rs.getObject("week_start", LocalDate.class), true), guildId);
    }

    public List<RosterEventRow> rosterEvents(long guildId, int limit) {
        return query("roster timeline", "SELECT rsn, event_type, event_at FROM younglings.clan_roster_event WHERE guild_id = ? ORDER BY event_at DESC LIMIT ?",
                rs -> new RosterEventRow(rs.getString("rsn"), rs.getString("event_type"), rs.getObject("event_at", OffsetDateTime.class)), guildId, limit);
    }

    public List<RenameRow> confirmedRenames(long guildId, int limit) {
        return query("renames", """
                SELECT old_rsn, new_rsn, COALESCE(resolved_at, detected_at) AS at FROM younglings.rsn_rename_candidate
                WHERE guild_id = ? AND status = 'CONFIRMED' ORDER BY COALESCE(resolved_at, detected_at) DESC LIMIT ?
                """, rs -> new RenameRow(rs.getString("old_rsn"), rs.getString("new_rsn"), rs.getObject("at", OffsetDateTime.class)), guildId, limit);
    }

    /** Active signup sheets with who has signed up (RuneScape names only), in queue order. */
    public List<SignupRow> activeSignups(long guildId) {
        record Head(long id, String title, String notification, Integer max, String status, OffsetDateTime createdAt) {}
        List<Head> heads = query("signups", """
                SELECT signup_id, title, notification_message, max_signups, status, created_at FROM younglings.signup
                WHERE guild_id = ? AND status IN ('ACTIVE', 'PAUSED') AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 20
                """, rs -> new Head(rs.getLong("signup_id"), rs.getString("title"), rs.getString("notification_message"), (Integer) rs.getObject("max_signups"),
                rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class)), guildId);
        if (heads.isEmpty()) return new ArrayList<>();

        // Every sheet's entries in one round trip, split per sheet below (the newest-first sheet order comes from the heads).
        record Entry(long signupId, SignupEntry entry) {}
        Map<Long, List<SignupEntry>> entriesBySheet = new HashMap<>();
        for (Entry e : query("signup entries", "SELECT signup_id, discord_user_id, rsn, queue_position FROM younglings.signup_entry WHERE signup_id = ANY(?) ORDER BY signup_id, queue_position",
                rs -> new Entry(rs.getLong("signup_id"), new SignupEntry(rs.getLong("discord_user_id"), rs.getString("rsn"), rs.getInt("queue_position"))),
                (Object) heads.stream().map(Head::id).toArray(Long[]::new))) {
            entriesBySheet.computeIfAbsent(e.signupId(), k -> new ArrayList<>()).add(e.entry());
        }

        List<SignupRow> rows = new ArrayList<>();
        for (Head head : heads) {
            List<SignupEntry> entries = entriesBySheet.getOrDefault(head.id(), new ArrayList<>());
            rows.add(new SignupRow(head.id(), head.title(), head.notification(), head.max(), head.status(), head.createdAt(), entries));
        }
        return rows;
    }

    /** How many sheets {@link #activeSignups} lists (so capped at its 20), without loading their entries. */
    public int countActiveSignups(long guildId) {
        return query("signup count", """
                SELECT COUNT(*) AS n FROM (SELECT 1 FROM younglings.signup
                WHERE guild_id = ? AND status IN ('ACTIVE', 'PAUSED') AND deleted_at IS NULL LIMIT 20) s
                """, rs -> rs.getInt("n"), guildId).getFirst();
    }

    /** The server's polls, newest first, each with its options and vote counts. */
    public List<PollRow> polls(long guildId, int limit) {
        record Head(long id, long channelId, Long messageId, String title, boolean anonymous, boolean multiple, String status, OffsetDateTime createdAt, OffsetDateTime closedAt) {}
        List<Head> heads = query("polls", """
                SELECT poll_id, channel_id, message_id, title, anonymous, multiple_votes, status, created_at, closed_at
                FROM younglings.poll WHERE guild_id = ? ORDER BY created_at DESC LIMIT ?
                """, rs -> new Head(rs.getLong("poll_id"), rs.getLong("channel_id"), (Long) rs.getObject("message_id"), rs.getString("title"),
                rs.getBoolean("anonymous"), rs.getBoolean("multiple_votes"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("closed_at", OffsetDateTime.class)), guildId, limit);

        if (heads.isEmpty()) return new ArrayList<>();

        // Every poll's options and vote counts in one round trip, split per poll below (the newest-first poll order comes from the heads).
        record Option(long pollId, PollOption option) {}
        Map<Long, List<PollOption>> optionsByPoll = new HashMap<>();
        for (Option o : query("poll options", """
                SELECT o.poll_id, o.option_number, o.label, COUNT(v.vote_id) AS votes FROM younglings.poll_option o
                LEFT JOIN younglings.poll_vote v ON v.option_id = o.option_id
                WHERE o.poll_id = ANY(?) GROUP BY o.poll_id, o.option_number, o.label ORDER BY o.poll_id, o.option_number
                """, rs -> new Option(rs.getLong("poll_id"), new PollOption(rs.getInt("option_number"), rs.getString("label"), rs.getInt("votes"))),
                (Object) heads.stream().map(Head::id).toArray(Long[]::new))) {
            optionsByPoll.computeIfAbsent(o.pollId(), k -> new ArrayList<>()).add(o.option());
        }

        List<PollRow> polls = new ArrayList<>();
        for (Head head : heads) {
            List<PollOption> options = optionsByPoll.getOrDefault(head.id(), new ArrayList<>());
            polls.add(new PollRow(head.id(), head.channelId(), head.messageId(), head.title(), head.anonymous(), head.multiple(), head.status(),
                    head.createdAt(), head.closedAt(), options));
        }
        return polls;
    }

    // ---------- the clan coffer (aggregates only — who holds what stays private) ----------

    public CofferTotals cofferTotals(long guildId) {
        long donated = 0, held = 0, givenAway = 0;
        int donations = 0, donors = 0, giveaways = 0;
        for (long[] row : query("coffer donations", "SELECT COALESCE(SUM(amount), 0) AS total, COUNT(*) AS n, COUNT(DISTINCT LOWER(donor_name)) AS donors FROM younglings.coffer_donation WHERE guild_id = ?",
                rs -> new long[]{rs.getLong("total"), rs.getLong("n"), rs.getLong("donors")}, guildId)) {
            donated = row[0];
            donations = (int) row[1];
            donors = (int) row[2];
        }
        for (long[] row : query("coffer held", "SELECT COALESCE(SUM(amount), 0) AS total FROM younglings.coffer_holder WHERE guild_id = ?", rs -> new long[]{rs.getLong("total")}, guildId)) {
            held = row[0];
        }
        for (long[] row : query("coffer giveaways", "SELECT COALESCE(SUM(amount), 0) AS total, COUNT(*) AS n FROM younglings.coffer_giveaway WHERE guild_id = ?",
                rs -> new long[]{rs.getLong("total"), rs.getLong("n")}, guildId)) {
            givenAway = row[0];
            giveaways = (int) row[1];
        }
        return new CofferTotals(donated, donations, donors, held, giveaways, givenAway);
    }

    public List<CofferWeek> cofferByWeek(long guildId, int weeks) {
        return query("coffer weeks", """
                SELECT date_trunc('week', submitted_at AT TIME ZONE 'UTC')::date AS week_start, SUM(amount) AS donated
                FROM younglings.coffer_donation WHERE guild_id = ? AND submitted_at >= NOW() - (? * INTERVAL '7 days')
                GROUP BY week_start ORDER BY week_start
                """, rs -> new CofferWeek(rs.getObject("week_start", LocalDate.class), rs.getLong("donated")), guildId, weeks + 1);
    }

    public List<Donor> topDonors(long guildId, int limit) {
        return query("top donors", """
                SELECT MIN(donor_name) AS name, SUM(amount) AS total, COUNT(*) AS n FROM younglings.coffer_donation
                WHERE guild_id = ? GROUP BY LOWER(donor_name) ORDER BY total DESC LIMIT ?
                """, rs -> new Donor(rs.getString("name"), rs.getLong("total"), rs.getInt("n")), guildId, limit);
    }

    public List<Giveaway> recentGiveaways(long guildId, int limit) {
        return query("recent giveaways", "SELECT amount, description, given_at FROM younglings.coffer_giveaway WHERE guild_id = ? ORDER BY given_at DESC LIMIT ?",
                rs -> new Giveaway(rs.getLong("amount"), rs.getString("description"), rs.getObject("given_at", OffsetDateTime.class)), guildId, limit);
    }

    /** Capped / visited head-counts for each of the last {@code weeks} Citadel weeks, oldest first, including the running week. */
    public List<WeekCount> citadelByWeek(long guildId, int weeks) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.citadelByWeek(guildId, weeks);
        return query("citadel weeks", """
                WITH acts AS (
                    SELECT LOWER(a.rsn) AS k,
                           (date_trunc('week', (COALESCE(a.occurred_at, a.recorded_at) AT TIME ZONE 'UTC') - INTERVAL '2 days 10 minutes') + INTERVAL '2 days')::date AS week_start,
                           (a.activity_text LIKE 'Capped at my Clan Citadel%') AS capped
                    FROM younglings.player_activity a
                    JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(a.rsn) AND m.active
                    WHERE m.guild_id = ? AND (a.activity_text LIKE 'Visited my Clan Citadel%' OR a.activity_text LIKE 'Capped at my Clan Citadel%')
                      AND COALESCE(a.occurred_at, a.recorded_at) >= NOW() - (? * INTERVAL '7 days'))
                SELECT week_start, COUNT(DISTINCT k) FILTER (WHERE capped) AS capped, COUNT(DISTINCT k) AS visited
                FROM acts GROUP BY week_start ORDER BY week_start
                """, rs -> new WeekCount(rs.getObject("week_start", LocalDate.class), rs.getInt("capped"), rs.getInt("visited")), guildId, weeks + 1);
    }

    /** The members who have capped the most Citadel weeks. */
    public List<Capper> topCappers(long guildId, int limit) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.topCappers(guildId, limit);
        return query("top cappers", """
                SELECT m.rsn, COUNT(DISTINCT (date_trunc('week', (COALESCE(a.occurred_at, a.recorded_at) AT TIME ZONE 'UTC') - INTERVAL '2 days 10 minutes'))) AS weeks_capped, COUNT(*) AS total_caps
                FROM younglings.player_activity a
                JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(a.rsn) AND m.active
                WHERE m.guild_id = ? AND a.activity_text LIKE 'Capped at my Clan Citadel%'
                GROUP BY m.rsn ORDER BY weeks_capped DESC, total_caps DESC, m.rsn LIMIT ?
                """, rs -> new Capper(rs.getString("rsn"), rs.getInt("weeks_capped"), rs.getInt("total_caps")), guildId, limit);
    }

    /** Joins and leaves per calendar week (Monday start) for the last {@code weeks} weeks, oldest first. */
    public List<WeekRoster> rosterByWeek(long guildId, int weeks) {
        return query("roster events", """
                SELECT date_trunc('week', event_at AT TIME ZONE 'UTC')::date AS week_start,
                       COUNT(*) FILTER (WHERE event_type = 'JOIN') AS joins, COUNT(*) FILTER (WHERE event_type = 'LEAVE') AS leaves
                FROM younglings.clan_roster_event
                WHERE guild_id = ? AND event_at >= NOW() - (? * INTERVAL '7 days')
                GROUP BY week_start ORDER BY week_start
                """, rs -> new WeekRoster(rs.getObject("week_start", LocalDate.class), rs.getInt("joins"), rs.getInt("leaves")), guildId, weeks + 1);
    }

    /** The top {@code perSkill} members by XP in every skill, from each member's newest snapshot. */
    public List<SkillLeader> skillLeaders(long guildId, int perSkill) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.skillLeaders(guildId, perSkill);
        return query("skill leaders", """
                WITH latest AS (
                    SELECT DISTINCT ON (LOWER(s.rsn)) s.snapshot_id, m.rsn
                    FROM younglings.player_stats_snapshot s
                    JOIN younglings.clan_member m ON LOWER(m.rsn) = LOWER(s.rsn) AND m.active
                    WHERE m.guild_id = ? ORDER BY LOWER(s.rsn), s.snapshot_at DESC)
                SELECT skill_id, rsn, level, xp FROM (
                    SELECT k.skill_id, l.rsn, k.level, k.xp, ROW_NUMBER() OVER (PARTITION BY k.skill_id ORDER BY k.xp DESC, l.rsn) AS rn
                    FROM latest l JOIN younglings.player_skill_snapshot k ON k.snapshot_id = l.snapshot_id) t
                WHERE rn <= ? ORDER BY skill_id, rn
                """, rs -> new SkillLeader(rs.getInt("skill_id"), rs.getString("rsn"), rs.getInt("level"), rs.getLong("xp")), guildId, perSkill);
    }

    /** One point per UTC day (that day's last snapshot) for the last {@code days} days, oldest first. */
    public List<DayPoint> dailyHistory(long guildId, String rsn, int days) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.dailyHistory(guildId, rsn, days);
        return query("daily history", """
                SELECT DISTINCT ON ((snapshot_at AT TIME ZONE 'UTC')::date) (snapshot_at AT TIME ZONE 'UTC')::date AS d, total_xp, total_level
                FROM younglings.player_stats_snapshot
                WHERE LOWER(rsn) = LOWER(?) AND snapshot_at >= NOW() - (? * INTERVAL '1 day')
                ORDER BY (snapshot_at AT TIME ZONE 'UTC')::date, snapshot_at DESC
                """, rs -> new DayPoint(rs.getObject("d", LocalDate.class), rs.getLong("total_xp"), rs.getInt("total_level")), rsn, days);
    }

    /** One player's Citadel record: how many caps and visits the bot has seen, and which of the last weeks they capped. */
    public CitadelTotals citadelFor(long guildId, String rsn) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.citadelFor(guildId, rsn);
        record Row(boolean capped, LocalDate week) {}
        List<Row> rows = query("citadel totals", """
                SELECT (activity_text LIKE 'Capped at my Clan Citadel%') AS capped,
                       (date_trunc('week', (COALESCE(occurred_at, recorded_at) AT TIME ZONE 'UTC') - INTERVAL '2 days 10 minutes') + INTERVAL '2 days')::date AS week_start
                FROM younglings.player_activity
                WHERE LOWER(rsn) = LOWER(?) AND (activity_text LIKE 'Visited my Clan Citadel%' OR activity_text LIKE 'Capped at my Clan Citadel%')
                """, rs -> new Row(rs.getBoolean("capped"), rs.getObject("week_start", LocalDate.class)), rsn);

        int caps = 0, visits = 0;
        List<LocalDate> cappedWeeks = new ArrayList<>();
        for (Row row : rows) {
            if (row.capped()) {
                caps++;
                if (!cappedWeeks.contains(row.week())) cappedWeeks.add(row.week());
            } else {
                visits++;
            }
        }
        cappedWeeks.sort(null);
        return new CitadelTotals(caps, visits, cappedWeeks);
    }
}
