package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.access.AccessArea;
import com.runeclans.bot.access.AccessGrantService;
import com.runeclans.bot.beta.BetaAccessService;

import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.commands.ticket.HelpOnboarding;
import com.runeclans.bot.commands.ticket.HelpPanels;
import com.runeclans.bot.commands.ticket.HelpRules;
import com.runeclans.bot.commands.ticket.TicketRules;
import com.runeclans.bot.commands.ticket.TicketService;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminJson.BadRequest;
import com.runeclans.bot.clansite.SiteRoles;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.permission.OwnerOverride;
import com.runeclans.bot.permission.DashboardAccess.Tier;
import com.runeclans.bot.ticket.TicketModels.Field;
import com.runeclans.bot.ticket.TicketModels.HelpSettings;
import com.runeclans.bot.ticket.TicketModels.Option;
import com.runeclans.bot.ticket.TicketModels.Panel;
import com.runeclans.bot.ticket.TicketModels.PanelDefinition;
import com.runeclans.bot.ticket.TicketModels.Settings;
import com.runeclans.bot.ticket.TicketModels.Status;
import com.runeclans.bot.ticket.TicketModels.Ticket;
import com.runeclans.bot.ticket.TicketRepository;
import com.runeclans.bot.ticket.TicketRepository.Transcript;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The ticket dashboard's side of the internal API, under {@code /internal/admin/}. {@link InternalApiServer}
 * has already checked the shared secret and found the guild; this class additionally checks <em>who</em> is
 * asking. The website sends the logged-in user's Discord id in {@code X-Actor-Id}, and every request here —
 * not just a login check at the start — looks that person up in the guild and refuses unless they are Admin
 * tier or hold the Developer role (see {@link DashboardAccess}). A leaked secret alone therefore can't act as
 * a user the website never authenticated, and a demoted admin loses access on their next click.
 * <p>
 * Everything is read from and written to the database through {@link TicketRepository}, so nothing here holds
 * state of its own.
 */
@BService
public class TicketAdminApi {
    private static final Logger log = LoggerFactory.getLogger(TicketAdminApi.class);
    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final String PREFIX = "/internal/admin/";
    private static final int MAX_PAGE = 100;
    /** The longest the server picker waits on Discord, in total, for the servers whose members the bot hasn't cached. */
    private static final java.time.Duration GUILD_LIST_BUDGET = java.time.Duration.ofMillis(2500);

    private final DashboardAccess access;
    private final TicketRepository repository;
    private final TicketService service;
    private final ClanAdminApi clanAdmin;
    private final CommunityAdminApi communityAdmin;
    /** Writes per admin: a few quick clicks are fine, a sustained flood is refused. */
    private final RateLimiter adminWrites = new RateLimiter(20, 1_000);
    private final AdminOpsApi ops;
    private final AdminToolsStore auditStore;
    private final HelpOnboarding onboarding;
    private final HelpPanels helpPanels;
    private final WelcomeAdminApi welcomeAdmin;
    private final ServerSetupAdminApi serverSetup;
    private final PermissionsAdminApi permissions;
    private final HubAdminApi hubAdmin;
    private final OverviewAdminApi overviewAdmin;
    private final ImageAdminApi imageAdmin;
    private final List<AdminRouteExtension> extensions;
    private final BetaAccessService betaAccess;
    private final AccessGrantService accessGrants;
    private final SiteAccessRoutes siteAccess;
    private final ClanSiteAdminApi clanSiteAdmin;
    private final SitePictureAdminApi sitePictures;
    private final com.runeclans.bot.access.OverallAdmins overallAdmins;

    public TicketAdminApi(DashboardAccess access, TicketRepository repository, TicketService service, ClanAdminApi clanAdmin, CommunityAdminApi communityAdmin, AdminOpsApi ops, AdminToolsStore auditStore,
                          HelpOnboarding onboarding, HelpPanels helpPanels, WelcomeAdminApi welcomeAdmin, ServerSetupAdminApi serverSetup, PermissionsAdminApi permissions, HubAdminApi hubAdmin, OverviewAdminApi overviewAdmin, ImageAdminApi imageAdmin, BetaAccessService betaAccess,
                          AccessGrantService accessGrants, List<AdminRouteExtension> extensions, SiteAccessRoutes siteAccess, ClanSiteAdminApi clanSiteAdmin,
                          SitePictureAdminApi sitePictures, com.runeclans.bot.access.OverallAdmins overallAdmins) {
        this.siteAccess = siteAccess;
        this.clanSiteAdmin = clanSiteAdmin;
        this.sitePictures = sitePictures;
        this.overallAdmins = overallAdmins;
        this.accessGrants = accessGrants;
        this.extensions = extensions;
        log.info("Admin API ready with {} route extension(s): {}", extensions.size(), extensions.stream().map(e -> e.getClass().getSimpleName()).toList());
        this.betaAccess = betaAccess;
        this.imageAdmin = imageAdmin;
        this.overviewAdmin = overviewAdmin;
        this.welcomeAdmin = welcomeAdmin;
        this.serverSetup = serverSetup;
        this.permissions = permissions;
        this.hubAdmin = hubAdmin;
        this.onboarding = onboarding;
        this.helpPanels = helpPanels;
        this.access = access;
        this.repository = repository;
        this.service = service;
        this.clanAdmin = clanAdmin;
        this.communityAdmin = communityAdmin;
        this.ops = ops;
        this.auditStore = auditStore;
    }

    /** A failure with the status and message the website should see. */
    static final class ApiError extends RuntimeException {
        final int status;
        final List<String> problems;

        ApiError(int status, String message) {
            this(status, message, List.of());
        }

        ApiError(int status, String message, List<String> problems) {
            super(message);
            this.status = status;
            this.problems = problems;
        }
    }

    public void handle(HttpExchange exchange, Guild guild) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String route = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
            if (route.endsWith("/")) route = route.substring(0, route.length() - 1);
            String[] parts = route.isEmpty() ? new String[0] : route.split("/");
            String method = exchange.getRequestMethod().toUpperCase();

