package com.runeclans.bot.runescape;

import com.runeclans.bot.internal.SiteInvalidator;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Moves the bot's own rows from a player's old name to their new one when the RuneClans hub links a name change (T-1160), and back when the
 * hub undoes it. The hub is the only place that spots and links name changes; the bot only follows ({@link HubNameChanges}).
 * <p>
 * One transaction per change. What moved is written to {@code younglings.hub_name_change_applied} (keyed by the hub's change id), so the same
 * feed entry delivered twice does nothing the second time, and an undo puts back exactly what this moved, nothing more:
 * <ul>
 *   <li>everywhere: the bot's account and link rows ({@code player_account}, {@code player_link}, {@code player_link_exclusion});</li>
 *   <li>in each server given (the servers whose clan the change belongs to): the roster row, points, goals, notes, recap marks, season
 *       standings and the site's rank and cockpit marks. A row whose new name already holds the same thing is left where it is, except the
 *       roster row and the points total, which are merged (earliest join date kept, points added);</li>
 *   <li>the leave of the old name and the join of the new one the roster sync already recorded (if it saw the change first) are taken out, so
 *       the digest and the site count one member, not a leave and a join; a confirmed row is added to the rename history the site shows.</li>
 * </ul>
 * A table a deployment doesn't have yet (the site tables are made by their own stores) is skipped.
 */
@BService
public class HubNameChangeStore {
    private static final Logger log = LoggerFactory.getLogger(HubNameChangeStore.class);
    /** How far back a leave and join the roster sync recorded can still belong to this change. */
    static final Duration EVENT_WINDOW = Duration.ofDays(30);

    private final ConnectionSupplier connections;

    public HubNameChangeStore(ConnectionSupplier connections, RuneScapeDatabaseInitializer initializer) {
        this.connections = connections;
    }

    /**
     * One table that holds a name. {@code nameCol} is the display name column and {@code keyCol} the lower-cased key column (either may be
     * null, not both); {@code rowCols} tell one of the name's rows from another; {@code uniqueCols} are the columns that, with the server and
     * the name, may hold only one row (empty: one row per name; null: no limit).
     */
    record Table(String name, boolean perGuild, String nameCol, String keyCol, List<String> rowCols, List<String> uniqueCols) {}

    static final List<Table> GLOBAL = List.of(
            new Table("player_account", false, "rsn", null, List.of("account_id"), List.of()),
            new Table("player_link", false, "rsn", null, List.of("link_id"), List.of("guild_id")),
            new Table("player_link_exclusion", false, "rsn", null, List.of("guild_id"), List.of("guild_id")));

    static final List<Table> PER_GUILD = List.of(
            new Table("clan_points_award", true, "rsn", null, List.of("id"), List.of("award_type", "awarded_for_date")),
            new Table("member_goal", true, "rsn", null, List.of("id"), null),
            new Table("admin_note", true, "rsn", null, List.of("id"), null),
            new Table("member_recap_seen", true, "rsn", null, List.of("period_key"), List.of("period_key")),
            new Table("site_season_standing", true, "rsn", null, List.of("season"), List.of("season")),
            new Table("site_cockpit_mark", true, null, "rsn_key", List.of("kind"), List.of("kind")),
            new Table("site_rank_tick", true, null, "rsn_key", List.of("rank_name"), List.of("rank_name")),
            new Table("site_rank_hold", true, "rsn", "rsn_key", List.of(), List.of()),
            new Table("site_rank_due", true, "rsn", "rsn_key", List.of("rank_name"), List.of("rank_name")),
            new Table("site_rank_promoted", true, "rsn", "rsn_key", List.of("week_start"), List.of("week_start")));

    /** What one apply did: the servers whose roster row moved or merged (they get the "X is now Y" line), and the account's owner, if linked. */
    public record Applied(List<Long> rosterGuildIds, Long ownerDiscordId) {}

    static String keyOf(String rsn) {
        return rsn == null ? "" : rsn.strip().toLowerCase(Locale.ROOT);
    }

    /** The servers whose roster (current or past) holds either name: the only ones a change can concern. */
    public Set<Long> guildsHolding(String from, String to) {
        try (Connection c = connections.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT DISTINCT guild_id FROM younglings.clan_member WHERE LOWER(rsn) IN (LOWER(?), LOWER(?))")) {
            s.setString(1, from);
            s.setString(2, to);
            Set<Long> out = new LinkedHashSet<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the servers holding a name", e);
        }
    }

    /** Whether this change was already applied (and not undone). */
    public boolean isApplied(long changeId) {
        return "linked".equals(state(changeId));
    }

    String state(long changeId) {
        try (Connection c = connections.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT state FROM younglings.hub_name_change_applied WHERE change_id = ?")) {
            s.setLong(1, changeId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read a name change", e);
        }
    }

    /**
     * Moves the rows of {@code from} to {@code to}: the bot's links everywhere, everything else in {@code guildIds}. Null when this change was
     * already applied (a repeated feed entry), so the caller posts nothing twice.
     */
    public Applied apply(long changeId, String from, String to, Instant at, Collection<Long> guildIds) {
        try (Connection c = connections.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!claim(c, changeId, from, to, guildIds)) {
                    c.rollback();
                    return null;
                }
                DataArray journal = DataArray.empty();
                for (Table t : GLOBAL) move(c, t, null, from, to, journal);
                List<Long> roster = new ArrayList<>();
                Instant since = (at == null ? Instant.now() : at).minus(EVENT_WINDOW);
                for (long guildId : guildIds) {
                    if (moveRosterRow(c, guildId, from, to, journal)) roster.add(guildId);
                    movePoints(c, guildId, from, to, journal);
                    for (Table t : PER_GUILD) move(c, t, guildId, from, to, journal);
                    dropSupersededEvents(c, guildId, from, to, since, journal);
                    addHistory(c, guildId, from, to, journal);
                }
                saveJournal(c, changeId, journal);
                Long owner = owner(c, to);
                c.commit();
                for (long guildId : guildIds) SiteInvalidator.signal(guildId, SiteInvalidator.Area.ROSTER);
                log.info("Name change {} followed: {} rows moved for '{}' -> '{}' in {} server(s)", changeId, journal.length(), from, to, guildIds.size());
                return new Applied(roster, owner);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to follow name change " + changeId, e);
        }
    }

    /**
     * Puts back what {@link #apply} moved for this change. Answers the servers whose roster row came back and the old name's owner (for the
     * nickname); null when it was never applied here, or was already undone.
     */
    public Applied undoApplied(long changeId) {
        try (Connection c = connections.getConnection()) {
            c.setAutoCommit(false);
            try {
                String from, to;
                DataArray journal;
                String guilds;
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT from_rsn, to_rsn, journal, guild_ids FROM younglings.hub_name_change_applied WHERE change_id = ? AND state = 'linked' FOR UPDATE")) {
                    s.setLong(1, changeId);
                    try (ResultSet rs = s.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return null;
                        }
                        from = rs.getString(1);
                        to = rs.getString(2);
                        journal = DataArray.fromJson(rs.getString(3));
                        guilds = rs.getString(4);
                    }
                }
                List<Long> roster = new ArrayList<>();
                for (int i = journal.length() - 1; i >= 0; i--) {
                    DataObject step = journal.getObject(i);
                    putBack(c, step, from, to);
                    if (step.getString("op").startsWith("roster_")) roster.add(step.getLong("guild"));
                }
                try (PreparedStatement s = c.prepareStatement(
                        "UPDATE younglings.hub_name_change_applied SET state = 'undone', undone_at = NOW() WHERE change_id = ?")) {
                    s.setLong(1, changeId);
                    s.executeUpdate();
                }
                Long owner = owner(c, from);
                c.commit();
                for (String g : guilds.split(",")) if (!g.isBlank()) SiteInvalidator.signal(Long.parseLong(g), SiteInvalidator.Area.ROSTER);
                log.info("Name change {} undone: '{}' is back as '{}'", changeId, to, from);
                return new Applied(roster, owner);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to undo name change " + changeId, e);
        }
    }

    // ---------------------------------------------------------------- apply

    private static boolean claim(Connection c, long changeId, String from, String to, Collection<Long> guildIds) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.hub_name_change_applied (change_id, from_rsn, to_rsn, state, guild_ids)
                VALUES (?, ?, ?, 'linked', ?) ON CONFLICT (change_id) DO NOTHING
                """)) {
            s.setLong(1, changeId);
            s.setString(2, from);
            s.setString(3, to);
            s.setString(4, String.join(",", guildIds.stream().map(String::valueOf).toList()));
            return s.executeUpdate() == 1;
        }
    }

    private static void saveJournal(Connection c, long changeId, DataArray journal) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE younglings.hub_name_change_applied SET journal = ? WHERE change_id = ?")) {
            s.setString(1, journal.toString());
            s.setLong(2, changeId);
            s.executeUpdate();
        }
    }

    private static Long owner(Connection c, String rsn) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT discord_user_id FROM younglings.player_account WHERE LOWER(rsn) = LOWER(?)")) {
            s.setString(1, rsn);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static boolean exists(Connection c, String table) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            s.setString(1, "younglings." + table);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** {@code name matches ?}: the key column when the table has one, else the name column, case-insensitively. */
    private static String match(Table t) {
        return t.keyCol() != null ? t.keyCol() + " = ?" : "LOWER(" + t.nameCol() + ") = LOWER(?)";
    }

    private static String nameArg(Table t, String rsn) {
        return t.keyCol() != null ? keyOf(rsn) : rsn;
    }

    private static String setName(Table t) {
        List<String> sets = new ArrayList<>();
        if (t.nameCol() != null) sets.add(t.nameCol() + " = ?");
        if (t.keyCol() != null) sets.add(t.keyCol() + " = ?");
        return String.join(", ", sets);
    }

    private static int bindSetName(PreparedStatement s, Table t, String rsn, int i) throws SQLException {
        if (t.nameCol() != null) s.setString(i++, rsn);
        if (t.keyCol() != null) s.setString(i++, keyOf(rsn));
        return i;
    }

    private static String eqCols(List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (String col : cols) sb.append(" AND ").append(col).append("::text = ?");
        return sb.toString();
    }

    private static String scope(Table t) {
        return t.perGuild() ? "guild_id = ? AND " : "";
    }

    /** The generic move of one table: every row of {@code from} whose new name doesn't already hold the same thing. */
    private static void move(Connection c, Table t, Long guildId, String from, String to, DataArray journal) throws SQLException {
        if (!exists(c, t.name())) return;
        List<String> rowCols = t.rowCols();
        List<String> uniq = t.uniqueCols() == null ? List.of() : t.uniqueCols();
        List<String> select = new ArrayList<>();
        for (String col : rowCols) select.add(col + "::text");
        for (String col : uniq) select.add(col + "::text");
        String sql = "SELECT " + (select.isEmpty() ? "1" : String.join(", ", select)) + " FROM younglings." + t.name() + " WHERE " + scope(t) + match(t);
        List<List<String>> rows = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            if (t.perGuild()) s.setLong(i++, guildId);
            s.setString(i, nameArg(t, from));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    List<String> vals = new ArrayList<>();
                    for (int k = 1; k <= select.size(); k++) vals.add(rs.getString(k));
                    rows.add(vals);
                }
            }
        }
        for (List<String> vals : rows) {
            List<String> rowVals = vals.subList(0, rowCols.size());
            List<String> uniqVals = vals.subList(rowCols.size(), vals.size());
            if (t.uniqueCols() != null && holds(c, t, guildId, to, uniqVals)) continue; // the new name already has this: left where it is
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings." + t.name() + " SET " + setName(t) + " WHERE " + scope(t) + match(t) + eqCols(rowCols))) {
                int i = bindSetName(s, t, to, 1);
                if (t.perGuild()) s.setLong(i++, guildId);
                s.setString(i++, nameArg(t, from));
                for (String v : rowVals) s.setString(i++, v);
                if (s.executeUpdate() == 0) continue;
            }
            journal.add(DataObject.empty().put("op", "move").put("table", t.name()).put("guild", guildId)
                    .put("row", DataArray.fromCollection(rowVals)).put("unique", DataArray.fromCollection(uniqVals)));
        }
    }

    /** Whether {@code rsn} already has a row with these unique values in this table (and server). */
    private static boolean holds(Connection c, Table t, Long guildId, String rsn, List<String> uniqVals) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM younglings." + t.name() + " WHERE " + scope(t) + match(t) + eqCols(t.uniqueCols()))) {
            int i = 1;
            if (t.perGuild()) s.setLong(i++, guildId);
            s.setString(i++, nameArg(t, rsn));
            for (String v : uniqVals) s.setString(i++, v);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        }
    }

    private record RosterRow(long id, Timestamp firstSeen, java.sql.Date joinedAt, boolean active) {}

    private static RosterRow rosterRow(Connection c, long guildId, String rsn) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT id, first_seen, clan_joined_at, active FROM younglings.clan_member WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)")) {
            s.setLong(1, guildId);
            s.setString(2, rsn);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new RosterRow(rs.getLong(1), rs.getTimestamp(2), rs.getDate(3), rs.getBoolean(4)) : null;
            }
        }
    }

    /**
     * The roster row: renamed when the new name has none; when the roster sync already added the new name (it saw the change first), the
     * new row keeps the earliest first-seen and join date and stays (or becomes) active, and the old one is left inactive. True if either.
     */
    private static boolean moveRosterRow(Connection c, long guildId, String from, String to, DataArray journal) throws SQLException {
        RosterRow old = rosterRow(c, guildId, from);
        if (old == null) return false;
        RosterRow target = rosterRow(c, guildId, to);
        if (target == null) {
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.clan_member SET rsn = ? WHERE id = ?")) {
                s.setString(1, to);
                s.setLong(2, old.id());
                s.executeUpdate();
            }
            journal.add(DataObject.empty().put("op", "roster_move").put("guild", guildId).put("id", old.id()));
            return true;
        }
        try (PreparedStatement s = c.prepareStatement("""
                UPDATE younglings.clan_member SET first_seen = LEAST(first_seen, ?),
                    clan_joined_at = CASE WHEN ?::date IS NULL THEN clan_joined_at WHEN clan_joined_at IS NULL THEN ?::date ELSE LEAST(clan_joined_at, ?::date) END,
                    active = active OR ?
                WHERE id = ?
                """)) {
            s.setTimestamp(1, old.firstSeen());
            s.setDate(2, old.joinedAt());
            s.setDate(3, old.joinedAt());
            s.setDate(4, old.joinedAt());
            s.setBoolean(5, old.active());
            s.setLong(6, target.id());
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE younglings.clan_member SET active = FALSE WHERE id = ?")) {
            s.setLong(1, old.id());
            s.executeUpdate();
        }
        journal.add(DataObject.empty().put("op", "roster_merge").put("guild", guildId).put("id", target.id()).put("oldId", old.id())
                .put("firstSeen", target.firstSeen().toInstant().toString()).put("joinedAt", target.joinedAt() == null ? null : target.joinedAt().toString())
                .put("active", target.active()).put("oldActive", old.active()));
        return true;
    }

    /** The points total: renamed, or added onto the new name's total (and the old row removed) when the new name already has one. */
    private static void movePoints(Connection c, long guildId, String from, String to, DataArray journal) throws SQLException {
        if (!exists(c, "clan_member_points")) return;
        long oldId;
        long total;
        boolean needed;
        java.sql.Date since;
        try (PreparedStatement s = c.prepareStatement(
                "SELECT id, total_points, promotion_needed, promotion_needed_since FROM younglings.clan_member_points WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)")) {
            s.setLong(1, guildId);
            s.setString(2, from);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return;
                oldId = rs.getLong(1);
                total = rs.getLong(2);
                needed = rs.getBoolean(3);
                since = rs.getDate(4);
            }
        }
        Long targetId = null;
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM younglings.clan_member_points WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)")) {
            s.setLong(1, guildId);
            s.setString(2, to);
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) targetId = rs.getLong(1);
            }
        }
        if (targetId == null) {
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.clan_member_points SET rsn = ? WHERE id = ?")) {
                s.setString(1, to);
                s.setLong(2, oldId);
                s.executeUpdate();
            }
            journal.add(DataObject.empty().put("op", "points_move").put("guild", guildId).put("id", oldId));
            return;
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE younglings.clan_member_points SET total_points = total_points + ? WHERE id = ?")) {
            s.setLong(1, total);
            s.setLong(2, targetId);
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.clan_member_points WHERE id = ?")) {
            s.setLong(1, oldId);
            s.executeUpdate();
        }
        journal.add(DataObject.empty().put("op", "points_merge").put("guild", guildId).put("id", targetId).put("oldId", oldId).put("total", total)
                .put("needed", needed).put("since", since == null ? null : since.toString()));
    }

    /** The newest leave of the old name and join of the new one since {@code since}: the pair this change replaces. */
    private static void dropSupersededEvents(Connection c, long guildId, String from, String to, Instant since, DataArray journal) throws SQLException {
        for (String[] pair : new String[][]{{from, "LEAVE"}, {to, "JOIN"}}) {
            long id;
            String rsn;
            Timestamp at;
            try (PreparedStatement s = c.prepareStatement("""
                    SELECT id, rsn, event_at FROM younglings.clan_roster_event
                    WHERE guild_id = ? AND LOWER(rsn) = LOWER(?) AND event_type = ? AND event_at >= ? ORDER BY event_at DESC, id DESC LIMIT 1
                    """)) {
                s.setLong(1, guildId);
                s.setString(2, pair[0]);
                s.setString(3, pair[1]);
                s.setTimestamp(4, Timestamp.from(since));
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) continue;
                    id = rs.getLong(1);
                    rsn = rs.getString(2);
                    at = rs.getTimestamp(3);
                }
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.clan_roster_event WHERE id = ?")) {
                s.setLong(1, id);
                s.executeUpdate();
            }
            journal.add(DataObject.empty().put("op", "event_drop").put("guild", guildId).put("id", id).put("rsn", rsn).put("type", pair[1])
                    .put("at", at.toInstant().toString()));
        }
    }

    /** A confirmed row in the rename history the site shows (the hub decided it). */
    private static void addHistory(Connection c, long guildId, String from, String to, DataArray journal) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.rsn_rename_candidate (guild_id, old_rsn, new_rsn, confidence, basis, status, resolved_at)
                VALUES (?, ?, ?, 'HUB', 'Linked on RuneClans.', 'CONFIRMED', NOW()) RETURNING id
                """)) {
            s.setLong(1, guildId);
            s.setString(2, from);
            s.setString(3, to);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                journal.add(DataObject.empty().put("op", "history").put("guild", guildId).put("id", rs.getLong(1)));
            }
        }
    }

    // ---------------------------------------------------------------- undo

    private static Table table(String name) {
        for (Table t : GLOBAL) if (t.name().equals(name)) return t;
        for (Table t : PER_GUILD) if (t.name().equals(name)) return t;
        throw new IllegalStateException("Unknown table in a name change journal: " + name);
    }

    private static List<String> strings(DataArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.isNull(i) ? null : a.getString(i));
        return out;
    }

    private static void putBack(Connection c, DataObject e, String from, String to) throws SQLException {
        Long guildId = e.isNull("guild") ? null : e.getLong("guild");
        switch (e.getString("op")) {
            case "move" -> {
                Table t = table(e.getString("table"));
                List<String> rowVals = strings(e.getArray("row"));
                List<String> uniqVals = strings(e.getArray("unique"));
                if (t.uniqueCols() != null && holds(c, t, guildId, from, uniqVals)) return; // the old name has this again: left on the new one
                try (PreparedStatement s = c.prepareStatement("UPDATE younglings." + t.name() + " SET " + setName(t) + " WHERE " + scope(t) + match(t) + eqCols(t.rowCols()))) {
                    int i = bindSetName(s, t, from, 1);
                    if (t.perGuild()) s.setLong(i++, guildId);
                    s.setString(i++, nameArg(t, to));
                    for (String v : rowVals) s.setString(i++, v);
                    s.executeUpdate();
                }
            }
            case "roster_move" -> {
                if (rosterRow(c, guildId, from) != null) return;
                try (PreparedStatement s = c.prepareStatement("UPDATE younglings.clan_member SET rsn = ? WHERE id = ?")) {
                    s.setString(1, from);
                    s.setLong(2, e.getLong("id"));
                    s.executeUpdate();
                }
            }
            case "roster_merge" -> {
                try (PreparedStatement s = c.prepareStatement("UPDATE younglings.clan_member SET first_seen = ?, clan_joined_at = ?::date, active = ? WHERE id = ?")) {
                    s.setTimestamp(1, Timestamp.from(Instant.parse(e.getString("firstSeen"))));
                    s.setString(2, e.isNull("joinedAt") ? null : e.getString("joinedAt"));
                    s.setBoolean(3, e.getBoolean("active"));
                    s.setLong(4, e.getLong("id"));
                    s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("UPDATE younglings.clan_member SET active = ? WHERE id = ?")) {
                    s.setBoolean(1, e.getBoolean("oldActive"));
                    s.setLong(2, e.getLong("oldId"));
                    s.executeUpdate();
                }
            }
            case "points_move" -> {
                try (PreparedStatement s = c.prepareStatement("""
                        UPDATE younglings.clan_member_points SET rsn = ? WHERE id = ?
                          AND NOT EXISTS (SELECT 1 FROM younglings.clan_member_points WHERE guild_id = ? AND LOWER(rsn) = LOWER(?))
                        """)) {
                    s.setString(1, from);
                    s.setLong(2, e.getLong("id"));
                    s.setLong(3, guildId);
                    s.setString(4, from);
                    s.executeUpdate();
                }
            }
            case "points_merge" -> {
                long total = e.getLong("total");
                try (PreparedStatement s = c.prepareStatement("UPDATE younglings.clan_member_points SET total_points = total_points - ? WHERE id = ?")) {
                    s.setLong(1, total);
                    s.setLong(2, e.getLong("id"));
                    s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO younglings.clan_member_points (id, guild_id, rsn, total_points, promotion_needed, promotion_needed_since)
                        VALUES (?, ?, ?, ?, ?, ?::date) ON CONFLICT DO NOTHING
                        """)) {
                    s.setLong(1, e.getLong("oldId"));
                    s.setLong(2, guildId);
                    s.setString(3, from);
                    s.setLong(4, total);
                    s.setBoolean(5, e.getBoolean("needed"));
                    s.setString(6, e.isNull("since") ? null : e.getString("since"));
                    s.executeUpdate();
                }
            }
            case "event_drop" -> {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO younglings.clan_roster_event (id, guild_id, rsn, event_type, event_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                    s.setLong(1, e.getLong("id"));
                    s.setLong(2, guildId);
                    s.setString(3, e.getString("rsn"));
                    s.setString(4, e.getString("type"));
                    s.setTimestamp(5, Timestamp.from(Instant.parse(e.getString("at"))));
                    s.executeUpdate();
                }
            }
            case "history" -> {
                try (PreparedStatement s = c.prepareStatement("UPDATE younglings.rsn_rename_candidate SET status = 'REJECTED', resolved_at = NOW() WHERE id = ?")) {
                    s.setLong(1, e.getLong("id"));
                    s.executeUpdate();
                }
            }
            default -> throw new IllegalStateException("Unknown step in a name change journal: " + e.getString("op"));
        }
    }
}
