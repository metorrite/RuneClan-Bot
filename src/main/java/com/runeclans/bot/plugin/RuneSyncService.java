package com.runeclans.bot.plugin;

import com.runeclans.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * RuneSync's own server side (RUNESYNC-DESIGN.md sections 6, 7 and 10, ticket T-202): the settings the plugin asks for, the member's
 * consent written from the in-game panel (the same record the profile writes), and diagnostics snapshots for bug reports.
 * <p>
 * <b>Diagnostics never keep a secret:</b> any field whose name says token, assertion, tracking id, chat text/message/line, message text,
 * secret, password, authorization, cookie or discord is dropped, wherever it is in the snapshot, before anything is stored (permission
 * flags such as {@code chatAccess} stay); snapshots are capped at 16 KB, 10 per member
 * per day, and kept 14 days.
 */
@BService
public class RuneSyncService {
    private static final Logger log = LoggerFactory.getLogger(RuneSyncService.class);

    public static final int SEND_EVERY_SECONDS = 60;
    public static final int MAX_SNAPSHOT_BYTES = 16 * 1024;
    public static final int SNAPSHOTS_PER_DAY = 10;
    public static final Duration SNAPSHOT_KEPT = Duration.ofDays(14);
    /** Event types the profile lists, newest 50 each. */
    public static final List<String> PROFILE_TYPES = List.of("XP", "DROP", "KILL", "CLUE");
    public static final int PROFILE_EVENTS_PER_TYPE = 50;

    private static final Pattern SECRET_FIELD = Pattern.compile(
            "token|assertion|tracking_?id|chat_?(text|message|line)|message_?text|secret|password|authori[sz]ation|cookie|discord", Pattern.CASE_INSENSITIVE);
    private static final String ID_ALPHABET = "BCDFGHJKMNPQRSTVWXZ23456789";

    private final PluginService plugins;
    private final RuneSyncRepository repository;
    private final BotConfig config;
    private final RuneSyncPanelService panel;
    private final SecureRandom random = new SecureRandom();

    /** Why something couldn't be done, with the HTTP status to answer. */
    public static final class RuneSyncException extends RuntimeException {
        private final int status;

        public RuneSyncException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public RuneSyncService(PluginService plugins, RuneSyncRepository repository, BotConfig config, RuneSyncPanelService panel) {
        this.plugins = plugins;
        this.repository = repository;
        this.config = config;
        this.panel = panel;
    }

    /**
     * What the plugin asks for on login and every ~10 minutes: which categories the member allows, whether the in-game clan panel may be
     * shown (one of the member's clans switched it on, T-206), the oldest client version still accepted, and how often to send.
     */
    public DataObject config(long userId) {
        // T-812: null = Discord was too slow to say ("unknown"); RuneSync then keeps what it last knew and asks again soon
        Boolean panelAvailable;
        try {
            panelAvailable = panel.available(userId);
        } catch (RuntimeException e) {
            log.warn("Couldn't tell whether user {} may see the clan panel", userId, e);
            panelAvailable = null;
        }
        return DataObject.empty().put("consent", categoryKeys(plugins.consent(userId))).put("panelAvailable", panelAvailable)
                .put("minClientVersion", minClientVersion()).put("sendEverySeconds", SEND_EVERY_SECONDS);
    }

    /** {@code RUNESYNC_MIN_CLIENT_VERSION}; unset, 0.0.0 (no client is refused until the operator says so). */
    public String minClientVersion() {
        String v = config.getRuneSyncSetting("RUNESYNC_MIN_CLIENT_VERSION");
        return v == null || !v.strip().matches("\\d+(\\.\\d+){0,3}") ? "0.0.0" : v.strip();
    }

