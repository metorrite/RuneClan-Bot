package com.runeclans.bot.internal;

import com.runeclans.bot.database.SchemaBootstrapper;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the website's admin tools remember: an audit log of every change made from the dashboard, private staff notes
 * on members, and messages queued to post later. Kept in the bot's own database so none of it depends on the website.
 */
@BConfiguration
public class AdminToolsStore {
    private static final Logger log = LoggerFactory.getLogger(AdminToolsStore.class);

    public record AuditEntry(long id, String actorId, String actorName, String method, String path, int status, OffsetDateTime at) {}

    public record Note(long id, String rsn, String note, String authorId, String authorName, OffsetDateTime at) {}

    public record ScheduledPost(long id, long channelId, String text, boolean convert, OffsetDateTime sendAt, String status, String createdBy, String createdByName,
                                OffsetDateTime createdAt, String error) {}

    private final ConnectionSupplier connectionSupplier;


    /** Stats reads answer from the hub while it polls, else from this class's own SQL (T-851, {@link StatsBackends}). */
    private volatile StatsBackends stats = StatsBackends.none();

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it.  */
    @BService
    public static AdminToolsStore adminToolsStore(ConnectionSupplier connectionSupplier, StatsBackends backends) {
        AdminToolsStore repository = new AdminToolsStore(connectionSupplier);
        repository.stats = backends;
        return repository;
    }

