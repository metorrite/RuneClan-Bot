package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.alliance.AllianceRepository;
import com.runeclans.bot.alliance.AllianceRules;
import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.JoinLinks;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.events.EventCohostRepository;
import com.runeclans.bot.events.EventListing;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRoster;
import com.runeclans.bot.events.EventSettings;
import com.runeclans.bot.events.EventStandings;
import com.runeclans.bot.events.HubBoard;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.events.EventListing.Access;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventTemplate;
import com.runeclans.bot.events.EventTemplates;
import com.runeclans.bot.events.HubModerationRepository;
import com.runeclans.bot.events.PrizePlan;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.runeclans.bot.config.BotBrand;

/**
 * The events hub's engine events (T-216, design {@code docs/EVENTS-HUB.md}), reached through {@link DirectoryApi} ({@code /internal/directory/hub-events}),
 * so only the shared secret is checked: the website says who is looking ({@code viewer}, a Discord id it signed in, or nothing). Everyone sees events their
 * host opened to everyone; members of an allied server also see alliance events; members of the host server see their own server's open events marked as
 * theirs. Never a player's name or Discord id, apart from standings an event shows on purpose (RuneScape names only).
 */
@BService
public class HubEventsApi {
    private static final long LIST_TTL_MILLIS = 30_000;
    private static final int MAX_SHARED = 200;
    private static final int STANDINGS_SHOWN = 10;

    private final EventRepository events;
    private final EventService service;
    private final EventTemplates templates;
    private final JoinLinks joinLinks;
    private final ClanProfileService profiles;
    private final AllianceRepository alliances;
    private final PlayerLinkService links;
    private final HubModerationRepository moderation;
    private final TtlCache cache = new TtlCache();
    static final int MIN_REASON = 10;
    static final int MAX_REASON = 500;
    static final int MAX_REPORTS_PER_EVENT_PER_DAY = 20;

    private final EventCohostRepository cohosts;
    private final DashboardAccess dashboardAccess;
    private final MemberEventsApi memberEvents;
    static final int MINE_SHOWN = 50;
    private static final int MEMBER_EVENTS_PER_SERVER = 50;

    public HubEventsApi(EventRepository events, EventService service, EventTemplates templates, JoinLinks joinLinks, ClanProfileService profiles,
                        AllianceRepository alliances, PlayerLinkService links, HubModerationRepository moderation, EventCohostRepository cohosts,
                        DashboardAccess dashboardAccess, MemberEventsApi memberEvents) {
        this.cohosts = cohosts;
        this.dashboardAccess = dashboardAccess;
        this.memberEvents = memberEvents;
        this.moderation = moderation;
        this.events = events;
        this.service = service;
        this.templates = templates;
        this.joinLinks = joinLinks;
        this.profiles = profiles;
        this.alliances = alliances;
        this.links = links;
    }

    /** Every listed event the viewer may see, soonest first, each with how they may see it. */
    public DataObject list(JDA jda, String viewer) {
        List<Event> shared = cache.get("shared", LIST_TTL_MILLIS, () -> {
            Set<Long> barred = moderation.barredIds();
            return events.sharedBeyondServer(MAX_SHARED).stream().filter(e -> EventListing.listed(e, Instant.now()) && !barred.contains(e.guildId())).toList();
        });
        Long viewerId = idOf(viewer);
        Set<Long> viewerGuilds = guildsOf(jda, viewerId);
        DataArray out = DataArray.empty();
        Map<Long, Boolean> alliedCache = new LinkedHashMap<>();
        for (Event e : shared) {
            Access access = accessOf(e, viewerGuilds, alliedCache);
            if (access == null) continue;
            out.add(card(jda, e).put("access", access.name()));
        }
        // "Only this server" events of the viewer's own servers: only they see them (T-335)
        Instant now = Instant.now();
        for (long guildId : viewerGuilds) {
            for (Event e : events.events(guildId, MEMBER_STATUSES, MEMBER_EVENTS_PER_SERVER)) {
                if (e.scope() == com.runeclans.bot.events.EventRules.Scope.PRIVATE && EventListing.shownToMembers(e, now)) out.add(card(jda, e).put("access", Access.MINE.name()));
            }
        }
        return DataObject.empty().put("events", out).put("generatedAt", now.toString());
    }

