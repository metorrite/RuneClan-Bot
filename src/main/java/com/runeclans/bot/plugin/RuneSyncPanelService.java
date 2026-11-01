package com.runeclans.bot.plugin;

import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.discord.DiscordLookups.MemberLookup;
import com.runeclans.bot.discord.DiscordLookups.Outcome;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.events.BingoBoardImage;
import com.runeclans.bot.events.BingoBoardView;
import com.runeclans.bot.events.BingoTemplate;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.points.PointsRepository;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The data behind RuneSync's optional in-game clan panel (RUNESYNC-DESIGN.md sections 7 and 9, ticket T-206): for each of the member's
 * clans that has switched the panel on (dashboard, Clan plugin page; off by default), its upcoming and running events, the member's own
 * points, and the member's own progress in the running events they are in.
 * <p>
 * Data flows only <b>into</b> the client, and only what the member could already see: events are listed only for servers whose Discord
 * the member is in (the rule the profile's event list uses), and the only points and progress in it are the member's own. Answers are
 * cached 60 seconds per member, so a panel left open costs the bot almost nothing.
 * <p>
 * Since T-142/T-143 a bingo also carries its board (the member's own in a solo event, the team's in a team event), and a team event
 * carries "team status": the teammates' names as the server shows them (the event's team page shows the same) and, only for those who
 * switched on the Presence category, whether they are in the game with RuneSync now. Nobody outside the member's own team is named.
 */
@BService
public class RuneSyncPanelService {
    private static final Logger log = LoggerFactory.getLogger(RuneSyncPanelService.class);
    public static final Duration CACHE = Duration.ofSeconds(60);
    static final int EVENTS_PER_CLAN = 10;

    private final RuneSyncRepository repository;
    private final EventRepository events;
    private final EventService eventService;
    private final PointsRepository points;
    private final PluginRepository plugins;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private volatile JDA jda;

    private record Cached(Instant at, DataObject answer) {}

    /**
     * T-812: how long a known answer to "which of the member's clans allow the panel" is reused. The member lookup goes to Discord for
     * anyone not in the bot's member cache (which holds online members only), so it is kept a while; an answer Discord was too slow to
     * give is never kept.
     */
    static final Duration CLANS_CACHE = Duration.ofMinutes(5);

    /** The member's panel clans, and whether Discord was too slow to answer for some of them (then the list may be short). */
    record Clans(List<Guild> guilds, boolean unsure) {}

    /** What the panel needs from Discord; a seam so tests need no gateway. */
    interface Discord {
        /** Every server the bot is in. */
        List<Guild> guilds();

        /** The member in each of {@code guilds}, asked together within one short time limit. */
        List<MemberLookup> members(List<Guild> guilds, long userId);
    }

    private record KnownClans(Instant at, List<Long> guildIds) {}

    private final Map<Long, KnownClans> knownClans = new ConcurrentHashMap<>();
    private volatile Discord discord = new Discord() {
        @Override
        public List<Guild> guilds() {
            JDA current = jda;
            return current == null ? List.of() : Shards.guilds(current);
        }

        @Override
        public List<MemberLookup> members(List<Guild> guilds, long userId) {
            return DiscordLookups.memberInEach(guilds, userId, DiscordLookups.BUDGET);
        }
    };