    /** The in-game panel's category switches: the same rules and the same record as the profile's Plugin tab. */
    public DataObject setConsent(long userId, DataObject body) {
        Set<PluginCategory> chosen = java.util.EnumSet.noneOf(PluginCategory.class);
        DataArray given = body.hasKey("categories") && body.isType("categories", DataType.ARRAY) ? body.getArray("categories") : DataArray.empty();
        for (int i = 0; i < given.length(); i++) {
            try {
                chosen.add(PluginCategory.valueOf(given.getString(i).strip().toUpperCase(Locale.ROOT)));
            } catch (RuntimeException e) {
                throw new RuneSyncException(400, "One of those categories isn't one the bot knows.");
            }
        }
        plugins.setConsent(userId, chosen);
        return config(userId);
    }

    /** The plugin asked for its settings: remember the client and its local pause for the profile's device card. */
    public void checkIn(long userId, String client, boolean paused, Instant now) {
        checkIn(userId, client, null, paused, now);
    }

    /** {@link #checkIn(long, String, boolean, Instant)} with the RuneLink name of the character playing (T-586), or null for a token client. */
    public void checkIn(long userId, String client, String linkedRsn, boolean paused, Instant now) {
        repository.recordCheckIn(userId, cleanClient(client), PluginPayload.normaliseRsn(linkedRsn), paused, now);
    }

    /** A batch was ingested: keep its result for the profile's device card ("last batch result"). */
    public void recordBatch(long userId, String client, int accepted, int duplicates, int rejected, Instant now) {
        repository.recordBatch(userId, cleanClient(client), accepted, duplicates, rejected, now);
    }

    /** A client name as the plugin gave it, trimmed to something safe to show; null when there is none. */
    static String cleanClient(String raw) {
        if (raw == null) return null;
        String clean = raw.replaceAll("[^A-Za-z0-9 ._()/+-]", "").strip();
        if (clean.isEmpty()) return null;
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    /**
     * What the member's profile shows about RuneSync (RUNESYNC-DESIGN.md section 8): their client, their own latest 50 events per type
     * and their own diagnostics ids. Only ever the asking member's own records.
     */
    public DataObject memberView(long userId, Instant now) {
        DataObject client = repository.clientStatus(userId).map(c -> DataObject.empty().put("client", c.client()).put("lastSeen", c.lastSeen().toString())
                .put("paused", c.paused()).put("lastBatchAt", c.lastBatchAt() == null ? null : c.lastBatchAt().toString())
                .put("lastBatch", DataObject.empty().put("accepted", c.accepted()).put("duplicates", c.duplicates()).put("rejected", c.rejected()))).orElse(null);
        java.util.Map<String, DataArray> byType = new java.util.LinkedHashMap<>();
        for (String type : PROFILE_TYPES) byType.put(type, DataArray.empty());
        for (RuneSyncRepository.RecentEvent e : repository.recentEvents(userId, PROFILE_TYPES, PROFILE_EVENTS_PER_TYPE)) {
            DataObject data;
            try {
                data = DataObject.fromJson(e.data());
            } catch (RuntimeException bad) {
                data = DataObject.empty();
            }
            byType.computeIfAbsent(e.type(), t -> DataArray.empty()).add(DataObject.empty().put("at", e.occurredAt().toString()).put("sourceKind", e.sourceKind()).put("sourceName", e.sourceName()).put("data", data));
        }
        DataObject recent = DataObject.empty();
        byType.forEach(recent::put);
        DataArray diagnostics = DataArray.empty();
        for (RuneSyncRepository.Diagnostic d : repository.diagnosticsOf(userId, now)) {
            diagnostics.add(DataObject.empty().put("id", d.id()).put("createdAt", d.createdAt().toString()).put("expiresAt", d.expiresAt().toString()));
        }
        return DataObject.empty().put("client", client).put("recent", recent).put("diagnostics", diagnostics).put("minClientVersion", minClientVersion())
                .put("diagnosticsKeptDays", SNAPSHOT_KEPT.toDays());
    }

    /** One snapshot for the owner's view in RuneClan Admin (runeclans.com/admin), or null when the id is unknown or expired. It was redacted when stored. */
    public DataObject diagnostic(String rawId, Instant now) {
        String id = normaliseId(rawId);
        if (id == null) throw new RuneSyncException(400, "That isn't a diagnostics id (they look like RS-7K2M9Q).");
        return repository.diagnostic(id, now).map(d -> {
            DataObject snapshot;
            try {
                snapshot = DataObject.fromJson(d.json());
            } catch (RuntimeException bad) {
                snapshot = DataObject.empty();
            }
            return DataObject.empty().put("id", d.id()).put("discordUserId", Long.toString(d.discordUserId())).put("createdAt", d.createdAt().toString())
                    .put("expiresAt", d.expiresAt().toString()).put("snapshot", snapshot);
        }).orElse(null);
    }

    /** Part of "delete everything the plugin sent": the member's client record and diagnostics go too. */
    public void deleteFor(long userId) {
        repository.deleteFor(userId);
    }

    /** Stores a redacted snapshot and answers its short id, e.g. {@code RS-7K2M9Q}. */
    public DataObject storeDiagnostics(long userId, DataObject snapshot, Instant now) {
        DataObject clean = redact(snapshot);
        String json = clean.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) throw new RuneSyncException(413, "That snapshot is larger than 16 KB.");
        if (repository.diagnosticsSince(userId, now.minus(Duration.ofDays(1))) >= SNAPSHOTS_PER_DAY) {
            throw new RuneSyncException(429, "That's " + SNAPSHOTS_PER_DAY + " snapshots today already. Use one of those ids.");
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            String id = newId();
            if (repository.insertDiagnostic(id, userId, json, now, now.plus(SNAPSHOT_KEPT))) {
                log.info("User {} stored RuneSync diagnostics {}", userId, id);
                return DataObject.empty().put("id", id).put("expiresAt", now.plus(SNAPSHOT_KEPT).toString());
            }
        }
        throw new IllegalStateException("Couldn't draw a free diagnostics id");
    }