    private static final List<Status> MEMBER_STATUSES = List.of(Status.SIGNUPS, Status.RUNNING, Status.SCORING, Status.DONE);

    /**
     * "My events" on RuneClans (T-337): every event the viewer plays in, in any server, newest first, each with their own place in it (waiting, team,
     * verification, points and rank when the event shows ranks to its players). Only the viewer's own rows; nothing about other players.
     */
    public DataObject mine(JDA jda, String viewer) {
        Long viewerId = idOf(viewer);
        DataArray out = DataArray.empty();
        if (viewerId == null) return DataObject.empty().put("events", out);
        for (Event e : events.eventsForUserEverywhere(viewerId, MINE_SHOWN)) {
            Participant me = events.participant(e.id(), viewerId).orElse(null);
            if (me == null) continue;
            out.add(card(jda, e).put("access", Access.MINE.name()).put("me", myPlace(e, me)));
        }
        return DataObject.empty().put("events", out);
    }

    /** The viewer's own place in an event: no other player's name or row. */
    private DataObject myPlace(Event e, Participant me) {
        boolean teamEvent = e.scoringMode() == com.runeclans.bot.events.EventRules.ScoringMode.TEAM;
        String team = me.teamId() == null ? null : events.teams(e.id()).stream().filter(t -> t.id() == me.teamId()).map(EventRepository.Team::name).findFirst().orElse(null);
        EventSettings settings = settingsOf(e);
        boolean scored = e.status() == Status.RUNNING || e.status() == Status.SCORING || e.status() == Status.DONE;
        String subject = teamEvent ? (me.teamId() == null ? null : "t:" + me.teamId()) : "u:" + me.userId();
        int rank = 0;
        long points = 0;
        int of = 0;
        if (scored && subject != null) {
            List<Standing> all = service.standings(e.guildId(), e.id());
            boolean ranks = EventListing.standingsShownToMember(e, true);
            for (int i = 0; i < all.size(); i++) {
                if (!all.get(i).subject().equals(subject)) continue;
                points = all.get(i).points();
                if (ranks) rank = i + 1;
            }
            if (ranks) of = all.size();
        }
        return DataObject.empty().put("waiting", me.waiting()).put("team", team).put("teamEvent", teamEvent)
                .put("verification", EventRoster.effective(me.verification(), settings.verification()).name())
                .put("verificationRequired", settings.verification().required())
                .put("rank", rank).put("of", of).put("points", points)
                .put("canSubmit", !me.waiting() && e.status() == Status.RUNNING);
    }

