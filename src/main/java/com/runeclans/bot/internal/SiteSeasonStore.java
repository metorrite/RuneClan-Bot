package com.runeclans.bot.internal;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.internal.SeasonRules.Row;
import com.runeclans.bot.internal.SeasonRules.Weights;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What clan seasons (T-596) keep, by server id so a bot-less site works the same: the owner's settings (the switch, the three weights and
 * the optional season-end post) and each finished season's final standings, kept so the recap and the badges never change once a season
 * is over. The live standings are never stored: they are worked out from the XP snapshots, the clan points awards and the events, which
 * already live here.
 */
@BService
public class SiteSeasonStore {
    private static final Logger log = LoggerFactory.getLogger(SiteSeasonStore.class);

    private final ConnectionSupplier connectionSupplier;

    /** The event and RuneScape tables come first, since the reads below join them. */
    public SiteSeasonStore(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer runescape, EventRepository events) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "clan seasons", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.site_season_settings (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    weight_xp INT NOT NULL DEFAULT 1,
                    weight_points INT NOT NULL DEFAULT 1,
                    weight_events INT NOT NULL DEFAULT 1,
                    post_on BOOLEAN NOT NULL DEFAULT FALSE,
                    post_channel_id BIGINT NULL,
                    enabled_at TIMESTAMPTZ NULL,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_season_final (
                    guild_id BIGINT NOT NULL,
                    season TEXT NOT NULL,
                    finished_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    weight_xp INT NOT NULL,
                    weight_points INT NOT NULL,
                    weight_events INT NOT NULL,
                    members INT NOT NULL,
                    PRIMARY KEY (guild_id, season)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_season_standing (
                    guild_id BIGINT NOT NULL,
                    season TEXT NOT NULL,
                    place INT NOT NULL,
                    rsn TEXT NOT NULL,
                    score DOUBLE PRECISION NOT NULL,
                    xp BIGINT NOT NULL,
                    points BIGINT NOT NULL,
                    wins INT NOT NULL,
                    xp_pts INT NOT NULL,
                    points_pts INT NOT NULL,
                    wins_pts INT NOT NULL,
                    PRIMARY KEY (guild_id, season, rsn)
                );
                """,
                "CREATE INDEX IF NOT EXISTS site_season_standing_place ON younglings.site_season_standing (guild_id, season, place);"));
    }

    /** The owner's settings; {@link #OFF} until they save any. */
    public record Settings(boolean enabled, Weights weights, boolean postOn, Long postChannelId, OffsetDateTime enabledAt) {
        public static final Settings OFF = new Settings(false, Weights.EQUAL, false, null, null);
    }

    /** A finished season: when it was closed, the weights it was scored with and how many members scored. */
    public record Final(String season, OffsetDateTime finishedAt, Weights weights, int members) {}

    private Connection connection() throws SQLException {
        return connectionSupplier.getConnection();
    }

    private static RuntimeException fail(String what, SQLException e) {
        log.error("Clan seasons: could not {}", what, e);
        return new RuntimeException("Could not " + what, e);
    }

    // ---------- settings ----------

    public Settings settings(long guildId) {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.site_season_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? settingsOf(rs) : Settings.OFF;
            }
        } catch (SQLException e) {
            throw fail("read the season settings", e);
        }
    }

    private static Settings settingsOf(ResultSet rs) throws SQLException {
        long channel = rs.getLong("post_channel_id");
        Long channelId = rs.wasNull() ? null : channel;
        return new Settings(rs.getBoolean("enabled"), new Weights(rs.getInt("weight_xp"), rs.getInt("weight_points"), rs.getInt("weight_events")),
                rs.getBoolean("post_on"), channelId, rs.getObject("enabled_at", OffsetDateTime.class));
    }

    /** Saves the settings. Turning seasons on notes when (kept while they stay on), so only seasons that end after it are closed. */
    public void save(long guildId, boolean enabled, Weights w, boolean postOn, Long postChannelId, long by) {
        String sql = """
                INSERT INTO younglings.site_season_settings (guild_id, enabled, weight_xp, weight_points, weight_events, post_on, post_channel_id, enabled_at, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() END, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, weight_xp = EXCLUDED.weight_xp, weight_points = EXCLUDED.weight_points,
                    weight_events = EXCLUDED.weight_events, post_on = EXCLUDED.post_on, post_channel_id = EXCLUDED.post_channel_id,
                    enabled_at = CASE WHEN NOT EXCLUDED.enabled THEN NULL
                                      WHEN younglings.site_season_settings.enabled THEN younglings.site_season_settings.enabled_at
                                      ELSE NOW() END,
                    updated_by = EXCLUDED.updated_by, updated_at = NOW()
                """;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setBoolean(2, enabled);
            s.setInt(3, w.xp());
            s.setInt(4, w.points());
            s.setInt(5, w.events());
            s.setBoolean(6, postOn);
            if (postChannelId == null) s.setNull(7, Types.BIGINT);
            else s.setLong(7, postChannelId);
            s.setBoolean(8, enabled);
            s.setLong(9, by);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save the season settings", e);
        }
    }

    /** Every server with seasons on, and its settings (for the season-end job). */
    public Map<Long, Settings> enabled() {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.site_season_settings WHERE enabled")) {
            Map<Long, Settings> out = new HashMap<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.put(rs.getLong("guild_id"), settingsOf(rs));
            }
            return out;
        } catch (SQLException e) {
            throw fail("list the servers with seasons on", e);
        }
    }

    // ---------- finished seasons ----------

    /**
     * Keeps a finished season's final standings, once: the first call stores them and answers true; any later call (the next tick, a second
     * bot process) changes nothing and answers false. One transaction, so a season is never half kept.
     */
    public boolean finish(long guildId, String season, Weights w, List<Row> rows) {
        try (Connection c = connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO younglings.site_season_final (guild_id, season, weight_xp, weight_points, weight_events, members)
                        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """)) {
                    s.setLong(1, guildId);
                    s.setString(2, season);
                    s.setInt(3, w.xp());
                    s.setInt(4, w.points());
                    s.setInt(5, w.events());
                    s.setInt(6, rows.size());
                    inserted = s.executeUpdate();
                }
                if (inserted > 0 && !rows.isEmpty()) {
                    try (PreparedStatement s = c.prepareStatement("""
                            INSERT INTO younglings.site_season_standing (guild_id, season, place, rsn, score, xp, points, wins, xp_pts, points_pts, wins_pts)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                            """)) {
                        for (Row r : rows.subList(0, Math.min(rows.size(), SeasonRules.MAX_KEPT_ROWS))) {
                            s.setLong(1, guildId);
                            s.setString(2, season);
                            s.setInt(3, r.place());
                            s.setString(4, r.rsn());
                            s.setDouble(5, r.score());
                            s.setLong(6, r.xp());
                            s.setLong(7, r.points());
                            s.setInt(8, r.wins());
                            s.setInt(9, r.xpPts());
                            s.setInt(10, r.pointsPts());
                            s.setInt(11, r.winsPts());
                            s.addBatch();
                        }
                        s.executeBatch();
                    }
                }
                c.commit();
                return inserted > 0;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw fail("keep the season's final standings", e);
        }
    }

    /** The finished seasons, newest first. */
    public List<Final> finals(long guildId, int limit) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.site_season_final WHERE guild_id = ? ORDER BY season DESC LIMIT ?")) {
            s.setLong(1, guildId);
            s.setInt(2, limit);
            List<Final> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new Final(rs.getString("season"), rs.getObject("finished_at", OffsetDateTime.class),
                            new Weights(rs.getInt("weight_xp"), rs.getInt("weight_points"), rs.getInt("weight_events")), rs.getInt("members")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw fail("read the finished seasons", e);
        }
    }

    public Optional<Final> finalOf(long guildId, String season) {
        return finals(guildId, 400).stream().filter(f -> f.season().equals(season)).findFirst();
    }

    /** A finished season's kept standings, best first, at most {@code limit} rows. */
    public List<Row> standings(long guildId, String season, int limit) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.site_season_standing WHERE guild_id = ? AND season = ? ORDER BY place, score DESC, xp DESC, LOWER(rsn) LIMIT ?")) {
            s.setLong(1, guildId);
            s.setString(2, season);
            s.setInt(3, limit);
            List<Row> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rowOf(rs));
            }
            return out;
        } catch (SQLException e) {
            throw fail("read a season's standings", e);
        }
    }

    /** A member's badge places (top {@link SeasonRules#BADGE_PLACES}) in finished seasons, newest season first: season token to row. */
    public List<Map.Entry<String, Row>> badges(long guildId, String rsn) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT * FROM younglings.site_season_standing WHERE guild_id = ? AND LOWER(rsn) = LOWER(?) AND place <= ? ORDER BY season DESC
                     """)) {
            s.setLong(1, guildId);
            s.setString(2, rsn);
            s.setInt(3, SeasonRules.BADGE_PLACES);
            List<Map.Entry<String, Row>> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(Map.entry(rs.getString("season"), rowOf(rs)));
            }
            return out;
        } catch (SQLException e) {
            throw fail("read a member's season badges", e);
        }
    }

    private static Row rowOf(ResultSet rs) throws SQLException {
        return new Row(rs.getInt("place"), rs.getString("rsn"), rs.getDouble("score"), rs.getLong("xp"), rs.getLong("points"), rs.getInt("wins"),
                rs.getInt("xp_pts"), rs.getInt("points_pts"), rs.getInt("wins_pts"));
    }

    // ---------- event wins ----------

    /**
     * The server's old bot-table events that finished inside {@code [from, to)}: status DONE, finished at its end time (the wrap-up record is the hub's
     * since T-941). Cancelled events never count.
     */
    public List<Long> eventsFinishedBetween(long guildId, OffsetDateTime from, OffsetDateTime to) {
        String sql = """
                SELECT e.id FROM younglings.event e
                WHERE e.guild_id = ? AND e.status = 'DONE' AND COALESCE(e.ends_at, e.updated_at) >= ? AND COALESCE(e.ends_at, e.updated_at) < ?
                ORDER BY e.id
                """;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setObject(2, from);
            s.setObject(3, to);
            List<Long> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return out;
        } catch (SQLException e) {
            throw fail("list the season's finished events", e);
        }
    }

    /**
     * The RuneScape name each of these Discord accounts has linked in this server, where that name is on the clan's roster now (the primary
     * link first, then the oldest). Accounts with no such name are left out: their wins can't be shown against a member.
     */
    public Map<Long, String> rosterNames(long guildId, List<Long> userIds) {
        Map<Long, String> out = new HashMap<>();
        if (userIds.isEmpty()) return out;
        String sql = """
                SELECT DISTINCT ON (l.discord_user_id) l.discord_user_id, m.rsn
                FROM younglings.player_link l
                JOIN younglings.clan_member m ON m.guild_id = l.guild_id AND LOWER(m.rsn) = LOWER(l.rsn) AND m.active
                WHERE l.guild_id = ? AND l.discord_user_id = ANY (?)
                ORDER BY l.discord_user_id, l.is_primary DESC, l.verified_at ASC
                """;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setArray(2, c.createArrayOf("bigint", userIds.toArray()));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.put(rs.getLong(1), rs.getString(2));
            }
            return out;
        } catch (SQLException e) {
            throw fail("find the event winners' names", e);
        }
    }

    /** Drops everything with the server's deleted website. */
    public void forget(long guildId) {
        try (Connection c = connection()) {
            for (String table : List.of("site_season_settings", "site_season_final", "site_season_standing")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                    s.setLong(1, guildId);
                    s.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw fail("forget the server's seasons", e);
        }
    }
}
