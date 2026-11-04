package com.runeclans.bot.runescape;

import com.runeclans.bot.internal.SiteInvalidator;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import com.runeclans.bot.runescape.stats.StatsBackend;
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

    /** Saves a snapshot and returns its generated {@code snapshot_id}, so per-skill rows can reference it. */
    public long saveSnapshot(String rsn, RuneScapeProfile profile, String skillsJson) {
        String sql = """
                INSERT INTO younglings.player_stats_snapshot
                    (rsn, total_level, total_xp, combat_level, quests_complete,
                     quests_started, quests_not_started, skills_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, rsn);
            statement.setInt(2, profile.totalLevel());
            statement.setLong(3, profile.totalXp());
            statement.setInt(4, profile.combatLevel());
            statement.setInt(5, profile.questsComplete());
            statement.setInt(6, profile.questsStarted());
            statement.setInt(7, profile.questsNotStarted());
            statement.setString(8, skillsJson);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("No snapshot_id returned after saving stats snapshot.");

        } catch (SQLException e) {
            log.error("Failed to save stats snapshot for '{}'", rsn, e);
            throw new RuntimeException("Failed to save stats snapshot", e);
        }
    }

    /**
     * Same as {@link #saveSnapshot}, but with an explicit {@code snapshotAt} instead of relying on
     * the column's {@code NOW()} default — only real callers need "right now"; backfilling test/
     * historical data needs to place rows in the past instead.
     */
    public long saveSnapshotAt(String rsn, java.time.OffsetDateTime snapshotAt, RuneScapeProfile profile, String skillsJson) {
        String sql = """
                INSERT INTO younglings.player_stats_snapshot
                    (rsn, snapshot_at, total_level, total_xp, combat_level, quests_complete,
                     quests_started, quests_not_started, skills_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, rsn);
            statement.setObject(2, snapshotAt);
            statement.setInt(3, profile.totalLevel());
            statement.setLong(4, profile.totalXp());
            statement.setInt(5, profile.combatLevel());
            statement.setInt(6, profile.questsComplete());
            statement.setInt(7, profile.questsStarted());
            statement.setInt(8, profile.questsNotStarted());
            statement.setString(9, skillsJson);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("No snapshot_id returned after saving stats snapshot.");

        } catch (SQLException e) {
            log.error("Failed to save backdated stats snapshot for '{}'", rsn, e);
            throw new RuntimeException("Failed to save backdated stats snapshot", e);
        }
    }

    public void saveSkillSnapshot(long snapshotId, List<SkillValue> skills) {
        if (skills.isEmpty()) return;

        String sql = """
                INSERT INTO younglings.player_skill_snapshot (snapshot_id, skill_id, level, xp, rank)
                VALUES (?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            for (SkillValue skill : skills) {
                statement.setLong(1, snapshotId);
                statement.setInt(2, skill.skillId());
                statement.setInt(3, skill.level());
                statement.setLong(4, skill.xp());
                statement.setInt(5, skill.rank());
                statement.addBatch();
            }
            statement.executeBatch();

        } catch (SQLException e) {
            log.error("Failed to save skill snapshot rows for snapshot {}", snapshotId, e);
            throw new RuntimeException("Failed to save skill snapshot", e);
        }
    }

    /** Per-skill breakdown for one snapshot, ordered by skill ID (the game's own skill order). */
    public List<SkillValue> getSkillsForSnapshot(long snapshotId) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.skillsForSnapshot(snapshotId);
        String sql = """
                SELECT skill_id, level, xp, rank
                FROM younglings.player_skill_snapshot
                WHERE snapshot_id = ?
                ORDER BY skill_id
                """;

        List<SkillValue> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, snapshotId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new SkillValue(rs.getInt("skill_id"), rs.getInt("level"),
                            rs.getLong("xp"), rs.getInt("rank")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get skills for snapshot {}", snapshotId, e);
            throw new RuntimeException("Failed to get skill snapshot", e);
        }
    }

    /** Every snapshot since {@code since}, oldest first — used to find "first vs. latest this period" totals. */
    public List<StatsSnapshotRow> getSnapshotsSince(String rsn, java.time.OffsetDateTime since) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.snapshotsSince(rsn, since);
        String sql = """
                SELECT snapshot_id, snapshot_at, total_level, total_xp, combat_level,
                       quests_complete, quests_started, quests_not_started
                FROM younglings.player_stats_snapshot
                WHERE LOWER(rsn) = LOWER(?) AND snapshot_at >= ?
                ORDER BY snapshot_at ASC
                """;

        List<StatsSnapshotRow> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, rsn);
            statement.setObject(2, since);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapSnapshotRow(rs));
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get snapshots since {} for '{}'", since, rsn, e);
            throw new RuntimeException("Failed to get snapshots since", e);
        }
    }

    /** Every skill's XP at every poll since {@code since}, oldest first — grouped/reduced in Java to find per-skill gains over the period. */
    public List<SkillHistoryPoint> getAllSkillsXpHistorySince(String rsn, java.time.OffsetDateTime since) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.allSkillsXpHistorySince(rsn, since);
        String sql = """
                SELECT sk.skill_id, s.snapshot_at, sk.xp
                FROM younglings.player_skill_snapshot sk
                JOIN younglings.player_stats_snapshot s ON s.snapshot_id = sk.snapshot_id
                WHERE LOWER(s.rsn) = LOWER(?) AND s.snapshot_at >= ?
                ORDER BY sk.skill_id, s.snapshot_at ASC
                """;

        List<SkillHistoryPoint> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, rsn);
            statement.setObject(2, since);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new SkillHistoryPoint(rs.getInt("skill_id"),
                            rs.getObject("snapshot_at", java.time.OffsetDateTime.class), rs.getLong("xp")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get all-skills XP history since {} for '{}'", since, rsn, e);
            throw new RuntimeException("Failed to get all-skills XP history", e);
        }
    }

    public record SkillHistoryPoint(int skillId, java.time.OffsetDateTime timestamp, long xp) {
    }

    /** Activities recorded since {@code since}, oldest first — the monthly recap's data source for "times capped" and "most challenged". */
    public List<PlayerActivity> getActivitiesSince(String rsn, java.time.OffsetDateTime since) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.activitiesSince(rsn, since);
        String sql = """
                SELECT activity_date, activity_text, activity_details
                FROM younglings.player_activity
                WHERE LOWER(rsn) = LOWER(?) AND recorded_at >= ?
                ORDER BY recorded_at ASC
                """;

        List<PlayerActivity> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, rsn);
            statement.setObject(2, since);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new PlayerActivity(rs.getString("activity_date"),
                            rs.getString("activity_text"), rs.getString("activity_details")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get activities since {} for '{}'", since, rsn, e);
            throw new RuntimeException("Failed to get activities since", e);
        }
    }

    /** One skill's XP at each poll since {@code since}, oldest first — the XP-over-time chart's data source. */
    public List<SkillXpPoint> getSkillXpHistory(String rsn, int skillId, java.time.OffsetDateTime since) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.skillXpHistory(rsn, skillId, since);
        String sql = """
                SELECT s.snapshot_at, sk.xp
                FROM younglings.player_skill_snapshot sk
                JOIN younglings.player_stats_snapshot s ON s.snapshot_id = sk.snapshot_id
                WHERE LOWER(s.rsn) = LOWER(?) AND sk.skill_id = ? AND s.snapshot_at >= ?
                ORDER BY s.snapshot_at ASC
                """;

        List<SkillXpPoint> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, rsn);
            statement.setInt(2, skillId);
            statement.setObject(3, since);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new SkillXpPoint(rs.getObject("snapshot_at", java.time.OffsetDateTime.class), rs.getLong("xp")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get skill XP history for '{}' skill {}", rsn, skillId, e);
            throw new RuntimeException("Failed to get skill XP history", e);
        }
    }

    /** Most recent snapshot for {@code rsn}, or {@code null} if it's never been polled. */
    public StatsSnapshotRow getLatestSnapshot(String rsn) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.latest(rsn);
        String sql = """
                SELECT snapshot_id, snapshot_at, total_level, total_xp, combat_level,
                       quests_complete, quests_started, quests_not_started
                FROM younglings.player_stats_snapshot
                WHERE LOWER(rsn) = LOWER(?)
                ORDER BY snapshot_at DESC
                LIMIT 1
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, rsn);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapSnapshotRow(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get latest snapshot for '{}'", rsn, e);
            throw new RuntimeException("Failed to get latest stats snapshot", e);
        }
    }

    /** Which of these names (lower-cased) have any stats snapshot at all: the "first polls" check, lighter than {@link #getLatestSnapshots}. */
    public java.util.Set<String> namesWithSnapshot(java.util.Collection<String> rsns) {
        java.util.Set<String> results = new java.util.HashSet<>();
        if (rsns.isEmpty()) return results;
        String sql = """
                SELECT n AS key FROM unnest(?::text[]) AS n
                WHERE EXISTS (SELECT 1 FROM younglings.player_stats_snapshot s WHERE LOWER(s.rsn) = n)
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("text", rsns.stream().map(r -> r.toLowerCase(java.util.Locale.ROOT)).distinct().toArray()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(rs.getString("key"));
            }
            return results;
        } catch (SQLException e) {
            log.error("Failed to check which of {} names have a snapshot", rsns.size(), e);
            throw new RuntimeException("Failed to check for stats snapshots", e);
        }
    }

    /**
     * The most recent snapshot of each of these names at once (a name that has never been polled is missing from the answer), keyed by the lower-cased name. One
     * query for a whole roster instead of one per player.
     */
    public java.util.Map<String, StatsSnapshotRow> getLatestSnapshots(java.util.Collection<String> rsns) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.latestMany(rsns);
        java.util.Map<String, StatsSnapshotRow> results = new java.util.HashMap<>();
        if (rsns.isEmpty()) return results;
        String sql = """
                SELECT DISTINCT ON (LOWER(rsn)) LOWER(rsn) AS key, snapshot_id, snapshot_at, total_level, total_xp, combat_level,
                       quests_complete, quests_started, quests_not_started
                FROM younglings.player_stats_snapshot
                WHERE LOWER(rsn) = ANY (?)
                ORDER BY LOWER(rsn), snapshot_at DESC
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setArray(1, connection.createArrayOf("text", rsns.stream().map(r -> r.toLowerCase(java.util.Locale.ROOT)).distinct().toArray()));

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.put(rs.getString("key"), mapSnapshotRow(rs));
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get the latest snapshots of {} names", rsns.size(), e);
            throw new RuntimeException("Failed to get the latest stats snapshots", e);
        }
    }

    /** Every snapshot for {@code rsn}, most recent first, capped at {@code limit} — the trend/history view's data source. */
    public List<StatsSnapshotRow> getSnapshotHistory(String rsn, int limit) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.history(rsn, limit);
        String sql = """
                SELECT snapshot_id, snapshot_at, total_level, total_xp, combat_level,
                       quests_complete, quests_started, quests_not_started
                FROM younglings.player_stats_snapshot
                WHERE LOWER(rsn) = LOWER(?)
                ORDER BY snapshot_at DESC
                LIMIT ?
                """;

        List<StatsSnapshotRow> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, rsn);
            statement.setInt(2, limit);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapSnapshotRow(rs));
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get snapshot history for '{}'", rsn, e);
            throw new RuntimeException("Failed to get snapshot history", e);
        }
    }

    private static StatsSnapshotRow mapSnapshotRow(ResultSet rs) throws SQLException {
        return new StatsSnapshotRow(
                rs.getLong("snapshot_id"),
                rs.getObject("snapshot_at", java.time.OffsetDateTime.class),
                rs.getInt("total_level"),
                rs.getLong("total_xp"),
                rs.getInt("combat_level"),
                rs.getInt("quests_complete"),
                rs.getInt("quests_started"),
                rs.getInt("quests_not_started")
        );
    }

    public record StatsSnapshotRow(long snapshotId, java.time.OffsetDateTime snapshotAt, int totalLevel, long totalXp,
                                    int combatLevel, int questsComplete, int questsStarted, int questsNotStarted) {
    }

    // --- Activity history ---

    /**
     * Inserts every activity not already recorded for this player (naturally deduped — see the
     * unique index) and returns just the ones that were genuinely new, oldest-checked-first order —
     * the tracking system's signal for "what should actually get announced" from this poll, as
     * opposed to activities RuneMetrics' rolling window is just showing us again.
     */
    public List<PlayerActivity> saveActivities(String rsn, List<PlayerActivity> activities) {
        if (activities.isEmpty()) return List.of();

        String sql = """
                INSERT INTO younglings.player_activity (rsn, activity_date, activity_text, activity_details, occurred_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (LOWER(rsn), activity_date, activity_text) DO NOTHING
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            for (PlayerActivity activity : activities) {
                statement.setString(1, rsn);
                statement.setString(2, activity.date());
                statement.setString(3, activity.text());
                statement.setString(4, activity.details());
                java.time.Instant occurred = RuneMetricsDates.toInstant(activity.date());
                if (occurred == null) statement.setNull(5, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                else statement.setTimestamp(5, java.sql.Timestamp.from(occurred));
                statement.addBatch();
            }
            int[] results = statement.executeBatch();

            // A batched INSERT ... ON CONFLICT DO NOTHING reports 1 for a row actually inserted, 0
            // for one skipped by the conflict — zip that against the input list to know which are new.
            List<PlayerActivity> newlyInserted = new ArrayList<>();
            for (int i = 0; i < results.length && i < activities.size(); i++) {
                if (results[i] > 0) newlyInserted.add(activities.get(i));
            }
            return newlyInserted;

        } catch (SQLException e) {
            log.error("Failed to save activities for '{}'", rsn, e);
            throw new RuntimeException("Failed to save activities", e);
        }
    }

    /** Most recently *recorded* activities for {@code rsn} (i.e. by when we first saw them, not the in-game date string). */
    public List<PlayerActivity> getRecentActivities(String rsn, int limit) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.recentActivities(rsn, limit);
        String sql = """
                SELECT activity_date, activity_text, activity_details
                FROM younglings.player_activity
                WHERE LOWER(rsn) = LOWER(?)
                ORDER BY recorded_at DESC, id ASC
                LIMIT ?
                """;

        List<PlayerActivity> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, rsn);
            statement.setInt(2, limit);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new PlayerActivity(rs.getString("activity_date"),
                            rs.getString("activity_text"), rs.getString("activity_details")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get recent activities for '{}'", rsn, e);
            throw new RuntimeException("Failed to get recent activities", e);
        }
    }
}