    /** One listed event, with its full description, prizes and (when the host allowed it) standings; null when the viewer may not see it. */
    public DataObject one(JDA jda, String rawId, String viewer) {
        if (rawId == null || !rawId.matches("\\d{1,18}")) return null;
        Optional<Event> found = events.eventById(Long.parseLong(rawId));
        if (found.isEmpty()) return null;
        Event e = found.get();
        Long viewerId = idOf(viewer);
        Set<Long> viewerGuilds = guildsOf(jda, viewerId);
        boolean member = viewerGuilds.contains(e.guildId());
        // members of the host server also see its "Only this server" events (and events taken off the public hub) on RuneClans (T-335)
        if (!(listedNow(e) || (member && EventListing.shownToMembers(e, Instant.now())))) return null;
        Access access = accessOf(e, viewerGuilds, new LinkedHashMap<>());
        if (access == null) return null;
        Participant me = viewerId == null ? null : events.participant(e.id(), viewerId).orElse(null);
        DataObject card = card(jda, e).put("access", access.name()).put("description", e.description() == null ? "" : e.description());
        DataArray prizes = DataArray.empty();
        for (PrizePlan.Prize p : service.prizes(e.id())) {
            prizes.add(DataObject.empty().put("place", p.place()).put("placeTo", p.placeTo()).put("kind", p.kind().name()).put("description", p.description()).put("amount", p.amount()));
        }
        card.put("prizes", prizes);
        card.put("signupClosesAt", Optional.ofNullable(settingsOf(e).signupClosesAt()).map(Instant::toString).orElse(null));
        card.put("cohosts", cohostsOf(jda, e));
        if (me == null) card.put("joinWith", joinWithOf(jda, e, viewerGuilds));
        boolean scored = e.status() == Status.RUNNING || e.status() == Status.SCORING || e.status() == Status.DONE;
        // a clan-vs-clan event shows its clans' standings (clan names only) whenever it is open to everyone (T-223)
        boolean shown = scored && (EventListing.standingsShown(e) || EventListing.clanStandingsShown(e) || (member && EventListing.standingsShownToMember(e, me != null)));
        List<Standing> all = scored ? service.standings(e.guildId(), e.id()) : List.of();
        List<HubBoard.Subject> rows = shown ? named(e, all) : List.of();
        if (shown) {
            DataArray standings = DataArray.empty();
            for (int i = 0; i < rows.size(); i++) standings.add(DataObject.empty().put("rank", i + 1).put("name", rows.get(i).name()).put("points", rows.get(i).points()));
            card.put("standings", standings);
        } else if (scored) {
            card.put("standingsNote", member ? EventStandings.hiddenText(EventStandings.of(DataObject.fromJson(e.config()))) : "The host keeps the standings to their own server.");
        }
        card.put("clanVsClan", com.runeclans.bot.events.ClanVsClan.is(e.template()));

        HubBoard.Subject mine = null;
        boolean teamEvent = e.scoringMode() == com.runeclans.bot.events.EventRules.ScoringMode.TEAM;
        if (me != null && !me.waiting() && scored) {
            String subject = teamEvent ? (me.teamId() == null ? null : "t:" + me.teamId()) : "u:" + me.userId();
            Standing own = all.stream().filter(s -> s.subject().equals(subject)).findFirst().orElse(null);
            if (subject != null) mine = new HubBoard.Subject(subject, "", own == null ? 0 : own.points(), own == null ? Map.of() : own.completions());
        }
        if (com.runeclans.bot.events.ClanVsClan.plays(e, com.runeclans.bot.events.BingoTemplate.KEY)) {
            card.put("board", HubBoard.of(DataObject.fromJson(e.config()), service.objectives(e.id()), rows, mine, scored ? events.progress(e.id()) : List.of(), teamEvent));
        }
        card.put("xpTiles", xpTilesOf(e));
        if (me != null) card.put("me", myPlace(e, me).put("channels", channelsOf(jda, e, viewerId)));
        card.put("manage", manageOf(jda, e, viewerId));
        return card;
    }

    /**
     * The XP tiles of a bingo or scavenger hunt counted from RuneSync (T-543), as their labels, so the page can say that only RuneSync XP counts
     * there; null when XP tiles aren't counted or none can be shown ({@link com.runeclans.bot.events.PluginObjectiveMatcher#shownXpTiles}).
     */
    DataArray xpTilesOf(Event e) {
        if (!com.runeclans.bot.events.PluginObjectiveMatcher.xpTilesOffered()) return null;
        return MemberEventsApi.xpTiles(e, service.objectives(e.id()));
    }

    /** The viewer's links to the event's channels (only the ones they can use), from the host's own player page. */
    private DataArray channelsOf(JDA jda, Event e, long viewerId) {
        Guild guild = jda == null ? null : Shards.guild(jda, e.guildId());
        if (guild == null) return DataArray.empty();
        try {
            DataObject detail = memberEvents.detail(guild, viewerId, e.id());
            return detail.hasKey("channels") ? detail.getArray("channels") : DataArray.empty();
        } catch (RuntimeException ex) {
            return DataArray.empty();
        }
    }

