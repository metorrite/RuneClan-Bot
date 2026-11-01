package com.runeclans.bot.access;

import com.runeclans.bot.beta.BetaGuildRepository;
import com.runeclans.bot.beta.BetaGuildRepository.BetaGuild;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.PanelSwitches;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.siteaccess.SiteSwitchRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Where the access gates come from (runeclans-hub PLATFORM-DESIGN B-1.4): the beta servers, the access grants and the site switches.
 * With the switch on (RuneClan Admin > Switches "Beta access from the API", or {@code RUNECLANS_HUB_ACCESS=on}, which wins while set) and the
 * hub client on, they are read from the hub's {@code /v1/access/...} (kept 10 minutes, emptied
 * by the hub's {@code access.changed}); while the hub can't answer, its last answer is used up to 15 minutes, then the bot's own tables
 * until the move. Off, the bot's own tables, exactly as before.
 * <p>
 * With the same switch, a clan verified on the bot's dashboard is also sent to the hub ({@link #clanVerified}), where it becomes
 * {@code legacy}.
 */
@BConfiguration
public class GateSource {
    private static final Logger log = LoggerFactory.getLogger(GateSource.class);
    public static final String SETTING_ENV = "RUNECLANS_HUB_ACCESS";
    /** The switch on RuneClan Admin > Switches (runeclans-hub {@code SwitchCatalogue.HUB_ACCESS}, T-817). */
    public static final String SWITCH_ID = "hub-access";
    static final Duration FRESH = Duration.ofMinutes(10);
    static final String TAG = "access";
    static final String VERIFIED_PATH = "/v1/clans/bot-verified";

    /** The switch, read on every check: the panel's {@code hub-access} unless {@code RUNECLANS_HUB_ACCESS} holds it (PanelSwitches). */
    private final BooleanSupplier on;
    /** A cached hub read ({@link RuneClansHubClient#read}); null when the hub client is off. */
    private final Function<String, RuneClansHubClient.Read> hub;
    private final Supplier<List<BetaGuild>> ownBeta;
    private final Supplier<List<AccessGrant>> ownGrants;
    private final Supplier<Map<String, Boolean>> ownSwitches;
    /** Sends a {@code POST /v1/clans/bot-verified} body through the hub client's write queue; null when the hub client is off. */
    private final Function<String, HubWriteQueue.Result> verifiedWriter;
    private final AtomicBoolean warned = new AtomicBoolean();

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static GateSource gateSource(BotConfig config, RuneClansHubClient hub, PanelSwitches panel, BetaGuildRepository beta, AccessGrantRepository grants,
                                        SiteSwitchRepository switches) {
        String railway = config.getRuneClansSetting(SETTING_ENV);
        if (on(railway) && !hub.enabled()) log.warn("{} is on but the hub client is off (RUNECLANS_HUB_ENABLED): the gates come from the bot's own tables.", SETTING_ENV);
        else if (on(railway)) log.info("Access gates come from the RuneClans hub.");
        return new GateSource(panel.toggle(SWITCH_ID, railway), hub.enabled() ? path -> hub.read(path, FRESH, TAG) : null,
                hub.enabled() ? body -> hub.write("POST", VERIFIED_PATH, body, GateSource::verifiedSent) : null, beta::all, grants::all, switches::all);
    }

    GateSource(BooleanSupplier on, Function<String, RuneClansHubClient.Read> hub, Function<String, HubWriteQueue.Result> verifiedWriter, Supplier<List<BetaGuild>> ownBeta,
               Supplier<List<AccessGrant>> ownGrants, Supplier<Map<String, Boolean>> ownSwitches) {
        this.on = on;
        this.hub = hub;
        this.verifiedWriter = verifiedWriter;
        this.ownBeta = ownBeta;
        this.ownGrants = ownGrants;
        this.ownSwitches = ownSwitches;
    }

    /** The bot's own tables only, as with the switch off (for tests); a null supplier is an empty table. */
    public static GateSource ownTables(Supplier<List<BetaGuild>> beta, Supplier<List<AccessGrant>> grants, Supplier<Map<String, Boolean>> switches) {
        return new GateSource(() -> false, null, null, beta == null ? List::of : beta, grants == null ? List::of : grants, switches == null ? Map::of : switches);
    }

    /** A Railway value holding the switch on ({@link PanelSwitches#hold}). */
    static boolean on(String raw) {
        return Boolean.TRUE.equals(PanelSwitches.hold(raw));
    }

    /** True when the gates are read from the hub. */
    public boolean fromHub() {
        return hub != null && on.getAsBoolean();
    }

    /** The beta servers. */
    public List<BetaGuild> betaGuilds() {
        return servers().map(ServerGates::betaGuilds).orElseGet(ownBeta);
    }

    /**
     * The grants that could let {@code userId} in: from the hub, every server grant plus that person's own; from the bot, the whole list.
     * {@link AccessGrantService#matches} decides either way.
     */
    public List<AccessGrant> grantsFor(long userId) {
        Optional<ServerGates> servers = servers();
        Optional<List<AccessGrant>> mine = servers.isEmpty() ? Optional.empty() : userGrants(userId);
        if (servers.isEmpty() || mine.isEmpty()) return ownGrants.get();
        List<AccessGrant> all = new ArrayList<>(servers.get().guildGrants());
        all.addAll(mine.get());
        return all;
    }

