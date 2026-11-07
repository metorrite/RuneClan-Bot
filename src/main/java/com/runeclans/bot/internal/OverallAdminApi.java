package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.access.OverallAdminRepository;
import com.runeclans.bot.access.OverallAdmins;
import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.premium.PremiumAccessRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The overall RuneClans admin panel (N5, runeclans.com/admin), under {@code /internal/admin/overall/}. It names no server: an overall admin
 * need not be in the bot's home server or any other, so {@link InternalApiServer} sends these here before looking a server up. The asker is
 * {@code X-Actor-Id} (from the website's verified session, trusted like the {@code guilds} list once the internal secret was checked), and
 * every route but {@code whoami} needs an overall admin ({@link OverallAdmins}); every write is rate limited and written to the home server's
 * audit log.
 * <ul>
 *   <li>{@code GET overall/whoami}: 200 always, {@code {overallAdmin, builtIn}}</li>
 *   <li>{@code GET overall/admins}: {@code {builtIn: [{userId}], admins: [{userId, note, addedBy, addedAt}], canManage}}</li>
 *   <li>{@code PUT overall/admins/{userId}} {@code {note?}} and {@code DELETE overall/admins/{userId}}: built-in admins only; a built-in admin
 *   can't be added or removed; answers the list</li>
 *   <li>{@code GET overall/premium}: {@code {grants: [{id, kind, targetId, startsAt, endsAt, note, grantedBy, grantedAt, active}], enforced: false}};
 *   {@code POST overall/premium} {@code {kind: "site"|"user", targetId, startsAt?, endsAt?, note?}} (dates {@code YYYY-MM-DD}, UTC) and
 *   {@code DELETE overall/premium/{id}}: answer the list. Structure only: nothing reads these grants ({@link PremiumAccessRepository})</li>
 *   <li>{@code overall/owner-sites...}: every clan site ({@link SiteOwnerAdminApi}'s routes) and {@code overall/beta-testers...}: the beta
 *   testers ({@link SiteBuilderBetaAdminApi}'s), for every overall admin</li>
 *   <li>{@code GET overall/health}: the bot's health, and {@code GET overall/runesync/diagnostics/{id}} (the bot's owner only) ({@link OverallOpsRoutes})</li>
 * </ul>
 */
@BService
public class OverallAdminApi {
    private static final Logger log = LoggerFactory.getLogger(OverallAdminApi.class);
    static final String PREFIX = "/internal/admin/overall";
    private static final String ACTOR_HEADER = "X-Actor-Id";
    static final int MAX_NOTE = 120;
    static final int MAX_ADMINS = 100;
    static final int MAX_GRANTS = 1000;

    private final OverallAdmins admins;
    private final BotOwners owners;
    private final PremiumAccessRepository premium;
    private final SiteOwnerAdminApi sites;
    private final SiteBuilderBetaAdminApi beta;
    private final OverallOpsRoutes ops;
    private final SiteRoleGuard siteRoles;
    private final AdminToolsStore auditStore;
    private final BotConfig config;
    private final RateLimiter writes = new RateLimiter(20, 1_000);

    public OverallAdminApi(OverallAdmins admins, BotOwners owners, PremiumAccessRepository premium, SiteOwnerAdminApi sites, SiteBuilderBetaAdminApi beta,
                           OverallOpsRoutes ops, SiteRoleGuard siteRoles, AdminToolsStore auditStore, BotConfig config) {
        this.admins = admins;
        this.owners = owners;
        this.premium = premium;
        this.sites = sites;
        this.beta = beta;
        this.ops = ops;
        this.siteRoles = siteRoles;
        this.auditStore = auditStore;
        this.config = config;
    }

    static boolean ours(String path) {
        return path.equals(PREFIX) || path.startsWith(PREFIX + "/");
    }

    /** One request; the internal secret was already checked. */
    public void handle(HttpExchange exchange, JDA jda) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String route = path.length() > PREFIX.length() ? path.substring(PREFIX.length() + 1) : "";
            if (route.endsWith("/")) route = route.substring(0, route.length() - 1);
            String[] parts = route.isEmpty() ? new String[0] : route.split("/");
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            long actor = actorId(exchange);
            Supplier<DataObject> body = () -> body(exchange);

            if (parts.length == 1 && parts[0].equals("whoami")) {
                AdminRouteExtension.requireMethod(method, "GET");
                InternalApiServer.sendJson(exchange, 200, DataObject.empty().put("overallAdmin", admins.isOverallAdmin(jda, actor))
                        .put("builtIn", admins.isBuiltIn(jda, actor)));
                return;
            }
            if (!admins.isOverallAdmin(jda, actor)) throw new ApiError(403, "Only RuneClan Admins can use this.");
            if (!method.equals("GET") && !writes.tryAcquire(Long.toString(actor))) throw new ApiError(429, "You're doing that too fast. Wait a moment and try again.");
            DataObject result = route(jda, actor, method, parts, body);
            if (result == null) {
                InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
                return;
            }
            Long home = config.getGuildId();
            if (!method.equals("GET") && home != null) auditStore.audit(home, Long.toString(actor), "RuneClan Admin", method, "overall/" + route, 200);
            InternalApiServer.sendJson(exchange, 200, result);
        } catch (ApiError e) {
            DataObject out = DataObject.empty().put("error", e.getMessage());
            if (!e.problems.isEmpty()) out.put("problems", DataArray.fromCollection(e.problems));
            InternalApiServer.sendJson(exchange, e.status, out);
        } catch (TicketAdminJson.BadRequest e) {
            InternalApiServer.sendJson(exchange, 400, DataObject.empty().put("error", e.getMessage()));
        } catch (com.runeclans.bot.discord.DiscordLookups.Slow e) {
            InternalApiServer.sendSlow(exchange);
        } catch (Exception e) {
            log.error("Overall admin request {} {} failed", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e);
            InternalApiServer.sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /** {@code null} for a path that doesn't exist. The asker is an overall admin. */
    DataObject route(JDA jda, long actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 0) return null;
        String[] rest = Arrays.copyOfRange(parts, 1, parts.length);
        return switch (parts[0]) {
            case "admins" -> adminsRoute(jda, actor, method, rest, body);
            case "premium" -> premiumRoute(actor, method, rest, body);
            case SiteOwnerAdminApi.ROOT -> {
                Guild home = home(jda);
                yield sites.routes(home, actor, method, parts, body);
            }
            case SiteBuilderBetaAdminApi.TESTERS -> beta.testersRoute(actor, method, parts, body);
            case OverallOpsRoutes.HEALTH, OverallOpsRoutes.RUNESYNC, OverallOpsRoutes.PREMIUM_GUILDS, OverallOpsRoutes.RSNLINK -> ops.route(jda, actor, home(jda), method, parts, body);
            default -> null;
        };
    }

    // ---------- overall admins ----------

    private DataObject adminsRoute(JDA jda, long actor, String method, String[] rest, Supplier<DataObject> body) {
        if (rest.length == 0) {
            AdminRouteExtension.requireMethod(method, "GET");
            return adminsJson(jda, actor);
        }
        if (rest.length != 1) return null;
        if (!admins.isBuiltIn(jda, actor)) throw new ApiError(403, "Only the built-in RuneClan Admin can add or remove admins.");
        long userId = userId(rest[0]);
        if (admins.isBuiltIn(jda, userId)) throw new ApiError(400, "That person is a built-in admin: they are always an admin and can't be added or removed.");
        if (method.equals("PUT")) {
            String note = note(body.get().getString("note", ""));
            boolean listed = admins.list().stream().anyMatch(e -> e.userId() == userId);
            if (!listed && admins.list().size() >= MAX_ADMINS) throw new ApiError(400, "The admin list is full. Remove someone first.");
            admins.put(userId, note, actor);
        } else {
            AdminRouteExtension.requireMethod(method, "DELETE");
            if (!admins.remove(userId, actor)) throw new ApiError(404, "That person isn't a RuneClan Admin.");
        }
        // their site roles change with this, everywhere
        siteRoles.forgetAll();
        return adminsJson(jda, actor);
    }

    DataObject adminsJson(JDA jda, long actor) {
        DataArray builtIn = DataArray.empty();
        for (long id : new TreeSet<>(owners.known(jda))) builtIn.add(DataObject.empty().put("userId", Long.toString(id)));
        DataArray added = DataArray.empty();
        for (OverallAdminRepository.Entry e : admins.list()) {
            added.add(DataObject.empty().put("userId", Long.toString(e.userId())).put("note", e.note()).put("addedBy", Long.toString(e.addedBy()))
                    .put("addedAt", e.addedAt().toString()));
        }
        return DataObject.empty().put("builtIn", builtIn).put("admins", added).put("canManage", admins.isBuiltIn(jda, actor));
    }

    // ---------- premium access (structure only) ----------

    private DataObject premiumRoute(long actor, String method, String[] rest, Supplier<DataObject> body) {
        if (rest.length == 0) {
            if (method.equals("GET")) return premiumJson();
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            PremiumAccessRepository.Kind kind = PremiumAccessRepository.Kind.ofKey(b.getString("kind", ""))
                    .orElseThrow(() -> new ApiError(400, "Choose whether the grant is for a clan site or a person."));
            long target = kind == PremiumAccessRepository.Kind.SITE ? serverId(b.getString("targetId", "")) : userId(b.getString("targetId", ""));
            Instant starts = date(b, "startsAt", Instant.now());
            Instant ends = date(b, "endsAt", null);
            if (ends != null && !ends.isAfter(starts)) throw new ApiError(400, "The end date must be after the start date.");
            if (premium.all().size() >= MAX_GRANTS) throw new ApiError(400, "The premium list is full. Remove an old grant first.");
            premium.add(kind, target, starts, ends, note(b.getString("note", "")), actor);
            log.info("Premium access: {} granted {} {} (not enforced)", actor, kind.key(), target);
            return premiumJson();
        }
        if (rest.length != 1) return null;
        AdminRouteExtension.requireMethod(method, "DELETE");
        long id;
        try {
            id = Long.parseLong(rest[0]);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That isn't a grant.");
        }
        if (!premium.delete(id)) throw new ApiError(404, "That grant is already gone.");
        log.info("Premium access: {} removed grant {}", actor, id);
        return premiumJson();
    }

    DataObject premiumJson() {
        Instant now = Instant.now();
        DataArray grants = DataArray.empty();
        for (PremiumAccessRepository.Grant g : premium.all()) {
            grants.add(DataObject.empty().put("id", Long.toString(g.id())).put("kind", g.kind().key()).put("targetId", Long.toString(g.targetId()))
                    .put("startsAt", g.startsAt().toString()).put("endsAt", g.endsAt() == null ? null : g.endsAt().toString()).put("note", g.note())
                    .put("grantedBy", Long.toString(g.grantedBy())).put("grantedAt", g.grantedAt().toString()).put("active", g.active(now)));
        }
        return DataObject.empty().put("grants", grants).put("enforced", false);
    }

    // ---------- helpers ----------

    private Guild home(JDA jda) {
        Long id = config.getGuildId();
        Guild home = id == null ? null : Shards.guild(jda, id);
        if (home == null) throw new ApiError(503, "The bot's home server isn't available yet. Try again in a minute.");
        return home;
    }

    private static Instant date(DataObject b, String key, Instant fallback) {
        String raw = b.isNull(key) ? "" : b.getString(key, "").strip();
        if (raw.isEmpty()) return fallback;
        try {
            return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new ApiError(400, "Give dates as YYYY-MM-DD.");
        }
    }

    private static String note(String raw) {
        String note = raw == null ? "" : raw.strip();
        if (note.length() > MAX_NOTE) throw new ApiError(400, "Keep the note under " + MAX_NOTE + " characters.");
        return note;
    }

    static long userId(String raw) {
        long id = SiteOwnerAdminApi.parseId(raw == null ? null : raw.strip());
        if (id == 0) throw new ApiError(400, "A Discord user ID is 17 to 20 digits.");
        return id;
    }

    private static long serverId(String raw) {
        long id = SiteOwnerAdminApi.parseId(raw == null ? null : raw.strip());
        if (id == 0) throw new ApiError(400, "A Discord server ID is 17 to 20 digits.");
        return id;
    }

    private static long actorId(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders().getFirst(ACTOR_HEADER);
        return SiteOwnerAdminApi.parseId(raw == null ? null : raw.strip());
    }

    private static DataObject body(HttpExchange exchange) {
        try (var in = exchange.getRequestBody()) {
            return DataObject.fromJson(in);
        } catch (Exception e) {
            throw new ApiError(400, "The request body isn't valid JSON.");
        }
    }
}
