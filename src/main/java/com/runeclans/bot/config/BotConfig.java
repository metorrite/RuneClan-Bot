package com.runeclans.bot.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Activity;

import java.util.List;
import java.util.Properties;

public class BotConfig {
    private static final BotConfig INSTANCE = new BotConfig();

    private final Dotenv dotenv;
    private final Properties properties;

    private BotConfig() {
        this.dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        this.properties = new Properties();

        try {
            var inputStream = BotConfig.class.getClassLoader()
                    .getResourceAsStream("config.properties");

            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config.properties", e);
        }
    }

    public boolean getLiveEnvironment() {
        String isLive = System.getenv("LIVE_ENV");

        if (isLive == null || isLive.isBlank()) {
            isLive = dotenv.get("LIVE_ENV");
        }

        if (isLive == null || isLive.isBlank()) {
            throw new IllegalStateException(
                    "Missing LIVE_ENV. Add it to environment variables or .env file."
            );
        }

        return Boolean.parseBoolean(isLive);
    }

    /**
     * The channel a bot that is not live sends its automatic alerts to instead of the servers' own channels ({@code DEV_ALERT_CHANNEL_ID}); null when
     * unset, in which case they are only logged. See {@code AutoPostRouter}.
     */
    public Long getDevAlertChannelId() {
        String raw = System.getenv("DEV_ALERT_CHANNEL_ID");
        if (raw == null || raw.isBlank()) raw = dotenv.get("DEV_ALERT_CHANNEL_ID");
        if (raw == null || !raw.strip().matches("\\d{15,20}")) return null;
        return Long.parseLong(raw.strip());
    }

