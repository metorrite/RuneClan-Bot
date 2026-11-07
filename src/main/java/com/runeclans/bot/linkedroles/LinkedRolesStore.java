package com.runeclans.bot.linkedroles;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.linkedroles.RoleMetadata.Profile;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongPredicate;

/**
 * Linked Roles' table, {@code younglings.linked_role_connection}: one row per person who verified, with their OAuth tokens sealed by
 * {@link TokenBox} (never stored or logged in clear), the RuneLink name the website found for them, and what was last sent to Discord. Plus
 * the read-only lookup of what the bot knows about a person (linked name, levels, clan) from the RuneScape tables it already keeps.
 */
@BConfiguration
public class LinkedRolesStore {
    private static final Logger log = LoggerFactory.getLogger(LinkedRolesStore.class);

    /** {@code rsLinkConfirmed}: the hub had confirmed that RuneLink name from public data when it was last asked (T-552). */
    public record Row(long discordUserId, byte[] accessEnc, byte[] refreshEnc, Instant accessExpiresAt, String rsLinkRsn, Instant rsLinkSince,
                      String lastFingerprint, Instant lastPushedAt, Instant createdAt, boolean rsLinkConfirmed) {}

    private final ConnectionSupplier db;

    /** Stats reads answer from the hub while it polls, else from this class's own SQL (T-851, {@link StatsBackends}). */
    private volatile StatsBackends stats = StatsBackends.none();

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static LinkedRolesStore linkedRolesStore(ConnectionSupplier db, StatsBackends backends) {
        LinkedRolesStore store = new LinkedRolesStore(db);
        store.stats = backends;
        return store;
    }

    /** Reads from {@code backends} while the hub polls (tests). */
    public LinkedRolesStore withStats(StatsBackends backends) {
        this.stats = backends;
        return this;
    }

    public LinkedRolesStore(ConnectionSupplier db) {
        this.db = db;
        SchemaBootstrapper.run(db, log, "linked roles", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.linked_role_connection (
                    discord_user_id BIGINT PRIMARY KEY,
                    access_enc BYTEA NOT NULL,
                    refresh_enc BYTEA NOT NULL,
                    access_expires_at TIMESTAMPTZ NOT NULL,
                    rslink_rsn TEXT NULL,
                    rslink_since TIMESTAMPTZ NULL,
                    last_fingerprint TEXT NULL,
                    last_pushed_at TIMESTAMPTZ NULL,
                    failures INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "ALTER TABLE younglings.linked_role_connection ADD COLUMN IF NOT EXISTS rslink_confirmed BOOLEAN NOT NULL DEFAULT FALSE;"));
    }

    /** Saves a person's tokens after they verified (a second verification replaces the first). */
    public void saveTokens(long userId, byte[] accessEnc, byte[] refreshEnc, Instant accessExpiresAt, String rsLinkRsn, Instant rsLinkSince) {
        exec("""
                INSERT INTO younglings.linked_role_connection (discord_user_id, access_enc, refresh_enc, access_expires_at, rslink_rsn, rslink_since)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (discord_user_id) DO UPDATE SET access_enc = EXCLUDED.access_enc, refresh_enc = EXCLUDED.refresh_enc,
                    access_expires_at = EXCLUDED.access_expires_at, rslink_rsn = EXCLUDED.rslink_rsn, rslink_since = EXCLUDED.rslink_since,
                    rslink_confirmed = linked_role_connection.rslink_confirmed AND linked_role_connection.rslink_rsn IS NOT DISTINCT FROM EXCLUDED.rslink_rsn,
                    failures = 0, updated_at = NOW()
                """, s -> {
            s.setLong(1, userId);
            s.setBytes(2, accessEnc);
            s.setBytes(3, refreshEnc);
            s.setTimestamp(4, Timestamp.from(accessExpiresAt));
            s.setString(5, rsLinkRsn);
            s.setTimestamp(6, rsLinkSince == null ? null : Timestamp.from(rsLinkSince));
        });
    }

    /** After a refresh: Discord hands out a new refresh token with each new access token. */
    public void updateTokens(long userId, byte[] accessEnc, byte[] refreshEnc, Instant accessExpiresAt) {
        exec("UPDATE younglings.linked_role_connection SET access_enc = ?, refresh_enc = ?, access_expires_at = ?, updated_at = NOW() WHERE discord_user_id = ?", s -> {
            s.setBytes(1, accessEnc);
            s.setBytes(2, refreshEnc);
            s.setTimestamp(3, Timestamp.from(accessExpiresAt));
            s.setLong(4, userId);
        });
    }

    /** The person's RuneLink name changed on the hub since they verified (T-349), or whether it is confirmed did (T-552); null clears it. */
    public void updateRsLink(long userId, String rsLinkRsn, Instant rsLinkSince, boolean confirmed) {
        exec("UPDATE younglings.linked_role_connection SET rslink_rsn = ?, rslink_since = ?, rslink_confirmed = ?, updated_at = NOW() WHERE discord_user_id = ?", s -> {
            s.setString(1, rsLinkRsn);
            s.setTimestamp(2, rsLinkSince == null ? null : Timestamp.from(rsLinkSince));
            s.setBoolean(3, rsLinkRsn != null && confirmed);
            s.setLong(4, userId);
        });
    }

