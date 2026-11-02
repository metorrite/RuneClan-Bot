package com.runeclans.bot.member;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What members choose for themselves on the website: their profile text and look, privacy toggles, DM
 * preferences, skill goals, and the server's list of self-assignable roles. Everything is keyed by guild and
 * Discord user so it survives restarts and a second server is a configuration matter, not a schema change.
 */
@BService
public class MemberProfileRepository {
    private static final Logger log = LoggerFactory.getLogger(MemberProfileRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public MemberProfileRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "member profile", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",

                """
                CREATE TABLE IF NOT EXISTS younglings.member_profile (
                    guild_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    bio TEXT NOT NULL DEFAULT '',
                    accent_color TEXT NULL,
                    pinned_skill INTEGER NULL,
                    hide_adventure_log BOOLEAN NOT NULL DEFAULT FALSE,
                    hide_from_leaderboards BOOLEAN NOT NULL DEFAULT FALSE,
                    dm_goals BOOLEAN NOT NULL DEFAULT TRUE,
                    dm_events BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, discord_user_id)
                );
                """,

                // Added later: keeps a member's Discord name in the website's "Who's Online" list from linking to their clan profile.
                """
                ALTER TABLE younglings.member_profile ADD COLUMN IF NOT EXISTS hide_discord_link BOOLEAN NOT NULL DEFAULT FALSE;
                """,

                // Added later (T-395): whether the member saved their privacy toggles since site defaults exist. Until then the server's
                // member privacy defaults may hide them too; a toggle they turned on always stays on.
                "ALTER TABLE younglings.member_profile ADD COLUMN IF NOT EXISTS privacy_chosen BOOLEAN NOT NULL DEFAULT FALSE;",

                // Added later: the member turned their recaps off for everyone else. Not one of the privacy toggles: saving it never sets
                // privacy_chosen, and the server's defaults don't touch it (a recap is shown once its member opened it, unless they said no).
                "ALTER TABLE younglings.member_profile ADD COLUMN IF NOT EXISTS hide_recap BOOLEAN NOT NULL DEFAULT FALSE;",

                // The server's member privacy defaults (T-395, site admin's Member privacy page): what is hidden for members who haven't chosen.
                """
                CREATE TABLE IF NOT EXISTS younglings.member_privacy_default (
                    guild_id BIGINT PRIMARY KEY,
                    hide_from_leaderboards BOOLEAN NOT NULL DEFAULT FALSE,
                    hide_adventure_log BOOLEAN NOT NULL DEFAULT FALSE,
                    hide_discord_link BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NULL
                );
                """,

                """
                CREATE TABLE IF NOT EXISTS younglings.member_goal (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    skill_id INTEGER NOT NULL,
                    target_level INTEGER NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    achieved_at TIMESTAMPTZ NULL,
                    UNIQUE (guild_id, discord_user_id, skill_id, target_level)
                );
                """,

                // The roles members may add to / remove from themselves on the website. An admin picks them in
                // the dashboard; the bot re-checks each one is safe every time a member toggles it.
                """
                CREATE TABLE IF NOT EXISTS younglings.self_role (
                    guild_id BIGINT NOT NULL,
                    role_id BIGINT NOT NULL,
                    label TEXT NULL,
                    description TEXT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (guild_id, role_id)
                );
                """,

                // One reminder per event per person, remembered across restarts so a reboot never double-DMs.
                """
                CREATE TABLE IF NOT EXISTS younglings.event_reminder (
                    event_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_id, discord_user_id)
                );
                """,

                // When a member first opened their own recap for one period (rsn lower-cased; period_key from RecapPeriod.key): until then
                // nobody else sees it, so a recap is never a spoiler. Kept by name, as recaps are; discord_user_id is who opened it.
                """
                CREATE TABLE IF NOT EXISTS younglings.member_recap_seen (
                    guild_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    period_key TEXT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, rsn, period_key)
                );
                """
        ));
    }

    // ---------- profile settings ----------

    /**
     * {@code privacyChosen}: the member saved their privacy toggles, so the server's {@link PrivacyDefaults} no longer apply to them. Saving
     * never turns it back off.
     * <p>
     * {@code hideRecap}: the member turned their recaps off for everyone else (off unless they say so: a recap is shown to the clan once its
     * member has opened it). It is not one of the privacy toggles: the server's defaults never change it and saving it doesn't count as
     * choosing them.
     */
    public record Profile(long guildId, long userId, String bio, String accentColor, Integer pinnedSkill, boolean hideAdventureLog,
                          boolean hideFromLeaderboards, boolean dmGoals, boolean dmEvents, boolean hideDiscordLink, boolean privacyChosen,
                          boolean hideRecap) {
        public static Profile defaults(long guildId, long userId) {
            return new Profile(guildId, userId, "", null, null, false, false, true, false, false, false, false);
        }

        /** A profile whose privacy toggles weren't chosen (yet), recaps not hidden. */
        public Profile(long guildId, long userId, String bio, String accentColor, Integer pinnedSkill, boolean hideAdventureLog,
                       boolean hideFromLeaderboards, boolean dmGoals, boolean dmEvents, boolean hideDiscordLink) {
            this(guildId, userId, bio, accentColor, pinnedSkill, hideAdventureLog, hideFromLeaderboards, dmGoals, dmEvents, hideDiscordLink, false);
        }

        /** A profile with recaps not hidden. */
        public Profile(long guildId, long userId, String bio, String accentColor, Integer pinnedSkill, boolean hideAdventureLog,
                       boolean hideFromLeaderboards, boolean dmGoals, boolean dmEvents, boolean hideDiscordLink, boolean privacyChosen) {
            this(guildId, userId, bio, accentColor, pinnedSkill, hideAdventureLog, hideFromLeaderboards, dmGoals, dmEvents, hideDiscordLink, privacyChosen, false);
        }

        /**
         * The privacy that applies: the member's own toggles once they chose them; before that, a toggle is on when they turned it on or the
         * server's default turns it on. A default never shows anything a member hid.
         */
        public Profile withDefaults(PrivacyDefaults d) {
            if (privacyChosen || d == null) return this;
            return new Profile(guildId, userId, bio, accentColor, pinnedSkill, hideAdventureLog || d.hideAdventureLog(),
                    hideFromLeaderboards || d.hideFromLeaderboards(), dmGoals, dmEvents, hideDiscordLink || d.hideDiscordLink(), false, hideRecap);
        }
    }

    // ---------- the server's member privacy defaults (T-395) ----------

    /** What is hidden for members who haven't chosen their own privacy toggles; all off (nothing hidden) until an admin changes them. */
    public record PrivacyDefaults(boolean hideFromLeaderboards, boolean hideAdventureLog, boolean hideDiscordLink) {
        public static final PrivacyDefaults NONE = new PrivacyDefaults(false, false, false);

        public boolean any() {
            return hideFromLeaderboards || hideAdventureLog || hideDiscordLink;
        }
    }

    public PrivacyDefaults privacyDefaults(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT hide_from_leaderboards, hide_adventure_log, hide_discord_link FROM younglings.member_privacy_default WHERE guild_id = ?""")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return PrivacyDefaults.NONE;
                return new PrivacyDefaults(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3));
            }
        } catch (SQLException e) {
            throw fail("read member privacy defaults", e);
        }
    }

    public void savePrivacyDefaults(long guildId, PrivacyDefaults d, long actorId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.member_privacy_default (guild_id, hide_from_leaderboards, hide_adventure_log, hide_discord_link, updated_by, updated_at)
                     VALUES (?, ?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id) DO UPDATE SET hide_from_leaderboards = EXCLUDED.hide_from_leaderboards,
                         hide_adventure_log = EXCLUDED.hide_adventure_log, hide_discord_link = EXCLUDED.hide_discord_link,
                         updated_by = EXCLUDED.updated_by, updated_at = NOW()""")) {
            s.setLong(1, guildId);
            s.setBoolean(2, d.hideFromLeaderboards());
            s.setBoolean(3, d.hideAdventureLog());
            s.setBoolean(4, d.hideDiscordLink());
            s.setLong(5, actorId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save member privacy defaults", e);
        }
    }

    /** Drops a server's defaults (its website was deleted, or the bot left for good). */
    public void deletePrivacyDefaults(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.member_privacy_default WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("delete member privacy defaults", e);
        }
    }

    public Profile getProfile(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT bio, accent_color, pinned_skill, hide_adventure_log, hide_from_leaderboards, dm_goals, dm_events, hide_discord_link, privacy_chosen,
                         hide_recap
                     FROM younglings.member_profile WHERE guild_id = ? AND discord_user_id = ?""")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Profile.defaults(guildId, userId);
                return new Profile(guildId, userId, rs.getString("bio"), rs.getString("accent_color"), (Integer) rs.getObject("pinned_skill"),
                        rs.getBoolean("hide_adventure_log"), rs.getBoolean("hide_from_leaderboards"), rs.getBoolean("dm_goals"), rs.getBoolean("dm_events"), rs.getBoolean("hide_discord_link"),
                        rs.getBoolean("privacy_chosen"), rs.getBoolean("hide_recap"));
            }
        } catch (SQLException e) {
            throw fail("read a member profile", e);
        }
    }

    public void saveProfile(Profile p) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.member_profile (guild_id, discord_user_id, bio, accent_color, pinned_skill, hide_adventure_log,
                         hide_from_leaderboards, dm_goals, dm_events, hide_discord_link, privacy_chosen, hide_recap, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id, discord_user_id) DO UPDATE SET bio = EXCLUDED.bio, accent_color = EXCLUDED.accent_color,
                         pinned_skill = EXCLUDED.pinned_skill, hide_adventure_log = EXCLUDED.hide_adventure_log,
                         hide_from_leaderboards = EXCLUDED.hide_from_leaderboards, dm_goals = EXCLUDED.dm_goals,
                         dm_events = EXCLUDED.dm_events, hide_discord_link = EXCLUDED.hide_discord_link,
                         privacy_chosen = younglings.member_profile.privacy_chosen OR EXCLUDED.privacy_chosen, hide_recap = EXCLUDED.hide_recap,
                         updated_at = NOW()""")) {
            s.setLong(1, p.guildId());
            s.setLong(2, p.userId());
            s.setString(3, p.bio());
            if (p.accentColor() == null) s.setNull(4, Types.VARCHAR); else s.setString(4, p.accentColor());
            if (p.pinnedSkill() == null) s.setNull(5, Types.INTEGER); else s.setInt(5, p.pinnedSkill());
            s.setBoolean(6, p.hideAdventureLog());
            s.setBoolean(7, p.hideFromLeaderboards());
            s.setBoolean(8, p.dmGoals());
            s.setBoolean(9, p.dmEvents());
            s.setBoolean(10, p.hideDiscordLink());
            s.setBoolean(11, p.privacyChosen());
            s.setBoolean(12, p.hideRecap());
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save a member profile", e);
        }
    }

    /** Public profile bits for one RuneScape name: whoever has it linked, and what they chose to show. {@code null} if nobody's linked it. */
    public Profile getProfileForRsn(long guildId, String rsn) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT p.discord_user_id, p.bio, p.accent_color, p.pinned_skill, p.hide_adventure_log, p.hide_from_leaderboards, p.dm_goals, p.dm_events, p.hide_discord_link, p.privacy_chosen,
                         p.hide_recap
                     FROM younglings.player_link l JOIN younglings.member_profile p ON p.guild_id = l.guild_id AND p.discord_user_id = l.discord_user_id
                     WHERE l.guild_id = ? AND LOWER(l.rsn) = LOWER(?)""")) {
            s.setLong(1, guildId);
            s.setString(2, rsn);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return null;
                return new Profile(guildId, rs.getLong("discord_user_id"), rs.getString("bio"), rs.getString("accent_color"), (Integer) rs.getObject("pinned_skill"),
                        rs.getBoolean("hide_adventure_log"), rs.getBoolean("hide_from_leaderboards"), rs.getBoolean("dm_goals"), rs.getBoolean("dm_events"), rs.getBoolean("hide_discord_link"),
                        rs.getBoolean("privacy_chosen"), rs.getBoolean("hide_recap"));
            }
        } catch (SQLException e) {
            throw fail("read a profile by name", e);
        }
    }

    /**
     * Whether {@code rsn}'s adventure log is hidden on this server: the linked member's own toggle once they chose, before that their toggle or
     * the server's default; for a name nobody linked, the server's default.
     */
    public boolean adventureLogHidden(long guildId, String rsn) {
        PrivacyDefaults d = privacyDefaults(guildId);
        Profile p = getProfileForRsn(guildId, rsn);
        return p == null ? d.hideAdventureLog() : p.withDefaults(d).hideAdventureLog();
    }

    /**
     * Whether this RuneScape name hid itself from the rankings on any server, current member or not (T-846): a player who hid on a clan's site
     * and then left that clan stays hidden on their runeclans.com profile too.
     */
    public boolean hidesFromRankingsAnywhere(String rsn) {
        String sql = "SELECT 1 FROM younglings.player_link l JOIN younglings.member_profile p ON p.guild_id = l.guild_id AND p.discord_user_id = l.discord_user_id "
                + "WHERE LOWER(l.rsn) = LOWER(?) AND p.hide_from_leaderboards LIMIT 1";
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, rsn.strip());
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw fail("read whether a player hides anywhere", e);
        }
    }

    /**
     * Lower-cased RuneScape names whose owners hid them from rankings ({@code leaderboards = true}) or hid their adventure log. When the
     * server's {@link PrivacyDefaults} hide that by default, also every current clan member whose name isn't linked to someone who chose
     * their own privacy toggles (T-395): a member who never chose (or never linked) follows the default.
     */
    public Set<String> hiddenRsns(long guildId, boolean leaderboards) {
        String column = leaderboards ? "hide_from_leaderboards" : "hide_adventure_log";
        PrivacyDefaults d = privacyDefaults(guildId);
        boolean byDefault = leaderboards ? d.hideFromLeaderboards() : d.hideAdventureLog();
        String sql = "SELECT LOWER(l.rsn) AS rsn FROM younglings.player_link l JOIN younglings.member_profile p ON p.guild_id = l.guild_id AND p.discord_user_id = l.discord_user_id WHERE l.guild_id = ? AND p." + column;
        if (byDefault) sql += " " + """
                 UNION SELECT LOWER(m.rsn) AS rsn FROM younglings.clan_member m WHERE m.guild_id = ? AND m.active AND NOT EXISTS (
                     SELECT 1 FROM younglings.player_link l JOIN younglings.member_profile p ON p.guild_id = l.guild_id AND p.discord_user_id = l.discord_user_id
                     WHERE l.guild_id = m.guild_id AND LOWER(l.rsn) = LOWER(m.rsn) AND p.privacy_chosen)""";
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            if (byDefault) s.setLong(2, guildId);
            Set<String> hidden = new HashSet<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) hidden.add(rs.getString("rsn"));
            }
            return hidden;
        } catch (SQLException e) {
            throw fail("read hidden members", e);
        }
    }

    /**
     * Discord users who asked not to be linked to their clan profile from the Who's Online list; when the server's defaults hide that, also
     * everyone with a linked name here who hasn't chosen their own privacy toggles.
     */
    public Set<Long> hiddenDiscordUsers(long guildId) {
        boolean byDefault = privacyDefaults(guildId).hideDiscordLink();
        String sql = "SELECT discord_user_id FROM younglings.member_profile WHERE guild_id = ? AND hide_discord_link";
        if (byDefault) sql += " " + """
                 UNION SELECT l.discord_user_id FROM younglings.player_link l WHERE l.guild_id = ? AND NOT EXISTS (
                     SELECT 1 FROM younglings.member_profile p WHERE p.guild_id = l.guild_id AND p.discord_user_id = l.discord_user_id AND p.privacy_chosen)""";
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            if (byDefault) s.setLong(2, guildId);
            Set<Long> hidden = new HashSet<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) hidden.add(rs.getLong(1));
            }
            return hidden;
        } catch (SQLException e) {
            throw fail("read hidden discord links", e);
        }
    }

    // ---------- who may see a member's recap (RecapGate) ----------

    /**
     * The member a RuneScape name belongs to on this server, for its recap: their Discord user id, whether they turned recaps off for
     * everyone else ({@code hideRecap}; no profile row means not), and whether they have opened the recap with this period key
     * ({@code seen}, always false for a null key or a mark someone else left on the name).
     */
    public record RecapOwner(long userId, boolean hideRecap, boolean seen) {}

    /** {@link RecapOwner} for {@code rsn} (any case) on this server; {@code null} when nobody linked it (a name has one link per server). */
    public RecapOwner recapOwner(long guildId, String rsn, String periodKey) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT l.discord_user_id, COALESCE(p.hide_recap, FALSE) AS hide_recap,
                         EXISTS (SELECT 1 FROM younglings.member_recap_seen r WHERE r.guild_id = l.guild_id AND r.rsn = LOWER(?) AND r.period_key = ?
                             AND r.discord_user_id = l.discord_user_id) AS seen
                     FROM younglings.player_link l
                     LEFT JOIN younglings.member_profile p ON p.guild_id = l.guild_id AND p.discord_user_id = l.discord_user_id
                     WHERE l.guild_id = ? AND LOWER(l.rsn) = LOWER(?)""")) {
            s.setString(1, rsn.trim());
            if (periodKey == null) s.setNull(2, Types.VARCHAR); else s.setString(2, periodKey);
            s.setLong(3, guildId);
            s.setString(4, rsn.trim());
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new RecapOwner(rs.getLong("discord_user_id"), rs.getBoolean("hide_recap"), rs.getBoolean("seen")) : null;
            }
        } catch (SQLException e) {
            throw fail("read a recap's member", e);
        }
    }

    /**
     * Records that {@code userId}, who has {@code rsn} linked, opened its recap for {@code periodKey}. The first time is kept; if the name has
     * since passed to someone else, their own first opening replaces the old mark (it was never theirs).
     */
    public void markRecapSeen(long guildId, String rsn, String periodKey, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.member_recap_seen (guild_id, rsn, period_key, discord_user_id) VALUES (?, LOWER(?), ?, ?)
                     ON CONFLICT (guild_id, rsn, period_key) DO UPDATE SET discord_user_id = EXCLUDED.discord_user_id, seen_at = NOW()
                     WHERE younglings.member_recap_seen.discord_user_id <> EXCLUDED.discord_user_id""")) {
            s.setLong(1, guildId);
            s.setString(2, rsn.trim());
            s.setString(3, periodKey);
            s.setLong(4, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("record an opened recap", e);
        }
    }

    // ---------- goals ----------

    public record Goal(long id, long guildId, long userId, String rsn, int skillId, int targetLevel, OffsetDateTime createdAt, OffsetDateTime achievedAt) {}

    private static Goal mapGoal(ResultSet rs) throws SQLException {
        return new Goal(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("discord_user_id"), rs.getString("rsn"), rs.getInt("skill_id"),
                rs.getInt("target_level"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("achieved_at", OffsetDateTime.class));
    }

    public List<Goal> goalsFor(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.member_goal WHERE guild_id = ? AND discord_user_id = ? ORDER BY achieved_at IS NOT NULL, created_at DESC")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            List<Goal> goals = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) goals.add(mapGoal(rs));
            }
            return goals;
        } catch (SQLException e) {
            throw fail("read goals", e);
        }
    }

    /** @return the new goal's id, or {@code -1} if that exact goal already exists */
    public long addGoal(long guildId, long userId, String rsn, int skillId, int targetLevel) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.member_goal (guild_id, discord_user_id, rsn, skill_id, target_level) VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT DO NOTHING RETURNING id""")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setString(3, rsn);
            s.setInt(4, skillId);
            s.setInt(5, targetLevel);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw fail("add a goal", e);
        }
    }

    public boolean deleteGoal(long guildId, long userId, long goalId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.member_goal WHERE id = ? AND guild_id = ? AND discord_user_id = ?")) {
            s.setLong(1, goalId);
            s.setLong(2, guildId);
            s.setLong(3, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("delete a goal", e);
        }
    }

    public List<Goal> pendingGoals() {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.member_goal WHERE achieved_at IS NULL");
             ResultSet rs = s.executeQuery()) {
            List<Goal> goals = new ArrayList<>();
            while (rs.next()) goals.add(mapGoal(rs));
            return goals;
        } catch (SQLException e) {
            throw fail("read pending goals", e);
        }
    }

    /** @return {@code true} if this call is the one that marked it achieved (so only one caller sends the congratulation) */
    public boolean markAchieved(long goalId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.member_goal SET achieved_at = NOW() WHERE id = ? AND achieved_at IS NULL")) {
            s.setLong(1, goalId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("mark a goal achieved", e);
        }
    }

    // ---------- self-assignable roles ----------

    public record SelfRole(long roleId, String label, String description, int position) {}

    public List<SelfRole> selfRoles(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT role_id, label, description, position FROM younglings.self_role WHERE guild_id = ? ORDER BY position, role_id")) {
            s.setLong(1, guildId);
            List<SelfRole> roles = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) roles.add(new SelfRole(rs.getLong("role_id"), rs.getString("label"), rs.getString("description"), rs.getInt("position")));
            }
            return roles;
        } catch (SQLException e) {
            throw fail("read self-assignable roles", e);
        }
    }

    /** Replaces the server's whole list in one transaction. */
    public void replaceSelfRoles(long guildId, List<SelfRole> roles) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.self_role WHERE guild_id = ?")) {
                    delete.setLong(1, guildId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = c.prepareStatement("INSERT INTO younglings.self_role (guild_id, role_id, label, description, position) VALUES (?, ?, ?, ?, ?)")) {
                    int position = 0;
                    for (SelfRole role : roles) {
                        insert.setLong(1, guildId);
                        insert.setLong(2, role.roleId());
                        insert.setString(3, role.label());
                        insert.setString(4, role.description());
                        insert.setInt(5, position++);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("save self-assignable roles", e);
        }
    }

    // ---------- event reminders ----------

    /** @return {@code true} if this call recorded the reminder (so the caller should send it) */
    public boolean claimReminder(long eventId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.event_reminder (event_id, discord_user_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, eventId);
            s.setLong(2, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("record an event reminder", e);
        }
    }

    /** Everyone in the server who asked for event reminders. */
    public List<Long> usersWantingEventReminders(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT discord_user_id FROM younglings.member_profile WHERE guild_id = ? AND dm_events")) {
            s.setLong(1, guildId);
            List<Long> users = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) users.add(rs.getLong(1));
            }
            return users;
        } catch (SQLException e) {
            throw fail("read reminder subscribers", e);
        }
    }

    /** Whether a member wants goal-reached DMs (on by default). */
    public boolean wantsGoalDms(long guildId, long userId) {
        return getProfile(guildId, userId).dmGoals();
    }

    private static RuntimeException fail(String action, SQLException e) {
        log.error("Failed to {}", action, e);
        return new RuntimeException("Failed to " + action, e);
    }
}