    public String getToken() {
        String token = System.getenv("DISCORD_TOKEN");

        if (token == null || token.isBlank()) {
            token = dotenv.get("DISCORD_TOKEN");
        }

        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Missing DISCORD_TOKEN. Add it to environment variables or .env file."
            );
        }

        return token;
    }

    public String getDatabaseUrl() {
        String databaseUrl = System.getenv("DATABASE_URL");

        if (databaseUrl == null || databaseUrl.isBlank()) {
            databaseUrl = dotenv.get("DATABASE_URL");
        }

        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "Missing DATABASE_URL. Add it to environment variables or .env file."
            );
        }

        return databaseUrl;
    }

    /**
     * The bot's status (one value for the whole bot: Discord has no per-server status). {@code BOT_STATUS} in the environment wins, so it
     * can be changed on the host without a new build; otherwise {@code bot.activity} from the properties file.
     */
    public Activity getActivity() {
        String fromEnv = System.getenv("BOT_STATUS");
        if (fromEnv == null || fromEnv.isBlank()) fromEnv = dotenv.get("BOT_STATUS");
        if (fromEnv != null && !fromEnv.isBlank()) return Activity.customStatus(fromEnv.strip());
        return Activity.customStatus(properties.getProperty("bot.activity", "Online"));
    }

    /**
     * ID of the "Admin" role (or equivalent) used to gate admin-tier commands — see
     * {@code com.runeclans.bot.permission.AdminRoleFilter}, which treats this role and anything
     * ranked above it in the guild's role hierarchy as authorized. Returns {@code null} if unset,
     * in which case {@code AdminRoleFilter} fails closed (rejects everyone) rather than guessing.
     */
    public Long getAdminRoleId() {
        String raw = System.getenv("ADMIN_ROLE_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("ADMIN_ROLE_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * Fallback clan name used when a guild hasn't set its own via {@code /configure} — see
     * {@code GuildSettingsService#getEffective}. {@code null} if unset, in which case a guild with
     * no per-guild override either has no clan name at all until configured.
     */
    public String getClanName() {
        String raw = System.getenv("RUNESCAPE_CLAN_NAME");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("RUNESCAPE_CLAN_NAME");
        }

        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }

    /**
     * ID of the channel {@code RsnRenameService} posts possible-rename alerts to, with Confirm/
     * Reject buttons for an admin to resolve — see {@code rsn_rename_candidate}. {@code null} if
     * unset, in which case a detected rename is logged but nobody is notified.
     */
    public Long getRenameAlertChannelId() {
        String raw = System.getenv("RSN_RENAME_ALERT_CHANNEL_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("RSN_RENAME_ALERT_CHANNEL_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * ID of the channel a new {@code /rs} link request is posted to for an admin to Approve/Reject —
     * see {@code RsInteractionListener#postForAdminReview}. {@code null} if unset, in which case a
     * submitted request has nobody to notify (logged, but otherwise stuck pending until an admin
     * finds it another way, e.g. Player Lookup).
     */
    public Long getVerificationReviewChannelId() {
        String raw = System.getenv("RSN_VERIFICATION_CHANNEL_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("RSN_VERIFICATION_CHANNEL_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * ID of the role granted to a verified member who <em>is</em> a current clan member — picked
     * from a dropdown of the guild's roles under {@code /configure}'s Verification section (see
     * {@code VerificationRoleSyncService}), not typed by name. {@code null} if unset, in which case
     * nothing is granted for this slot rather than guessing a role.
     */
    public Long getVerifiedClanRoleId() {
        String raw = System.getenv("VERIFIED_CLAN_ROLE_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("VERIFIED_CLAN_ROLE_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * ID of the role granted to a verified member who is <em>not</em> a current clan member.
     * Typically a role something else (e.g. a join flow) already grants — this is only a
     * supplemental assign for the case where the bot verifies someone who doesn't have it yet.
     * {@code null} if unset.
     */
    public Long getVerifiedNonClanRoleId() {
        String raw = System.getenv("VERIFIED_NON_CLAN_ROLE_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("VERIFIED_NON_CLAN_ROLE_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /** ID of the role removed on verification (either clan-member or not). {@code null} if unset. */
    public Long getUnverifiedRoleId() {
        String raw = System.getenv("UNVERIFIED_ROLE_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("UNVERIFIED_ROLE_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * The dev/test guild ID — outside of production, commands are pushed here as guild commands
     * (near-instant sync) instead of globally, and {@code @Test}-annotated commands (e.g.
     * {@code /dev signups}) are only ever pushed here regardless of environment. Returns {@code
     * null} if unset, in which case dev falls back to global command registration and {@code
     * @Test} commands register nowhere at all.
     */
    public Long getGuildId() {
        String raw = System.getenv("GUILD_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("GUILD_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * Shared secret the internal API (see {@code com.runeclans.bot.internal.InternalApiServer})
     * requires on every request, via the {@code X-Internal-Secret} header. Returns {@code null} if
     * unset, in which case the internal API server does not start at all — it's opt-in, not
     * required for the bot's core functionality.
     */
    public String getInternalApiSecret() {
        String secret = System.getenv("INTERNAL_API_SECRET");

        if (secret == null || secret.isBlank()) {
            secret = dotenv.get("INTERNAL_API_SECRET");
        }

        return (secret == null || secret.isBlank()) ? null : secret;
    }

    /**
     * The website's public address (e.g. {@code https://rsyounglings.com}), no trailing slash — where {@code /wrapped}
     * fetches recap images from and what its "open the full recap" button links to. {@code null} if unset, in which
     * case {@code /wrapped} says it isn't set up.
     */
    public String getSiteUrl() {
        String url = System.getenv("SITE_URL");

        if (url == null || url.isBlank()) {
            url = dotenv.get("SITE_URL");
        }

        if (url == null || url.isBlank()) return null;
        url = url.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The runeclans website that serves the clan sites (e.g. {@code https://runeclans.com}), no trailing slash: {@code SiteInvalidator} tells its
     * {@code /api/revalidate} what changed, with per-site tags (T-393). {@code CLAN_SITES_URL}; {@code null} if unset, and then clan sites
     * refresh on their timers.
     */
    public String getClanSitesUrl() {
        String url = envOrDotenv("CLAN_SITES_URL");
        if (url == null) return null;
        url = url.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The runeclans website's billing reconcile route ({@code https://runeclans.com/api/billing/reconcile}, T-366): once a day the bot sends it the
     * subscriptions of servers it left 14+ days ago, to be cancelled at the period end. {@code null} if unset: nothing is sent.
     */
    public String getBillingReconcileUrl() {
        String url = envOrDotenv("BILLING_RECONCILE_URL");
        return url == null ? null : url.trim();
    }

    /** Where polls, signups, the group finder and forms live: {@code bot} or {@code hub} ({@code hubcommunity.HubCommunity}, T-869). Null when unset. */
    public String getCommunityBackend() {
        return envOrDotenv("COMMUNITY_BACKEND");
    }

    /** Where clan sites live: {@code bot} (unset, the default) or {@code hub} ({@code hubsites.HubSites}, T-505). Null when unset. */
    public String getSitesBackend() {
        return envOrDotenv("SITES_BACKEND");
    }

    /** The servers put on the beta list when a database is first set up ({@code com.runeclans.bot.beta.BetaSeed}). */
    public static final String BETA_SEED_GUILDS_ENV = "BETA_SEED_GUILDS";

    /** {@link #BETA_SEED_GUILDS_ENV}: comma-separated server ids, each optionally {@code id:label}; null when unset (nothing is seeded). */
    public String getBetaSeedGuilds() {
        return envOrDotenv(BETA_SEED_GUILDS_ENV);
    }

    /** A RuneSync server setting ({@code RUNESYNC_*}, e.g. {@code RUNESYNC_MIN_CLIENT_VERSION}); null when unset. */
    public String getRuneSyncSetting(String name) {
        if (!name.startsWith("RUNESYNC_")) throw new IllegalArgumentException("Only RUNESYNC_ settings are served here");
        return envOrDotenv(name);
    }

    /**
     * A setting of the RSN link service ({@code com.runeclans.bot.rsnlink}), which reads its own {@code LINK_*} variables so it can move to a
     * separate service unchanged. Only names starting with {@code LINK_} are served; null when unset.
     */
    public String getLinkSetting(String name) {
        if (!name.startsWith("LINK_")) throw new IllegalArgumentException("Only LINK_ settings are served here");
        return envOrDotenv(name);
    }

    /** The DM-code secret's name (T-659). */
    public static final String RUNELINK_DM_SECRET_ENV = "RUNELINK_DM_SECRET";
    /** Its name before RS Link became RuneLink; still read when the new one is unset, with a one-time deprecation warning. */
    public static final String LEGACY_RUNELINK_DM_SECRET_ENV = "RSNLINK_DM_SECRET";

    /**
     * {@code RUNELINK_DM_SECRET} (formerly {@code RSNLINK_DM_SECRET}, still read as a fallback): the secret runeclans-hub sends
     * ({@code X-RuneLink-Dm-Secret}, or the older {@code X-RsLink-Dm-Secret}) to have the bot DM RuneLink codes for it (T-166).
     * It opens only {@code /internal/rsnlink-dm/}. Null when unset (those routes are then closed).
     */
    public String getRuneLinkDmSecret() {
        String secret = renamedSettingFrom(RUNELINK_DM_SECRET_ENV, LEGACY_RUNELINK_DM_SECRET_ENV, this::envOrDotenv, this::warnLegacy);
        return secret == null || secret.isBlank() ? null : secret.strip();
    }

    /**
     * A setting that was renamed: the new name wins; else the old name, reported to {@code usedLegacy}; else null (T-659). Values are
     * passed through as the lookup gives them.
     */
    static String renamedSettingFrom(String name, String legacy, java.util.function.Function<String, String> lookup,
                                     java.util.function.BiConsumer<String, String> usedLegacy) {
        String value = lookup.apply(name);
        if (value != null) return value;
        value = lookup.apply(legacy);
        if (value != null) usedLegacy.accept(legacy, name);
        return value;
    }

    private void warnLegacy(String legacy, String name) {
        if (LEGACY_WARNED.add(legacy)) {
            org.slf4j.LoggerFactory.getLogger(BotConfig.class).warn("{} is deprecated and will be removed in a later release; rename it to {}", legacy, name);
        }
    }

    /** A Discord Linked Roles setting ({@code LINKED_ROLES_*}, see {@code linkedroles.LinkedRolesSettings}); null when unset. */
    public String getLinkedRolesSetting(String name) {
        if (!name.startsWith("LINKED_ROLES_")) throw new IllegalArgumentException("Only LINKED_ROLES_ settings are served here");
        return envOrDotenv(name);
    }

    /**
     * A setting of the RuneClans hub client ({@code com.runeclans.bot.runeclans}): {@code RUNECLANS_HUB_ENABLED}, {@code HUB_API_URL},
     * {@code RUNECLANS_HUB_API_KEY}. Prefixed {@code RUNECLANS_} so they can't be mistaken for the event hub's settings. Null when unset.
     * The old {@code RSCLANS_} names (before the hub was renamed to runeclans-hub) still work for one release: used only when the new name is
     * unset, with a deprecation warning.
     */
    public String getRuneClansSetting(String name) {
        return runeClansSettingFrom(name, this::envOrDotenv, legacy -> {
            if (LEGACY_WARNED.add(legacy)) {
                org.slf4j.LoggerFactory.getLogger(BotConfig.class).warn("{} is deprecated and will be removed in the next release; rename it to {}", legacy, name);
            }
        });
    }

    /**
     * {@link #getRuneClansSetting}'s rule, apart from where settings come from (T-284): the new name wins; else the old {@code RSCLANS_} name,
     * reported to {@code usedLegacy}; else null.
     */
    static String runeClansSettingFrom(String name, java.util.function.Function<String, String> lookup, java.util.function.Consumer<String> usedLegacy) {
        // HUB_API_URL is the API's address on every service (a Railway shared variable): read as it is, no old RSCLANS_ name
        if (name.equals("HUB_API_URL")) return lookup.apply(name);
        if (!name.startsWith("RUNECLANS_")) throw new IllegalArgumentException("Only RUNECLANS_ settings are served here");
        String value = lookup.apply(name);
        if (value != null) return value;
        String legacy = "RSCLANS_" + name.substring("RUNECLANS_".length());
        value = lookup.apply(legacy);
        if (value != null) usedLegacy.accept(legacy);
        return value;
    }

    private static final java.util.Set<String> LEGACY_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** What the JonnyBot face's address was before it was configurable. */
    public static final String DEFAULT_JONNYBOT_URL = "https://jonnybot.rsyounglings.com";

    /**
     * The address of JonnyBot's own website (the {@code jonnybot} face of the site host map), no trailing slash. Sent as contact information in the
     * User-Agent of every request the bot makes to a third party, so they can tell who is calling. {@code RUNECLAN_BOT_SITE_URL} (old name
     * {@code JONNYBOT_SITE_URL}, still read when the new one is unset); unset, it is
     * {@value #DEFAULT_JONNYBOT_URL}, exactly as before.
     */
    public String getJonnybotUrl() {
        return jonnybotUrlFrom(envOrDotenv("RUNECLAN_BOT_SITE_URL", "JONNYBOT_SITE_URL"));
    }

    /** The configured address cleaned up, or the default when there is none. Separate so it can be tested without the environment. */
    static String jonnybotUrlFrom(String configured) {
        if (configured == null || configured.isBlank()) return DEFAULT_JONNYBOT_URL;
        String url = configured.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Port the internal API listens on. Defaults to 8081 if unset. */
    public int getInternalApiPort() {
        String raw = System.getenv("INTERNAL_API_PORT");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("INTERNAL_API_PORT");
        }

        if (raw == null || raw.isBlank()) {
            return 8081;
        }

        return Integer.parseInt(raw.trim());
    }

    /**
     * How many gateway shards the bot logs in with ({@code SHARD_COUNT}, T-668). Defaults to 1, which behaves exactly like the single
     * connection the bot always had. Discord requires sharding from 2,500 servers; see docs/DEV-SETUP.md 3c.
     */
    public int getShardCount() {
        return parseShardCount(envOrDotenv("SHARD_COUNT"));
    }

    /**
     * The gateway process's internal API on the private network ({@code GATEWAY_INTERNAL_URL}, e.g. {@code http://bot-gateway.railway.internal:8081};
     * T-746). Only the api role reads it: routes that need servers or members are passed on there. Null when unset.
     */
    public String getGatewayInternalUrl() {
        String raw = envOrDotenv("GATEWAY_INTERNAL_URL");
        return raw == null || raw.isBlank() ? null : raw.strip();
    }

    /** Which part of the bot this process runs ({@code BOT_ROLE}: all, gateway or api; default all, T-670). See {@link BotRole}. */
    public BotRole getRole() {
        return BotRole.parse(envOrDotenv("BOT_ROLE"));
    }

    /** {@code SHARD_COUNT}'s value as a shard total: unset, not a number or below 1 is 1. */
    static int parseShardCount(String raw) {
        if (raw == null || raw.isBlank()) return 1;
        try {
            int count = Integer.parseInt(raw.trim());
            if (count >= 1) return count;
        } catch (NumberFormatException ignored) {
            // falls through to the warning
        }
        org.slf4j.LoggerFactory.getLogger(BotConfig.class).warn("SHARD_COUNT must be a whole number of 1 or more; using 1");
        return 1;
    }

    /**
     * Whether {@code RosterPollScheduler} and {@code ClanSyncScheduler} poll automatically at all.
     * Defaults to {@code true} now that the storage format has settled — every guild's clan roster is
     * spread evenly across a 3-hour window, linked-but-not-a-member accounts across an 8-hour window
     * anchored to end just ahead of RuneScape's own daily reset, and the full clan roster (plus
     * join/leave detection) syncs once a day at 00:00 UTC. Every poll only actually writes to the
     * database when something changed (see {@code RuneScapeStatsService}). Set
     * {@code RUNESCAPE_AUTO_POLL_ENABLED=false} to go back to manual-only (the admin panel's
     * "Update"/"Update All" buttons, or {@code /rsadmin}'s Sync Clan) while iterating on something that
     * touches the storage format again.
     */
    public boolean getRunescapeAutoPollEnabled() {
        String raw = System.getenv("RUNESCAPE_AUTO_POLL_ENABLED");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("RUNESCAPE_AUTO_POLL_ENABLED");
        }

        if (raw == null || raw.isBlank()) {
            return true;
        }

        return Boolean.parseBoolean(raw);
    }

    /**
     * The steady rate, in RuneMetrics profile requests per minute, the whole bot may send. Defaults to 10, which the live
     * API sustained without a single rate-limit in {@code tools/RuneMetricsRateTest.java}; set
     * {@code RUNESCAPE_POLL_REQUESTS_PER_MINUTE} to tune it without a code change. The poll coordinator spends from one
     * shared budget at this rate, so raising it makes every tier faster and every rate-limit more likely.
     */
    public int getRunescapePollRequestsPerMinute() {
        return (int) positiveNumber("RUNESCAPE_POLL_REQUESTS_PER_MINUTE", 10);
    }

    /**
     * How many requests the budget may store up while the bot is quiet, to be spent at once on a burst (a person pressing
     * Update, a clan sync starting). Defaults to 6; set {@code RUNESCAPE_POLL_BURST} to tune it.
     */
    public int getRunescapePollBurst() {
        return (int) positiveNumber("RUNESCAPE_POLL_BURST", 6);
    }

    /**
     * How long one pass over the polling list's first tier takes: every player in a clan that has been registered with
     * JonnyBot is refreshed once per window, spread evenly across it. Defaults to 180 minutes; set
     * {@code RUNESCAPE_CLAN_POLL_WINDOW_MINUTES} to tune it without a code change. Shorter means fresher tracking feeds
     * and more RuneMetrics requests; the shared request rate ({@link #getRunescapePollRequestsPerMinute}) still caps it.
     */
    public long getRunescapeClanPollWindowMinutes() {
        return positiveMinutes("RUNESCAPE_CLAN_POLL_WINDOW_MINUTES", 180);
    }

    /**
     * The same for the second tier: players who have linked their RuneScape name with JonnyBot but aren't in any
     * registered clan. They only need their own profile and history to stay roughly current, so they are polled far less
     * often. Defaults to 480 minutes; set {@code RUNESCAPE_LINKED_POLL_WINDOW_MINUTES} to tune it.
     */
    public long getRunescapeLinkedPollWindowMinutes() {
        return positiveMinutes("RUNESCAPE_LINKED_POLL_WINDOW_MINUTES", 480);
    }

    /**
     * The window for the community tier (T-736): RuneLink-linked players the hub places in a community site and in no registered clan.
     * Defaults to the linked window ({@link #getRunescapeLinkedPollWindowMinutes}); set {@code RUNESCAPE_COMMUNITY_POLL_WINDOW_MINUTES} to tune it.
     */
    public long getRunescapeCommunityPollWindowMinutes() {
        return positiveMinutes("RUNESCAPE_COMMUNITY_POLL_WINDOW_MINUTES", getRunescapeLinkedPollWindowMinutes());
    }

    /**
     * The window for the premium tier: clans on the faster plan, whose players are polled more often than a standard clan's.
     * Defaults to 60 minutes; set {@code RUNESCAPE_PREMIUM_POLL_WINDOW_MINUTES} to tune it.
     */
    public long getRunescapePremiumPollWindowMinutes() {
        return positiveMinutes("RUNESCAPE_PREMIUM_POLL_WINDOW_MINUTES", 60);
    }

    /**
     * How many of the plugin's raw XP units make one point of experience. RuneSync sends whole XP (checked against the Hiscores on 2026-10-26),
     * so the default is 1; {@code PLUGIN_XP_SCALE} changes it for a client that ever sends tenths. Always at least 1.
     */
    public long getPluginXpScale() {
        return positiveNumber("PLUGIN_XP_SCALE", 1);
    }

    /**
     * Whether clans may pay points for the XP members gain each UTC day (T-542, the Clan plugin page's "Points for XP gained"), read from the RuneClans
     * hub's XP ledger. Off by default; needs the hub client ({@code RUNECLANS_HUB_ENABLED}) too. Off, the rule isn't offered and nothing is paid.
     */
    public boolean getPluginXpPointsEnabled() {
        String raw = envOrDotenv("PLUGIN_XP_POINTS_ENABLED");
        return raw != null && raw.trim().equalsIgnoreCase("true");
    }

    /**
     * How long after midnight UTC a day's XP points are settled (T-712), so XP still on its way to the hub counts. {@code PLUGIN_XP_POINTS_GRACE_MINUTES},
     * default 15; {@link com.runeclans.bot.plugin.PluginXpPoints} holds it to 1 minute to 12 hours.
     */
    public long getPluginXpPointsGraceMinutes() {
        return positiveNumber("PLUGIN_XP_POINTS_GRACE_MINUTES", 15);
    }

    private long positiveMinutes(String name, long fallback) {
        return positiveNumber(name, fallback);
    }

    private long positiveNumber(String name, long fallback) {
        String raw = System.getenv(name);

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get(name);
        }

        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        long minutes = Long.parseLong(raw.trim());
        return minutes > 0 ? minutes : fallback;
    }

    /**
     * How many days after the bot is removed from a server its saved data is deleted for good (it is kept until then, so adding the bot
     * back picks up where it left off). Defaults to 30; set {@code GUILD_DATA_RETENTION_DAYS} to change it, or to 0 to keep data forever.
     */
    public long getGuildDataRetentionDays() {
        String raw = envOrDotenv("GUILD_DATA_RETENTION_DAYS");
        if (raw == null) return 30;
        try {
            return Math.max(0, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    /**
     * Whether servers may pick one of the machine-drafted language packs (T-099) on the dashboard's Language page. Off by default: the
     * drafts haven't been checked by a speaker yet, so until {@code LANGUAGE_PACKS_ENABLED=true} only English is offered (a server that
     * already chose a pack keeps it).
     */
    public boolean getLanguagePacksEnabled() {
        String raw = envOrDotenv("LANGUAGE_PACKS_ENABLED");
        return raw != null && raw.trim().equalsIgnoreCase("true");
    }

    /**
     * Whether bingo and scavenger XP tiles ("Gain 1M Fletching XP", T-543) are counted from the RuneClans hub's XP ledger. Off by default; needs the
     * hub client ({@code RUNECLANS_HUB_ENABLED}) too. Off, such tiles take proof or an organiser like any other tile.
     */
    public boolean getEventXpTilesEnabled() {
        String raw = envOrDotenv("EVENT_XP_TILES_ENABLED");
        return raw != null && raw.trim().equalsIgnoreCase("true");
    }

    /**
     * Whether Premium exists for clan websites (T-422). Off by default ("everything is free for now"): every server gets the website's
     * Premium features and limits until {@code PREMIUM_ENABLED=1} (or true). Keep it equal to the website's switch of the same name.
     */
    public boolean getPremiumEnabled() {
        String raw = envOrDotenv("PREMIUM_ENABLED");
        return raw != null && (raw.trim().equals("1") || raw.trim().equalsIgnoreCase("true"));
    }

    /**
     * Whether the data-retention sweep only reports what it would delete. On by default, so nothing is ever removed until the
     * operator has seen what a sweep would do and sets {@code GUILD_DATA_RETENTION_DRY_RUN=false}.
     */
    public boolean getGuildDataRetentionDryRun() {
        String raw = envOrDotenv("GUILD_DATA_RETENTION_DRY_RUN");
        return raw == null || !raw.trim().equalsIgnoreCase("false");
    }

    // --- Metrics, request log and ops alerts (T-674, docs/DEV-SETUP.md section 3c). All off by default. ---

    /** {@code METRICS_ENABLED=on}: the internal API serves {@code /metrics} (with {@link #getMetricsToken()}, private network only). */
    public boolean isMetricsEnabled() {
        return com.runeclans.bot.ops.MetricsAccess.on(envOrDotenv("METRICS_ENABLED"));
    }

    /** {@code METRICS_TOKEN}: the bearer token for {@code /metrics}, or null when unset or shorter than 24 characters (metrics then stay off). */
    public String getMetricsToken() {
        return com.runeclans.bot.ops.MetricsAccess.token(envOrDotenv("METRICS_TOKEN"));
    }

    /** Whether {@code METRICS_TOKEN} is set at all (to explain a too-short one in the log). */
    public boolean hasMetricsTokenSetting() {
        return envOrDotenv("METRICS_TOKEN") != null;
    }

    /** {@code REQUEST_LOG=on}: one log line per internal API request, with its {@code X-Request-Id}. */
    public boolean isRequestLogEnabled() {
        return com.runeclans.bot.ops.MetricsAccess.on(envOrDotenv("REQUEST_LOG"));
    }

    /** {@code OPS_ALERTS=on}: the ops alert rules run every minute and post to {@link #getOpsAlertChannelId()}. */
    public boolean isOpsAlertsEnabled() {
        return com.runeclans.bot.ops.MetricsAccess.on(envOrDotenv("OPS_ALERTS"));
    }

    /** {@code OPS_WEEKLY=on}: the weekly numbers post, Mondays 09:00 UTC, in {@link #getOpsAlertChannelId()}. */
    public boolean isOpsWeeklyEnabled() {
        return com.runeclans.bot.ops.MetricsAccess.on(envOrDotenv("OPS_WEEKLY"));
    }

    /** {@code OPS_ALERT_CHANNEL_ID}: the ops channel in the owner's server, or null. */
    public Long getOpsAlertChannelId() {
        String raw = envOrDotenv("OPS_ALERT_CHANNEL_ID");
        if (raw == null || !raw.strip().matches("\\d{15,20}")) return null;
        return Long.parseLong(raw.strip());
    }

    /** {@code OPS_HEALTH_URLS}: comma-separated health addresses the ops alerts check every minute (http or https), possibly empty. */
    public java.util.List<String> getOpsHealthUrls() {
        String raw = envOrDotenv("OPS_HEALTH_URLS");
        if (raw == null) return java.util.List.of();
        java.util.List<String> urls = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String u = part.strip();
            if (u.startsWith("https://") || u.startsWith("http://")) urls.add(u);
        }
        return java.util.List.copyOf(urls);
    }

    /** {@code OPS_429_THRESHOLD}: Discord 429 answers within 5 minutes that make a spike (default 20). */
    public int getOps429Threshold() {
        String raw = envOrDotenv("OPS_429_THRESHOLD");
        try {
            int n = raw == null ? 20 : Integer.parseInt(raw.strip());
            return n > 0 ? n : 20;
        } catch (NumberFormatException e) {
            return 20;
        }
    }

    private String envOrDotenv(String name) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) raw = dotenv.get(name);
        return raw == null || raw.isBlank() ? null : raw;
    }

    /** A renamed setting: {@code name} (environment, then {@code .env}), else the old {@code deprecatedName}, with a one-time note ({@link EnvAliases}). */
    private String envOrDotenv(String name, String deprecatedName) {
        return EnvAliases.get(name, deprecatedName, this::envOrDotenv);
    }


    /**
     * Every secret this process was started with, for {@code SnapshotRedactor} to mask wherever it turns up: the value of any
     * environment or {@code .env} entry whose name looks like a secret (token, secret, password, key, database url), plus the
     * password and user inside a connection string. Names are never returned, only values.
     */
    public java.util.Set<String> secretValues() {
        java.util.Set<String> found = new java.util.HashSet<>();
        java.util.Map<String, String> all = new java.util.HashMap<>(System.getenv());
        dotenv.entries().forEach(entry -> all.putIfAbsent(entry.getKey(), entry.getValue()));
        all.forEach((name, value) -> {
            if (value == null || value.isBlank()) return;
            String upper = name.toUpperCase(java.util.Locale.ROOT);
            boolean secretLooking = upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("PASSWORD") || upper.contains("PASSWD")
                    || upper.contains("API_KEY") || upper.endsWith("_KEY") || upper.contains("DATABASE_URL") || upper.contains("PRIVATE")
                    || upper.contains("CREDENTIAL") || upper.contains("PGPASSWORD");
            if (!secretLooking) return;
            found.add(value.strip());
            // A connection string also hides its password and user on their own.
            try {
                java.net.URI uri = java.net.URI.create(value.strip());
                if (uri.getUserInfo() != null) {
                    for (String part : uri.getUserInfo().split(":", 2)) if (!part.isBlank()) found.add(part);
                }
            } catch (IllegalArgumentException ignored) {
                // Not a URI; the whole value is already covered.
            }
        });
        return found;
    }
    public List<Long> getOwnerIds() {
        String rawOwnerIds = System.getenv("OWNER_IDS");

        if (rawOwnerIds == null || rawOwnerIds.isBlank()) {
            rawOwnerIds = dotenv.get("OWNER_IDS");
        }

        if (rawOwnerIds == null || rawOwnerIds.isBlank()) {
            return List.of();
        }

        return List.of(rawOwnerIds.split(","))
                .stream()
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .map(Long::parseLong)
                .toList();
    }

    @BService
    public static BotConfig getInstance() {
        return INSTANCE;
    }
}
