package com.runeclans.bot.hubevents;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.hubcommunity.HubCommunityMaps;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Discord side of events once the hub owns them (T-932 F1): only Discord ids and the bot's own handles, keyed by hub ids. No event,
 * roster, team, progress or claim data is kept here; that is the hub's ({@link HubEvents}).
 * <ul>
 *   <li>{@code hub_event_map}: a hub event, its clan and server, its <b>local id</b> (a whole number from the sequence T-869 made, from
 *       10^12: Discord buttons, {@code /events join <number>} and the bot's role and channel tables name an event by number), the Discord
 *       signup attached to it, its panel and its update post.</li>
 *   <li>{@code hub_event_ids}: the same for a hub team or claim named in a button or an option (kind, local id, hub id).</li>
 *   <li>{@code hub_event_board_panel}: the board messages posted for an event, by subject.</li>
 *   <li>{@code hub_event_claim_receipt}: the receipt posted in a player's channel for a claim.</li>
 *   <li>{@code hub_event_signup_entry}: which players came in from the event's Discord signup, so the signup sweep knows whom to follow.</li>
 *   <li>{@code hub_event_signup_refusal}: a Discord signup entry the hub turned away, and why, told to the player once.</li>
 * </ul>
 */
@BService
public class HubEventMap {
    private static final Logger log = LoggerFactory.getLogger(HubEventMap.class);
    public static final String TEAM = "team";
    public static final String CLAIM = "claim";
    public static final String AWARD = "award";

    /** A mapped hub event. */
    public record Row(long localId, UUID hubId, UUID clanId, long guildId, Long signupId, Long panelChannelId, Long panelMessageId, Long updateChannelId,
                      Long updateMessageId, long revSeen) {}

    /** A posted board message. */
    public record BoardPanel(long channelId, long messageId, String hash) {}

    /** A claim's receipt message. */
    public record Receipt(long channelId, long messageId) {}

    /** A Discord signup entry the hub turned away. */
    public record Refusal(long userId, String reason, Instant at) {}

    private final ConnectionSupplier db;

    public HubEventMap(ConnectionSupplier db) {
        this.db = db;
        SchemaBootstrapper.run(db, log, "hub event map", schema());
    }

    static List<String> schema() {
        return List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                "CREATE SEQUENCE IF NOT EXISTS younglings.hub_community_local_id START WITH " + HubCommunityMaps.FIRST_LOCAL_ID + ";",
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_event_map (
                    local_id BIGINT PRIMARY KEY DEFAULT nextval('younglings.hub_community_local_id'),
                    hub_id UUID NOT NULL UNIQUE,
                    clan_id UUID NULL,
                    guild_id BIGINT NOT NULL,
                    signup_id BIGINT NULL,
                    panel_channel_id BIGINT NULL,
                    panel_message_id BIGINT NULL,
                    update_channel_id BIGINT NULL,
                    update_message_id BIGINT NULL,
                    rev_seen BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS hub_event_map_guild ON younglings.hub_event_map (guild_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_event_ids (
                    local_id BIGINT PRIMARY KEY DEFAULT nextval('younglings.hub_community_local_id'),
                    kind TEXT NOT NULL,
                    hub_id UUID NOT NULL,
                    event_local_id BIGINT NOT NULL REFERENCES younglings.hub_event_map (local_id) ON DELETE CASCADE,
                    UNIQUE (kind, hub_id)
                );
                """,
                "CREATE INDEX IF NOT EXISTS hub_event_ids_event ON younglings.hub_event_ids (event_local_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_event_board_panel (
                    event_local_id BIGINT NOT NULL REFERENCES younglings.hub_event_map (local_id) ON DELETE CASCADE,
                    subject TEXT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NOT NULL,
                    hash TEXT NULL,
                    PRIMARY KEY (event_local_id, subject)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_event_claim_receipt (
                    claim_local_id BIGINT PRIMARY KEY,
                    event_local_id BIGINT NOT NULL REFERENCES younglings.hub_event_map (local_id) ON DELETE CASCADE,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_event_signup_entry (
                    event_local_id BIGINT NOT NULL REFERENCES younglings.hub_event_map (local_id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    PRIMARY KEY (event_local_id, user_id)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_event_signup_refusal (
                    event_local_id BIGINT NOT NULL REFERENCES younglings.hub_event_map (local_id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    reason TEXT NOT NULL,
                    at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_local_id, user_id)
                );
                """
        );
    }

    // ---------- events ----------

    /** The local id of a hub event, made on first sight (an event the website made gets one when the bot first meets it). */
    public long localId(UUID hubId, UUID clanId, long guildId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_event_map (hub_id, clan_id, guild_id) VALUES (?, ?, ?)
                    ON CONFLICT (hub_id) DO UPDATE SET clan_id = COALESCE(EXCLUDED.clan_id, younglings.hub_event_map.clan_id) RETURNING local_id""")) {
                s.setObject(1, hubId);
                s.setObject(2, clanId, Types.OTHER);
                s.setLong(3, guildId);
                try (ResultSet rs = s.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** The hub id behind a local event id, or empty when it isn't one of the hub's events. */
    public Optional<UUID> hubId(long localId) {
        return event(localId).map(Row::hubId);
    }

    private static final String ROW = "SELECT local_id, hub_id, clan_id, guild_id, signup_id, panel_channel_id, panel_message_id, update_channel_id, update_message_id, rev_seen FROM younglings.hub_event_map";

    public Optional<Row> event(long localId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement(ROW + " WHERE local_id = ?")) {
                s.setLong(1, localId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(row(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<Row> eventByHubId(UUID hubId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement(ROW + " WHERE hub_id = ?")) {
                s.setObject(1, hubId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(row(rs)) : Optional.empty();
                }
            }
        });
    }

    /** The mapped events of a server, newest first. */
    public List<Row> eventsOf(long guildId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement(ROW + " WHERE guild_id = ? ORDER BY local_id DESC")) {
                s.setLong(1, guildId);
                try (ResultSet rs = s.executeQuery()) {
                    List<Row> out = new ArrayList<>();
                    while (rs.next()) out.add(row(rs));
                    return out;
                }
            }
        });
    }

    /** Every server with a mapped event, with the clan the hub said it is (null when the mapping never learned it). */
    public Map<Long, UUID> guilds() {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT guild_id, MAX(clan_id::text) FROM younglings.hub_event_map GROUP BY guild_id ORDER BY guild_id");
                 ResultSet rs = s.executeQuery()) {
                Map<Long, UUID> out = new LinkedHashMap<>();
                while (rs.next()) {
                    String clan = rs.getString(2);
                    out.put(rs.getLong(1), clan == null ? null : UUID.fromString(clan));
                }
                return out;
            }
        });
    }

    /** The events that have a Discord signup attached. */
    public List<Row> eventsWithSignup() {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement(ROW + " WHERE signup_id IS NOT NULL ORDER BY local_id"); ResultSet rs = s.executeQuery()) {
                List<Row> out = new ArrayList<>();
                while (rs.next()) out.add(row(rs));
                return out;
            }
        });
    }

    public void setSignup(long localId, Long signupId) {
        updateNullable("UPDATE younglings.hub_event_map SET signup_id = ? WHERE local_id = ?", signupId, localId);
    }

    public void setPanel(long localId, long channelId, long messageId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.hub_event_map SET panel_channel_id = ?, panel_message_id = ? WHERE local_id = ?")) {
                s.setLong(1, channelId);
                s.setLong(2, messageId);
                s.setLong(3, localId);
                s.executeUpdate();
            }
            return null;
        });
    }

    public void setUpdatePost(long localId, Long channelId, Long messageId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.hub_event_map SET update_channel_id = ?, update_message_id = ? WHERE local_id = ?")) {
                s.setObject(1, channelId, Types.BIGINT);
                s.setObject(2, messageId, Types.BIGINT);
                s.setLong(3, localId);
                s.executeUpdate();
            }
            return null;
        });
    }

    /** Remembers the newest event revision the bot acted on; true when {@code rev} is newer than what was seen (so a feed entry is acted on once). */
    public boolean seen(long localId, long rev) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.hub_event_map SET rev_seen = ? WHERE local_id = ? AND rev_seen < ?")) {
                s.setLong(1, rev);
                s.setLong(2, localId);
                s.setLong(3, rev);
                return s.executeUpdate() > 0;
            }
        });
    }

    /** Forgets an event the hub deleted, with its board panels, receipts and signup bookkeeping. */
    public void forget(long localId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_map WHERE local_id = ?")) {
                s.setLong(1, localId);
                s.executeUpdate();
            }
            return null;
        });
    }

    // ---------- teams, claims and awards ----------

    /** The local id of a hub team, claim or award ({@code kind}) of a mapped event, made on first sight. */
    public long localId(String kind, UUID hubId, long eventLocalId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_event_ids (kind, hub_id, event_local_id) VALUES (?, ?, ?)
                    ON CONFLICT (kind, hub_id) DO UPDATE SET kind = EXCLUDED.kind RETURNING local_id""")) {
                s.setString(1, kind);
                s.setObject(2, hubId);
                s.setLong(3, eventLocalId);
                try (ResultSet rs = s.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** The hub id behind a local team, claim or award id. */
    public Optional<UUID> hubId(String kind, long localId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT hub_id FROM younglings.hub_event_ids WHERE kind = ? AND local_id = ?")) {
                s.setString(1, kind);
                s.setLong(2, localId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
                }
            }
        });
    }

    // ---------- board panels and receipts ----------

    public Map<String, BoardPanel> boardPanels(long eventLocalId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT subject, channel_id, message_id, hash FROM younglings.hub_event_board_panel WHERE event_local_id = ?")) {
                s.setLong(1, eventLocalId);
                try (ResultSet rs = s.executeQuery()) {
                    Map<String, BoardPanel> out = new LinkedHashMap<>();
                    while (rs.next()) out.put(rs.getString(1), new BoardPanel(rs.getLong(2), rs.getLong(3), rs.getString(4)));
                    return out;
                }
            }
        });
    }

    public void setBoardPanel(long eventLocalId, String subject, long channelId, long messageId, String hash) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_event_board_panel (event_local_id, subject, channel_id, message_id, hash) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (event_local_id, subject) DO UPDATE SET channel_id = EXCLUDED.channel_id, message_id = EXCLUDED.message_id, hash = EXCLUDED.hash""")) {
                s.setLong(1, eventLocalId);
                s.setString(2, subject);
                s.setLong(3, channelId);
                s.setLong(4, messageId);
                s.setString(5, hash);
                s.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Receipt> receipt(long claimLocalId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT channel_id, message_id FROM younglings.hub_event_claim_receipt WHERE claim_local_id = ?")) {
                s.setLong(1, claimLocalId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(new Receipt(rs.getLong(1), rs.getLong(2))) : Optional.empty();
                }
            }
        });
    }

    public void setReceipt(long eventLocalId, long claimLocalId, long channelId, long messageId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_event_claim_receipt (claim_local_id, event_local_id, channel_id, message_id) VALUES (?, ?, ?, ?)
                    ON CONFLICT (claim_local_id) DO UPDATE SET channel_id = EXCLUDED.channel_id, message_id = EXCLUDED.message_id""")) {
                s.setLong(1, claimLocalId);
                s.setLong(2, eventLocalId);
                s.setLong(3, channelId);
                s.setLong(4, messageId);
                s.executeUpdate();
            }
            return null;
        });
    }

    // ---------- the Discord signup's bookkeeping ----------

    /** The players of the event who came in from its Discord signup. */
    public List<Long> signupEntries(long eventLocalId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT user_id FROM younglings.hub_event_signup_entry WHERE event_local_id = ?")) {
                s.setLong(1, eventLocalId);
                try (ResultSet rs = s.executeQuery()) {
                    List<Long> out = new ArrayList<>();
                    while (rs.next()) out.add(rs.getLong(1));
                    return out;
                }
            }
        });
    }

    public void addSignupEntry(long eventLocalId, long userId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.hub_event_signup_entry (event_local_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                s.setLong(1, eventLocalId);
                s.setLong(2, userId);
                s.executeUpdate();
            }
            return null;
        });
    }

    public void removeSignupEntry(long eventLocalId, long userId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_signup_entry WHERE event_local_id = ? AND user_id = ?")) {
                s.setLong(1, eventLocalId);
                s.setLong(2, userId);
                s.executeUpdate();
            }
            return null;
        });
    }

    /** Remembers why a signup entry was turned away; true the first time (so the player is told once), false when the same reason is already kept. */
    public boolean recordRefusal(long eventLocalId, long userId, String reason) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.hub_event_signup_refusal (event_local_id, user_id, reason) VALUES (?, ?, ?)
                    ON CONFLICT (event_local_id, user_id) DO UPDATE SET reason = EXCLUDED.reason, at = NOW() WHERE younglings.hub_event_signup_refusal.reason <> EXCLUDED.reason""")) {
                s.setLong(1, eventLocalId);
                s.setLong(2, userId);
                s.setString(3, reason);
                return s.executeUpdate() > 0;
            }
        });
    }

    public void clearRefusal(long eventLocalId, long userId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_signup_refusal WHERE event_local_id = ? AND user_id = ?")) {
                s.setLong(1, eventLocalId);
                s.setLong(2, userId);
                s.executeUpdate();
            }
            return null;
        });
    }

    public List<Refusal> refusals(long eventLocalId) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT user_id, reason, at FROM younglings.hub_event_signup_refusal WHERE event_local_id = ? ORDER BY at")) {
                s.setLong(1, eventLocalId);
                try (ResultSet rs = s.executeQuery()) {
                    List<Refusal> out = new ArrayList<>();
                    while (rs.next()) {
                        Timestamp at = rs.getTimestamp(3);
                        out.add(new Refusal(rs.getLong(1), rs.getString(2), at == null ? null : at.toInstant()));
                    }
                    return out;
                }
            }
        });
    }

    // ---------- helpers ----------

    private static Row row(ResultSet rs) throws SQLException {
        String clan = rs.getString(3);
        return new Row(rs.getLong(1), UUID.fromString(rs.getString(2)), clan == null ? null : UUID.fromString(clan), rs.getLong(4), (Long) rs.getObject(5),
                (Long) rs.getObject(6), (Long) rs.getObject(7), (Long) rs.getObject(8), (Long) rs.getObject(9), rs.getLong(10));
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T sql(Work<T> work) {
        try (Connection c = db.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new IllegalStateException("hub event map: " + e.getMessage(), e);
        }
    }

    private void updateNullable(String q, Long value, long localId) {
        sql(c -> {
            try (PreparedStatement s = c.prepareStatement(q)) {
                s.setObject(1, value, Types.BIGINT);
                s.setLong(2, localId);
                s.executeUpdate();
            }
            return null;
        });
    }
}