    /** Reads from {@code backends} while the hub polls (tests). */
    public AdminToolsStore withStats(StatsBackends backends) {
        this.stats = backends;
        return this;
    }
    public AdminToolsStore(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "admin tools", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.admin_audit (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    actor_id TEXT NOT NULL,
                    actor_name TEXT NULL,
                    method TEXT NOT NULL,
                    path TEXT NOT NULL,
                    status INT NOT NULL,
                    at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS admin_audit_guild_at ON younglings.admin_audit (guild_id, at DESC);",
                """
                CREATE TABLE IF NOT EXISTS younglings.admin_note (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    note TEXT NOT NULL,
                    author_id TEXT NOT NULL,
                    author_name TEXT NULL,
                    at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS admin_note_rsn ON younglings.admin_note (guild_id, LOWER(rsn));",
                """
                CREATE TABLE IF NOT EXISTS younglings.scheduled_post (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    text TEXT NOT NULL,
                    convert BOOLEAN NOT NULL DEFAULT FALSE,
                    send_at TIMESTAMPTZ NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    created_by TEXT NOT NULL,
                    created_by_name TEXT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    error TEXT NULL
                );
                """,
                // The 30 s due-post poll reads only PENDING rows; finished rows are kept, so the table only grows.
                "CREATE INDEX IF NOT EXISTS scheduled_post_due ON younglings.scheduled_post (send_at) WHERE status = 'PENDING';",
                "CREATE INDEX IF NOT EXISTS scheduled_post_guild ON younglings.scheduled_post (guild_id, send_at);"));
    }

    private static OffsetDateTime at(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }

    private RuntimeException fail(String what, SQLException e) {
        log.error("Admin tools: could not {}", what, e);
        return new RuntimeException("Could not " + what, e);
    }

    // ---------- audit ----------

    public void audit(long guildId, String actorId, String actorName, String method, String path, int status) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.admin_audit (guild_id, actor_id, actor_name, method, path, status) VALUES (?, ?, ?, ?, ?, ?)")) {
            s.setLong(1, guildId);
            s.setString(2, actorId);
            s.setString(3, actorName);
            s.setString(4, method);
            s.setString(5, path);
            s.setInt(6, status);
            s.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not write an audit entry for {} {}", method, path, e); // never let logging break the action itself
        }
    }

    public List<AuditEntry> recentAudit(long guildId, int limit, String actorFilter, String search) {
        StringBuilder sql = new StringBuilder("SELECT id, actor_id, actor_name, method, path, status, at FROM younglings.admin_audit WHERE guild_id = ?");
        List<Object> params = new ArrayList<>(List.of(guildId));
        if (actorFilter != null && !actorFilter.isBlank()) {
            sql.append(" AND actor_id = ?");
            params.add(actorFilter);
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (LOWER(path) LIKE ? OR LOWER(COALESCE(actor_name, '')) LIKE ?)");
            String like = "%" + search.toLowerCase().replace("%", "") + "%";
            params.add(like);
            params.add(like);
        }
        sql.append(" ORDER BY at DESC, id DESC LIMIT ?");
        params.add(limit);
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) s.setObject(i + 1, params.get(i));
            try (ResultSet rs = s.executeQuery()) {
                List<AuditEntry> out = new ArrayList<>();
                while (rs.next()) out.add(new AuditEntry(rs.getLong("id"), rs.getString("actor_id"), rs.getString("actor_name"), rs.getString("method"), rs.getString("path"), rs.getInt("status"), at(rs, "at")));
                return out;
            }
        } catch (SQLException e) {
            throw fail("read the audit log", e);
        }
    }

    // ---------- notes ----------

    public List<Note> notes(long guildId, String rsn) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT id, rsn, note, author_id, author_name, at FROM younglings.admin_note WHERE guild_id = ? AND LOWER(rsn) = LOWER(?) ORDER BY at DESC")) {
            s.setLong(1, guildId);
            s.setString(2, rsn);
            try (ResultSet rs = s.executeQuery()) {
                List<Note> out = new ArrayList<>();
                while (rs.next()) out.add(new Note(rs.getLong("id"), rs.getString("rsn"), rs.getString("note"), rs.getString("author_id"), rs.getString("author_name"), at(rs, "at")));
                return out;
            }
        } catch (SQLException e) {
            throw fail("read member notes", e);
        }
    }

    /** How many notes each member has (lower-case RSN → count), so the roster can flag the ones with notes. */
    public Map<String, Integer> noteCounts(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT LOWER(rsn) AS rsn, COUNT(*) AS n FROM younglings.admin_note WHERE guild_id = ? GROUP BY LOWER(rsn)")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                Map<String, Integer> out = new HashMap<>();
                while (rs.next()) out.put(rs.getString("rsn"), rs.getInt("n"));
                return out;
            }
        } catch (SQLException e) {
            throw fail("count member notes", e);
        }
    }

    public void addNote(long guildId, String rsn, String note, String authorId, String authorName) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.admin_note (guild_id, rsn, note, author_id, author_name) VALUES (?, ?, ?, ?, ?)")) {
            s.setLong(1, guildId);
            s.setString(2, rsn);
            s.setString(3, note);
            s.setString(4, authorId);
            s.setString(5, authorName);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save the note", e);
        }
    }

    public boolean deleteNote(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.admin_note WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("delete the note", e);
        }
    }

    // ---------- scheduled posts ----------

    public long schedule(long guildId, long channelId, String text, boolean convert, OffsetDateTime sendAt, String createdBy, String createdByName) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.scheduled_post (guild_id, channel_id, text, convert, send_at, created_by, created_by_name) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
            s.setLong(1, guildId);
            s.setLong(2, channelId);
            s.setString(3, text);
            s.setBoolean(4, convert);
            s.setTimestamp(5, Timestamp.from(sendAt.toInstant()));
            s.setString(6, createdBy);
            s.setString(7, createdByName);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw fail("schedule the post", e);
        }
    }

    private static ScheduledPost postRow(ResultSet rs) throws SQLException {
        return new ScheduledPost(rs.getLong("id"), rs.getLong("channel_id"), rs.getString("text"), rs.getBoolean("convert"), at(rs, "send_at"), rs.getString("status"),
                rs.getString("created_by"), rs.getString("created_by_name"), at(rs, "created_at"), rs.getString("error"));
    }

    /** Pending posts first (soonest first), then the most recent finished ones. */
    public List<ScheduledPost> scheduled(long guildId, int finishedLimit) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     (SELECT * FROM younglings.scheduled_post WHERE guild_id = ? AND status = 'PENDING' ORDER BY send_at)
                     UNION ALL
                     (SELECT * FROM younglings.scheduled_post WHERE guild_id = ? AND status <> 'PENDING' ORDER BY send_at DESC LIMIT ?)
                     """)) {
            s.setLong(1, guildId);
            s.setLong(2, guildId);
            s.setInt(3, finishedLimit);
            try (ResultSet rs = s.executeQuery()) {
                List<ScheduledPost> out = new ArrayList<>();
                while (rs.next()) out.add(postRow(rs));
                return out;
            }
        } catch (SQLException e) {
            throw fail("list scheduled posts", e);
        }
    }

    /** Pending posts whose time has come, across every guild (the runner posts each into its own guild). */
    public List<Map.Entry<Long, ScheduledPost>> due() {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.scheduled_post WHERE status = 'PENDING' AND send_at <= NOW() ORDER BY send_at LIMIT 20");
             ResultSet rs = s.executeQuery()) {
            List<Map.Entry<Long, ScheduledPost>> out = new ArrayList<>();
            while (rs.next()) out.add(Map.entry(rs.getLong("guild_id"), postRow(rs)));
            return out;
        } catch (SQLException e) {
            throw fail("find due posts", e);
        }
    }

    public void finish(long id, String status, String error) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.scheduled_post SET status = ?, error = ? WHERE id = ?")) {
            s.setString(1, status);
            s.setString(2, error);
            s.setLong(3, id);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("update the scheduled post", e);
        }
    }

    /** Cancels a post that hasn't been sent yet. */
    public boolean cancel(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.scheduled_post SET status = 'CANCELLED' WHERE guild_id = ? AND id = ? AND status = 'PENDING'")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("cancel the scheduled post", e);
        }
    }

    // ---------- activity timestamps for the roster view ----------

    /** Each active member's newest adventure-log entry time (lower-case RSN → time). */
    public Map<String, OffsetDateTime> lastActivity(long guildId) {
        return stats.backend().lastActivity(guildId);
    }

    public long pingDatabaseMillis() {
        long start = System.nanoTime();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT 1"); ResultSet rs = s.executeQuery()) {
            rs.next();
        } catch (SQLException e) {
            return -1;
        }
        return (System.nanoTime() - start) / 1_000_000;
    }
}