    /** Where staff of the host manage the event on the dashboard, only for someone who may use that server's event manager; else null. */
    private DataObject manageOf(JDA jda, Event e, Long viewerId) {
        if (viewerId == null || jda == null) return null;
        Guild guild = Shards.guild(jda, e.guildId());
        net.dv8tion.jda.api.entities.Member member = guild == null ? null : guild.getMemberById(viewerId);
        if (member == null) return null;
        boolean may = dashboardAccess.tierOf(guild, member) != DashboardAccess.Tier.NONE || dashboardAccess.isEventCoordinator(guild, member);
        return may ? DataObject.empty().put("guildId", guild.getId()).put("eventId", Long.toString(e.id())) : null;
    }

    /** The servers co-hosting the event (accepted only), named like the host: never by a Discord id. */
    private DataArray cohostsOf(JDA jda, Event e) {
        DataArray out = DataArray.empty();
        for (EventCohostRepository.Cohost c : cohosts.forEvent(e.id())) {
            if (c.status() != EventCohostRepository.Status.ACCEPTED) continue;
            DataObject h = host(jda, c.guildId());
            out.add(DataObject.empty().put("name", h.getString("name")).put("slug", h.opt("slug").orElse(null)).put("iconUrl", h.opt("iconUrl").orElse(null)));
        }
        return out;
    }

    private static EventSettings settingsOf(Event e) {
        try {
            return EventSettings.parse(DataObject.fromJson(e.config()));
        } catch (RuntimeException ex) {
            return EventSettings.defaults();
        }
    }

    /** An answer with its HTTP status, for the routes that change something. */
    public record Answer(int status, DataObject body) {}

    /**
     * Joins a listed event as {@code rawUser} (the Discord id the website signed in), by the same rules as joining from the host's own pages: the person has
     * to be in the host's Discord (D10), so someone who isn't gets 403 with {@code notInServer} and the host's invite link to try again after joining.
     */
    public Answer join(JDA jda, String rawId, String rawUser) {
        return join(jda, rawId, rawUser, null);
    }

    /**
     * As above, joining through the server {@code via} names (T-341): null or {@code "host"} for the host, or a {@code cohost-<n>} key from the event's
     * {@code joinWith} list, which must be an accepted co-host the person is in. The server they came through feeds the team-mixing rules and seat quotas.
     */
    public Answer join(JDA jda, String rawId, String rawUser, String via) {
        Long userId = idOf(rawUser);
        if (userId == null) return new Answer(400, DataObject.empty().put("error", "Sign in with Discord first."));
        if (rawId == null || !rawId.matches("\\d{1,18}")) return new Answer(404, DataObject.empty().put("error", "No such event"));
        Optional<Event> found = events.eventById(Long.parseLong(rawId));
        if (found.isEmpty() || !listedNow(found.get())) return new Answer(404, DataObject.empty().put("error", "No such event"));
        Event e = found.get();
        Guild host = jda == null ? null : Shards.guild(jda, e.guildId());
        if (host == null) return new Answer(503, DataObject.empty().put("error", "The host's server can't be reached right now. Try again in a minute."));
        // an alliance event is only for members of allied servers (or of the host): the same rule as seeing it
        if (accessOf(e, guildsOf(jda, userId), new LinkedHashMap<>()) == null && !inServer(host, userId)) return new Answer(404, DataObject.empty().put("error", "No such event"));
        if (!inServer(host, userId)) {
            String invite = host(jda, e.guildId()).getString("inviteUrl", null);
            return new Answer(403, DataObject.empty().put("notInServer", true).put("inviteUrl", invite)
                    .put("error", "To take part you need to be in the " + host.getName() + " Discord server. Join it, then press Join again."));
        }
        long origin = e.guildId();
        if (via != null && !via.isBlank() && !via.equals("host")) {
            Long cohost = cohostFor(jda, e, via, userId);
            if (cohost == null) return new Answer(400, DataObject.empty().put("error", "You can only join through a co-hosting server you're in."));
            origin = cohost;
        }
        try {
            var joined = service.join(e.guildId(), e.id(), origin, userId, null, false, null);
            return new Answer(200, DataObject.empty().put("joined", true).put("waiting", joined.waiting()).put("position", joined.position()));
        } catch (com.runeclans.bot.events.EventProblem p) {
            return new Answer(p.status() >= 400 && p.status() < 500 ? p.status() : 409, DataObject.empty().put("error", p.getMessage()));
        }
    }

