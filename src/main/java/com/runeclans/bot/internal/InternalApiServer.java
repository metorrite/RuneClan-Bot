package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.config.BotRole;
import com.runeclans.bot.hubsites.HubSites;
import com.runeclans.bot.ops.OpsTelemetry;
import com.runeclans.bot.hubsites.SitesMoved;
import com.runeclans.bot.tracking.CitadelWinners;
import com.runeclans.bot.upload.ImageUploadService;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A small, private HTTP API for the companion website (younglings-website) to read live guild
 * data and perform a couple of profile actions on a member's behalf, server-side. This is
 * deliberately NOT a public API — every request must carry the correct {@code X-Internal-Secret}
 * header, and it's meant to be called only from the website's own backend, never from a browser
 * directly.
 * <p>
 * <b>Trust boundary:</b> the shared secret authenticates that a request genuinely comes from the
 * website's backend — it says nothing about which end user it's acting on behalf of. Every write
 * endpoint here takes a {@code userId} and just performs the action on that member, trusting the
 * caller. The website is responsible for always sourcing {@code userId} from its own
 * server-side-verified login session (never from client-supplied input), or this would let anyone
 * who can reach the endpoint modify an arbitrary member.
 * <p>
 * Entirely opt-in: if {@link BotConfig#getInternalApiSecret()} or {@link BotConfig#getGuildId()}
 * aren't configured, the server simply never starts — the bot's core functionality never depends
 * on this.
 * <p>
 * Why the read endpoints live in the bot at all (rather than the website talking to Discord
 * directly): Discord only pushes member presence (online/idle/dnd/offline) over a persistent
 * Gateway connection, the kind this bot already maintains. A website's backend can't cheaply get
 * that on its own without running a second, redundant Gateway connection — so it asks the bot,
 * which already knows.
 * <p>
 * {@link JDA} isn't available yet when {@code @BService}s are normally constructed at startup — it
 * only exists once {@link com.runeclans.bot.Bot#createJDA} actually runs. So instead of taking it
 * as a constructor parameter (which would fail immediately, before the bot even logs in), this
 * waits for {@link InjectedJDAEvent}, which BotCommands fires once JDA is ready, and starts the
 * HTTP server at that point.
 */
@BService
public class InternalApiServer {
    private static final Logger log = LoggerFactory.getLogger(InternalApiServer.class);
    private static final String SECRET_HEADER = "X-Internal-Secret";
    /** Worker threads in the api role, where a request mostly waits for the gateway's answer. */
    static final int FORWARDING_THREADS = 32;
    private static final String GUILD_HEADER = "X-Guild-Id";

    private final BotConfig botConfig;
    private final TicketAdminApi ticketAdminApi;
    private final SiteApi siteApi;
    private final DirectoryApi directoryApi;
    private final PluginApi pluginApi;
    private final MemberApi memberApi;
    private final ImageUploadService imageUploads;
    private final CitadelWinners citadelWinners;
    private final FormsPublicApi formsApi;
    private final java.util.List<InternalRouteModule> routeModules;
    private final com.runeclans.bot.discord.jobs.DiscordOutbox outbox;
    private final BotlessAdminApi botlessAdmin;
    private final OverallAdminApi overallAdmin;
    /** With {@code SITES_BACKEND=hub}, the site write routes answer "moved" ({@link SitesMoved}, T-506). */
    private final HubSites hubSites;
    private JDA jda;

    public InternalApiServer(BotConfig botConfig, TicketAdminApi ticketAdminApi, SiteApi siteApi, DirectoryApi directoryApi, PluginApi pluginApi, MemberApi memberApi, ImageUploadService imageUploads,
                             CitadelWinners citadelWinners, FormsPublicApi formsApi, java.util.List<InternalRouteModule> routeModules, BotlessAdminApi botlessAdmin,
                             com.runeclans.bot.discord.jobs.DiscordOutbox outbox, OverallAdminApi overallAdmin, HubSites hubSites) {
        this.hubSites = hubSites;
        this.overallAdmin = overallAdmin;
        this.outbox = outbox;
        this.botlessAdmin = botlessAdmin;
        this.formsApi = formsApi;
        this.citadelWinners = citadelWinners;
        this.routeModules = java.util.List.copyOf(routeModules);
        this.imageUploads = imageUploads;
        this.botConfig = botConfig;
        this.ticketAdminApi = ticketAdminApi;
        this.siteApi = siteApi;
        this.directoryApi = directoryApi;
        this.pluginApi = pluginApi;
        this.memberApi = memberApi;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        start();
    }

    /**
     * One route: answered here, or (in the api role, for a route that needs Discord state) passed on to the gateway once the caller passes
     * {@code authorized}, the route's own check.
     */
    private static void route(HttpServer server, GatewayForward forward, String path, HttpHandler handler, Predicate<HttpExchange> authorized) {
        OpsTelemetry.context(server, path, forward == null ? handler : forward.handlerFor(path, handler, authorized));
    }

    /** The api role's way to the gateway, or null (logged) when its address is unusable; start-up refuses that first (BotRole). */
    private GatewayForward gatewayForward() {
        try {
            return new GatewayForward(botConfig.getGatewayInternalUrl());
        } catch (IllegalArgumentException e) {
            log.error("Internal API not started: GATEWAY_INTERNAL_URL is not an http(s) address with a host");
            return null;
        }
    }

    private void start() {
        String secret = botConfig.getInternalApiSecret();
        Long guildId = botConfig.getGuildId();

        if (secret == null) {
            log.info("INTERNAL_API_SECRET not set — internal API disabled.");
            return;
        }
        if (guildId == null) {
            log.warn("INTERNAL_API_SECRET is set but GUILD_ID is not — internal API disabled.");
            return;
        }

        try {
            // T-746: the api role has no servers or members of its own, so it passes those routes on to the gateway process
            // (built before the port is taken, so an unusable address leaves nothing bound)
            GatewayForward forward = BotRole.current().forwardsToGateway() ? gatewayForward() : null;
            if (BotRole.current().forwardsToGateway() && forward == null) return;
            int port = botConfig.getInternalApiPort();
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            if (forward != null) log.info("BOT_ROLE=api: routes that need servers or members are answered by the gateway process");
            Predicate<HttpExchange> internal = exchange -> {
                String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
                return provided != null && constantTimeEquals(provided, secret);
            };

            route(server, forward, "/internal/online-members", exchange -> handleOnlineMembers(exchange, secret, guildId), internal);
            route(server, forward, "/internal/events", exchange -> handleEvents(exchange, secret, guildId), internal);
            route(server, forward, "/internal/member", exchange -> handleGetMember(exchange, secret, guildId), internal);
            route(server, forward, "/internal/nickname", exchange -> handleSetNickname(exchange, secret, guildId), internal);
            route(server, forward, "/internal/color-role", exchange -> handleSetColorRole(exchange, secret, guildId), internal);

            route(server, forward, "/internal/admin/", exchange -> handleAdmin(exchange, secret, guildId), internal);
            route(server, forward, "/internal/site/", exchange -> handleSite(exchange, secret, guildId), internal);
            route(server, forward, "/internal/directory/", exchange -> handleDirectory(exchange, secret), internal);
            route(server, forward, "/internal/plugin/", exchange -> handlePlugin(exchange, secret), internal);
            route(server, forward, "/internal/me/", exchange -> handleMe(exchange, secret, guildId), internal);
            route(server, forward, "/internal/image/", exchange -> handleImage(exchange, secret), internal);
            route(server, forward, "/internal/forms/", exchange -> handleForms(exchange, secret), internal);
            route(server, forward, "/internal/wheel-gif/", exchange -> handleWheelGif(exchange, secret), internal);
            for (InternalRouteModule module : routeModules) {
                route(server, forward, module.prefix(), exchange -> handleModule(exchange, secret, module), exchange -> module.authorized(exchange, secret));
            }

            // A fixed crew with a bounded queue. When it's full the accepting thread runs the work itself, which slows
            // new connections down (back-pressure) instead of letting an unbounded backlog build up behind a flood.
            // In the api role most requests only wait on the gateway (up to 7 s each), so it gets more threads: waiting, not working (T-746).
            int threads = forward == null ? 6 : FORWARDING_THREADS;
            server.setExecutor(new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.CallerRunsPolicy()));
            server.start();
            log.info("Internal API listening on port {}", port);
        } catch (IOException e) {
            log.error("Failed to start internal API server", e);
        }
    }

    // --- Auth + guild resolution shared by every endpoint ---

    /** Checks method + secret + guild availability, sending the appropriate error response and returning {@code null} if any fail. */
    private Guild authorize(HttpExchange exchange, String expectedSecret, long guildId, String requiredMethod) throws IOException {
        if (!requiredMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
            return null;
        }

        String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
        if (provided == null || !constantTimeEquals(provided, expectedSecret)) {
            sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
            return null;
        }

        Guild guild = Shards.guild(jda, guildId);
        if (guild == null) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Guild not available yet"));
            return null;
        }

        return guild;
    }

    /**
     * Once the hub owns the sites ({@code SITES_BACKEND=hub}), a site write here would land on the old side: it answers
     * {@code 409 {"moved": "hub"}} before anything is changed (T-506, D-19). True when it answered.
     */
    static boolean refusedAsMoved(HubSites hubSites, HttpExchange exchange) throws IOException {
        if (!hubSites.movedToHub() || !SitesMoved.movedWrite(exchange.getRequestMethod(), exchange.getRequestURI().getPath())) return false;
        sendJson(exchange, 409, SitesMoved.answer());
        return true;
    }

    /**
     * The dashboard's routes: this checks the secret and finds the guild (any HTTP method), then
     * {@link TicketAdminApi} checks who is asking and does the rest. The server comes from the {@code X-Guild-Id}
     * header; without one it is the bot's home server, so the clan website's own admin console keeps working
     * unchanged. Whoever is asking is always checked against <em>that</em> server, never the home server's rules.
     * {@code /internal/admin/guilds}, {@code /internal/admin/editable-sites} and {@code /internal/admin/beta-testers/me} are the routes with no server: the servers the asker may manage,
     * the servers whose clan website they may edit, and whether they may run a clan website without the bot. {@code /internal/admin/overall/...} is the overall admin panel ({@link OverallAdminApi}, N5).
     * <p>
     * A server the bot isn't in (named in {@code X-Guild-Id}) goes to {@link BotlessAdminApi}, which serves a bot-less clan website's routes on
     * the website's signed sign-in facts ({@link ActorFacts}); a server the bot is in never looks at those facts.
     */
    private void handleAdmin(HttpExchange exchange, String secret, long homeGuildId) throws IOException {
        try {
            String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
            if (provided == null || !constantTimeEquals(provided, secret)) {
                sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (refusedAsMoved(hubSites, exchange)) return;
            if (path.equals("/internal/admin/guilds") || path.equals("/internal/admin/guilds/")) {
                ticketAdminApi.handleGuildList(exchange, jda);
                return;
            }
            if (path.equals("/internal/admin/beta-testers/me") || path.equals("/internal/admin/beta-testers/me/")) {
                botlessAdmin.handleBetaTesterMe(exchange, jda);
                return;
            }
            if (path.equals("/internal/admin/editable-sites") || path.equals("/internal/admin/editable-sites/")) {
                botlessAdmin.handleEditableSites(exchange, jda);
                return;
            }
            if (OverallAdminApi.ours(path)) {
                overallAdmin.handle(exchange, jda);
                return;
            }

            Long botless = botlessGuild(exchange);
            if (botless != null) {
                botlessAdmin.handle(exchange, botless, jda, secret);
                return;
            }
            Guild guild = adminGuild(exchange, homeGuildId);
            if (guild == null) return;
            ticketAdminApi.handle(exchange, guild);
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API admin request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /** The server {@code X-Guild-Id} names when it is a valid id of a server the bot isn't in (a bot-less site's), else null. */
    private Long botlessGuild(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders().getFirst(GUILD_HEADER);
        if (raw == null || raw.isBlank() || !raw.strip().matches("[0-9]{15,20}")) return null;
        long id;
        try {
            id = Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
        // Only once every server has loaded, and never for one Discord says is in an outage: a server the bot IS in must never pass as bot-less.
        if (!Shards.allConnected(jda) || Shards.unavailable(jda, id)) return null;
        return Shards.guild(jda, id) == null ? id : null;
    }

    /** The server an admin request is about, or {@code null} after the error has been sent. */
    private Guild adminGuild(HttpExchange exchange, long homeGuildId) throws IOException {
        String raw = exchange.getRequestHeaders().getFirst(GUILD_HEADER);
        boolean requested = raw != null && !raw.isBlank();
        long id = homeGuildId;
        if (requested) {
            try {
                id = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                sendJson(exchange, 400, DataObject.empty().put("error", "That isn't a valid server id."));
                return null;
            }
        }

        Guild guild = Shards.guild(jda, id);
        if (guild == null) {
            if (requested) sendJson(exchange, 404, DataObject.empty().put("error", "The bot isn't in that server."));
            else sendJson(exchange, 503, DataObject.empty().put("error", "Guild not available yet"));
            return null;
        }
        return guild;
    }

    /** A member's own settings, goals and roles — secret and guild checked here; the user id is trusted from the website's verified session. */
    private void handleMe(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
            if (provided == null || !constantTimeEquals(provided, secret)) {
                sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
                return;
            }
            Guild guild = Shards.guild(jda, guildId);
            if (guild == null) {
                sendJson(exchange, 503, DataObject.empty().put("error", "Guild not available yet"));
                return;
            }
            memberApi.handle(exchange, guild);
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API member request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /** The website's public clan data (roster, stats, profiles, who's online, events) — secret and guild checked here, the rest in {@link SiteApi}. */
    /** The plugin API (events from the in-game plugin, and a player's tokens and consent): the secret is checked here, the rest in {@link PluginApi}. */
    private void handlePlugin(HttpExchange exchange, String secret) throws IOException {
        try {
            String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
            if (provided == null || !constantTimeEquals(provided, secret)) {
                sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
                return;
            }
            pluginApi.handle(exchange);
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API plugin request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /** A self-contained module (see {@link InternalRouteModule}): only the secret is checked here. */
    private void handleModule(HttpExchange exchange, String secret, InternalRouteModule module) throws IOException {
        try {
            if (!module.authorized(exchange, secret)) {
                sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
                return;
            }
            module.handle(exchange);
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API request to {} failed: {}", module.prefix(), e.getClass().getSimpleName());
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /** The public clan directory: not about any one server, so only the secret is checked here; {@link DirectoryApi} does the rest. */
    private void handleDirectory(HttpExchange exchange, String secret) throws IOException {
        try {
            String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
            if (provided == null || !constantTimeEquals(provided, secret)) {
                sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
                return;
            }
            directoryApi.handle(exchange, jda);
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API directory request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    private void handleForms(HttpExchange exchange, String secret) throws IOException {
        try {
            String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
            if (provided == null || !constantTimeEquals(provided, secret)) {
                sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
                return;
            }
            formsApi.handle(exchange, jda);
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API forms request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    private void handleSite(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "GET");
            if (guild == null) return;
            siteApi.handle(exchange, guild);
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API site request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /**
     * Cache-first, then a one-off REST fetch on a miss. Needed because {@link com.runeclans.bot.Bot}
     * now runs {@code MemberCachePolicy.ONLINE} (see its javadoc) instead of {@code ALL} — an offline
     * member (or one who's simply never come online since the bot's last reconnect) won't be in the
     * local cache, but this endpoint still needs to answer for them. These are all low-frequency,
     * admin/website-triggered calls, not a hot path, so the occasional extra REST round trip is a
     * non-issue. {@code null} if the member genuinely isn't in the guild at all.
     */
    private Member resolveMember(Guild guild, long userId) {
        return ServerProfile.resolveMember(guild, userId);
    }

    // --- Read endpoints ---

    private void handleOnlineMembers(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "GET");
            if (guild == null) return;

            sendJson(exchange, 200, buildOnlineMembers(guild));
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API /online-members request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    private void handleEvents(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "GET");
            if (guild == null) return;

            sendJson(exchange, 200, buildScheduledEvents(guild));
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API /events request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    private void handleGetMember(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "GET");
            if (guild == null) return;

            String userIdRaw = queryParam(exchange, "userId");
            if (userIdRaw == null) {
                sendJson(exchange, 400, DataObject.empty().put("error", "Missing userId query parameter"));
                return;
            }

            Member member = resolveMember(guild, Long.parseLong(userIdRaw));
            if (member == null) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Member not found in guild"));
                return;
            }

            sendJson(exchange, 200, buildMemberInfo(member));
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, DataObject.empty().put("error", "userId must be numeric"));
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API /member request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    // --- Write endpoints ---

    private void handleSetNickname(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "POST");
            if (guild == null) return;

            DataObject body = readJsonBody(exchange);
            Member member = resolveMember(guild, body.getLong("userId"));
            if (member == null) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Member not found in guild"));
                return;
            }

            DataObject result = ServerProfile.setNickname(outbox, guild, member, body);
            log.info("Queued a nickname change for member {} in guild {}", member.getId(), guildId);
            sendJson(exchange, 200, result);
        } catch (ServerProfile.Refused e) {
            sendJson(exchange, e.status, DataObject.empty().put("error", e.getMessage()));
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API /nickname request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error",
                    "Failed to change nickname — the bot may not have permission here"));
        }
    }

    private void handleSetColorRole(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "POST");
            if (guild == null) return;

            DataObject body = readJsonBody(exchange);
            Member member = resolveMember(guild, body.getLong("userId"));
            if (member == null) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Member not found in guild"));
                return;
            }

            DataObject result = ServerProfile.setColorRole(outbox, guild, member, body);
            log.info("Queued color role '{}' for member {} in guild {}", result.getString("colorRole", null), member.getId(), guildId);
            sendJson(exchange, 200, result);
        } catch (ServerProfile.Refused e) {
            sendJson(exchange, e.status, DataObject.empty().put("error", e.getMessage()));
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API /color-role request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error",
                    "Failed to change color role — the bot may not have permission here"));
        }
    }

    // --- Response builders ---

    private DataObject buildOnlineMembers(Guild guild) {
        DataArray members = DataArray.empty();
        if (!com.runeclans.bot.config.PrivilegedIntents.presence()) return DataObject.empty().put("members", members).put("unavailable", true);

        for (Member member : guild.getMembers()) {
            if (member.getUser().isBot()) continue;

            OnlineStatus status = member.getOnlineStatus();
            if (status == OnlineStatus.OFFLINE || status == OnlineStatus.UNKNOWN) continue;

            List<Role> roles = member.getRoles();
            Role topRole = roles.isEmpty() ? null : roles.getFirst();

            DataObject entry = DataObject.empty()
                    .put("id", member.getId())
                    .put("displayName", member.getEffectiveName())
                    .put("avatarUrl", member.getEffectiveAvatarUrl())
                    .put("status", status.getKey());

            entry.put("topRole", topRole == null ? null : DataObject.empty()
                    .put("name", topRole.getName())
                    .put("colorRaw", topRole.getColors().getPrimaryRaw()));

            members.add(entry);
        }

        return DataObject.empty().put("members", members).put("unavailable", false);
    }

    private DataObject buildScheduledEvents(Guild guild) {
        DataArray events = DataArray.empty();

        for (ScheduledEvent event : guild.getScheduledEvents()) {
            events.add(DataObject.empty()
                    .put("id", event.getId())
                    .put("name", event.getName())
                    .put("description", event.getDescription())
                    .put("imageUrl", event.getImageUrl())
                    .put("location", event.getLocation())
                    .put("startTime", event.getStartTime().toString())
                    .put("endTime", event.getEndTime() == null ? null : event.getEndTime().toString())
                    .put("interestedCount", event.getInterestedUserCount()));
        }

        return DataObject.empty().put("events", events);
    }

    private DataObject buildMemberInfo(Member member) {
        return ServerProfile.info(member);
    }

    // --- Helpers ---

    /**
     * A picture a server uploaded, by its token, for the website to pass on to whoever asks (Discord fetches an embed's pictures from
     * the website's address, never from the bot). No server is named: the token is the secret that names one picture.
     */
    private void handleImage(HttpExchange exchange, String secret) throws IOException {
        try {
            String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
            if (provided == null || !constantTimeEquals(provided, secret)) {
                sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String token = path.substring("/internal/image/".length());
            var image = imageUploads.find(token);
            if (image.isEmpty()) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
                return;
            }

            byte[] data = image.get().data();
            exchange.getResponseHeaders().set("Content-Type", image.get().contentType());
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(data);
            }
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API image request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /**
     * A locked Citadel wheel winner's GIF by its unguessable token, for the website's public GIF address (which is what makes the picture show inside a
     * Discord message). The secret is checked here, so only the website can ask; the token is what keeps one week's picture from being guessed.
     */
    private void handleWheelGif(HttpExchange exchange, String secret) throws IOException {
        try {
            String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
            if (provided == null || !constantTimeEquals(provided, secret)) {
                sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
                return;
            }
            String token = exchange.getRequestURI().getPath().substring("/internal/wheel-gif/".length());
            var gif = citadelWinners.gifByToken(token);
            if (gif.isEmpty()) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
                return;
            }
            byte[] data = gif.get();
            exchange.getResponseHeaders().set("Content-Type", "image/gif");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(data);
            }
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            sendSlow(exchange);
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Internal API wheel GIF request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /** Discord didn't answer a who-is-this question in time ({@link com.runeclans.bot.discord.DiscordLookups.Slow}): a 503 the website can retry. */
    static void sendSlow(HttpExchange exchange) throws IOException {
        sendJson(exchange, 503, DataObject.empty().put("error", com.runeclans.bot.discord.DiscordLookups.Slow.MESSAGE).put("code", "DISCORD_SLOW"));
    }

    static void sendJson(HttpExchange exchange, int statusCode, DataObject payload) throws IOException {
        byte[] body = payload.toJson();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    private DataObject readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            return DataObject.fromJson(body);
        }
    }

    private String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;

        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Avoids leaking secret length/content via response-timing differences. */
    private boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }
}
