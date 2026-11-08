package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import com.runeclans.bot.runescape.PlayerLinkRepository;
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
    public static SiteStatsRepository siteStatsRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer, StatsBackends backends,
                                                          com.runeclans.bot.runescape.ClanPointsRepository clanPoints) {
        SiteStatsRepository repository = new SiteStatsRepository(connectionSupplier, initializer);
        repository.stats = backends;
        repository.clanPoints = clanPoints;
        return repository;
    }

    /** Clan points are the hub's (T-978): totals, flags and award history come from there. Null (tests without it): no points. */
    private volatile com.runeclans.bot.runescape.ClanPointsRepository clanPoints;

    /** Reads clan points through {@code points} (tests). */
    public SiteStatsRepository withPoints(com.runeclans.bot.runescape.ClanPointsRepository points) {
        this.clanPoints = points;
        return this;
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
        List<MemberRow> rows = membersFromRoster(guildId);
        // the roster (rank, points, verified) is the bot's; the polled levels are the hub's
        Map<String, PlayerLinkRepository.StatsSnapshotRow> latest = stats.backend().membersLatest(guildId);
        List<MemberRow> out = new ArrayList<>(rows.size());
        for (MemberRow m : rows) {
            PlayerLinkRepository.StatsSnapshotRow s = latest.get(m.rsn().strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT));
            out.add(new MemberRow(m.rsn(), m.clanRank(), m.rankOrder(), m.totalXp(), m.kills(), m.clanJoinedAt(), m.firstSeen(), m.points(), m.promotionNeeded(), m.verified(),
                    s == null ? null : s.totalLevel(), s == null ? null : s.combatLevel(), s == null ? null : s.snapshotAt()));
        }
        return out;
    }

    /** The roster rows, without levels (those are the hub's); points and flags are the hub's too, rank order the standard ladder's. */
    private List<MemberRow> membersFromRoster(long guildId) {
        Map<String, com.runeclans.bot.runescape.ClanPointsRepository.MemberPointsRow> points = clanPoints == null ? Map.of() : clanPoints.totals(guildId);
        return query("site members", """
                SELECT m.rsn, m.clan_rank, m.total_xp, m.kills, m.clan_joined_at, m.first_seen,
                       EXISTS (SELECT 1 FROM younglings.player_link l WHERE l.guild_id = m.guild_id AND LOWER(l.rsn) = LOWER(m.rsn)) AS verified
                FROM younglings.clan_member m
                WHERE m.guild_id = ? AND m.active
                """, rs -> {
            String rsn = rs.getString("rsn");
            var p = points.get(com.runeclans.bot.runescape.ClanPointsRepository.key(rsn));
            return new MemberRow(rsn, rs.getString("clan_rank"), com.runeclans.bot.runescape.ClanPointsRepository.standardOrder(rs.getString("clan_rank")), rs.getLong("total_xp"),
                    rs.getLong("kills"), rs.getObject("clan_joined_at", LocalDate.class), rs.getObject("first_seen", OffsetDateTime.class),
                    p == null ? 0 : p.totalPoints(), p != null && p.promotionNeeded(), rs.getBoolean("verified"), null, null, null);
        }, guildId);
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
        return stats.backend().xpGainsBetween(guildId, from, to);
    }

    /** The first moment the bot has any XP snapshot for the clan — where the month-by-month leaderboards can start. */
    public OffsetDateTime firstSnapshotAt(long guildId) {
        return stats.backend().firstSnapshotAt(guildId);
    }

    /** Members ranked by Citadel weeks capped inside {@code [from, to)}. */
    public List<Capper> cappersBetween(long guildId, OffsetDateTime from, OffsetDateTime to, int limit) {
        return stats.backend().cappersBetween(guildId, from, to, limit);
    }

    /** A member's clan-point awards, newest first (the hub's, T-978). */
    public List<Award> awards(long guildId, String rsn, int limit) {
        if (clanPoints == null) return List.of();
        return clanPoints.awards(guildId, rsn, limit).stream().map(a -> new Award(a.type(), a.points(), a.awardedFor())).toList();
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
        return stats.backend().skillGainsBetween(guildId, rsn, from, to);
    }

    /** XP gained on each UTC day inside the window (one member, or the whole clan summed), oldest first. */
    public List<DayXp> dailyXpBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        return stats.backend().dailyXpBetween(guildId, rsn, from, to);
    }

    /** Adventure-log entries first seen inside the window, for one member or everyone. */
    public List<ActivityRow> activitiesBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        return stats.backend().activitiesBetween(guildId, rsn, from, to);
    }

    /** Clan points awarded for the UTC days inside the window, per member (or just one); the hub's (T-978). */
    public List<PointsEarned> pointsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        if (clanPoints == null) return List.of();
        LocalDate start = from.atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate(), end = to.atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
        if (!end.isAfter(start)) return List.of();
        return clanPoints.earned(guildId, start, end, rsn, false).stream().map(e -> new PointsEarned(e.rsn(), e.points())).toList();
    }

    /**
     * {@link #pointsBetween}, but throws when the hub didn't answer, for a caller that keeps what it reads (a season's close, which is
     * then tried again on its next run instead of keeping zeros).
     */
    public List<PointsEarned> pointsBetweenOrThrow(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        if (clanPoints == null) return List.of();
        LocalDate start = from.atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate(), end = to.atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
        if (!end.isAfter(start)) return List.of();
        return clanPoints.earnedIfKnown(guildId, start, end, rsn, false)
                .orElseThrow(() -> new IllegalStateException("RuneClans didn't answer for the clan points of server " + guildId))
                .stream().map(e -> new PointsEarned(e.rsn(), e.points())).toList();
    }

    /** Clan points awarded per member per day from {@code from} (a UTC date) on, for the leaderboard race's month of clan points (T-710); the hub's. */
    public List<PointsDay> pointsByDay(long guildId, LocalDate from) {
        if (clanPoints == null) return List.of();
        return clanPoints.earned(guildId, from, null, null, true).stream().map(e -> new PointsDay(e.rsn(), e.day(), e.points())).toList();
    }

    /** A member's total level at the start and end of the window ({@code null} if they have no snapshot there). */
    public Integer[] totalLevelsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        return stats.backend().totalLevelsBetween(guildId, rsn, from, to);
    }


    // ---------- activity, records, history ----------

    /** The newest adventure-log entries across active members, as the bot first saw them. */
    public List<ActivityRow> recentActivities(long guildId, int limit) {
        return stats.backend().recentActivities(guildId, limit);
    }

    /** Every active member's activities whose text starts with {@code prefix} — for the PvM and drop tallies. */
    public List<ActivityRow> activitiesStartingWith(long guildId, String prefix) {
        return stats.backend().activitiesStartingWith(guildId, prefix);
    }

    /** The biggest single-day XP gains anyone has had, from the daily snapshot history. */
    public List<BigDay> biggestDays(long guildId, int limit) {
        return stats.backend().biggestDays(guildId, limit);
    }

    /** Members with at least one 200M skill, by how many they have, from each member's newest snapshot. */
    public List<ClubMember> twoHundredMillionClub(long guildId, int limit) {
        return stats.backend().twoHundredMillionClub(guildId, limit);
    }

    /** Which Citadel weeks (Wednesday starts) each active member visited or capped in the last {@code weeks} weeks. */
    public List<CapWeek> citadelGrid(long guildId, int weeks) {
        return stats.backend().citadelGrid(guildId, weeks);
    }

    /** Every Citadel week (as a week start) in which a member capped — the streak calculator's input. */
    public List<CapWeek> allCapWeeks(long guildId) {
        return stats.backend().allCapWeeks(guildId);
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
        return stats.backend().citadelByWeek(guildId, weeks);
    }

    /** The members who have capped the most Citadel weeks. */
    public List<Capper> topCappers(long guildId, int limit) {
        return stats.backend().topCappers(guildId, limit);
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
        return stats.backend().skillLeaders(guildId, perSkill);
    }

    /** One point per UTC day (that day's last snapshot) for the last {@code days} days, oldest first. */
    public List<DayPoint> dailyHistory(long guildId, String rsn, int days) {
        return stats.backend().dailyHistory(guildId, rsn, days);
    }

    /** One player's Citadel record: how many caps and visits the bot has seen, and which of the last weeks they capped. */
    public CitadelTotals citadelFor(long guildId, String rsn) {
        return stats.backend().citadelFor(guildId, rsn);
    }
}
