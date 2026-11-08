package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.clansite.SiteRoles;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.SeasonRules.Input;
import com.runeclans.bot.internal.SeasonRules.Row;
import com.runeclans.bot.internal.SeasonRules.Season;
import com.runeclans.bot.internal.SeasonRules.Weights;
import com.runeclans.bot.internal.SiteSeasonStore.Final;
import com.runeclans.bot.internal.SiteSeasonStore.Settings;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.member.MemberProfileRepository;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Clan seasons (T-596): quarterly seasons where XP gained, clan points and event wins add up to a season score ({@link SeasonRules}), with
 * the final standings kept at each season's end for the recap and the top three's badges. Off by default twice over: the bot's
 * {@code CLAN_SEASONS} switch and each site's own switch, which only the site's owner can turn on.
 * <ul>
 *   <li>{@code GET clan-site/seasons} (site admins): the settings, the season so far and the finished seasons. {@code canChange} says whether
 *   the asker may change the settings.</li>
 *   <li>{@code PUT clan-site/seasons/settings} {@code {enabled, weights: {xp, points, events}, post?: {on, channelId}}} (the site's owner):
 *   answers the page.</li>
 *   <li>The public answer ({@link DirectorySiteSeasonRoutes}): {@code GET sites/{slug}/data/season[?rsn=]}.</li>
 * </ul>
 * Reached through {@link SiteMembersAdmin} from {@link ClanSiteAdminApi}, which has already checked the site role, so bot-less sites get it
 * too ({@link BotlessAdminApi}); they score XP and clan points (event wins need the bot's events, which a bot-less server has none of).
 */
@BService
public class SiteSeasons {
    private static final Logger log = LoggerFactory.getLogger(SiteSeasons.class);
    static final String PATH = "seasons";
    /** The bot's switch: "on", "1" or "true" turns seasons on for the sites that want them. Off by default. */
    static final String SETTING_ENV = "CLAN_SEASONS";
    /** How long the public answer's live standings are kept. */
    static final long LIVE_TTL_MS = 5 * 60_000L;
    static final int MAX_CHANNELS = 200;
    static final int PAST_SEASONS = 8;

    private final SiteSeasonStore store;
    private final SiteStatsRepository stats;
    private final MemberProfileRepository profiles;
    private final EventService events;
    private final EventRepository eventRows;
    private final SiteRoleGuard siteRoles;
    private final DiscordOutbox outbox;
    private final TrackingPostingToggle posting;
    /** The bot's switch, read once at start; tests flip it. */
    boolean switchedOn;
    private final Map<Long, Cached> live = new ConcurrentHashMap<>();

    private record Cached(long at, String season, Weights weights, List<Row> rows) {}

    public SiteSeasons(SiteSeasonStore store, SiteStatsRepository stats, MemberProfileRepository profiles, EventService events, EventRepository eventRows,
                       SiteRoleGuard siteRoles, DiscordOutbox outbox, TrackingPostingToggle posting) {
        this.store = store;
        this.stats = stats;
        this.profiles = profiles;
        this.events = events;
        this.eventRows = eventRows;
        this.siteRoles = siteRoles;
        this.outbox = outbox;
        this.posting = posting;
        this.switchedOn = switchOn(System.getenv(SETTING_ENV));
    }

    static boolean switchOn(String raw) {
        return raw != null && List.of("on", "1", "true").contains(raw.strip().toLowerCase(Locale.ROOT));
    }

    // ---------- the standings ----------

    /** Each current member's XP gained, clan points and event wins inside the season, members hidden from the rankings left out. */
    List<Input> inputs(long guildId, Season season, OffsetDateTime now) {
        return inputs(guildId, season, now, false);
    }

    /**
     * {@link #inputs}; {@code keeping} (a close, whose standings are kept for good) throws when the hub didn't answer for the clan points
     * (T-978), so the close is tried again on the next run instead of freezing everyone at 0 points.
     */
    List<Input> inputs(long guildId, Season season, OffsetDateTime now, boolean keeping) {
        OffsetDateTime to = now.isBefore(season.to()) ? now : season.to();
        Set<String> hidden = profiles.hiddenRsns(guildId, true);
        Map<String, Input> byKey = new LinkedHashMap<>();
        for (SiteStatsRepository.MemberRow m : stats.members(guildId)) {
            String key = m.rsn().toLowerCase(Locale.ROOT);
            if (!hidden.contains(key)) byKey.put(key, new Input(m.rsn(), 0, 0, 0));
        }
        for (SiteStatsRepository.Gain g : stats.xpGainsBetween(guildId, season.from(), to)) {
            byKey.computeIfPresent(g.rsn().toLowerCase(Locale.ROOT), (k, in) -> new Input(in.rsn(), Math.max(0, g.xp()), in.points(), in.wins()));
        }
        for (SiteStatsRepository.PointsEarned p : keeping ? stats.pointsBetweenOrThrow(guildId, null, season.from(), to) : stats.pointsBetween(guildId, null, season.from(), to)) {
            byKey.computeIfPresent(p.rsn().toLowerCase(Locale.ROOT), (k, in) -> new Input(in.rsn(), in.xp(), Math.max(0, p.points()), in.wins()));
        }
        for (Map.Entry<String, Integer> w : wins(guildId, season.from(), to).entrySet()) {
            byKey.computeIfPresent(w.getKey(), (k, in) -> new Input(in.rsn(), in.xp(), in.points(), w.getValue()));
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Events won inside the window, per member (lower-case name): first place in an event that finished then (ties share it), every member of
     * a winning team. Only players whose Discord account is linked to a name on the roster can be counted.
     */
    Map<String, Integer> wins(long guildId, OffsetDateTime from, OffsetDateTime to) {
        Map<Long, Integer> byUser = new HashMap<>();
        for (long eventId : store.eventsFinishedBetween(guildId, from, to)) {
            try {
                for (long userId : winners(guildId, eventId)) byUser.merge(userId, 1, Integer::sum);
            } catch (RuntimeException e) {
                log.warn("Clan seasons: skipped event {} in server {}: {}", eventId, guildId, e.getMessage());
            }
        }
        Map<String, Integer> out = new HashMap<>();
        Map<Long, String> names = store.rosterNames(guildId, new ArrayList<>(byUser.keySet()));
        byUser.forEach((user, count) -> {
            String rsn = names.get(user);
            if (rsn != null) out.merge(rsn.toLowerCase(Locale.ROOT), count, Integer::sum);
        });
        return out;
    }

    /** The Discord accounts that won one event: everyone on the top line of its standings (with points), team members included. */
    List<Long> winners(long guildId, long eventId) {
        List<Standing> table = events.standings(guildId, eventId);
        long top = table.stream().mapToLong(Standing::points).max().orElse(0);
        if (top <= 0) return List.of();
        List<Long> out = new ArrayList<>();
        var participants = eventRows.participants(eventId);
        for (Standing s : table) {
            if (s.points() != top) continue;
            long subject = Long.parseLong(s.subject().substring(2));
            if (s.subject().startsWith("t:")) {
                eventRows.teams(eventId).stream().filter(t -> t.id() == subject && t.captainId() != null).forEach(t -> out.add(t.captainId()));
                participants.stream().filter(p -> p.teamId() != null && p.teamId() == subject && !p.waiting()).forEach(p -> out.add(p.userId()));
            } else {
                out.add(subject);
            }
        }
        return out.stream().distinct().toList();
    }

    /** The season so far, from a short cache (the public pages ask often; the inputs change slowly). */
    List<Row> liveStandings(long guildId, Season season, Weights weights, OffsetDateTime now) {
        Cached c = live.get(guildId);
        long t = System.currentTimeMillis();
        if (c != null && t - c.at() < LIVE_TTL_MS && c.season().equals(season.token()) && c.weights().equals(weights)) return c.rows();
        List<Row> rows = SeasonRules.standings(inputs(guildId, season, now), weights);
        live.put(guildId, new Cached(t, season.token(), weights, rows));
        return rows;
    }

    // ---------- the season's end ----------

    /**
     * Closes the season before {@code now}'s for every site with seasons on, once each: works out the final standings and keeps them. A site
     * that turned seasons on only after that season ended is left alone. Safe to run again and again (the store keeps the first close only),
     * and from two bot processes at once. Answers how many seasons it closed.
     */
    int closeFinished(JDA jda, OffsetDateTime now) {
        if (!switchedOn) return 0;
        Season ended = SeasonRules.seasonOf(now).previous();
        int closed = 0;
        for (Map.Entry<Long, Settings> e : store.enabled().entrySet()) {
            long guildId = e.getKey();
            Settings s = e.getValue();
            if (s.enabledAt() != null && !s.enabledAt().isBefore(ended.to())) continue;
            try {
                if (close(jda, guildId, ended, s, now)) closed++;
            } catch (RuntimeException ex) {
                log.error("Clan seasons: could not close season {} for server {}", ended.token(), guildId, ex);
            }
        }
        return closed;
    }

    /** Closes one season for one server; true when this call was the one that closed it (and so may post about it). */
    boolean close(JDA jda, long guildId, Season season, Settings settings, OffsetDateTime now) {
        if (store.finalOf(guildId, season.token()).isPresent()) return false;
        List<Row> rows = SeasonRules.standings(inputs(guildId, season, now, true), settings.weights());
        boolean fresh = store.finish(guildId, season.token(), settings.weights(), rows);
        if (!fresh) return false;
        log.info("Clan seasons: closed {} for server {} ({} members scored)", season.token(), guildId, rows.size());
        live.remove(guildId);
        post(jda, guildId, season, settings, rows);
        return true;
    }

    /** The optional season-end post (off by default): through the Discord job outbox, never from a dev bot with posting off, no pings. */
    private void post(JDA jda, long guildId, Season season, Settings settings, List<Row> rows) {
        if (!settings.postOn() || settings.postChannelId() == null || jda == null || !posting.isEnabled()) return;
        Guild guild = Shards.guild(jda, guildId);
        if (guild == null) return;
        String text = SeasonRules.postText(season, escaped(rows.stream().limit(SeasonRules.BADGE_PLACES).toList()));
        outbox.enqueue(SeasonPostJob.KIND, guildId, 0L, "season-end:" + guildId + ":" + season.token(), SeasonPostJob.payload(settings.postChannelId(), text));
    }

    private static List<Row> escaped(List<Row> rows) {
        return rows.stream().map(r -> new Row(r.place(), net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(r.rsn()), r.score(), r.xp(), r.points(), r.wins(),
                r.xpPts(), r.pointsPts(), r.winsPts())).toList();
    }

    // ---------- JSON ----------

    static DataObject seasonJson(Season s, OffsetDateTime now) {
        return DataObject.empty().put("token", s.token()).put("label", s.label()).put("from", s.from().toString()).put("to", s.to().toString())
                .put("days", s.days()).put("daysLeft", s.daysLeft(now)).put("recapPeriod", s.recapPeriod());
    }

    static DataObject weightsJson(Weights w) {
        return DataObject.empty().put("xp", w.xp()).put("points", w.points()).put("events", w.events());
    }

    static DataObject rowJson(Row r) {
        return DataObject.empty().put("place", r.place()).put("rsn", r.rsn()).put("score", r.score()).put("xp", r.xp()).put("points", r.points())
                .put("wins", r.wins()).put("xpPts", r.xpPts()).put("pointsPts", r.pointsPts()).put("winsPts", r.winsPts());
    }

    static DataArray rowsJson(List<Row> rows, int limit, Set<String> hidden) {
        DataArray out = DataArray.empty();
        rows.stream().filter(r -> !hidden.contains(r.rsn().toLowerCase(Locale.ROOT))).limit(limit).forEach(r -> out.add(rowJson(r)));
        return out;
    }

    /**
     * What a clan site shows ({@code sites/{slug}/data/season}): {@code {enabled: false}} while either switch is off; otherwise the season so
     * far, the weights, the standings and the last finished season's podium. With {@code rsn}, that member's season badges too. Members hidden
     * from the rankings never show, a finished season's included.
     */
    DataObject publicAnswer(long guildId, String rsn, OffsetDateTime now) {
        Settings s = store.settings(guildId);
        if (!switchedOn || !s.enabled()) return DataObject.empty().put("enabled", false);
        Season season = SeasonRules.seasonOf(now);
        Set<String> hidden = profiles.hiddenRsns(guildId, true);
        List<Row> rows = liveStandings(guildId, season, s.weights(), now);
        DataObject answer = DataObject.empty().put("enabled", true).put("season", seasonJson(season, now)).put("weights", weightsJson(s.weights()))
                .put("scored", rows.size()).put("standings", rowsJson(rows, SeasonRules.MAX_ROWS, hidden)).put("last", null);
        List<Final> finals = store.finals(guildId, 1);
        if (!finals.isEmpty()) {
            Final f = finals.getFirst();
            Season last = SeasonRules.parse(f.season());
            if (last != null) {
                answer.put("last", seasonJson(last, now).put("finishedAt", f.finishedAt() == null ? null : f.finishedAt().toString())
                        .put("weights", weightsJson(f.weights())).put("scored", f.members())
                        .put("standings", rowsJson(store.standings(guildId, f.season(), 10 + hidden.size()), 10, hidden)));
            }
        }
        if (rsn != null && !rsn.isBlank()) answer.put("badges", badgesJson(guildId, rsn.strip(), hidden));
        return answer;
    }

    DataArray badgesJson(long guildId, String rsn, Set<String> hidden) {
        DataArray out = DataArray.empty();
        if (hidden.contains(rsn.toLowerCase(Locale.ROOT))) return out;
        for (Map.Entry<String, Row> e : store.badges(guildId, rsn)) {
            Season s = SeasonRules.parse(e.getKey());
            String title = SeasonRules.badgeTitle(e.getValue().place());
            if (s == null || title == null) continue;
            out.add(DataObject.empty().put("season", s.token()).put("label", s.label()).put("place", e.getValue().place()).put("title", title)
                    .put("score", e.getValue().score()));
        }
        return out;
    }

    // ---------- the Site Admin's Seasons page ----------

    /** The site role a route needs: changing the settings is the owner's; reading the page any site admin's. */
    static SiteRoles.Action actionFor(String method, String[] parts) {
        return parts.length == 3 && parts[2].equals("settings") && !method.equals("GET") ? SiteRoles.Action.OWN : SiteRoles.Action.SETTINGS;
    }

    /** {@code clan-site/seasons...}; null for a path it doesn't serve. */
    DataObject route(SiteCaller c, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 2 || !parts[1].equals(PATH)) return null;
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "GET");
            return view(c, OffsetDateTime.now(ZoneOffset.UTC));
        }
        if (parts.length == 3 && parts[2].equals("settings")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(c, body.get());
        }
        return null;
    }

    DataObject view(SiteCaller c, OffsetDateTime now) {
        long guildId = c.guildId();
        Settings s = store.settings(guildId);
        boolean owner = c.may(siteRoles, SiteRoles.Action.OWN);
        Season season = SeasonRules.seasonOf(now);
        DataObject post = DataObject.empty().put("available", !c.botless()).put("on", s.postOn() && !c.botless())
                .put("channelId", s.postChannelId() == null ? null : Long.toString(s.postChannelId()))
                .put("channelName", channelName(c, s.postChannelId()))
                .put("channels", owner && !c.botless() ? channels(c.guild()) : DataArray.empty());
        DataObject answer = DataObject.empty()
                .put("available", switchedOn)
                .put("enabled", s.enabled())
                .put("enabledAt", s.enabledAt() == null ? null : s.enabledAt().toString())
                .put("weights", weightsJson(s.weights()))
                .put("post", post)
                .put("canChange", owner)
                .put("botPresent", !c.botless())
                .put("season", seasonJson(season, now))
                .put("limits", DataObject.empty().put("maxWeight", SeasonRules.MAX_WEIGHT).put("badgePlaces", SeasonRules.BADGE_PLACES))
                .put("standings", DataArray.empty())
                .put("scored", 0);
        if (switchedOn) {
            // the preview with the saved weights, whether or not the site shows it yet
            Set<String> hidden = profiles.hiddenRsns(guildId, true);
            List<Row> rows = liveStandings(guildId, season, s.weights(), now);
            answer.put("standings", rowsJson(rows, 10, hidden)).put("scored", rows.size());
        }
        DataArray past = DataArray.empty();
        for (Final f : store.finals(guildId, PAST_SEASONS)) {
            Season p = SeasonRules.parse(f.season());
            if (p == null) continue;
            past.add(seasonJson(p, now).put("finishedAt", f.finishedAt() == null ? null : f.finishedAt().toString()).put("scored", f.members())
                    .put("weights", weightsJson(f.weights())).put("podium", rowsJson(store.standings(guildId, f.season(), SeasonRules.BADGE_PLACES), SeasonRules.BADGE_PLACES, Set.of())));
        }
        return answer.put("past", past).put("generatedAt", now.toString());
    }

    DataObject save(SiteCaller c, DataObject body) {
        if (!switchedOn) throw new ApiError(409, "Clan seasons aren't turned on for this bot yet.");
        List<String> problems = new ArrayList<>();
        if (!body.hasKey("enabled") || !body.isType("enabled", DataType.BOOLEAN)) problems.add("Say whether seasons are on.");
        int xp = 1, points = 1, wins = 1;
        if (!body.hasKey("weights") || !body.isType("weights", DataType.OBJECT)) problems.add("Send the three weights.");
        else {
            DataObject w = body.getObject("weights");
            xp = weight(w, "xp", problems);
            points = weight(w, "points", problems);
            wins = weight(w, "events", problems);
            if (problems.isEmpty()) {
                String problem = SeasonRules.weightsProblem(xp, points, wins);
                if (problem != null) problems.add(problem);
            }
        }
        Settings current = store.settings(c.guildId());
        boolean postOn = current.postOn();
        Long channelId = current.postChannelId();
        if (body.hasKey("post") && !body.isNull("post")) {
            if (!body.isType("post", DataType.OBJECT)) problems.add("The season-end post's settings aren't readable.");
            else {
                DataObject p = body.getObject("post");
                postOn = p.getBoolean("on", false);
                String raw = p.isNull("channelId") ? "" : p.getString("channelId", "");
                channelId = null;
                if (!raw.isBlank()) {
                    if (!raw.matches("\\d{15,20}")) problems.add("That channel isn't readable.");
                    else channelId = Long.parseLong(raw);
                }
                if (postOn) {
                    if (c.botless()) problems.add("The season-end post needs the bot in your Discord server.");
                    else if (channelId == null) problems.add("Pick a channel for the season-end post.");
                    else {
                        GuildMessageChannel ch = c.guild().getChannelById(GuildMessageChannel.class, channelId);
                        if (ch == null) problems.add("That channel isn't in your server.");
                        else if (!ch.canTalk()) problems.add("The bot can't post in #" + ch.getName() + ". Give it permission to view and send messages there.");
                    }
                }
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), problems.stream().distinct().toList());
        store.save(c.guildId(), body.getBoolean("enabled"), new Weights(xp, points, wins), postOn, channelId, c.userId());
        live.remove(c.guildId());
        return view(c, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static int weight(DataObject w, String key, List<String> problems) {
        if (!w.hasKey(key) || !w.isType(key, DataType.INT)) {
            problems.add("Each weight is a whole number from 0 to " + SeasonRules.MAX_WEIGHT + ".");
            return 0;
        }
        return w.getInt(key);
    }

    private static String channelName(SiteCaller c, Long id) {
        if (id == null || c.botless()) return null;
        GuildMessageChannel ch = c.guild().getChannelById(GuildMessageChannel.class, id);
        return ch == null ? null : ch.getName();
    }

    /** Text channels the bot can post in, from Discord's cache (never a request). */
    private static DataArray channels(Guild guild) {
        DataArray out = DataArray.empty();
        int n = 0;
        for (TextChannel ch : guild.getTextChannels()) {
            if (!ch.canTalk()) continue;
            out.add(DataObject.empty().put("id", ch.getId()).put("name", ch.getName()));
            if (++n >= MAX_CHANNELS) break;
        }
        return out;
    }

    /** Drops the server's seasons with its deleted website. */
    void forget(long guildId) {
        store.forget(guildId);
        live.remove(guildId);
    }
}
