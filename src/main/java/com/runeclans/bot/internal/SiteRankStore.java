package com.runeclans.bot.internal;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.internal.RankAdvisorRules.Due;
import com.runeclans.bot.internal.RankAdvisorRules.Hold;
import com.runeclans.bot.internal.RankAdvisorRules.Promoted;
import com.runeclans.bot.internal.RankAdvisorRules.Rule;
import com.runeclans.bot.internal.RankAdvisorRules.Tick;
import com.runeclans.bot.internal.RankAdvisorRules.Turn;
import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the rank-up advisor (T-594) reads and keeps, by server id (so a bot-less site works the same): the owner's rules per rank, the
 * weekly congrats post's settings, the staff's ticks, the Pending review and Ignored holds, and the weekly update (who was due, who each week
 * congratulated, the auto post); and, read only, each roster member's rank, XP since that rank, join date and clan points.
 */
@BService
public class SiteRankStore {
    private static final Logger log = LoggerFactory.getLogger(SiteRankStore.class);

    private final ConnectionSupplier connectionSupplier;
    /** Clan points are the hub's (T-978). */
    private final com.runeclans.bot.runescape.ClanPointsRepository clanPoints;

    public SiteRankStore(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer runescape, com.runeclans.bot.runescape.ClanPointsRepository clanPoints) {
        this.connectionSupplier = connectionSupplier;
        this.clanPoints = clanPoints;
        SchemaBootstrapper.run(connectionSupplier, log, "rank-up advisor", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.site_rank_rule (
                    guild_id BIGINT NOT NULL,
                    rank_name TEXT NOT NULL,
                    min_xp BIGINT NULL,
                    min_days INT NULL,
                    min_caps INT NULL,
                    min_points BIGINT NULL,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, rank_name)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_rank_settings (
                    guild_id BIGINT PRIMARY KEY,
                    congrats_on BOOLEAN NOT NULL DEFAULT FALSE,
                    congrats_channel_id BIGINT NULL,
                    congrats_text TEXT NULL,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_rank_tick (
                    guild_id BIGINT NOT NULL,
                    rsn_key TEXT NOT NULL,
                    rank_name TEXT NOT NULL,
                    ticked_by BIGINT NOT NULL,
                    ticked_by_name TEXT NULL,
                    ticked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, rsn_key, rank_name)
                );
                """,
                "ALTER TABLE younglings.site_rank_rule ADD COLUMN IF NOT EXISTS staff_check TEXT NULL;",
                """
                CREATE TABLE IF NOT EXISTS younglings.site_rank_hold (
                    guild_id BIGINT NOT NULL,
                    rsn_key TEXT NOT NULL,
                    rsn TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    held_by BIGINT NOT NULL,
                    held_by_name TEXT NULL,
                    held_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, rsn_key)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_rank_due (
                    guild_id BIGINT NOT NULL,
                    rsn_key TEXT NOT NULL,
                    rank_name TEXT NOT NULL,
                    rsn TEXT NOT NULL,
                    from_rank TEXT NOT NULL,
                    due_since DATE NOT NULL,
                    PRIMARY KEY (guild_id, rsn_key, rank_name)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_rank_week (
                    guild_id BIGINT NOT NULL,
                    week_start DATE NOT NULL,
                    turned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    job_id BIGINT NULL,
                    posted_at TIMESTAMPTZ NULL,
                    PRIMARY KEY (guild_id, week_start)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_rank_promoted (
                    guild_id BIGINT NOT NULL,
                    week_start DATE NOT NULL,
                    rsn_key TEXT NOT NULL,
                    rsn TEXT NOT NULL,
                    from_rank TEXT NOT NULL,
                    to_rank TEXT NOT NULL,
                    discord_name TEXT NULL,
                    PRIMARY KEY (guild_id, week_start, rsn_key)
                );
                """));
    }

    /**
     * The weekly congrats post's settings (Drake's T-594 answer): auto post off until the owner turns it on and picks a channel; the text is
     * the owner's template. With auto post off the page offers the message to copy.
     */
    public record Congrats(boolean on, Long channelId, String text) {
        public static final Congrats OFF = new Congrats(false, null, null);
    }

    private Connection connection() throws SQLException {
        return connectionSupplier.getConnection();
    }

    private static RuntimeException fail(String what, SQLException e) {
        log.error("Rank-up advisor: could not {}", what, e);
        return new RuntimeException("Could not " + what, e);
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    // ---------- the roster ----------

    /** A roster member's facts before the caps (which come from the Citadel grid). */
    public record Row(String rsn, String rank, Long xpGained, OffsetDateTime rankSince, OffsetDateTime joined, long points) {}

    /** Every active roster member with their XP since their current rank (null before the bot has a starting point) and clan points (the hub's; 0 when it can't say). */
    public List<Row> roster(long guildId) {
        Map<String, com.runeclans.bot.runescape.ClanPointsRepository.MemberPointsRow> points = clanPoints.totals(guildId);
        String sql = """
                SELECT m.rsn, m.clan_rank, m.first_seen, m.clan_joined_at, m.rank_since,
                       CASE WHEN m.rank_xp IS NULL THEN NULL ELSE GREATEST(m.total_xp - m.rank_xp, 0) END AS xp_gained
                FROM younglings.clan_member m
                WHERE m.guild_id = ? AND m.active
                """;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            List<Row> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    LocalDate joinedDay = rs.getObject("clan_joined_at", LocalDate.class);
                    OffsetDateTime joined = joinedDay != null ? joinedDay.atStartOfDay().atOffset(ZoneOffset.UTC) : rs.getObject("first_seen", OffsetDateTime.class);
                    var p = points.get(com.runeclans.bot.runescape.ClanPointsRepository.key(rs.getString("rsn")));
                    out.add(new Row(rs.getString("rsn"), rs.getString("clan_rank"), longOrNull(rs, "xp_gained"), rs.getObject("rank_since", OffsetDateTime.class),
                            joined, p == null ? 0 : p.totalPoints()));
                }
            }
            return out;
        } catch (SQLException e) {
            throw fail("read the roster", e);
        }
    }

    // ---------- rules ----------

    /** The site's rules by rank name, in ladder order. */
    public Map<String, Rule> rules(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT rank_name, min_xp, min_days, min_caps, min_points, staff_check FROM younglings.site_rank_rule WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            Map<String, Rule> found = new LinkedHashMap<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    String rank = RankAdvisorRules.canonical(rs.getString("rank_name"));
                    if (rank == null) continue;
                    found.put(rank, new Rule(rank, longOrNull(rs, "min_xp"), intOrNull(rs, "min_days"), intOrNull(rs, "min_caps"), longOrNull(rs, "min_points"),
                            rs.getString("staff_check")));
                }
            }
            Map<String, Rule> out = new LinkedHashMap<>();
            for (String rank : RankAdvisorRules.LADDER) if (found.containsKey(rank)) out.put(rank, found.get(rank));
            return out;
        } catch (SQLException e) {
            throw fail("read the rank rules", e);
        }
    }

    /** Replaces every rule at once (one save of the editor): ranks left out, or with nothing set (no number, no staff check), have no rule. */
    public void saveRules(long guildId, List<Rule> rules, long by) {
        try (Connection c = connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement clear = c.prepareStatement("DELETE FROM younglings.site_rank_rule WHERE guild_id = ?");
                 PreparedStatement add = c.prepareStatement("""
                         INSERT INTO younglings.site_rank_rule (guild_id, rank_name, min_xp, min_days, min_caps, min_points, staff_check, updated_by,
                             updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                         """)) {
                clear.setLong(1, guildId);
                clear.executeUpdate();
                for (Rule r : rules) {
                    if (r.blank()) continue;
                    add.setLong(1, guildId);
                    add.setString(2, r.rank());
                    setLong(add, 3, r.minXp());
                    setInt(add, 4, r.minDays());
                    setInt(add, 5, r.minCaps());
                    setLong(add, 6, r.minPoints());
                    add.setString(7, r.staffCheck());
                    add.setLong(8, by);
                    add.addBatch();
                }
                add.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw fail("save the rank rules", e);
        }
    }

    private static void setLong(PreparedStatement s, int i, Long v) throws SQLException {
        if (v == null) s.setNull(i, Types.BIGINT);
        else s.setLong(i, v);
    }

    private static void setInt(PreparedStatement s, int i, Integer v) throws SQLException {
        if (v == null) s.setNull(i, Types.INTEGER);
        else s.setInt(i, v);
    }

    // ---------- the congrats post ----------

    public Congrats congrats(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT congrats_on, congrats_channel_id, congrats_text FROM younglings.site_rank_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new Congrats(rs.getBoolean("congrats_on"), longOrNull(rs, "congrats_channel_id"), rs.getString("congrats_text")) : Congrats.OFF;
            }
        } catch (SQLException e) {
            throw fail("read the congrats settings", e);
        }
    }

    public void saveCongrats(long guildId, Congrats congrats, long by) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.site_rank_settings (guild_id, congrats_on, congrats_channel_id, congrats_text, updated_by, updated_at)
                     VALUES (?, ?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id) DO UPDATE SET congrats_on = EXCLUDED.congrats_on, congrats_channel_id = EXCLUDED.congrats_channel_id,
                         congrats_text = EXCLUDED.congrats_text, updated_by = EXCLUDED.updated_by, updated_at = NOW()
                     """)) {
            s.setLong(1, guildId);
            s.setBoolean(2, congrats.on());
            setLong(s, 3, congrats.channelId());
            s.setString(4, congrats.text());
            s.setLong(5, by);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save the congrats settings", e);
        }
    }

    // ---------- ticks ----------

    /** Ticks of the last {@link RankAdvisorRules#TICK_KEEP_DAYS} days, newest first. */
    public List<Tick> ticks(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT rsn_key, rank_name, ticked_by, ticked_by_name, ticked_at FROM younglings.site_rank_tick
                     WHERE guild_id = ? AND ticked_at >= NOW() - (? * INTERVAL '1 day') ORDER BY ticked_at DESC
                     """)) {
            s.setLong(1, guildId);
            s.setInt(2, RankAdvisorRules.TICK_KEEP_DAYS);
            List<Tick> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new Tick(rs.getString("rsn_key"), rs.getString("rank_name"), rs.getLong("ticked_by"), rs.getString("ticked_by_name"),
                            rs.getObject("ticked_at", OffsetDateTime.class)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw fail("read the ticks", e);
        }
    }

    /**
     * Ticks a member off for a rank (again: who and when move to now), and drops ticks long past keeping. True when the tick is new, false
     * when the member was already ticked off for that rank (a second click, or two staff at once): the congrats post goes out once.
     */
    public boolean tick(long guildId, String rsnKey, String rank, long by, String byName) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.site_rank_tick (guild_id, rsn_key, rank_name, ticked_by, ticked_by_name, ticked_at) VALUES (?, ?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id, rsn_key, rank_name) DO UPDATE SET ticked_by = EXCLUDED.ticked_by, ticked_by_name = EXCLUDED.ticked_by_name,
                         ticked_at = NOW()
                     RETURNING (xmax = 0) AS inserted
                     """);
             PreparedStatement prune = c.prepareStatement(
                     "DELETE FROM younglings.site_rank_tick WHERE guild_id = ? AND ticked_at < NOW() - (? * INTERVAL '1 day')")) {
            s.setLong(1, guildId);
            s.setString(2, rsnKey);
            s.setString(3, rank);
            s.setLong(4, by);
            s.setString(5, byName);
            boolean inserted;
            try (ResultSet rs = s.executeQuery()) {
                inserted = rs.next() && rs.getBoolean("inserted");
            }
            prune.setLong(1, guildId);
            prune.setInt(2, RankAdvisorRules.TICK_KEEP_DAYS);
            prune.executeUpdate();
            return inserted;
        } catch (SQLException e) {
            throw fail("save a tick", e);
        }
    }

