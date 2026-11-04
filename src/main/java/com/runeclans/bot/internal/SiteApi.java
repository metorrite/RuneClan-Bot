package com.runeclans.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.clansite.ClanSitePluginFeed;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.internal.SiteStatsRepository.Gain;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.commands.signup.SignupSession;
import com.runeclans.bot.commands.signup.SubmissionField;
import com.runeclans.bot.internal.SiteStatsRepository.MemberRow;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.member.MemberProfileRepository.Profile;
import com.runeclans.bot.runescape.ClanPointsRepository;
import com.runeclans.bot.runescape.ClanPointsRepository.RankConfigRow;
import com.runeclans.bot.runescape.PlayerActivity;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository.SkillHistoryPoint;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.runeclans.bot.runescape.RuneScapeSkillCatalog;
import com.runeclans.bot.runescape.RuneScapeXpTable;
import com.runeclans.bot.runescape.SkillValue;
import com.runeclans.bot.runescape.WeeklyDigestRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import com.runeclans.bot.config.PrivilegedIntents;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToLongFunction;

/**
 * The public-facing half of the internal API, under {@code /internal/site/}: clan roster, leaderboards,
 * charts, member profiles, the live "who's online" list and Discord events — everything the website shows to
 * anyone, logged in or not. {@link InternalApiServer} has already checked the shared secret and found the
 * guild; these routes are GET-only and need no acting user, because none of this is private. The one answer that depends on who asks is a
 * member's recap ({@code recap?scope=member}, and {@code member/recap} on {@link #tenant}): private until that member has opened it
 * ({@link RecapGate}, which reads the viewer from the {@value RecapGate#VIEWER_HEADER} header).
 * <p>
 * Discord identities: a member's RuneScape name, rank, stats and adventure log are what the clan pages show, and
 * whether that name is linked to a Discord account is reduced to a "verified" flag there. The one exception is
 * the "who's online" list ({@link #online}): for each online member it sends their Discord user id, display name,
 * avatar, status, colour and top role, and, for a member whose linked RuneScape name is a current clan member and
 * who hasn't asked not to be linked ({@code hide_discord_link}, or the server's member privacy default), that RuneScape name too. Members on the server's
 * hidden-from-online list ({@code site_online_hidden}) are counted but not listed.
 */
@BService
public class SiteApi {
    private static final Logger log = LoggerFactory.getLogger(SiteApi.class);
    private static final String PREFIX = "/internal/site/";
    private static final int HISTORY_DAYS = 90;
    private static final int CHART_WEEKS = 12;

    /** When an activity really happened, as an ISO instant (RuneMetrics writes UK local time, see RuneMetricsDates), or null if its date can't be read. */
    static String occurredAt(String rawDate) {
        java.time.Instant at = com.runeclans.bot.runescape.RuneMetricsDates.toInstant(rawDate);
        return at == null ? null : at.toString();
    }

    private final SiteStatsRepository stats;
    private final PlayerLinkRepository links;
    private final ClanPointsRepository points;
    private final GuildSettingsService settings;
    private final WeeklyDigestRepository rosterEvents;
    private final MemberProfileRepository profiles;
    private final SiteNewsService news;
    private final SignupService signupService;
    private final SiteCache cache;
    private final RecapService recap;
    private final CommunitySettings community;
    private final BossStatsService bossStats;
    private final SiteDiscordService discord;
    private final RecapGate recapGate;
    private final ClanSitePluginFeed pluginFeed;

    public SiteApi(SiteStatsRepository stats, PlayerLinkRepository links, ClanPointsRepository points, GuildSettingsService settings, WeeklyDigestRepository rosterEvents,
                   MemberProfileRepository profiles, SiteNewsService news, SignupService signupService, SiteCache cache, RecapService recap, CommunitySettings community, BossStatsService bossStats,
                   SiteDiscordService discord, ClanSitePluginFeed pluginFeed) {
        this.discord = discord;
        this.pluginFeed = pluginFeed;
        this.stats = stats;
        this.links = links;
        this.points = points;
        this.settings = settings;
        this.rosterEvents = rosterEvents;
        this.profiles = profiles;
        this.news = news;
        this.signupService = signupService;
        this.cache = cache;
        this.recap = recap;
        this.community = community;
        this.bossStats = bossStats;
        this.recapGate = new RecapGate(profiles);
    }

    public void handle(HttpExchange exchange, Guild guild) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                InternalApiServer.sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String route = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
            if (route.endsWith("/")) route = route.substring(0, route.length() - 1);

            // Every answer is cached briefly (and loaded once even if many requests arrive together) — the website
            // already caches too, but this protects the bot's database from several instances or a cold start.
            String cacheKey = route + "?" + exchange.getRequestURI().getRawQuery();
            final String finalRoute = route;
            DataObject result = cache.get(cacheKey, ttlMillis(route), () -> answer(guild, finalRoute, exchange));
            // A member's recap is private until they've opened it (RecapGate): decided per viewer here, after the cache everyone shares.
            if (route.equals("recap") && "member".equals(query(exchange, "scope"))) {
                result = recapGate.answer(guild.getIdLong(), false, result, RecapGate.viewer(exchange));
            }