    public void markPushed(long userId, String fingerprint, Instant at) {
        exec("UPDATE younglings.linked_role_connection SET last_fingerprint = ?, last_pushed_at = ?, failures = 0, updated_at = NOW() WHERE discord_user_id = ?", s -> {
            s.setString(1, fingerprint);
            s.setTimestamp(2, Timestamp.from(at));
            s.setLong(3, userId);
        });
    }

    /** Counts a failed push; returns the new count. */
    public int markFailed(long userId) {
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.linked_role_connection SET failures = failures + 1, updated_at = NOW() WHERE discord_user_id = ? RETURNING failures")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("linked roles: counting a failure failed", e);
        }
    }

    public boolean delete(long userId) {
        return exec("DELETE FROM younglings.linked_role_connection WHERE discord_user_id = ?", s -> s.setLong(1, userId)) > 0;
    }

    public Optional<Row> find(long userId) {
        List<Row> rows = select("WHERE discord_user_id = ?", s -> s.setLong(1, userId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Everyone connected, oldest push first, at most {@code limit}. */
    public List<Long> allUserIds(int limit) {
        List<Long> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT discord_user_id FROM younglings.linked_role_connection ORDER BY last_pushed_at ASC NULLS FIRST LIMIT ?")) {
            s.setInt(1, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("linked roles: listing failed", e);
        }
        return out;
    }

    /**
     * What the bot knows about a person: their verified RuneScape account (the bot's own {@code player_account}, else their primary per-server
     * link, else the RuneLink name the website passed), the latest level snapshot of that name, and whether it is an active member of a clan
     * that {@code listed} says is publicly listed.
     */
    public Profile profile(long userId, String rsLinkRsn, Instant rsLinkSince, LongPredicate listed) {
        Profile fromTables = profileFromTables(userId, rsLinkRsn, rsLinkSince, listed);
        if (fromTables.rsn() == null) return fromTables;
        // the levels are the hub's, read after the database connection is back in the pool
        var latest = stats.backend().latest(fromTables.rsn());
        return latest == null ? fromTables : new Profile(fromTables.rsn(), fromTables.linkedSince(), latest.totalLevel(), latest.combatLevel(), fromTables.clanMember());
    }

    private Profile profileFromTables(long userId, String rsLinkRsn, Instant rsLinkSince, LongPredicate listed) {
        try (Connection c = db.getConnection()) {
            String rsn = null;
            Instant since = null;
            for (String sql : new String[]{
                    "SELECT rsn, verified_at FROM younglings.player_account WHERE discord_user_id = ? ORDER BY verified_at ASC LIMIT 1",
                    "SELECT rsn, verified_at FROM younglings.player_link WHERE discord_user_id = ? ORDER BY is_primary DESC, verified_at ASC LIMIT 1"}) {
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, userId);
                    try (ResultSet rs = s.executeQuery()) {
                        if (rs.next()) {
                            rsn = rs.getString(1);
                            since = rs.getTimestamp(2).toInstant();
                        }
                    }
                }
                if (rsn != null) break;
            }
            if (rsn == null && rsLinkRsn != null && !rsLinkRsn.isBlank()) {
                rsn = rsLinkRsn;
                since = rsLinkSince;
            }
            if (rsn == null) return Profile.NONE;

            Integer total = null;
            Integer combat = null;
            boolean member = false;
            try (PreparedStatement s = c.prepareStatement("SELECT DISTINCT guild_id FROM younglings.clan_member WHERE LOWER(rsn) = LOWER(?) AND active")) {
                s.setString(1, rsn);
                try (ResultSet rs = s.executeQuery()) {
                    while (!member && rs.next()) member = listed.test(rs.getLong(1));
                }
            }
            return new Profile(rsn, since, total, combat, member);
        } catch (SQLException e) {
            throw new IllegalStateException("linked roles: reading a profile failed", e);
        }
    }

    private interface Binder {
        void bind(PreparedStatement s) throws SQLException;
    }

    private int exec(String sql, Binder binder) {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            binder.bind(s);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("linked roles: a write failed", e);
        }
    }

    private List<Row> select(String where, Binder binder) {
        List<Row> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT discord_user_id, access_enc, refresh_enc, access_expires_at, rslink_rsn, rslink_since, last_fingerprint, last_pushed_at, created_at,
                         rslink_confirmed
                     FROM younglings.linked_role_connection\s""" + where)) {
            binder.bind(s);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new Row(rs.getLong(1), rs.getBytes(2), rs.getBytes(3), rs.getTimestamp(4).toInstant(), rs.getString(5), instant(rs, 6),
                            rs.getString(7), instant(rs, 8), rs.getTimestamp(9).toInstant(), rs.getBoolean(10)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("linked roles: a read failed", e);
        }
        return out;
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