    /** A copy of the snapshot without any field that could hold a secret or someone's identity (at any depth). */
    static DataObject redact(DataObject in) {
        DataObject out = DataObject.empty();
        for (String key : in.keys()) {
            if (SECRET_FIELD.matcher(key).find() || in.isNull(key)) continue;
            out.put(key, redactValue(in, key));
        }
        return out;
    }

    private static Object redactValue(DataObject parent, String key) {
        if (parent.isType(key, DataType.OBJECT)) return redact(parent.getObject(key));
        if (parent.isType(key, DataType.ARRAY)) return redactArray(parent.getArray(key));
        return parent.opt(key).orElse(null);
    }

    private static DataArray redactArray(DataArray in) {
        DataArray out = DataArray.empty();
        for (int i = 0; i < in.length(); i++) {
            if (in.isType(i, DataType.OBJECT)) out.add(redact(in.getObject(i)));
            else if (in.isType(i, DataType.ARRAY)) out.add(redactArray(in.getArray(i)));
            else if (!in.isNull(i)) out.add(in.toList().get(i));
        }
        return out;
    }

    private String newId() {
        StringBuilder id = new StringBuilder("RS-");
        for (int i = 0; i < 6; i++) id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        return id.toString();
    }

    /** A typed id tidied for lookup, or null when it can't be one. */
    public static String normaliseId(String raw) {
        if (raw == null) return null;
        String id = raw.strip().toUpperCase(Locale.ROOT);
        if (!id.startsWith("RS-")) id = "RS-" + id;
        return id.matches("RS-[" + ID_ALPHABET + "]{6}") ? id : null;
    }

    private static DataArray categoryKeys(Set<PluginCategory> categories) {
        DataArray array = DataArray.empty();
        for (PluginCategory c : PluginCategory.values()) if (categories.contains(c)) array.add(c.name());
        return array;
    }
}