            if (result == null) {
                InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
            } else {
                InternalApiServer.sendJson(exchange, 200, result);
            }
        } catch (com.runeclans.bot.runescape.stats.StatsUnavailableException e) {
            InternalApiServer.sendJson(exchange, 503, DataObject.empty().put("error", "Stats aren't available right now. Try again in a minute."));
        } catch (Exception e) {
            log.error("Site API request {} failed", exchange.getRequestURI().getPath(), e);
            InternalApiServer.sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /** The home server's answer for one {@code /internal/site/} route, uncached; null for a route that doesn't exist. */
    DataObject answer(Guild guild, String route, HttpExchange exchange) {
        long id = guild.getIdLong();
        return switch (route) {
            case "online" -> online(guild);
            case "events" -> events(guild);
            case "members" -> members(id);
            case "overview" -> overview(id);
            case "member" -> member(id, query(exchange, "rsn"));
            case "member/skill" -> memberSkill(id, query(exchange, "rsn"), query(exchange, "skill"));
            case "leaderboard" -> leaderboard(id, query(exchange, "month"), query(exchange, "period"));
            case "coffer" -> coffer(id);
            case "feed" -> feed(id, query(exchange, "limit"), query(exchange, "kind"));
            case "records" -> records(id);
            case "citadel-grid" -> citadelGrid(id, query(exchange, "weeks"));
            case "history" -> history(id);
            case "polls" -> polls(id);
            case "signups" -> signups(id);
            case "news" -> DataObject.empty().put("posts", news.latest(guild, 14));
            case "recap" -> "member".equals(query(exchange, "scope")) && visibleMember(id, query(exchange, "rsn")) == null ? null
                    : recap.build(id, query(exchange, "scope"), query(exchange, "rsn"), query(exchange, "period"));
            case "pvm" -> pvm(id);
            case "drops" -> drops(id);
            case "options" -> DataObject.empty().put("navEventBubble", community.navEventBubble(id));
            case "bosses" -> bossStats.bosses(id, query(exchange, "period"));
            case "boss" -> bossStats.boss(id, query(exchange, "boss"), query(exchange, "period"));
            case "item" -> bossStats.item(id, query(exchange, "item"), query(exchange, "period"), query(exchange, "boss"));
            case "drop-log" -> bossStats.drops(id, query(exchange, "period"), query(exchange, "bosses"));
            case "me" -> me(guild, query(exchange, "userId"));
            default -> null;
        };
    }

    /**
     * The answers only the home server's own site has, which {@link #TENANT_ROUTES} lack (T-519): the news channel, the site options, the
     * signed-in member's link state ("me", by the {@code userId} the website takes from its session), the live Discord list and Discord's
     * events (the home site always shows them; a clan site only when its settings say so).
     */
    public static final java.util.Set<String> HOME_ROUTES = java.util.Set.of("news", "options", "me", "online", "events");

    /**
     * The home server's site read by server id (T-519, {@link GuildSiteRoutes}): {@link #HOME_ROUTES} answer exactly as {@code /internal/site/}
     * does, every other route is the clan-site answer ({@link #tenant}), with its privacy rules. Null for any other route.
     */
    public DataObject home(Guild guild, String route, HttpExchange exchange) {
        if (!HOME_ROUTES.contains(route)) return tenant(guild, route, exchange);
        String cacheKey = route + "?" + exchange.getRequestURI().getRawQuery() + "#g" + guild.getIdLong();
        return cache.get(cacheKey, ttlMillis(route), () -> answer(guild, route, exchange));
    }

    /**
     * The routes a hosted clan website (website builder) may read: clan-level answers, never the live Discord list, the news channel (T-365
     * has its own module for those) or "me" ({@link DirectoryClanSiteMemberRoutes}). The per-member answers ({@code member}, {@code member/recap},
     * {@code member/skill}) are only for a current member who hasn't hidden themselves from the rankings ({@link #visibleMember}); a member's
     * recap also waits for that member to open it ({@link RecapGate}).
     */
    public static final java.util.Set<String> TENANT_ROUTES = java.util.Set.of("members", "overview", "leaderboard", "recap", "feed", "records", "citadel-grid",
            "history", "coffer", "polls", "signups", "events", "pvm", "drops", "bosses", "boss", "item", "drop-log", "member/skill", "notifications",
            "member", "member/recap");

    /**
     * One answer for a hosted clan website, for any server (the clan site's own routes above are for the home server). Only
     * {@link #TENANT_ROUTES}: {@code recap} is the clan's, {@code member/recap} one member's, and that one is private until its member has
     * opened it ({@link RecapGate}, by the {@value RecapGate#VIEWER_HEADER} header, decided after the cache). Cached per server: the key ends
     * in {@code #g<id>}, so the home server's keys are unchanged and {@link SiteCache#invalidate} by route still clears these.
     * <p>
     * Privacy (builder v3, section 11): the same rules as the home server's routes, a little stricter. A member who hid themselves from the
     * rankings is left out of every list of names here (rankings, records, the timeline, donors, signup names), and one who hid their adventure
     * log out of every activity line. The roster itself ({@code members}) still lists everyone on the clan's in-game list, as the home site does:
     * that list is public in the game. No Discord user ids, link rows or "me" data are ever in these answers.
     */
    public DataObject tenant(Guild guild, String route, HttpExchange exchange) {
        return tenant(guild.getIdLong(), guild, route, exchange);
    }

    /**
     * The routes of {@link #TENANT_ROUTES} that need the bot in the server: Discord's scheduled events, and the polls, signup sheets and coffer
     * members post through the bot's commands. A site whose server the bot isn't in (a bot-less site) gets {@link #needsBot()} for these.
     */
    public static final java.util.Set<String> DISCORD_ROUTES = java.util.Set.of("events", "polls", "signups", "coffer");

    /** What a Discord-only route answers for a bot-less site, so the widget can say the bot is needed instead of looking empty. */
    public static DataObject needsBot() {
        return DataObject.empty().put("needsBot", true);
    }

    /**
     * {@link #tenant(Guild, String, HttpExchange)} by server id, for a site whose server the bot may not be in ({@code guild} null): the clan's
     * stats are stored by server id, so every route but {@link #DISCORD_ROUTES} answers the same; those answer {@link #needsBot()}, and the
     * bell ({@code notifications}) has only the activity lines.
     */
    public DataObject tenant(long guildId, Guild guild, String route, HttpExchange exchange) {
        if (!TENANT_ROUTES.contains(route)) return null;
        if (guild == null && DISCORD_ROUTES.contains(route)) return needsBot();
        String cacheKey = route + "?" + exchange.getRequestURI().getRawQuery() + "#g" + guildId;
        DataObject answer = cache.get(cacheKey, ttlMillis(route), () -> switch (route) {
            case "members" -> members(guildId);
            case "overview" -> overview(guildId);
            case "leaderboard" -> leaderboard(guildId, query(exchange, "month"), query(exchange, "period"), true);
            case "recap" -> tenantRecap(guildId, query(exchange, "period"));
            case "feed" -> feed(guildId, query(exchange, "limit"), query(exchange, "kind"));
            case "records" -> records(guildId, true);
            case "citadel-grid" -> citadelGrid(guildId, query(exchange, "weeks"));
            case "history" -> history(guildId, true);
            case "coffer" -> coffer(guildId, true);
            case "polls" -> polls(guildId);
            case "signups" -> signups(guildId, true);
            case "events" -> discord.eventsShown(guildId) ? events(guild) : DataObject.empty().put("events", DataArray.empty());
            case "pvm" -> pvm(guildId);
            case "drops" -> drops(guildId);
            case "bosses" -> bossStats.bosses(guildId, query(exchange, "period"));
            case "boss" -> bossStats.boss(guildId, query(exchange, "boss"), query(exchange, "period"));
            case "item" -> bossStats.item(guildId, query(exchange, "item"), query(exchange, "period"), query(exchange, "boss"));
            case "drop-log" -> bossStats.drops(guildId, query(exchange, "period"), query(exchange, "bosses"));
            case "member/skill" -> tenantMemberSkill(guildId, query(exchange, "rsn"), query(exchange, "skill"));
            case "member" -> visibleMember(guildId, query(exchange, "rsn")) == null ? null : member(guildId, query(exchange, "rsn"));
            case "member/recap" -> visibleMember(guildId, query(exchange, "rsn")) == null ? null : recap.build(guildId, "member", query(exchange, "rsn"), query(exchange, "period"));
            case "notifications" -> guild == null ? notifications(guildId, null).put("needsBot", true) : notifications(guildId, guild);
            default -> null;
        });
        // The cache is shared by everyone; who may see a member's recap is decided per viewer, after it (a bot-less site has no links).
        return route.equals("member/recap") ? recapGate.answer(guildId, guild == null, answer, RecapGate.viewer(exchange)) : answer;
    }

    /** {@link MemberProfileRepository#hidesFromRankingsAnywhere}: hidden from the rankings on any server, current member or not. */
    boolean hidesFromRankingsAnywhere(String rsn) {
        return rsn != null && !rsn.isBlank() && profiles.hidesFromRankingsAnywhere(rsn);
    }

    /**
     * The roster row for {@code rsn} when a clan site may show that member on their own (member page, member recap, skill series, T-369):
     * a current member of this server's clan who hasn't hidden themselves from the rankings. {@code null} otherwise, and the route answers 404,
     * so a hidden member's page looks the same as a name that isn't in the clan.
     */
    MemberRow visibleMember(long guildId, String rsn) {
        if (rsn == null || rsn.isBlank()) return null;
        MemberRow row = stats.members(guildId).stream().filter(m -> m.rsn().equalsIgnoreCase(rsn.trim())).findFirst().orElse(null);
        return row == null || !shown(profiles.hiddenRsns(guildId, true), row.rsn()) ? null : row;
    }

    /** The clan's recap for a clan site: the list of new members' names leaves out members hidden from the rankings (the count stays whole). */
    DataObject tenantRecap(long guildId, String period) {
        DataObject answer = recap.build(guildId, "clan", null, period);
        if (answer == null || !answer.hasKey("roster")) return answer;
        Set<String> hiddenBoards = profiles.hiddenRsns(guildId, true);
        DataObject roster = answer.getObject("roster");
        DataArray names = DataArray.empty();
        DataArray all = roster.getArray("newMembers");
        for (int i = 0; i < all.length(); i++) {
            if (shown(hiddenBoards, all.getString(i))) names.add(all.getString(i));
        }
        roster.put("newMembers", names);
        return answer;
    }

    /** One skill's XP series for a clan site: only for a current member of that clan who hasn't hidden themselves from the rankings. */
    DataObject tenantMemberSkill(long guildId, String rsn, String skillRaw) {
        MemberRow row = visibleMember(guildId, rsn);
        return row == null ? null : memberSkill(guildId, row.rsn(), skillRaw);
    }

    private static final int NOTIFICATION_ITEMS = 5;
    /** Activity lines for the bell: more than the other kinds, since the website folds repeats into one line ("4 new drops"). */
    static final int NOTIFICATION_FEED_ITEMS = 20;
    private static final Duration EVENTS_SOON = Duration.ofDays(7);

    /**
     * What a clan site's bell shows (builder v3, section 7): Discord events starting within a week or running now, open polls, open signup
     * sheets and the newest few activity lines. Each list is short and newest (or soonest) first; built from the answers above, so it follows
     * their privacy rules.
     */
    DataObject notifications(long guildId, Guild guild) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DataArray events = DataArray.empty();
        DataArray polls = DataArray.empty();
        DataArray signups = DataArray.empty();
        // A bot-less site (guild null) has no Discord events, polls or signup sheets: only the activity lines.
        if (guild == null) return DataObject.empty().put("events", events).put("polls", polls).put("signups", signups)
                .put("feed", feed(guildId, Integer.toString(NOTIFICATION_FEED_ITEMS), null).getArray("items"));
        if (discord.eventsShown(guildId)) guild.getScheduledEvents().stream()
                .filter(e -> e.getStatus() == ScheduledEvent.Status.ACTIVE
                        || (e.getStatus() == ScheduledEvent.Status.SCHEDULED && e.getStartTime().isBefore(now.plus(EVENTS_SOON))))
                .sorted(Comparator.comparing(ScheduledEvent::getStartTime)).limit(NOTIFICATION_ITEMS)
                .forEach(e -> events.add(DataObject.empty().put("id", e.getId()).put("name", e.getName()).put("status", e.getStatus().name())
                        .put("startTime", e.getStartTime().toString()).put("url", "https://discord.com/events/" + guild.getId() + "/" + e.getId())));

        for (DataObject poll : objects(polls(guildId).getArray("polls"))) {
            if (polls.length() >= NOTIFICATION_ITEMS) break;
            if (poll.getBoolean("active")) polls.add(DataObject.empty().put("id", poll.getString("id")).put("title", poll.getString("title"))
                    .put("createdAt", poll.getString("createdAt")).put("closesAt", poll.getString("closesAt", null)).put("url", poll.getString("url", null)));
        }

        for (DataObject sheet : objects(signups(guildId, true).getArray("signups"))) {
            if (signups.length() >= NOTIFICATION_ITEMS) break;
            if (!sheet.getBoolean("paused")) signups.add(DataObject.empty().put("id", sheet.getString("id")).put("title", sheet.getString("title"))
                    .put("createdAt", sheet.getString("createdAt")).put("entries", sheet.getArray("entries").length()).put("max", sheet.isNull("max") ? null : sheet.getInt("max")));
        }

        return DataObject.empty().put("events", events).put("polls", polls).put("signups", signups)
                .put("feed", feed(guildId, Integer.toString(NOTIFICATION_FEED_ITEMS), null).getArray("items"));
    }

    private static List<DataObject> objects(DataArray array) {
        List<DataObject> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) list.add(array.getObject(i));
        return list;
    }

    /** How long an answer may be reused. Live things are short; heavy aggregates are longer. Writes drop their own entries early. */
    private static long ttlMillis(String route) {
        return switch (route) {
            case "online" -> 10_000;
            case "polls", "signups" -> 5_000;
            case "feed", "notifications" -> 15_000;
            case "events" -> 30_000;
            case "members", "overview", "member", "news" -> 60_000;
            case "me" -> 15_000;
            case "options" -> 30_000;
            case "recap" -> 300_000;
            case "bosses", "boss", "item", "drop-log" -> 60_000;
            default -> 120_000; // records, history, citadel grid, PvM, drops, coffer, leaderboards, skill series
        };
    }

    // ---------- members who chose to stay out of the rankings or hide their adventure log ----------

    private static boolean shown(Set<String> hidden, String rsn) {
        return !hidden.contains(rsn.toLowerCase());
    }

    private static List<Gain> visible(List<Gain> gains, Set<String> hidden) {
        return gains.stream().filter(g -> shown(hidden, g.rsn())).toList();
    }

    // ---------- who's online, in the order Discord's own member list uses ----------

    /**
     * Online members grouped the way Discord's member list groups them: under their highest <em>hoisted</em>
     * role (the roles set to "display separately"), groups ordered by that role's position, everyone else
     * under a trailing "Online" group, and alphabetical (ignoring case) inside each group.
     */
    /** The online answer while the Presence intent is off: nothing can be known, which is not the same as nobody being online. */
    static DataObject onlineUnavailable() {
        return DataObject.empty().put("total", 0).put("groups", DataArray.empty()).put("unavailable", true);
    }

    DataObject online(Guild guild) {
        if (!PrivilegedIntents.presence()) return onlineUnavailable();
        record Group(long id, String name, int colorRaw, int position) {}
        // Which online members can be linked to a clan profile: they have a linked RuneScape name that is a current member and haven't opted out.
        long guildId = guild.getIdLong();
        Set<String> activeRsns = new java.util.HashSet<>();
        stats.members(guildId).forEach(m -> activeRsns.add(m.rsn().toLowerCase()));
        Set<Long> hiddenLinks = profiles.hiddenDiscordUsers(guildId);
        // Members who asked not to be named in the online list: counted, but not listed (as on a clan site).
        Set<Long> hiddenNames = discord.hiddenFromOnline(guildId);
        Map<Long, String> rsnByUser = new HashMap<>();
        for (var link : links.getAllLinks(guildId)) {
            if (activeRsns.contains(link.rsn().toLowerCase()) && !hiddenLinks.contains(link.discordUserId())) rsnByUser.putIfAbsent(link.discordUserId(), link.rsn());
        }

        Map<Long, List<Member>> byGroup = new HashMap<>();
        Map<Long, Group> groups = new HashMap<>();
        int total = 0;

        for (Member member : guild.getMembers()) {
            if (member.getUser().isBot()) continue;
            OnlineStatus status = member.getOnlineStatus();
            if (status == OnlineStatus.OFFLINE || status == OnlineStatus.UNKNOWN) continue;
            total++;
            if (hiddenNames.contains(member.getIdLong())) continue;

            Role hoisted = member.getRoles().stream().filter(Role::isHoisted).findFirst().orElse(null);
            long key = hoisted == null ? 0 : hoisted.getIdLong();
            groups.computeIfAbsent(key, k -> hoisted == null
                    ? new Group(0, "Online", -1, Integer.MIN_VALUE)
                    : new Group(hoisted.getIdLong(), hoisted.getName(), hoisted.getColors().getPrimaryRaw(), hoisted.getPosition()));
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(member);
        }

        DataArray groupArray = DataArray.empty();
        groups.values().stream().sorted(Comparator.comparingInt(Group::position).reversed()).forEach(group -> {
            List<Member> members = byGroup.get(group.id());
            members.sort(Comparator.comparing(m -> m.getEffectiveName().toLowerCase()));

            DataArray memberArray = DataArray.empty();
            for (Member m : members) {
                List<Role> roles = m.getRoles();
                Role top = roles.isEmpty() ? null : roles.getFirst();
                memberArray.add(DataObject.empty()
                        .put("id", m.getId())
                        .put("displayName", m.getEffectiveName())
                        .put("avatarUrl", m.getEffectiveAvatarUrl())
                        .put("status", m.getOnlineStatus().getKey())
                        .put("colorRaw", m.getColors().getPrimaryRaw())
                        .put("rsn", rsnByUser.get(m.getIdLong()))
                        .put("topRole", top == null ? null : top.getName()));
            }
            groupArray.add(DataObject.empty()
                    .put("name", group.name())
                    .put("colorRaw", group.colorRaw())
                    .put("count", members.size())
                    .put("members", memberArray));
        });

        return DataObject.empty().put("total", total).put("groups", groupArray).put("unavailable", false);
    }

    // ---------- events ----------

    DataObject events(Guild guild) {
        DataArray events = DataArray.empty();
        guild.getScheduledEvents().stream()
                .sorted(Comparator.comparing(ScheduledEvent::getStartTime))
                .forEach(event -> events.add(DataObject.empty()
                        .put("id", event.getId())
                        .put("name", event.getName())
                        .put("description", event.getDescription())
                        .put("imageUrl", event.getImageUrl())
                        .put("location", locationOf(guild, event))
                        .put("status", event.getStatus().name())
                        .put("startTime", event.getStartTime().toString())
                        .put("endTime", event.getEndTime() == null ? null : event.getEndTime().toString())
                        .put("interestedCount", event.getInterestedUserCount())
                        .put("url", "https://discord.com/events/" + guild.getId() + "/" + event.getId())));
        return DataObject.empty().put("events", events);
    }

    /** For a voice or stage event JDA's "location" is the channel's id, which means nothing to a reader — show its name instead. */
    private static String locationOf(Guild guild, ScheduledEvent event) {
        if (event.getType() == ScheduledEvent.Type.EXTERNAL) return event.getLocation();
        try {
            var channel = guild.getGuildChannelById(Long.parseLong(event.getLocation()));
            return channel == null ? null : (event.getType() == ScheduledEvent.Type.STAGE_INSTANCE ? "Stage: " : "Voice: ") + channel.getName();
        } catch (NumberFormatException e) {
            return event.getLocation();
        }
    }

    // ---------- the roster ----------

    private static DataObject memberJson(MemberRow m) {
        return DataObject.empty()
                .put("rsn", m.rsn())
                .put("rank", m.clanRank())
                .put("rankOrder", m.rankOrder())
                .put("totalXp", m.totalXp())
                .put("kills", m.kills())
                .put("joined", (m.clanJoinedAt() != null ? m.clanJoinedAt() : m.firstSeen().toLocalDate()).toString())
                .put("joinedExact", m.clanJoinedAt() != null)
                .put("points", m.points())
                .put("promotionNeeded", m.promotionNeeded())
                .put("verified", m.verified())
                .put("totalLevel", m.totalLevel())
                .put("combatLevel", m.combatLevel());
    }

    private DataArray ranksJson(long guildId, List<MemberRow> members) {
        Map<Integer, Integer> counts = new HashMap<>();
        members.forEach(m -> counts.merge(m.rankOrder(), 1, Integer::sum));

        DataArray ranks = DataArray.empty();
        for (RankConfigRow rank : points.getRanksOrdered(guildId)) {
            ranks.add(DataObject.empty()
                    .put("name", rank.rankName())
                    .put("order", rank.rankOrder())
                    .put("threshold", rank.pointThreshold())
                    .put("count", counts.getOrDefault(rank.rankOrder(), 0)));
        }
        return ranks;
    }

    /**
     * The whole in-game clan list. {@code pagePublic} (T-416): whether the member's own page can be opened ({@link #visibleMember}); false for a
     * member hidden from the rankings, by their choice or the server's member privacy defaults, so the directory shows their name unlinked.
     */
    DataObject members(long guildId) {
        List<MemberRow> members = stats.members(guildId);
        Set<String> hiddenBoards = profiles.hiddenRsns(guildId, true);
        DataArray array = DataArray.empty();
        members.stream()
                .sorted(Comparator.comparingInt(MemberRow::rankOrder).reversed().thenComparing(m -> m.rsn().toLowerCase()))
                .forEach(m -> array.add(memberJson(m).put("pagePublic", shown(hiddenBoards, m.rsn()))));
        return DataObject.empty().put("members", array).put("ranks", ranksJson(guildId, members));
    }

    // ---------- clan overview: leaderboards and charts ----------

    private static DataArray gainsJson(List<Gain> gains, int limit) {
        DataArray array = DataArray.empty();
        gains.stream().filter(g -> g.xp() > 0).sorted(Comparator.comparingLong(Gain::xp).reversed()).limit(limit)
                .forEach(g -> array.add(DataObject.empty().put("rsn", g.rsn()).put("xp", g.xp())));
        return array;
    }

    private static DataArray topBy(List<MemberRow> members, ToLongFunction<MemberRow> metric, int limit) {
        DataArray array = DataArray.empty();
        members.stream().filter(m -> metric.applyAsLong(m) > 0)
                .sorted(Comparator.comparingLong(metric).reversed()).limit(limit)
                .forEach(m -> array.add(DataObject.empty().put("rsn", m.rsn()).put("value", metric.applyAsLong(m))));
        return array;
    }

    DataObject overview(long guildId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<MemberRow> members = stats.members(guildId);

        List<Gain> day = stats.xpGains(guildId, now.minusDays(1));
        List<Gain> week = stats.xpGains(guildId, now.minusDays(7));
        List<Gain> month = stats.xpGains(guildId, now.minusDays(30));

        DataObject clan = DataObject.empty()
                .put("name", settings.getEffective(guildId).clanName())
                .put("memberCount", members.size())
                .put("verifiedCount", members.stream().filter(MemberRow::verified).count())
                .put("totalXp", members.stream().mapToLong(MemberRow::totalXp).sum())
                .put("totalKills", members.stream().mapToLong(MemberRow::kills).sum())
                .put("averageTotalLevel", Math.round(members.stream().filter(m -> m.totalLevel() != null).mapToInt(MemberRow::totalLevel).average().orElse(0)))
                .put("xpToday", day.stream().mapToLong(Gain::xp).sum())
                .put("xpWeek", week.stream().mapToLong(Gain::xp).sum())
                .put("xpMonth", month.stream().mapToLong(Gain::xp).sum());

        Set<String> hiddenBoards = profiles.hiddenRsns(guildId, true);
        List<MemberRow> ranked = members.stream().filter(m -> shown(hiddenBoards, m.rsn())).toList();

        DataObject gains = DataObject.empty()
                .put("day", gainsJson(visible(day, hiddenBoards), 10)).put("week", gainsJson(visible(week, hiddenBoards), 10)).put("month", gainsJson(visible(month, hiddenBoards), 10));

        DataArray citadelWeeks = DataArray.empty();
        for (var w : stats.citadelByWeek(guildId, CHART_WEEKS)) {
            citadelWeeks.add(DataObject.empty().put("weekStart", w.weekStart().toString()).put("capped", w.capped()).put("visited", w.visited()));
        }
        DataArray cappers = DataArray.empty();
        for (var c : stats.topCappers(guildId, 40).stream().filter(c -> shown(hiddenBoards, c.rsn())).limit(10).toList()) {
            cappers.add(DataObject.empty().put("rsn", c.rsn()).put("weeksCapped", c.weeksCapped()).put("totalCaps", c.totalCaps()));
        }

        DataArray roster = DataArray.empty();
        for (var w : stats.rosterByWeek(guildId, CHART_WEEKS)) {
            roster.add(DataObject.empty().put("weekStart", w.weekStart().toString()).put("joins", w.joins()).put("leaves", w.leaves()));
        }

        // the leaderboard race's real days (T-710): RuneSync XP from the hub's ledger, clan points from the awards. Only active members who
        // may be ranked race: not a former member, not an old name, not a hub row under an alt's name, not someone hidden (by choice or default)
        LocalDate today = now.toLocalDate();
        Set<String> racers = ranked.stream().map(m -> m.rsn().toLowerCase()).collect(java.util.stream.Collectors.toSet());
        java.util.function.Predicate<String> racing = rsn -> rsn != null && racers.contains(rsn.toLowerCase());
        PointsMonth month30 = pointsMonth(stats.pointsByDay(guildId, today.minusDays(RACE_DAYS - 1)), today, racing, 10);
        DataObject xpRace = pluginFeed == null ? null : pluginFeed.xpRace(guildId, racing);
        DataObject race = DataObject.empty();
        if (xpRace != null) race.put("xp", xpRace);
        if (month30.race() != null) race.put("points", month30.race());

        Map<Integer, List<SiteStatsRepository.SkillLeader>> bySkill = new TreeMap<>();
        stats.skillLeaders(guildId, 12).stream().filter(l -> shown(hiddenBoards, l.rsn())).forEach(l -> bySkill.computeIfAbsent(l.skillId(), k -> new ArrayList<>()).add(l));
        DataArray skills = DataArray.empty();
        bySkill.forEach((skillId, leaders) -> {
            DataArray array = DataArray.empty();
            leaders.forEach(l -> array.add(DataObject.empty().put("rsn", l.rsn()).put("level", l.level()).put("xp", l.xp())));
            skills.add(DataObject.empty().put("skillId", skillId).put("skill", RuneScapeSkillCatalog.nameFor(skillId)).put("leaders", array));
        });

        return DataObject.empty()
                .put("clan", clan)
                .put("ranks", ranksJson(guildId, members))
                .put("gains", gains)
                .put("citadel", DataObject.empty().put("weeks", citadelWeeks).put("topCappers", cappers))
                .put("roster", roster)
                .put("skillLeaders", skills)
                .put("topXp", topBy(ranked, MemberRow::totalXp, 10))
                .put("topKills", topBy(ranked, MemberRow::kills, 10))
                .put("topLevel", topBy(ranked, m -> m.totalLevel() == null ? 0 : m.totalLevel(), 10))
                .put("topPoints", topBy(ranked, MemberRow::points, 10))
                .put("topPointsMonth", month30.top())
                .put("race", race);
    }

    /** Days in the leaderboard race's month (T-710): the last 30 UTC days, today included, as the hub's race route counts them. */
    static final int RACE_DAYS = 30;

    /** The month of clan points for the leaderboard race: the top members by points earned, and their running totals per day (null: nobody). */
    record PointsMonth(DataArray top, DataObject race) {}

    /**
     * Clan points earned per member over the {@link #RACE_DAYS} days ending {@code today} (UTC), from the day rows: the top {@code limit} by
     * points earned (only names {@code racing} lets in: active members who may be ranked; ties by name), and for them each day's running total.
     */
    static PointsMonth pointsMonth(List<SiteStatsRepository.PointsDay> rows, LocalDate today, java.util.function.Predicate<String> racing, int limit) {
        LocalDate first = today.minusDays(RACE_DAYS - 1);
        Map<String, String> names = new java.util.HashMap<>();
        Map<String, long[]> byMember = new java.util.HashMap<>();
        for (SiteStatsRepository.PointsDay row : rows) {
            if (row.day().isBefore(first) || row.day().isAfter(today) || !racing.test(row.rsn())) continue;
            String key = row.rsn().toLowerCase();
            names.putIfAbsent(key, row.rsn());
            byMember.computeIfAbsent(key, k -> new long[RACE_DAYS])[(int) (row.day().toEpochDay() - first.toEpochDay())] += row.points();
        }
        List<String> order = byMember.entrySet().stream().filter(e -> java.util.Arrays.stream(e.getValue()).sum() > 0)
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> -java.util.Arrays.stream(e.getValue()).sum()).thenComparing(Map.Entry::getKey))
                .limit(limit).map(Map.Entry::getKey).toList();
        DataArray top = DataArray.empty();
        DataArray raceRows = DataArray.empty();
        for (String key : order) {
            long running = 0;
            DataArray values = DataArray.empty();
            for (long day : byMember.get(key)) values.add(running += day);
            top.add(DataObject.empty().put("rsn", names.get(key)).put("value", running));
            raceRows.add(DataObject.empty().put("rsn", names.get(key)).put("values", values));
        }
        DataArray days = DataArray.empty();
        for (int i = 0; i < RACE_DAYS; i++) days.add(first.plusDays(i).toString());
        return new PointsMonth(top, order.isEmpty() ? null : DataObject.empty().put("days", days).put("rows", raceRows));
    }

    // ---------- one member's profile ----------

    /** XP gained from {@code since} to the newest snapshot; snapshots are newest first. */
    static long gainSince(List<StatsSnapshotRow> newestFirst, OffsetDateTime since) {
        if (newestFirst.size() < 2) return 0;
        long latest = newestFirst.getFirst().totalXp();
        StatsSnapshotRow baseline = null;
        for (StatsSnapshotRow row : newestFirst) {
            if (!row.snapshotAt().isAfter(since)) {
                baseline = row;
                break;
            }
        }
        if (baseline == null) baseline = newestFirst.getLast(); // history doesn't reach back that far — use what there is
        return Math.max(0, latest - baseline.totalXp());
    }

    /** Per-skill XP gained since {@code since}, from a baseline at or before it where one exists. */
    static Map<Integer, Long> skillGainsSince(List<SkillHistoryPoint> points, OffsetDateTime since) {
        Map<Integer, List<SkillHistoryPoint>> bySkill = new HashMap<>();
        points.forEach(p -> bySkill.computeIfAbsent(p.skillId(), k -> new ArrayList<>()).add(p));

        Map<Integer, Long> gains = new LinkedHashMap<>();
        bySkill.forEach((skillId, series) -> {
            series.sort(Comparator.comparing(SkillHistoryPoint::timestamp));
            long latest = series.getLast().xp();
            SkillHistoryPoint baseline = series.getFirst();
            for (SkillHistoryPoint p : series) {
                if (p.timestamp().isAfter(since)) break;
                baseline = p;
            }
            long gain = latest - baseline.xp();
            if (gain > 0) gains.put(skillId, gain);
        });
        return gains;
    }

    DataObject member(Guild guild, String rsn) {
        return member(guild.getIdLong(), rsn);
    }

    DataObject member(long guildId, String rsn) {
        if (rsn == null || rsn.isBlank()) return null;

        MemberRow row = stats.members(guildId).stream().filter(m -> m.rsn().equalsIgnoreCase(rsn.trim())).findFirst().orElse(null);
        if (row == null) return null;

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<StatsSnapshotRow> snapshots = links.getSnapshotHistory(row.rsn(), 2000);
        StatsSnapshotRow latest = snapshots.isEmpty() ? null : snapshots.getFirst();

        DataObject json = memberJson(row)
                .put("firstSeen", row.firstSeen().toString())
                .put("lastPolled", row.lastPolled() == null ? null : row.lastPolled().toString())
                .put("questsComplete", latest == null ? null : latest.questsComplete());

        // Progress toward the next rank, by clan points.
        List<RankConfigRow> ranks = points.getRanksOrdered(guildId);
        RankConfigRow next = ranks.stream().filter(r -> r.rankOrder() > row.rankOrder()).min(Comparator.comparingInt(RankConfigRow::rankOrder)).orElse(null);
        json.put("nextRank", next == null ? null : DataObject.empty()
                .put("name", next.rankName())
                .put("threshold", next.pointThreshold())
                .put("pointsNeeded", Math.max(0, next.pointThreshold() - row.points())));
        // The highest rank order, so the website can colour the member's rank by how high it is (rsyounglings' rank badge).
        json.put("maxRankOrder", ranks.stream().mapToInt(RankConfigRow::rankOrder).max().orElse(0));
        json.put("standing", standing(guildId, row, now));

        // Skills now.
        DataArray skills = DataArray.empty();
        if (latest != null) {
            for (SkillValue skill : links.getSkillsForSnapshot(latest.snapshotId())) {
                skills.add(DataObject.empty().put("id", skill.skillId()).put("name", RuneScapeSkillCatalog.nameFor(skill.skillId()))
                        .put("level", skill.level()).put("xp", skill.xp()).put("rank", skill.rank())
                        .put("xpToNext", RuneScapeXpTable.xpToNextLevel(skill.skillId(), skill.level(), skill.xp()))
                        .put("xpTo99", Math.max(0, RuneScapeXpTable.xpForLevel(skill.skillId(), 99) - skill.xp()))
                        .put("xpTo120", Math.max(0, RuneScapeXpTable.xpForLevel(skill.skillId(), 120) - skill.xp())));
            }
        }
        json.put("skills", skills);

        // Gains: totals and per skill.
        json.put("gains", DataObject.empty()
                .put("day", gainSince(snapshots, now.minus(Duration.ofDays(1))))
                .put("week", gainSince(snapshots, now.minus(Duration.ofDays(7))))
                .put("month", gainSince(snapshots, now.minus(Duration.ofDays(30)))));

        List<SkillHistoryPoint> skillHistory = links.getAllSkillsXpHistorySince(row.rsn(), now.minusDays(32));
        json.put("skillGains", DataObject.empty()
                .put("day", skillGainsJson(skillGainsSince(skillHistory, now.minusDays(1))))
                .put("week", skillGainsJson(skillGainsSince(skillHistory, now.minusDays(7))))
                .put("month", skillGainsJson(skillGainsSince(skillHistory, now.minusDays(30)))));

        // Daily history for the charts.
        DataArray history = DataArray.empty();
        for (var point : stats.dailyHistory(guildId, row.rsn(), HISTORY_DAYS)) {
            history.add(DataObject.empty().put("date", point.date().toString()).put("totalXp", point.totalXp()).put("totalLevel", point.totalLevel()));
        }
        json.put("history", history);

        // Citadel record.
        var citadel = stats.citadelFor(guildId, row.rsn());
        DataArray weeks = DataArray.empty();
        for (LocalDate week : citadel.cappedWeeks()) weeks.add(week.toString());
        json.put("citadel", DataObject.empty().put("caps", citadel.caps()).put("visits", citadel.visits()).put("cappedWeeks", weeks));

        // Clan-point awards, newest first.
        DataArray awards = DataArray.empty();
        for (var award : stats.awards(guildId, row.rsn(), 60)) {
            awards.add(DataObject.empty().put("type", award.type()).put("points", award.points()).put("date", award.date().toString()));
        }
        json.put("awards", awards);

        // What the member chose to show on their profile.
        Profile chosen = profiles.getProfileForRsn(guildId, row.rsn());
        json.put("bio", chosen == null ? "" : chosen.bio()).put("accentColor", chosen == null ? null : chosen.accentColor())
                .put("pinnedSkill", chosen == null ? null : chosen.pinnedSkill());
        boolean hideLog = profiles.adventureLogHidden(guildId, row.rsn());
        json.put("adventureLogHidden", hideLog);

        // Adventure log.
        DataArray activities = DataArray.empty();
        for (PlayerActivity activity : hideLog ? List.<PlayerActivity>of() : links.getRecentActivities(row.rsn(), 30)) {
            activities.add(DataObject.empty().put("date", activity.date()).put("occurredAt", occurredAt(activity.date())).put("text", activity.text()).put("details", activity.details()));
        }
        json.put("activities", activities);

        return json;
    }

    /**
     * Where the member stands among the clan's leaders (rsyounglings' Rising star and Points leader badges): their place for XP gained in the
     * last 7 days and for clan points, 1 being first, as the overview's top-10 lists count it (members hidden from boards left out); null
     * outside the top 10 or when they are hidden themselves.
     */
    private DataObject standing(long guildId, MemberRow row, OffsetDateTime now) {
        Set<String> hiddenBoards = profiles.hiddenRsns(guildId, true);
        if (!shown(hiddenBoards, row.rsn())) return DataObject.empty().put("weekGain", null).put("points", null);
        List<String> gainers = visible(stats.xpGains(guildId, now.minusDays(7)), hiddenBoards).stream().filter(g -> g.xp() > 0)
                .sorted(Comparator.comparingLong(Gain::xp).reversed()).limit(10).map(Gain::rsn).toList();
        List<String> pointsLeaders = stats.members(guildId).stream().filter(m -> shown(hiddenBoards, m.rsn()) && m.points() > 0)
                .sorted(Comparator.comparingLong(MemberRow::points).reversed()).limit(10).map(MemberRow::rsn).toList();
        return DataObject.empty().put("weekGain", place(gainers, row.rsn())).put("points", place(pointsLeaders, row.rsn()));
    }

    private static Integer place(List<String> rsns, String rsn) {
        for (int i = 0; i < rsns.size(); i++) {
            if (rsns.get(i).equalsIgnoreCase(rsn)) return i + 1;
        }
        return null;
    }

    // ---------- activity feed, records, history, polls, PvM and drops ----------

    /** How many adventure-log entries a feed of one kind looks through (a few months of a big clan's log). */
    static final int KIND_SCAN = 3000;

    /** The clan's newest notable adventure-log entries (level-ups, milestones, quests, boss kills, drops, pets, caps…). */
    DataObject feed(long guildId, String limitRaw, String kindRaw) {
        int limit = 40;
        try {
            if (limitRaw != null) limit = Math.max(1, Math.min(100, Integer.parseInt(limitRaw.trim())));
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        ActivityKinds.Kind wanted = null;
        if (kindRaw != null && !kindRaw.isBlank()) {
            try {
                wanted = ActivityKinds.Kind.valueOf(kindRaw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        Set<String> hiddenLogs = profiles.hiddenRsns(guildId, false);
        DataArray items = DataArray.empty();
        // one kind (pets for the rare drops wall) is rarer than the rest, so look further back for it
        for (var row : stats.recentActivities(guildId, wanted == null ? limit * 6 : Math.max(limit * 6, KIND_SCAN))) {
            if (!shown(hiddenLogs, row.rsn())) continue;
            ActivityKinds.Kind kind = ActivityKinds.kindOf(row.text());
            if (kind == ActivityKinds.Kind.OTHER || kind == ActivityKinds.Kind.CITADEL_VISIT) continue;
            if (wanted != null && kind != wanted) continue;
            items.add(DataObject.empty().put("rsn", row.rsn()).put("kind", kind.name()).put("text", row.text()).put("details", row.details())
                    .put("date", row.date()).put("occurredAt", occurredAt(row.date())).put("recordedAt", row.recordedAt().toString()));
            if (items.length() >= limit) break;
        }
        return DataObject.empty().put("items", items);
    }

    /** The longest run of consecutive Citadel weeks capped, and whether it is still going. */
    record Streak(String rsn, int longest, int current) {}

    static List<Streak> capStreaks(List<SiteStatsRepository.CapWeek> capWeeks, LocalDate currentWeekStart) {
        Map<String, TreeMap<LocalDate, Boolean>> byMember = new HashMap<>();
        capWeeks.forEach(c -> byMember.computeIfAbsent(c.rsn(), k -> new TreeMap<>()).put(c.weekStart(), true));

        List<Streak> streaks = new ArrayList<>();
        byMember.forEach((rsn, weeks) -> {
            int longest = 0;
            int run = 0;
            LocalDate previous = null;
            for (LocalDate week : weeks.keySet()) {
                run = previous != null && previous.plusWeeks(1).equals(week) ? run + 1 : 1;
                longest = Math.max(longest, run);
                previous = week;
            }
            // "Current" counts only if the last capped week is this week or the one before (this week may not be done yet).
            boolean live = previous != null && !previous.isBefore(currentWeekStart.minusWeeks(1));
            streaks.add(new Streak(rsn, longest, live ? run : 0));
        });
        streaks.sort(Comparator.comparingInt(Streak::longest).reversed().thenComparing(Streak::rsn));
        return streaks;
    }

    private static LocalDate currentCitadelWeekStart() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int sinceWednesday = (today.getDayOfWeek().getValue() - java.time.DayOfWeek.WEDNESDAY.getValue() + 7) % 7;
        return today.minusDays(sinceWednesday);
    }

    DataObject records(Guild guild) {
        return records(guild.getIdLong());
    }

    DataObject records(long guildId) {
        return records(guildId, false);
    }

    /** @param strict a clan site's answer: the longest-serving list leaves out members hidden from the rankings too */
    DataObject records(long guildId, boolean strict) {
        Set<String> hiddenBoards = profiles.hiddenRsns(guildId, true);
        DataArray days = DataArray.empty();
        stats.biggestDays(guildId, 30).stream().filter(d -> shown(hiddenBoards, d.rsn())).limit(8).forEach(d -> days.add(DataObject.empty().put("rsn", d.rsn()).put("date", d.date().toString()).put("xp", d.xp())));
        DataArray club = DataArray.empty();
        stats.twoHundredMillionClub(guildId, 40).stream().filter(c -> shown(hiddenBoards, c.rsn())).limit(10).forEach(c -> club.add(DataObject.empty().put("rsn", c.rsn()).put("skills", c.skills())));

        DataArray streaks = DataArray.empty();
        capStreaks(stats.allCapWeeks(guildId), currentCitadelWeekStart()).stream().filter(s -> shown(hiddenBoards, s.rsn())).limit(10)
                .forEach(s -> streaks.add(DataObject.empty().put("rsn", s.rsn()).put("longest", s.longest()).put("current", s.current())));

        DataArray veterans = DataArray.empty();
        stats.members(guildId).stream().filter(m -> !strict || shown(hiddenBoards, m.rsn()))
                .sorted(Comparator.comparing((MemberRow m) -> m.clanJoinedAt() != null ? m.clanJoinedAt() : m.firstSeen().toLocalDate()).thenComparing(MemberRow::rsn))
                .limit(8).forEach(m -> veterans.add(DataObject.empty().put("rsn", m.rsn())
                        .put("joined", (m.clanJoinedAt() != null ? m.clanJoinedAt() : m.firstSeen().toLocalDate()).toString()).put("joinedExact", m.clanJoinedAt() != null)));

        return DataObject.empty().put("biggestDays", days).put("twoHundredClub", club).put("capStreaks", streaks).put("veterans", veterans);
    }

    DataObject citadelGrid(long guildId, String weeksRaw) {
        int weeks = 12;
        try {
            if (weeksRaw != null) weeks = Math.max(4, Math.min(26, Integer.parseInt(weeksRaw.trim())));
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        LocalDate current = currentCitadelWeekStart();
        List<LocalDate> weekStarts = new ArrayList<>();
        for (int i = weeks - 1; i >= 0; i--) weekStarts.add(current.minusWeeks(i));

        Map<String, int[]> grid = new LinkedHashMap<>();
        final int weekCount = weeks;
        Set<String> hiddenBoards = profiles.hiddenRsns(guildId, true);
        stats.members(guildId).stream().filter(m -> shown(hiddenBoards, m.rsn())).forEach(m -> grid.put(m.rsn(), new int[weekCount]));
        for (var cell : stats.citadelGrid(guildId, weeks)) {
            int[] row = grid.get(cell.rsn());
            int index = weekStarts.indexOf(cell.weekStart());
            if (row != null && index >= 0) row[index] = cell.capped() ? 2 : 1;
        }

        DataArray weekArray = DataArray.empty();
        weekStarts.forEach(w -> weekArray.add(w.toString()));
        DataArray rows = DataArray.empty();
        grid.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> (int) java.util.Arrays.stream(e.getValue()).filter(v -> v == 2).count()).reversed()
                        .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> {
                    DataArray cells = DataArray.empty();
                    for (int v : e.getValue()) cells.add(v);
                    rows.add(DataObject.empty().put("rsn", e.getKey()).put("weeks", cells));
                });
        return DataObject.empty().put("weeks", weekArray).put("members", rows);
    }

    DataObject history(long guildId) {
        return history(guildId, false);
    }

    /** @param strict a clan site's answer: the timeline and "close to promotion" leave out members hidden from the rankings (the counts stay whole) */
    DataObject history(long guildId, boolean strict) {
        List<MemberRow> members = stats.members(guildId);
        var events = stats.rosterEvents(guildId, 200);
        Set<String> hiddenBoards = strict ? profiles.hiddenRsns(guildId, true) : Set.of();

        // Member count after each day with changes, walking backwards from today's roster size.
        TreeMap<LocalDate, Integer> countByDay = new TreeMap<>();
        int count = members.size();
        countByDay.put(LocalDate.now(ZoneOffset.UTC), count);
        for (var event : events) { // newest first
            LocalDate day = event.at().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
            countByDay.putIfAbsent(day, count); // the count at the END of that day
            count += event.type().equals("JOIN") ? -1 : 1;
        }
        if (!events.isEmpty()) countByDay.putIfAbsent(events.getLast().at().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().minusDays(1), count);
        DataArray series = DataArray.empty();
        countByDay.forEach((day, n) -> series.add(DataObject.empty().put("date", day.toString()).put("members", n)));

        // A merged timeline of joins, leaves and confirmed renames.
        record Entry(String type, String rsn, String other, OffsetDateTime at) {}
        List<Entry> timeline = new ArrayList<>();
        events.stream().filter(e -> shown(hiddenBoards, e.rsn())).limit(60).forEach(e -> timeline.add(new Entry(e.type(), e.rsn(), null, e.at())));
        stats.confirmedRenames(guildId, 20).stream().filter(r -> shown(hiddenBoards, r.newRsn()) && shown(hiddenBoards, r.oldRsn()))
                .forEach(r -> timeline.add(new Entry("RENAME", r.newRsn(), r.oldRsn(), r.at())));
        timeline.sort(Comparator.comparing(Entry::at).reversed());
        DataArray timelineArray = DataArray.empty();
        timeline.stream().limit(60).forEach(e -> timelineArray.add(DataObject.empty().put("type", e.type()).put("rsn", e.rsn()).put("from", e.other()).put("at", e.at().toString())));

        // The rank ladder, and the members closest to their next rank.
        List<RankConfigRow> ranks = points.getRanksOrdered(guildId);
        record Close(MemberRow member, RankConfigRow next, long needed) {}
        List<Close> close = new ArrayList<>();
        for (MemberRow m : members) {
            if (!shown(hiddenBoards, m.rsn())) continue;
            RankConfigRow next = ranks.stream().filter(r -> r.rankOrder() > m.rankOrder()).min(Comparator.comparingInt(RankConfigRow::rankOrder)).orElse(null);
            if (next != null && next.pointThreshold() > 0) close.add(new Close(m, next, Math.max(0, next.pointThreshold() - m.points())));
        }
        close.sort(Comparator.comparingLong(Close::needed).thenComparing(c -> c.member().rsn()));
        DataArray closeArray = DataArray.empty();
        close.stream().limit(12).forEach(c -> closeArray.add(DataObject.empty().put("rsn", c.member().rsn()).put("rank", c.member().clanRank())
                .put("next", c.next().rankName()).put("points", c.member().points()).put("needed", c.needed()).put("promotionNeeded", c.member().promotionNeeded())));

        return DataObject.empty().put("memberCount", series).put("timeline", timelineArray).put("ranks", ranksJson(guildId, members)).put("closeToPromotion", closeArray);
    }

    /**
     * Open signup sheets and who is on them. A sheet's entry stores either the name someone typed (queue
     * signups) or just their Discord id (group and submission signups) — the id is never sent: the site shows
     * the member's linked RuneScape name, or "A member" if they haven't linked one.
     */
    DataObject signups(Guild guild) {
        return signups(guild.getIdLong());
    }

    DataObject signups(long guildId) {
        return signups(guildId, false);
    }

    /** @param strict a clan site's answer: a member hidden from the rankings shows as "A member" here too */
    DataObject signups(long guildId, boolean strict) {
        Set<String> hiddenBoards = strict ? profiles.hiddenRsns(guildId, true) : Set.of();
        DataArray array = DataArray.empty();
        for (var sheet : stats.activeSignups(guildId)) {
            SignupSession session = signupService.getSessionById(sheet.id());
            String type = session == null ? "QUEUE" : session.type().name();

            DataArray fields = DataArray.empty();
            if (session != null && session.type() == com.runeclans.bot.commands.signup.SignupType.SUBMISSION) {
                for (SubmissionField f : SubmissionField.deserialize(session.submissionFields())) {
                    fields.add(DataObject.empty().put("label", f.label()).put("type", f.type()).put("required", f.required()));
                }
            }

            DataArray entries = DataArray.empty();
            for (var entry : sheet.entries()) {
                String name = entry.rsn();
                if (!type.equals("QUEUE")) {
                    var linked = links.getLinksForUser(guildId, entry.userId());
                    name = linked.isEmpty() ? "A member" : linked.getFirst().rsn();
                }
                if (name != null && !shown(hiddenBoards, name)) name = "A member";
                entries.add(DataObject.empty().put("name", name).put("position", entry.position()));
            }
            array.add(DataObject.empty().put("id", Long.toString(sheet.id())).put("title", sheet.title()).put("note", sheet.notification())
                    .put("max", sheet.max()).put("type", type).put("paused", !"ACTIVE".equalsIgnoreCase(sheet.status()))
                    .put("createdAt", sheet.createdAt().toString()).put("fields", fields).put("entries", entries));
        }
        return DataObject.empty().put("signups", array);
    }

    DataObject polls(long guildId) {
        DataArray array = DataArray.empty();
        var allPolls = stats.polls(guildId, 20);
        var closing = community.closesAt(allPolls.stream().map(SiteStatsRepository.PollRow::id).toList());
        for (var poll : allPolls) {
            DataArray options = DataArray.empty();
            int total = poll.options().stream().mapToInt(SiteStatsRepository.PollOption::votes).sum();
            poll.options().forEach(o -> options.add(DataObject.empty().put("number", o.number()).put("label", o.label()).put("votes", o.votes())));
            array.add(DataObject.empty().put("id", Long.toString(poll.id())).put("title", poll.title()).put("status", poll.status())
                    .put("anonymous", poll.anonymous()).put("multiple", poll.multiple()).put("totalVotes", total).put("active", "ACTIVE".equalsIgnoreCase(poll.status()))
                    .put("createdAt", poll.createdAt().toString()).put("closedAt", poll.closedAt() == null ? null : poll.closedAt().toString())
                    .put("closesAt", closing.get(poll.id()) == null || !"ACTIVE".equalsIgnoreCase(poll.status()) ? null : closing.get(poll.id()).toString())
                    .put("url", poll.messageId() == null ? null : "https://discord.com/channels/" + guildId + "/" + poll.channelId() + "/" + poll.messageId())
                    .put("options", options));
        }
        return DataObject.empty().put("polls", array);
    }

    /** Boss kill tallies from the adventure log: per boss, and who has killed it most. Only covers what the bot has seen since it began polling. */
    DataObject pvm(long guildId) {
        Map<String, Map<String, Integer>> byBoss = new HashMap<>();
        Map<String, Integer> byPlayer = new HashMap<>();
        Set<String> hiddenLogs = profiles.hiddenRsns(guildId, false);
        for (String prefix : List.of("I killed", "I defeated")) {
            for (var row : stats.activitiesStartingWith(guildId, prefix)) {
                if (!shown(hiddenLogs, row.rsn())) continue;
                var boss = ActivityKinds.bossOf(row.text());
                if (boss.isEmpty()) continue;
                int kills = ActivityKinds.killCount(row.text());
                byBoss.computeIfAbsent(boss.get(), k -> new HashMap<>()).merge(row.rsn(), kills, Integer::sum);
                byPlayer.merge(row.rsn(), kills, Integer::sum);
            }
        }

        DataArray bosses = DataArray.empty();
        byBoss.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Map<String, Integer>> e) -> e.getValue().values().stream().mapToInt(Integer::intValue).sum()).reversed())
                .limit(40).forEach(e -> {
                    DataArray killers = DataArray.empty();
                    e.getValue().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3)
                            .forEach(k -> killers.add(DataObject.empty().put("rsn", k.getKey()).put("kills", k.getValue())));
                    bosses.add(DataObject.empty().put("boss", e.getKey()).put("total", e.getValue().values().stream().mapToInt(Integer::intValue).sum()).put("killers", killers));
                });
        DataArray players = DataArray.empty();
        byPlayer.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10)
                .forEach(p -> players.add(DataObject.empty().put("rsn", p.getKey()).put("kills", p.getValue())));

        return DataObject.empty().put("totalKills", byPlayer.values().stream().mapToInt(Integer::intValue).sum()).put("bosses", bosses).put("topKillers", players);
    }

    /** Notable drops recorded in adventure logs, newest first, with a tally of which items drop most. */
    DataObject drops(long guildId) {
        DataArray recent = DataArray.empty();
        Map<String, Integer> tally = new HashMap<>();
        Set<String> hiddenLogs = profiles.hiddenRsns(guildId, false);
        for (var row : stats.activitiesStartingWith(guildId, "I found")) {
            if (!shown(hiddenLogs, row.rsn())) continue;
            var item = ActivityKinds.dropOf(row.text());
            if (item.isEmpty()) continue;
            tally.merge(item.get(), 1, Integer::sum);
            if (recent.length() < 60) recent.add(DataObject.empty().put("rsn", row.rsn()).put("item", item.get()).put("date", row.date()).put("occurredAt", occurredAt(row.date())).put("recordedAt", row.recordedAt().toString()));
        }
        DataArray top = DataArray.empty();
        tally.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(12)
                .forEach(e -> top.add(DataObject.empty().put("item", e.getKey()).put("count", e.getValue())));
        return DataObject.empty().put("total", tally.values().stream().mapToInt(Integer::intValue).sum()).put("recent", recent).put("topItems", top);
    }

    // ---------- one skill's XP over time ----------

    /** One skill's XP over the last 90 days, one point per UTC day (that day's last snapshot). */
    DataObject memberSkill(long guildId, String rsn, String skillRaw) {
        if (rsn == null || skillRaw == null) return null;
        int skillId;
        try {
            skillId = Integer.parseInt(skillRaw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (skillId < 0 || skillId >= RuneScapeSkillCatalog.skillCount()) return null;

        TreeMap<LocalDate, Long> byDay = new TreeMap<>();
        for (var point : links.getSkillXpHistory(rsn.trim(), skillId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(HISTORY_DAYS))) {
            byDay.put(point.timestamp().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(), point.xp()); // ascending, so the last write per day wins
        }
        DataArray history = DataArray.empty();
        byDay.forEach((date, xp) -> history.add(DataObject.empty().put("date", date.toString()).put("xp", xp)));
        return DataObject.empty().put("skillId", skillId).put("skill", RuneScapeSkillCatalog.nameFor(skillId)).put("history", history);
    }

    // ---------- month-by-month leaderboards ----------

    /** Top XP gainers, Citadel cappers and roster changes for one calendar month ({@code month} is {@code YYYY-MM}; blank means this month), plus the months that have data. */
    DataObject leaderboard(long guildId, String monthRaw, String periodRaw) {
        return leaderboard(guildId, monthRaw, periodRaw, false);
    }

    /** @param strict a clan site's answer: who joined and left leaves out members hidden from the rankings too */
    DataObject leaderboard(long guildId, String monthRaw, String periodRaw, boolean strict) {
        YearMonth current = YearMonth.now(ZoneOffset.UTC);

        OffsetDateTime from;
        OffsetDateTime to;
        DataObject periodJson;
        if (periodRaw != null && !periodRaw.isBlank()) {
            // Any window the recap understands: week, last-30d, ytd, 2026-09, a custom 2026-09-01_2026-09-20 range, all...
            RecapPeriod p = RecapPeriod.parse(periodRaw, OffsetDateTime.now(ZoneOffset.UTC), stats.firstSnapshotAt(guildId)).orElse(null);
            if (p == null) return null;
            from = p.from();
            to = p.to();
            periodJson = DataObject.empty().put("token", p.token()).put("label", p.label()).put("from", from.toString()).put("to", to.toString()).put("toDate", p.toDate());
        } else {
            YearMonth month;
            try {
                month = monthRaw == null || monthRaw.isBlank() ? current : YearMonth.parse(monthRaw.trim());
            } catch (Exception e) {
                return null;
            }
            if (month.isAfter(current)) return null;
            from = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            to = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            periodJson = DataObject.empty().put("token", month.toString()).put("label", month.toString()).put("from", from.toString()).put("to", to.toString()).put("toDate", month.equals(current));
        }

        Set<String> hiddenBoards = profiles.hiddenRsns(guildId, true);
        List<Gain> allGains = stats.xpGainsBetween(guildId, from, to);
        List<Gain> gains = visible(allGains, hiddenBoards);
        DataArray cappers = DataArray.empty();
        for (var c : stats.cappersBetween(guildId, from, to, 60).stream().filter(c -> shown(hiddenBoards, c.rsn())).limit(15).toList()) {
            cappers.add(DataObject.empty().put("rsn", c.rsn()).put("weeksCapped", c.weeksCapped()).put("totalCaps", c.totalCaps()));
        }

        DataArray joined = DataArray.empty();
        DataArray left = DataArray.empty();
        for (var event : rosterEvents.getRosterEventsInWindow(guildId, from, to)) {
            if (strict && !shown(hiddenBoards, event.rsn())) continue;
            (event.eventType().equals("JOIN") ? joined : left).add(event.rsn());
        }

        OffsetDateTime first = stats.firstSnapshotAt(guildId);
        DataArray months = DataArray.empty();
        if (first != null) {
            for (YearMonth m = YearMonth.from(first.withOffsetSameInstant(ZoneOffset.UTC)); !m.isAfter(current); m = m.plusMonths(1)) months.add(m.toString());
        }

        return DataObject.empty()
                .put("month", from.toString().substring(0, 7))
                .put("period", periodJson)
                .put("months", months)
                .put("totalXp", allGains.stream().mapToLong(Gain::xp).sum())
                .put("gainers", gainsJson(gains, 15))
                .put("cappers", cappers)
                .put("joined", joined)
                .put("left", left);
    }

    // ---------- the clan coffer (totals only) ----------

    DataObject coffer(long guildId) {
        return coffer(guildId, false);
    }

    /** @param strict a clan site's answer: the top donors leave out names hidden from the rankings (the totals stay whole) */
    DataObject coffer(long guildId, boolean strict) {
        var totals = stats.cofferTotals(guildId);
        Set<String> hiddenBoards = strict ? profiles.hiddenRsns(guildId, true) : Set.of();

        DataArray weeks = DataArray.empty();
        stats.cofferByWeek(guildId, CHART_WEEKS).forEach(w -> weeks.add(DataObject.empty().put("weekStart", w.weekStart().toString()).put("donated", w.donated())));
        DataArray donors = DataArray.empty();
        stats.topDonors(guildId, 10 + hiddenBoards.size()).stream().filter(d -> shown(hiddenBoards, d.name())).limit(10).forEach(d -> donors.add(DataObject.empty().put("name", d.name()).put("total", d.total()).put("donations", d.donations())));
        DataArray giveaways = DataArray.empty();
        stats.recentGiveaways(guildId, 8).forEach(g -> giveaways.add(DataObject.empty().put("amount", g.amount()).put("description", g.description()).put("at", g.at().toString())));

        return DataObject.empty()
                .put("donated", totals.donated()).put("donations", totals.donations()).put("donors", totals.donors())
                .put("held", totals.held()).put("giveaways", totals.giveaways()).put("givenAway", totals.givenAway())
                .put("weeks", weeks).put("topDonors", donors).put("recentGiveaways", giveaways);
    }

    // ---------- "my profile": which RuneScape names a logged-in Discord user has linked ----------

    /**
     * The RuneScape names linked to a Discord user. The website calls this only with the id from the visitor's
     * own verified login session, so it reveals a person's own link to themselves and to no one else.
     */
    DataObject me(Guild guild, String userIdRaw) {
        long userId;
        try {
            userId = Long.parseLong(userIdRaw == null ? "" : userIdRaw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        DataArray rsns = DataArray.empty();
        links.getLinksForUser(guild.getIdLong(), userId).forEach(link -> rsns.add(link.rsn()));
        var pending = links.getPendingAttemptForUser(guild.getIdLong(), userId);
        String state = rsns.length() > 0 ? "LINKED" : pending != null ? "PENDING" : "NONE";

        // Things worth telling this member about, from the last two weeks: how a link request ended, and goals they reached.
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(14);
        DataArray notices = DataArray.empty();
        var decision = links.latestDecision(guild.getIdLong(), userId, since);
        if (decision != null) {
            notices.add(DataObject.empty().put("id", "link-" + decision.attemptId()).put("kind", "LINK_" + decision.status()).put("rsn", decision.rsn()).put("at", decision.resolvedAt().toString()));
        }
        for (var goal : profiles.goalsFor(guild.getIdLong(), userId)) {
            if (goal.achievedAt() == null || goal.achievedAt().isBefore(since)) continue;
            notices.add(DataObject.empty().put("id", "goal-" + goal.id()).put("kind", "GOAL_REACHED").put("rsn", goal.rsn())
                    .put("skill", RuneScapeSkillCatalog.nameFor(goal.skillId())).put("level", goal.targetLevel()).put("at", goal.achievedAt().toString()));
        }
        return DataObject.empty().put("rsns", rsns).put("state", state).put("pendingRsn", pending == null ? null : pending.rsn()).put("notices", notices);
    }

    private static DataArray skillGainsJson(Map<Integer, Long> gains) {
        DataArray array = DataArray.empty();
        gains.entrySet().stream().sorted(Map.Entry.<Integer, Long>comparingByValue().reversed()).forEach(e -> array.add(
                DataObject.empty().put("skillId", e.getKey()).put("skill", RuneScapeSkillCatalog.nameFor(e.getKey())).put("xp", e.getValue())));
        return array;
    }

    private static String query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
