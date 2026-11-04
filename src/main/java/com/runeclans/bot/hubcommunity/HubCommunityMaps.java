package com.runeclans.bot.hubcommunity;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Discord side of polls, signups, group finder posts and forms once the hub owns them (ticket F3, {@link HubCommunity}): only Discord
 * ids and the bot's own button handles, keyed by hub ids. No poll, vote, signup, entry, group or form data is kept here.
 * <ul>
 *   <li>{@code hub_poll_map}: a hub poll, its server, channel and posted message.</li>
 *   <li>{@code hub_signup_map}: a hub signup, its server, the bot's submission questions (the hub keeps one label) and its group role;
 *       {@code hub_signup_message}: the messages showing it; {@code hub_signup_name}: the display name a member signed up under when it
 *       isn't a RuneScape name the hub can keep.</li>
 *   <li>{@code hub_group_map}: a hub group finder group's post.</li>
 *   <li>{@code hub_form_map}: a hub form's ticket panel, answers channel and role.</li>
 * </ul>
 * Polls and signups also get a whole-number <b>local id</b> from one sequence starting at 10^12, because Discord buttons and the dashboard
 * name them by number: far above any id the bot's own tables gave, so the two never mix, and the move step (G) can write a moved poll's
 * old bot id in as its local id, so buttons already posted keep working.
 */
@BService
public class HubCommunityMaps {
    private static final Logger log = LoggerFactory.getLogger(HubCommunityMaps.class);
    /** The first local id: above every id the bot's own poll and signup tables gave out. */
    public static final long FIRST_LOCAL_ID = 1_000_000_000_000L;

    /** A mapped hub poll. */
    public record PollRow(long localId, UUID hubId, long guildId, Long channelId, Long messageId) {}

    /** A mapped hub signup. */
    public record SignupRow(long localId, UUID hubId, long guildId, String submissionFields, Long groupRoleId) {}

    /** A message showing a hub signup. */
    public record SignupMessageRow(long messageId, long localId, long guildId, long channelId, String type) {}

    /** A group finder group's Discord post. */
    public record GroupPost(UUID hubId, long guildId, long channelId, long messageId) {}

    /** A hub form's Discord settings. */
    public record FormRow(UUID hubId, long guildId, Long panelId, Long channelId, Long roleId) {}

    private final ConnectionSupplier db;
    /** Local id lookups are hot (every button click): both directions are cached; rows never change their ids. */
    private final Map<Long, UUID> pollHub = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pollLocal = new ConcurrentHashMap<>();
    private final Map<Long, UUID> signupHub = new ConcurrentHashMap<>();
    private final Map<UUID, Long> signupLocal = new ConcurrentHashMap<>();

    public HubCommunityMaps(ConnectionSupplier db) {
        this.db = db;
        SchemaBootstrapper.run(db, log, "hub community maps", schema());
    }