    /**
     * Takes the signed-in person out of an event they are in (T-337), by the same rule as leaving from the host's own pages: only while it is taking
     * signups. A paid buy-in has to be refunded by an organiser first.
     */
    public Answer leave(JDA jda, String rawId, String rawUser) {
        Long userId = idOf(rawUser);
        if (userId == null) return new Answer(400, DataObject.empty().put("error", "Sign in with Discord first."));
        if (rawId == null || !rawId.matches("\\d{1,18}")) return new Answer(404, DataObject.empty().put("error", "No such event"));
        Optional<Event> found = events.eventById(Long.parseLong(rawId));
        Participant me = found.isEmpty() ? null : events.participant(found.get().id(), userId).orElse(null);
        if (me == null) return new Answer(404, DataObject.empty().put("error", "You aren't in this event."));
        Event e = found.get();
        if (e.status() != Status.SIGNUPS) {
            return new Answer(409, DataObject.empty().put("error", "You can only leave while an event is taking sign-ups. Ask an organiser if you need out of one that has started."));
        }
        if (me.buyIn() == com.runeclans.bot.events.PrizeRules.BuyInStatus.PAID) {
            return new Answer(409, DataObject.empty().put("error", "You've paid a buy-in. Ask an organiser to refund it, then you can leave."));
        }
        try {
            service.leave(e.guildId(), e.id(), userId);
            return new Answer(200, DataObject.empty().put("left", true));
        } catch (com.runeclans.bot.events.EventProblem p) {
            return new Answer(p.status() >= 400 && p.status() < 500 ? p.status() : 409, DataObject.empty().put("error", p.getMessage()));
        }
    }

    /** The accepted co-hosts in a stable order, with the {@code cohost-<n>} key each is offered under (never its Discord id). */
    private List<EventCohostRepository.Cohost> acceptedCohosts(Event e) {
        return cohosts.forEvent(e.id()).stream().filter(c -> c.status() == EventCohostRepository.Status.ACCEPTED)
                .sorted(java.util.Comparator.comparing(EventCohostRepository.Cohost::guildId)).toList();
    }

    /** The co-host server a {@code cohost-<n>} key names, if it is accepted and the person is in it; else null. */
    private Long cohostFor(JDA jda, Event e, String key, long userId) {
        if (!key.matches("cohost-\\d{1,3}")) return null;
        int n = Integer.parseInt(key.substring("cohost-".length()));
        List<EventCohostRepository.Cohost> accepted = acceptedCohosts(e);
        if (n < 1 || n > accepted.size()) return null;
        long guildId = accepted.get(n - 1).guildId();
        Guild guild = jda == null ? null : Shards.guild(jda, guildId);
        return guild != null && inServer(guild, userId) ? guildId : null;
    }

    /** The servers the viewer may join through: the co-hosts they're in, keyed {@code cohost-<n>}. Empty for nobody signed in or no co-hosts. */
    private DataArray joinWithOf(JDA jda, Event e, Set<Long> viewerGuilds) {
        DataArray out = DataArray.empty();
        if (viewerGuilds.isEmpty()) return out;
        List<EventCohostRepository.Cohost> accepted = acceptedCohosts(e);
        for (int i = 0; i < accepted.size(); i++) {
            if (!viewerGuilds.contains(accepted.get(i).guildId())) continue;
            out.add(DataObject.empty().put("key", "cohost-" + (i + 1)).put("name", host(jda, accepted.get(i).guildId()).getString("name")));
        }
        return out;
    }

