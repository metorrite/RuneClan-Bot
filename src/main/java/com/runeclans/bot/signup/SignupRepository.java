package com.runeclans.bot.signup;

import com.runeclans.bot.internal.SiteInvalidator;
import com.runeclans.bot.commands.signup.SignupEntry;
import com.runeclans.bot.commands.signup.SignupMessage;
import com.runeclans.bot.commands.signup.SignupSession;
import com.runeclans.bot.commands.signup.SignupType;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

@BService
public class SignupRepository implements SignupStore {
    private static final Logger log = LoggerFactory.getLogger(SignupRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public SignupRepository(ConnectionSupplier connectionSupplier, SignupDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    // --- Mappers ---

    private static SignupSession mapSignupSession(ResultSet rs) throws SQLException {
        Integer maxSignups = rs.getObject("max_signups") == null ? null : rs.getInt("max_signups");
        Long groupRoleId = rs.getObject("group_role_id") == null ? null : rs.getLong("group_role_id");
        SignupType type = SignupType.valueOf(rs.getString("signup_type"));
        return new SignupSession(
                rs.getLong("signup_id"),
                rs.getLong("guild_id"),
                rs.getString("title"),
                rs.getString("notification_message"),
                maxSignups,
                type,
                rs.getString("submission_field_label"), // stored as serialized SubmissionField list
                groupRoleId
        );
    }

    private static SignupMessage mapSignupMessage(ResultSet rs) throws SQLException {
        return new SignupMessage(
                rs.getLong("message_id"),
                rs.getLong("signup_id"),
                rs.getLong("guild_id"),
                rs.getLong("channel_id"),
                rs.getString("message_type")
        );
    }

    private static SignupEntry mapSignupEntry(ResultSet rs) throws SQLException {
        return new SignupEntry(
                rs.getLong("discord_user_id"),
                rs.getString("rsn"),
                rs.getString("submission_value")
        );
    }

    // --- Signup CRUD ---

    @Override
    public long createSignup(long guildId, String title, SignupType type, String notificationMessage,
                              String submissionFieldLabel, Integer maxSignups, long createdByUserId,
                              Long groupRoleId) {
        String sql = """
                INSERT INTO younglings.signup
                    (guild_id, title, signup_type, notification_message, submission_field_label,
                     max_signups, created_by_user_id, group_role_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING signup_id
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, title);
            statement.setString(3, type.name());
            statement.setString(4, notificationMessage);
            statement.setString(5, submissionFieldLabel);

            if (maxSignups == null) {
                statement.setNull(6, Types.INTEGER);
            } else {
                statement.setInt(6, maxSignups);
            }

            statement.setLong(7, createdByUserId);

            if (groupRoleId == null) {
                statement.setNull(8, Types.BIGINT);
            } else {
                statement.setLong(8, groupRoleId);
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    long signupId = resultSet.getLong("signup_id");
                    log.info("Created {} signup {} for guild {}", type, signupId, guildId);
                    SiteInvalidator.signal(guildId, SiteInvalidator.Area.SIGNUPS);
                    return signupId;
                }
            }

            throw new SQLException("No signup_id returned after creating signup.");

        } catch (SQLException e) {
            log.error("Failed to create signup for guild {}", guildId, e);
            throw new RuntimeException("Failed to create signup", e);
        }
    }

    @Override
    public void updateGroupRoleId(long signupId, long roleId) {
        String sql = """
                UPDATE younglings.signup
                SET group_role_id = ?
                WHERE signup_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, roleId);
            statement.setLong(2, signupId);
            statement.executeUpdate();

            log.info("Saved group role {} for signup {}", roleId, signupId);

        } catch (SQLException e) {
            log.error("Failed to update group role ID for signup {}", signupId, e);
            throw new RuntimeException("Failed to update group role ID", e);
        }
    }

    @Override
    public List<SignupSession> getOpenSignups() {
        String sql = """
                SELECT signup_id, guild_id, title, notification_message, max_signups,
                       signup_type, submission_field_label, group_role_id
                FROM younglings.signup
                WHERE deleted_at IS NULL
                ORDER BY created_at ASC
                """;

        List<SignupSession> signups = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                signups.add(mapSignupSession(resultSet));
            }

