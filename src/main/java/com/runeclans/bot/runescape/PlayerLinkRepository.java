package com.runeclans.bot.runescape;

import com.runeclans.bot.internal.SiteInvalidator;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import com.runeclans.bot.config.BotBrand;

@BConfiguration
public class PlayerLinkRepository {
    private static final Logger log = LoggerFactory.getLogger(PlayerLinkRepository.class);

    private final ConnectionSupplier connectionSupplier;


    /** Stats reads answer from the hub while it polls, else from this class's own SQL (T-851, {@link StatsBackends}). */
    private volatile StatsBackends stats = StatsBackends.none();

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. The bot's own tables answer until the hub polls. */
    @BService
    public static PlayerLinkRepository playerLinkRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer, StatsBackends backends) {
        PlayerLinkRepository repository = new PlayerLinkRepository(connectionSupplier, initializer);
        repository.stats = backends;
        return repository;
    }

    /** Reads from {@code backends} while the hub polls (tests). */
    public PlayerLinkRepository withStats(StatsBackends backends) {
        this.stats = backends;
        return this;
    }
    public PlayerLinkRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    private static VerificationAttempt mapAttempt(ResultSet rs) throws SQLException {
        return new VerificationAttempt(
                rs.getLong("attempt_id"),
                rs.getLong("guild_id"),
                rs.getLong("discord_user_id"),
                rs.getString("rsn"),
                rs.getString("assigned_hairstyle"),
                rs.getString("assigned_hair_color"),
                rs.getString("assigned_skin_tone"),
                rs.getString("status")
        );
    }

    private static PlayerLink mapLink(ResultSet rs) throws SQLException {
        return new PlayerLink(
                rs.getLong("link_id"),
                rs.getLong("guild_id"),
                rs.getLong("discord_user_id"),
                rs.getString("rsn"),
                rs.getString("verification_method"),
                rs.getObject("verified_at", java.time.OffsetDateTime.class),
                rs.getObject("last_self_poll_at", java.time.OffsetDateTime.class)
        );
    }

    // --- Verification attempts ---

    public long createAttempt(long guildId, long discordUserId, String rsn,
                               String hairstyle, String hairColor, String skinTone) {
        String sql = """
                INSERT INTO younglings.player_verification_attempt
                    (guild_id, discord_user_id, rsn, assigned_hairstyle, assigned_hair_color, assigned_skin_tone)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);
            statement.setString(3, rsn);
            statement.setString(4, hairstyle);
            statement.setString(5, hairColor);
            statement.setString(6, skinTone);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }

            throw new SQLException("No attempt_id returned after creating verification attempt.");

        } catch (SQLException e) {
            log.error("Failed to create verification attempt for '{}' (guild {})", rsn, guildId, e);
            throw new RuntimeException("Failed to create verification attempt", e);
        }
    }

    public VerificationAttempt getAttempt(long attemptId) {
        String sql = """
                SELECT attempt_id, guild_id, discord_user_id, rsn, assigned_hairstyle,
                       assigned_hair_color, assigned_skin_tone, status
                FROM younglings.player_verification_attempt
                WHERE attempt_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, attemptId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapAttempt(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get verification attempt {}", attemptId, e);
            throw new RuntimeException("Failed to get verification attempt", e);
        }
    }

    public void resolveAttempt(long attemptId, String status, long resolvedByUserId) {
        String sql = """
                UPDATE younglings.player_verification_attempt
                SET status = ?, resolved_at = NOW(), resolved_by_user_id = ?
                WHERE attempt_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, status);
            statement.setLong(2, resolvedByUserId);
            statement.setLong(3, attemptId);
            statement.executeUpdate();

            log.info("Resolved verification attempt {} as {} (by {})", attemptId, status, resolvedByUserId);

        } catch (SQLException e) {
            log.error("Failed to resolve verification attempt {}", attemptId, e);
            throw new RuntimeException("Failed to resolve verification attempt", e);
        }
    }

    /** Where a request's card sits in the review channel. */
    public record ReviewCard(long channelId, long messageId) {}

    /** How a request ended, and who ended it (the requester themselves for a cancel). */
    public record Resolution(String status, Long resolvedByUserId) {}

    public void setReviewCard(long attemptId, long channelId, long messageId) {
        String sql = "UPDATE younglings.player_verification_attempt SET review_channel_id = ?, review_message_id = ? WHERE attempt_id = ?";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, channelId);
            statement.setLong(2, messageId);
            statement.setLong(3, attemptId);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to remember the review card for verification attempt {}", attemptId, e);
            throw new RuntimeException("Failed to remember the review card", e);
        }
    }

    /** The card posted for this request, or null if none was recorded (it predates this, or no review channel was set). */
    public ReviewCard getReviewCard(long attemptId) {
        String sql = "SELECT review_channel_id, review_message_id FROM younglings.player_verification_attempt WHERE attempt_id = ?";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, attemptId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                long channelId = rs.getLong("review_channel_id");
                long messageId = rs.getLong("review_message_id");
                return rs.wasNull() || channelId == 0 || messageId == 0 ? null : new ReviewCard(channelId, messageId);
            }
        } catch (SQLException e) {
            log.error("Failed to read the review card for verification attempt {}", attemptId, e);
            throw new RuntimeException("Failed to read the review card", e);
        }
    }

    /** How this request ended, or null if it doesn't exist. */
    public Resolution getResolution(long attemptId) {
        String sql = "SELECT status, resolved_by_user_id FROM younglings.player_verification_attempt WHERE attempt_id = ?";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, attemptId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                long by = rs.getLong("resolved_by_user_id");
                return new Resolution(rs.getString("status"), rs.wasNull() ? null : by);
            }
        } catch (SQLException e) {
            log.error("Failed to read how verification attempt {} ended", attemptId, e);
            throw new RuntimeException("Failed to read how a verification attempt ended", e);
        }
    }

    public List<VerificationAttempt> getPendingAttempts(long guildId) {
        String sql = """
                SELECT attempt_id, guild_id, discord_user_id, rsn, assigned_hairstyle,
                       assigned_hair_color, assigned_skin_tone, status
                FROM younglings.player_verification_attempt
                WHERE guild_id = ? AND status = 'PENDING'
                ORDER BY requested_at ASC
                """;

        List<VerificationAttempt> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapAttempt(rs));
            }

            return results;

        } catch (SQLException e) {
            log.error("Failed to get pending verification attempts for guild {}", guildId, e);
            throw new RuntimeException("Failed to get pending verification attempts", e);
        }
    }

    /** This user's own in-progress attempt, if any — so starting a new one doesn't leave orphaned duplicates behind. */
    public VerificationAttempt getPendingAttemptForUser(long guildId, long discordUserId) {
        String sql = """
                SELECT attempt_id, guild_id, discord_user_id, rsn, assigned_hairstyle,
                       assigned_hair_color, assigned_skin_tone, status
                FROM younglings.player_verification_attempt
                WHERE guild_id = ? AND discord_user_id = ? AND status = 'PENDING'
                ORDER BY requested_at DESC
                LIMIT 1
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapAttempt(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get pending verification attempt for user {}", discordUserId, e);
            throw new RuntimeException("Failed to get pending verification attempt", e);
        }
    }

    /** How an admin settled a member's link request. */
    public record Decision(long attemptId, String rsn, String status, java.time.OffsetDateTime resolvedAt) {}

    /** The member's most recent approved or turned-down request since {@code since}, or null — what the website's bell tells them about. */
    public Decision latestDecision(long guildId, long discordUserId, java.time.OffsetDateTime since) {
        String sql = """
                SELECT attempt_id, rsn, status, resolved_at
                FROM younglings.player_verification_attempt
                WHERE guild_id = ? AND discord_user_id = ? AND status IN ('APPROVED', 'REJECTED') AND resolved_at >= ?
                ORDER BY resolved_at DESC
                LIMIT 1
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);
            statement.setObject(3, since);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? new Decision(rs.getLong("attempt_id"), rs.getString("rsn"), rs.getString("status"), rs.getObject("resolved_at", java.time.OffsetDateTime.class)) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to read the latest verification decision for user {}", discordUserId, e);
            throw new RuntimeException("Failed to read the latest verification decision", e);
        }
    }

    // --- Confirmed links ---

    /** Who owns an RS account with JonnyBot, in every server at once — see {@code player_account}. */
    public record PlayerAccount(long accountId, String rsn, long discordUserId, String verificationMethod,
                                java.time.OffsetDateTime verifiedAt, Long verifiedGuildId) {
    }

    /** Thrown when an RS name already belongs to a different Discord user in another server, so this server can't take it over. */
    public static class RsnTakenException extends RuntimeException {
        private final long ownerDiscordUserId;

        public RsnTakenException(String rsn, long ownerDiscordUserId) {
            super("'" + rsn + "' is already registered with " + BotBrand.NAME + " to another Discord account.");
            this.ownerDiscordUserId = ownerDiscordUserId;
        }

        public long ownerDiscordUserId() {
            return ownerDiscordUserId;
        }
    }

    private static PlayerAccount mapAccount(ResultSet rs) throws SQLException {
        long verifiedGuild = rs.getLong("verified_guild_id");
        return new PlayerAccount(rs.getLong("account_id"), rs.getString("rsn"), rs.getLong("discord_user_id"),
                rs.getString("verification_method"), rs.getObject("verified_at", java.time.OffsetDateTime.class),
                rs.wasNull() ? null : verifiedGuild);
    }

    private static final String ACCOUNT_COLUMNS = "account_id, rsn, discord_user_id, verification_method, verified_at, verified_guild_id";

    /**
     * Registers {@code rsn} to {@code discordUserId} with JonnyBot (once, for every server) and in {@code guildId} (so it
     * shows there). A name that already belongs to the same person just gets registered in this server too. A name that
     * belongs to someone else can only be taken over if no other server has it registered: within the one server that
     * vouched for it, an admin re-linking a name to its real owner has always been how a mistake is fixed, but a different
     * server must not be able to take a name away from the person another server verified.
     *
     * @throws RsnTakenException if the name is registered to a different Discord user in another server
     */
    public void createLink(long guildId, long discordUserId, String rsn, String verificationMethod) {
        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PlayerAccount existing = findAccount(connection, rsn, true);
                if (existing == null) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO younglings.player_account (rsn, discord_user_id, verification_method, verified_guild_id)
                            VALUES (?, ?, ?, ?)
                            """)) {
                        insert.setString(1, rsn);
                        insert.setLong(2, discordUserId);
                        insert.setString(3, verificationMethod);
                        insert.setLong(4, guildId);
                        insert.executeUpdate();
                    }
                } else if (existing.discordUserId() != discordUserId) {
                    if (registeredInAnotherServer(connection, rsn, guildId)) throw new RsnTakenException(rsn, existing.discordUserId());
                    try (PreparedStatement takeOver = connection.prepareStatement("""
                            UPDATE younglings.player_account
                            SET discord_user_id = ?, verification_method = ?, verified_at = NOW(), verified_guild_id = ?
                            WHERE account_id = ?
                            """)) {
                        takeOver.setLong(1, discordUserId);
                        takeOver.setString(2, verificationMethod);
                        takeOver.setLong(3, guildId);
                        takeOver.setLong(4, existing.accountId());
                        takeOver.executeUpdate();
                    }
                }

                try (PreparedStatement link = connection.prepareStatement("""
                        INSERT INTO younglings.player_link (guild_id, discord_user_id, rsn, verification_method)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (guild_id, LOWER(rsn)) DO UPDATE SET
                            discord_user_id = EXCLUDED.discord_user_id,
                            verification_method = EXCLUDED.verification_method,
                            verified_at = NOW()
                        """)) {
                    link.setLong(1, guildId);
                    link.setLong(2, discordUserId);
                    link.setString(3, rsn);
                    link.setString(4, verificationMethod);
                    link.executeUpdate();
                }

                // linking a name in a server on purpose undoes an admin having removed it from that server before
                try (PreparedStatement allow = connection.prepareStatement(
                        "DELETE FROM younglings.player_link_exclusion WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)")) {
                    allow.setLong(1, guildId);
                    allow.setString(2, rsn);
                    allow.executeUpdate();
                }

                connection.commit();
                log.info("Linked RSN '{}' to Discord user {} in guild {}", rsn, discordUserId, guildId);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to link RSN '{}' to user {}", rsn, discordUserId, e);
            throw new RuntimeException("Failed to link RSN", e);
        }
    }

    private PlayerAccount findAccount(Connection connection, String rsn, boolean lock) throws SQLException {
        String sql = "SELECT " + ACCOUNT_COLUMNS + " FROM younglings.player_account WHERE LOWER(rsn) = LOWER(?)" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rsn);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapAccount(rs) : null;
            }
        }
    }

    private boolean registeredInAnotherServer(Connection connection, String rsn, long guildId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM younglings.player_link WHERE LOWER(rsn) = LOWER(?) AND guild_id <> ? LIMIT 1")) {
            statement.setString(1, rsn);
            statement.setLong(2, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The account registered under this RS name, in any server, or null. */
    public PlayerAccount getAccountForRsn(String rsn) {
        try (Connection connection = connectionSupplier.getConnection()) {
            return findAccount(connection, rsn, false);
        } catch (SQLException e) {
            log.error("Failed to get the account for RSN '{}'", rsn, e);
            throw new RuntimeException("Failed to get player account", e);
        }
    }

    /** Every RS name registered with JonnyBot, in any server: the second tier of the polling list. */
    public List<String> getAllAccountRsns() {
        List<String> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT rsn FROM younglings.player_account ORDER BY LOWER(rsn)");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) results.add(rs.getString(1));
            return results;
        } catch (SQLException e) {
            log.error("Failed to list the registered accounts to poll", e);
            throw new RuntimeException("Failed to list registered accounts", e);
        }
    }

    /** Every account registered with the bot, in any server, oldest first: what the hub link catch-up sends (T-839). */
    public List<PlayerAccount> getAllAccounts() {
        List<PlayerAccount> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + ACCOUNT_COLUMNS + " FROM younglings.player_account ORDER BY verified_at, account_id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) results.add(mapAccount(rs));
            return results;
        } catch (SQLException e) {
            log.error("Failed to list the registered accounts", e);
            throw new RuntimeException("Failed to list registered accounts", e);
        }
    }

    /** Every account registered to this Discord user, whichever server it was registered in. */
    public List<PlayerAccount> getAccountsForUser(long discordUserId) {
        String sql = "SELECT " + ACCOUNT_COLUMNS + " FROM younglings.player_account WHERE discord_user_id = ? ORDER BY verified_at ASC";
        List<PlayerAccount> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, discordUserId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapAccount(rs));
            }
            return results;
        } catch (SQLException e) {
            log.error("Failed to get accounts for user {}", discordUserId, e);
            throw new RuntimeException("Failed to get player accounts", e);
        }
    }

    /**
     * Registers this user's accounts in a server they haven't used JonnyBot in yet, except any an admin there removed on
     * purpose. Returns the names that were added. Running {@code /rs} is the opt-in: being in a server isn't enough to appear in it.
     */
    public List<String> adoptAccounts(long guildId, long discordUserId) {
        String sql = """
                INSERT INTO younglings.player_link (guild_id, discord_user_id, rsn, verification_method, verified_at)
                SELECT ?, a.discord_user_id, a.rsn, a.verification_method, a.verified_at
                FROM younglings.player_account a
                WHERE a.discord_user_id = ?
                  AND NOT EXISTS (SELECT 1 FROM younglings.player_link l WHERE l.guild_id = ? AND LOWER(l.rsn) = LOWER(a.rsn))
                  AND NOT EXISTS (SELECT 1 FROM younglings.player_link_exclusion x WHERE x.guild_id = ? AND LOWER(x.rsn) = LOWER(a.rsn))
                ON CONFLICT DO NOTHING
                RETURNING rsn
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);
            statement.setLong(3, guildId);
            statement.setLong(4, guildId);
            List<String> added = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) added.add(rs.getString(1));
            }
            if (!added.isEmpty()) log.info("Registered {} account(s) of Discord user {} in guild {}", added.size(), discordUserId, guildId);
            return added;
        } catch (SQLException e) {
            log.error("Failed to register accounts of user {} in guild {}", discordUserId, guildId, e);
            throw new RuntimeException("Failed to register accounts", e);
        }
    }

    public List<PlayerLink> getLinksForUser(long guildId, long discordUserId) {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method, verified_at, last_self_poll_at
                FROM younglings.player_link
                WHERE guild_id = ? AND discord_user_id = ?
                ORDER BY verified_at ASC
                """;

        List<PlayerLink> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapLink(rs));
            }

            return results;

        } catch (SQLException e) {
            log.error("Failed to get links for user {}", discordUserId, e);
            throw new RuntimeException("Failed to get player links", e);
        }
    }

    public PlayerLink getLinkForRsn(long guildId, String rsn) {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method, verified_at, last_self_poll_at
                FROM younglings.player_link
                WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapLink(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get link for RSN '{}'", rsn, e);
            throw new RuntimeException("Failed to get player link", e);
        }
    }

    /**
     * Renames the account behind an existing link, in place and in every server (the same {@code link_id}, the same
     * account), instead of deleting and recreating it — for a name change, not a re-verification, so no history should
     * look like it belonged to two different accounts. Throws if {@code newRsn} collides with a different account's RSN.
     */
    public void updateRsn(long guildId, long linkId, String newRsn) {
        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String oldRsn = rsnOfLink(connection, guildId, linkId);
                if (oldRsn == null) {
                    connection.rollback();
                    return;
                }
                for (String table : List.of("player_account", "player_link", "player_link_exclusion")) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE younglings." + table + " SET rsn = ? WHERE LOWER(rsn) = LOWER(?)")) {
                        statement.setString(1, newRsn);
                        statement.setString(2, oldRsn);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                log.info("Renamed account '{}' to '{}' (link {} in guild {})", oldRsn, newRsn, linkId, guildId);
                SiteInvalidator.signal(guildId, SiteInvalidator.Area.ROSTER);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to rename link {} to '{}'", linkId, newRsn, e);
            throw new RuntimeException("Failed to rename link", e);
        }
    }

    private String rsnOfLink(Connection connection, long guildId, long linkId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rsn FROM younglings.player_link WHERE guild_id = ? AND link_id = ?")) {
            statement.setLong(1, guildId);
            statement.setLong(2, linkId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Takes the account out of every server, and off JonnyBot — what its owner choosing to unlink means. Its history stays,
     * since that is RuneScape's data about a name, not theirs.
     */
    public void deleteAccount(long guildId, long linkId) {
        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String rsn = rsnOfLink(connection, guildId, linkId);
                if (rsn == null) {
                    connection.rollback();
                    return;
                }
                for (String table : List.of("player_link", "player_link_exclusion", "player_account")) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM younglings." + table + " WHERE LOWER(rsn) = LOWER(?)")) {
                        statement.setString(1, rsn);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                log.info("Unlinked '{}' everywhere (link {} in guild {})", rsn, linkId, guildId);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to unlink {}", linkId, e);
            throw new RuntimeException("Failed to unlink RSN", e);
        }
    }

    /**
     * Takes a name out of one server only — what a server's admin removing a link means. The account stays with its
     * owner and in their other servers, and {@code /rs} here won't put it back unless they link it again on purpose.
     */
    public void deleteLink(long guildId, long linkId) {
        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String rsn = rsnOfLink(connection, guildId, linkId);
                if (rsn == null) {
                    connection.rollback();
                    return;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM younglings.player_link WHERE guild_id = ? AND link_id = ?")) {
                    statement.setLong(1, guildId);
                    statement.setLong(2, linkId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO younglings.player_link_exclusion (guild_id, rsn) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                    statement.setLong(1, guildId);
                    statement.setString(2, rsn);
                    statement.executeUpdate();
                }
                connection.commit();
                log.info("Unlinked player_link {} in guild {}", linkId, guildId);
                SiteInvalidator.signal(guildId, SiteInvalidator.Area.ROSTER);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to unlink {}", linkId, e);
            throw new RuntimeException("Failed to unlink RSN", e);
        }
    }

    /**
     * Stamps {@code last_self_poll_at} to now — called only from the member's own "Poll Now" click under {@code /rs}, never from an admin
     * or auto poll. The stamp goes on every server's link to the same RS name, because a player's stats are global: polling from a second
     * server straight after the first would only spend another request on data that was just refreshed.
     */
    public void recordSelfPoll(long linkId) {
        String sql = "UPDATE younglings.player_link SET last_self_poll_at = NOW() WHERE LOWER(rsn) = (SELECT LOWER(rsn) FROM younglings.player_link WHERE link_id = ?)";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, linkId);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to record self-poll for link {}", linkId, e);
            throw new RuntimeException("Failed to record self-poll", e);
        }
    }

    /** All currently-linked RSNs across the guild, for the stats scheduler to poll. */
    public List<PlayerLink> getAllLinks(long guildId) {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method, verified_at, last_self_poll_at
                FROM younglings.player_link
                WHERE guild_id = ?
                """;

        List<PlayerLink> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapLink(rs));
            }

            return results;

        } catch (SQLException e) {
            log.error("Failed to get all player links for guild {}", guildId, e);
            throw new RuntimeException("Failed to get all player links", e);
        }
    }

    // --- Stats snapshots ---

    /** Per-skill breakdown for one snapshot, ordered by skill ID (the game's own skill order). */
    public List<SkillValue> getSkillsForSnapshot(long snapshotId) {
        return stats.backend().skillsForSnapshot(snapshotId);
    }

    /** Every snapshot since {@code since}, oldest first — used to find "first vs. latest this period" totals. */
    public List<StatsSnapshotRow> getSnapshotsSince(String rsn, java.time.OffsetDateTime since) {
        return stats.backend().snapshotsSince(rsn, since);
    }

    /** Every skill's XP at every poll since {@code since}, oldest first — grouped/reduced in Java to find per-skill gains over the period. */
    public List<SkillHistoryPoint> getAllSkillsXpHistorySince(String rsn, java.time.OffsetDateTime since) {
        return stats.backend().allSkillsXpHistorySince(rsn, since);
    }

    public record SkillHistoryPoint(int skillId, java.time.OffsetDateTime timestamp, long xp) {
    }

    /** Activities recorded since {@code since}, oldest first — the monthly recap's data source for "times capped" and "most challenged". */
    public List<PlayerActivity> getActivitiesSince(String rsn, java.time.OffsetDateTime since) {
        return stats.backend().activitiesSince(rsn, since);
    }

    /** One skill's XP at each poll since {@code since}, oldest first — the XP-over-time chart's data source. */
    public List<SkillXpPoint> getSkillXpHistory(String rsn, int skillId, java.time.OffsetDateTime since) {
        return stats.backend().skillXpHistory(rsn, skillId, since);
    }

    /** Most recent snapshot for {@code rsn}, or {@code null} if it's never been polled. */
    public StatsSnapshotRow getLatestSnapshot(String rsn) {
        return stats.backend().latest(rsn);
    }

    /**
     * The most recent snapshot of each of these names at once (a name that has never been polled is missing from the answer), keyed by the lower-cased name. One
     * query for a whole roster instead of one per player.
     */
    public java.util.Map<String, StatsSnapshotRow> getLatestSnapshots(java.util.Collection<String> rsns) {
        return stats.backend().latestMany(rsns);
    }

    /** Every snapshot for {@code rsn}, most recent first, capped at {@code limit} — the trend/history view's data source. */
    public List<StatsSnapshotRow> getSnapshotHistory(String rsn, int limit) {
        return stats.backend().history(rsn, limit);
    }


    public record StatsSnapshotRow(long snapshotId, java.time.OffsetDateTime snapshotAt, int totalLevel, long totalXp,
                                    int combatLevel, int questsComplete, int questsStarted, int questsNotStarted) {
    }

    // --- Activity history ---

    /** Most recently *recorded* activities for {@code rsn} (i.e. by when we first saw them, not the in-game date string). */
    public List<PlayerActivity> getRecentActivities(String rsn, int limit) {
        return stats.backend().recentActivities(rsn, limit);
    }
}
