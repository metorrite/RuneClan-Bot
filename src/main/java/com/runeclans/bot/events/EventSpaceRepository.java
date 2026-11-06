package com.runeclans.bot.events;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.hubevents.HubEventMap;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What JonnyBot made on Discord for an event, so it only ever touches its own: the roles (by key: {@code participant}, {@code organiser}, {@code team:<id>}),
 * the categories and channels (by key: {@code category}, {@code staff}, {@code team:<id>}, ...), and who it gave each role to. A channel an organiser chose
 * is recorded too, not owned, so the event's channels can be told from the channel a message arrives in. The event's setup state (applied, complete, what
 * is missing) lives with the rest of its setup in {@link EventRepository}.
 */
@BService
public class EventSpaceRepository {
    private static final Logger log = LoggerFactory.getLogger(EventSpaceRepository.class);

    /** {@code owned} says JonnyBot made the channel (and may remove it) rather than being told to use it; {@code parent} is the key of the category it sits in. */
    public record ChannelRow(long eventId, String key, long channelId, String name, boolean owned, String parent) {}

    public record RoleRow(long eventId, String key, long roleId, String name) {}

    private final ConnectionSupplier connectionSupplier;

    /**
     * Events are the hub's (T-934): {@code event_id} is the event's local id from {@link HubEventMap}, whose table the server lookups join, so the map is a
     * dependency only so its table exists first. Rows are no longer tied to the bot's old event table: an event that is gone from the hub has its rows
     * cleared by {@link EventSpaceOps} instead.
     */
    public EventSpaceRepository(ConnectionSupplier connectionSupplier, HubEventMap map) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "event roles and channels", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_role (
                    event_id BIGINT NOT NULL,
                    role_key TEXT NOT NULL,
                    role_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_id, role_key)
                );
                """,
                "CREATE INDEX IF NOT EXISTS event_role_id ON younglings.event_role (role_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_channel (
                    event_id BIGINT NOT NULL,
                    channel_key TEXT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    owned BOOLEAN NOT NULL DEFAULT TRUE,
                    parent_key TEXT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_id, channel_key)
                );
                """,
                "CREATE INDEX IF NOT EXISTS event_channel_id ON younglings.event_channel (channel_id);",
                // T-934: the rows name hub events by their local id, which the bot's old event table never held
                "ALTER TABLE younglings.event_role DROP CONSTRAINT IF EXISTS event_role_event_id_fkey;",
                "ALTER TABLE younglings.event_channel DROP CONSTRAINT IF EXISTS event_channel_event_id_fkey;",
                // the welcome message JonnyBot posted when it made the channel (T-209), so it is only ever posted once
                "ALTER TABLE younglings.event_channel ADD COLUMN IF NOT EXISTS welcome_message_id BIGINT NULL;",
                // who JonnyBot has given each of an event's roles to, so it can take the role back from exactly those people (members who are offline aren't cached)
                """
                CREATE TABLE IF NOT EXISTS younglings.event_role_member (
                    event_id BIGINT NOT NULL,
                    role_key TEXT NOT NULL,
                    user_id BIGINT NOT NULL,
                    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_id, role_key, user_id),
                    FOREIGN KEY (event_id, role_key) REFERENCES younglings.event_role (event_id, role_key) ON DELETE CASCADE
                );
                """));
    }

    // ---------- roles ----------

    public List<RoleRow> roles(long eventId) {
        List<RoleRow> rows = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT event_id, role_key, role_id, name FROM younglings.event_role WHERE event_id = ? ORDER BY created_at, role_key")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) rows.add(new RoleRow(rs.getLong("event_id"), rs.getString("role_key"), rs.getLong("role_id"), rs.getString("name")));
            }
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event's roles", e);
        }
    }

    /**
     * Records a role JonnyBot made for the event. A role made again after someone deleted it replaces the record, and nobody holds the new one yet, so who was
     * recorded as holding the old one is forgotten.
     */
    public void saveRole(long eventId, String key, long roleId, String name) {
        String forget = """
                DELETE FROM younglings.event_role_member m USING younglings.event_role r
                WHERE m.event_id = r.event_id AND m.role_key = r.role_key AND r.event_id = ? AND r.role_key = ? AND r.role_id <> ?
                """;
        String sql = """
                INSERT INTO younglings.event_role (event_id, role_key, role_id, name) VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id, role_key) DO UPDATE SET role_id = EXCLUDED.role_id, name = EXCLUDED.name
                """;
        try (Connection c = connectionSupplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement(forget)) {
                s.setLong(1, eventId);
                s.setString(2, key);
                s.setLong(3, roleId);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement(sql)) {
                s.setLong(1, eventId);
                s.setString(2, key);
                s.setLong(3, roleId);
                s.setString(4, name);
                s.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the event's role", e);
        }
    }

    /** Forgets a role and who held it. */
    public void deleteRole(long eventId, String key) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_role WHERE event_id = ? AND role_key = ?")) {
            s.setLong(1, eventId);
            s.setString(2, key);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to forget the event's role", e);
        }
    }

    // ---------- channels ----------

    public List<ChannelRow> channels(long eventId) {
        List<ChannelRow> rows = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT event_id, channel_key, channel_id, name, owned, parent_key FROM younglings.event_channel WHERE event_id = ? ORDER BY created_at, channel_key")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) rows.add(map(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event's channels", e);
        }
    }

    private static ChannelRow map(ResultSet rs) throws SQLException {
        return new ChannelRow(rs.getLong("event_id"), rs.getString("channel_key"), rs.getLong("channel_id"), rs.getString("name"), rs.getBoolean("owned"), rs.getString("parent_key"));
    }

    /** Records a channel or category for the event (or replaces the record of one that was made again after someone deleted it). */
    /** Notes the welcome message posted in a channel JonnyBot made. */
    public void saveWelcome(long eventId, String key, long messageId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.event_channel SET welcome_message_id = ? WHERE event_id = ? AND channel_key = ?")) {
            s.setLong(1, messageId);
            s.setLong(2, eventId);
            s.setString(3, key);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to note the welcome message", e);
        }
    }

    public void saveChannel(long eventId, String key, long channelId, String name, boolean owned, String parent) {
        String sql = """
                INSERT INTO younglings.event_channel (event_id, channel_key, channel_id, name, owned, parent_key) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id, channel_key) DO UPDATE SET channel_id = EXCLUDED.channel_id, name = EXCLUDED.name, owned = EXCLUDED.owned, parent_key = EXCLUDED.parent_key
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setString(2, key);
            s.setLong(3, channelId);
            s.setString(4, name);
            s.setBoolean(5, owned);
            s.setString(6, parent);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the event's channel", e);
        }
    }

    public void deleteChannel(long eventId, String key) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_channel WHERE event_id = ? AND channel_key = ?")) {
            s.setLong(1, eventId);
            s.setString(2, key);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to forget the event's channel", e);
        }
    }

    /** The event a channel belongs to (one JonnyBot made or one an organiser chose), or empty. The first answer when it belongs to several. */
    public Optional<ChannelRow> channelById(long guildId, long channelId) {
        String sql = """
                SELECT c.event_id, c.channel_key, c.channel_id, c.name, c.owned, c.parent_key FROM younglings.event_channel c
                JOIN younglings.hub_event_map e ON e.local_id = c.event_id WHERE e.guild_id = ? AND c.channel_id = ? ORDER BY c.event_id DESC LIMIT 1
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, channelId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to look up the channel", e);
        }
    }

    /** The channels other events of the server already use, so one isn't chosen for two events. Maps the channel to the event. */
    public Map<Long, Long> channelsOfOtherEvents(long guildId, long exceptEventId) {
        String sql = """
                SELECT c.channel_id, c.event_id FROM younglings.event_channel c JOIN younglings.hub_event_map e ON e.local_id = c.event_id
                WHERE e.guild_id = ? AND c.event_id <> ?
                """;
        Map<Long, Long> used = new LinkedHashMap<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, exceptEventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) used.put(rs.getLong(1), rs.getLong(2));
            }
            return used;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the channels in use", e);
        }
    }

    /** Ids of the events of the server that have a role or channel recorded, whether or not setting them up finished. */
    public List<Long> eventsWithSpace(long guildId) {
        String sql = """
                SELECT DISTINCT e.local_id FROM younglings.hub_event_map e
                WHERE e.guild_id = ? AND (EXISTS (SELECT 1 FROM younglings.event_role r WHERE r.event_id = e.local_id) OR EXISTS (SELECT 1 FROM younglings.event_channel c WHERE c.event_id = e.local_id))
                ORDER BY e.local_id
                """;
        List<Long> ids = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the events with channels", e);
        }
    }

    /** Whether the event has any role or channel recorded. */
    public boolean hasSpace(long eventId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM younglings.event_role WHERE event_id = ?) OR EXISTS (SELECT 1 FROM younglings.event_channel WHERE event_id = ?)";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setLong(2, eventId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check the event's channels", e);
        }
    }

    // ---------- who holds the roles ----------

    /** Who JonnyBot has given each of the event's roles to, by role key. */
    public Map<String, Set<Long>> members(long eventId) {
        Map<String, Set<Long>> members = new LinkedHashMap<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT role_key, user_id FROM younglings.event_role_member WHERE event_id = ?")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) members.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getLong(2));
            }
            return members;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read who holds the event's roles", e);
        }
    }

    public void addMember(long eventId, String roleKey, long userId) {
        String sql = "INSERT INTO younglings.event_role_member (event_id, role_key, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setString(2, roleKey);
            s.setLong(3, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record who holds the role", e);
        }
    }

    public void removeMember(long eventId, String roleKey, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "DELETE FROM younglings.event_role_member WHERE event_id = ? AND role_key = ? AND user_id = ?")) {
            s.setLong(1, eventId);
            s.setString(2, roleKey);
            s.setLong(3, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to forget who held the role", e);
        }
    }

    /** The events of the server in which {@code userId} holds a role JonnyBot gave them. */
    public List<Long> eventsHeldBy(long guildId, long userId) {
        String sql = """
                SELECT DISTINCT m.event_id FROM younglings.event_role_member m JOIN younglings.hub_event_map e ON e.local_id = m.event_id WHERE e.guild_id = ? AND m.user_id = ?
                """;
        List<Long> ids = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the events the player holds roles in", e);
        }
    }

    /** Forgets every role, channel and role holder recorded for the event (they were removed). */
    public void clear(long eventId) {
        try (Connection c = connectionSupplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_role WHERE event_id = ?")) {
                s.setLong(1, eventId);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_channel WHERE event_id = ?")) {
                s.setLong(1, eventId);
                s.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to forget the event's roles and channels", e);
        }
    }
}
