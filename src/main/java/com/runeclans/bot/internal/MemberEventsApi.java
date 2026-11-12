package com.runeclans.bot.internal;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.JoinLinks;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.events.BingoBoardView;
import com.runeclans.bot.events.BingoTemplate;
import com.runeclans.bot.events.EventListing;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventSpaceRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventStandings;
import com.runeclans.bot.events.EventRoster;
import com.runeclans.bot.events.EventSettings;
import com.runeclans.bot.events.PluginObjectiveMatcher;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.i18n.Text;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What a member sees of the events they are in, for their profile on the website ({@code GET /internal/me/events}): their place and
 * points, which objectives they have completed, whether their buy-in is paid, pending proof, and their prizes with where each stands.
 * Only the member's own rows are returned (other players' prizes and claims never are), and drafts and cancelled-before-start events
 * are left out. Also lists events taking signups that the member hasn't joined, so they can find them, and lets them join or leave one
 * ({@link #join}, {@link #leave}) by the same rules as {@code /events join}. Taking part in an event needs the member to be in the server's Discord.
 * <p>
 * The events are the hub's ({@link HubEvents}, T-932): the website's Event Manager events and the bot's alike, joined on the hub. The bot's old event
 * tables are no longer read here. A server with no clan on the hub has no events to show.
 */
@BService
public class MemberEventsApi {
    private static final Logger log = LoggerFactory.getLogger(MemberEventsApi.class);
    private static final int MAX_EVENTS = 30;
    /** How many of a member's events (and of the open ones) get the extra hub reads in one answer, and the time those reads may take. */
    static final int DETAILED = 10;
    static final java.time.Duration BUDGET = java.time.Duration.ofSeconds(4);

    private final HubEvents events;
    private final ClanProfileRepository profiles;
    private final EventSpaceRepository space;
    private final JoinLinks joinLinks;
    private final com.runeclans.bot.events.EventUpdateRepository updatePosts;

    public MemberEventsApi(HubEvents events, ClanProfileRepository profiles, EventSpaceRepository space, JoinLinks joinLinks,
                           com.runeclans.bot.events.EventUpdateRepository updatePosts) {
        this.joinLinks = joinLinks;
        this.updatePosts = updatePosts;
        this.events = events;
        this.profiles = profiles;
        this.space = space;
    }

    /** Signs the member up for an event, as the player (so the deadline, a closed signup, the team policy and the waitlist all apply). */
    public DataObject join(Guild guild, long userId, DataObject body) {
        long eventId = eventIdOf(body);
        requireInServer(guild, userId);
        var joined = events.join(guild.getIdLong(), eventId, userId, null, false, Actor.member(userId, guild.getIdLong()));
        return mine(guild, userId).put("result", DataObject.empty().put("waiting", joined.waiting()).put("position", joined.position()));
    }

    /** Takes the member out of an event, only while it is taking signups (the same rule as {@code /events leave}). */
    public DataObject leave(Guild guild, long userId, DataObject body) {
        long eventId = eventIdOf(body);
        long guildId = guild.getIdLong();
        Event event = events.event(guildId, eventId);
        if (event.status() != Status.SIGNUPS) throw new EventProblem(409, Text.of(guildId, "events.problem.you_can_only_leave_while_an"));
        events.leave(guildId, eventId, userId, Actor.member(userId, guildId));
        return mine(guild, userId);
    }

    private static long eventIdOf(DataObject body) {
        try {
            return Long.parseLong(body.getString("eventId", "").strip());
        } catch (NumberFormatException e) {
            throw new EventProblem(400, Text.en("events.problem.say_which_event"));
        }
    }

    /** Taking part needs the Discord, so someone who isn't in the server is told to join it, with the clan's link when it has one. */
    private void requireInServer(Guild guild, long userId) {
        Member member = DiscordLookups.memberOrSlow(guild, userId);
        if (member == null) throw new EventProblem(403, notInServerMessage(guild));
    }

    /** The sentence shown to someone who isn't in the server yet; its link is the server's one join link ({@link JoinLinks}), when there is one. */
    String notInServerMessage(Guild guild) {
        String link = joinLinks.of(guild.getIdLong(), profiles.get(guild.getIdLong()).map(p -> p.applyUrl()).orElse(null));
        return "To take part in an event you need to be in the " + guild.getName() + " Discord server. Join it first" + (link == null ? ", then sign up here again." : " (" + link + "), then sign up here again.");
    }

    public DataObject mine(Guild guild, long userId) {
        DataArray mine = DataArray.empty();
        DataArray open = DataArray.empty();
        // only people in the server see its events
        if (guild.getMemberById(userId) == null) return DataObject.empty().put("events", mine).put("open", open);

        long guildId = guild.getIdLong();
        List<HubEvents.Mine> joined;
        List<Event> taking;
        try {
            joined = events.mine(guildId, userId, MAX_EVENTS);
            taking = events.events(guildId, List.of(Status.SIGNUPS), 20);
        } catch (EventProblem e) {
            if (e.status() != 404) throw e;
            return DataObject.empty().put("events", mine).put("open", open); // no clan on the hub: no events
        }
        // the website gives up after 8 seconds: only the first DETAILED events, and only within BUDGET, get the extra hub reads (settings, standings,
        // prizes, proof); the rest are listed from the compact answers
        Instant until = Instant.now().plus(BUDGET);
        int detailed = 0;
        for (HubEvents.Mine m : joined) {
            if (m.me() == null) continue; // the hub listed it without the member's place
            if (!shownToMember(m.event(), m.takenDown())) continue;
            try {
                boolean full = detailed < DETAILED && Instant.now().isBefore(until);
                Event event = m.event();
                if (full) {
                    detailed++;
                    // the hub's list is compact: the settings (verification, buy-in, standings) come with the event read in full
                    event = events.full(m.event()).orElse(m.event());
                }
                mine.add(one(guildId, event, m.me(), userId, full));
            } catch (RuntimeException e) {
                log.warn("Could not build member view of event {}", m.event().id(), e);
            }
        }
        int openDetailed = 0;
        for (Event listed : taking) {
            if (joined.stream().anyMatch(j -> j.event().id() == listed.id())) continue;
            if (!shownToMember(listed, false)) continue;
            try {
                Event event = listed;
                Boolean closed = null;
                if (openDetailed < DETAILED && Instant.now().isBefore(until)) {
                    openDetailed++;
                    // one read gives the settings and whether signups are closed
                    Optional<HubEvents.Full> full = events.fullWithSetup(listed);
                    if (full.isPresent()) {
                        event = full.get().event();
                        closed = full.get().setup().signupsClosed();
                    }
                }
                if (takenDownIn(event)) continue; // the full read's settings say whether staff took it down
                EventSettings settings = settingsOf(event);
                open.add(DataObject.empty().put("id", Long.toString(event.id())).put("hubId", hubIdOf(event)).put("name", event.name()).put("description", event.description())
                        .put("template", event.template()).put("scoringMode", event.scoringMode().name())
                        .put("signupClosesAt", settings.signupClosesAt() == null ? null : settings.signupClosesAt().toString())
                        .put("closed", closed != null && closed)
                        .put("verification", settings.verification().required())
                        .put("buyIn", settings.buyIn().enabled() ? settings.buyIn().amount() : 0));
            } catch (RuntimeException e) {
                log.warn("Could not build the open event {} for a member", listed.id(), e);
            }
        }
        return DataObject.empty().put("events", mine).put("open", open);
    }

    /**
     * Whether a member's events page lists an event: announced ones only (signups, running, scoring, done), never a draft, a cancelled or archived
     * one, or one RuneClans staff took down; the same as the website's {@code shownOnSite}.
     */
    private static boolean takenDownIn(Event event) {
        try {
            return event.config() != null && !event.config().isBlank() && EventListing.takenDown(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean shownToMember(Event event, boolean takenDown) {
        if (takenDown || event.archived()) return false;
        return switch (event.status()) {
            case SIGNUPS, RUNNING, SCORING, DONE -> true;
            case DRAFT, CANCELLED -> false;
        };
    }

    /** How many rows of the standings a member is shown (the top of the table, plus their own row). */
    private static final int STANDINGS_SHOWN = 10;
    private static final int SUBMISSIONS_SHOWN = 20;

    /**
     * One event as its player sees it, for the website's event page ({@code GET /internal/me/events/detail?eventId=}): the schedule, rules and prizes, their
     * team and teammates, their progress (and their board for a bingo), the top of the standings, their own submissions with how each was decided, their
     * verification status, and links to the channels they can use. A member can open an event they are in, or one that is taking signups (to read it
     * and join); anything else, and any event of another server, is "not found". Other players' submissions, prizes and buy-ins are never included.
     * While XP tiles are counted (T-707), also the XP tiles ({@link #xpTiles}) and whether the last XP pass listed this player as not counted.
     */
    public DataObject detail(Guild guild, long userId, long eventId) {
        // about nine hub reads one after another (B-38): each tried once, all within one budget under the website's 8 seconds, so a slow hub is
        // a "try again" (503) the page can show instead of the website giving up on the bot
        return RuneClansHubClient.withinBudget(RuneClansHubClient.WEB_BUDGET, () -> detailNow(guild, userId, eventId));
    }

    private DataObject detailNow(Guild guild, long userId, long eventId) {
        long guildId = guild.getIdLong();
        if (guild.getMemberById(userId) == null) throw new EventProblem(403, notInServerMessage(guild));
        Event event = events.event(guildId, eventId);
        // one roster read gives the member's own place and the player count
        List<Participant> players = events.participants(eventId);
        Participant me = players.stream().filter(p -> p.userId() == userId).findFirst().orElse(null);
        if (me == null && event.status() != Status.SIGNUPS) throw new EventProblem(404, Text.of(guildId, "events.problem.that_event_doesn_t_exist_or"));
        EventSettings settings = settingsOf(event);
        var setup = events.setup(eventId);
        boolean teamEvent = event.scoringMode() == com.runeclans.bot.events.EventRules.ScoringMode.TEAM;
        boolean scored = event.status() == Status.RUNNING || event.status() == Status.SCORING || event.status() == Status.DONE;
        List<EventRepository.Team> teams = events.teams(eventId);
        var objectives = events.objectives(eventId);
        DataArray xpTiles = xpTiles(event, objectives);

        DataObject out = DataObject.empty();
        out.put("event", DataObject.empty().put("id", Long.toString(event.id())).put("hubId", hubIdOf(event)).put("name", event.name()).put("description", event.description()).put("template", event.template())
                .put("status", event.status().name()).put("scoringMode", event.scoringMode().name())
                .put("startsAt", event.startsAt() == null ? null : event.startsAt().toString()).put("endsAt", event.endsAt() == null ? null : event.endsAt().toString())
                .put("signupClosesAt", settings.signupClosesAt() == null ? null : settings.signupClosesAt().toString()).put("signupsClosed", setup.signupsClosed())
                .put("maxParticipants", event.maxParticipants()).put("players", (int) players.stream().filter(p -> !p.waiting()).count())
                .put("buyIn", DataObject.empty().put("enabled", settings.buyIn().enabled()).put("amount", settings.buyIn().amount()))
                .put("verificationRequired", settings.verification().required())
                .put("verificationInstructions", settings.verification().required() ? EventRoster.askText(settings.verification()) : ""));
        if (xpTiles != null) out.put("xpTiles", xpTiles); // off (the default): the answer is as before

        if (me == null) {
            out.put("me", (DataObject) null);
            out.put("prizes", prizes(event));
            return out;
        }

        String teamName = me.teamId() == null ? null : teams.stream().filter(t -> t.id() == me.teamId()).map(EventRepository.Team::name).findFirst().orElse(null);
        EventRoster.Verification verification = EventRoster.effective(me.verification(), settings.verification());
        List<EventRepository.Claim> claims = events.claimsOf(guildId, eventId, userId, SUBMISSIONS_SHOWN);
        out.put("me", DataObject.empty().put("waiting", me.waiting()).put("team", teamName)
                .put("buyIn", DataObject.empty().put("enabled", settings.buyIn().enabled()).put("amount", settings.buyIn().amount()).put("status", me.buyIn().name()))
                .put("verification", DataObject.empty().put("required", settings.verification().required()).put("state", verification.name())
                        .put("note", me.verification() == EventRoster.Verification.REJECTED ? me.verificationNote() : "")
                        .put("waitingProof", pending(claims, EventRepository.ClaimKind.VERIFICATION) > 0)));

        DataArray teammates = DataArray.empty();
        if (teamEvent && me.teamId() != null) {
            for (Participant p : players) {
                if (p.waiting() || !me.teamId().equals(p.teamId())) continue;
                teammates.add(DataObject.empty().put("name", displayName(guild, p.userId())).put("me", p.userId() == userId));
            }
        }
        out.put("teammates", teammates);

        List<Standing> standings = scored ? events.standings(guildId, eventId) : List.of();
        String subject = teamEvent ? (me.teamId() == null ? null : "t:" + me.teamId()) : "u:" + userId;
        Standing mineStanding = standings.stream().filter(s -> s.subject().equals(subject)).findFirst().orElse(null);
        EventStandings.Visibility visibility = EventStandings.of(DataObject.fromJson(event.config()));
        boolean shown = EventStandings.tableShown(visibility, event.status());
        DataArray rows = DataArray.empty();
        int myRank = 0;
        for (int i = 0; i < standings.size() && shown; i++) {
            Standing s = standings.get(i);
            boolean mine = s.subject().equals(subject);
            if (mine) myRank = i + 1;
            if (i < STANDINGS_SHOWN || mine) rows.add(DataObject.empty().put("rank", i + 1).put("name", standingName(guild, s.subject(), teams)).put("points", s.points()).put("me", mine));
        }
        // hidden: players still see their own points, never the table or where they rank
        out.put("standings", DataObject.empty().put("rows", rows).put("of", shown ? standings.size() : 0).put("myRank", myRank).put("myPoints", mineStanding == null ? 0 : mineStanding.points())
                .put("hidden", !shown).put("hiddenText", shown ? null : EventStandings.hiddenText(visibility)));

        DataArray progress = DataArray.empty();
        if (scored && !me.waiting()) {
            for (var o : objectives) {
                int done = mineStanding == null ? 0 : mineStanding.completions().getOrDefault(o.key(), 0);
                progress.add(DataObject.empty().put("key", o.key()).put("label", o.label()).put("points", o.points()).put("target", o.target()).put("repeatLimit", o.repeatLimit()).put("done", done));
            }
        }
        out.put("objectives", progress);

        DataObject config = DataObject.fromJson(event.config());
        if (scored && !me.waiting() && com.runeclans.bot.events.ClanVsClan.plays(event, BingoTemplate.KEY)) {
            var tiles = BingoBoardView.personal(BingoBoardView.boardKeys(config), objectives, mineStanding);
            DataArray tileJson = DataArray.empty();
            for (var t : tiles) tileJson.add(DataObject.empty().put("n", t.number()).put("label", t.label()).put("points", t.points()).put("done", t.done()).put("locked", t.locked()));
            int size = (int) Math.round(Math.sqrt(tiles.size()));
            boolean grid = size * size == tiles.size() && !tiles.isEmpty();
            out.put("board", DataObject.empty().put("tiles", tileJson).put("columns", grid ? size : 0));
        } else {
            out.put("board", (DataObject) null);
        }

        DataArray submissions = DataArray.empty();
        for (EventRepository.Claim c : claims) {
            String label = c.kind() == EventRepository.ClaimKind.VERIFICATION ? "Verification"
                    : objectives.stream().filter(o -> o.key().equals(c.objectiveKey())).map(o -> o.label()).findFirst().orElse(c.objectiveKey());
            submissions.add(DataObject.empty().put("id", Long.toString(c.id())).put("kind", c.kind().name()).put("label", label).put("status", c.status().name())
                    .put("note", c.note()).put("decisionNote", c.decisionNote()).put("createdAt", c.createdAt() == null ? null : c.createdAt().toString()));
        }
        out.put("submissions", submissions);

        out.put("prizes", prizes(event));

        out.put("awards", awards(guildId, event, userId));

        out.put("channels", channels(guild, eventId, me, teamEvent));
        out.put("canSubmit", !me.waiting() && event.status() == Status.RUNNING);
        out.put("updates", com.runeclans.bot.events.ClanVsClan.plays(event, BingoTemplate.KEY) ? updates(eventId, config, event.status()) : null);
        out.put("win", win(guild, eventId, config, teams, shown));
        return out;
    }

    /**
     * The XP tiles of a bingo or scavenger hunt counted from RuneSync (T-543), as their labels, so the page can say that only RuneSync XP counts there;
     * null while {@code EVENT_XP_TILES_ENABLED} is off or none can be shown yet ({@link PluginObjectiveMatcher#shownXpTiles}). The hub event page uses it too.
     */
    static DataArray xpTiles(Event event, List<com.runeclans.bot.events.EventRules.ObjectiveSpec> objectives) {
        if (!PluginObjectiveMatcher.xpTilesOffered()) return null;
        List<String> labels = PluginObjectiveMatcher.shownXpTiles(com.runeclans.bot.events.ClanVsClan.game(event), event.status(), objectives);
        return labels.isEmpty() ? null : DataArray.fromCollection(labels);
    }

    /**
     * How the event is won (T-649), when it isn't the usual "most points": the rules in words. Null for an event won on points, so the page looks as
     * before. The draw itself is the hub's since T-941 (the event's page on RuneClans shows it with its seed and entries).
     */
    private DataObject win(Guild guild, long eventId, DataObject config, List<EventRepository.Team> teams, boolean standingsShown) {
        var rules = com.runeclans.bot.events.WinModes.of(config);
        if (rules.isDefault()) return null;
        DataArray lines = DataArray.empty();
        com.runeclans.bot.events.WinModes.describe(rules).forEach(lines::add);
        return DataObject.empty().put("mode", rules.mode().name()).put("rules", lines).put("draw", (DataObject) null);
    }

    private DataArray prizes(Event event) {
        DataArray prizes = DataArray.empty();
        for (var p : events.prizes(event.id())) {
            prizes.add(DataObject.empty().put("place", p.place()).put("placeTo", p.placeTo()).put("kind", p.kind().name()).put("description", p.description()).put("amount", p.amount()));
        }
        return prizes;
    }

    /** Links to the channels this player can use: the shared ones and their own team's or player channel, when the event has set them up. */
    private DataArray channels(Guild guild, long eventId, Participant me, boolean teamEvent) {
        DataArray links = DataArray.empty();
        String own = teamEvent && me.teamId() != null ? "team:" + me.teamId() : "player:" + me.userId();
        var rows = space.channels(eventId);
        for (String key : new String[]{"announcements", "general", "board", own}) {
            for (var row : rows) {
                if (!row.key().equals(key) || guild.getGuildChannelById(row.channelId()) == null) continue;
                String label = switch (key) {
                    case "announcements" -> "Announcements";
                    case "general" -> "General chat";
                    case "board" -> "Live board";
                    default -> key.startsWith("team:") ? "Your team's channel" : "Your channel";
                };
                links.add(DataObject.empty().put("label", label).put("url", "https://discord.com/channels/" + guild.getId() + "/" + row.channelId()));
            }
        }
        return links;
    }

    private static String displayName(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? "Player" : member.getEffectiveName();
    }

    private static String standingName(Guild guild, String subject, List<EventRepository.Team> teams) {
        long id = Long.parseLong(subject.substring(2));
        if (subject.startsWith("t:")) return teams.stream().filter(t -> t.id() == id).map(EventRepository.Team::name).findFirst().orElse("A team");
        return displayName(guild, id);
    }

    private static EventSettings settingsOf(Event event) {
        try {
            return EventSettings.parse(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return EventSettings.defaults();
        }
    }

    /** The member's own prizes in a finished (or scoring) event, from the hub's list for the player. */
    private DataArray awards(long guildId, Event event, long userId) {
        DataArray awards = DataArray.empty();
        if (event.status() != Status.SCORING && event.status() != Status.DONE) return awards;
        for (DataObject a : events.awardsOf(guildId, event.id(), userId)) {
            awards.add(DataObject.empty().put("place", a.getInt("place", 0)).put("kind", a.getString("kind", "")).put("description", a.getString("description", ""))
                    .put("amount", a.getLong("amount", 0)).put("status", a.getString("status", "")));
        }
        return awards;
    }

    /** The event's id on the hub (a uuid), beside the bot's local {@code id}: what the website matches its own hub reads on, so an event isn't listed twice. */
    /** The hub's id for an event the bot names by local id, or null when it isn't a hub event. Also the directory cards' ({@link HubEventsApi}). */
    String hubIdOf(Event event) {
        try {
            return events.map().event(event.id()).map(r -> r.hubId().toString()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int pending(List<EventRepository.Claim> claims, EventRepository.ClaimKind kind) {
        return (int) claims.stream().filter(c -> c.kind() == kind && c.status() == EventRepository.ClaimStatus.PENDING).count();
    }

    /** One of the member's events; {@code detailed} false lists it from what the hub's compact answer had, without further hub reads. */
    private DataObject one(long guildId, Event event, Participant me, long userId, boolean detailed) {
        EventSettings settings = settingsOf(event);
        String teamName = me.teamId() == null || !detailed ? null : events.teams(event.id()).stream().filter(t -> t.id() == me.teamId()).map(EventRepository.Team::name).findFirst().orElse(null);

        boolean scored = event.status() == Status.RUNNING || event.status() == Status.SCORING || event.status() == Status.DONE;
        List<Standing> standings = scored && detailed ? events.standings(guildId, event.id()) : List.of();
        String subject = event.scoringMode() == com.runeclans.bot.events.EventRules.ScoringMode.TEAM ? (me.teamId() == null ? null : "t:" + me.teamId()) : "u:" + userId;
        int rank = 0;
        Standing mineStanding = null;
        boolean ranked = EventStandings.tableShown(EventStandings.of(DataObject.fromJson(event.config())), event.status());
        for (int i = 0; i < standings.size(); i++) {
            if (standings.get(i).subject().equals(subject)) {
                rank = ranked ? i + 1 : 0;
                mineStanding = standings.get(i);
            }
        }

        DataArray objectives = DataArray.empty();
        if (scored && detailed && !me.waiting()) {
            for (var o : events.objectives(event.id())) {
                int done = mineStanding == null ? 0 : mineStanding.completions().getOrDefault(o.key(), 0);
                objectives.add(DataObject.empty().put("key", o.key()).put("label", o.label()).put("points", o.points()).put("target", o.target())
                        .put("repeatLimit", o.repeatLimit()).put("done", done));
            }
        }

        DataArray awards = detailed ? awards(guildId, event, userId) : DataArray.empty();
        // proof waiting for staff only matters while the event can still take it
        boolean open = event.status() == Status.SIGNUPS || event.status() == Status.RUNNING;
        int pendingProof = open && detailed ? pending(events.claimsOf(guildId, event.id(), userId, SUBMISSIONS_SHOWN), EventRepository.ClaimKind.OBJECTIVE) : 0;

        return DataObject.empty().put("id", Long.toString(event.id())).put("hubId", hubIdOf(event)).put("name", event.name()).put("description", event.description()).put("template", event.template())
                .put("status", event.status().name()).put("scoringMode", event.scoringMode().name())
                .put("waiting", me.waiting()).put("team", teamName)
                .put("verification", DataObject.empty().put("required", settings.verification().required())
                        .put("state", EventRoster.effective(me.verification(), settings.verification()).name())
                        .put("note", me.verification() == EventRoster.Verification.REJECTED ? me.verificationNote() : "")
                        .put("instructions", settings.verification().required() ? EventRoster.askText(settings.verification()) : ""))
                .put("buyIn", DataObject.empty().put("enabled", settings.buyIn().enabled()).put("amount", settings.buyIn().amount()).put("status", me.buyIn().name()))
                .put("rank", rank).put("of", standings.size()).put("points", mineStanding == null ? 0 : mineStanding.points())
                .put("objectives", objectives).put("pendingProof", pendingProof).put("awards", awards);
    }

    /** How many of the event's recaps and milestone posts a player is shown. */
    static final int UPDATES_SHOWN = 8;

    /**
     * A bingo's board update rules in words and its latest recaps and milestones (T-650), for the player's event page. Null when the event uses today's
     * rules and has posted nothing, so the page shows nothing new.
     */
    private DataObject updates(long eventId, DataObject config, Status status) {
        var rules = com.runeclans.bot.events.BoardUpdates.of(config);
        var recent = updatePosts.recent(eventId, UPDATES_SHOWN);
        if (rules.isDefault() && recent.isEmpty()) return null;
        DataArray lines = DataArray.empty();
        boolean hidden = !com.runeclans.bot.events.EventStandings.tableShown(com.runeclans.bot.events.EventStandings.of(config), status);
        com.runeclans.bot.events.BoardUpdates.describe(rules, hidden).forEach(lines::add);
        DataArray posts = DataArray.empty();
        for (var p : recent) posts.add(DataObject.empty().put("key", p.key()).put("kind", p.kind()).put("text", p.text()).put("at", p.at().toString()));
        return DataObject.empty().put("mode", rules.mode().name()).put("rules", lines).put("posts", posts);
    }
}
