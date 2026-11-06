package com.runeclans.bot.internal;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * The stored half of the dashboard's on/off dots (T-461): every feature whose dot is a plain fact in its own table, read in ONE query, so a dashboard
 * load costs one round trip instead of one per page. Each flag says the same as the JS rule the dashboard used on that page's own answer, including
 * what a server that never saved the page gets (no row = the page's defaults). The tables are the features' own; their repositories create them.
 * {@link FeatureStatusApi} adds the dots that need more than one table's row (settings with env fallbacks, the checkup, ...).
 */
@BService
public class FeatureStatusRepository {
    private static final Logger log = LoggerFactory.getLogger(FeatureStatusRepository.class);

    /**
     * What one query found: the flags by name (most are a tab's path), and the lists the API matches against enums in Java: the permission groups with
     * Role toolkit roles, the RuneScape timers switched on, the clan activity groups switched off (no row = on) and each group's channels in order.
     */
    public record Facts(Map<String, Boolean> flags, Set<String> toolkitGroups, Set<String> enabledLookupKinds, Set<String> trackingOff,
                        Map<String, List<Long>> trackingChannels) {
        boolean flag(String key) {
            return Boolean.TRUE.equals(flags.get(key));
        }
    }

    /** {@code g.id} is the server; a flag is a boolean SQL expression. Keyed by the tab whose dot it is, or a helper name without a slash. */
    static final Map<String, String> FLAGS = new LinkedHashMap<>();

    private static void exists(String key, String table, String where) {
        FLAGS.put(key, "EXISTS(SELECT 1 FROM younglings." + table + " t WHERE t.guild_id = g.id" + (where.isEmpty() ? "" : " AND " + where) + ")");
    }

    private static void enabled(String key, String table) {
        exists(key, table, "t.enabled");
    }

    static {
        exists("welcomeWithChannel", "welcome_config", "t.enabled AND t.channel_id IS NOT NULL");
        enabled("/joins/welcome", "welcome_config");
        enabled("/joins/goodbye", "goodbye_config");
        exists("/joins/newaccounts", "account_gate_config", "(t.enabled OR t.name_rules_enabled)");
        enabled("/roles/autoroles", "autorole_config");
        exists("/roles/self-roles", "self_role", "");
        exists("/roles/role-panels", "role_panel", "t.channel_id IS NOT NULL AND t.message_id IS NOT NULL");
        FLAGS.put("/roles/role-keeper", "(EXISTS(SELECT 1 FROM younglings.sticky_role t WHERE t.guild_id = g.id)"
                + " OR EXISTS(SELECT 1 FROM younglings.role_kept t WHERE t.guild_id = g.id AND t.source = 'MANUAL')"
                + " OR EXISTS(SELECT 1 FROM younglings.role_temp t WHERE t.guild_id = g.id))");
        enabled("/roles/role-rules", "role_rule");
        exists("toolkitJobs", "role_bulk_job", "");
        exists("/mod/warnings", "autopunish_rule", "");
        exists("/mod/cases", "mod_case", "");
        enabled("/mod/appeals", "appeal_setting");
        exists("/mod/lockdown", "lockdown_config", "cardinality(t.channel_ids) > 0");
        exists("/protection/autoclean", "autopurge_rule", "");
        exists("/protection/autodelete", "autodelete_rule", "");
        enabled("/protection/honeypot", "honeypot_config");
        enabled("/protection/servertags", "server_tag_config");
        enabled("/logs/modlog", "mod_log_config");
        enabled("/logs/message-logs", "message_log_config");
        exists("/logs/audit", "admin_audit", "");
        enabled("/logs/staff-digest", "staff_digest_config");
        enabled("/messages/post", "ban_announcement_config");
        exists("/messages/scheduled", "scheduled_post", "t.status = 'PENDING'");
        exists("/messages/server-reminders", "server_reminder", "t.status = 'ACTIVE'");
        enabled("/messages/autoresponder", "auto_reply");
        FLAGS.put("/messages/custom-tags", "(EXISTS(SELECT 1 FROM younglings.custom_tag_config t WHERE t.guild_id = g.id AND t.enabled)"
                + " AND EXISTS(SELECT 1 FROM younglings.custom_tag t WHERE t.guild_id = g.id))");
        exists("/messages/embedder", "embedder_message", "");
        enabled("/alerts/feeds", "feed_subscription");
        FLAGS.put("/alerts/twitch", "(EXISTS(SELECT 1 FROM younglings.twitch_settings t WHERE t.guild_id = g.id AND t.enabled)"
                + " AND EXISTS(SELECT 1 FROM younglings.twitch_streamer t WHERE t.guild_id = g.id))");
        FLAGS.put("/alerts/kick", "(EXISTS(SELECT 1 FROM younglings.kick_settings t WHERE t.guild_id = g.id AND t.enabled)"
                + " AND EXISTS(SELECT 1 FROM younglings.kick_streamer t WHERE t.guild_id = g.id))");
        enabled("/alerts/highlights", "highlight_config");
        exists("/community/polls", "site_setting", "t.poll_channel_id IS NOT NULL");
        exists("/community/giveaways", "giveaway", "t.status = 'OPEN'");
        exists("/community/suggestions", "suggestion_settings", "t.channel_id IS NOT NULL");
        enabled("/community/starboard", "starboard_config");
        exists("/community/tickets", "ticket_panel", "t.enabled AND t.posted_channel_id IS NOT NULL");
        enabled("/community/forms", "web_form");
        enabled("/levels/points", "points_settings");
        enabled("/levels/chat-levels", "chat_level_config");
        exists("/voice/temp-voice", "tempvoice_hub", "");
        enabled("/voice/voice-text", "voice_text_config");
        exists("/event-tools/event-manager", "event", "NOT t.archived AND t.template <> 'quick-group'");
        enabled("/event-tools/event-announcer", "eventannounce_config");
        exists("/event-tools/citadel-wheel", "citadel_wheel_settings", "t.auto_post AND t.channel_id IS NOT NULL");
        // the coffer is in use once someone holds gp in it
        exists("/event-tools/coffer", "coffer_holder", "t.amount > 0");
        // the bingo pool and skill of the week are the hub's (T-941): no bot table to read, so their tabs get no dot
        // no profile row reads as UNLISTED (ClanProfile.empty), so the tab is on until someone makes it private
        FLAGS.put("/clan/clan-profile", "COALESCE((SELECT t.visibility FROM younglings.clan_profile t WHERE t.guild_id = g.id), 'UNLISTED') <> 'PRIVATE'");
        exists("/clan/clan-points", "clan_points_settings", "(t.daily_membership_points > 0 OR t.citadel_visit_points > 0 OR t.citadel_cap_points > 0)");
        enabled("/runescape/anniversaries", "anniversary_config");
        exists("/runescape/pvm-help", "ticket_panel", "t.help_kind <> 'NONE'");
        exists("/runescape/alliances", "alliance_member", "t.status = 'ACTIVE'");
        exists("/runescape/plugin", "plugin_guild_settings", "t.clan_panel");
        // the snapshot list is the bot's, not one server's (DevSnapshotRepository.list)
        FLAGS.put("/tools/snapshots", "EXISTS(SELECT 1 FROM younglings.dev_snapshot)");
    }

    static final String SQL;

    static {
        StringJoiner columns = new StringJoiner(",\n       ");
        int i = 0;
        for (String expression : FLAGS.values()) columns.add(expression + " AS f" + i++);
        columns.add("ARRAY(SELECT DISTINCT t.group_key FROM younglings.role_toolkit_allow t WHERE t.guild_id = g.id) AS toolkit_groups");
        columns.add("ARRAY(SELECT t.kind FROM younglings.lookup_reminder t WHERE t.guild_id = g.id AND t.enabled) AS lookup_kinds");
        columns.add("ARRAY(SELECT t.group_key FROM younglings.tracking_group_config t WHERE t.guild_id = g.id AND NOT t.enabled) AS tracking_off");
        columns.add("ARRAY(SELECT t.group_key || ':' || t.channel_id FROM younglings.tracking_destination t WHERE t.guild_id = g.id ORDER BY t.id) AS tracking_channels");
        SQL = "SELECT " + columns + "\nFROM (SELECT ?::bigint AS id) g";
    }

    private final ConnectionSupplier connectionSupplier;

    public FeatureStatusRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
    }

    /** Every stored flag of this server, or {@code null} when the query failed (a missing table): those dots are then grey ("na"). */
    public Facts read(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(SQL)) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                Map<String, Boolean> flags = new LinkedHashMap<>();
                int i = 1;
                for (String key : FLAGS.keySet()) flags.put(key, rs.getBoolean(i++));
                Set<String> toolkit = strings(rs.getArray(i++));
                Set<String> lookups = strings(rs.getArray(i++));
                Set<String> trackingOff = strings(rs.getArray(i++));
                Map<String, List<Long>> channels = new LinkedHashMap<>();
                for (Object pair : (Object[]) rs.getArray(i).getArray()) {
                    String text = String.valueOf(pair);
                    int colon = text.lastIndexOf(':');
                    channels.computeIfAbsent(text.substring(0, colon), k -> new ArrayList<>()).add(Long.parseLong(text.substring(colon + 1)));
                }
                return new Facts(flags, toolkit, lookups, trackingOff, channels);
            }
        } catch (SQLException e) {
            log.error("Failed to read the feature status of guild {}", guildId, e);
            return null;
        }
    }

    private static Set<String> strings(Array array) throws SQLException {
        if (array == null) return Set.of();
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    }
}
