package com.runeclans.bot.hubsites;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.runeclans.PanelSwitches;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Where clan sites live (runeclans-hub PHASE-2-SITES, T-505): {@code SITES_BACKEND=bot} (the default) keeps everything on the bot exactly as
 * before; {@code SITES_BACKEND=hub} means the RuneClans hub owns the site records. Then the bot finds a server's site with
 * {@code GET /v1/guilds/{guildId}/site} (kept 10 minutes, emptied by the hub's {@code site.*} changes), keeps its Discord channel choices as
 * mappings keyed by the hub's site id ({@link SiteChannelMappings}), reports who holds the roles a site's rules follow
 * ({@link HubSiteReporter}), and its own site write routes answer "moved" ({@link SitesMoved}).
 * <p>
 * The switch is "Clan sites backend" on RuneClan Admin > Switches (T-817), read on every call; {@code SITES_BACKEND} on the bot's Railway
 * service still wins while it is {@code bot} or {@code hub}. It flips only after the move run (D-18). Reading from the hub also needs the hub client ({@code RUNECLANS_HUB_ENABLED}); without it
 * the bot keeps reading its own tables, but its site writes still answer "moved", because the hub is then the one writer.
 */
@BConfiguration
public class HubSites {
    private static final Logger log = LoggerFactory.getLogger(HubSites.class);
    public static final String SETTING_ENV = "SITES_BACKEND";
    /** The choice on RuneClan Admin > Switches (runeclans-hub {@code SwitchCatalogue.SITES_BACKEND}, T-817) and its two values. */
    public static final String SWITCH_ID = "sites-backend";
    static final String BOT = "bot";
    static final String HUB = "hub";
    static final java.util.List<String> BACKENDS = java.util.List.of(BOT, HUB);
    static final Duration FRESH = Duration.ofMinutes(10);
    /** The cache tag of every site answer; a hub {@code site.*} change empties them all (a bot is in few servers). */
    public static final String TAG = "sites";

    /** What the hub said about a server's site: {@code OFF} (the switch is off), {@code FOUND}, {@code NONE} (no site) or {@code UNKNOWN} (no answer). */
    public enum Status { OFF, FOUND, NONE, UNKNOWN }

    /**
     * A server's site on the hub: the hub's own site id, its address, whether it is published, the server it is connected to, and its
     * community's id ({@code /community join}, T-732; null when the hub didn't say).
     */
    public record HubSite(String siteId, String slug, boolean published, long guildId, String communityId) {
        public HubSite(String siteId, String slug, boolean published, long guildId) {
            this(siteId, slug, published, guildId, null);
        }
    }

    public record Lookup(Status status, HubSite site) {
        static final Lookup OFF = new Lookup(Status.OFF, null);
        static final Lookup NONE = new Lookup(Status.NONE, null);
        static final Lookup UNKNOWN = new Lookup(Status.UNKNOWN, null);
    }

    /** The switch: {@code SITES_BACKEND} on Railway while it is bot or hub, else the panel's {@code sites-backend} (PanelSwitches). */
    private final BooleanSupplier switchOn;
    /** A cached hub read ({@link RuneClansHubClient#read}); null when the hub client is off. */
    private final Function<String, RuneClansHubClient.Read> hub;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static HubSites hubSites(BotConfig config, RuneClansHubClient client, PanelSwitches panel) {
        String railway = config.getSitesBackend();
        boolean on = switchOn(railway);
        if (on && !client.enabled()) {
            log.warn("{}=hub but the hub client is off (RUNECLANS_HUB_ENABLED): site writes answer moved, and site reads stay on the bot's own tables.", SETTING_ENV);
        } else if (on) {
            log.info("Clan sites come from the RuneClans hub ({}=hub).", SETTING_ENV);
        }
        Supplier<String> backend = panel.choice(SWITCH_ID, railway, BACKENDS, BOT);
        return new HubSites(() -> HUB.equals(backend.get()), client.enabled() ? path -> client.read(path, FRESH, TAG) : null);
    }

    HubSites(BooleanSupplier switchOn, Function<String, RuneClansHubClient.Read> hub) {
        this.switchOn = switchOn;
        this.hub = hub;
    }

    /** {@code SITES_BACKEND=bot}: everything as before (for tests and callers that have no switch). */
    public static HubSites off() {
        return new HubSites(() -> false, null);
    }

    /** {@code SITES_BACKEND=hub} with a stub hub (for tests); a null {@code hub} is the hub client being off. */
    public static HubSites on(Function<String, RuneClansHubClient.Read> hub) {
        return new HubSites(() -> true, hub);
    }

    static boolean switchOn(String raw) {
        return raw != null && raw.strip().toLowerCase(Locale.ROOT).equals("hub");
    }

    /** {@code SITES_BACKEND=hub}: the hub owns the sites, so the bot's site write routes answer "moved". */
    public boolean movedToHub() {
        return switchOn.getAsBoolean();
    }

    /** {@code SITES_BACKEND=hub} and the hub client on: site lookups go to the hub. */
    public boolean readsHub() {
        return hub != null && switchOn.getAsBoolean();
    }

    /** {@code GET /v1/guilds/{guildId}/site}. A cached copy is used while the hub is down (up to 15 minutes). */
    public Lookup site(long guildId) {
        if (!readsHub()) return Lookup.OFF;
        RuneClansHubClient.Read read = hub.apply("/v1/guilds/" + guildId + "/site");
        if (read == null || read.answer() == null) return Lookup.UNKNOWN;
        RuneClansHubClient.Answer a = read.answer();
        if (a.status() == 404) return Lookup.NONE;
        if (!a.ok() || a.json() == null) return Lookup.UNKNOWN;
        HubSite site = parse(a.json(), guildId);
        return site == null ? Lookup.UNKNOWN : new Lookup(Status.FOUND, site);
    }

    /** The hub's site id for this server's site, when the hub says there is one. */
    public Optional<String> siteId(long guildId) {
        Lookup lookup = site(guildId);
        return lookup.status() == Status.FOUND ? Optional.of(lookup.site().siteId()) : Optional.empty();
    }

    /**
     * {@code GET /v1/guilds/{guildId}/role-mappings} ({@code {"roleIds": ["..."]}}): the roles whose holders the bot must report for that
     * server (only roles a site's rules follow, D-13). Empty when off or the hub can't say; an empty set when nothing is mapped.
     */
    public Optional<Set<Long>> mappedRoles(long guildId) {
        if (!readsHub()) return Optional.empty();
        RuneClansHubClient.Read read = hub.apply("/v1/guilds/" + guildId + "/role-mappings");
        if (read == null || read.answer() == null) return Optional.empty();
        RuneClansHubClient.Answer a = read.answer();
        if (a.status() == 404) return Optional.of(Set.of());
        if (!a.ok() || a.json() == null) return Optional.empty();
        Set<Long> roles = new LinkedHashSet<>();
        DataArray ids = a.json().optArray("roleIds").orElse(DataArray.empty());
        for (Object raw : ids.toList()) {
            Long id = snowflake(raw == null ? null : String.valueOf(raw));
            if (id != null) roles.add(id);
        }
        return Optional.of(Set.copyOf(roles));
    }

    /**
     * A site's Discord feature settings as the hub keeps them (PHASE-2-SITES T-496): the online list's mode, size and grouping, how many
     * posts a feed shows, and whether Discord events are on the site. The feed's channel picks stay bot mappings ({@link SiteChannelMappings}).
     */
    public record SiteSettings(String onlineMode, int onlineLimit, boolean groupByRole, int feedLimit, boolean eventsEnabled) {}

    /**
     * {@code GET /v1/guilds/{guildId}/site/settings} (T-545): the settings of the server's hub site, kept like the site lookup and emptied by
     * the hub's {@code site.updated} change. Empty when off, when the server has no hub site, or when the hub can't say: the bot then uses
     * its own copy ({@code SiteConnectionStore}), which the website keeps in step until the move.
     */
    public Optional<SiteSettings> settings(long guildId) {
        if (!readsHub()) return Optional.empty();
        RuneClansHubClient.Read read = hub.apply("/v1/guilds/" + guildId + "/site/settings");
        if (read == null || read.answer() == null || !read.answer().ok() || read.answer().json() == null) return Optional.empty();
        return Optional.ofNullable(parseSettings(read.answer().json()));
    }

    /** The settings in a hub answer ({@code {online: {mode, limit, groupByRole}, feed: {limit}, events: {enabled}}}), or null when incomplete. */
    static SiteSettings parseSettings(DataObject json) {
        DataObject online = json.optObject("online").orElse(null);
        DataObject feed = json.optObject("feed").orElse(null);
        DataObject events = json.optObject("events").orElse(null);
        if (online == null || feed == null || events == null) return null;
        String mode = online.getString("mode", "").strip();
        int onlineLimit = online.getInt("limit", 0);
        int feedLimit = feed.getInt("limit", 0);
        if (mode.isEmpty() || onlineLimit < 1 || feedLimit < 1) return null;
        return new SiteSettings(mode, onlineLimit, online.getBoolean("groupByRole", true), feedLimit, events.getBoolean("enabled", true));
    }

    /** The site in a hub answer, or null when it lacks a site id. The server falls back to the one asked about. */
    static HubSite parse(DataObject json, long askedGuild) {
        String siteId = json.getString("siteId", "").strip();
        if (siteId.isEmpty() || siteId.length() > 64) return null;
        Long guild = snowflake(json.isNull("guildId") ? null : String.valueOf(json.opt("guildId").orElse(null)));
        String slug = json.isNull("slug") ? null : json.getString("slug", null);
        String community = json.isNull("communityId") ? "" : json.getString("communityId", "").strip();
        return new HubSite(siteId, slug, json.getBoolean("published", false), guild == null ? askedGuild : guild,
                community.matches("[0-9a-fA-F-]{36}") ? community : null);
    }

    private static Long snowflake(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,20}")) return null;
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
