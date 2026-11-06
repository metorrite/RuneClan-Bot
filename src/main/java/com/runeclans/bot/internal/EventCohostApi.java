package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.alliance.AllianceRepository;
import com.runeclans.bot.alliance.AllianceRules;
import com.runeclans.bot.events.CohostPanels;
import com.runeclans.bot.events.EventCohostRepository;
import com.runeclans.bot.events.EventCohostRepository.Cohost;
import com.runeclans.bot.events.EventCohostRepository.Status;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventSettings;
import com.runeclans.bot.events.QueueRules;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Co-hosted events (T-219, design {@code docs/EVENTS-CROSS-SERVER.md} sections 2, 3, 6 and 7). Reached through {@link TicketAdminApi}, which checks the asker
 * against the calling server and audit-logs every change; every route here then checks the calling server's place in the event.
 * <ul>
 *   <li>The host: {@code GET events/{id}/cohosts}, {@code POST events/{id}/cohosts {guildId}} (invite a server it shares an active alliance with),
 *       {@code DELETE events/{id}/cohosts/{guildId}} (only before signups close).</li>
 *   <li>A co-host: {@code GET cohost-events} (invitations and the events it co-hosts, with its own players only), {@code POST cohost-events/{id}/accept},
 *       {@code POST cohost-events/{id}/decline}, {@code DELETE cohost-events/{id}/players/{userId}} (one of its own players, before the roster is confirmed).</li>
 * </ul>
 * A co-host never sees another server's players: only counts and its own.
 */
