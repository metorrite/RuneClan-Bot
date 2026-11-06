package com.runeclans.bot.hubevents;

import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.events.EventPrep;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Claim;
import com.runeclans.bot.events.EventRepository.ClaimKind;
import com.runeclans.bot.events.EventRepository.ClaimStatus;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRepository.Setup;
import com.runeclans.bot.events.EventRepository.Team;
import com.runeclans.bot.events.EventRoster;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventService.EventInput;
import com.runeclans.bot.events.EventService.Joined;
import com.runeclans.bot.events.EventSettings;
import com.runeclans.bot.events.PrizePlan;
import com.runeclans.bot.events.PrizeRules.BuyInStatus;
import com.runeclans.bot.events.PrizeRules.PrizeKind;
import com.runeclans.bot.events.TeamRules.Kind;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bot's events, read from and written to the RuneClans hub (T-932 F1): what {@code EventService} and {@code EventRepository} were for the
 * Discord side before the hub owned the data. The commands, buttons, signup panels, boards and spaces call this and nothing else; the hub's
 * event engine applies the rules and answers with its reasons, which come back as {@link EventProblem}s.
 * <ul>
 *   <li>Every event is named by its <b>local id</b> ({@link HubEventMap}): the whole number Discord buttons and {@code /events join} use.</li>
 *   <li>A server's clan is the hub's clan of its site ({@code GET /v1/guilds/{guildId}/site}, kept ten minutes).</li>
 *   <li>Members are named by Discord id both ways: the hub's first-party answers carry {@code discordId} beside each person id, and a person in a
 *       request is {@code discord:<id>} (P1, T-933). A participant the hub has no Discord id for (a website player who never linked) comes
 *       back with user id 0: Discord code mentions or roles nobody for them.</li>
 *   <li>Reads for a panel or a sweep are the application itself ({@link Actor#APP}, an organiser of everything); a member's own command sends
 *       them as the actor, so the hub applies their rights and shows them their view.</li>
 * </ul>
 * The records are the ones the bot's Discord code already draws ({@link EventRepository.Event} and friends), filled from the hub's JSON.
 */
@BService
public class HubEvents {
    private static final Logger log = LoggerFactory.getLogger(HubEvents.class);
    static final Duration CLAN_KEPT = Duration.ofMinutes(10);
    public static final String NO_CLAN_TEXT = "This server has no clan on RuneClans yet, so it can't have events. Make the clan's site on runeclans.com first.";

    private final HubEventClient hub;
    private final HubEventMap map;
    private final EventChanges changes;
    private volatile Clock clock = Clock.systemUTC();
    private record KnownClan(UUID clanId, Instant until) {}
    private final Map<Long, KnownClan> clans = new ConcurrentHashMap<>();

    public HubEvents(HubEventClient hub, HubEventMap map, EventChanges changes) {
        this.hub = hub;
        this.map = map;
        this.changes = changes;
    }

    /** For tests: the clock the clan cache ages by (a service class has exactly one constructor). */
    HubEvents withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    public boolean enabled() {
        return hub.enabled();
    }

    // ---------- servers and clans ----------

    /** The hub clan of a server's site, or empty when the server has none (or the hub can't say). */
    public Optional<UUID> clanOf(long guildId) {
        KnownClan known = clans.get(guildId);
        Instant now = clock.instant();
        if (known != null && known.until().isAfter(now)) return Optional.ofNullable(known.clanId());
        Optional<DataObject> site;
        try {
            site = hub.find("/v1/guilds/" + guildId + "/site", Actor.APP);
        } catch (EventProblem e) {
            return known == null ? Optional.empty() : Optional.ofNullable(known.clanId());
        }
        UUID clan = site.map(s -> uuid(s.getString("clanId", null))).orElse(null);
        clans.put(guildId, new KnownClan(clan, now.plus(CLAN_KEPT)));
        return Optional.ofNullable(clan);
    }

    /** Forgets what a server's clan was (the hub said its site changed). */
    public void forgetClan(long guildId) {
        clans.remove(guildId);
    }

    /**
     * The server whose site is hub clan {@code clanId}: one already seen in the event map, else the first of {@code candidates} (the servers the bot is in)
     * whose site says so. Empty when none does. Each server's answer is cached as {@link #clanOf} caches it.
     */
    public Optional<Long> guildOf(UUID clanId, Collection<Long> candidates) {
        if (clanId == null) return Optional.empty();
        for (Map.Entry<Long, UUID> e : map.guilds().entrySet()) if (clanId.equals(e.getValue())) return Optional.of(e.getKey());
        for (long guildId : candidates) if (clanOf(guildId).filter(clanId::equals).isPresent()) return Optional.of(guildId);
        return Optional.empty();
    }

    private UUID requireClan(long guildId) {
        return clanOf(guildId).orElseThrow(() -> new EventProblem(404, NO_CLAN_TEXT));
    }

    // ---------- events ----------

    /** The event by its local id, which has to be the server's. */
    public Event event(long guildId, long id) {
        HubEventMap.Row row = map.event(id).filter(r -> r.guildId() == guildId).orElseThrow(() -> new EventProblem(404, "That event doesn't exist in this server."));
        return toEvent(hub.get("/v1/events/" + row.hubId(), Actor.APP), row);
    }

    /** The event as {@code actor} sees it (their own view: no organiser-only settings). */
    public Event event(long guildId, long id, Actor actor) {
        HubEventMap.Row row = map.event(id).filter(r -> r.guildId() == guildId).orElseThrow(() -> new EventProblem(404, "That event doesn't exist in this server."));
        return toEvent(hub.get("/v1/events/" + row.hubId(), actor), row);
    }

    /** The event by its local id, whichever server it belongs to; empty when the id isn't a mapped event or the hub no longer has it. */
    public Optional<Event> eventById(long id) {
        Optional<HubEventMap.Row> row = map.event(id);
        if (row.isEmpty()) return Optional.empty();
        return hub.find("/v1/events/" + row.get().hubId(), Actor.APP).map(json -> toEvent(json, row.get()));
    }

    /** The event behind a hub id, mapped on first sight (its clan's server has to be known: {@code guildId}). */
    public Event eventByHubId(UUID hubId, long guildId) {
        DataObject json = hub.get("/v1/events/" + hubId, Actor.APP);
        long local = map.localId(hubId, uuid(json.getString("clanId", null)), guildId);
        return toEvent(json, map.event(local).orElseThrow());
    }

    /** The server's events in {@code statuses} (any when empty), newest first, up to {@code limit}. Events the website made are mapped as they are met. */
    public List<Event> events(long guildId, List<Status> statuses, int limit) {
        UUID clan = requireClan(guildId);
        String status = statuses.isEmpty() ? "" : "?status=" + statuses.stream().map(s -> s.name().toLowerCase(Locale.ROOT)).reduce((a, b) -> a + "," + b).orElse("");
        DataArray list = hub.get("/v1/clans/" + clan + "/events" + status, Actor.APP).optArray("events").orElse(DataArray.empty());
        List<Event> out = new ArrayList<>();
        for (int i = 0; i < list.length() && out.size() < limit; i++) {
            DataObject compact = list.getObject(i);
            UUID hubId = uuid(compact.getString("id", null));
            if (hubId == null) continue;
            long local = map.localId(hubId, clan, guildId);
            out.add(toEvent(compact, map.event(local).orElseThrow()));
        }
        return out;
    }

    /** Every mapped event in {@code status}, across the bot's servers (the sweeps). A server whose hub clan is gone contributes nothing. */
    public List<Event> eventsInStatus(Status status) {
        List<Event> out = new ArrayList<>();
        for (long guildId : map.guilds().keySet()) {
            try {
                out.addAll(events(guildId, List.of(status), Integer.MAX_VALUE));
            } catch (EventProblem e) {
                log.debug("Events of server {} couldn't be listed: {}", guildId, e.getMessage());
            }
        }
        return out;
    }

    /** The events a member is in or waiting for, newest first, in this server. */
    public List<Event> eventsForUser(long guildId, long userId, int limit) {
        UUID clan = requireClan(guildId);
        DataArray list = hub.get("/v1/people/me/events", Actor.member(userId, guildId)).optArray("events").orElse(DataArray.empty());
        List<Event> out = new ArrayList<>();
        for (int i = 0; i < list.length() && out.size() < limit; i++) {
            DataObject compact = list.getObject(i);
            if (!clan.toString().equals(compact.getString("clanId", ""))) continue;
            UUID hubId = uuid(compact.getString("id", null));
            if (hubId == null) continue;
            long local = map.localId(hubId, clan, guildId);
            out.add(toEvent(compact, map.event(local).orElseThrow()));
        }
        return out;
    }

    /**
     * Makes an event with the usual settings of its kind ({@code /events create}): the hub fills in the template's standard answers and the clan's
     * defaults, then applies what the member chose. Returns the new event's local id. {@code actor} has to be staff of the clan.
     */
    public long createEvent(long guildId, long actorId, EventInput in) {
        UUID clan = requireClan(guildId);
        DataObject answers = DataObject.empty().put("template", in.template()).put("name", in.name()).put("description", in.description() == null ? "" : in.description())
                .put("scope", in.scope().name()).put("scoringMode", in.scoringMode().name()).put("maxParticipants", in.maxParticipants())
                .put("startsAt", in.startsAt() == null ? null : in.startsAt().toString()).put("endsAt", in.endsAt() == null ? null : in.endsAt().toString());
        if (in.config() != null) answers.put("config", DataObject.fromJson(in.config().toString()));
        DataObject json = hub.send("POST", "/v1/clans/" + clan + "/events", answers, Actor.of(actorId, guildId, true));
        UUID hubId = uuid(json.getString("id", null));
        if (hubId == null) throw new EventProblem(502, "RuneClans made the event but didn't say which.");
        return map.localId(hubId, clan, guildId);
    }

    /** Moves an event to {@code to} (start, finish, cancel...). The hub applies the bot's old safeguards (objectives, checklist, verification). */
    public void moveStage(long guildId, long id, Status to, Actor actor) {
        hub.send("POST", path(guildId, id) + "/stage", DataObject.empty().put("to", to.name()), actor);
        changes.publish(EventChanges.Kind.STAGE, guildId, id);
    }

    public void deleteEvent(long guildId, long id, Actor actor) {
        hub.send("DELETE", path(guildId, id), null, actor);
        map.forget(id);
    }

    // ---------- the roster ----------

    public List<Participant> participants(long eventId) {
        HubEventMap.Row row = row(eventId);
        DataObject json = hub.get("/v1/events/" + row.hubId() + "/participants", Actor.APP);
        return participants(json.optArray("participants").orElse(DataArray.empty()), row);
    }

    public Optional<Participant> participant(long eventId, long userId) {
        return participants(eventId).stream().filter(p -> p.userId() == userId).findFirst();
    }

    /** The server's queue position of a waiting player (1 is next), 0 when they aren't waiting. */
    public int queuePosition(long eventId, long userId) {
        HubEventMap.Row row = row(eventId);
        DataArray list = hub.get("/v1/events/" + row.hubId() + "/participants", Actor.APP).optArray("participants").orElse(DataArray.empty());
        for (int i = 0; i < list.length(); i++) {
            DataObject p = list.getObject(i);
            if (discordId(p.getString("discordId", null)) == userId) return p.getBoolean("waiting", false) ? p.getInt("position", 0) : 0;
        }
        return 0;
    }

    /**
     * Adds a player: themselves ({@code staff} false, from the server they are in) or a late entry an organiser adds. The hub makes the person
     * record for a member it has never met. Where they ended up: in a place, or waiting at {@code position}.
     */
    public Joined join(long guildId, long id, long userId, Long teamId, boolean staff, Actor actor) {
        DataObject body = DataObject.empty();
        if (staff) body.put("discordId", Long.toString(userId));
        if (teamId != null) body.put("teamId", teamHubId(id, teamId).toString());
        DataObject json = hub.send("POST", path(guildId, id) + "/participants", body, actor);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
        if (!staff) changes.publish(new EventChanges.Change(EventChanges.Kind.PLAYER_JOINED, guildId, id, 0, userId, false, ""));
        boolean waiting = json.getBoolean("waiting", false);
        return new Joined(waiting, waiting ? json.getInt("position", 0) : 0);
    }

    /** Removes a player ({@code userId}; the actor themselves when it is their own id) and gives their place to the next person waiting. */
    public void leave(long guildId, long id, long userId, Actor actor) {
        if (actor.discordId() != null && actor.discordId() == userId) hub.send("DELETE", path(guildId, id) + "/participants", null, actor);
        else hub.send("DELETE", path(guildId, id) + "/participants/" + person(userId), null, actor);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
        changes.publish(new EventChanges.Change(EventChanges.Kind.PLAYER_LEFT, guildId, id, 0, userId, false, ""));
    }

    public void ban(long guildId, long id, long userId, String reason, Actor actor) {
        hub.send("POST", path(guildId, id) + "/bans", DataObject.empty().put("discordId", Long.toString(userId)).put("reason", reason), actor);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    public void unban(long guildId, long id, long userId, Actor actor) {
        hub.send("DELETE", path(guildId, id) + "/bans/" + person(userId), null, actor);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    /** Who is barred from the event, from the hub's bans list (a player without a Discord id comes back with user id 0). */
    public List<EventRepository.Ban> bans(long eventId) {
        HubEventMap.Row row = row(eventId);
        DataArray bans = hub.get("/v1/events/" + row.hubId() + "/bans", Actor.APP).optArray("bans").orElse(DataArray.empty());
        List<EventRepository.Ban> out = new ArrayList<>();
        for (int i = 0; i < bans.length(); i++) {
            DataObject b = bans.getObject(i);
            out.add(new EventRepository.Ban(discordId(b.getString("discordId", null)), b.getString("reason", ""), discordId(b.getString("byDiscordId", null)), instant(b.getString("at", null))));
        }
        return out;
    }

    /** Whether (and why) {@code userId} is barred from the event. */
    public Optional<EventRepository.Ban> ban(long eventId, long userId) {
        return bans(eventId).stream().filter(b -> b.userId() == userId).findFirst();
    }

    public void closeSignups(long guildId, long id, Actor actor) {
        hub.send("POST", path(guildId, id) + "/signups/close", DataObject.empty(), actor);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    public void reopenSignups(long guildId, long id, Actor actor) {
        hub.send("POST", path(guildId, id) + "/signups/reopen", DataObject.empty(), actor);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    public void confirmRoster(long guildId, long id, Actor actor) {
        hub.send("POST", path(guildId, id) + "/signups/confirm", DataObject.empty(), actor);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    /** Gives free places to the waitlist in the event's queue order. How many moved in. */
    public int fillSeats(long guildId, long id) {
        DataObject json = hub.send("POST", path(guildId, id) + "/fill-seats", DataObject.empty(), Actor.APP);
        int moved = json.getInt("moved", 0);
        if (moved > 0) changes.publish(EventChanges.Kind.ROSTER, guildId, id);
        return moved;
    }

    public void setVerification(long guildId, long id, long userId, EventRoster.Verification to, String note, Actor actor) {
        hub.send("PUT", path(guildId, id) + "/participants/" + person(userId) + "/verification", DataObject.empty().put("to", to.name()).put("note", note == null ? "" : note), actor);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    public void setRating(long guildId, long id, long userId, Integer rating, Actor actor) {
        hub.send("PUT", path(guildId, id) + "/participants/" + person(userId) + "/rating", DataObject.empty().put("rating", rating), actor);
    }

    // ---------- teams ----------

    public List<Team> teams(long eventId) {
        HubEventMap.Row row = row(eventId);
        return teams(hub.get("/v1/events/" + row.hubId() + "/teams", Actor.APP).optArray("teams").orElse(DataArray.empty()), row);
    }

    /** Adds a team; a captain, if named, is put on it. The new team's local id. */
    public long addTeam(long guildId, long id, String name, Long captainId, Actor actor) {
        DataObject body = DataObject.empty().put("name", name);
        if (captainId != null) body.put("captainId", person(captainId));
        DataObject json = hub.send("POST", path(guildId, id) + "/teams", body, actor);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
        UUID hubId = uuid(json.getString("id", null));
        if (hubId == null) {
            // some answers carry the whole team list: find the one by name
            DataArray teams = json.optArray("teams").orElse(DataArray.empty());
            for (int i = 0; i < teams.length(); i++) if (name.strip().equalsIgnoreCase(teams.getObject(i).getString("name", ""))) hubId = uuid(teams.getObject(i).getString("id", null));
        }
        if (hubId == null) throw new EventProblem(502, "RuneClans added the team but didn't say which.");
        return map.localId(HubEventMap.TEAM, hubId, id);
    }

    public void renameTeam(long guildId, long id, long teamId, String name, Actor actor) {
        hub.send("PUT", path(guildId, id) + "/teams/" + teamHubId(id, teamId), DataObject.empty().put("name", name), actor);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    public void setCaptain(long guildId, long id, long teamId, Long captainId, Actor actor) {
        hub.send("PUT", path(guildId, id) + "/teams/" + teamHubId(id, teamId), DataObject.empty().put("captainId", captainId == null ? null : person(captainId)), actor);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    public void removeTeam(long guildId, long id, long teamId, Actor actor) {
        hub.send("DELETE", path(guildId, id) + "/teams/" + teamHubId(id, teamId), null, actor);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    /** Puts a player on a team, or takes them off it (null). */
    public void setTeam(long guildId, long id, long userId, Long teamId, Actor actor) {
        hub.send("PUT", path(guildId, id) + "/participants/" + person(userId) + "/team", DataObject.empty().put("teamId", teamId == null ? null : teamHubId(id, teamId).toString()), actor);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    /** Moves several players at once, all or nothing. How many changed. */
    public int assignTeams(long guildId, long id, Map<Long, Long> assignments, Actor actor) {
        DataObject moves = DataObject.empty();
        assignments.forEach((userId, teamId) -> moves.put(person(userId), teamId == null ? null : teamHubId(id, teamId).toString()));
        DataObject json = hub.send("POST", path(guildId, id) + "/teams/assign", DataObject.empty().put("assignments", moves), actor);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
        return json.getInt("moved", assignments.size());
    }

    public void finaliseTeams(long guildId, long id, Actor actor) {
        hub.send("POST", path(guildId, id) + "/teams/finalise", DataObject.empty(), actor);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    public void reopenTeams(long guildId, long id, Actor actor) {
        hub.send("POST", path(guildId, id) + "/teams/reopen", DataObject.empty(), actor);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    // ---------- organisers, settings, setup ----------

    /** Names or removes organisers. The organisers after the change, by Discord id. */
    public List<Long> changeOrganisers(long guildId, long id, Collection<Long> add, Collection<Long> remove, Actor actor) {
        DataObject body = DataObject.empty().put("add", DataArray.fromCollection(add.stream().map(HubEvents::person).toList()))
                .put("remove", DataArray.fromCollection(remove.stream().map(HubEvents::person).toList()));
        DataObject json = hub.send("PUT", path(guildId, id) + "/organisers", body, actor);
        changes.publish(EventChanges.Kind.SETUP, guildId, id);
        return organisers(json);
    }

    /** The organisers named on the event, by Discord id (those the hub has one for). */
    public List<Long> organisers(long guildId, long id) {
        return organisers(hub.get(path(guildId, id) + "/organisers", Actor.APP));
    }

    static List<Long> organisers(DataObject json) {
        List<Long> out = new ArrayList<>();
        DataArray ids = json.optArray("organiserDiscordIds").orElse(DataArray.empty());
        for (int i = 0; i < ids.length(); i++) {
            long id = ids.isNull(i) ? 0 : discordId(ids.getString(i));
            if (id != 0) out.add(id);
        }
        return out;
    }

    /** Whether this member may run the event from Discord: staff always, otherwise only someone named an organiser of it. */
    public boolean mayOrganise(Event event, long userId, boolean staff) {
        return staff || settingsOf(event).isOrganiser(userId);
    }

    public EventSettings settingsOf(Event event) {
        try {
            return EventSettings.parse(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return EventSettings.defaults();
        }
    }

    public Setup setup(long eventId) {
        HubEventMap.Row row = row(eventId);
        return setup(hub.get("/v1/events/" + row.hubId(), Actor.APP));
    }

    /** The setup checklist the hub keeps for the event: what is done, what to do now, what stops it from starting. */
    public EventPrep.Prep prepOf(long guildId, long id) {
        return prep(hub.get(path(guildId, id), Actor.APP));
    }

    public void setChannelSettings(long guildId, long id, DataObject channels, Actor actor) {
        hub.send("PUT", path(guildId, id) + "/channel-settings", DataObject.empty().put("channels", channels), actor);
        changes.publish(EventChanges.Kind.SETUP, guildId, id);
    }

    public void setWrapupSettings(long guildId, long id, DataObject wrapup, Actor actor) {
        hub.send("PUT", path(guildId, id) + "/wrapup-settings", DataObject.empty().put("wrapup", wrapup), actor);
        changes.publish(EventChanges.Kind.SETUP, guildId, id);
    }

    // ---------- objectives, standings, prizes ----------

    public List<ObjectiveSpec> objectives(long eventId) {
        HubEventMap.Row row = row(eventId);
        return objectives(hub.get("/v1/events/" + row.hubId() + "/objectives", Actor.APP).optArray("objectives").orElse(DataArray.empty()));
    }

    /** The standings as the hub works them out; subjects are {@code u:<discordId>} or {@code t:<local team id>}, as the bot's own were. */
    public List<Standing> standings(long guildId, long id) {
        HubEventMap.Row row = row(id);
        DataArray rows = hub.get("/v1/events/" + row.hubId() + "/standings", Actor.APP).optArray("standings").orElse(DataArray.empty());
        List<Standing> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            DataObject s = rows.getObject(i);
            String subject;
            if (s.hasKey("teamId") && !s.isNull("teamId")) subject = "t:" + map.localId(HubEventMap.TEAM, UUID.fromString(s.getString("teamId")), id);
            else subject = "u:" + discordId(s.getString("discordId", null));
            Map<String, Integer> completions = new LinkedHashMap<>();
            DataObject c = s.optObject("completions").orElse(DataObject.empty());
            for (String key : c.keys()) completions.put(key, c.getInt(key, 0));
            out.add(new Standing(subject, s.getLong("points", 0), s.getInt("objectivesCompleted", 0), completions, instant(s.getString("lastCompletedAt", null))));
        }
        return out;
    }

    public List<PrizePlan.Prize> prizes(long eventId) {
        HubEventMap.Row row = row(eventId);
        DataArray list = hub.get("/v1/events/" + row.hubId() + "/prizes", Actor.APP).optArray("prizes").orElse(DataArray.empty());
        List<PrizePlan.Prize> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            DataObject p = list.getObject(i);
            out.add(new PrizePlan.Prize(i + 1, p.getInt("place", 1), p.getInt("placeTo", p.getInt("place", 1)), enumOf(PrizeKind.class, p.getString("kind", null), PrizeKind.OTHER),
                    p.getString("description", ""), p.getLong("amount", 0), p.getInt("percentOfPot", 0), enumOf(PrizePlan.Split.class, p.getString("split", null), PrizePlan.Split.EQUAL)));
        }
        return out;
    }

    // ---------- progress and claims ----------

    /** A player's proof for an objective, waiting for an organiser. The claim's local id. */
    public long submitClaim(long guildId, long id, String objectiveKey, long userId, long amount, String note, String proofUrl) {
        DataObject body = DataObject.empty().put("objectiveKey", objectiveKey).put("amount", amount).put("note", note == null ? "" : note).put("proofUrl", proofUrl);
        DataObject json = hub.send("POST", path(guildId, id) + "/claims", body, Actor.member(userId, guildId));
        return claimLocalId(json, id);
    }

    /** A player's proof for the event's verification. The claim's local id. */
    public long submitVerification(long guildId, long id, long userId, String note, String proofUrl) {
        DataObject body = DataObject.empty().put("note", note == null ? "" : note).put("proofUrl", proofUrl);
        DataObject json = hub.send("POST", path(guildId, id) + "/verification", body, Actor.member(userId, guildId));
        return claimLocalId(json, id);
    }

    private long claimLocalId(DataObject json, long eventId) {
        DataObject claim = json.optObject("claim").orElse(json);
        UUID hubId = uuid(claim.getString("id", null));
        if (hubId == null) throw new EventProblem(502, "RuneClans took the proof but didn't say which claim it is.");
        return map.localId(HubEventMap.CLAIM, hubId, eventId);
    }

    /** An organiser's decision on a claim. The claim as it stands after. */
    public Claim decideClaim(long guildId, long id, long claimId, boolean approve, String note, Actor actor) {
        UUID claimHub = map.hubId(HubEventMap.CLAIM, claimId).orElseThrow(() -> new EventProblem(404, "That claim doesn't exist."));
        DataObject json = hub.send("POST", path(guildId, id) + "/claims/" + claimHub + "/decide", DataObject.empty().put("approve", approve).put("note", note == null ? "" : note), actor);
        Claim claim = claim(json.optObject("claim").orElse(json), id);
        changes.publish(new EventChanges.Change(EventChanges.Kind.CLAIM_DECIDED, guildId, id, claimId, claim.userId(), approve, note == null ? "" : note));
        return claim;
    }

    public Optional<Claim> claim(long eventId, long claimId) {
        HubEventMap.Row row = row(eventId);
        Optional<UUID> claimHub = map.hubId(HubEventMap.CLAIM, claimId);
        if (claimHub.isEmpty()) return Optional.empty();
        DataArray list = hub.get("/v1/events/" + row.hubId() + "/claims", Actor.APP).optArray("claims").orElse(DataArray.empty());
        for (int i = 0; i < list.length(); i++) {
            if (claimHub.get().toString().equals(list.getObject(i).getString("id", ""))) return Optional.of(claim(list.getObject(i), eventId));
        }
        return Optional.empty();
    }

    /** Records progress for a player from the bot's own trackers or an organiser (the hub applies the objective's rules). */
    public void recordProgress(long guildId, long id, String objectiveKey, long userId, long amount, EventRules.Source source, String sourceKey, String note, Actor actor) {
        DataObject body = DataObject.empty().put("discordId", Long.toString(userId)).put("objectiveKey", objectiveKey).put("amount", amount).put("source", source.name())
                .put("sourceKey", sourceKey).put("note", note == null ? "" : note);
        hub.send("POST", path(guildId, id) + "/progress", body, actor);
        changes.publish(EventChanges.Kind.PROGRESS, guildId, id);
    }

    // ---------- the JSON as the bot's records ----------

    Event toEvent(DataObject j, HubEventMap.Row row) {
        DataObject config = DataObject.fromJson(j.optObject("config").orElse(DataObject.empty()).toJson());
        // the hub keeps organisers beside the config; the bot's settings read them from it ({@code EventSettings.isOrganiser}), as Discord ids
        DataArray organisers = j.optArray("organiserDiscordIds").orElse(null);
        if (organisers != null) {
            DataArray ids = DataArray.empty();
            for (int i = 0; i < organisers.length(); i++) if (!organisers.isNull(i)) ids.add(organisers.getString(i));
            config.put("organisers", ids);
        }
        return new Event(row.localId(), row.guildId(), j.getString("template", "custom"), j.getString("name", ""), j.getString("description", ""),
                enumOf(Status.class, j.getString("status", null), Status.DRAFT), enumOf(Scope.class, j.getString("scope", null), Scope.PRIVATE),
                enumOf(ScoringMode.class, j.getString("scoringMode", null), ScoringMode.INDIVIDUAL), instant(j.getString("startsAt", null)), instant(j.getString("endsAt", null)),
                j.getInt("maxParticipants", 0), config.toString(), discordId(j.getString("createdByDiscordId", null)), instant(j.getString("createdAt", null)), row.signupId(),
                j.getBoolean("archived", false));
    }

    List<Participant> participants(DataArray list, HubEventMap.Row row) {
        List<Participant> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            DataObject p = list.getObject(i);
            UUID team = uuid(p.getString("teamId", null));
            UUID origin = uuid(p.getString("originClanId", null));
            long originGuild = origin == null || origin.equals(row.clanId()) ? row.guildId() : 0;
            out.add(new Participant(row.localId(), discordId(p.getString("discordId", null)), originGuild, team == null ? null : map.localId(HubEventMap.TEAM, team, row.localId()),
                    enumOf(Kind.class, p.getString("kind", null), Kind.HOME_OTHER), p.getBoolean("waiting", false), enumOf(BuyInStatus.class, p.getString("buyIn", null), BuyInStatus.NONE),
                    instant(p.getString("joinedAt", null)), enumOf(EventRoster.Verification.class, p.getString("verification", null), EventRoster.Verification.NOT_REQUIRED), null, null,
                    p.getString("verificationNote", ""), p.hasKey("rating") && !p.isNull("rating") ? p.getInt("rating") : null,
                    enumOf(EventRepository.RosterSource.class, p.getString("source", null), EventRepository.RosterSource.SELF)));
        }
        return out;
    }

    List<Team> teams(DataArray list, HubEventMap.Row row) {
        List<Team> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            DataObject t = list.getObject(i);
            UUID hubId = uuid(t.getString("id", null));
            if (hubId == null) continue;
            long captain = discordId(t.getString("captainDiscordId", null));
            out.add(new Team(map.localId(HubEventMap.TEAM, hubId, row.localId()), row.localId(), t.getString("name", ""), captain == 0 ? null : captain));
        }
        return out;
    }

    static Setup setup(DataObject event) {
        DataObject s = event.optObject("setup").orElse(DataObject.empty());
        // the hub says what is done and whether it changed since; the bot's record says it with moments, so "done" is "at some moment"
        Instant now = Instant.EPOCH;
        Instant closed = s.getBoolean("signupsClosed", false) ? firstOf(instant(s.getString("signupsClosedAt", null)), now) : null;
        Instant confirmed = s.getBoolean("rosterConfirmed", false) ? now : null;
        Instant finalised = s.getBoolean("teamsFinalised", false) ? now : null;
        Instant applied = s.getBoolean("spaceApplied", false) ? now : null;
        Instant rosterChanged = s.getBoolean("rosterChangedSinceConfirmed", false) ? now.plusSeconds(1) : null;
        Instant teamsChanged = s.getBoolean("teamsChangedSinceFinalised", false) ? now.plusSeconds(1) : null;
        return new Setup(closed, null, confirmed, null, finalised, null, applied, null, s.getBoolean("spaceComplete", false), s.getString("spaceNote", ""), rosterChanged, teamsChanged);
    }

    static EventPrep.Prep prep(DataObject event) {
        DataObject p = event.optObject("prep").orElse(DataObject.empty());
        List<EventPrep.Step> steps = new ArrayList<>();
        DataArray list = p.optArray("steps").orElse(DataArray.empty());
        for (int i = 0; i < list.length(); i++) {
            DataObject s = list.getObject(i);
            steps.add(new EventPrep.Step(s.getString("key", ""), s.getString("label", ""), enumOf(EventPrep.State.class, s.getString("state", null), EventPrep.State.WAITING),
                    s.getBoolean("required", false), s.getString("detail", ""), strings(s.optArray("blockers").orElse(DataArray.empty()))));
        }
        return new EventPrep.Prep(steps, strings(p.optArray("blockers").orElse(DataArray.empty())), strings(p.optArray("warnings").orElse(DataArray.empty())));
    }

    static List<ObjectiveSpec> objectives(DataArray list) {
        List<ObjectiveSpec> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            DataObject o = list.getObject(i);
            out.add(new ObjectiveSpec(o.getString("key", ""), o.getString("label", ""), o.getString("description", ""), o.getLong("points", 0),
                    enumOf(Evidence.class, o.getString("evidence", null), Evidence.ORGANISER), o.getLong("target", 1), o.getInt("repeatLimit", 1), strings(o.optArray("prerequisites").orElse(DataArray.empty()))));
        }
        return out;
    }

    Claim claim(DataObject c, long eventId) {
        UUID hubId = uuid(c.getString("id", null));
        long local = hubId == null ? 0 : map.localId(HubEventMap.CLAIM, hubId, eventId);
        ClaimKind kind = enumOf(ClaimKind.class, c.getString("kind", null), ClaimKind.OBJECTIVE);
        Optional<HubEventMap.Receipt> receipt = local == 0 ? Optional.empty() : map.receipt(local);
        long decidedBy = discordId(c.getString("decidedByDiscordId", null));
        return new Claim(local, eventId, c.getString("objectiveKey", null), discordId(c.getString("discordId", null)), c.getLong("amount", 1), c.getString("note", ""), c.getString("proofUrl", null),
                enumOf(ClaimStatus.class, c.getString("status", null), ClaimStatus.PENDING), decidedBy == 0 ? null : decidedBy, c.getString("decisionNote", null), null,
                instant(c.getString("createdAt", null)), instant(c.getString("decidedAt", null)), kind, receipt.map(HubEventMap.Receipt::channelId).orElse(null),
                receipt.map(HubEventMap.Receipt::messageId).orElse(null));
    }


    // ---------- what a member sees ----------

    /** One of a member's events: the event and their own place in it (null when the hub listed it without one). */
    public record Mine(Event event, Participant me) {}

    /** The events a member is in or waiting for in this server, with their own place in each, newest first (one hub call). */
    public List<Mine> mine(long guildId, long userId, int limit) {
        UUID clan = requireClan(guildId);
        DataArray list = hub.get("/v1/people/me/events", Actor.member(userId, guildId)).optArray("events").orElse(DataArray.empty());
        List<Mine> out = new ArrayList<>();
        for (int i = 0; i < list.length() && out.size() < limit; i++) {
            DataObject compact = list.getObject(i);
            if (!clan.toString().equals(compact.getString("clanId", ""))) continue;
            UUID hubId = uuid(compact.getString("id", null));
            if (hubId == null) continue;
            long local = map.localId(hubId, clan, guildId);
            HubEventMap.Row row = map.event(local).orElseThrow();
            DataObject me = compact.optObject("me").orElse(null);
            Participant p = null;
            if (me != null) {
                DataObject withId = DataObject.fromJson(me.toJson()).put("discordId", Long.toString(userId));
                p = participants(DataArray.empty().add(withId), row).get(0);
            }
            out.add(new Mine(toEvent(compact, row), p));
        }
        return out;
    }

    /** A member's own prizes in an event: {@code place}, {@code kind}, {@code description}, {@code status} each, as the hub lists them for the player. */
    public List<DataObject> awardsOf(long guildId, long id, long userId) {
        DataArray list = hub.get(path(guildId, id) + "/awards", Actor.member(userId, guildId)).optArray("awards").orElse(DataArray.empty());
        List<DataObject> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) out.add(list.getObject(i));
        return out;
    }

    /** The bingo board as a PNG drawn by the hub: the member's own ({@code mine}) or the event's. */
    public byte[] boardPng(long guildId, long id, long userId, boolean mine) {
        return hub.bytes(path(guildId, id) + "/board.png" + (mine ? "?mine=true" : ""), userId == 0 ? Actor.APP : Actor.member(userId, guildId));
    }

    // ---------- team rules read from the event ----------

    /** {@link EventSettings} of an event, as the team builder names it. */
    public EventSettings settings(Event event) {
        return settingsOf(event);
    }

    /** Why a player can't be put on a team right now, in words, or null when they can: the hub's own rule, read from what it says about the player. */
    public String teamBlock(Event event, Participant player) {
        return teamBlock(event, player, "That player");
    }

    /** {@link #teamBlock(Event, Participant)} with the player called {@code who} in the sentence. */
    public String teamBlock(Event event, Participant player, String who) {
        if (player == null) return who + " isn't in the event.";
        if (player.waiting()) return who + " is on the waitlist, so they can't be put on a team yet.";
        EventRoster.Setting verification = settingsOf(event).verification();
        EventRoster.Gate moment = event.status() == Status.RUNNING || event.status() == Status.SCORING || event.status() == Status.DONE ? EventRoster.Gate.START : EventRoster.Gate.TEAMS;
        if (EventRoster.counts(player.verification(), verification, moment)) return null;
        return switch (EventRoster.effective(player.verification(), verification)) {
            case REJECTED -> who + (who.endsWith("s") ? "'" : "'s") + " verification was turned down, so they can't be put on a team. Verify or waive them first.";
            default -> who + " hasn't been verified, which this event needs before teams are made. Verify or waive them first.";
        };
    }

    // ---------- helpers ----------

    private HubEventMap.Row row(long eventId) {
        return map.event(eventId).orElseThrow(() -> new EventProblem(404, "That event doesn't exist (any more)."));
    }

    private String path(long guildId, long id) {
        HubEventMap.Row row = map.event(id).filter(r -> r.guildId() == guildId).orElseThrow(() -> new EventProblem(404, "That event doesn't exist in this server."));
        return "/v1/events/" + row.hubId();
    }

    private UUID teamHubId(long eventId, long teamId) {
        return map.hubId(HubEventMap.TEAM, teamId).orElseThrow(() -> new EventProblem(404, "That team doesn't exist."));
    }

    /** A person named by Discord id in a hub request or path (P1: {@code discord:<id>}). */
    static String person(long discordId) {
        return "discord:" + discordId;
    }

    static long discordId(String raw) {
        if (raw == null || !raw.matches("\\d{1,20}")) return 0;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static UUID uuid(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant firstOf(Instant a, Instant b) {
        return a != null ? a : b;
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String raw, E fallback) {
        if (raw == null) return fallback;
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static List<String> strings(DataArray array) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) if (!array.isNull(i)) out.add(array.getString(i));
        return out;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The map, for the Discord-side bookkeeping (panels, receipts, the signup's entries). */
    /** The hub client behind this, for the feed reader's own lookups. */
    public HubEventClient client() {
        return hub;
    }

    public HubEventMap map() {
        return map;
    }
}
