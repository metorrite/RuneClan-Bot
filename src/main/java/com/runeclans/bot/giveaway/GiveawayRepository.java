package com.runeclans.bot.giveaway;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.giveaway.Giveaway.Status;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@BService
public class GiveawayRepository {
    private static final Logger log = LoggerFactory.getLogger(GiveawayRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public GiveawayRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "giveaway", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.giveaway (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NULL,
                    prize TEXT NOT NULL,
                    host_id BIGINT NOT NULL,
                    winner_count INTEGER NOT NULL DEFAULT 1,
                    ends_at TIMESTAMPTZ NOT NULL,
                    required_role_id BIGINT NULL,
                    status TEXT NOT NULL DEFAULT 'OPEN',
                    winners TEXT NOT NULL DEFAULT '',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS giveaway_guild ON younglings.giveaway (guild_id, id DESC);",
                "CREATE INDEX IF NOT EXISTS giveaway_due ON younglings.giveaway (status, ends_at);",
                // Every reaction on a giveaway message looks the giveaway up by its message.
                "CREATE INDEX IF NOT EXISTS giveaway_message ON younglings.giveaway (message_id) WHERE message_id IS NOT NULL;",
                """
                CREATE TABLE IF NOT EXISTS younglings.giveaway_entry (
                    giveaway_id BIGINT NOT NULL REFERENCES younglings.giveaway (id) ON DELETE CASCADE,
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    PRIMARY KEY (giveaway_id, user_id)
                );
                """,
                "ALTER TABLE younglings.giveaway ADD COLUMN IF NOT EXISTS winner_role_id BIGINT NULL;",
                "ALTER TABLE younglings.giveaway ADD COLUMN IF NOT EXISTS dm_winners BOOLEAN NOT NULL DEFAULT FALSE;",
                "ALTER TABLE younglings.giveaway ADD COLUMN IF NOT EXISTS reaction_entry BOOLEAN NOT NULL DEFAULT FALSE;"));
    }

    public long create(long guildId, long channelId, String prize, long hostId, int winnerCount, Instant endsAt, Long requiredRoleId, Long winnerRoleId, boolean dmWinners,
                       boolean reactionEntry) {
        String sql = "INSERT INTO younglings.giveaway (guild_id, channel_id, prize, host_id, winner_count, ends_at, required_role_id, winner_role_id, dm_winners, reaction_entry) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, channelId);
            s.setString(3, prize);
            s.setLong(4, hostId);
            s.setInt(5, winnerCount);
            s.setTimestamp(6, Timestamp.from(endsAt));
            if (requiredRoleId == null) s.setNull(7, Types.BIGINT); else s.setLong(7, requiredRoleId);
            if (winnerRoleId == null) s.setNull(8, Types.BIGINT); else s.setLong(8, winnerRoleId);
            s.setBoolean(9, dmWinners);
            s.setBoolean(10, reactionEntry);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("Failed to create a giveaway in guild {}", guildId, e);
            throw new RuntimeException("Failed to create the giveaway", e);
        }
    }

    public void setMessage(long id, long messageId) {
        exec("UPDATE younglings.giveaway SET message_id = ? WHERE id = ?", messageId, id);
    }

    public Optional<Giveaway> get(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.giveaway WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the giveaway", e);
        }
    }

    /** By id alone, for a button press (the message already names the giveaway). */
    public Optional<Giveaway> byId(long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.giveaway WHERE id = ?")) {
            s.setLong(1, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the giveaway", e);
        }
    }

    /** The giveaway whose message this is, for a reaction on it. */
    public Optional<Giveaway> byMessage(long messageId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.giveaway WHERE message_id = ?")) {
            s.setLong(1, messageId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the giveaway", e);
        }
    }

    public List<Giveaway> recent(long guildId, int limit) {
        List<Giveaway> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.giveaway WHERE guild_id = ? ORDER BY id DESC LIMIT ?")) {
            s.setLong(1, guildId);
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list the giveaways", e);
        }
    }

    public List<Giveaway> due(Instant now, int limit) {
        List<Giveaway> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.giveaway WHERE status = 'OPEN' AND ends_at <= ? AND message_id IS NOT NULL ORDER BY ends_at LIMIT ?")) {
            s.setTimestamp(1, Timestamp.from(now));
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to read the due giveaways", e);
        }
        return list;
    }

    /**
     * Ends an open giveaway with these winners, once, in one statement. Only the caller that gets {@code true} may announce: the sweep, the
     * dashboard's End now job and /giveaway end can all reach the same giveaway at its end time, and the others must do nothing. Draw
     * before calling it: a draw that fails leaves the giveaway OPEN, so the sweep tries again.
     */
    public boolean finishIfOpen(long id, List<Long> winners) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.giveaway SET status = 'ENDED', winners = ? WHERE id = ? AND status = 'OPEN'")) {
            s.setString(1, String.join(",", winners.stream().map(String::valueOf).toList()));
            s.setLong(2, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to end the giveaway", e);
        }
    }

    public void finish(long id, List<Long> winners) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.giveaway SET status = 'ENDED', winners = ? WHERE id = ?")) {
            s.setString(1, String.join(",", winners.stream().map(String::valueOf).toList()));
            s.setLong(2, id);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to finish the giveaway", e);
        }
    }

    public void setWinners(long id, List<Long> winners) {
        finish(id, winners);
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.giveaway WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the giveaway", e);
        }
    }

    // ---- entries ----

    public List<Long> entrants(long giveawayId) {
        List<Long> ids = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT user_id FROM younglings.giveaway_entry WHERE giveaway_id = ?")) {
            s.setLong(1, giveawayId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the entries", e);
        }
    }

    public int entryCount(long giveawayId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.giveaway_entry WHERE giveaway_id = ?")) {
            s.setLong(1, giveawayId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the entries", e);
        }
    }

    /** Enters the person, or takes them out if they were in. Returns whether they are now entered. */
    public boolean toggleEntry(long giveawayId, long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.giveaway_entry WHERE giveaway_id = ? AND user_id = ?")) {
                s.setLong(1, giveawayId);
                s.setLong(2, userId);
                if (s.executeUpdate() > 0) return false;
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.giveaway_entry (giveaway_id, guild_id, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                s.setLong(1, giveawayId);
                s.setLong(2, guildId);
                s.setLong(3, userId);
                s.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to change the entry", e);
        }
    }

    /** Enters the person (a reaction); a person already in stays in. */
    public void addEntry(long giveawayId, long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.giveaway_entry (giveaway_id, guild_id, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, giveawayId);
            s.setLong(2, guildId);
            s.setLong(3, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add the entry", e);
        }
    }

    /** Takes the person out (a reaction removed). */
    public void removeEntry(long giveawayId, long userId) {
        exec("DELETE FROM younglings.giveaway_entry WHERE giveaway_id = ? AND user_id = ?", giveawayId, userId);
    }

    private void exec(String sql, long a, long b) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, a);
            s.setLong(2, b);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Giveaway statement failed", e);
        }
    }

    private static Giveaway map(ResultSet rs) throws SQLException {
        long message = rs.getLong("message_id");
        boolean noMessage = rs.wasNull();
        long role = rs.getLong("required_role_id");
        boolean noRole = rs.wasNull();
        long winnerRole = rs.getLong("winner_role_id");
        boolean noWinnerRole = rs.wasNull();
        List<Long> winners = new ArrayList<>();
        String raw = rs.getString("winners");
        if (raw != null && !raw.isBlank()) for (String part : raw.split(",")) winners.add(Long.parseLong(part.strip()));
        return new Giveaway(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("channel_id"), noMessage ? null : message, rs.getString("prize"), rs.getLong("host_id"),
                rs.getInt("winner_count"), rs.getTimestamp("ends_at").toInstant(), noRole ? null : role, Status.valueOf(rs.getString("status")), winners,
                noWinnerRole ? null : winnerRole, rs.getBoolean("dm_winners"), rs.getBoolean("reaction_entry"));
    }
}