    /**
     * Whether someone is in a server, asking Discord when they aren't in the cache. Throws {@link DiscordLookups.Slow} (a 503) when Discord
     * didn't answer in time, so a slow Discord never turns into a wrong "join the server first".
     */
    private static boolean inServer(Guild guild, long userId) {
        return DiscordLookups.memberOrSlow(guild, userId) != null;
    }

    /** Whether an event is on the hub right now: listed, and its server not barred from listing. */
    private boolean listedNow(Event e) {
        return EventListing.listed(e, Instant.now()) && !moderation.barredIds().contains(e.guildId());
    }

    /**
     * Records a report about a listed event for the bot's owner (T-218). Anyone who can see the hub may report; {@code reporter} is whatever the website chose
     * to record (the signed-in name, or nothing). A flood guard keeps it to {@value #MAX_REPORTS_PER_EVENT_PER_DAY} a day per event.
     */
    public Answer report(JDA jda, String rawId, DataObject body) {
        if (rawId == null || !rawId.matches("\\d{1,18}")) return new Answer(404, DataObject.empty().put("error", "No such event"));
        Optional<Event> found = events.eventById(Long.parseLong(rawId));
        if (found.isEmpty() || !listedNow(found.get())) return new Answer(404, DataObject.empty().put("error", "No such event"));
        String reason = body.getString("reason", "").strip();
        if (reason.length() < MIN_REASON || reason.length() > MAX_REASON) {
            return new Answer(400, DataObject.empty().put("error", "Say what is wrong, in " + MIN_REASON + " to " + MAX_REASON + " characters."));
        }
        String reporter = body.hasKey("reporter") && !body.isNull("reporter") ? body.getString("reporter", "").strip() : null;
        if (reporter != null && reporter.length() > 100) reporter = reporter.substring(0, 100);
        Event e = found.get();
        if (!moderation.addReport(e.id(), e.guildId(), reason, reporter == null || reporter.isEmpty() ? null : reporter, MAX_REPORTS_PER_EVENT_PER_DAY)) {
            return new Answer(429, DataObject.empty().put("error", "This event has been reported a lot today already. It will be looked at."));
        }
        return new Answer(202, DataObject.empty().put("ok", true));
    }

    /** How the viewer may see a listed event, or null. */
    private Access accessOf(Event e, Set<Long> viewerGuilds, Map<Long, Boolean> alliedCache) {
        if (viewerGuilds.contains(e.guildId())) return Access.MINE;
        boolean allied = !viewerGuilds.isEmpty() && alliedCache.computeIfAbsent(e.guildId(), host -> allied(host, viewerGuilds));
        if (!EventListing.visible(e, allied)) return null;
        return e.scope() == com.runeclans.bot.events.EventRules.Scope.PUBLIC ? Access.PUBLIC : Access.ALLIANCE;
    }

    /** Whether one of the viewer's servers shares an active alliance with the host. */
    private boolean allied(long host, Set<Long> viewerGuilds) {
        for (AllianceRepository.Alliance a : alliances.alliancesOf(host)) {
            List<AllianceRepository.Membership> members = alliances.membersOf(a.id());
            boolean hostActive = members.stream().anyMatch(m -> m.guildId() == host && m.status() == AllianceRules.Status.ACTIVE);
            if (!hostActive) continue;
            if (members.stream().anyMatch(m -> m.status() == AllianceRules.Status.ACTIVE && m.guildId() != host && viewerGuilds.contains(m.guildId()))) return true;
        }
        return false;
    }

