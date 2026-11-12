package com.runeclans.bot.eventannounce;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.eventannounce.EventAnnounceRules.Settings;
import com.runeclans.bot.eventannounce.EventAnnounceRules.State;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The settings for announcing a server's scheduled events, and the events announced so far (so each is announced once and tidied up once). */
@BService
public class EventAnnounceRepository {
    private static final Logger log = LoggerFactory.getLogger(EventAnnounceRepository.class);

    /** An announced event: where its message is, the role made for it (null: none, or already deleted) and whether its start was announced. */
    public record Tracked(long eventId, long guildId, Long channelId, Long messageId, Long roleId, State state, boolean startAnnounced) {}

    private final ConnectionSupplier connectionSupplier;

    public EventAnnounceRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "event announcer", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.eventannounce_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    channel_id BIGINT NULL,
                    use_role BOOLEAN NOT NULL DEFAULT FALSE,
                    notify_start BOOLEAN NOT NULL DEFAULT TRUE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.eventannounce_event (
                    event_id BIGINT PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NULL,
                    message_id BIGINT NULL,
                    role_id BIGINT NULL,
                    state TEXT NOT NULL DEFAULT 'SCHEDULED',
                    start_announced BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS eventannounce_event_guild ON younglings.eventannounce_event (guild_id, state);"));
    }

    // ---------- settings ----------

    public Settings settings(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, channel_id, use_role, notify_start FROM younglings.eventannounce_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Settings.defaults(guildId);
                boolean on = rs.getBoolean(1);
                long channel = rs.getLong(2);
                boolean noChannel = rs.wasNull();
                return new Settings(guildId, on, noChannel ? null : channel, rs.getBoolean(3), rs.getBoolean(4));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event announcer settings", e);
        }
    }

    public void saveSettings(Settings settings) {
        String sql = """
                INSERT INTO younglings.eventannounce_config (guild_id, enabled, channel_id, use_role, notify_start) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, channel_id = EXCLUDED.channel_id, use_role = EXCLUDED.use_role, notify_start = EXCLUDED.notify_start
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, settings.guildId());
            s.setBoolean(2, settings.enabled());
            if (settings.channelId() == null) s.setNull(3, Types.BIGINT); else s.setLong(3, settings.channelId());
            s.setBoolean(4, settings.useRole());
            s.setBoolean(5, settings.notifyStart());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the event announcer settings", e);
        }
    }

    // ---------- announced events ----------

    /** Claims an event for announcing. Returns true only for the one call that claimed it, so an event is announced once. */
    public boolean claim(long eventId, long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.eventannounce_event (event_id, guild_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, eventId);
            s.setLong(2, guildId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the announced event", e);
        }
    }

    public void setMessage(long eventId, long channelId, long messageId, Long roleId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE younglings.eventannounce_event SET channel_id = ?, message_id = ?, role_id = ? WHERE event_id = ?")) {
            s.setLong(1, channelId);
            s.setLong(2, messageId);
            if (roleId == null) s.setNull(3, Types.BIGINT); else s.setLong(3, roleId);
            s.setLong(4, eventId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the announcement", e);
        }
    }

    /** Records the event's role as soon as it is made, so it is deleted when the event ends even if the post never goes out (B-102). */
    public void setRole(long eventId, long roleId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.eventannounce_event SET role_id = ? WHERE event_id = ?")) {
            s.setLong(1, roleId);
            s.setLong(2, eventId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the event's role", e);
        }
    }

    /** Gives back the claim on an event whose announcement was never posted (no message saved), so nothing is left tracked for it. */
    public void release(long eventId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "DELETE FROM younglings.eventannounce_event WHERE event_id = ? AND message_id IS NULL")) {
            s.setLong(1, eventId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to release the announced event", e);
        }
    }

    public void setState(long eventId, State state, boolean startAnnounced) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE younglings.eventannounce_event SET state = ?, start_announced = ? WHERE event_id = ?")) {
            s.setString(1, state.name());
            s.setBoolean(2, startAnnounced);
            s.setLong(3, eventId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update the announced event", e);
        }
    }

    public void clearRole(long eventId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.eventannounce_event SET role_id = NULL WHERE event_id = ?")) {
            s.setLong(1, eventId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update the announced event", e);
        }
    }

    public Optional<Tracked> tracked(long eventId) {
        List<Tracked> found = query("SELECT * FROM younglings.eventannounce_event WHERE event_id = ?", eventId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** The events of a server not yet finished or cancelled, plus the latest few that have, newest first. */
    public List<Tracked> recent(long guildId, int limit) {
        List<Tracked> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.eventannounce_event WHERE guild_id = ? ORDER BY created_at DESC LIMIT ?")) {
            s.setLong(1, guildId);
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the announced events", e);
        }
    }

    /** Events that may still need tidying: not finished, or finished with a role left behind. */
    public List<Tracked> open() {
        List<Tracked> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT * FROM younglings.eventannounce_event WHERE state IN ('SCHEDULED', 'ACTIVE') OR role_id IS NOT NULL");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) out.add(map(rs));
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the announced events", e);
        }
    }

    private List<Tracked> query(String sql, long id) {
        List<Tracked> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, id);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the announced event", e);
        }
    }

    private static Tracked map(ResultSet rs) throws SQLException {
        long channel = rs.getLong("channel_id");
        boolean noChannel = rs.wasNull();
        long message = rs.getLong("message_id");
        boolean noMessage = rs.wasNull();
        long role = rs.getLong("role_id");
        boolean noRole = rs.wasNull();
        return new Tracked(rs.getLong("event_id"), rs.getLong("guild_id"), noChannel ? null : channel, noMessage ? null : message, noRole ? null : role,
                EventAnnounceRules.state(rs.getString("state")), rs.getBoolean("start_announced"));
    }
}