    /** The site switches, key to open. */
    public Map<String, Boolean> switches() {
        return servers().map(ServerGates::switches).orElseGet(ownSwitches);
    }

    /**
     * Tells the hub that {@code actorId} verified {@code clanName} on the dashboard (held up to 5 minutes when the hub can't take it). The
     * bot's own verification is already saved, so this never fails the dashboard. Returns whether it was sent or queued.
     */
    public boolean clanVerified(String clanName, long actorId) {
        if (!fromHub() || verifiedWriter == null || clanName == null || actorId <= 0) return false;
        String body = DataObject.empty().put("name", clanName).put("verifiedBy", Long.toString(actorId)).toString();
        HubWriteQueue.Result r = verifiedWriter.apply(body);
        verifiedSent(r);
        return r != null && r.outcome() != HubWriteQueue.Outcome.REFUSED;
    }

    private static void verifiedSent(HubWriteQueue.Result r) {
        if (r == null || r.outcome() == HubWriteQueue.Outcome.REFUSED) {
            log.warn("The hub didn't take a dashboard clan verification ({}); the bot's own copy has it and the move copies it later.",
                    r == null || r.answer() == null ? "unreachable" : r.answer().status());
        }
    }

    // ---------- the hub's answers ----------

    record ServerGates(List<BetaGuild> betaGuilds, List<AccessGrant> guildGrants, Map<String, Boolean> switches) {}

    private Optional<ServerGates> servers() {
        return answer("/v1/access/servers").map(GateSource::parseServers);
    }

    private Optional<List<AccessGrant>> userGrants(long userId) {
        return answer("/v1/access/users/" + userId).map(json -> parseUserGrants(json, userId));
    }

    private Optional<DataObject> answer(String path) {
        if (!fromHub()) return Optional.empty();
        RuneClansHubClient.Read read = hub.apply(path);
        if (read == null || !read.answer().ok() || read.answer().json() == null) {
            if (warned.compareAndSet(false, true)) {
                log.warn("The hub didn't answer {} ({}): using the bot's own access tables until it does.", path.startsWith("/v1/access/users/") ? "/v1/access/users/…" : path,
                        read == null ? "unreachable" : read.answer().status());
            }
            return Optional.empty();
        }
        warned.set(false);
        return Optional.of(read.answer().json());
    }

    static ServerGates parseServers(DataObject json) {
        List<BetaGuild> beta = new ArrayList<>();
        DataArray servers = json.optArray("betaServers").orElse(DataArray.empty());
        for (int i = 0; i < servers.length(); i++) {
            DataObject b = servers.getObject(i);
            Long guild = snowflake(b.getString("guildId", null));
            if (guild == null) continue;
            beta.add(new BetaGuild(guild, null, AccessLevel.ofKey(b.getString("minLevel", "")).orElse(null)));
        }
        List<AccessGrant> grants = new ArrayList<>();
        DataArray list = json.optArray("guildGrants").orElse(DataArray.empty());
        for (int i = 0; i < list.length(); i++) {
            DataObject g = list.getObject(i);
            Optional<AccessArea> area = AccessArea.ofKey(g.getString("area", ""));
            Optional<AccessLevel> level = AccessLevel.ofKey(g.getString("minLevel", ""));
            Long guild = snowflake(g.getString("guildId", null));
            // an area or level this bot doesn't know lets nobody in, as in the bot's own list
            if (area.isEmpty() || level.isEmpty() || guild == null) continue;
            grants.add(new AccessGrant(0, area.get(), null, guild, level.get(), null, 0, null, instant(g.getString("expiresAt", null))));
        }
        return new ServerGates(beta, grants, parseSwitches(json));
    }

    static List<AccessGrant> parseUserGrants(DataObject json, long userId) {
        List<AccessGrant> grants = new ArrayList<>();
        DataArray list = json.optArray("grants").orElse(DataArray.empty());
        for (int i = 0; i < list.length(); i++) {
            DataObject g = list.getObject(i);
            Optional<AccessArea> area = AccessArea.ofKey(g.getString("area", ""));
            if (area.isEmpty()) continue;
            grants.add(new AccessGrant(0, area.get(), userId, null, null, null, 0, null, instant(g.getString("expiresAt", null))));
        }
        return grants;
    }

    static Map<String, Boolean> parseSwitches(DataObject json) {
        Map<String, Boolean> switches = new LinkedHashMap<>();
        DataObject raw = json.optObject("switches").orElse(DataObject.empty());
        for (String key : raw.keys()) {
            Object v = raw.get(key);
            if (v instanceof Boolean b) switches.put(key, b);
        }
        return switches;
    }

    private static Long snowflake(String raw) {
        if (raw == null || !raw.matches("[0-9]{15,20}")) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** An expiry; one that can't be read counts as already past, so a grant the bot can't date lets nobody in. */
    private static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}