    public RuneSyncPanelService(RuneSyncRepository repository, EventRepository events, EventService eventService, PointsRepository points,
                                PluginRepository plugins) {
        this.repository = repository;
        this.events = events;
        this.eventService = eventService;
        this.points = points;
        this.plugins = plugins;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    /** For tests: Discord as a stand-in. */
    void discord(Discord stand) {
        this.discord = stand;
    }

    /**
     * Whether any of the member's servers allows the panel: RuneSync's config says {@code panelAvailable} from this. {@code null} when
     * Discord was too slow to say and nothing is known yet ("unknown", never "not allowed": T-812).
     */
    public Boolean available(long userId) {
        Clans clans = clans(userId, Instant.now());
        if (!clans.guilds().isEmpty()) return true;
        return clans.unsure() ? null : false;
    }

    /**
     * The member's clans (servers the bot shares with them) that have switched the in-game panel on. Since T-812 this asks Discord for
     * members the bot's cache doesn't hold (anyone offline or Invisible), all panel servers at once within {@link DiscordLookups#BUDGET},
     * so a website or plugin request never waits long. A known answer is reused for {@link #CLANS_CACHE}; when Discord is slow, the last
     * known answer stands, and a slow answer is never remembered.
     */
    Clans clans(long userId, Instant now) {
        KnownClans known = knownClans.get(userId);
        if (known != null && known.at().plus(CLANS_CACHE).isAfter(now)) return new Clans(guildsById(known.guildIds()), false);
        java.util.Set<Long> panelOn = repository.clanPanelGuilds();
        if (panelOn.isEmpty()) return remember(userId, now, List.of());
        List<Guild> candidates = new ArrayList<>();
        for (Guild guild : discord.guilds()) if (panelOn.contains(guild.getIdLong())) candidates.add(guild);
        if (candidates.isEmpty()) return remember(userId, now, List.of());
        List<MemberLookup> found = discord.members(candidates, userId);
        List<Guild> on = new ArrayList<>();
        boolean slow = false;
        for (int i = 0; i < candidates.size() && i < found.size(); i++) {
            Outcome outcome = found.get(i).outcome();
            if (outcome == Outcome.FOUND) on.add(candidates.get(i));
            else if (outcome == Outcome.SLOW) slow = true;
        }
        if (!slow) return remember(userId, now, on);
        if (known != null) {
            // Discord was slow: the last known answer stands (plus any server found just now), and nothing new is remembered.
            List<Guild> merged = new ArrayList<>(guildsById(known.guildIds()));
            for (Guild g : on) if (!known.guildIds().contains(g.getIdLong())) merged.add(g);
            return new Clans(merged, merged.isEmpty());
        }
        return new Clans(on, true);
    }

    private Clans remember(long userId, Instant now, List<Guild> on) {
        knownClans.put(userId, new KnownClans(now, on.stream().map(Guild::getIdLong).toList()));
        if (knownClans.size() > 5_000) knownClans.entrySet().removeIf(e -> e.getValue().at().plus(CLANS_CACHE).isBefore(now));
        return new Clans(on, false);
    }

    private List<Guild> guildsById(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        List<Guild> out = new ArrayList<>();
        for (Guild g : discord.guilds()) if (ids.contains(g.getIdLong())) out.add(g);
        return out;
    }

    /** The panel's data for this member, at most 60 seconds old. */
    public DataObject panel(long userId, Instant now) {
        Cached hit = cache.get(userId);
        if (hit != null && hit.at().plus(CACHE).isAfter(now)) return hit.answer();
        Clans found = clans(userId, now);
        DataArray clans = DataArray.empty();
        for (Guild guild : found.guilds()) {
            try {
                clans.add(clan(guild.getIdLong(), guild.getName(), userId));
            } catch (RuntimeException e) {
                log.warn("Couldn't build the RuneSync panel for server {}", guild.getId(), e);
            }
        }
        boolean unknown = clans.isEmpty() && found.unsure();
        DataObject answer = DataObject.empty().put("available", unknown ? null : !clans.isEmpty()).put("clans", clans).put("generatedAt", now.toString())
                .put("cacheSeconds", CACHE.toSeconds());
        // T-812: an answer Discord was too slow for is not kept, so the next ask tries again
        if (!found.unsure()) cache.put(userId, new Cached(now, answer));
        if (cache.size() > 5_000) cache.entrySet().removeIf(e -> e.getValue().at().plus(CACHE).isBefore(now));
        return answer;
    }

    /** One clan: its open and running events, the member's own points, and the member's own progress. */
    DataObject clan(long guildId, String name, long userId) {
        DataArray list = DataArray.empty();
        DataArray progress = DataArray.empty();
        for (Event event : events.events(guildId, List.of(Status.RUNNING, Status.SIGNUPS), EVENTS_PER_CLAN)) {
            if (event.archived()) continue;
            Participant me = events.participant(event.id(), userId).orElse(null);
            boolean joined = me != null && !me.waiting();
            list.add(DataObject.empty().put("id", Long.toString(event.id())).put("name", event.name()).put("template", event.template())
                    .put("status", event.status().name()).put("startsAt", event.startsAt() == null ? null : event.startsAt().toString())
                    .put("endsAt", event.endsAt() == null ? null : event.endsAt().toString()).put("joined", joined));
            if (joined && event.status() == Status.RUNNING) progress.add(progress(guildId, event, me));
        }
        PointsRepository.Settings pointSettings = points.settings(guildId);
        DataObject own = pointSettings.enabled()
                ? DataObject.empty().put("enabled", true).put("balance", points.balance(guildId, userId)).put("unit", pointSettings.unit())
                : DataObject.empty().put("enabled", false);
        return DataObject.empty().put("name", name).put("events", list).put("points", own).put("progress", progress);
    }

    /** The member's own place in one running event: their (or their team's) points, objectives done, and bingo tiles done. */
    private DataObject progress(long guildId, Event event, Participant me) {
        boolean team = event.scoringMode() == ScoringMode.TEAM;
        String subject = team ? (me.teamId() == null ? null : "t:" + me.teamId()) : "u:" + me.userId();
        Standing mine = subject == null ? null
                : eventService.standings(guildId, event.id()).stream().filter(s -> s.subject().equals(subject)).findFirst().orElse(null);
        var objectives = eventService.objectives(event.id());
        int done = 0;
        for (var o : objectives) if (mine != null && mine.completions().getOrDefault(o.key(), 0) > 0) done++;
        DataObject out = DataObject.empty().put("eventId", Long.toString(event.id())).put("name", event.name()).put("team", team)
                .put("points", mine == null ? 0 : mine.points()).put("objectivesDone", done).put("objectivesTotal", objectives.size());
        if (com.runeclans.bot.events.ClanVsClan.plays(event, BingoTemplate.KEY)) {
            DataObject config = DataObject.fromJson(event.config());
            var tiles = BingoBoardView.personal(BingoBoardView.boardKeys(config), objectives, mine);
            DataObject bingo = DataObject.empty().put("tilesDone", (int) tiles.stream().filter(t -> t.done()).count()).put("tiles", tiles.size());
            // T-142: the board itself, so RuneSync can draw it. In a team event the board the member plays is their team's
            // (standings are per team there), so it is sent as teamBoard; in a solo event it is the member's own board.
            bingo.put(team ? "teamBoard" : "board", board(tiles, BingoBoardView.boardSize(config)));
            out.put("bingo", bingo);
        } else {
            out.put("bingo", (DataObject) null);
        }
        if (team && me.teamId() != null) out.put("teamStatus", teamStatus(guildId, event.id(), me.teamId()));
        return out;
    }

    /** A board for the client: its width and each tile's label and whether it is done (tiles in reading order). */
    static DataObject board(List<BingoBoardImage.TileView> tiles, int size) {
        DataArray cells = DataArray.empty();
        for (var t : tiles) cells.add(DataObject.empty().put("label", t.label()).put("done", t.done()).put("locked", t.locked()));
        int width = size > 0 ? size : (int) Math.ceil(Math.sqrt(Math.max(1, tiles.size())));
        return DataObject.empty().put("size", width).put("cells", cells);
    }

    /** How long after a check-in a teammate still counts as in the game with RuneSync (RuneSync checks in about every 10 minutes). */
    static final Duration IN_GAME_WINDOW = Duration.ofMinutes(11);

    /**
     * T-143, the lite party window: the member's event team, each teammate named as clanmates see them in the server, and whether they are in
     * the game with RuneSync now. "In game" is only told for teammates who switched on the Presence category (their own consent, off by
     * default); for the others it is null ("not shared"). No location, no combat stats, no gear: only what the team page already shows plus
     * the opted-in presence.
     */
    DataObject teamStatus(long guildId, long eventId, long teamId) {
        String teamName = events.teams(eventId).stream().filter(t -> t.id() == teamId).map(EventRepository.Team::name).findFirst().orElse("");
        DataArray members = DataArray.empty();
        Instant now = Instant.now();
        for (Participant p : events.participants(eventId)) {
            if (p.waiting() || p.teamId() == null || p.teamId() != teamId) continue;
            Boolean inGame = null;
            if (plugins.consent(p.userId()).contains(PluginCategory.PRESENCE)) {
                inGame = repository.clientStatus(p.userId()).map(s -> s.lastSeen() != null && s.lastSeen().isAfter(now.minus(IN_GAME_WINDOW))).orElse(false);
            }
            members.add(DataObject.empty().put("name", displayName(guildId, p.userId())).put("inGame", inGame));
        }
        return DataObject.empty().put("name", teamName).put("members", members);
    }

    /** A teammate's name as the clan sees it in the server, or "A teammate" when the bot can't tell. */
    String displayName(long guildId, long userId) {
        JDA current = jda;
        Guild guild = current == null ? null : Shards.guild(current, guildId);
        var member = guild == null ? null : guild.getMemberById(userId);
        return member == null ? "A teammate" : member.getEffectiveName();
    }

    /** The clan switch changed: cached answers for anyone may be wrong now. */
    public void forget() {
        cache.clear();
        knownClans.clear();
    }
}
