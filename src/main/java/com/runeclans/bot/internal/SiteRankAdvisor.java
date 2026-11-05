package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.clansite.SiteRoles;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.CockpitRules.CapWeek;
import com.runeclans.bot.internal.RankAdvisorRules.Check;
import com.runeclans.bot.internal.RankAdvisorRules.Due;
import com.runeclans.bot.internal.RankAdvisorRules.Facts;
import com.runeclans.bot.internal.RankAdvisorRules.Hold;
import com.runeclans.bot.internal.RankAdvisorRules.Pending;
import com.runeclans.bot.internal.RankAdvisorRules.Promoted;
import com.runeclans.bot.internal.RankAdvisorRules.Ready;
import com.runeclans.bot.internal.RankAdvisorRules.Rule;
import com.runeclans.bot.internal.RankAdvisorRules.Tick;
import com.runeclans.bot.internal.SiteRankStore.Congrats;
import com.runeclans.bot.internal.SiteRankStore.Week;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The rank-up advisor (T-594) on the site admin's Clan health page, reached through {@link SiteCockpitAdmin} (so through
 * {@link ClanSiteAdminApi}, and {@link BotlessAdminApi} for bot-less sites), which has already checked the site role each route needs:
 * <ul>
 *   <li>{@code GET clan-site/cockpit/ranks} (site admins): the owner's rules per rank, who meets the rules for their next rank, the Pending
 *   review and Ignored lists, who staff ticked off lately, the weekly update (who was due and got the rank, and its congrats message) and
 *   the weekly post's settings. {@code canChangeRules} says whether the asker may change the rules and the post.</li>
 *   <li>{@code PUT clan-site/cockpit/ranks/rules} {@code {rules: [{rank, minXp, minDays, minCaps, minPoints, staffCheck}], congrats?: {on,
 *   channelId, text}}} (the site's owner): replaces every rule at once, and the weekly post's settings; answers the page.</li>
 *   <li>{@code POST clan-site/cockpit/ranks/ticks} {@code {rsn, rank, done, name?}} (site admins): staff ranked the member in game (or take
 *   that back); answers {@code {tick, job, congratsProblem}} ({@code job} and {@code congratsProblem} always null since the weekly post).</li>
 *   <li>{@code POST clan-site/cockpit/ranks/holds} {@code {rsn, hold: "pending" | "ignored" | null, name?}} (site admins): puts a member on
 *   Pending review or Ignore indefinitely, or back on the normal list; answers {@code {hold}}.</li>
 * </ul>
 * The weekly update (Drake's T-594 answers): each promotion week ({@link RankAdvisorRules#weekOf}) the bot notes who is due (ready, meeting
 * the numbers on Pending review, or ticked); when the week turns, everyone due before it who has the rank now is congratulated. The page
 * shows that message to copy; with the owner's auto post on, {@link #sweep} posts it once through the Discord job outbox, never from a bot
 * that isn't live, with no pings. Only the sweep (every half hour) turns the week and notes who is due; loading the page writes nothing.
 * <p>
 * It only advises: the bot can't change an in-game rank, and never says it did. Read-only on Discord apart from the queued weekly post.
 */
@BService
public class SiteRankAdvisor {
    private static final Logger log = LoggerFactory.getLogger(SiteRankAdvisor.class);
    static final String PATH = "ranks";
    /** Channels offered for the congrats post, at most. */
    static final int MAX_CHANNELS = 200;

    private final SiteRankStore store;
    private final SiteCockpitStore cockpit;
    private final SiteStatsRepository stats;
    private final AdminToolsStore activity;
    private final SiteRoleGuard siteRoles;
    private final DiscordOutbox outbox;
    private final TrackingPostingToggle posting;
    /** Ticks per person: a burst of 10 (working down a list), then 2 a second. */
    private final RateLimiter ticks = new RateLimiter(10, 500);

    /** The weekly auto post goes out at most this long after the week turned (a bot that was off for days doesn't post a stale week). */
    static final Duration POST_WINDOW = Duration.ofDays(2);

    public SiteRankAdvisor(SiteRankStore store, SiteCockpitStore cockpit, SiteStatsRepository stats, AdminToolsStore activity, SiteRoleGuard siteRoles,
                           DiscordOutbox outbox, TrackingPostingToggle posting) {
        this.store = store;
        this.cockpit = cockpit;
        this.stats = stats;
        this.activity = activity;
        this.siteRoles = siteRoles;
        this.outbox = outbox;
        this.posting = posting;
    }

    /** The site role a route needs: the rules are the owner's (Drake's planning answer); seeing the list and ticking is any site admin's. */
    static SiteRoles.Action actionFor(String method, String[] parts) {
        return parts.length == 4 && parts[3].equals("rules") && !method.equals("GET") ? SiteRoles.Action.OWN : SiteRoles.Action.SETTINGS;
    }

    /** {@code clan-site/cockpit/ranks...}; null for a path it doesn't serve. */
    DataObject route(SiteCaller c, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 3 || !parts[2].equals(PATH)) return null;
        if (parts.length == 3) {
            AdminRouteExtension.requireMethod(method, "GET");
            return view(c, OffsetDateTime.now(ZoneOffset.UTC));
        }
        if (parts.length != 4) return null;
        return switch (parts[3]) {
            case "rules" -> {
                AdminRouteExtension.requireMethod(method, "PUT");
                yield saveRules(c, body.get());
            }
            case "ticks" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield tick(c, body.get());
            }
            case "holds" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield hold(c, body.get());
            }
            default -> null;
        };
    }

    void forget(long guildId) {
        store.forget(guildId);
    }

    // ---------- the page ----------

    /** Each roster member's facts, caps included when they can be counted ({@code caps} null for everyone when not). */
    List<Facts> facts(long guildId, OffsetDateTime now, boolean withCaps) {
        LocalDate thisWeek = RecapPeriod.citadelWeekStart(now.toLocalDate());
        Map<String, Integer> capped = Map.of();
        Map<String, OffsetDateTime> lastLog = Map.of();
        if (withCaps) {
            List<CapWeek> grid = stats.citadelGrid(guildId, RankAdvisorRules.CAP_WINDOW_WEEKS).stream()
                    .map(w -> new CapWeek(w.rsn(), w.weekStart(), w.capped())).toList();
            capped = RankAdvisorRules.capsByMember(grid, thisWeek);
            lastLog = activity.lastActivity(guildId);
        }
        List<Facts> out = new ArrayList<>();
        for (SiteRankStore.Row r : store.roster(guildId)) {
            String key = CockpitRules.keyOf(r.rsn());
            Integer caps = withCaps ? RankAdvisorRules.capsOf(key, capped, lastLog, thisWeek) : null;
            out.add(new Facts(r.rsn(), r.rank(), r.xpGained(), r.rankSince(), r.joined(), caps, r.points()));
        }
        return out;
    }

    /** Everything the page and the weekly sweep read (for the sweep, after the week's bookkeeping: the week turned when due, who is due noted). */
    record State(Map<String, Rule> rules, List<Tick> ticks, List<Hold> holds, List<Facts> members, List<Ready> ready, List<Pending> pending,
                 Map<String, Long> links, LocalDate week, Week latest) {}

    /**
     * Reads the advisor's data. With {@code bookkeeping} (the weekly sweep only, never a page load) it also turns the promotion week over when
     * it is due (once) and notes everyone due now; an empty roster (a sync outage) turns nothing. {@code guild} null: no Discord names.
     */
    State state(long guildId, Guild guild, OffsetDateTime now, boolean bookkeeping) {
        boolean capsOn = true;
        Map<String, Rule> rules = store.rules(guildId);
        List<Tick> ticks = store.ticks(guildId);
        List<Hold> holds = store.holds(guildId);
        List<Facts> members = facts(guildId, now, capsOn);
        Map<String, Long> links = guild == null ? Map.of() : cockpit.links(guildId);
        Set<String> held = new HashSet<>();
        Set<String> ignored = new HashSet<>();
        for (Hold h : holds) {
            held.add(h.rsnKey());
            if (h.ignored()) ignored.add(h.rsnKey());
        }
        LocalDate week = RankAdvisorRules.weekOf(now);
        if (bookkeeping && !members.isEmpty() && !store.turned(guildId, week)) {
            RankAdvisorRules.Turn turn = RankAdvisorRules.turn(week, store.due(guildId), members, ticks, ignored, discordNames(guild, links));
            if (store.turn(guildId, week, turn) && !turn.promoted().isEmpty()) {
                log.info("Rank-up advisor: the promotion week {} turned over in server {} ({} promoted)", week, guildId, turn.promoted().size());
            }
        }
        List<Ready> ready = RankAdvisorRules.ready(members, rules, ticks, now, capsOn, held);
        List<Pending> pending = RankAdvisorRules.pending(members, rules, ticks, holds, now, capsOn);
        List<Due> due = new ArrayList<>();
        for (Ready r : ready) due.add(new Due(r.member().key(), r.member().rsn(), r.member().rank(), r.target(), week));
        for (Pending p : pending) {
            if (p.meets()) due.add(new Due(p.member().key(), p.member().rsn(), p.member().rank(), p.next().target(), week));
        }
        if (bookkeeping) store.noteDue(guildId, due);
        return new State(rules, ticks, holds, members, ready, pending, links, week, store.latestWeek(guildId));
    }

    /** Linked members' Discord names (the server's display name), from Discord's cache; none for a bot-less site. */
    private static Map<String, String> discordNames(Guild guild, Map<String, Long> links) {
        Map<String, String> out = new HashMap<>();
        if (guild == null) return out;
        links.forEach((key, id) -> {
            net.dv8tion.jda.api.entities.Member m = guild.getMemberById(id);
            if (m != null) out.put(key, m.getEffectiveName());
        });
        return out;
    }

    DataObject view(SiteCaller c, OffsetDateTime now) {
        long guildId = c.guildId();
        boolean capsOn = true;
        State st = state(guildId, c.botless() ? null : c.guild(), now, false);
        Map<String, Rule> rules = st.rules();
        Map<String, Facts> byKey = new HashMap<>();
        for (Facts f : st.members()) byKey.put(f.key(), f);
        Map<String, Long> links = st.links();

        DataArray ready = DataArray.empty();
        for (Ready r : st.ready()) {
            DataArray checks = DataArray.empty();
            for (Check k : r.checks()) checks.add(checkJson(k));
            ready.add(person(c, r.member().rsn(), links.get(r.member().key())).put("rank", r.member().rank()).put("target", r.target())
                    .put("rankSince", iso(r.member().rankSince())).put("checks", checks).put("staffCheck", staffCheck(rules, r.target())));
        }

        DataArray pending = DataArray.empty();
        for (Pending p : st.pending()) {
            DataArray checks = DataArray.empty();
            String target = p.next() == null ? null : p.next().target();
            if (p.next() != null) for (Check k : p.next().checks()) checks.add(checkJson(k));
            pending.add(person(c, p.member().rsn(), links.get(p.member().key())).put("rank", p.member().rank()).put("target", target)
                    .put("checks", checks).put("meets", p.meets()).put("staffCheck", target == null ? null : staffCheck(rules, target))
                    .put("heldBy", Long.toString(p.hold().by())).put("heldByName", p.hold().byName()).put("heldAt", iso(p.hold().at())));
        }

        DataArray ignored = DataArray.empty();
        for (Hold h : st.holds()) {
            if (!h.ignored()) continue;
            Facts f = byKey.get(h.rsnKey());
            ignored.add(DataObject.empty().put("rsn", f == null ? h.rsn() : f.rsn()).put("rank", f == null ? null : f.rank()).put("inClan", f != null)
                    .put("heldBy", Long.toString(h.by())).put("heldByName", h.byName()).put("heldAt", iso(h.at())));
        }

        DataArray ticked = DataArray.empty();
        OffsetDateTime showFrom = now.minusDays(RankAdvisorRules.TICK_SHOW_DAYS);
        for (Tick t : st.ticks()) {
            if (t.at() == null || t.at().isBefore(showFrom)) continue;
            Facts f = byKey.get(t.rsnKey());
            if (f == null) continue; // left the clan since
            boolean inGame = RankAdvisorRules.indexOf(f.rank()) >= RankAdvisorRules.indexOf(t.rank());
            ticked.add(DataObject.empty().put("rsn", f.rsn()).put("rank", f.rank()).put("target", t.rank()).put("inGame", inGame)
                    .put("by", Long.toString(t.by())).put("byName", t.byName()).put("at", iso(t.at())));
        }

        DataArray ruleRows = DataArray.empty();
        for (Rule r : rules.values()) ruleRows.add(ruleJson(r));

        boolean owner = c.may(siteRoles, SiteRoles.Action.OWN);
        Congrats congrats = store.congrats(guildId);
        DataObject congratsJson = DataObject.empty().put("available", !c.botless()).put("on", congrats.on() && !c.botless())
                .put("channelId", congrats.channelId() == null ? null : Long.toString(congrats.channelId()))
                .put("channelName", channelName(c, congrats.channelId()))
                .put("text", congrats.text() == null ? RankAdvisorRules.DEFAULT_CONGRATS : congrats.text())
                .put("defaultText", RankAdvisorRules.DEFAULT_CONGRATS)
                .put("channels", owner ? channels(c.guild()) : DataArray.empty());

        OffsetDateTime counted = st.members().stream().map(Facts::rankSince).filter(t -> t != null).min(OffsetDateTime::compareTo).orElse(null);
        DataArray ladder = DataArray.empty();
        RankAdvisorRules.LADDER.forEach(ladder::add);
        DataArray targets = DataArray.empty();
        RankAdvisorRules.TARGETS.forEach(targets::add);
        return DataObject.empty()
                .put("ladder", ladder)
                .put("targets", targets)
                .put("limits", DataObject.empty().put("capWindowWeeks", RankAdvisorRules.CAP_WINDOW_WEEKS).put("maxXp", RankAdvisorRules.MAX_XP)
                        .put("maxDays", RankAdvisorRules.MAX_DAYS).put("maxPoints", RankAdvisorRules.MAX_POINTS)
                        .put("tickShowDays", RankAdvisorRules.TICK_SHOW_DAYS).put("maxCongrats", RankAdvisorRules.MAX_CONGRATS)
                        .put("maxStaffCheck", RankAdvisorRules.MAX_STAFF_CHECK))
                .put("rules", ruleRows)
                .put("caps", capsJson(capsOn))
                .put("canChangeRules", owner)
                .put("botPresent", !c.botless())
                .put("rosterSize", st.members().size())
                .put("xpCountedSince", iso(counted))
                .put("ready", ready)
                .put("pending", pending)
                .put("ignored", ignored)
                .put("ticked", ticked)
                .put("weekly", weeklyJson(c, st, congrats, now))
                .put("congrats", congratsJson)
                .put("generatedAt", now.toString());
    }

    private static String staffCheck(Map<String, Rule> rules, String rank) {
        Rule r = rules.get(rank);
        return r == null ? null : r.staffCheck();
    }

    /**
     * The weekly update card: this week, when the next turn is, how many are due now, and the latest turned week with its congrats message
     * (to copy) and whether the auto post went out.
     */
    DataObject weeklyJson(SiteCaller c, State st, Congrats congrats, OffsetDateTime now) {
        Week latest = st.latest();
        OffsetDateTime nextTurn = st.week().plusWeeks(1).atStartOfDay().atOffset(ZoneOffset.UTC).plus(RankAdvisorRules.WEEK_TURN_DELAY);
        int dueNow = st.ready().size() + (int) st.pending().stream().filter(Pending::meets).count();
        DataObject out = DataObject.empty().put("week", st.week().toString()).put("nextTurnAt", nextTurn.toString()).put("dueNow", dueNow)
                .put("latest", null);
        if (latest == null) return out;
        List<Promoted> named = RankAdvisorRules.withNames(latest.promoted(), discordNames(c.botless() ? null : c.guild(), st.links()));
        DataArray promoted = DataArray.empty();
        for (Promoted p : named) {
            promoted.add(DataObject.empty().put("rsn", p.rsn()).put("from", p.fromRank()).put("to", p.toRank()).put("discordName", p.discordName()));
        }
        DataObject posted = latest.postedAt() == null ? null : DataObject.empty().put("at", latest.postedAt().toString())
                .put("jobId", latest.jobId() == null ? null : Long.toString(latest.jobId()));
        return out.put("latest", DataObject.empty().put("weekStart", latest.start().toString()).put("turnedAt", iso(latest.turnedAt()))
                .put("current", latest.start().equals(st.week())).put("promoted", promoted)
                .put("text", RankAdvisorRules.weeklyText(congrats.text(), named)).put("posted", posted));
    }

    // ---------- the weekly sweep ----------

    /**
     * For every server with rank rules: does the week's bookkeeping (so the week turns over even when nobody opens the page) and, with the
     * owner's auto post on, posts the week's congrats once. Answers how many posts it queued.
     */
    int sweep(JDA jda, OffsetDateTime now) {
        int posted = 0;
        for (long guildId : store.serversWithRules()) {
            try {
                Guild guild = jda == null ? null : Shards.guild(jda, guildId);
                State st = state(guildId, guild, now, true);
                if (post(guild, guildId, st, now)) posted++;
            } catch (RuntimeException e) {
                log.warn("Rank-up advisor: the weekly update failed for server {}", guildId, e);
            }
        }
        return posted;
    }

    /** The week's auto post: only when turned on, for this week's turn, with someone to congratulate, from a live bot, once. */
    boolean post(Guild guild, long guildId, State st, OffsetDateTime now) {
        Week latest = st.latest();
        if (guild == null || latest == null || !latest.start().equals(st.week()) || latest.postedAt() != null || latest.promoted().isEmpty()) return false;
        if (latest.turnedAt() != null && latest.turnedAt().plus(POST_WINDOW).isBefore(now)) return false;
        Congrats congrats = store.congrats(guildId);
        if (!congrats.on() || congrats.channelId() == null || !posting.isEnabled()) return false;
        GuildMessageChannel ch = guild.getChannelById(GuildMessageChannel.class, congrats.channelId());
        if (ch == null || !ch.canTalk()) {
            log.info("Rank-up advisor: the weekly congrats post in server {} has no channel the bot can post in, so nothing was posted", guildId);
            return false;
        }
        if (!store.claimPost(guildId, latest.start())) return false;
        String text = RankAdvisorRules.weeklyText(congrats.text(), RankAdvisorRules.withNames(latest.promoted(), discordNames(guild, st.links())));
        DiscordJob job = outbox.enqueue(RankCongratsJob.KIND, guildId, 0L, "rankweek:" + guildId + ":" + latest.start(),
                RankCongratsJob.payload(ch.getIdLong(), text));
        store.saveJob(guildId, latest.start(), job.id());
        log.info("Rank-up advisor: queued the weekly congrats post for server {} (job {})", guildId, job.id());
        return true;
    }

    /** One part of a rule as the page shows it; {@code unavailable} true: not counted (Citadel caps once RuneMetrics is off). */
    static DataObject checkJson(Check k) {
        return DataObject.empty().put("what", k.what()).put("need", k.need()).put("have", k.have()).put("met", k.met())
                .put("unavailable", k.unavailable());
    }

    /** Whether rule caps parts are counted; when not, the page says so on the rules card and each row (T-725). */
    static DataObject capsJson(boolean available) {
        return DataObject.empty().put("available", available).put("reason", null);
    }

    static DataObject ruleJson(Rule r) {
        return DataObject.empty().put("rank", r.rank()).put("minXp", r.minXp()).put("minDays", r.minDays()).put("minCaps", r.minCaps())
                .put("minPoints", r.minPoints()).put("staffCheck", r.staffCheck());
    }

    private static DataObject person(SiteCaller c, String rsn, Long discordId) {
        DataObject row = DataObject.empty().put("rsn", rsn);
        if (discordId == null || c.botless()) return row.put("discordId", null).put("discordName", null);
        net.dv8tion.jda.api.entities.Member m = c.guild().getMemberById(discordId);
        return row.put("discordId", Long.toString(discordId)).put("discordName", m == null ? null : m.getEffectiveName());
    }

    private static String channelName(SiteCaller c, Long id) {
        if (id == null || c.botless()) return null;
        GuildMessageChannel ch = c.guild().getChannelById(GuildMessageChannel.class, id);
        return ch == null ? null : ch.getName();
    }

    /** Text channels the bot can post in, from Discord's cache (never a request). */
    private static DataArray channels(Guild guild) {
        DataArray out = DataArray.empty();
        if (guild == null) return out;
        int n = 0;
        for (TextChannel ch : guild.getTextChannels()) {
            if (!ch.canTalk()) continue;
            out.add(DataObject.empty().put("id", ch.getId()).put("name", ch.getName()));
            if (++n >= MAX_CHANNELS) break;
        }
        return out;
    }

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    // ---------- writes ----------

    /** The rules a save names, checked: known ranks only, each once, whole numbers in range. Problems are added in words. */
    static List<Rule> parseRules(DataObject body, List<String> problems) {
        if (!body.hasKey("rules") || !body.isType("rules", DataType.ARRAY)) {
            problems.add("Send the rules as a list.");
            return List.of();
        }
        DataArray raw = body.getArray("rules");
        List<Rule> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            if (!raw.isType(i, DataType.OBJECT)) {
                problems.add("A rule isn't readable.");
                continue;
            }
            DataObject r = raw.getObject(i);
            String rank = RankAdvisorRules.canonical(r.getString("rank", ""));
            if (rank == null || !RankAdvisorRules.TARGETS.contains(rank)) {
                problems.add("\"" + r.getString("rank", "") + "\" isn't a rank a rule can be set for.");
                continue;
            }
            if (!seen.add(rank)) {
                problems.add(rank + " has two rules.");
                continue;
            }
            int before = problems.size();
            Long xp = RankAdvisorRules.number(value(r, "minXp"), RankAdvisorRules.MAX_XP, rank + ": XP gained", problems);
            Long days = RankAdvisorRules.number(value(r, "minDays"), RankAdvisorRules.MAX_DAYS, rank + ": days in the clan", problems);
            Long caps = RankAdvisorRules.number(value(r, "minCaps"), RankAdvisorRules.CAP_WINDOW_WEEKS, rank + ": Citadel caps", problems);
            Long points = RankAdvisorRules.number(value(r, "minPoints"), RankAdvisorRules.MAX_POINTS, rank + ": clan points", problems);
            if (problems.size() > before) continue;
            String check = r.isNull("staffCheck") ? null : RankAdvisorRules.cleanStaffCheck(r.getString("staffCheck", ""));
            out.add(new Rule(rank, xp, days == null ? null : days.intValue(), caps == null ? null : caps.intValue(), points, check));
        }
        return out;
    }

    private static Object value(DataObject o, String key) {
        if (!o.hasKey(key) || o.isNull(key)) return null;
        if (o.isType(key, DataType.INT)) return o.getLong(key);
        return o.get(key);
    }

    DataObject saveRules(SiteCaller c, DataObject body) {
        List<String> problems = new ArrayList<>();
        List<Rule> rules = parseRules(body, problems);
        Congrats congrats = null;
        if (body.hasKey("congrats") && !body.isNull("congrats")) {
            if (!body.isType("congrats", DataType.OBJECT)) problems.add("The congrats settings aren't readable.");
            else congrats = parseCongrats(c, body.getObject("congrats"), problems);
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), problems);
        store.saveRules(c.guildId(), rules, c.userId());
        if (congrats != null) store.saveCongrats(c.guildId(), congrats, c.userId());
        return view(c, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** The weekly congrats post's settings: turning auto post on needs the bot in the server and a channel it can post in. */
    static Congrats parseCongrats(SiteCaller c, DataObject o, List<String> problems) {
        boolean on = o.getBoolean("on", false);
        String text = RankAdvisorRules.cleanCongrats(o.getString("text", ""));
        Long channelId = null;
        String raw = o.isNull("channelId") ? "" : o.getString("channelId", "");
        if (!raw.isBlank()) {
            if (!raw.matches("\\d{15,20}")) problems.add("That channel isn't readable.");
            else channelId = Long.parseLong(raw);
        }
        if (on) {
            if (c.botless()) problems.add("The congrats post needs the bot in your Discord server.");
            else if (channelId == null) problems.add("Pick a channel for the congrats post.");
            else {
                GuildMessageChannel ch = c.guild().getChannelById(GuildMessageChannel.class, channelId);
                if (ch == null) problems.add("That channel isn't in your server.");
                else if (!ch.canTalk()) problems.add("The bot can't post in #" + ch.getName() + ". Give it permission to view and send messages there.");
            }
        }
        return new Congrats(on, channelId, text);
    }

    DataObject tick(SiteCaller c, DataObject body) {
        if (!ticks.tryAcquire(Long.toString(c.userId()))) throw new ApiError(429, Text.of(c.guildId(), "dashboard.you_re_doing_that_too_fast"));
        String rsn = body.getString("rsn", "");
        String rank = RankAdvisorRules.canonical(body.getString("rank", ""));
        if (rank == null || !RankAdvisorRules.TARGETS.contains(rank)) throw refused(c, "That isn't a rank this list ticks off.");
        if (!CockpitRules.plausibleName(rsn) || !cockpit.knownName(c.guildId(), rsn)) throw refused(c, "That name isn't on your clan's roster.");
        if (!body.hasKey("done") || !body.isType("done", DataType.BOOLEAN)) throw refused(c, "Say whether they were ranked up.");
        String key = CockpitRules.keyOf(rsn);
        if (!body.getBoolean("done")) {
            store.untick(c.guildId(), key, rank);
            return DataObject.empty().put("tick", null).put("job", null).put("congratsProblem", null);
        }
        String current = rosterRank(c.guildId(), key);
        if (current != null && RankAdvisorRules.indexOf(current) >= RankAdvisorRules.indexOf(rank)) {
            throw refused(c, "The clan Hiscores already show " + rsn.strip() + " at " + current + " or higher. Reload the page.");
        }
        String name = SiteCockpitAdmin.displayName(c, body.getString("name", ""));
        store.tick(c.guildId(), key, rank, c.userId(), name);
        // ranked up from Pending review: off that list; and due for the rank, so next week's update congratulates them
        if (store.holds(c.guildId()).stream().anyMatch(h -> h.rsnKey().equals(key) && RankAdvisorRules.PENDING.equals(h.kind()))) {
            store.hold(c.guildId(), key, rsn.strip(), null, c.userId(), name);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        store.noteDue(c.guildId(), List.of(new Due(key, rosterName(c.guildId(), rsn), current == null ? "" : current, rank, RankAdvisorRules.weekOf(now))));
        DataObject tick = DataObject.empty().put("rank", rank).put("by", Long.toString(c.userId())).put("byName", name).put("at", now.toString());
        return DataObject.empty().put("tick", tick).put("job", null).put("congratsProblem", null);
    }

    /** Pending review, Ignore indefinitely, or back on the normal list (Drake's T-594 answer). Ignoring never pauses points or days. */
    DataObject hold(SiteCaller c, DataObject body) {
        if (!ticks.tryAcquire(Long.toString(c.userId()))) throw new ApiError(429, Text.of(c.guildId(), "dashboard.you_re_doing_that_too_fast"));
        String rsn = body.getString("rsn", "");
        if (!CockpitRules.plausibleName(rsn)) throw refused(c, "That name isn't on your clan's roster.");
        String key = CockpitRules.keyOf(rsn);
        String kind = body.isNull("hold") ? null : body.getString("hold", "");
        if (kind != null && !kind.equals(RankAdvisorRules.PENDING) && !kind.equals(RankAdvisorRules.IGNORED)) throw refused(c, "That isn't a list a member can go on.");
        // taking someone off a list works after they left the clan too; putting them on one needs them on the roster
        boolean listed = store.holds(c.guildId()).stream().anyMatch(h -> h.rsnKey().equals(key));
        if (kind != null && !cockpit.knownName(c.guildId(), rsn)) throw refused(c, "That name isn't on your clan's roster.");
        if (kind == null && !listed) return DataObject.empty().put("hold", null);
        String name = SiteCockpitAdmin.displayName(c, body.getString("name", ""));
        store.hold(c.guildId(), key, kind == null ? rsn.strip() : rosterName(c.guildId(), rsn), kind, c.userId(), name);
        log.info("Rank-up advisor: {} set the hold of a member in server {} to {}", c.userId(), c.guildId(), kind == null ? "none" : kind);
        if (kind == null) return DataObject.empty().put("hold", null);
        return DataObject.empty().put("hold", DataObject.empty().put("kind", kind).put("by", Long.toString(c.userId())).put("byName", name)
                .put("at", OffsetDateTime.now(ZoneOffset.UTC).toString()));
    }

    /** The member's in-game rank on the roster, or null. */
    private String rosterRank(long guildId, String key) {
        for (SiteRankStore.Row r : store.roster(guildId)) if (CockpitRules.keyOf(r.rsn()).equals(key)) return r.rank();
        return null;
    }

    /** The name as the roster spells it (the website may send any case). */
    private String rosterName(long guildId, String rsn) {
        String key = CockpitRules.keyOf(rsn);
        for (SiteRankStore.Row r : store.roster(guildId)) if (CockpitRules.keyOf(r.rsn()).equals(key)) return r.rsn();
        return rsn.strip();
    }

    private static ApiError refused(SiteCaller c, String problem) {
        return new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of(problem));
    }
}