            long askerId = actorIdOf(exchange);
            OwnerOverride override = access.ownerOverride();
            // the bot owner's "lift restrictions" mode (T-445): started and ended here, by the owner alone, after the website's acknowledgement
            if (parts.length == 1 && parts[0].equals("owner-override")) {
                InternalApiServer.sendJson(exchange, 200, OwnerOverrideRoute.handle(override, id -> overviewAdmin.isKnownOwner(id) || overviewAdmin.isOwner(guild, id), () -> body(exchange),
                        auditStore::audit, () -> ownerName(guild, askerId), guild.getIdLong(), askerId, method));
                return;
            }
            // decided once per request: the access check, the stand-in and the audit name all use this same answer
            boolean overriding = askerId != 0 && override.active(guild.getIdLong(), askerId);
            Member found = actorOf(exchange, guild);
            // while it runs, the owner is this server's Admin even when they aren't a member of it: a stand-in with no roles or Discord rights
            Member actor = OwnerOverrideRoute.actorFor(found, overriding, () -> OwnerStandIn.of(guild, askerId));
            Tier tier = actor == null ? Tier.NONE : access.tierOf(guild, actor);
            boolean coordinator = actor != null && tier == Tier.NONE && access.isEventCoordinator(guild, actor);

            // whoami is the one route that answers "no" with a 200: the website uses it to decide what to show.
            if (parts.length == 1 && parts[0].equals("whoami")) {
                requireMethod(method, "GET");
                DataObject who = whoami(guild, actor, tier);
                // An Event coordinator is not allowed in the dashboard as such (allowed stays false); the website lets them into the event manager only.
                if (coordinator) who.put("coordinator", true).put("guildId", guild.getId()).put("guildName", guild.getName()).put("guildIconUrl", guild.getIconUrl());
                if (actor != null && tier != Tier.NONE) who.put("isOwner", overviewAdmin.isOwner(guild, actor.getIdLong()));
                // an overall RuneClans admin (N5) may open this server's site admin and editor, member or not
                who.put("overallAdmin", askerId != 0 && overallAdmins.isOverallAdmin(guild.getJDA(), askerId));
                // the bot owner may lift the restrictions for this server (T-445); say whether they did and until when
                // from what is already known (no Discord call inside the web request; the overall admin check above has just filled it)
                if (askerId != 0 && overviewAdmin.isKnownOwner(askerId)) who.put("ownerOverride", OwnerOverrideRoute.state(override, guild.getIdLong(), askerId));
                // Whether this person may use JonnyBot's website while it is closed: the clan's own admins, or admins of a trusted beta server.
                // Worked out from the asker's id alone, because a beta tester usually isn't a member of the clan's server.
                boolean beta = tier != Tier.NONE || (askerId != 0 && betaAccess.managesAnyBetaGuild(guild.getJDA(), askerId));
                who.put("beta", beta);
                // Per restricted area: the beta rule above, or a grant on the owner's access list for that area. Fails closed.
                DataObject areas = DataObject.empty();
                for (AccessArea area : AccessArea.values()) {
                    // the bot-less site builder is opened by a grant alone (BotlessAdminApi), so it reports just that
                    boolean granted = accessGrants.allows(guild.getJDA(), area, askerId);
                    areas.put(area.key(), area == AccessArea.SITE_BUILDER_BETA ? granted : beta || granted);
                }
                who.put("areas", areas);
                InternalApiServer.sendJson(exchange, 200, who);
                return;
            }
            // the clan website's routes for people let in by the site itself, member or not (overall admins, site admins, editors; N5)
            if (tier == Tier.NONE && !(coordinator && DashboardAccess.coordinatorMayUse(method, parts))) {
                SiteCaller site = siteAccess.caller(exchange, guild, askerId, actor, method, parts);
                if (site != null) {
                    if (!method.equals("GET") && !adminWrites.tryAcquire(Long.toString(site.userId()))) throw new ApiError(429, Text.of(guild, "dashboard.you_re_doing_that_too_fast"));
                    DataObject answer = SiteAccessRoutes.route(site, method, parts, () -> method.equals("GET") ? AdminRouteExtension.queryOf(exchange.getRequestURI()) : body(exchange), clanSiteAdmin, sitePictures, imageAdmin, () -> AdminReads.cached(guild, "structure", () -> structure(guild)),
                            () -> ops.audit(guild, queryParam(exchange, "limit"), null, "clan-site"));
                    if (answer == null) {
                        InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
                        return;
                    }
                    if (!method.equals("GET")) {
                        AdminReads.forget(guild);
                        String name = siteAuditName(actor != null ? actor.getEffectiveName() : null, site.facts(), siteRoles(site));
                        auditStore.audit(guild.getIdLong(), Long.toString(site.userId()), name, method, route, 200);
                    }
                    InternalApiServer.sendJson(exchange, 200, answer);
                    return;
                }
            }
            if (tier == Tier.NONE && !(coordinator && DashboardAccess.coordinatorMayUse(method, parts))
                    && !(actor != null && extensions.stream().anyMatch(e -> e.admits(guild, actor, method, parts)))) {
                InternalApiServer.sendJson(exchange, 403, DataObject.empty().put("error", "You don't have access to the dashboard."));
                return;
            }