    static List<String> schema() {
        return List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                "CREATE SEQUENCE IF NOT EXISTS younglings.hub_community_local_id START WITH " + FIRST_LOCAL_ID + ";",
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_poll_map (
                    local_id BIGINT PRIMARY KEY DEFAULT nextval('younglings.hub_community_local_id'),
                    hub_id UUID NOT NULL UNIQUE,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NULL,
                    message_id BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS hub_poll_map_guild ON younglings.hub_poll_map (guild_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_signup_map (
                    local_id BIGINT PRIMARY KEY DEFAULT nextval('younglings.hub_community_local_id'),
                    hub_id UUID NOT NULL UNIQUE,
                    guild_id BIGINT NOT NULL,
                    submission_fields TEXT NULL,
                    group_role_id BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS hub_signup_map_guild ON younglings.hub_signup_map (guild_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_signup_message (
                    message_id BIGINT PRIMARY KEY,
                    local_id BIGINT NOT NULL REFERENCES younglings.hub_signup_map (local_id) ON DELETE CASCADE,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    message_type TEXT NOT NULL,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS hub_signup_message_local ON younglings.hub_signup_message (local_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_signup_name (
                    local_id BIGINT NOT NULL REFERENCES younglings.hub_signup_map (local_id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    PRIMARY KEY (local_id, user_id)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_group_map (
                    hub_id UUID PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_form_map (
                    hub_id UUID PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    panel_id BIGINT NULL,
                    channel_id BIGINT NULL,
                    role_id BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS hub_form_map_guild ON younglings.hub_form_map (guild_id);"
        );
    }

    // ---------- polls ----------

    /** The local id of a hub poll, made on first sight (a poll the website made gets one when the bot first shows it). */
    public long pollLocalId(UUID hubId, long guildId) {
        Long known = pollLocal.get(hubId);
        if (known != null) return known;
        long id = sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_poll_map (hub_id, guild_id) VALUES (?, ?)
                    ON CONFLICT (hub_id) DO UPDATE SET hub_id = EXCLUDED.hub_id RETURNING local_id""")) {
                s.setObject(1, hubId);
                s.setLong(2, guildId);
                try (ResultSet rs = s.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        remember(pollHub, pollLocal, id, hubId);
        return id;
    }

    public Optional<PollRow> poll(long localId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT local_id, hub_id, guild_id, channel_id, message_id FROM younglings.hub_poll_map WHERE local_id = ?")) {
                s.setLong(1, localId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(pollRow(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<PollRow> pollByHubId(UUID hubId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT local_id, hub_id, guild_id, channel_id, message_id FROM younglings.hub_poll_map WHERE hub_id = ?")) {
                s.setObject(1, hubId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(pollRow(rs)) : Optional.empty();
                }
            }
        });
    }

    /** The hub id behind a local poll id, or empty when it isn't one of the hub's. */
    public Optional<UUID> pollHubId(long localId) {
        UUID known = pollHub.get(localId);
        if (known != null) return Optional.of(known);
        Optional<UUID> hub = poll(localId).map(PollRow::hubId);
        hub.ifPresent(h -> remember(pollHub, pollLocal, localId, h));
        return hub;
    }

    public void setPollChannel(long localId, long channelId) {
        update("UPDATE younglings.hub_poll_map SET channel_id = ? WHERE local_id = ?", channelId, localId);
    }

    public void setPollMessage(long localId, long messageId) {
        update("UPDATE younglings.hub_poll_map SET message_id = ? WHERE local_id = ?", messageId, localId);
    }

    /** The posted polls of a server (the ones with a channel). */
    public List<PollRow> pollsOf(long guildId) {
        return sql(c -> {
            List<PollRow> out = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT local_id, hub_id, guild_id, channel_id, message_id FROM younglings.hub_poll_map WHERE guild_id = ? ORDER BY local_id")) {
                s.setLong(1, guildId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) out.add(pollRow(rs));
                }
            }
            return out;
        });
    }

    private static PollRow pollRow(ResultSet rs) throws SQLException {
        return new PollRow(rs.getLong(1), rs.getObject(2, UUID.class), rs.getLong(3), nullableLong(rs, 4), nullableLong(rs, 5));
    }

    // ---------- signups ----------

    public long signupLocalId(UUID hubId, long guildId) {
        Long known = signupLocal.get(hubId);
        if (known != null) return known;
        long id = sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_signup_map (hub_id, guild_id) VALUES (?, ?)
                    ON CONFLICT (hub_id) DO UPDATE SET hub_id = EXCLUDED.hub_id RETURNING local_id""")) {
                s.setObject(1, hubId);
                s.setLong(2, guildId);
                try (ResultSet rs = s.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        remember(signupHub, signupLocal, id, hubId);
        return id;
    }

    public Optional<SignupRow> signup(long localId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT local_id, hub_id, guild_id, submission_fields, group_role_id FROM younglings.hub_signup_map WHERE local_id = ?")) {
                s.setLong(1, localId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(signupRow(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<SignupRow> signupByHubId(UUID hubId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT local_id, hub_id, guild_id, submission_fields, group_role_id FROM younglings.hub_signup_map WHERE hub_id = ?")) {
                s.setObject(1, hubId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(signupRow(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<UUID> signupHubId(long localId) {
        UUID known = signupHub.get(localId);
        if (known != null) return Optional.of(known);
        Optional<UUID> hub = signup(localId).map(SignupRow::hubId);
        hub.ifPresent(h -> remember(signupHub, signupLocal, localId, h));
        return hub;
    }

    public void setSignupDetails(long localId, String submissionFields, Long groupRoleId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.hub_signup_map SET submission_fields = ?, group_role_id = ? WHERE local_id = ?")) {
                s.setString(1, submissionFields);
                if (groupRoleId == null) s.setNull(2, Types.BIGINT); else s.setLong(2, groupRoleId);
                s.setLong(3, localId);
                s.executeUpdate();
            }
            return null;
        });
    }

    public void setSignupRole(long localId, long roleId) {
        update("UPDATE younglings.hub_signup_map SET group_role_id = ? WHERE local_id = ?", roleId, localId);
    }

    private static SignupRow signupRow(ResultSet rs) throws SQLException {
        return new SignupRow(rs.getLong(1), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4), nullableLong(rs, 5));
    }

    public void saveSignupMessage(long localId, long guildId, long channelId, long messageId, String type) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_signup_message (message_id, local_id, guild_id, channel_id, message_type) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (message_id) DO UPDATE SET active = TRUE""")) {
                s.setLong(1, messageId);
                s.setLong(2, localId);
                s.setLong(3, guildId);
                s.setLong(4, channelId);
                s.setString(5, type);
                s.executeUpdate();
            }
            return null;
        });
    }

    /** The active messages of a signup, oldest first; {@code type} null for every type. */
    public List<SignupMessageRow> signupMessages(long localId, String type) {
        return sql(c -> {
            List<SignupMessageRow> out = new ArrayList<>();
            String q = "SELECT message_id, local_id, guild_id, channel_id, message_type FROM younglings.hub_signup_message WHERE local_id = ? AND active"
                    + (type == null ? "" : " AND message_type = ?") + " ORDER BY created_at, message_id";
            try (PreparedStatement s = c.prepareStatement(q)) {
                s.setLong(1, localId);
                if (type != null) s.setString(2, type);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) out.add(new SignupMessageRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)));
                }
            }
            return out;
        });
    }

    public void signupMessageInactive(long messageId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.hub_signup_message SET active = FALSE WHERE message_id = ?")) {
                s.setLong(1, messageId);
                s.executeUpdate();
            }
            return null;
        });
    }

    public void allSignupMessagesInactive(long localId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.hub_signup_message SET active = FALSE WHERE local_id = ?")) {
                s.setLong(1, localId);
                s.executeUpdate();
            }
            return null;
        });
    }

    public void saveSignupName(long localId, long userId, String name) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_signup_name (local_id, user_id, name) VALUES (?, ?, ?)
                    ON CONFLICT (local_id, user_id) DO UPDATE SET name = EXCLUDED.name""")) {
                s.setLong(1, localId);
                s.setLong(2, userId);
                s.setString(3, name);
                s.executeUpdate();
            }
            return null;
        });
    }

    public Map<Long, String> signupNames(long localId) {
        return sql(c -> {
            Map<Long, String> out = new java.util.HashMap<>();
            try (PreparedStatement s = c.prepareStatement("SELECT user_id, name FROM younglings.hub_signup_name WHERE local_id = ?")) {
                s.setLong(1, localId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) out.put(rs.getLong(1), rs.getString(2));
                }
            }
            return out;
        });
    }

    // ---------- group finder posts ----------

    public Optional<GroupPost> groupPost(UUID hubId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT hub_id, guild_id, channel_id, message_id FROM younglings.hub_group_map WHERE hub_id = ?")) {
                s.setObject(1, hubId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(new GroupPost(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3), rs.getLong(4))) : Optional.empty();
                }
            }
        });
    }

    public void saveGroupPost(UUID hubId, long guildId, long channelId, long messageId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_group_map (hub_id, guild_id, channel_id, message_id) VALUES (?, ?, ?, ?)
                    ON CONFLICT (hub_id) DO UPDATE SET channel_id = EXCLUDED.channel_id, message_id = EXCLUDED.message_id""")) {
                s.setObject(1, hubId);
                s.setLong(2, guildId);
                s.setLong(3, channelId);
                s.setLong(4, messageId);
                s.executeUpdate();
            }
            return null;
        });
    }

    public void forgetGroupPost(UUID hubId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_group_map WHERE hub_id = ?")) {
                s.setObject(1, hubId);
                s.executeUpdate();
            }
            return null;
        });
    }

    // ---------- forms ----------

    public Optional<FormRow> form(UUID hubId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT hub_id, guild_id, panel_id, channel_id, role_id FROM younglings.hub_form_map WHERE hub_id = ?")) {
                s.setObject(1, hubId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(formRow(rs)) : Optional.empty();
                }
            }
        });
    }

    public List<FormRow> formsOf(long guildId) {
        return sql(c -> {
            List<FormRow> out = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT hub_id, guild_id, panel_id, channel_id, role_id FROM younglings.hub_form_map WHERE guild_id = ?")) {
                s.setLong(1, guildId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) out.add(formRow(rs));
                }
            }
            return out;
        });
    }

    public void saveForm(FormRow row) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_form_map (hub_id, guild_id, panel_id, channel_id, role_id, updated_at) VALUES (?, ?, ?, ?, ?, NOW())
                    ON CONFLICT (hub_id) DO UPDATE SET panel_id = EXCLUDED.panel_id, channel_id = EXCLUDED.channel_id, role_id = EXCLUDED.role_id,
                        updated_at = NOW() WHERE younglings.hub_form_map.guild_id = EXCLUDED.guild_id""")) {
                s.setObject(1, row.hubId());
                s.setLong(2, row.guildId());
                setNullable(s, 3, row.panelId());
                setNullable(s, 4, row.channelId());
                setNullable(s, 5, row.roleId());
                s.executeUpdate();
            }
            return null;
        });
    }

    private static FormRow formRow(ResultSet rs) throws SQLException {
        return new FormRow(rs.getObject(1, UUID.class), rs.getLong(2), nullableLong(rs, 3), nullableLong(rs, 4), nullableLong(rs, 5));
    }

    // ---------- plumbing ----------

    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T sql(Work<T> work) {
        try (Connection c = db.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new IllegalStateException("hub community maps: " + e.getMessage(), e);
        }
    }

    private void update(String q, long value, long localId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement(q)) {
                s.setLong(1, value);
                s.setLong(2, localId);
                s.executeUpdate();
            }
            return null;
        });
    }

    private static <K, V> void remember(Map<K, V> forward, Map<V, K> back, K k, V v) {
        forward.put(k, v);
        back.put(v, k);
    }

    private static Long nullableLong(ResultSet rs, int col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static void setNullable(PreparedStatement s, int i, Long v) throws SQLException {
        if (v == null) s.setNull(i, Types.BIGINT); else s.setLong(i, v);
    }
}
