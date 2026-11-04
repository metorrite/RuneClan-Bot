package com.runeclans.bot.poll;

import com.runeclans.bot.internal.SiteInvalidator;
import com.runeclans.bot.commands.poll.PollOption;
import com.runeclans.bot.commands.poll.PollSession;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

@BService
public class PollRepository implements PollStore {
    private static final Logger log = LoggerFactory.getLogger(PollRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public PollRepository(ConnectionSupplier connectionSupplier, PollDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    // --- Mappers ---

    private static PollSession mapPoll(ResultSet rs) throws SQLException {
        Long messageId = rs.getObject("message_id") == null ? null : rs.getLong("message_id");
        return new PollSession(
                rs.getLong("poll_id"),
                rs.getLong("guild_id"),
                rs.getLong("channel_id"),
                messageId,
                rs.getString("title"),
                rs.getBoolean("anonymous"),
                rs.getBoolean("multiple_votes"),
                rs.getString("status"),
                rs.getLong("created_by_user_id")
        );
    }

    private static PollOption mapOption(ResultSet rs) throws SQLException {
        return new PollOption(
                rs.getLong("option_id"),
                rs.getLong("poll_id"),
                rs.getInt("option_number"),
                rs.getString("label")
        );
    }

    // --- Poll CRUD ---

    @Override
    public long createPoll(long guildId, long channelId, String title, boolean anonymous,
                            boolean multipleVotes, long createdByUserId) {
        String sql = """
                INSERT INTO younglings.poll
                    (guild_id, channel_id, title, anonymous, multiple_votes, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING poll_id
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, channelId);
            statement.setString(3, title);
            statement.setBoolean(4, anonymous);
            statement.setBoolean(5, multipleVotes);
            statement.setLong(6, createdByUserId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return rs.getLong("poll_id");
            }
            throw new SQLException("No poll_id returned.");
        } catch (SQLException e) {
            log.error("Failed to create poll for guild {}", guildId, e);
            throw new RuntimeException("Failed to create poll", e);
        }
    }

    @Override
    public long addOption(long pollId, int optionNumber, String label) {
        String sql = """
                INSERT INTO younglings.poll_option (poll_id, option_number, label)
                VALUES (?, ?, ?)
                RETURNING option_id
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pollId);
            statement.setInt(2, optionNumber);
            statement.setString(3, label);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return rs.getLong("option_id");
            }
            throw new SQLException("No option_id returned.");
        } catch (SQLException e) {
            log.error("Failed to add option to poll {}", pollId, e);
            throw new RuntimeException("Failed to add poll option", e);
        }
    }

    @Override
    public void saveMessageId(long pollId, long messageId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE younglings.poll SET message_id = ? WHERE poll_id = ?")) {
            statement.setLong(1, messageId);
            statement.setLong(2, pollId);
            statement.executeUpdate();
            SiteInvalidator.signalLater(() -> guildOfPoll(pollId), SiteInvalidator.Area.POLLS); // the poll is posted: the last step of making one
        } catch (SQLException e) {
            log.error("Failed to save message ID for poll {}", pollId, e);
            throw new RuntimeException("Failed to save poll message ID", e);
        }
    }

    @Override
    public List<PollSession> getAllActivePolls() {
        String sql = """
                SELECT poll_id, guild_id, channel_id, message_id, title, anonymous, multiple_votes, status, created_by_user_id
                FROM younglings.poll
                WHERE status = 'ACTIVE'
                ORDER BY created_at ASC
                """;
        List<PollSession> polls = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) polls.add(mapPoll(rs));
            return polls;
        } catch (SQLException e) {
            log.error("Failed to load active polls", e);
            throw new RuntimeException("Failed to load active polls", e);
        }
    }

    /** The server of a poll, for {@code SiteInvalidator} (it runs this on its own thread). */
    private OptionalLong guildOfPoll(long pollId) {
        PollSession poll = getPollById(pollId);
        return poll == null ? OptionalLong.empty() : OptionalLong.of(poll.guildId());
    }

    /** The active polls of one server: the {@code /poll} panel and the website list read this, so a poll made by another process shows up too. */
    @Override
    public List<PollSession> getActivePolls(long guildId) {
        String sql = """
                SELECT poll_id, guild_id, channel_id, message_id, title, anonymous, multiple_votes, status, created_by_user_id
                FROM younglings.poll
                WHERE guild_id = ? AND status = 'ACTIVE'
                ORDER BY poll_id ASC
                """;
        List<PollSession> polls = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) polls.add(mapPoll(rs));
            }
            return polls;
        } catch (SQLException e) {
            log.error("Failed to get active polls for guild {}", guildId, e);
            throw new RuntimeException("Failed to get active polls", e);
        }
    }

    @Override
    public PollSession getPollById(long pollId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT poll_id, guild_id, channel_id, message_id, title, anonymous, multiple_votes, status, created_by_user_id FROM younglings.poll WHERE poll_id = ?")) {
            statement.setLong(1, pollId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapPoll(rs) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to get poll {}", pollId, e);
            throw new RuntimeException("Failed to get poll", e);
        }
    }

    public PollSession findPollByTitle(long guildId, String query) {
        String sql = """
                SELECT poll_id, guild_id, channel_id, message_id, title, anonymous, multiple_votes, status, created_by_user_id
                FROM younglings.poll
                WHERE guild_id = ? AND status = 'ACTIVE' AND LOWER(title) LIKE LOWER(?)
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setString(2, "%" + query + "%");
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapPoll(rs) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to find poll by title in guild {}", guildId, e);
            throw new RuntimeException("Failed to find poll", e);
        }
    }

    @Override
    public List<PollOption> getOptions(long pollId) {
        String sql = """
                SELECT option_id, poll_id, option_number, label
                FROM younglings.poll_option
                WHERE poll_id = ?
                ORDER BY option_number ASC
                """;
        List<PollOption> options = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pollId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) options.add(mapOption(rs));
            }
            return options;
        } catch (SQLException e) {
            log.error("Failed to get options for poll {}", pollId, e);
            throw new RuntimeException("Failed to get poll options", e);
        }
    }

    // --- Voting ---

    @Override
    public boolean addVote(long pollId, long optionId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO younglings.poll_vote (poll_id, option_id, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            statement.setLong(1, pollId);
            statement.setLong(2, optionId);
            statement.setLong(3, userId);
            boolean added = statement.executeUpdate() > 0;
            if (added) SiteInvalidator.signalLater(() -> guildOfPoll(pollId), SiteInvalidator.Area.POLLS);
            return added;
        } catch (SQLException e) {
            log.error("Failed to add vote for poll {}", pollId, e);
            throw new RuntimeException("Failed to add vote", e);
        }
    }

    @Override
    public boolean removeVote(long pollId, long optionId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM younglings.poll_vote WHERE poll_id = ? AND option_id = ? AND user_id = ?")) {
            statement.setLong(1, pollId);
            statement.setLong(2, optionId);
            statement.setLong(3, userId);
            boolean removed = statement.executeUpdate() > 0;
            if (removed) SiteInvalidator.signalLater(() -> guildOfPoll(pollId), SiteInvalidator.Area.POLLS);
            return removed;
        } catch (SQLException e) {
            log.error("Failed to remove vote for poll {}", pollId, e);
            throw new RuntimeException("Failed to remove vote", e);
        }
    }

    @Override
    public void removeAllVotesForUser(long pollId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM younglings.poll_vote WHERE poll_id = ? AND user_id = ?")) {
            statement.setLong(1, pollId);
            statement.setLong(2, userId);
            statement.executeUpdate();
            SiteInvalidator.signalLater(() -> guildOfPoll(pollId), SiteInvalidator.Area.POLLS);
        } catch (SQLException e) {
            log.error("Failed to clear votes for user {} in poll {}", userId, pollId, e);
            throw new RuntimeException("Failed to clear user votes", e);
        }
    }

    @Override
    public boolean hasVotedForOption(long pollId, long optionId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM younglings.poll_vote WHERE poll_id = ? AND option_id = ? AND user_id = ? LIMIT 1")) {
            statement.setLong(1, pollId);
            statement.setLong(2, optionId);
            statement.setLong(3, userId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check vote", e);
        }
    }

    @Override
    public boolean hasVotedInPoll(long pollId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM younglings.poll_vote WHERE poll_id = ? AND user_id = ? LIMIT 1")) {
            statement.setLong(1, pollId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check poll vote", e);
        }
    }

    /** The option ids {@code userId} currently has a vote on in this poll. */
    @Override
    public java.util.Set<Long> getUserVotes(long pollId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT option_id FROM younglings.poll_vote WHERE poll_id = ? AND user_id = ?")) {
            statement.setLong(1, pollId);
            statement.setLong(2, userId);
            java.util.Set<Long> optionIds = new java.util.HashSet<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) optionIds.add(rs.getLong("option_id"));
            }
            return optionIds;
        } catch (SQLException e) {
            log.error("Failed to get votes of user {} in poll {}", userId, pollId, e);
            throw new RuntimeException("Failed to get a user's votes", e);
        }
    }

    @Override
    public Map<Long, Integer> getVoteCounts(long pollId) {
        Map<Long, Integer> counts = new HashMap<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT option_id, COUNT(*) AS vote_count FROM younglings.poll_vote WHERE poll_id = ? GROUP BY option_id")) {
            statement.setLong(1, pollId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) counts.put(rs.getLong("option_id"), rs.getInt("vote_count"));
            }
            return counts;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get vote counts", e);
        }
    }

    @Override
    public Map<Long, List<Long>> getVotersByOption(long pollId) {
        Map<Long, List<Long>> voters = new LinkedHashMap<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT option_id, user_id FROM younglings.poll_vote WHERE poll_id = ? ORDER BY option_id, voted_at ASC")) {
            statement.setLong(1, pollId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    voters.computeIfAbsent(rs.getLong("option_id"), k -> new ArrayList<>())
                          .add(rs.getLong("user_id"));
                }
            }
            return voters;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get voters by option", e);
        }
    }

    @Override
    public void closePoll(long pollId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE younglings.poll SET status = 'CLOSED', closed_at = NOW() WHERE poll_id = ?")) {
            statement.setLong(1, pollId);
            statement.executeUpdate();
            log.info("Closed poll {}", pollId);
            SiteInvalidator.signalLater(() -> guildOfPoll(pollId), SiteInvalidator.Area.POLLS);
        } catch (SQLException e) {
            log.error("Failed to close poll {}", pollId, e);
            throw new RuntimeException("Failed to close poll", e);
        }
    }
}
