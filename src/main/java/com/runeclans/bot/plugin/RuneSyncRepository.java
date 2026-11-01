package com.runeclans.bot.plugin;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What RuneSync needs stored beside the plugin events (RUNESYNC-DESIGN.md sections 8 and 10, ticket T-202):
 * <ul>
 *   <li>{@code plugin_client_status}: per member, the client that last sent a batch, when, and that batch's result, for the profile's
 *       device card;</li>
 *   <li>{@code plugin_diagnostics}: redacted snapshots a member sent from RuneSync's panel for a bug report, under a short id, kept 14
 *       days;</li>
 *   <li>the member's own most recent events per type, read from {@code plugin_event};</li>
 *   <li>{@code plugin_guild_settings}: a clan's plugin switches, set on the dashboard's Clan plugin page (T-206: "Allow the in-game clan
 *       panel", off by default).</li>
 * </ul>
 */
@BService
public class RuneSyncRepository {
    private static final Logger log = LoggerFactory.getLogger(RuneSyncRepository.class);

    /** The member's client: what it last said it was, when it last asked for its settings or sent, whether its local pause is on, and its last batch. */
    public record ClientStatus(String client, Instant lastSeen, boolean paused, Instant lastBatchAt, int accepted, int duplicates, int rejected) {}

    public record Diagnostic(String id, long discordUserId, Instant createdAt, Instant expiresAt, String json) {}

    public record RecentEvent(String type, Instant occurredAt, String sourceKind, String sourceName, String data) {}

    private final ConnectionSupplier connectionSupplier;

    public RuneSyncRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "runesync", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_client_status (
                    discord_user_id BIGINT PRIMARY KEY,
                    client TEXT NULL,
                    last_seen TIMESTAMPTZ NOT NULL,
                    paused BOOLEAN NOT NULL DEFAULT FALSE,
                    last_batch_at TIMESTAMPTZ NULL,
                    accepted INT NOT NULL DEFAULT 0,
                    duplicates INT NOT NULL DEFAULT 0,
                    rejected INT NOT NULL DEFAULT 0
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_diagnostics (
                    id TEXT PRIMARY KEY,
                    discord_user_id BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL,
                    snapshot JSONB NOT NULL
                );
                """,
                // a dev database may hold the table from before these two columns
                "ALTER TABLE younglings.plugin_client_status ADD COLUMN IF NOT EXISTS paused BOOLEAN NOT NULL DEFAULT FALSE;",
                "ALTER TABLE younglings.plugin_client_status ADD COLUMN IF NOT EXISTS last_batch_at TIMESTAMPTZ NULL;",
                // the RuneLink name of the character that checked in last (T-586, "In game now" on the clan site); null for a token client
                "ALTER TABLE younglings.plugin_client_status ADD COLUMN IF NOT EXISTS rsn TEXT NULL;",
                "CREATE INDEX IF NOT EXISTS plugin_diagnostics_user ON younglings.plugin_diagnostics (discord_user_id, created_at DESC);",
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_guild_settings (
                    guild_id BIGINT PRIMARY KEY,
                    clan_panel BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_at TIMESTAMPTZ NOT NULL,
                    updated_by BIGINT NULL
                );
                """,
                // where new competitions count XP from (T-537); null is the built-in RuneMetrics
                "ALTER TABLE younglings.plugin_guild_settings ADD COLUMN IF NOT EXISTS competition_xp_source TEXT NULL;"));
    }

    // ---------- the member's client ----------

    /** A batch came in: its client, time and result. */
    public void recordBatch(long userId, String client, int accepted, int duplicates, int rejected, Instant at) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.plugin_client_status (discord_user_id, client, last_seen, last_batch_at, accepted, duplicates, rejected) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (discord_user_id) DO UPDATE SET client = EXCLUDED.client, last_seen = EXCLUDED.last_seen, last_batch_at = EXCLUDED.last_batch_at,
                    accepted = EXCLUDED.accepted, duplicates = EXCLUDED.duplicates, rejected = EXCLUDED.rejected""")) {
            s.setLong(1, userId);
            s.setString(2, client);
            s.setTimestamp(3, Timestamp.from(at));
            s.setTimestamp(4, Timestamp.from(at));
            s.setInt(5, accepted);
            s.setInt(6, duplicates);
            s.setInt(7, rejected);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the plugin client status", e);
        }
    }

    /** The client asked for its settings: who it is and whether its local pause is on (the last batch is left as it was). */
    public void recordCheckIn(long userId, String client, boolean paused, Instant at) {
        recordCheckIn(userId, client, null, paused, at);
    }

    /**
     * {@link #recordCheckIn(long, String, boolean, Instant)} plus the RuneLink name of the character playing ({@code rsn}, null when the client
     * signed in another way: the name seen before is then kept).
     */
    public void recordCheckIn(long userId, String client, String rsn, boolean paused, Instant at) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.plugin_client_status (discord_user_id, client, last_seen, paused, rsn) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (discord_user_id) DO UPDATE SET client = COALESCE(EXCLUDED.client, plugin_client_status.client), last_seen = EXCLUDED.last_seen,
                    paused = EXCLUDED.paused, rsn = COALESCE(EXCLUDED.rsn, plugin_client_status.rsn)""")) {
            s.setLong(1, userId);
            s.setString(2, client);
            s.setTimestamp(3, Timestamp.from(at));
            s.setBoolean(4, paused);
            s.setString(5, rsn == null || rsn.isBlank() ? null : rsn.strip());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the plugin client check-in", e);
        }
    }

    public Optional<ClientStatus> clientStatus(long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.plugin_client_status WHERE discord_user_id = ?")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Timestamp batch = rs.getTimestamp("last_batch_at");
                return Optional.of(new ClientStatus(rs.getString("client"), rs.getTimestamp("last_seen").toInstant(), rs.getBoolean("paused"),
                        batch == null ? null : batch.toInstant(), rs.getInt("accepted"), rs.getInt("duplicates"), rs.getInt("rejected")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the plugin client status", e);
        }
    }

    /** A member whose RuneSync checked in recently and who switched on the Presence category (T-586); {@code rsn} is their RuneLink name, or null. */
    public record Present(long discordUserId, Instant lastSeen, String rsn) {}

    /**
     * T-586, "In game now": everyone whose RuneSync checked in after {@code since}, who switched on the Presence category ({@code plugin_consent},
     * their own choice, off by default) and who hasn't paused RuneSync in the game. Newest check-in first. Logging out sends nothing, so a
     * member drops out once their last check-in is older than {@code since}.
     */
    public List<Present> presentSince(Instant since) {
        List<Present> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT cs.discord_user_id, cs.last_seen, cs.rsn FROM younglings.plugin_client_status cs
                JOIN younglings.plugin_consent pc ON pc.discord_user_id = cs.discord_user_id AND pc.category = 'PRESENCE'
                WHERE cs.last_seen > ? AND NOT cs.paused
                ORDER BY cs.last_seen DESC
                """)) {
            s.setTimestamp(1, Timestamp.from(since));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Present(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3)));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read who is in the game with RuneSync", e);
        }
    }

    /** The member's own latest events, at most {@code perType} of each of the given types, newest first. */
    public List<RecentEvent> recentEvents(long userId, List<String> types, int perType) {
        List<RecentEvent> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT type, occurred_at, source_kind, source_name, data::text FROM (
                    SELECT *, ROW_NUMBER() OVER (PARTITION BY type ORDER BY occurred_at DESC, id DESC) AS rn
                    FROM younglings.plugin_event WHERE discord_user_id = ? AND type = ANY (?)
                ) t WHERE rn <= ? ORDER BY type, occurred_at DESC""")) {
            s.setLong(1, userId);
            s.setArray(2, c.createArrayOf("text", types.toArray()));
            s.setInt(3, perType);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new RecentEvent(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the member's recent plugin events", e);
        }
    }

    public void deleteFor(long userId) {
        try (Connection c = connectionSupplier.getConnection()) {
            for (String sql : List.of("DELETE FROM younglings.plugin_client_status WHERE discord_user_id = ?",
                    "DELETE FROM younglings.plugin_diagnostics WHERE discord_user_id = ?")) {
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, userId);
                    s.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the member's RuneSync records", e);
        }
    }

    // ---------- a clan's switches ----------

    /** Whether the clan allows RuneSync's in-game clan panel; off when it never chose. */
    public boolean clanPanelOn(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT clan_panel FROM younglings.plugin_guild_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the clan's plugin settings", e);
        }
    }

    /** The servers that allow the in-game clan panel (T-812: one query instead of one per server the bot is in). */
    public java.util.Set<Long> clanPanelGuilds() {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT guild_id FROM younglings.plugin_guild_settings WHERE clan_panel")) {
            java.util.Set<Long> out = new java.util.HashSet<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read which clans allow the panel", e);
        }
    }

    public void setClanPanel(long guildId, boolean on, long actorId, Instant at) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.plugin_guild_settings (guild_id, clan_panel, updated_at, updated_by) VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET clan_panel = EXCLUDED.clan_panel, updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by""")) {
            s.setLong(1, guildId);
            s.setBoolean(2, on);
            s.setTimestamp(3, Timestamp.from(at));
            s.setLong(4, actorId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the clan's plugin settings", e);
        }
    }

    /** Where the clan's new competitions count XP from ({@code runemetrics} or {@code plugin}); null when it never chose. */
    public String competitionXpSource(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT competition_xp_source FROM younglings.plugin_guild_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the clan's plugin settings", e);
        }
    }

    public void setCompetitionXpSource(long guildId, String source, long actorId, Instant at) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.plugin_guild_settings (guild_id, competition_xp_source, updated_at, updated_by) VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET competition_xp_source = EXCLUDED.competition_xp_source, updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by""")) {
            s.setLong(1, guildId);
            s.setString(2, source);
            s.setTimestamp(3, Timestamp.from(at));
            s.setLong(4, actorId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the clan's plugin settings", e);
        }
    }

    // ---------- diagnostics ----------

    /** Stores a redacted snapshot; false when the id is taken (the caller draws another). */
    public boolean insertDiagnostic(String id, long userId, String json, Instant at, Instant expires) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.plugin_diagnostics (id, discord_user_id, created_at, expires_at, snapshot) VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (id) DO NOTHING""")) {
            s.setString(1, id);
            s.setLong(2, userId);
            s.setTimestamp(3, Timestamp.from(at));
            s.setTimestamp(4, Timestamp.from(expires));
            s.setString(5, json);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store the diagnostics snapshot", e);
        }
    }

    public int diagnosticsSince(long userId, Instant since) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.plugin_diagnostics WHERE discord_user_id = ? AND created_at >= ?")) {
            s.setLong(1, userId);
            s.setTimestamp(2, Timestamp.from(since));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count diagnostics snapshots", e);
        }
    }

    public Optional<Diagnostic> diagnostic(String id, Instant now) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT id, discord_user_id, created_at, expires_at, snapshot::text FROM younglings.plugin_diagnostics WHERE id = ? AND expires_at > ?")) {
            s.setString(1, id);
            s.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Diagnostic(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the diagnostics snapshot", e);
        }
    }

    /** The member's own snapshot ids, newest first (only unexpired ones). */
    public List<Diagnostic> diagnosticsOf(long userId, Instant now) {
        List<Diagnostic> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT id, discord_user_id, created_at, expires_at, '{}' FROM younglings.plugin_diagnostics WHERE discord_user_id = ? AND expires_at > ? ORDER BY created_at DESC LIMIT 20")) {
            s.setLong(1, userId);
            s.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Diagnostic(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), null));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list diagnostics snapshots", e);
        }
    }

    public int purgeExpiredDiagnostics(Instant now) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.plugin_diagnostics WHERE expires_at <= ?")) {
            s.setTimestamp(1, Timestamp.from(now));
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge diagnostics snapshots", e);
        }
    }
}