    public void untick(long guildId, String rsnKey, String rank) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.site_rank_tick WHERE guild_id = ? AND rsn_key = ? AND rank_name = ?")) {
            s.setLong(1, guildId);
            s.setString(2, rsnKey);
            s.setString(3, rank);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("clear a tick", e);
        }
    }

    // ---------- holds: Pending review and Ignore indefinitely ----------

    public List<Hold> holds(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT rsn_key, rsn, kind, held_by, held_by_name, held_at FROM younglings.site_rank_hold WHERE guild_id = ? ORDER BY held_at")) {
            s.setLong(1, guildId);
            List<Hold> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new Hold(rs.getString("rsn_key"), rs.getString("rsn"), rs.getString("kind"), rs.getLong("held_by"),
                            rs.getString("held_by_name"), rs.getObject("held_at", OffsetDateTime.class)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw fail("read the pending and ignored lists", e);
        }
    }

    /**
     * Puts a member on Pending review or Ignored (moving them from the other one), or takes them off both: {@code kind} null. Ignoring also
     * drops them from the weekly update's due list, so an alt is never congratulated.
     */
    public void hold(long guildId, String rsnKey, String rsn, String kind, long by, String byName) {
        try (Connection c = connection()) {
            if (kind == null) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.site_rank_hold WHERE guild_id = ? AND rsn_key = ?")) {
                    s.setLong(1, guildId);
                    s.setString(2, rsnKey);
                    s.executeUpdate();
                }
                return;
            }
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.site_rank_hold (guild_id, rsn_key, rsn, kind, held_by, held_by_name, held_at) VALUES (?, ?, ?, ?, ?, ?, NOW())
                    ON CONFLICT (guild_id, rsn_key) DO UPDATE SET rsn = EXCLUDED.rsn, kind = EXCLUDED.kind, held_by = EXCLUDED.held_by,
                        held_by_name = EXCLUDED.held_by_name, held_at = NOW()
                    """)) {
                s.setLong(1, guildId);
                s.setString(2, rsnKey);
                s.setString(3, rsn);
                s.setString(4, kind);
                s.setLong(5, by);
                s.setString(6, byName);
                s.executeUpdate();
            }
            if (RankAdvisorRules.IGNORED.equals(kind)) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.site_rank_due WHERE guild_id = ? AND rsn_key = ?")) {
                    s.setLong(1, guildId);
                    s.setString(2, rsnKey);
                    s.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw fail("save the pending and ignored lists", e);
        }
    }

    // ---------- the weekly update ----------

    /** Servers with rank rules: the weekly update's sweep goes through these. */
    public List<Long> serversWithRules() {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("SELECT DISTINCT guild_id FROM younglings.site_rank_rule")) {
            List<Long> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return out;
        } catch (SQLException e) {
            throw fail("list the servers with rank rules", e);
        }
    }

    /** Notes everyone due now (on the ready or pending list, or just ticked) for the weekly update; someone already due keeps their first week. */
    public void noteDue(long guildId, List<Due> due) {
        if (due.isEmpty()) return;
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.site_rank_due (guild_id, rsn_key, rank_name, rsn, from_rank, due_since) VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT (guild_id, rsn_key, rank_name) DO NOTHING
                     """)) {
            for (Due d : due) {
                s.setLong(1, guildId);
                s.setString(2, d.rsnKey());
                s.setString(3, d.target());
                s.setString(4, d.rsn());
                s.setString(5, d.rank());
                s.setObject(6, d.since());
                s.addBatch();
            }
            s.executeBatch();
        } catch (SQLException e) {
            throw fail("note who is due", e);
        }
    }

    public List<Due> due(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT rsn_key, rsn, from_rank, rank_name, due_since FROM younglings.site_rank_due WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            List<Due> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new Due(rs.getString("rsn_key"), rs.getString("rsn"), rs.getString("from_rank"), rs.getString("rank_name"),
                            rs.getObject("due_since", LocalDate.class)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw fail("read who is due", e);
        }
    }

    /** Whether the server's week has turned over already (the update for {@code week} exists). */
    public boolean turned(long guildId, LocalDate week) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM younglings.site_rank_week WHERE guild_id = ? AND week_start = ?")) {
            s.setLong(1, guildId);
            s.setObject(2, week);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw fail("read the weekly update", e);
        }
    }

    /**
     * Turns the server's week over, once: saves who is congratulated for {@code week} and drops the due entries the turn settled, in one
     * transaction. False when the week had already turned (a second bot process, or the page and the sweep at once): nothing is written.
     */
    public boolean turn(long guildId, LocalDate week, Turn turn) {
        try (Connection c = connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement claim = c.prepareStatement(
                    "INSERT INTO younglings.site_rank_week (guild_id, week_start, turned_at) VALUES (?, ?, NOW()) ON CONFLICT DO NOTHING");
                 PreparedStatement add = c.prepareStatement("""
                         INSERT INTO younglings.site_rank_promoted (guild_id, week_start, rsn_key, rsn, from_rank, to_rank, discord_name)
                         VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                         """);
                 PreparedStatement drop = c.prepareStatement("DELETE FROM younglings.site_rank_due WHERE guild_id = ? AND rsn_key = ? AND rank_name = ?");
                 PreparedStatement prune = c.prepareStatement("DELETE FROM younglings.site_rank_promoted WHERE guild_id = ? AND week_start < ?");
                 PreparedStatement pruneWeeks = c.prepareStatement("DELETE FROM younglings.site_rank_week WHERE guild_id = ? AND week_start < ?")) {
                claim.setLong(1, guildId);
                claim.setObject(2, week);
                if (claim.executeUpdate() == 0) {
                    c.rollback();
                    return false;
                }
                for (Promoted p : turn.promoted()) {
                    add.setLong(1, guildId);
                    add.setObject(2, week);
                    add.setString(3, p.rsnKey());
                    add.setString(4, p.rsn());
                    add.setString(5, p.fromRank());
                    add.setString(6, p.toRank());
                    add.setString(7, p.discordName());
                    add.addBatch();
                }
                add.executeBatch();
                for (Due d : turn.drop()) {
                    drop.setLong(1, guildId);
                    drop.setString(2, d.rsnKey());
                    drop.setString(3, d.target());
                    drop.addBatch();
                }
                drop.executeBatch();
                // keep a year of weekly updates
                LocalDate keepFrom = week.minusWeeks(52);
                prune.setLong(1, guildId);
                prune.setObject(2, keepFrom);
                prune.executeUpdate();
                pruneWeeks.setLong(1, guildId);
                pruneWeeks.setObject(2, keepFrom);
                pruneWeeks.executeUpdate();
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw fail("turn the promotion week over", e);
        }
    }

    /** A turned week: who was congratulated, and the auto post's Discord job once queued. */
    public record Week(LocalDate start, OffsetDateTime turnedAt, Long jobId, OffsetDateTime postedAt, List<Promoted> promoted) {}

    /** The latest turned week, or null before the first turn. */
    public Week latestWeek(long guildId) {
        try (Connection c = connection();
             PreparedStatement w = c.prepareStatement("""
                     SELECT week_start, turned_at, job_id, posted_at FROM younglings.site_rank_week WHERE guild_id = ? ORDER BY week_start DESC LIMIT 1
                     """);
             PreparedStatement p = c.prepareStatement("""
                     SELECT rsn_key, rsn, from_rank, to_rank, discord_name FROM younglings.site_rank_promoted WHERE guild_id = ? AND week_start = ?
                     """)) {
            w.setLong(1, guildId);
            LocalDate start;
            OffsetDateTime turnedAt;
            Long jobId;
            OffsetDateTime postedAt;
            try (ResultSet rs = w.executeQuery()) {
                if (!rs.next()) return null;
                start = rs.getObject("week_start", LocalDate.class);
                turnedAt = rs.getObject("turned_at", OffsetDateTime.class);
                jobId = longOrNull(rs, "job_id");
                postedAt = rs.getObject("posted_at", OffsetDateTime.class);
            }
            p.setLong(1, guildId);
            p.setObject(2, start);
            List<Promoted> promoted = new ArrayList<>();
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    promoted.add(new Promoted(rs.getString("rsn_key"), rs.getString("rsn"), rs.getString("from_rank"), rs.getString("to_rank"),
                            rs.getString("discord_name")));
                }
            }
            promoted.sort(java.util.Comparator.comparingInt((Promoted x) -> RankAdvisorRules.indexOf(x.toRank())).reversed()
                    .thenComparing(Promoted::rsnKey));
            return new Week(start, turnedAt, jobId, postedAt, promoted);
        } catch (SQLException e) {
            throw fail("read the weekly update", e);
        }
    }

    /**
     * Claims the week's auto post, once: false when it was already claimed (a second bot process, or an earlier run), so a week is never posted
     * twice. Claimed before the job is queued: a failure in between loses the post rather than doubling it.
     */
    public boolean claimPost(long guildId, LocalDate week) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE younglings.site_rank_week SET posted_at = NOW() WHERE guild_id = ? AND week_start = ? AND posted_at IS NULL")) {
            s.setLong(1, guildId);
            s.setObject(2, week);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("claim the weekly post", e);
        }
    }

    /** The queued post's Discord job, for the page to show. */
    public void saveJob(long guildId, LocalDate week, long jobId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.site_rank_week SET job_id = ? WHERE guild_id = ? AND week_start = ?")) {
            s.setLong(1, jobId);
            s.setLong(2, guildId);
            s.setObject(3, week);
            s.executeUpdate();
        } catch (SQLException e) {
            log.warn("Rank-up advisor: could not save the weekly post's job for server {}", guildId, e);
        }
    }

    /** Drops the advisor's rules, settings and ticks with the server's deleted website. */
    public void forget(long guildId) {
        try (Connection c = connection()) {
            for (String table : List.of("site_rank_rule", "site_rank_settings", "site_rank_tick", "site_rank_hold", "site_rank_due", "site_rank_promoted",
                    "site_rank_week")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                    s.setLong(1, guildId);
                    s.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warn("Rank-up advisor: could not forget server {}", guildId, e);
        }
    }
}