            log.info("Loaded {} open signups from database.", signups.size());
            return signups;

        } catch (SQLException e) {
            log.error("Failed to load open signups from database.", e);
            throw new RuntimeException("Failed to load open signups", e);
        }
    }

    /** One open (not deleted) signup by id, or null: the load-through behind {@code SignupService.getSessionById}. */
    @Override
    public SignupSession getOpenSignupById(long signupId) {
        String sql = """
                SELECT signup_id, guild_id, title, notification_message, max_signups,
                       signup_type, submission_field_label, group_role_id
                FROM younglings.signup
                WHERE signup_id = ?
                  AND deleted_at IS NULL
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapSignupSession(resultSet) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get signup {}", signupId, e);
            throw new RuntimeException("Failed to get signup", e);
        }
    }

    /** Signups untouched (no new entries) since before {@code cutoff} — candidates for auto-close. */
    @Override
    public List<SignupSession> getInactiveSignups(Instant cutoff) {
        String sql = """
                SELECT signup_id, guild_id, title, notification_message, max_signups,
                       signup_type, submission_field_label, group_role_id
                FROM younglings.signup
                WHERE deleted_at IS NULL
                  AND last_activity_at < ?
                ORDER BY signup_id ASC
                """;

        List<SignupSession> signups = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setTimestamp(1, Timestamp.from(cutoff));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    signups.add(mapSignupSession(resultSet));
                }
            }

            return signups;

        } catch (SQLException e) {
            log.error("Failed to get inactive signups older than {}", cutoff, e);
            throw new RuntimeException("Failed to get inactive signups", e);
        }
    }

    /** Hard-deletes (cascading to entries/messages) signups soft-deleted before {@code cutoff}. Returns the count purged. */
    @Override
    public int purgeDeletedBefore(Instant cutoff) {
        String sql = """
                DELETE FROM younglings.signup
                WHERE deleted_at IS NOT NULL
                  AND deleted_at < ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setTimestamp(1, Timestamp.from(cutoff));
            int purged = statement.executeUpdate();

            if (purged > 0) log.info("Purged {} signup(s) soft-deleted before {}", purged, cutoff);
            return purged;

        } catch (SQLException e) {
            log.error("Failed to purge signups deleted before {}", cutoff, e);
            throw new RuntimeException("Failed to purge deleted signups", e);
        }
    }

    @Override
    public String getSignupStatus(long signupId) {
        String sql = """
                SELECT status
                FROM younglings.signup
                WHERE signup_id = ?
                  AND deleted_at IS NULL
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get signup status for {}", signupId, e);
            throw new RuntimeException("Failed to get signup status", e);
        }
    }

    @Override
    public void deleteSignupForAdminArchive(long signupId) {
        String updateSignupSql = """
                UPDATE younglings.signup
                SET status = 'DELETED',
                    deleted_at = NOW()
                WHERE signup_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(updateSignupSql)) {
                    statement.setLong(1, signupId);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM younglings.signup_entry
                        WHERE signup_id = ?
                        """)) {
                    statement.setLong(1, signupId);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE younglings.signup_message
                        SET active = FALSE
                        WHERE signup_id = ?
                        """)) {
                    statement.setLong(1, signupId);
                    statement.executeUpdate();
                }

                connection.commit();
                log.info("Deleted signup {}: status DELETED, entries cleared, all messages inactive", signupId);

            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {
            log.error("Failed to delete signup {}", signupId, e);
            throw new RuntimeException("Failed to delete signup", e);
        }
    }

    @Override
    public void setSignupStatus(long signupId, String status) {
        String sql = """
                UPDATE younglings.signup
                SET status = ?
                WHERE signup_id = ?
                  AND deleted_at IS NULL
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, status);
            statement.setLong(2, signupId);
            statement.executeUpdate();

            log.info("Set signup {} status to {}", signupId, status);
            SiteInvalidator.signalLater(() -> guildOfSignup(signupId), SiteInvalidator.Area.SIGNUPS);

        } catch (SQLException e) {
            log.error("Failed to set signup {} status to {}", signupId, status, e);
            throw new RuntimeException("Failed to set signup status", e);
        }
    }

    @Override
    public void softDeleteSignup(long signupId) {
        String sql = """
                UPDATE younglings.signup
                SET deleted_at = NOW(),
                    status = 'DELETED'
                WHERE signup_id = ?
                  AND deleted_at IS NULL
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);
            statement.executeUpdate();

            log.info("Soft deleted signup {}", signupId);
            SiteInvalidator.signalLater(() -> guildOfSignup(signupId), SiteInvalidator.Area.SIGNUPS);

        } catch (SQLException e) {
            log.error("Failed to delete signup {}", signupId, e);
            throw new RuntimeException("Failed to delete signup", e);
        }
    }

    /** The server of a signup sheet, for {@code SiteInvalidator} (it runs this on its own thread). */
    private OptionalLong guildOfSignup(long signupId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT guild_id FROM younglings.signup WHERE signup_id = ?")) {
            statement.setLong(1, signupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? OptionalLong.of(resultSet.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the server of signup " + signupId, e);
        }
    }

    @Override
    public List<SignupSession> getVisibleSignups(long guildId) {
        String sql = """
                SELECT signup_id, guild_id, title, notification_message, max_signups,
                       signup_type, submission_field_label, group_role_id
                FROM younglings.signup
                WHERE guild_id = ?
                  AND deleted_at IS NULL
                ORDER BY created_at ASC
                """;

        List<SignupSession> signups = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    signups.add(mapSignupSession(resultSet));
                }
            }

            return signups;

        } catch (SQLException e) {
            log.error("Failed to get visible signups for guild {}", guildId, e);
            throw new RuntimeException("Failed to get visible signups", e);
        }
    }

    @Override
    public List<SignupSession> searchVisibleSignups(long guildId, String query) {
        String sql = """
                SELECT signup_id, guild_id, title, notification_message, max_signups,
                       signup_type, submission_field_label, group_role_id
                FROM younglings.signup
                WHERE guild_id = ?
                  AND deleted_at IS NULL
                  AND LOWER(title) LIKE LOWER(?)
                ORDER BY created_at ASC
                LIMIT 25
                """;

        List<SignupSession> signups = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, "%" + query + "%");

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    signups.add(mapSignupSession(resultSet));
                }
            }

            return signups;

        } catch (SQLException e) {
            log.error("Failed to search signups for guild {}", guildId, e);
            throw new RuntimeException("Failed to search signups", e);
        }
    }

    // --- Entry management ---

    @Override
    public int getEntryCount(long signupId) {
        String sql = "SELECT COUNT(*) FROM younglings.signup_entry WHERE signup_id = ?";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, signupId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to get entry count for signup {}", signupId, e);
            throw new RuntimeException("Failed to get entry count", e);
        }
    }

    /**
     * Adds a signup entry, enforcing per-type uniqueness rules (duplicate user for QUEUE/GROUP,
     * duplicate case-insensitive RSN for QUEUE). The duplicate checks and the insert run inside a
     * single transaction guarded by a {@code pg_advisory_xact_lock} keyed on {@code signupId}, so
     * concurrent calls for the same signup are serialized and can't both pass the check before
     * either commits — closing the check-then-insert race without requiring a schema change
     * (there's no DB-level unique constraint backing this since SUBMISSION signups intentionally
     * allow multiple entries per user).
     */
    @Override
    public boolean addEntry(long signupId, long discordUserId, String rsn, String submissionValue,
                             long addedByUserId, SignupType type) {
        String insertSql = """
                INSERT INTO younglings.signup_entry
                    (signup_id, discord_user_id, rsn, queue_position, added_by_user_id, submission_value)
                VALUES (
                    ?, ?, ?,
                    COALESCE((SELECT MAX(queue_position) + 1 FROM younglings.signup_entry WHERE signup_id = ?), 1),
                    ?, ?
                )
                """;

        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockSignupForEntryChanges(connection, signupId);

                // SUBMISSION allows multiple entries per user; QUEUE and GROUP do not
                if (type != SignupType.SUBMISSION && isUserInSignup(connection, signupId, discordUserId)) {
                    connection.rollback();
                    log.info("Skipped duplicate user {} for signup {}", discordUserId, signupId);
                    return false;
                }

                // QUEUE enforces case-insensitive RSN uniqueness
                if (type == SignupType.QUEUE && isRsnInSignup(connection, signupId, rsn)) {
                    connection.rollback();
                    log.info("Skipped duplicate RSN '{}' for signup {}", rsn, signupId);
                    return false;
                }

                boolean added;
                try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                    statement.setLong(1, signupId);
                    statement.setLong(2, discordUserId);
                    statement.setString(3, rsn);
                    statement.setLong(4, signupId);
                    statement.setLong(5, addedByUserId);
                    statement.setString(6, submissionValue);
                    added = statement.executeUpdate() > 0;
                }

                if (added) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE younglings.signup SET last_activity_at = NOW() WHERE signup_id = ?")) {
                        statement.setLong(1, signupId);
                        statement.executeUpdate();
                    }
                }

                connection.commit();
                if (added) {
                    log.info("Added entry rsn='{}' / Discord {} to signup {}", rsn, discordUserId, signupId);
                    SiteInvalidator.signalLater(() -> guildOfSignup(signupId), SiteInvalidator.Area.SIGNUPS);
                }
                return added;

            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to add entry to signup {}", signupId, e);
            throw new RuntimeException("Failed to add signup entry", e);
        }
    }

    /**
     * Takes a transaction-scoped Postgres advisory lock keyed on {@code signupId}. Automatically
     * released on commit/rollback. Used to serialize concurrent entry-changing operations for the
     * same signup (see {@link #addEntry}).
     */
    private void lockSignupForEntryChanges(Connection connection, long signupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, signupId);
            statement.execute();
        }
    }

    private boolean isUserInSignup(Connection connection, long signupId, long discordUserId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM younglings.signup_entry WHERE signup_id = ? AND discord_user_id = ? LIMIT 1")) {
            statement.setLong(1, signupId);
            statement.setLong(2, discordUserId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next(); }
        }
    }

    private boolean isRsnInSignup(Connection connection, long signupId, String rsn) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM younglings.signup_entry WHERE signup_id = ? AND LOWER(rsn) = LOWER(?) LIMIT 1")) {
            statement.setLong(1, signupId);
            statement.setString(2, rsn);
            try (ResultSet rs = statement.executeQuery()) { return rs.next(); }
        }
    }

    @Override
    public boolean removeEntryByUserId(long signupId, long discordUserId) {
        String sql = """
                DELETE FROM younglings.signup_entry
                WHERE signup_id = ?
                  AND discord_user_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);
            statement.setLong(2, discordUserId);

            boolean removed = statement.executeUpdate() > 0;

            if (removed) {
                normalizePositions(connection, signupId);
                log.info("Removed Discord user {} from signup {}", discordUserId, signupId);
                SiteInvalidator.signalLater(() -> guildOfSignup(signupId), SiteInvalidator.Area.SIGNUPS);
            }

            return removed;

        } catch (SQLException e) {
            log.error("Failed to remove Discord user {} from signup {}", discordUserId, signupId, e);
            throw new RuntimeException("Failed to remove signup entry", e);
        }
    }

    @Override
    public List<SignupEntry> getEntries(long signupId) {
        String sql = """
                SELECT discord_user_id, rsn, submission_value
                FROM younglings.signup_entry
                WHERE signup_id = ?
                ORDER BY queue_position ASC
                """;

        List<SignupEntry> entries = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(mapSignupEntry(resultSet));
                }
            }

            return entries;

        } catch (SQLException e) {
            log.error("Failed to get entries for signup {}", signupId, e);
            throw new RuntimeException("Failed to get signup entries", e);
        }
    }

    @Override
    public SignupEntry getFirstEntry(long signupId) {
        List<SignupEntry> entries = getEntries(signupId);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    @Override
    public SignupEntry next(long signupId) {
        String deleteSql = """
                DELETE FROM younglings.signup_entry
                WHERE entry_id = (
                    SELECT entry_id
                    FROM younglings.signup_entry
                    WHERE signup_id = ?
                    ORDER BY queue_position ASC
                    LIMIT 1
                )
                """;

        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
                    statement.setLong(1, signupId);
                    statement.executeUpdate();
                }

                normalizePositions(connection, signupId);
                connection.commit();
                log.info("Advanced signup {}", signupId);

            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }

            return getFirstEntry(signupId);

        } catch (SQLException e) {
            log.error("Failed to advance signup {}", signupId, e);
            throw new RuntimeException("Failed to advance signup", e);
        }
    }

    @Override
    public SignupEntry removeFirst(long signupId) {
        return next(signupId);
    }

    @Override
    public SignupEntry skipFirst(long signupId) {
        String selectSql = """
                SELECT entry_id
                FROM younglings.signup_entry
                WHERE signup_id = ?
                ORDER BY queue_position ASC
                LIMIT 2
                """;

        String updateSql = """
                UPDATE younglings.signup_entry
                SET queue_position = ?
                WHERE entry_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long firstId;
                long secondId;

                try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                    statement.setLong(1, signupId);

                    try (ResultSet rs = statement.executeQuery()) {
                        if (!rs.next()) return null;
                        firstId = rs.getLong("entry_id");

                        if (!rs.next()) return null;
                        secondId = rs.getLong("entry_id");
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    statement.setInt(1, 2);
                    statement.setLong(2, firstId);
                    statement.executeUpdate();

                    statement.setInt(1, 1);
                    statement.setLong(2, secondId);
                    statement.executeUpdate();
                }

                normalizePositions(connection, signupId);
                connection.commit();
                log.info("Skipped first entry for signup {}", signupId);

            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }

            return getFirstEntry(signupId);

        } catch (SQLException e) {
            log.error("Failed to skip first entry for signup {}", signupId, e);
            throw new RuntimeException("Failed to skip signup entry", e);
        }
    }

    @Override
    public void clearEntries(long signupId) {
        String sql = """
                DELETE FROM younglings.signup_entry
                WHERE signup_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);
            int deleted = statement.executeUpdate();

            log.info("Cleared {} entries from signup {}", deleted, signupId);

        } catch (SQLException e) {
            log.error("Failed to clear signup {}", signupId, e);
            throw new RuntimeException("Failed to clear signup", e);
        }
    }

    private void normalizePositions(Connection connection, long signupId) throws SQLException {
        String sql = """
                WITH ordered AS (
                    SELECT entry_id,
                           ROW_NUMBER() OVER (ORDER BY queue_position ASC, entry_id ASC) AS new_position
                    FROM younglings.signup_entry
                    WHERE signup_id = ?
                )
                UPDATE younglings.signup_entry entry
                SET queue_position = ordered.new_position
                FROM ordered
                WHERE entry.entry_id = ordered.entry_id
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, signupId);
            statement.executeUpdate();
        }
    }

    // --- Message management ---

    @Override
    public void saveMessage(long signupId, long guildId, long channelId, long messageId, String messageType) {
        String sql = """
                INSERT INTO younglings.signup_message
                    (message_id, signup_id, guild_id, channel_id, message_type)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (message_id) DO UPDATE SET
                    signup_id = EXCLUDED.signup_id,
                    guild_id = EXCLUDED.guild_id,
                    channel_id = EXCLUDED.channel_id,
                    message_type = EXCLUDED.message_type,
                    active = TRUE
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, messageId);
            statement.setLong(2, signupId);
            statement.setLong(3, guildId);
            statement.setLong(4, channelId);
            statement.setString(5, messageType);
            statement.executeUpdate();

            log.info("Saved {} signup message {} for signup {}", messageType, messageId, signupId);

        } catch (SQLException e) {
            log.error("Failed to save signup message {} for signup {}", messageId, signupId, e);
            throw new RuntimeException("Failed to save signup message", e);
        }
    }

    @Override
    public List<SignupMessage> getActiveMessages(long signupId) {
        String sql = """
                SELECT message_id, signup_id, guild_id, channel_id, message_type
                FROM younglings.signup_message
                WHERE signup_id = ?
                  AND active = TRUE
                """;

        List<SignupMessage> messages = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(mapSignupMessage(resultSet));
                }
            }

            return messages;

        } catch (SQLException e) {
            log.error("Failed to get active messages for signup {}", signupId, e);
            throw new RuntimeException("Failed to get signup messages", e);
        }
    }

    @Override
    public List<SignupMessage> getActivePingMessages(long signupId) {
        String sql = """
                SELECT message_id, signup_id, guild_id, channel_id, message_type
                FROM younglings.signup_message
                WHERE signup_id = ?
                  AND active = TRUE
                  AND message_type = 'PING'
                """;

        List<SignupMessage> messages = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(mapSignupMessage(resultSet));
                }
            }

            return messages;

        } catch (SQLException e) {
            log.error("Failed to get ping messages for signup {}", signupId, e);
            throw new RuntimeException("Failed to get ping messages", e);
        }
    }

    @Override
    public SignupMessage getFirstActivePublicMessage(long signupId) {
        String sql = """
                SELECT message_id, signup_id, guild_id, channel_id, message_type
                FROM younglings.signup_message
                WHERE signup_id = ?
                  AND active = TRUE
                  AND message_type = 'PUBLIC'
                ORDER BY created_at ASC
                LIMIT 1
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return mapSignupMessage(resultSet);
                }
            }

            return null;

        } catch (SQLException e) {
            log.error("Failed to get public message for signup {}", signupId, e);
            throw new RuntimeException("Failed to get public signup message", e);
        }
    }

    @Override
    public void markMessageInactive(long messageId) {
        String sql = """
                UPDATE younglings.signup_message
                SET active = FALSE
                WHERE message_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, messageId);
            statement.executeUpdate();

            log.info("Marked signup message {} inactive", messageId);

        } catch (SQLException e) {
            log.error("Failed to mark signup message {} inactive", messageId, e);
            throw new RuntimeException("Failed to mark signup message inactive", e);
        }
    }
}