@BService
public class EventCohostApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EventCohostApi.class);
    /** At most this many servers invited to or co-hosting one event. */
    public static final int MAX_COHOSTS = 10;

    private final EventCohostRepository cohosts;
    private final EventRepository events;
    private final EventService service;
    private final AllianceRepository alliances;
    private final CohostPanels panels;

    public EventCohostApi(EventCohostRepository cohosts, EventRepository events, EventService service, AllianceRepository alliances, CohostPanels panels) {
        this.cohosts = cohosts;
        this.events = events;
        this.service = service;
        this.alliances = alliances;
        this.panels = panels;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        try {
            if (parts.length >= 3 && parts[0].equals("events") && parts[2].equals("cohosts")) return hostRoute(guild, actor, method, parts, body);
            if (parts.length >= 1 && parts[0].equals("cohost-events")) return cohostRoute(guild, actor, method, parts, body);
            return null;
        } catch (EventProblem e) {
            throw new ApiError(e.status() == 500 ? 500 : e.status(), e.getMessage(), e.problems());
        }
    }

    // ---------- the host ----------

    private DataObject hostRoute(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        Event event = service.event(guild.getIdLong(), idOf(parts[1], "event")); // the event must be this server's
        if (parts.length == 3) {
            if (method.equals("GET")) return hostView(guild, event);
            AdminRouteExtension.requireMethod(method, "POST");
            long target = idOf(body.get().getString("guildId", ""), "server");
            invite(guild, actor, event, target);
            return hostView(guild, event);
        }
        if (parts.length == 4 && parts[3].equals("settings")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            DataObject in = body.get();
            if (!in.hasKey("announceStart") || !(in.opt("announceStart").orElse(null) instanceof Boolean on)) throw new EventProblem(400, Text.of(guild, "events.problem.say_whether_to_announce_the_start"));
            DataObject config = DataObject.fromJson(event.config()).put(CohostPanels.ANNOUNCE_START, on);
            if (!events.setConfig(event.guildId(), event.id(), config.toString())) throw new EventProblem(404, Text.of(guild, "events.problem.that_event_doesn_t_exist_in"));
            log.info("Dashboard: {} turned the co-host start announcement {} for event {}", actor.getId(), on ? "on" : "off", event.id());
            return hostView(guild, events.eventById(event.id()).orElse(event));
        }
        if (parts.length == 4 && parts[3].equals("queue")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            saveQueue(actor, event, body.get());
            return hostView(guild, events.eventById(event.id()).orElse(event));
        }
        if (parts.length == 4) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            long target = idOf(parts[3], "server");
            remove(actor, event, target);
            return hostView(guild, event);
        }
        return null;
    }

    void invite(Guild guild, Member actor, Event event, long target) {
        if (event.status() == EventRules.Status.DONE || event.status() == EventRules.Status.CANCELLED) throw new EventProblem(409, Text.of(guild, "events.problem.this_event_is_over"));
        if (target == event.guildId()) throw new EventProblem(400, Text.of(guild, "events.problem.your_own_server_is_the_host"));
        if (!allied(event.guildId(), target)) throw new EventProblem(403, Text.of(guild, "events.problem.you_can_only_invite_a_server"));
        long active = cohosts.forEvent(event.id()).stream().filter(c -> c.status() == Status.INVITED || c.status() == Status.ACCEPTED).count();
        boolean already = cohosts.get(event.id(), target).map(c -> c.status() == Status.INVITED || c.status() == Status.ACCEPTED).orElse(false);
        if (already) throw new EventProblem(409, Text.of(guild, "events.problem.that_server_is_already_invited_or"));
        if (active >= MAX_COHOSTS) throw new EventProblem(409, Text.of(guild, "events.problem.an_event_can_have_at_most", MAX_COHOSTS));
        cohosts.invite(event.id(), target, actor.getIdLong());
        log.info("Dashboard: {} invited server {} to co-host event {}", actor.getId(), target, event.id());
    }

    void remove(Member actor, Event event, long target) {
        if (events.setup(event.id()).signupsClosed()) throw new EventProblem(409, Text.of(actor.getGuild(), "events.problem.signups_have_closed_so_the_co"));
        Cohost c = cohosts.get(event.id(), target).orElseThrow(() -> new EventProblem(404, Text.of(actor.getGuild(), "events.problem.that_server_isn_t_a_co")));
        if (!cohosts.decide(event.id(), target, c.status(), Status.REMOVED, actor.getIdLong()) || c.status() == Status.REMOVED || c.status() == Status.DECLINED) {
            throw new EventProblem(409, Text.of(actor.getGuild(), "events.problem.that_server_isn_t_invited_or"));
        }
        log.info("Dashboard: {} removed server {} as a co-host of event {}", actor.getId(), target, event.id());
        service.syncTemplateTeams(event, false); // a clan-vs-clan event takes the clan's empty team away (T-222)
        panels.remove(event, target); // its signup panel comes down (T-221)
        cohosts.deletePendingJoins(event.id(), target);
    }

    /**
     * The fill queue (T-220): {@code {queue, hostQuota, releaseHours, quotas: {guildId: n|null}}}. The policy and the host's quota are kept in the
     * event's settings, each accepted co-host's quota with the co-host. Quotas need a player limit and may not add up to more than it. Places that the
     * new quotas allow are filled from the waitlist at once.
     */
    void saveQueue(Member actor, Event event, DataObject body) {
        if (event.status() != EventRules.Status.SIGNUPS && event.status() != EventRules.Status.DRAFT) throw new EventProblem(409, Text.of(actor.getGuild(), "events.problem.the_queue_can_only_change_while"));
        DataObject config = DataObject.fromJson(event.config());
        if (body.hasKey("queue") && !body.isNull("queue")) config.put("queue", body.getString("queue", "").strip().toUpperCase(java.util.Locale.ROOT));
        Integer hostQuota = optionalCount(body, "hostQuota");
        int releaseHours = body.hasKey("releaseHours") && !body.isNull("releaseHours") ? count(body, "releaseHours") : QueueRules.quotaSetting(config).releaseHours();
        config.put("queueQuota", DataObject.empty().put("host", hostQuota == null ? 0 : hostQuota).put("releaseHours", releaseHours));
        List<String> problems = new java.util.ArrayList<>(EventSettings.problems(config));
        if (!problems.isEmpty()) throw new EventProblem(400, problems.get(0), problems);

        java.util.Map<Long, Integer> quotas = new java.util.LinkedHashMap<>();
        Set<Long> accepted = new LinkedHashSet<>();
        for (Cohost c : cohosts.forEvent(event.id())) if (c.status() == Status.ACCEPTED) accepted.add(c.guildId());
        java.util.Map<Long, Integer> current = events.cohostQuotas(event.id());
        for (long id : accepted) quotas.put(id, current.get(id));
        if (body.hasKey("quotas") && !body.isNull("quotas")) {
            DataObject given = body.getObject("quotas");
            for (String key : given.keys()) {
                long id = idOf(key, "server");
                if (!accepted.contains(id)) throw new EventProblem(400, Text.of(actor.getGuild(), "events.problem.seat_quotas_are_only_for_servers"));
                quotas.put(id, optionalCount(given, key));
            }
        }
        String problem = QueueRules.quotaProblem(event.maxParticipants(), hostQuota, quotas.values());
        if (problem != null) throw new EventProblem(400, problem);

        if (!events.setConfig(event.guildId(), event.id(), config.toString())) throw new EventProblem(404, Text.of(actor.getGuild(), "events.problem.that_event_doesn_t_exist_in"));
        cohosts.setQuotas(event.id(), quotas);
        int moved = service.fillSeats(events.eventById(event.id()).orElse(event));
        log.info("Dashboard: {} set the fill queue of event {} ({}, host quota {}, co-host quotas {}, release {} h); {} moved in", actor.getId(), event.id(),
                config.getString("queue", "FIRST_COME"), hostQuota, quotas, releaseHours, moved);
    }

    /** A whole number 0 or more from the body, or a refusal. */
    private static int count(DataObject body, String key) {
        try {
            int n = body.getInt(key);
            if (n < 0) throw new EventProblem(400, Text.en("events.problem.numbers_here_can_t_be_negative"));
            return n;
        } catch (EventProblem e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EventProblem(400, Text.en("events.problem.use_whole_numbers_for_quotas_and"));
        }
    }

    /** A quota from the body: missing, null or 0 is "no quota". */
    private static Integer optionalCount(DataObject body, String key) {
        if (!body.hasKey(key) || body.isNull(key)) return null;
        int n = count(body, key);
        return n == 0 ? null : n;
    }

    /** What the Co-hosts card shows about the fill queue: the policy, the quotas, their sum against the player limit, and the release. */
    private DataObject queueView(Event event) {
        DataObject config = DataObject.fromJson(event.config());
        EventSettings settings = EventSettings.parse(config);
        QueueRules.QuotaSetting setting = QueueRules.quotaSetting(config);
        java.util.Map<Long, Integer> cohostQuotas = events.cohostQuotas(event.id());
        int sum = (setting.host() == null ? 0 : setting.host()) + cohostQuotas.values().stream().mapToInt(Integer::intValue).sum();
        boolean released = QueueRules.released(java.time.Instant.now(), settings.signupClosesAt(), events.setup(event.id()).signupsClosed(), setting.releaseHours());
        java.time.Instant releaseAt = QueueRules.releaseAt(settings.signupClosesAt(), setting.releaseHours());
        return DataObject.empty().put("policy", settings.queue().name()).put("hostQuota", setting.host()).put("releaseHours", setting.releaseHours())
                .put("quotaSum", sum).put("maxParticipants", event.maxParticipants()).put("released", sum > 0 && released)
                .put("releaseAt", releaseAt == null ? null : releaseAt.toString())
                .put("canChange", event.status() == EventRules.Status.SIGNUPS || event.status() == EventRules.Status.DRAFT);
    }

    private DataObject hostView(Guild guild, Event event) {
        List<Participant> players = events.participants(event.id());
        java.util.Map<Long, Integer> quotas = events.cohostQuotas(event.id());
        DataArray list = DataArray.empty();
        Set<Long> taken = new LinkedHashSet<>();
        for (Cohost c : cohosts.forEvent(event.id())) {
            if (c.status() == Status.REMOVED) continue;
            taken.add(c.guildId());
            long count = players.stream().filter(p -> p.guildId() == c.guildId()).count();
            list.add(DataObject.empty().put("guildId", Long.toString(c.guildId())).put("name", nameOf(guild, c.guildId())).put("status", c.status().name()).put("players", count)
                    .put("quota", quotas.get(c.guildId())));
        }
        DataArray candidates = DataArray.empty();
        for (long id : alliedServers(event.guildId())) {
            if (!taken.contains(id) || cohosts.get(event.id(), id).map(c -> c.status() == Status.DECLINED).orElse(false)) {
                candidates.add(DataObject.empty().put("guildId", Long.toString(id)).put("name", nameOf(guild, id)));
            }
        }
        return DataObject.empty().put("cohosts", list).put("candidates", candidates).put("max", MAX_COHOSTS)
                .put("canRemove", !events.setup(event.id()).signupsClosed()).put("queue", queueView(event))
                .put("announceStart", CohostPanels.announceStart(event));
    }

    // ---------- a co-host ----------

    /**
     * The co-host's panel channel from a request ({@code panelChannelId}, a text channel of this server JonnyBot can post in), or null when none was given.
     */
    private static Long panelChannel(Guild guild, DataObject body) {
        if (body == null || !body.hasKey("panelChannelId") || body.isNull("panelChannelId") || body.getString("panelChannelId", "").isBlank()) return null;
        long id = idOf(body.getString("panelChannelId", ""), "channel");
        net.dv8tion.jda.api.entities.channel.concrete.TextChannel channel = guild.getTextChannelById(id);
        if (channel == null) throw new ApiError(400, "That channel isn't a text channel in this server.");
        if (!CohostPanels.canPost(channel)) throw new ApiError(400, "The bot needs to see and send messages in #" + channel.getName() + ".");
        return id;
    }

    private DataObject cohostRoute(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        long me = guild.getIdLong();
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return cohostView(guild);
        }
        long eventId = idOf(parts[1], "event");
        Cohost mine = cohosts.get(eventId, me).orElseThrow(() -> new ApiError(404, "Your server isn't invited to that event."));
        Event event = events.eventById(eventId).orElseThrow(() -> new ApiError(404, "That event doesn't exist any more."));
        if (parts.length == 3 && (parts[2].equals("accept") || parts[2].equals("decline"))) {
            AdminRouteExtension.requireMethod(method, "POST");
            if (mine.status() != Status.INVITED) throw new ApiError(409, "That invitation isn't open any more.");
            if (event.status() == EventRules.Status.DONE || event.status() == EventRules.Status.CANCELLED) throw new ApiError(409, "That event is over.");
            Status to = parts[2].equals("accept") ? Status.ACCEPTED : Status.DECLINED;
            if (to == Status.ACCEPTED && !allied(event.guildId(), me)) throw new ApiError(403, "Your server is no longer in an alliance with the host.");
            Long panelChannel = to == Status.ACCEPTED ? panelChannel(guild, body.get()) : null;
            if (!cohosts.decide(eventId, me, Status.INVITED, to, actor.getIdLong())) throw new ApiError(409, "That invitation isn't open any more.");
            if (panelChannel != null) panels.setChannel(event, me, panelChannel, Actor.of(actor.getIdLong(), me, true)); // the panel goes up straight away while signups are open (T-221)
            log.info("Dashboard: {} {} co-hosting event {} for server {}", actor.getId(), to == Status.ACCEPTED ? "accepted" : "declined", eventId, me);
            service.syncTemplateTeams(event, false); // a clan-vs-clan event gives the accepting clan its team (T-222)
            return cohostView(guild);
        }
        if (parts.length == 3 && parts[2].equals("panel")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            if (mine.status() != Status.ACCEPTED) throw new ApiError(403, "Your server doesn't co-host that event.");
            Long channel = panelChannel(guild, body.get());
            panels.setChannel(event, me, channel, Actor.of(actor.getIdLong(), me, true));
            log.info("Dashboard: {} set the signup panel channel of server {} for event {} to {}", actor.getId(), me, eventId, channel);
            return cohostView(guild);
        }
        if (parts.length == 4 && parts[2].equals("players")) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            if (mine.status() != Status.ACCEPTED) throw new ApiError(403, "Your server doesn't co-host that event.");
            long userId = idOf(parts[3], "player");
            Participant p = events.participant(eventId, userId).orElseThrow(() -> new ApiError(404, "That player isn't in the event."));
            if (p.guildId() != me) throw new ApiError(403, "You can only remove players who joined through your server.");
            if (events.setup(eventId).rosterConfirmed() || event.status() != EventRules.Status.SIGNUPS) throw new ApiError(409, "The host has confirmed the roster, so only they can change it now.");
            service.leave(event.guildId(), eventId, userId);
            log.info("Dashboard: {} (co-host server {}) removed player {} from event {}", actor.getId(), me, userId, eventId);
            return cohostView(guild);
        }
        return null;
    }

    private DataObject cohostView(Guild guild) {
        long me = guild.getIdLong();
        DataArray invitations = DataArray.empty();
        DataArray hosting = DataArray.empty();
        for (Cohost c : cohosts.forGuild(me)) {
            Event e = events.eventById(c.eventId()).orElse(null);
            if (e == null) continue;
            DataObject base = DataObject.empty().put("eventId", Long.toString(e.id())).put("name", e.name()).put("host", nameOf(guild, e.guildId())).put("status", e.status().name())
                    .put("startsAt", e.startsAt() == null ? null : e.startsAt().toString());
            if (c.status() == Status.INVITED) {
                invitations.add(base);
                continue;
            }
            List<Participant> players = events.participants(e.id());
            DataArray yours = DataArray.empty();
            for (Participant p : players) {
                if (p.guildId() != me) continue;
                Member m = guild.getMemberById(p.userId());
                yours.add(DataObject.empty().put("userId", Long.toString(p.userId())).put("name", m == null ? "A member who left" : m.getEffectiveName()).put("waiting", p.waiting()));
            }
            boolean canRemove = e.status() == EventRules.Status.SIGNUPS && !events.setup(e.id()).rosterConfirmed();
            var panel = panels.panel(e.id(), me).orElse(null);
            hosting.add(base.put("players", players.stream().filter(p -> !p.waiting()).count()).put("yours", yours).put("canRemove", canRemove)
                    .put("quota", events.cohostQuotas(e.id()).get(me))
                    .put("panelChannelId", panel == null || panel.channelId() == null ? null : Long.toString(panel.channelId()))
                    .put("panelPosted", panel != null && panel.messageId() != null));
        }
        return DataObject.empty().put("invitations", invitations).put("events", hosting);
    }

    // ---------- alliances ----------

    /** Whether the two servers are both accepted members of one alliance. */
    boolean allied(long a, long b) {
        return alliedServers(a).contains(b);
    }

    /** Every server that shares an alliance with {@code host}, both accepted. */
    Set<Long> alliedServers(long host) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AllianceRepository.Alliance alliance : alliances.alliancesOf(host)) {
            List<AllianceRepository.Membership> members = alliances.membersOf(alliance.id());
            boolean hostActive = members.stream().anyMatch(m -> m.guildId() == host && m.status() == AllianceRules.Status.ACTIVE);
            if (!hostActive) continue;
            members.stream().filter(m -> m.guildId() != host && m.status() == AllianceRules.Status.ACTIVE).forEach(m -> ids.add(m.guildId()));
        }
        return ids;
    }

    private static String nameOf(Guild any, long guildId) {
        Guild g = Shards.guild(any.getJDA(), guildId);
        return g == null ? "A server the bot has left" : g.getName();
    }

    private static long idOf(String raw, String what) {
        if (raw == null || !raw.strip().matches("\\d{1,20}")) throw new ApiError(400, "That isn't a valid " + what + ".");
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That isn't a valid " + what + ".");
        }
    }
}