    /** The servers JonnyBot shares with the viewer (from what Discord has told it), or none for nobody signed in. */
    private static Set<Long> guildsOf(JDA jda, Long viewerId) {
        Set<Long> ids = new HashSet<>();
        if (viewerId == null || jda == null) return ids;
        User user = Shards.user(jda, viewerId);
        if (user != null) Shards.mutualGuilds(user).forEach(g -> ids.add(g.getIdLong()));
        return ids;
    }

    private static Long idOf(String raw) {
        return raw != null && raw.matches("\\d{1,20}") ? Long.parseUnsignedLong(raw) : null;
    }

    /** What a listing card shows: the event and who hosts it, never who is in it. */
    private DataObject card(JDA jda, Event e) {
        EventTemplate template = templates.byKey(e.template()).orElse(null);
        int players = (int) events.participants(e.id()).stream().filter(p -> !p.waiting()).count();
        boolean signupsOpen = e.status() == Status.SIGNUPS && !events.setup(e.id()).signupsClosed();
        DataObject card = DataObject.empty().put("id", Long.toString(e.id()));
        // a hub event also carries the hub's id, so the website (which reads the hub directly too) lists it once
        String hubId = memberEvents == null ? null : memberEvents.hubIdOf(e);
        if (hubId != null) card.put("hubId", hubId);
        return card.put("name", e.name()).put("template", e.template())
                .put("templateLabel", template == null ? e.template() : template.label()).put("status", e.status().name()).put("scope", e.scope().name())
                .put("scoringMode", e.scoringMode().name())
                .put("startsAt", e.startsAt() == null ? null : e.startsAt().toString()).put("endsAt", e.endsAt() == null ? null : e.endsAt().toString())
                .put("signupsOpen", signupsOpen).put("players", players).put("maxParticipants", e.maxParticipants())
                .put("summary", EventListing.shortDescription(e.description())).put("needsRsn", template != null && template.needsRsn())
                .put("host", host(jda, e.guildId()));
    }

    /**
     * The host as the hub shows it: its listed clan profile when there is one, else the server's own name and icon; and where to join it.
     * Never the server's Discord id: the hub is public, and the directory shares no Discord ids (T-182).
     */
    DataObject host(JDA jda, long guildId) {
        Guild guild = jda == null ? null : Shards.guild(jda, guildId);
        Optional<ClanProfile> listed = profiles.publicView(guildId, false);
        // the server's one join link (JoinLinks), the same one its recruiting post shows: also the "join the Discord first" message's
        String invite = joinLinks.of(guildId, listed.map(ClanProfile::applyUrl).orElse(null));
        DataObject host = DataObject.empty().put("inviteUrl", invite);
        if (listed.isPresent()) {
            ClanProfile p = listed.get();
            return host.put("name", p.displayName()).put("iconUrl", p.logoUrl()).put("slug", p.slug());
        }
        return host.put("name", guild == null ? "A " + BotBrand.NAME + " server" : guild.getName()).put("iconUrl", guild == null ? null : guild.getIconUrl()).put("slug", null);
    }

    /** The top of the standings with RuneScape names only (a team's name for a team); a player with no linked name is "Player n". */
    private List<HubBoard.Subject> named(Event e, List<Standing> all) {
        Map<Long, String> teams = new LinkedHashMap<>();
        events.teams(e.id()).forEach(t -> teams.put(t.id(), t.name()));
        List<HubBoard.Subject> rows = new ArrayList<>();
        int unnamed = 0;
        for (Standing s : new ArrayList<>(all).subList(0, Math.min(STANDINGS_SHOWN, all.size()))) {
            long id = Long.parseLong(s.subject().substring(2));
            String name;
            if (s.subject().startsWith("t:")) {
                name = teams.getOrDefault(id, "A team");
            } else {
                List<PlayerLink> mine = links.getLinksForUser(e.guildId(), id);
                name = mine.isEmpty() ? "Player " + (++unnamed) : mine.get(0).rsn();
            }
            rows.add(new HubBoard.Subject(s.subject(), name, s.points(), s.completions()));
        }
        return rows;
    }
}