            DataObject result = route(exchange, guild, actor, overriding, method, parts);
            if (result == null) {
                InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
                return;
            }
            // Every change made from the dashboard is written to the audit log (never the request body, which may hold private text).
            if (!method.equals("GET")) {
                // the clan sees the owner's changes in its own log, marked as made with restrictions lifted (T-445)
                auditStore.audit(guild.getIdLong(), actor.getId(), OwnerOverrideRoute.auditName(actor.getEffectiveName(), overriding), method, route, 200);
            }
            InternalApiServer.sendJson(exchange, 200, result);
        } catch (ApiError e) {
            DataObject body = DataObject.empty().put("error", e.getMessage());
            if (!e.problems.isEmpty()) {
                DataArray problems = DataArray.empty();
                e.problems.forEach(problems::add);
                body.put("problems", problems);
            }
            InternalApiServer.sendJson(exchange, e.status, body);
        } catch (com.runeclans.bot.hubcommunity.HubCommunityProblem e) {
            // hub-community: the hub's answer, in words an admin can read
            InternalApiServer.sendJson(exchange, e.httpStatus(), DataObject.empty().put("error", e.getMessage()));
        } catch (BadRequest e) {
            InternalApiServer.sendJson(exchange, 400, DataObject.empty().put("error", e.getMessage()));
        } catch (DiscordLookups.Slow e) {
            InternalApiServer.sendSlow(exchange);
        } catch (Exception e) {
            log.error("Admin API request {} {} failed", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e);
            InternalApiServer.sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /**
     * The servers JonnyBot is in where the asker may use the dashboard, for the dashboard's server picker. Each server is
     * judged by its own rules ({@link DashboardAccess}), and only servers the asker can manage are named, so nobody
     * learns which other servers the bot is in. Answers an empty list, not an error, for an unknown asker.
     */
    public void handleGuildList(HttpExchange exchange, net.dv8tion.jda.api.JDA jda) throws IOException {
        try {
            requireMethod(exchange.getRequestMethod().toUpperCase(), "GET");
            DataArray guilds = DataArray.empty();
            long actorId = actorIdOf(exchange);
            if (actorId != 0) {
                // asked of every server at once, with one shared wait: a server Discord is slow about is left out this time, not waited on
                List<Guild> all = Shards.guilds(jda);
                List<DiscordLookups.MemberLookup> found = DiscordLookups.memberInEach(all, actorId, GUILD_LIST_BUDGET);
                for (int i = 0; i < all.size(); i++) {
                    Guild guild = all.get(i);
                    Member member = found.get(i).member();
                    if (member == null) continue;
                    Tier tier = access.tierOf(guild, member);
                    boolean coordinator = tier == Tier.NONE && access.isEventCoordinator(guild, member);
                    if (tier == Tier.NONE && !coordinator) continue;
                    guilds.add(DataObject.empty().put("id", guild.getId()).put("name", guild.getName()).put("iconUrl", guild.getIconUrl())
                            .put("memberCount", guild.getMemberCount()).put("tier", coordinator ? "COORDINATOR" : tier.name()));
                }
            }
            InternalApiServer.sendJson(exchange, 200, DataObject.empty().put("guilds", guilds));
        } catch (ApiError e) {
            InternalApiServer.sendJson(exchange, e.status, DataObject.empty().put("error", e.getMessage()));
        }
    }

    // ---------- the bot owner's override (T-445) ----------

    private static String ownerName(Guild guild, long userId) {
        String name = com.runeclans.bot.discord.DiscordLookups.userName(guild.getJDA(), userId);
        return name == null || name.isBlank() ? "The bot owner" : name;
    }

    /**
     * The name a site change is logged under, as the clan sees it in its site history (T-445, Drake: clans see the owner's edits): a RuneClan
     * Admin who has no owner rights on this site of their own is marked as one; anyone else by name, or as "A Site Admin" when not a member.
     */
    static String siteAuditName(String memberName, SiteRoles.Facts facts, SiteRoles.Settings settings) {
        if (facts != null && SiteRoles.onlyAsOverallAdmin(facts, settings)) return memberName == null ? "A RuneClan Admin" : memberName + " (RuneClan Admin)";
        return memberName != null ? memberName : facts != null && facts.overallAdmin() ? "A RuneClan Admin" : "A Site Admin";
    }

    private SiteRoles.Settings siteRoles(SiteCaller site) {
        return clanSiteAdmin.siteSettings(site.guildId());
    }

    // ---------- who is asking ----------

    /** The asker's Discord id from the request, or 0 if there isn't a usable one. */
    private static long actorIdOf(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders().getFirst(ACTOR_HEADER);
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The asker in this server, or null when they aren't in it; throws {@link DiscordLookups.Slow} (a 503) when Discord didn't answer in time. */
    private static Member memberIn(Guild guild, long userId) {
        return DiscordLookups.memberOrSlow(guild, userId);
    }

    private Member actorOf(HttpExchange exchange, Guild guild) {
        long id = actorIdOf(exchange);
        return id == 0 ? null : memberIn(guild, id);
    }

    private static DataObject whoami(Guild guild, Member actor, Tier tier) {
        DataObject json = DataObject.empty().put("allowed", tier != Tier.NONE).put("tier", tier.name());
        if (actor != null) {
            json.put("id", actor.getId()).put("displayName", actor.getEffectiveName()).put("avatarUrl", actor.getEffectiveAvatarUrl());
        }
        // which server this answer is about; only given to someone allowed in, so it never names a server to a stranger
        if (tier != Tier.NONE) json.put("guildId", guild.getId()).put("guildName", guild.getName()).put("guildIconUrl", guild.getIconUrl());
        return json;
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) throw new ApiError(405, Text.en("dashboard.method_not_allowed"));
    }

    // ---------- routing ----------

    /**
     * {@code null} for a path that doesn't exist. Pages that ask Discord a lot are served from {@link AdminReads}' cache and writes that talk to
     * Discord get a time limit ({@link AdminRoutePolicy}); every change drops the server's cached pages.
     */
    private DataObject route(HttpExchange exchange, Guild guild, Member actor, boolean overriding, String method, String[] parts) throws IOException {
        try {
            switch (AdminRoutePolicy.modeOf(method, parts)) {
                case CACHED -> {
                    URI uri = exchange.getRequestURI();
                    return AdminReads.cached(guild, uri.getRawPath() + "?" + uri.getRawQuery(), () -> routeNow(exchange, guild, actor, method, parts));
                }
                case BOUNDED -> {
                    return AdminReads.bounded(guild, () -> routeNow(exchange, guild, actor, method, parts),
                            () -> auditStore.audit(guild.getIdLong(), actor.getId(), OwnerOverrideRoute.auditName(actor.getEffectiveName(), overriding), method, String.join("/", parts), 202));
                }
                default -> {
                    return routeNow(exchange, guild, actor, method, parts);
                }
            }
        } finally {
            if (!method.equals("GET")) AdminReads.forget(guild);
        }
    }

    private DataObject routeNow(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) {
        try {
            return routeUncached(exchange, guild, actor, method, parts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private DataObject routeUncached(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) throws IOException {
        if (parts.length == 1 && parts[0].equals("structure")) {
            requireMethod(method, "GET");
            return structure(guild);
        }
        if (!method.equals("GET") && !adminWrites.tryAcquire(actor.getId())) throw new ApiError(429, Text.of(guild, "dashboard.you_re_doing_that_too_fast"));

        DataObject opsResult = opsRoute(exchange, guild, actor, method, parts);
        if (opsResult != null) return opsResult;

        // features that bring their own routes (see AdminRouteExtension), so adding one never means editing this class
        for (AdminRouteExtension extension : extensions) {
            DataObject answer = extension.route(guild, actor, method, parts,
                    () -> method.equals("GET") ? AdminRouteExtension.queryOf(exchange.getRequestURI()) : body(exchange));
            if (answer != null) return answer;
        }

        if (parts.length >= 1 && (parts[0].equals("polls") || parts[0].equals("signups"))) return communityRoute(exchange, guild, actor, method, parts);
        if (parts.length == 1 && parts[0].equals("selfroles")) {
            if (method.equals("GET")) return clanAdmin.selfRoles(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveSelfRoles(guild, actor, body(exchange));
        }
        if (parts.length >= 1 && parts[0].equals("promotions")) {
            if (parts.length == 1) {
                requireMethod(method, "GET");
                return clanAdmin.promotions(guild);
            }
            if (parts.length == 3 && parts[2].equals("done")) {
                requireMethod(method, "POST");
                return clanAdmin.markPromoted(guild, actor, parts[1]);
            }
            return null;
        }
        if (parts.length >= 1 && parts[0].equals("tracking")) {
            if (parts.length == 1) {
                requireMethod(method, "GET");
                return clanAdmin.tracking(guild);
            }
            requireMethod(method, "PUT");
            return clanAdmin.saveTracking(guild, actor, parts[1], body(exchange));
        }
        if (parts.length == 1 && parts[0].equals("post")) {
            requireMethod(method, "POST");
            return clanAdmin.post(guild, actor, body(exchange));
        }
        if (parts.length == 1 && parts[0].equals("community")) {
            if (method.equals("GET")) return clanAdmin.community(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveCommunity(guild, actor, body(exchange));
        }
        if (parts.length == 1 && parts[0].equals("news")) {
            if (method.equals("GET")) return clanAdmin.newsChannels(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveNewsChannels(guild, actor, body(exchange));
        }
        if (parts.length == 2 && parts[0].equals("clan") && parts[1].equals("website")) {
            if (method.equals("GET")) return clanAdmin.clanWebsite(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveClanWebsite(guild, actor, body(exchange));
        }
        if (parts.length == 2 && parts[0].equals("site") && parts[1].equals("options")) {
            if (method.equals("GET")) return clanAdmin.siteOptions(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveSiteOptions(guild, actor, body(exchange));
        }
        if (parts.length == 2 && parts[0].equals("clan") && parts[1].equals("points")) {
            if (method.equals("GET")) return clanAdmin.clanPoints(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveClanPoints(guild, actor, body(exchange));
        }
        if (parts.length == 3 && parts[0].equals("images")) {
            if (method.equals("PUT")) return imageAdmin.upload(guild, actor, parts[1], parts[2], body(exchange));
            requireMethod(method, "DELETE");
            return imageAdmin.remove(guild, actor, parts[1], parts[2]);
        }
        if (parts.length == 1 && parts[0].equals("overview")) {
            requireMethod(method, "GET");
            return overviewAdmin.overview(guild, actor);
        }
        if (parts.length >= 1 && parts[0].equals("notices")) {
            if (parts.length == 1) {
                if (method.equals("GET")) return overviewAdmin.listNotices();
                requireMethod(method, "POST");
                return overviewAdmin.addNotice(guild, actor, body(exchange));
            }
            if (parts.length == 2) {
                requireMethod(method, "DELETE");
                return overviewAdmin.removeNotice(guild, actor, parts[1]);
            }
            return null;
        }
        if (parts.length >= 1 && parts[0].equals("hub")) {
            if (parts.length == 1) {
                requireMethod(method, "GET");
                return hubAdmin.get(guild);
            }
            if (parts.length == 2) {
                requireMethod(method, "PUT");
                return hubAdmin.save(guild, actor, parts[1], body(exchange));
            }
            return null;
        }
        if (parts.length == 1 && parts[0].equals("permissions")) {
            if (method.equals("GET")) return permissions.get(guild);
            requireMethod(method, "PUT");
            return permissions.save(guild, actor, body(exchange));
        }
        if (parts.length == 1 && parts[0].equals("setup")) {
            if (method.equals("GET")) return serverSetup.get(guild);
            requireMethod(method, "PUT");
            return serverSetup.save(guild, actor, body(exchange));
        }
        if (parts.length >= 1 && parts[0].equals("welcome")) {
            if (parts.length == 1) {
                if (method.equals("GET")) return welcomeAdmin.get(guild);
                requireMethod(method, "PUT");
                return welcomeAdmin.save(guild, actor, body(exchange));
            }
            if (parts.length == 2 && parts[1].equals("test")) {
                requireMethod(method, "POST");
                return welcomeAdmin.test(guild, actor, body(exchange));
            }
            return null;
        }
        if (parts.length >= 2 && parts[0].equals("help")) return helpRoute(exchange, guild, actor, method, parts);
        if (parts.length < 2 || !parts[0].equals("ticket")) return null;

        switch (parts[1]) {
            case "settings" -> {
                if (parts.length != 2) return null;
                if (method.equals("GET")) return TicketAdminJson.settingsJson(repository.getSettings(guild.getIdLong()));
                requireMethod(method, "PUT");
                return saveSettings(exchange, guild, actor);
            }
            case "panels" -> {
                return routePanels(exchange, guild, actor, method, parts);
            }
            case "defaults" -> {
                if (parts.length != 2) return null;
                if (method.equals("GET")) return panelDefaults(guild);
                requireMethod(method, "PUT");
                return savePanelDefaults(exchange, guild, actor);
            }
            case "stats" -> {
                if (parts.length != 2) return null;
                requireMethod(method, "GET");
                return stats(guild);
            }
            case "tickets" -> {
                requireMethod(method, "GET");
                if (parts.length == 2) return listTickets(exchange, guild);
                if (parts.length == 3) return ticketDetail(guild, parts[2]);
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** Roster view, attention list, health, audit log, notes and scheduled posts; {@code null} when the path isn't one of those. */
    private DataObject opsRoute(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) {
        if (parts.length == 0) return null;
        switch (parts[0]) {
            case "members" -> {
                if (parts.length != 1) return null;
                requireMethod(method, "GET");
                return ops.members(guild);
            }
            case "attention" -> {
                requireMethod(method, "GET");
                return ops.attention(guild);
            }
            case "health" -> {
                requireMethod(method, "GET");
                // The whole bot's health (gateway, database, memory, polling of every server) is for the bot's owner only; a server's own
                // summary is on the Overview.
                if (!overviewAdmin.isOwner(guild, actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_see_3"));
                return ops.health(guild);
            }
            case "polling" -> {
                if (parts.length == 1) {
                    requireMethod(method, "GET");
                    return ops.pollingStatus();
                }
                if (parts.length == 2 && parts[1].equals("poll")) {
                    requireMethod(method, "POST");
                    return ops.pollPlayer(body(exchange).getString("rsn", ""));
                }
                return null;
            }
            case "audit" -> {
                requireMethod(method, "GET");
                return ops.audit(guild, queryParam(exchange, "limit"), queryParam(exchange, "actor"), queryParam(exchange, "q"));
            }
            case "notes" -> {
                if (parts.length == 1 && method.equals("GET")) return ops.notes(guild, queryParam(exchange, "rsn"));
                if (parts.length == 1) {
                    requireMethod(method, "POST");
                    return ops.addNote(guild, actor, body(exchange));
                }
                if (parts.length == 2) {
                    requireMethod(method, "DELETE");
                    return ops.deleteNote(guild, idOf(parts[1]), queryParam(exchange, "rsn"));
                }
                return null;
            }
            case "scheduled" -> {
                if (parts.length == 1 && method.equals("GET")) return ops.scheduled(guild);
                if (parts.length == 1) {
                    requireMethod(method, "POST");
                    return ops.schedulePost(guild, actor, body(exchange));
                }
                if (parts.length == 2) {
                    requireMethod(method, "DELETE");
                    return ops.cancelScheduled(guild, idOf(parts[1]));
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return null;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /** Polls and signups: {@code POST polls}, {@code POST polls/{id}/end}, {@code GET|POST signups}, {@code POST signups/{id}/{action}}. */
    private DataObject communityRoute(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) {
        if (parts[0].equals("polls")) {
            if (parts.length == 1) {
                requireMethod(method, "POST");
                return communityAdmin.createPoll(guild, actor, body(exchange));
            }
            if (parts.length == 3 && parts[2].equals("end")) {
                requireMethod(method, "POST");
                return communityAdmin.endPoll(guild, actor, idOf(parts[1]));
            }
            return null;
        }

        if (parts.length == 1) {
            if (method.equals("GET")) return communityAdmin.signupList(guild);
            requireMethod(method, "POST");
            return communityAdmin.createSignup(guild, actor, body(exchange));
        }
        if (parts.length == 3) {
            requireMethod(method, "POST");
            DataObject body;
            try (var in = exchange.getRequestBody()) {
                byte[] bytes = in.readAllBytes();
                body = bytes.length == 0 ? DataObject.empty() : DataObject.fromJson(bytes);
            } catch (Exception e) {
                throw new ApiError(400, Text.of(guild, "dashboard.the_request_body_isn_t_valid"));
            }
            return communityAdmin.signupAction(guild, actor, idOf(parts[1]), parts[2], body);
        }
        return null;
    }

    private DataObject routePanels(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) throws IOException {
        if (parts.length == 2) {
            if (method.equals("GET")) return listPanels(guild);
            requireMethod(method, "POST");
            return savePanel(exchange, guild, actor, 0);
        }

        long panelId = idOf(parts[2]);
        if (parts.length == 3) {
            return switch (method) {
                case "GET" -> panelDetail(guild, panelId);
                case "PUT" -> savePanel(exchange, guild, actor, panelId);
                case "DELETE" -> deletePanel(guild, actor, panelId);
                default -> throw new ApiError(405, Text.of(guild, "dashboard.method_not_allowed"));
            };
        }
        if (parts.length == 4 && parts[3].equals("post")) {
            requireMethod(method, "POST");
            return postPanel(exchange, guild, actor, panelId);
        }
        return null;
    }

    private static long idOf(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_id"));
        }
    }

    private static DataObject body(HttpExchange exchange) {
        try (var in = exchange.getRequestBody()) {
            return DataObject.fromJson(in);
        } catch (Exception e) {
            throw new ApiError(400, Text.en("dashboard.the_request_body_isn_t_valid"));
        }
    }

    // ---------- the server's roles and channels, for the editor's pickers ----------

    private DataObject structure(Guild guild) {
        RoleHolders holders = RoleHolders.of(guild);
        DataArray roles = DataArray.empty();
        for (Role role : guild.getRoles()) {
            if (role.isPublicRole()) continue;
            roles.add(DataObject.empty()
                    .put("id", role.getId())
                    .put("name", role.getName())
                    .put("color", role.getColorRaw() & 0xFFFFFF)
                    .put("managed", role.isManaged())
                    .put("position", role.getPosition())
                    .put("holders", holders.holders(role.getIdLong())));
        }

        DataArray categories = DataArray.empty();
        for (Category category : guild.getCategories()) {
            categories.add(DataObject.empty().put("id", category.getId()).put("name", category.getName()));
        }

        DataArray channels = DataArray.empty();
        guild.getTextChannels().forEach(channel -> channels.add(DataObject.empty()
                .put("id", channel.getId())
                .put("name", channel.getName())
                .put("category", channel.getParentCategory() == null ? null : channel.getParentCategory().getName())
                .put("canPost", channel.canTalk())));

        // announcement channels post like text channels
        guild.getNewsChannels().forEach(channel -> channels.add(DataObject.empty()
                .put("id", channel.getId())
                .put("name", channel.getName())
                .put("category", channel.getParentCategory() == null ? null : channel.getParentCategory().getName())
                .put("canPost", channel.canTalk())));

        // A forum can't be posted in directly: JonnyBot posts in one of its threads, so the picker offers each forum with its threads.
        DataArray forums = DataArray.empty();
        DataArray threads = DataArray.empty();
        for (ForumChannel forum : guild.getForumChannels()) {
            boolean forumCanPost = guild.getSelfMember().hasPermission(forum, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND_IN_THREADS);
            forums.add(DataObject.empty()
                    .put("id", forum.getId())
                    .put("name", forum.getName())
                    .put("category", forum.getParentCategory() == null ? null : forum.getParentCategory().getName())
                    .put("canPost", forumCanPost));

            java.util.Map<Long, ThreadChannel> all = new java.util.LinkedHashMap<>();
            forum.getThreadChannels().forEach(thread -> all.put(thread.getIdLong(), thread));
            for (ThreadChannel archived : archivedThreads(forum)) all.putIfAbsent(archived.getIdLong(), archived);
            all.values().forEach(thread -> threads.add(DataObject.empty()
                    .put("id", thread.getId())
                    .put("name", thread.getName())
                    .put("forumId", forum.getId())
                    .put("archived", thread.isArchived())
                    .put("canPost", forumCanPost && !thread.isLocked())));
        }

        return DataObject.empty().put("roles", roles).put("membersSeen", holders.seen()).put("categories", categories).put("channels", channels).put("forums", forums).put("threads", threads);
    }

    private static final int ARCHIVED_THREADS_PER_FORUM = 25;
    private final TtlCache archivedThreadCache = new TtlCache();

    /**
     * A forum's most recently archived threads. Forum posts are archived after a few days of quiet, so without these the list would
     * usually be empty; posting in an archived thread opens it again. One request per forum, remembered for a minute.
     */
    private List<ThreadChannel> archivedThreads(ForumChannel forum) {
        return archivedThreadCache.get("forum:" + forum.getId(), 60_000, () -> {
            try {
                return forum.retrieveArchivedPublicThreadChannels().limit(ARCHIVED_THREADS_PER_FORUM).complete();
            } catch (RuntimeException e) {
                return List.of(); // no permission to see them, or Discord is slow: the active threads still work
            }
        });
    }

    // ---------- settings ----------

    private DataObject saveSettings(HttpExchange exchange, Guild guild, Member actor) {
        Settings current = repository.getSettings(guild.getIdLong());
        Settings updated = TicketAdminJson.readSettings(current, body(exchange));

        List<String> problems = new ArrayList<>();
        if (updated.logChannelId() != null && guild.getChannelById(GuildMessageChannel.class, updated.logChannelId()) == null) problems.add("The log channel isn't a channel the bot can post in, in this server.");
        if (updated.closeDelaySeconds() < 0 || updated.closeDelaySeconds() > 300) problems.add("The close delay must be between 0 and 300 seconds.");
        if (updated.transcriptRetentionDays() != null && (updated.transcriptRetentionDays() < 1 || updated.transcriptRetentionDays() > 3650)) problems.add("Transcript retention must be between 1 and 3650 days.");
        if (!com.runeclans.bot.ticket.TicketInactivity.validDays(updated.autoCloseDays())) problems.add("Tickets can close themselves after between 1 and " + com.runeclans.bot.ticket.TicketInactivity.MAX_DAYS + " days of silence, or never.");
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_settings_can_t_be_saved_2"), problems);

        repository.saveSettings(updated);
        log.info("Dashboard: {} updated ticket settings", actor.getId());
        return TicketAdminJson.settingsJson(repository.getSettings(guild.getIdLong()));
    }

    // ---------- PvM Help ----------

    private DataObject helpRoute(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) {
        if (parts.length == 2 && parts[1].equals("settings")) {
            if (method.equals("GET")) return helpSettingsResponse(guild);
            requireMethod(method, "PUT");
            return saveHelpSettings(exchange, guild, actor);
        }
        if (parts.length == 2 && parts[1].equals("panels")) {
            requireMethod(method, "POST");
            return createHelpPanels(guild, actor);
        }
        if (parts.length == 3 && parts[1].equals("guidelines") && parts[2].equals("post")) {
            requireMethod(method, "POST");
            return postGuidelines(exchange, guild, actor);
        }
        return null;
    }

    /** The help settings, plus the PvM Help and CA Help panels that exist, so the page can link to them. */
    private DataObject helpSettingsResponse(Guild guild) {
        DataArray panels = DataArray.empty();
        for (Panel panel : repository.getPanels(guild.getIdLong())) {
            if (panel.helpKind() == com.runeclans.bot.ticket.TicketModels.HelpKind.NONE) continue;
            panels.add(DataObject.empty()
                    .put("id", Long.toString(panel.id()))
                    .put("name", panel.name())
                    .put("helpKind", panel.helpKind().name())
                    .put("categoryId", panel.categoryId() == null ? null : Long.toString(panel.categoryId()))
                    .put("postedChannelId", panel.postedChannelId() == null ? null : Long.toString(panel.postedChannelId())));
        }
        return TicketAdminJson.helpSettingsJson(repository.getHelpSettings(guild.getIdLong())).put("panels", panels);
    }

    /** Creates the standard PvM Help, CA Help and Informational panels where the server doesn't have them; ones already there are left as they are. */
    private DataObject createHelpPanels(Guild guild, Member actor) {
        HelpPanels.Result result = helpPanels.createMissing(guild.getIdLong());
        log.info("Dashboard: {} created {} help panel(s); {} already existed", actor.getId(), result.created().size(), result.existing().size());
        return helpSettingsResponse(guild)
                .put("createdPanels", DataArray.fromCollection(result.created().stream().map(Panel::name).toList()))
                .put("existingPanels", DataArray.fromCollection(result.existing().stream().map(Panel::name).toList()));
    }

    private DataObject saveHelpSettings(HttpExchange exchange, Guild guild, Member actor) {
        HelpSettings updated = TicketAdminJson.readHelpSettings(repository.getHelpSettings(guild.getIdLong()), body(exchange));

        List<String> problems = new ArrayList<>(HelpRules.validate(updated));
        if (updated.helperRoleId() != null && guild.getRoleById(updated.helperRoleId()) == null) problems.add("The PVM Helper role no longer exists in this server.");
        if (updated.helperPlusRoleId() != null && guild.getRoleById(updated.helperPlusRoleId()) == null) problems.add("The PVM Helper+ role no longer exists in this server.");
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_settings_can_t_be_saved_2"), problems);

        repository.saveHelpSettings(updated);
        onboarding.refreshPosted(guild); // new guidelines show up on the message members read
        log.info("Dashboard: {} updated the PvM Help settings", actor.getId());
        return helpSettingsResponse(guild);
    }

    private DataObject postGuidelines(HttpExchange exchange, Guild guild, Member actor) {
        Long channelId = TicketAdminJson.idOrNull(body(exchange), "channelId");
        if (channelId == null) throw new ApiError(400, Text.of(guild, "dashboard.choose_a_channel_to_post_in"));
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_text_channel"));
        if (!channel.canTalk()) throw new ApiError(400, Text.of(guild, "dashboard.jonnybot_can_t_post_in_it", channel.getName()));
        if (repository.getHelpSettings(guild.getIdLong()).helperRoleId() == null) throw new ApiError(400, Text.of(guild, "dashboard.choose_the_pvm_helper_role_first"));

        try {
            onboarding.post(guild, channel).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Posting the helper guidelines to {} failed", channelId, e);
            throw new ApiError(502, Text.of(guild, "dashboard.discord_wouldn_t_let_the_guidelines"));
        }
        log.info("Dashboard: {} posted the helper guidelines in {}", actor.getId(), channelId);
        return helpSettingsResponse(guild);
    }

    // ---------- what a new panel starts with ----------

    /** The saved starting values for new panels, or the built-in ones if none were saved yet. */
    private DataObject panelDefaults(Guild guild) {
        String stored = repository.getPanelDefaults(guild.getIdLong());
        if (stored == null) return TicketAdminJson.builtinDefaults();
        try {
            DataObject json = DataObject.fromJson(stored);
            // Anything saved before a setting existed reads as that setting's built-in value.
            DataObject merged = TicketAdminJson.builtinDefaults();
            for (String key : json.keys()) merged.put(key, json.get(key));
            return merged;
        } catch (RuntimeException e) {
            log.warn("The saved default panel settings for guild {} couldn't be read; using the built-in ones", guild.getId(), e);
            return TicketAdminJson.builtinDefaults();
        }
    }

    private DataObject savePanelDefaults(HttpExchange exchange, Guild guild, Member actor) {
        // Read it the way a panel is read, so the same limits and checks apply; the name and title are stand-ins that never get stored.
        DataObject body = body(exchange).put("name", "Defaults").put("title", "Defaults");
        PanelDefinition definition = TicketAdminJson.readPanel(guild.getIdLong(), 0, body);

        List<String> problems = new ArrayList<>(TicketRules.validatePanel(definition.panel(), List.of()));
        problems.addAll(guildProblems(guild, definition));
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_defaults_can_t_be_saved"), problems);

        repository.savePanelDefaults(guild.getIdLong(), TicketAdminJson.defaultsJson(definition).toString());
        log.info("Dashboard: {} updated the default settings for new ticket panels", actor.getId());
        return panelDefaults(guild);
    }

    // ---------- panels ----------

    private DataObject listPanels(Guild guild) {
        DataArray panels = DataArray.empty();
        for (Panel panel : repository.getPanels(guild.getIdLong())) {
            panels.add(TicketAdminJson.panelJson(panel)
                    .put("fieldCount", repository.getFields(panel.id()).size())
                    .put("openTickets", repository.listTickets(guild.getIdLong(), Status.OPEN, panel.id(), MAX_PAGE, 0).size()));
        }
        return DataObject.empty().put("panels", panels);
    }

    private PanelDefinition ownedDefinition(Guild guild, long panelId) {
        Panel panel = repository.getPanel(panelId);
        if (panel == null || panel.guildId() != guild.getIdLong()) throw new ApiError(404, Text.of(guild, "dashboard.that_panel_doesn_t_exist"));
        return repository.getDefinition(panelId);
    }

    private DataObject panelDetail(Guild guild, long panelId) {
        return TicketAdminJson.definitionJson(ownedDefinition(guild, panelId));
    }

    private DataObject savePanel(HttpExchange exchange, Guild guild, Member actor, long panelId) {
        if (panelId != 0) ownedDefinition(guild, panelId); // 404 for another server's or a missing panel
        PanelDefinition definition = TicketAdminJson.readPanel(guild.getIdLong(), panelId, body(exchange));

        List<String> problems = new ArrayList<>(TicketRules.validatePanel(definition.panel(), definition.fields()));
        problems.addAll(HelpRules.validatePanelHelp(definition.panel(), definition.fields()));
        problems.addAll(guildProblems(guild, definition));
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_panel_can_t_be_saved"), problems);

        long savedId;
        try {
            savedId = repository.saveDefinition(definition);
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.that_panel_can_t_be_saved"), List.of(e.getMessage()));
        }
        log.info("Dashboard: {} {} ticket panel {} (\"{}\")", actor.getId(), panelId == 0 ? "created" : "updated", savedId, definition.panel().name());

        refreshPostedMessage(guild, savedId);
        return TicketAdminJson.definitionJson(repository.getDefinition(savedId));
    }

    /** Roles and the category must exist in this server — the dashboard's pickers come from it, but a stale page may not match. */
    private static List<String> guildProblems(Guild guild, PanelDefinition definition) {
        List<String> problems = new ArrayList<>();
        Panel panel = definition.panel();

        if (panel.categoryId() != null && guild.getCategoryById(panel.categoryId()) == null) problems.add("The chosen category no longer exists.");

        Set<Long> roleIds = new HashSet<>();
        roleIds.addAll(definition.roles().helperRoleIds());
        roleIds.addAll(definition.roles().staffRoleIds());
        roleIds.addAll(definition.roles().closeRoleIds());
        if (panel.defaultPingRoleId() != null) roleIds.add(panel.defaultPingRoleId());
        if (panel.defaultEscalateRoleId() != null) roleIds.add(panel.defaultEscalateRoleId());
        for (Field field : definition.fields()) {
            for (Option option : field.options()) {
                if (option.pingRoleId() != null) roleIds.add(option.pingRoleId());
                if (option.escalateRoleId() != null) roleIds.add(option.escalateRoleId());
            }
        }
        for (long roleId : roleIds) {
            if (guild.getRoleById(roleId) == null) {
                problems.add("A chosen role (" + roleId + ") no longer exists in this server.");
                break;
            }
        }
        return problems;
    }

    /** If the panel is already posted, bring that message up to date with the saved wording. Best effort — the save itself has succeeded. */
    private void refreshPostedMessage(Guild guild, long panelId) {
        Panel panel = repository.getPanel(panelId);
        if (panel == null || panel.postedChannelId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, panel.postedChannelId());
        if (channel == null) return;
        service.postPanel(panel, channel).exceptionally(error -> {
            log.warn("Could not refresh the posted message for ticket panel {}", panelId, error);
            return null;
        });
    }

    private DataObject deletePanel(Guild guild, Member actor, long panelId) {
        Panel panel = ownedDefinition(guild, panelId).panel();
        if (!repository.listTickets(guild.getIdLong(), Status.OPEN, panelId, 1, 0).isEmpty()) {
            throw new ApiError(409, Text.of(guild, "dashboard.this_panel_still_has_open_tickets"));
        }
        repository.deletePanel(guild.getIdLong(), panelId);
        log.info("Dashboard: {} deleted ticket panel {} (\"{}\")", actor.getId(), panelId, panel.name());

        // Take the now-dead button down too (best effort).
        if (panel.postedChannelId() != null && panel.postedMessageId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, panel.postedChannelId());
            if (channel != null) channel.deleteMessageById(panel.postedMessageId()).queue(null, error -> {});
        }
        return DataObject.empty().put("deleted", true);
    }

    private DataObject postPanel(HttpExchange exchange, Guild guild, Member actor, long panelId) {
        Panel panel = ownedDefinition(guild, panelId).panel();
        Long channelId = TicketAdminJson.idOrNull(body(exchange), "channelId");
        if (channelId == null) throw new ApiError(400, Text.of(guild, "dashboard.choose_a_channel_to_post_in"));

        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_text_channel"));
        if (!channel.canTalk()) throw new ApiError(400, Text.of(guild, "dashboard.jonnybot_can_t_post_in_it", channel.getName()));

        try {
            service.postPanel(panel, channel).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Posting ticket panel {} to {} failed", panelId, channelId, e);
            throw new ApiError(502, Text.of(guild, "dashboard.discord_wouldn_t_let_the_panel"));
        }
        log.info("Dashboard: {} posted ticket panel {} in {}", actor.getId(), panelId, channelId);
        return TicketAdminJson.panelJson(repository.getPanel(panelId));
    }

    // ---------- tickets ----------

    private DataObject stats(Guild guild) {
        var stats = repository.stats(guild.getIdLong());

        DataArray byPanel = DataArray.empty();
        stats.byPanel().forEach(p -> byPanel.add(DataObject.empty().put("panel", p.panel()).put("total", p.total()).put("open", p.open())));
        DataArray byWeek = DataArray.empty();
        stats.byWeek().forEach(w -> byWeek.add(DataObject.empty().put("weekStart", w.weekStart().toString()).put("opened", w.opened())));
        DataArray helpers = DataArray.empty();
        stats.topHelpers().forEach(h -> helpers.add(DataObject.empty().put("id", Long.toString(h.userId())).put("name", nameOf(guild, h.userId())).put("tickets", h.tickets())));

        return DataObject.empty()
                .put("open", stats.open()).put("closed", stats.closed()).put("escalated", stats.escalated()).put("flagged", stats.flagged())
                .put("avgHoursToClose", stats.avgHoursToClose()).put("avgMinutesToFirstHelper", stats.avgMinutesToFirstHelper())
                .put("byPanel", byPanel).put("byWeek", byWeek).put("topHelpers", helpers);
    }

    private DataObject listTickets(HttpExchange exchange, Guild guild) {
        String statusRaw = query(exchange, "status");
        Status status = null;
        if (statusRaw != null && !statusRaw.isBlank() && !statusRaw.equalsIgnoreCase("ALL")) {
            try {
                status = Status.valueOf(statusRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.status_must_be_open_closed_or"));
            }
        }
        String panelRaw = query(exchange, "panel");
        Long panelId = panelRaw == null || panelRaw.isBlank() ? null : idOf(panelRaw);
        int limit = Math.max(1, Math.min(MAX_PAGE, numberOr(query(exchange, "limit"), 25)));
        int offset = Math.max(0, numberOr(query(exchange, "offset"), 0));

        // One extra row tells us whether there's another page without a separate count query.
        List<Ticket> rows = repository.listTickets(guild.getIdLong(), status, panelId, limit + 1, offset);
        boolean hasMore = rows.size() > limit;

        DataArray tickets = DataArray.empty();
        for (Ticket ticket : rows.subList(0, Math.min(limit, rows.size()))) {
            tickets.add(TicketAdminJson.ticketJson(ticket, panelNameOf(ticket), cachedNameOf(guild, ticket.requesterId())));
        }
        return DataObject.empty().put("tickets", tickets).put("hasMore", hasMore);
    }

    private DataObject ticketDetail(Guild guild, String rawId) {
        Ticket ticket = repository.getTicket(idOf(rawId));
        if (ticket == null || ticket.guildId() != guild.getIdLong()) throw new ApiError(404, Text.of(guild, "dashboard.that_ticket_doesn_t_exist"));

        DataArray helpers = DataArray.empty();
        for (long helperId : repository.getHelpers(ticket.id())) {
            helpers.add(DataObject.empty().put("id", Long.toString(helperId)).put("name", nameOf(guild, helperId)));
        }

        Transcript transcript = repository.getTranscript(ticket.id());
        DataObject json = TicketAdminJson.ticketJson(ticket, panelNameOf(ticket), nameOf(guild, ticket.requesterId()))
                .put("helpers", helpers)
                .put("closedByName", ticket.closedBy() == null ? null : nameOf(guild, ticket.closedBy()))
                .put("transcript", transcript == null ? null : transcript.content())
                .put("transcriptMessageCount", transcript == null ? null : transcript.messageCount());
        return json;
    }

    private String panelNameOf(Ticket ticket) {
        if (ticket.panelId() == null) return null;
        Panel panel = repository.getPanel(ticket.panelId());
        return panel == null ? null : panel.name();
    }

    /** Display name from the member cache only — fine for a list, no network call per row. */
    private static String cachedNameOf(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        if (member != null) return member.getEffectiveName();
        var user = Shards.user(guild.getJDA(), userId);
        return user == null ? null : user.getName();
    }

    /** Display name, asking Discord if the cache doesn't know — for a single ticket's page. */
    private static String nameOf(Guild guild, long userId) {
        String cached = cachedNameOf(guild, userId);
        if (cached != null) return cached;
        return DiscordLookups.userName(guild.getJDA(), userId); // null when Discord is slow: the page shows the id
    }

    private static String query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int numberOr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
