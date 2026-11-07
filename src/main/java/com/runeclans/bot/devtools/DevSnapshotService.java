package com.runeclans.bot.devtools;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.configure.GuildSettingsService;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.reflect.RecordComponent;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bundles the bot's state at one moment into a zip with an id, so a bug report can say "snapshot snap-20261019-031500-ab12" and
 * whoever investigates can see what the bot, its settings and the relevant database rows looked like then. The format is
 * documented in {@code docs/DEV-SNAPSHOT.md}; keep that and {@link #FORMAT_VERSION} in step with any change here.
 * <p>
 * Never trusts who is asking: callers ({@code DevSnapshotAdminApi}, {@code DevSnapshotCommand}) check {@code BotOwners} first. Every
 * byte that goes in the zip passes through {@link SnapshotWriter}, which masks secrets.
 */
@BService
public class DevSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(DevSnapshotService.class);

    /** Bump when the zip's layout changes in a way a reader of it would notice. */
    public static final int FORMAT_VERSION = 1;
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    /** snap-yyyyMMdd-HHmmss-xxxx (UTC time of creation, four random hex digits). */
    public static final Pattern ID_PATTERN = Pattern.compile("snap-\\d{8}-\\d{6}-[0-9a-f]{4}");

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int[] ROW_CAPS = {500, 150, 40, 10};
    private static final int MAX_CELL = 200_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FOCUS = Pattern.compile("(event|ticket|member):(\\d{1,20})");
    private static final Pattern SAFE_COMMIT = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Tables that are never copied, whatever the focus: they hold credentials or private uploads. */
    private static final Set<String> NEVER_COPY = Set.of("plugin_token", "uploaded_image", "dev_snapshot", "admin_audit");

    /** The per-server settings tables a snapshot copies; which of them have a row is also the "features switched on" list. */
    static final List<String> SETTINGS_TABLES = List.of(
            "guild_settings", "guild_branding", "beta_guild", "premium_guild", "ticket_settings", "help_settings", "welcome_config",
            "goodbye_config", "automod_config", "autorole_config", "account_gate_config", "mod_log_config", "starboard_config",
            "suggestion_settings", "points_settings", "clan_points_settings", "clan_rank_config", "citadel_wheel_settings",
            "tracking_group_config", "command_channel_group", "hub_command_setting", "site_news_channel", "clan_profile");

    /** What a request may be narrowed to: the focus kind and its id. */
    public record Focus(String kind, long id) {
        public String text() {
            return kind + ":" + id;
        }
    }

    public record Result(String id, byte[] zip, Instant createdAt, String focus) {}

    /** Thrown for a request that can be refused with a plain message (bad focus, focus not in this server). */
    public static final class SnapshotException extends RuntimeException {
        private final int status;

        public SnapshotException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final ConnectionSupplier connections;
    private final BotConfig config;
    private final GuildSettingsService guildSettings;
    private final SnapshotLogBuffer logs;
    private final DevSnapshotRepository repository;

    public DevSnapshotService(ConnectionSupplier connections, BotConfig config, GuildSettingsService guildSettings,
                              SnapshotLogBuffer logs, DevSnapshotRepository repository) {
        this.connections = connections;
        this.config = config;
        this.guildSettings = guildSettings;
        this.logs = logs;
        this.repository = repository;
    }

    /** {@code event:12}, {@code ticket:34} or {@code member:<discord id>}; blank means no focus. */
    public static Focus parseFocus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher matcher = FOCUS.matcher(raw.strip().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) throw new SnapshotException(400, "Focus must look like event:12, ticket:34 or member:<Discord user id>.");
        try {
            return new Focus(matcher.group(1), Long.parseLong(matcher.group(2)));
        } catch (NumberFormatException e) {
            throw new SnapshotException(400, "That id is too large.");
        }
    }

    /** Takes a snapshot of {@code guild}, stores it, and returns it. */
    public Result create(Guild guild, long actorId, String actorName, String focusText, String siteCommit) {
        Focus focus = parseFocus(focusText);
        String commit = siteCommit == null ? "" : siteCommit.strip();
        if (!commit.isEmpty() && !SAFE_COMMIT.matcher(commit).matches()) throw new SnapshotException(400, "That isn't a valid commit id.");

        Instant now = Instant.now();
        String id = "snap-" + ID_TIME.format(now) + "-" + HexFormat.of().formatHex(randomBytes(2));
        SnapshotRedactor redactor = new SnapshotRedactor(config.secretValues());

        byte[] zip = null;
        for (int cap : ROW_CAPS) {
            Map<String, String> files = collect(guild, id, now, actorId, actorName, focus, commit, cap);
            try {
                zip = SnapshotWriter.pack(files, redactor, MAX_BYTES);
                break;
            } catch (SnapshotWriter.TooLargeException e) {
                log.warn("Snapshot {} was too big with up to {} rows per table; trying fewer", id, cap);
            }
        }
        if (zip == null) throw new SnapshotException(413, "Even with very few rows the snapshot is over 5 MB; narrow the focus.");

        repository.save(id, guild.getIdLong(), actorId, focus == null ? "" : focus.text(), zip);
        repository.deleteExpired();
        log.info("Dev snapshot {} taken of guild {} by {} (focus {}, {} bytes)", id, guild.getId(), actorId, focus == null ? "none" : focus.text(), zip.length);
        return new Result(id, zip, now, focus == null ? "" : focus.text());
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    // ------------------------------------------------------------------ collecting

    Map<String, String> collect(Guild guild, String id, Instant now, long actorId, String actorName, Focus focus, String siteCommit, int rowCap) {
        Map<String, String> files = new LinkedHashMap<>();
        JDA jda = guild.getJDA();
        String botCommit = botCommit();
        String environment = environment();

        DataObject manifest = DataObject.empty()
                .put("format", "jonnybot-dev-snapshot").put("formatVersion", FORMAT_VERSION)
                .put("id", id).put("createdAtUtc", now.toString())
                .put("guildId", guild.getId()).put("guildName", guild.getName())
                .put("requestedBy", DataObject.empty().put("id", Long.toString(actorId)).put("name", actorName))
                .put("focus", focus == null ? null : focus.text())
                .put("environment", environment)
                .put("botCommit", botCommit).put("siteCommit", siteCommit.isEmpty() ? null : siteCommit)
                .put("rowCapPerTable", rowCap);
        files.put("manifest.json", manifest.toPrettyString());

        files.put("environment.json", environmentJson(jda, environment, botCommit, siteCommit).toPrettyString());

        DataObject guildJson = guildJson(guild, rowCap);
        files.put("guild.json", guildJson.toPrettyString());

        StringBuilder logText = new StringBuilder("# Warnings and errors from the last 30 minutes (oldest first)\n");
        List<SnapshotLogBuffer.Entry> entries = logs.recent();
        if (entries.isEmpty()) logText.append("(none)\n");
        for (SnapshotLogBuffer.Entry entry : entries) logText.append(entry).append('\n');
        files.put("logs.txt", logText.toString());

        if (focus != null) files.putAll(focusFiles(guild, focus, rowCap));

        files.put("README.txt", """
                JonnyBot dev snapshot %s (format %d)
                Taken %s UTC of server %s.

                manifest.json     who/what/when, bot and site commit
                environment.json  environment, uptime, gateway, intents, database time
                guild.json        the server's settings and which features have settings stored
                logs.txt          warnings and errors from the 30 minutes before the snapshot
                db/*.json         the focused event, ticket or member: every related row (capped per table)

                Secrets (tokens, passwords, the internal API secret, connection strings) are masked as %s.
                Format notes: docs/DEV-SNAPSHOT.md in the bot repository.
                """.formatted(id, FORMAT_VERSION, now, guild.getId(), SnapshotRedactor.MASK));
        return files;
    }

    private DataObject environmentJson(JDA jda, String environment, String botCommit, String siteCommit) {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        DataArray intents = DataArray.empty();
        new TreeSet<>(jda.getGatewayIntents().stream().map(GatewayIntent::name).toList()).forEach(intents::add);
        Runtime rt = Runtime.getRuntime();
        return DataObject.empty()
                .put("environment", environment)
                .put("botCommit", botCommit).put("siteCommit", siteCommit.isEmpty() ? null : siteCommit)
                .put("startedAtUtc", Instant.ofEpochMilli(System.currentTimeMillis() - uptimeMs).toString())
                .put("uptimeSeconds", uptimeMs / 1000)
                .put("gateway", DataObject.empty().put("status", Shards.status(jda).name()).put("pingMs", Shards.gatewayPing(jda)).put("intents", intents)
                        .put("servers", Shards.guilds(jda).size()).put("shards", Shards.total(jda)))
                .put("java", System.getProperty("java.version"))
                .put("memoryUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).put("memoryMaxMb", rt.maxMemory() / (1024 * 1024))
                .put("databaseTimeUtc", databaseTime())
                .put("pollAutoEnabled", config.getRunescapeAutoPollEnabled());
    }

    private DataObject guildJson(Guild guild, int rowCap) {
        DataObject json = DataObject.empty().put("guildId", guild.getId()).put("name", guild.getName()).put("memberCount", guild.getMemberCount());
        json.put("effectiveSettings", recordJson(guildSettings.getEffective(guild.getIdLong())));

        DataObject tables = DataObject.empty();
        DataArray features = DataArray.empty();
        try (Connection connection = connections.getConnection()) {
            Set<String> existing = tablesWithColumn(connection, "guild_id");
            for (String table : SETTINGS_TABLES) {
                if (!existing.contains(table) || NEVER_COPY.contains(table)) continue;
                DataArray rows = rows(connection, table, "guild_id", guild.getIdLong(), null, rowCap);
                if (rows.length() == 0) continue;
                features.add(table);
                tables.put(table, rows);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the server's settings", e);
        }
        return json.put("featuresWithSettingsStored", features).put("settings", tables);
    }

    // ------------------------------------------------------------------ focus

    private Map<String, String> focusFiles(Guild guild, Focus focus, int rowCap) {
        Map<String, String> files = new LinkedHashMap<>();
        long guildId = guild.getIdLong();
        try (Connection connection = connections.getConnection()) {
            Set<String> hasGuild = tablesWithColumn(connection, "guild_id");
            switch (focus.kind()) {
                case "event" -> {
                    DataArray root = rows(connection, "event", "id", focus.id(), guildId, rowCap);
                    requireFound(root, "event", focus);
                    files.put("db/event.json", root.toPrettyString());
                    for (String table : tablesWithColumn(connection, "event_id")) {
                        if (NEVER_COPY.contains(table)) continue;
                        files.put("db/" + table + ".json", rows(connection, table, "event_id", focus.id(), null, rowCap).toPrettyString());
                    }
                }
                case "ticket" -> {
                    DataArray root = rows(connection, "ticket", "id", focus.id(), guildId, rowCap);
                    requireFound(root, "ticket", focus);
                    files.put("db/ticket.json", root.toPrettyString());
                    for (String table : tablesWithColumn(connection, "ticket_id")) {
                        if (NEVER_COPY.contains(table)) continue;
                        files.put("db/" + table + ".json", rows(connection, table, "ticket_id", focus.id(), null, rowCap).toPrettyString());
                    }
                }
                default -> {
                    // A member: every row that names them in this server, found by the usual "who" column names.
                    Set<String> seen = new TreeSet<>();
                    for (String column : List.of("user_id", "requester_id", "discord_id", "member_id")) {
                        for (String table : tablesWithColumn(connection, column)) {
                            if (NEVER_COPY.contains(table) || !hasGuild.contains(table) || !seen.add(table)) continue;
                            DataArray rows = rows(connection, table, column, focus.id(), guildId, rowCap);
                            if (rows.length() > 0) files.put("db/" + table + ".json", rows.toPrettyString());
                        }
                    }
                    if (files.isEmpty()) throw new SnapshotException(404, "That member has no rows in this server.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the focused rows", e);
        }
        return files;
    }

    private static void requireFound(DataArray root, String what, Focus focus) {
        if (root.length() == 0) throw new SnapshotException(404, "No " + what + " " + focus.id() + " in this server.");
    }

    // ------------------------------------------------------------------ SQL helpers

    /** Names of tables in the bot's schema that have {@code column}. */
    private static Set<String> tablesWithColumn(Connection connection, String column) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.columns WHERE table_schema = 'younglings' AND column_name = ?")) {
            statement.setString(1, column);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /**
     * Up to {@code cap} rows of {@code table} where {@code column} is {@code value} (and {@code guild_id} is {@code guildId} when given).
     * Table and column names come from {@code information_schema} or from this class's constants, never from a request, and are quoted.
     * Columns that look like secrets are masked here, before the text-level scrub gets a look.
     */
    private static DataArray rows(Connection connection, String table, String column, long value, Long guildId, int cap) throws SQLException {
        String sql = "SELECT * FROM younglings.\"" + table.replace("\"", "") + "\" WHERE \"" + column.replace("\"", "") + "\" = ?"
                + (guildId == null ? "" : " AND guild_id = ?") + " LIMIT " + cap;
        DataArray out = DataArray.empty();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, value);
            if (guildId != null) statement.setLong(2, guildId);
            statement.setQueryTimeout(15);
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    DataObject row = DataObject.empty();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String name = meta.getColumnName(i);
                        row.put(name, SnapshotRedactor.isSecretName(name) ? SnapshotRedactor.MASK : cell(rs.getObject(i)));
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** A database value as JSON-safe data. Whole numbers go out as text so 19-digit Discord ids survive any reader. */
    static Object cell(Object value) {
        if (value == null) return null;
        if (value instanceof Long || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) return value.toString();
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Timestamp t) return t.toInstant().toString();
        if (value instanceof OffsetDateTime t) return t.toInstant().toString();
        if (value instanceof byte[] bytes) return "<" + bytes.length + " bytes>";
        if (value instanceof java.sql.Array array) {
            try {
                Object inner = array.getArray();
                if (inner instanceof Object[] items) {
                    List<Object> list = new ArrayList<>();
                    for (Object item : items) list.add(cell(item));
                    return DataArray.fromCollection(list);
                }
            } catch (SQLException ignored) {
                // fall through to text
            }
        }
        String text = value.toString();
        return text.length() > MAX_CELL ? text.substring(0, MAX_CELL) + "... [truncated, " + text.length() + " chars]" : text;
    }

    private String databaseTime() {
        try (Connection connection = connections.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT now()");
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getObject(1, OffsetDateTime.class).toInstant().toString() : null;
        } catch (SQLException e) {
            return "unavailable: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ environment

    /** {@code live}, {@code dev} (a hosted non-live bot) or {@code local} (database on this machine). */
    String environment() {
        try {
            if (config.getLiveEnvironment()) return "live";
        } catch (IllegalStateException e) {
            // LIVE_ENV unset: treat as not live.
        }
        String url;
        try {
            url = config.getDatabaseUrl();
        } catch (IllegalStateException e) {
            return "dev";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("@localhost") || lower.contains("@127.0.0.1") || lower.contains("//localhost") || lower.contains("//127.0.0.1") ? "local" : "dev";
    }

    /** The commit this bot was built from: the host's build variable if there is one, else {@code git} in the working directory. */
    private String botCommit() {
        for (String name : List.of("RAILWAY_GIT_COMMIT_SHA", "SOURCE_COMMIT", "GIT_COMMIT", "COMMIT_SHA")) {
            String value = System.getenv(name);
            if (value != null && SAFE_COMMIT.matcher(value.strip()).matches()) return value.strip();
        }
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unknown";
            }
            String out = new String(process.getInputStream().readAllBytes()).strip();
            return SAFE_COMMIT.matcher(out).matches() ? out : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static DataObject recordJson(Record record) {
        DataObject json = DataObject.empty();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                Object value = component.getAccessor().invoke(record);
                json.put(component.getName(), SnapshotRedactor.isSecretName(component.getName()) ? SnapshotRedactor.MASK : cell(value));
            } catch (ReflectiveOperationException e) {
                json.put(component.getName(), "unreadable");
            }
        }
        return json;
    }
}
