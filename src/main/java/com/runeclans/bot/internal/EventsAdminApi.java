package com.runeclans.bot.internal;

import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Claim;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.BingoLayout;
import com.runeclans.bot.events.BingoShapes;
import com.runeclans.bot.events.BingoLooks;
import com.runeclans.bot.events.BingoTemplate;
import com.runeclans.bot.events.ClanVsClan;
import com.runeclans.bot.events.BingoTiles;
import com.runeclans.bot.events.EventBoardPanels;
import com.runeclans.bot.events.EventBonuses;
import com.runeclans.bot.events.EventPrep;
import com.runeclans.bot.events.EventRoster;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventSignups;
import com.runeclans.bot.events.EventSettings;
import com.runeclans.bot.events.EventTeams;
import com.runeclans.bot.events.TeamBuilder;
import com.runeclans.bot.events.PrizePlan;
import com.runeclans.bot.events.PrizeRules;
import com.runeclans.bot.events.PrizeRules.AwardStatus;
import com.runeclans.bot.events.PrizeRules.BuyInStatus;
import com.runeclans.bot.events.PrizeRules.PrizeKind;
import com.runeclans.bot.events.TeamRules;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventService.EventInput;
import com.runeclans.bot.events.EventTemplates;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The event engine for the website. Events: {@code GET/POST events}, {@code GET events/templates}, {@code GET/PUT/DELETE events/{id}},
 * {@code POST events/{id}/status}. Inside an event: {@code PUT .../objectives} (the whole list), {@code POST .../teams},
 * {@code DELETE .../teams/{teamId}}, {@code POST .../participants}, {@code PUT/DELETE .../participants/{userId}}, {@code POST .../progress},
 * {@code GET .../standings}, {@code GET/POST .../claims}, {@code POST .../claims/{claimId}/approve|deny}, {@code POST .../signup/close|reopen},
 * {@code POST .../roster/confirm} and {@code PUT .../participants/{userId}/verification}. The team builder uses {@code PUT .../teams/{teamId}} (rename, captain),
 * {@code PUT .../teams/assign} (many moves at once), {@code POST .../teams/suggest}, {@code POST .../teams/finalise|reopen} and
 * {@code PUT .../participants/{userId}/rating}. Everything is checked by
 * {@link EventService}. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server
 * and audit-logs every change.
 */
@BService
public class EventsAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EventsAdminApi.class);

    private final EventService service;
    private final EventRepository repository;
    private final EventTemplates templates;
    private final EventSignups signups;
    private final BingoTemplate bingo;
    private final EventBoardPanels panels;
    private final com.runeclans.bot.runescape.PlayerLinkRepository links;
    private final BingoTiles tiles;
    private final EventSetupApi setupApi;
    private final EventTeams eventTeams;
    private final com.runeclans.bot.events.BingoPools pools;
    private final com.runeclans.bot.events.PluginXpTracker pluginXp;

    public EventsAdminApi(EventService service, EventRepository repository, EventTemplates templates, EventSignups signups, BingoTemplate bingo, EventBoardPanels panels,
                          com.runeclans.bot.runescape.PlayerLinkRepository links, BingoTiles tiles, EventSetupApi setupApi, EventTeams eventTeams,
                          com.runeclans.bot.events.BingoPools pools, com.runeclans.bot.events.PluginXpTracker pluginXp) {
        this.pools = pools;
        this.pluginXp = pluginXp;
        this.eventTeams = eventTeams;
        this.setupApi = setupApi;
        this.links = links;
        this.tiles = tiles;
        this.service = service;
        this.repository = repository;
        this.templates = templates;
        this.signups = signups;
        this.bingo = bingo;
        this.panels = panels;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("events")) return null;
        try {
            return dispatch(guild, actor, method, parts, body);
        } catch (EventProblem e) {
            throw new ApiError(e.status() == 500 ? 500 : e.status(), e.getMessage(), e.problems());
        }
    }

    private DataObject dispatch(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 1) {
            if (method.equals("GET")) return list(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            return create(guild, actor, body.get());
        }
        if (parts.length >= 2 && EventSetupApi.handles(parts[1])) {
            DataObject answer = setupApi.route(guild, actor, method, parts, body);
            if (answer == null) throw new ApiError(404, Text.of(guild, "dashboard.that_isn_t_something_the_event"));
            return answer;
        }
        if (parts.length == 2 && parts[1].equals("templates")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return templateList();
        }
        if (parts.length == 2 && parts[1].equals("bingo-catalog")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return tiles.choices();
        }
        if (parts.length == 2 && parts[1].equals("bingo-layouts")) {
            AdminRouteExtension.requireMethod(method, "GET");
            DataArray shapes = DataArray.empty();
            BingoLayout.all().forEach(l -> shapes.add(layoutJson(l)));
            DataArray looks = DataArray.empty();
            BingoLooks.all().forEach(l -> looks.add(DataObject.empty().put("id", l.id()).put("label", l.label()).put("blurb", l.blurb())));
            return DataObject.empty().put("layouts", shapes).put("looks", looks);
        }
        if (parts.length == 2 && parts[1].equals("archive-finished")) {
            AdminRouteExtension.requireMethod(method, "POST");
            int count = service.archiveFinished(guild.getIdLong());
            log.info("Dashboard: {} archived {} finished events", actor.getId(), count);
            return list(guild).put("archivedCount", count);
        }
        long id = idOf(parts[1], "event");
        if (parts.length == 2) {
            return switch (method) {
                case "GET" -> detail(guild, id);
                case "PUT" -> update(guild, actor, id, body.get());
                case "DELETE" -> delete(guild, actor, id);
                default -> throw new ApiError(405, Text.of(guild, "dashboard.method_not_allowed"));
            };
        }
        String what = parts[2];
        if (parts.length == 3) {
            switch (what) {
                case "status" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return moveStage(guild, actor, id, body.get());
                }
                case "objectives" -> {
                    AdminRouteExtension.requireMethod(method, "PUT");
                    return setObjectives(guild, actor, id, body.get());
                }
                case "teams" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return addTeam(guild, actor, id, body.get());
                }
                case "participants" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return addParticipant(guild, actor, id, body.get());
                }
                case "bans" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    DataObject b = body.get();
                    long userId = idOf(text(b, "userId"), "player");
                    service.ban(guild.getIdLong(), id, userId, text(b, "reason"), actor.getIdLong());
                    log.info("Dashboard: {} removed {} from event {} and barred them: {}", actor.getId(), userId, id, text(b, "reason"));
                    return detail(guild, id);
                }
                case "progress" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return progress(guild, actor, id, body.get());
                }
                case "bingo" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return rerollBingo(guild, actor, id, body.get());
                }
                case "look" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    String look = body.get().hasKey("look") && !body.get().isNull("look") ? text(body.get(), "look") : "";
                    service.changeLook(guild.getIdLong(), id, look.isEmpty() ? null : look);
                    log.info("Dashboard: {} set the look of event {}'s board to {}", actor.getId(), id, look.isEmpty() ? "the default" : look);
                    return detail(guild, id);
                }
                case "board" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    service.event(guild.getIdLong(), id);
                    return DataObject.empty().put("message", panels.refresh(guild.getIdLong(), id));
                }
                case "archive" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    boolean archived = !body.get().hasKey("archived") || body.get().getBoolean("archived", true);
                    service.archive(guild.getIdLong(), id, archived);
                    log.info("Dashboard: {} {} event {}", actor.getId(), archived ? "archived" : "unarchived", id);
                    return list(guild);
                }
                case "organisers" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return changeOrganisers(guild, actor, id, body.get());
                }
                case "signup" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    signups.open(guild, id, actor.getIdLong());
                    log.info("Dashboard: {} opened the signup of event {}", actor.getId(), id);
                    return detail(guild, id);
                }
                case "prizes" -> {
                    AdminRouteExtension.requireMethod(method, "PUT");
                    return setPrizes(guild, actor, id, body.get());
                }
                case "awards" -> {
                    if (method.equals("GET")) return awards(guild, id);
                    AdminRouteExtension.requireMethod(method, "POST");
                    service.generateAwards(guild.getIdLong(), id);
                    log.info("Dashboard: {} worked out the awards for event {}", actor.getId(), id);
                    return awards(guild, id);
                }
                case "standings" -> {
                    AdminRouteExtension.requireMethod(method, "GET");
                    return standings(guild, id);
                }
                case "claims" -> {
                    if (method.equals("GET")) return claims(guild, id);
                    AdminRouteExtension.requireMethod(method, "POST");
                    return submitClaim(guild, actor, id, body.get());
                }
                default -> {
                    return null;
                }
            }
        }
        if (parts.length == 4 && what.equals("signup") && parts[3].equals("sync")) {
            AdminRouteExtension.requireMethod(method, "POST");
            java.util.Set<Long> before = new java.util.HashSet<>();
            repository.participants(id).forEach(p -> before.add(p.userId()));
            int added = signups.sync(guild, id);
            java.util.Set<Long> after = new java.util.HashSet<>();
            repository.participants(id).forEach(p -> after.add(p.userId()));
            int removed = (int) before.stream().filter(u -> !after.contains(u)).count();
            log.info("Dashboard: {} synced the signup of event {} ({} added, {} removed)", actor.getId(), id, added, removed);
            return detail(guild, id).put("synced", added).put("removed", removed);
        }
        if (parts.length == 4 && what.equals("bans")) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            long userId = idOf(parts[3], "player");
            service.unban(guild.getIdLong(), id, userId);
            log.info("Dashboard: {} lifted the ban of {} from event {}", actor.getId(), userId, id);
            return detail(guild, id);
        }
        if (parts.length == 4 && what.equals("signup") && (parts[3].equals("close") || parts[3].equals("reopen"))) {
            AdminRouteExtension.requireMethod(method, "POST");
            if (parts[3].equals("reopen")) {
                signups.reopenSignups(guild, id, com.runeclans.bot.hubevents.HubEventClient.Actor.of(actor.getIdLong(), guild.getIdLong(), true));
                log.info("Dashboard: {} reopened the signups of event {}", actor.getId(), id);
                return detail(guild, id);
            }
            EventSignups.Closed closed = signups.closeSignups(guild, id, com.runeclans.bot.hubevents.HubEventClient.Actor.of(actor.getIdLong(), guild.getIdLong(), true));
            log.info("Dashboard: {} closed the signups of event {} ({} added by the last sync, {} on the roster)", actor.getId(), id, closed.added(), closed.active());
            DataArray refused = DataArray.empty();
            closed.refused().forEach(r -> refused.add(DataObject.empty().put("userId", Long.toString(r.userId())).put("userName", name(guild, r.userId())).put("reason", r.reason())));
            return detail(guild, id).put("closed", DataObject.empty().put("added", closed.added()).put("active", closed.active()).put("waiting", closed.waiting()).put("refused", refused));
        }
        if (parts.length == 4 && what.equals("roster") && parts[3].equals("confirm")) {
            AdminRouteExtension.requireMethod(method, "POST");
            service.confirmRoster(guild.getIdLong(), id, actor.getIdLong());
            log.info("Dashboard: {} confirmed the roster of event {}", actor.getId(), id);
            return detail(guild, id);
        }
        if (parts.length == 5 && what.equals("participants") && parts[4].equals("verification")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            DataObject b = body.get();
            EventRoster.Verification to;
            try {
                to = EventRoster.Verification.valueOf(text(b, "state").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.choose_verified_waived_rejected_or_pending"));
            }
            service.setVerification(guild.getIdLong(), id, idOf(parts[3], "player"), to, actor.getIdLong(), text(b, "note"));
            log.info("Dashboard: {} set a player's verification to {} in event {}", actor.getId(), to, id);
            return detail(guild, id);
        }
        if (parts.length == 4 && what.equals("teams")) {
            return teamRoute(guild, actor, method, id, parts[3], body);
        }
        if (parts.length == 5 && what.equals("participants") && parts[4].equals("rating")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            DataObject b = body.get();
            Integer rating = null;
            if (b.hasKey("rating") && !b.isNull("rating")) {
                long value = number(b, "rating", -1);
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new ApiError(400, Text.of(guild, "dashboard.a_rating_is_from_to", TeamBuilder.MIN_RATING, TeamBuilder.MAX_RATING));
                rating = (int) value;
            }
            service.setRating(guild.getIdLong(), id, idOf(parts[3], "player"), rating);
            return detail(guild, id);
        }
        if (parts.length == 4 && what.equals("participants")) {
            long userId = idOf(parts[3], "player");
            if (method.equals("DELETE")) {
                service.leave(guild.getIdLong(), id, userId);
                return detail(guild, id);
            }
            AdminRouteExtension.requireMethod(method, "PUT");
            service.setTeam(guild.getIdLong(), id, userId, optionalId(body.get(), "teamId"));
            return detail(guild, id);
        }
        if (parts.length == 5 && what.equals("participants") && parts[4].equals("buyin")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            BuyInStatus to;
            try {
                to = BuyInStatus.valueOf(text(body.get(), "status").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.choose_a_buy_in_stage_due"));
            }
            service.setBuyIn(guild.getIdLong(), id, idOf(parts[3], "player"), to);
            log.info("Dashboard: {} set a buy-in to {} in event {}", actor.getId(), to, id);
            return detail(guild, id);
        }
        if (parts.length == 5 && what.equals("awards") && parts[4].equals("status")) {
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            AwardStatus to;
            try {
                to = AwardStatus.valueOf(text(b, "status").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.choose_an_award_stage_paid_revoked"));
            }
            service.moveAward(guild.getIdLong(), id, idOf(parts[3], "award"), to, actor.getIdLong(), text(b, "note"));
            log.info("Dashboard: {} moved an award to {} in event {}", actor.getId(), to, id);
            return awards(guild, id);
        }
        if (parts.length == 5 && what.equals("claims") && (parts[4].equals("approve") || parts[4].equals("deny"))) {
            AdminRouteExtension.requireMethod(method, "POST");
            boolean approve = parts[4].equals("approve");
            DataObject b = body.get();
            service.decideClaim(guild.getIdLong(), id, idOf(parts[3], "claim"), approve, text(b, "note"), actor.getIdLong());
            log.info("Dashboard: {} {} a claim in event {}", actor.getId(), approve ? "approved" : "denied", id);
            return claims(guild, id);
        }
        return null;
    }

    // ---------- events ----------

    DataObject list(Guild guild) {
        DataArray events = DataArray.empty();
        Map<Long, EventRepository.ListCounts> counts = repository.listCounts(guild.getIdLong());
        for (Event e : repository.events(guild.getIdLong(), List.of(), 100)) {
            EventRepository.ListCounts n = counts.getOrDefault(e.id(), EventRepository.ListCounts.NONE);
            events.add(eventJson(e).put("players", n.players()).put("waiting", n.waiting()).put("proofWaiting", n.proofWaiting()));
        }
        return DataObject.empty().put("events", events).put("maxOpen", EventService.MAX_OPEN_EVENTS);
    }

    DataObject templateList() {
        DataArray array = DataArray.empty();
        templates.all().forEach(t -> array.add(DataObject.empty().put("key", t.key()).put("label", t.label()).put("description", t.description())));
        return DataObject.empty().put("templates", array);
    }

    DataObject create(Guild guild, Member actor, DataObject body) {
        var created = input(body, null);
        // a bingo event that uses the server's own tile pool gets a copy of it now, so changing the pool later never changes this event
        if (created.config() != null) created = created.withConfig(pools.expand(guild.getIdLong(), created.template(), created.config()));
        long id = service.createEvent(guild.getIdLong(), actor.getIdLong(), created);
        log.info("Dashboard: {} created event {}", actor.getId(), id);
        return detail(guild, id);
    }

    DataObject update(Guild guild, Member actor, long id, DataObject body) {
        Event existing = service.event(guild.getIdLong(), id);
        service.updateEvent(guild.getIdLong(), id, input(body, existing));
        log.info("Dashboard: {} changed event {}", actor.getId(), id);
        return detail(guild, id);
    }

    DataObject delete(Guild guild, Member actor, long id) {
        var board = repository.panel(id).orElse(null);
        service.deleteEvent(guild.getIdLong(), id);
        panels.removeMessage(board);
        log.info("Dashboard: {} deleted event {}", actor.getId(), id);
        return list(guild);
    }

    DataObject moveStage(Guild guild, Member actor, long id, DataObject body) {
        Status to;
        try {
            to = Status.valueOf(text(body, "status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.choose_a_stage_draft_signups_running"));
        }
        service.moveStage(guild.getIdLong(), id, to);
        log.info("Dashboard: {} moved event {} to {}", actor.getId(), id, to);
        String warning = null;
        try {
            // opening signups posts the event's signup when channels were chosen; starting or ending the event closes it
            if (to == Status.SIGNUPS && signups.stateOf(service.event(guild.getIdLong(), id)).channelsChosen() && !signups.stateOf(service.event(guild.getIdLong(), id)).attached()) signups.open(guild, id, actor.getIdLong());
            signups.onStage(guild, id, to);
        } catch (EventProblem e) {
            warning = e.getMessage();
            log.warn("Event {} moved to {} but its signup wasn't updated: {}", id, to, e.getMessage());
        }
        DataObject detail = detail(guild, id);
        return warning == null ? detail : detail.put("signupWarning", warning);
    }

    DataObject detail(Guild guild, long id) {
        Event e = service.event(guild.getIdLong(), id);
        DataArray objectives = DataArray.empty();
        for (ObjectiveSpec o : service.objectives(id)) objectives.add(objectiveJson(o));
        DataArray teams = DataArray.empty();
        repository.teams(id).forEach(t -> teams.add(DataObject.empty().put("id", Long.toString(t.id())).put("name", t.name()).put("captainId", t.captainId() == null ? null : Long.toString(t.captainId()))));
        EventSettings settings = EventSettings.parse(eventJson(e).getObject("config"));
        // for an event scored by team: how strong each player looks, and whether they may be put on a team right now
        boolean teamEvent = e.scoringMode() == ScoringMode.TEAM;
        List<TeamBuilder.Player> builderPlayers = List.of();
        if (teamEvent) {
            try {
                builderPlayers = eventTeams.players(guild.getIdLong(), e);
            } catch (EventProblem problem) {
                // T-934: the team builder's players come from RuneClans now; an event the hub doesn't know has none to show here
                log.debug("No team builder players for event {}: {}", id, problem.getMessage());
            }
        }
        Map<Long, Integer> suggestedRatings = TeamBuilder.suggestedRatings(builderPlayers);
        Map<Long, Integer> effectiveRatings = TeamBuilder.effectiveRatings(builderPlayers);
        Map<Long, TeamBuilder.Strength> strengths = new java.util.HashMap<>();
        builderPlayers.stream().filter(bp -> bp.strength() != null).forEach(bp -> strengths.put(bp.userId(), bp.strength()));
        DataArray players = DataArray.empty();
        repository.participants(id).forEach(p -> players.add(DataObject.empty().put("userId", Long.toString(p.userId())).put("userName", name(guild, p.userId()))
                .put("guildId", Long.toString(p.guildId())).put("teamId", p.teamId() == null ? null : Long.toString(p.teamId())).put("joinedAt", p.joinedAt().toString())
                .put("kind", p.kind().name()).put("waiting", p.waiting()).put("buyIn", p.buyIn().name()).put("source", p.source().name())
                .put("verification", EventRoster.effective(p.verification(), settings.verification()).name())
                .put("verifiedBy", p.verifiedBy() == null ? null : name(guild, p.verifiedBy())).put("verifiedAt", p.verifiedAt() == null ? null : p.verifiedAt().toString())
                .put("verificationNote", p.verificationNote())
                .put("rating", p.rating()).put("suggestedRating", suggestedRatings.get(p.userId())).put("effectiveRating", effectiveRatings.get(p.userId()))
                .put("strength", strengthJson(strengths.get(p.userId()))).put("teamBlock", teamEvent ? service.teamBlock(e, p) : null)));
        DataArray next = DataArray.empty();
        EventRules.next(e.status()).stream().sorted().forEach(s -> next.add(s.name()));
        DataArray prizes = DataArray.empty();
        service.prizes(id).forEach(p -> prizes.add(DataObject.empty().put("id", Long.toString(p.id())).put("place", p.place()).put("placeTo", p.placeTo()).put("kind", p.kind().name())
                .put("description", p.description()).put("amount", p.amount()).put("percentOfPot", p.percentOfPot()).put("split", p.split().name())));
        var signup = signups.stateOf(e);
        DataArray refused = DataArray.empty();
        repository.refusals(id).forEach(r -> refused.add(DataObject.empty().put("userId", Long.toString(r.userId())).put("userName", name(guild, r.userId()))
                .put("reason", r.reason()).put("at", r.at().toString())));
        DataArray bans = DataArray.empty();
        service.bans(id).forEach(b -> bans.add(DataObject.empty().put("userId", Long.toString(b.userId())).put("userName", name(guild, b.userId())).put("reason", b.reason())
                .put("bannedBy", name(guild, b.bannedBy())).put("at", b.at().toString())));
        DataArray tileInfo = DataArray.empty();
        if (ClanVsClan.plays(e, BingoTemplate.KEY)) {
            for (ObjectiveSpec o : service.objectives(id)) {
                BingoTiles.Ref ref = tiles.resolve(o.label());
                tileInfo.add(DataObject.empty().put("key", o.key()).put("boss", ref.boss()).put("item", ref.item()).put("icon", ref.iconKey()));
            }
        }
        DataArray organisers = DataArray.empty();
        settings.organisers().forEach(o -> organisers.add(DataObject.empty().put("userId", Long.toString(o)).put("userName", name(guild, o))));
        DataObject layout = null;
        if (ClanVsClan.plays(e, BingoTemplate.KEY)) {
            try {
                DataObject config = DataObject.fromJson(e.config());
                var shape = BingoTemplate.layoutOf(config);
                if (shape.isPresent()) layout = layoutJson(shape.get()).put("unlock", BingoTemplate.unlockedInOrder(config) ? BingoTemplate.UNLOCK_LOCKED : BingoTemplate.UNLOCK_FREE);
            } catch (RuntimeException ignored) {
                // an unreadable board has no shape to show
            }
        }
        return DataObject.empty().put("layout", layout).put("tiles", tileInfo).put("organisers", organisers).put("refused", refused).put("bans", bans).put("event", eventJson(e)).put("signup", DataObject.empty().put("attached", signup.attached()).put("active", signup.active())
                        .put("entries", signup.entries()).put("channelsChosen", signup.channelsChosen())).put("prizes", prizes).put("pot", service.pot(guild.getIdLong(), id)).put("potVisible", PrizeRules.potVisible(settings.buyIn()))
                .put("buyInEnabled", settings.buyIn().enabled()).put("objectives", objectives).put("teams", teams).put("participants", players).put("nextStages", next)
                .put("prep", prepJson(service.prepOf(e))).put("setup", setupJson(service.setupOf(id), guild)).put("verification", verificationJson(e, settings))
                .put("editable", EventRules.editable(e.status()));
    }

    /** The setup checklist as the dashboard shows it. */
    static DataObject prepJson(EventPrep.Prep prep) {
        DataArray steps = DataArray.empty();
        prep.steps().forEach(s -> {
            DataArray blockers = DataArray.empty();
            s.blockers().forEach(blockers::add);
            steps.add(DataObject.empty().put("key", s.key()).put("label", s.label()).put("state", s.state().name()).put("required", s.required()).put("detail", s.detail()).put("blockers", blockers));
        });
        DataArray blockers = DataArray.empty();
        prep.blockers().forEach(blockers::add);
        DataArray warnings = DataArray.empty();
        prep.warnings().forEach(warnings::add);
        return DataObject.empty().put("steps", steps).put("blockers", blockers).put("warnings", warnings).put("ready", prep.ready());
    }

    private static DataObject setupJson(EventRepository.Setup setup, Guild guild) {
        return DataObject.empty().put("signupsClosedAt", setup.signupsClosedAt() == null ? null : setup.signupsClosedAt().toString())
                .put("signupsClosedBy", setup.signupsClosedBy() == null ? null : name(guild, setup.signupsClosedBy()))
                .put("rosterConfirmedAt", setup.rosterConfirmedAt() == null ? null : setup.rosterConfirmedAt().toString())
                .put("rosterConfirmedBy", setup.rosterConfirmedBy() == null ? null : name(guild, setup.rosterConfirmedBy()))
                .put("teamsFinalisedAt", setup.teamsFinalisedAt() == null ? null : setup.teamsFinalisedAt().toString())
                .put("teamsFinalisedBy", setup.teamsFinalisedBy() == null ? null : name(guild, setup.teamsFinalisedBy()));
    }

    /** What is known of a player's account: their linked RuneScape name and its latest total level, combat level and total XP. Null when nothing is known. */
    private static DataObject strengthJson(TeamBuilder.Strength strength) {
        if (strength == null) return null;
        return DataObject.empty().put("rsn", strength.rsn()).put("totalLevel", strength.totalLevel()).put("combatLevel", strength.combatLevel()).put("totalXp", strength.totalXp());
    }

    /** The event's verification settings and how many players are in each state (players on the waitlist aren't counted). */
    private DataObject verificationJson(Event e, EventSettings settings) {
        var tally = EventRoster.tally(repository.participants(e.id()).stream().filter(p -> !p.waiting()).map(EventRepository.Participant::verification).toList(), settings.verification());
        return DataObject.empty().put("required", settings.verification().required()).put("gate", settings.verification().gate().name())
                .put("instructions", settings.verification().instructions()).put("verified", tally.verified()).put("waived", tally.waived())
                .put("pending", tally.pending()).put("rejected", tally.rejected());
    }

    // ---------- organisers ----------

    /** A person from what the dashboard sent: a Discord user id, or a RuneScape name linked to a member of this server. */
    private long personFrom(Guild guild, String raw) {
        String text = raw == null ? "" : raw.strip();
        Long userId = null;
        if (text.matches("\\d{15,20}")) {
            userId = Long.parseLong(text);
        } else if (!text.isEmpty()) {
            for (var link : links.getAllLinks(guild.getIdLong())) {
                if (link.rsn().equalsIgnoreCase(text)) userId = link.discordUserId();
            }
        }
        if (userId == null) throw new ApiError(400, Text.of(guild, "dashboard.i_couldn_t_find_use_a", text));
        Member member = DiscordLookups.memberOrSlow(guild, userId);
        if (member == null || member.getUser().isBot()) throw new ApiError(400, Text.of(guild, "dashboard.isn_t_a_member_of_this", text));
        return userId;
    }

    /** {@code {add: ["Name or user id", ...], remove: ["user id", ...]}}: names or removes organisers of one event. */
    DataObject changeOrganisers(Guild guild, Member actor, long id, DataObject body) {
        List<Long> add = new ArrayList<>();
        if (body.hasKey("add") && !body.isNull("add")) {
            DataArray array = body.getArray("add");
            for (int i = 0; i < array.length() && i < 20; i++) add.add(personFrom(guild, array.getString(i)));
        }
        List<Long> remove = new ArrayList<>();
        if (body.hasKey("remove") && !body.isNull("remove")) {
            DataArray array = body.getArray("remove");
            for (int i = 0; i < array.length() && i < 20; i++) {
                String raw = array.getString(i).strip();
                if (!raw.matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_an_organiser"));
                remove.add(Long.parseLong(raw));
            }
        }
        service.changeOrganisers(guild.getIdLong(), id, add, remove);
        log.info("Dashboard: {} changed the organisers of event {} (+{} -{})", actor.getId(), id, add.size(), remove.size());
        return detail(guild, id);
    }

    // ---------- objectives, teams, players ----------

    DataObject setObjectives(Guild guild, Member actor, long id, DataObject body) {
        List<ObjectiveSpec> specs = new ArrayList<>();
        DataArray array = body.hasKey("objectives") && !body.isNull("objectives") ? body.getArray("objectives") : DataArray.empty();
        for (int i = 0; i < array.length(); i++) {
            DataObject o = array.getObject(i);
            String label = text(o, "label");
            String key = text(o, "key");
            Evidence evidence;
            try {
                evidence = Evidence.valueOf(text(o, "evidence").isEmpty() ? "EITHER" : text(o, "evidence").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.choose_how_progress_is_recorded_auto", label));
            }
            List<String> prerequisites = new ArrayList<>();
            if (o.hasKey("prerequisites") && !o.isNull("prerequisites")) {
                DataArray p = o.getArray("prerequisites");
                for (int j = 0; j < p.length(); j++) prerequisites.add(p.getString(j));
            }
            specs.add(new ObjectiveSpec(key.isEmpty() ? EventRules.keyFrom(label) : key, label, text(o, "description"), number(o, "points", 0), evidence,
                    number(o, "target", 1), (int) number(o, "repeatLimit", 1), prerequisites));
        }
        service.setObjectives(guild.getIdLong(), id, specs);
        log.info("Dashboard: {} saved {} objectives for event {}", actor.getId(), specs.size(), id);
        return detail(guild, id);
    }

    DataObject addTeam(Guild guild, Member actor, long id, DataObject body) {
        Long captain = optionalId(body, "captainId");
        service.addTeam(guild.getIdLong(), id, text(body, "name"), captain);
        log.info("Dashboard: {} added a team to event {}", actor.getId(), id);
        return detail(guild, id);
    }

    /**
     * Inside {@code teams/}: {@code PUT teams/{teamId}} renames a team and/or names its captain ({@code {name, captainId}}, a null {@code captainId} clears it),
     * {@code DELETE teams/{teamId}} removes it, {@code PUT teams/assign} makes many moves at once ({@code {assignments: [{userId, teamId}]}}, all or none),
     * {@code POST teams/suggest} answers with a starting point for the teams without changing anything, and {@code POST teams/finalise|reopen} calls the teams
     * final or takes that back.
     */
    private DataObject teamRoute(Guild guild, Member actor, String method, long id, String what, Supplier<DataObject> body) {
        long guildId = guild.getIdLong();
        switch (what) {
            case "assign" -> {
                AdminRouteExtension.requireMethod(method, "PUT");
                DataObject b = body.get();
                if (!b.hasKey("assignments") || b.isNull("assignments")) throw new ApiError(400, Text.of(guild, "dashboard.send_the_moves_to_make"));
                DataArray array = b.getArray("assignments");
                if (array.length() > 1000) throw new ApiError(400, Text.of(guild, "dashboard.that_is_too_many_moves_at"));
                Map<Long, Long> moves = new LinkedHashMap<>();
                for (int i = 0; i < array.length(); i++) {
                    DataObject move = array.getObject(i);
                    Long userId = optionalId(move, "userId");
                    if (userId == null) throw new ApiError(400, Text.of(guild, "dashboard.every_move_needs_a_player"));
                    moves.put(userId, optionalId(move, "teamId"));
                }
                int changed = service.assignTeams(guildId, id, moves, user -> {
                    String name = name(guild, user);
                    return name == null ? "Player " + user : name;
                });
                log.info("Dashboard: {} moved {} players between the teams of event {}", actor.getId(), changed, id);
                return detail(guild, id);
            }
            case "suggest" -> {
                if (ClanVsClan.is(service.event(guildId, id).template())) throw new ApiError(409, Text.of(guild, "dashboard.in_a_clan_vs_clan_event"));
                AdminRouteExtension.requireMethod(method, "POST");
                TeamBuilder.Suggestion suggestion = eventTeams.suggest(guildId, id);
                DataArray moves = DataArray.empty();
                suggestion.moves().forEach(m -> moves.add(DataObject.empty().put("userId", Long.toString(m.userId())).put("teamId", m.teamId() == null ? null : Long.toString(m.teamId()))));
                DataArray notes = DataArray.empty();
                suggestion.notes().forEach(notes::add);
                return DataObject.empty().put("moves", moves).put("notes", notes);
            }
            case "finalise" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                service.finaliseTeams(guildId, id, actor.getIdLong());
                log.info("Dashboard: {} finalised the teams of event {}", actor.getId(), id);
                return detail(guild, id);
            }
            case "reopen" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                service.reopenTeams(guildId, id);
                log.info("Dashboard: {} reopened the teams of event {}", actor.getId(), id);
                return detail(guild, id);
            }
            default -> {
                long teamId = idOf(what, "team");
                if (method.equals("DELETE")) {
                    service.removeTeam(guildId, id, teamId);
                    log.info("Dashboard: {} removed a team from event {}", actor.getId(), id);
                    return detail(guild, id);
                }
                AdminRouteExtension.requireMethod(method, "PUT");
                DataObject b = body.get();
                if (b.hasKey("name") && !b.isNull("name")) service.renameTeam(guildId, id, teamId, text(b, "name"));
                if (b.hasKey("captainId")) service.setCaptain(guildId, id, teamId, optionalId(b, "captainId"));
                log.info("Dashboard: {} changed a team of event {}", actor.getId(), id);
                return detail(guild, id);
            }
        }
    }

    DataObject addParticipant(Guild guild, Member actor, long id, DataObject body) {
        Long userId = optionalId(body, "userId");
        if (userId == null) throw new ApiError(400, Text.of(guild, "dashboard.pick_a_player"));
        Member member = guild.getMemberById(userId);
        if (member == null || member.getUser().isBot()) throw new ApiError(400, Text.of(guild, "dashboard.that_player_isn_t_in_this"));
        TeamRules.Kind kind = null;
        if (!text(body, "kind").isEmpty()) {
            try {
                kind = TeamRules.Kind.valueOf(text(body, "kind").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.choose_the_kind_of_player_home"));
            }
        }
        var joined = service.join(guild.getIdLong(), id, guild.getIdLong(), userId, optionalId(body, "teamId"), true, kind);
        if (joined.waiting()) log.info("Dashboard: {} put player {} on the waitlist of event {} at {}", actor.getId(), userId, id, joined.position());
        log.info("Dashboard: {} added player {} to event {}", actor.getId(), userId, id);
        return detail(guild, id);
    }

    // ---------- progress, claims, standings ----------

    DataObject progress(Guild guild, Member actor, long id, DataObject body) {
        Long userId = optionalId(body, "userId");
        if (userId == null) throw new ApiError(400, Text.of(guild, "dashboard.pick_a_player"));
        var recorded = service.recordProgress(guild.getIdLong(), id, text(body, "objective"), userId, number(body, "amount", 1), Source.MANUAL,
                text(body, "sourceKey").isEmpty() ? null : "manual:" + text(body, "sourceKey"), actor.getIdLong(), text(body, "note"));
        log.info("Dashboard: {} recorded progress in event {}", actor.getId(), id);
        return DataObject.empty().put("progressId", Long.toString(recorded.progressId())).put("total", recorded.total()).put("completions", recorded.completions())
                .put("standings", standings(guild, id).getArray("standings"));
    }

    DataObject standings(Guild guild, long id) {
        Event event = service.event(guild.getIdLong(), id);
        var teams = repository.teams(id);
        DataArray array = DataArray.empty();
        int rank = 1;
        for (Standing s : service.standings(guild.getIdLong(), id)) {
            boolean team = s.subject().startsWith("t:");
            long subjectId = Long.parseLong(s.subject().substring(2));
            String label = team ? teams.stream().filter(t -> t.id() == subjectId).map(EventRepository.Team::name).findFirst().orElse("A removed team") : name(guild, subjectId);
            DataObject completions = DataObject.empty();
            s.completions().forEach(completions::put);
            DataArray earned = DataArray.empty();
            EventBonuses.earned(service.bonusesOf(event), s.completions()).forEach(b -> earned.add(b.name()));
            array.add(DataObject.empty().put("rank", rank++).put("subject", s.subject()).put("kind", team ? "TEAM" : "PLAYER").put("label", label).put("points", s.points()).put("bonuses", earned)
                    .put("objectivesCompleted", s.objectivesCompleted()).put("completions", completions)
                    .put("lastCompletedAt", s.lastCompletedAt() == null ? null : s.lastCompletedAt().toString()));
        }
        DataObject answer = DataObject.empty().put("scoringMode", event.scoringMode().name()).put("standings", array);
        if (ClanVsClan.is(event.template())) answer.put("clanVsClan", clanVsClanJson(guild, event));
        // bingo and scavenger XP tiles (T-543) list the same "not counted (no RuneSync)" players, marked so the page words it for tiles
        if (pluginXp != null) pluginXp.notCounted(id).ifPresent(n -> answer.put("pluginXp", pluginXpJson(guild, n).put("xpTiles", !com.runeclans.bot.events.ClanVsClan.plays(event, com.runeclans.bot.events.CompetitionTemplate.KEY))));
        if (pluginXp != null && com.runeclans.bot.events.CompetitionTemplate.countsAuto(DataObject.fromJson(event.config()))) answer.put("autoXp", autoXpJson(guild, id));
        return answer;
    }

    /** A competition counted from RuneSync (T-537): who scored nothing on the last pass because the hub can't count them, for "not counted (no RuneSync)". */
    private DataObject pluginXpJson(Guild guild, com.runeclans.bot.events.PluginXpTracker.NotCounted n) {
        DataArray list = DataArray.empty();
        n.userIds().stream().sorted().forEach(u -> list.add(DataObject.empty().put("userId", Long.toString(u)).put("name", name(guild, u))));
        // "count confirmed links only" (T-552): counted by the hub, but their RuneLink isn't confirmed yet
        DataArray unconfirmed = DataArray.empty();
        n.unconfirmed().stream().sorted().forEach(u -> unconfirmed.add(DataObject.empty().put("userId", Long.toString(u)).put("name", name(guild, u))));
        return DataObject.empty().put("notCounted", list).put("unconfirmed", unconfirmed).put("checkedAt", n.checkedAt().toString());
    }

    /**
     * An auto competition (T-557): who is counted from RuneSync (everyone else is from RuneMetrics), and the RuneSync players whose RuneMetrics
     * gain disagrees, for the organiser to look at. Nothing is corrected on its own.
     */
    private DataObject autoXpJson(Guild guild, long id) {
        DataArray runeSync = DataArray.empty();
        pluginXp.runeSyncPlayers(id).stream().sorted().forEach(u -> runeSync.add(DataObject.empty().put("userId", Long.toString(u)).put("name", name(guild, u))));
        DataArray disagree = DataArray.empty();
        pluginXp.disagreements(id).stream().sorted(java.util.Comparator.comparingLong(com.runeclans.bot.events.PluginXpTracker.Disagreement::userId))
                .forEach(d -> disagree.add(DataObject.empty().put("userId", Long.toString(d.userId())).put("name", name(guild, d.userId()))
                        .put("runeSyncXp", d.runeSyncXp()).put("runeMetricsXp", d.runeMetricsXp()).put("checkedAt", d.checkedAt().toString())));
        return DataObject.empty().put("runeSync", runeSync).put("disagreements", disagree);
    }

    /** A clan-vs-clan event's scoring, and what each player earned for their clan (T-222), best first within each clan. */
    private DataObject clanVsClanJson(Guild guild, Event event) {
        ClanVsClan.Settings settings = ClanVsClan.settings(DataObject.fromJson(event.config()));
        var earned = ClanVsClan.contributions(repository.objectives(event.id()), com.runeclans.bot.events.ClanVsClanTemplate.reports(repository.progress(event.id())));
        DataObject teams = DataObject.empty();
        earned.forEach((teamId, players) -> {
            DataArray list = DataArray.empty();
            players.entrySet().stream().sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                    .forEach(p -> list.add(DataObject.empty().put("userId", Long.toString(p.getKey())).put("name", name(guild, p.getKey())).put("points", p.getValue())));
            teams.put(Long.toString(teamId), list);
        });
        return DataObject.empty().put("game", settings.game()).put("scoring", settings.scoring().name()).put("topN", settings.topN()).put("contributions", teams);
    }

    DataObject claims(Guild guild, long id) {
        service.event(guild.getIdLong(), id);
        DataArray pending = DataArray.empty();
        repository.pendingClaims(id, 100).forEach(c -> pending.add(claimJson(guild, c)));
        DataArray decided = DataArray.empty();
        repository.decidedClaims(id, 50).forEach(c -> decided.add(claimJson(guild, c)));
        return DataObject.empty().put("pending", pending).put("decided", decided);
    }

    DataObject submitClaim(Guild guild, Member actor, long id, DataObject body) {
        Long userId = optionalId(body, "userId");
        if (userId == null) throw new ApiError(400, Text.of(guild, "dashboard.pick_a_player"));
        long claimId = service.submitClaim(guild.getIdLong(), id, text(body, "objective"), userId, number(body, "amount", 1), text(body, "note"), text(body, "proofUrl"));
        log.info("Dashboard: {} filed claim {} in event {}", actor.getId(), claimId, id);
        return claims(guild, id);
    }

    // ---------- bingo ----------

    /** A board shape as the dashboard needs it: its tiles in rows from the top, and which tiles each one needs first. */
    static DataObject layoutJson(BingoLayout l) {
        DataArray rows = DataArray.empty();
        l.rows().forEach(row -> {
            DataArray numbers = DataArray.empty();
            row.forEach(numbers::add);
            rows.add(numbers);
        });
        DataArray rowNames = DataArray.empty();
        l.rowNames().forEach(rowNames::add);
        DataObject requires = DataObject.empty();
        l.requires().forEach((number, needs) -> {
            DataArray list = DataArray.empty();
            needs.forEach(list::add);
            requires.put(Integer.toString(number), list);
        });
        DataObject json = DataObject.empty().put("id", l.id()).put("name", l.name()).put("blurb", l.blurb()).put("rule", l.rule()).put("tiles", l.tiles())
                .put("rows", rows).put("rowNames", rowNames).put("requires", requires).put("size", l.sizeParam()).put("generated", l.generated());
        // what the shape can be sized to, for the size picker
        BingoShapes.range(l.id()).ifPresent(r -> json.put("range", DataObject.empty().put("what", r.what()).put("min", r.min()).put("max", r.max()).put("picture", r.picture())));
        return json;
    }

    DataObject rerollBingo(Guild guild, Member actor, long id, DataObject body) {
        Event event = service.event(guild.getIdLong(), id);
        if (!ClanVsClan.plays(event, BingoTemplate.KEY)) throw new ApiError(409, Text.of(guild, "dashboard.this_event_isn_t_a_bingo"));
        Set<String> locked = new HashSet<>();
        if (body.hasKey("lockedKeys") && !body.isNull("lockedKeys")) {
            DataArray keys = body.getArray("lockedKeys");
            for (int i = 0; i < keys.length(); i++) locked.add(keys.getString(i));
        }
        Integer size = body.hasKey("size") && !body.isNull("size") ? (int) number(body, "size", 0) : null;
        Long seed = body.hasKey("seed") && !body.isNull("seed") && !text(body, "seed").isEmpty() ? number(body, "seed", 0) : null;
        try {
            if (body.hasKey("replace") && !body.isNull("replace")) {
                DataObject r = body.getObject("replace");
                var replaced = bingo.replaceTile(DataObject.fromJson(event.config()), service.objectives(id), (int) number(r, "number", 0), text(r, "label"), number(r, "points", 0));
                service.reconfigure(guild.getIdLong(), id, replaced.config(), replaced.objectives());
                log.info("Dashboard: {} replaced tile {} of the bingo board of event {}", actor.getId(), number(r, "number", 0), id);
                return detail(guild, id);
            }
            String shape = body.hasKey("layout") && !body.isNull("layout") ? text(body, "layout") : null;
            Integer shapeSize = body.hasKey("shapeSize") && !body.isNull("shapeSize") ? (int) number(body, "shapeSize", 0) : null;
            var rerolled = bingo.reroll(DataObject.fromJson(event.config()), service.objectives(id), size, seed, locked, shape == null || shape.isEmpty() ? null : shape, shapeSize);
            service.reconfigure(guild.getIdLong(), id, rerolled.config(), rerolled.objectives());
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, e.getMessage());
        }
        log.info("Dashboard: {} re-rolled the bingo board of event {}", actor.getId(), id);
        return detail(guild, id);
    }

    // ---------- prizes and awards ----------

    DataObject setPrizes(Guild guild, Member actor, long id, DataObject body) {
        List<PrizePlan.Prize> prizes = new ArrayList<>();
        DataArray array = body.hasKey("prizes") && !body.isNull("prizes") ? body.getArray("prizes") : DataArray.empty();
        for (int i = 0; i < array.length(); i++) {
            DataObject p = array.getObject(i);
            PrizeKind kind;
            PrizePlan.Split split;
            try {
                kind = PrizeKind.valueOf(text(p, "kind").isEmpty() ? "IN_GAME" : text(p, "kind").toUpperCase(Locale.ROOT));
                split = PrizePlan.Split.valueOf(text(p, "split").isEmpty() ? "EQUAL" : text(p, "split").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.choose_the_prize_kind_in_game"));
            }
            int place = (int) number(p, "place", 1);
            prizes.add(new PrizePlan.Prize(0, place, (int) number(p, "placeTo", place), kind, text(p, "description"), number(p, "amount", 0), (int) number(p, "percentOfPot", 0), split));
        }
        service.setPrizes(guild.getIdLong(), id, prizes);
        log.info("Dashboard: {} saved {} prizes for event {}", actor.getId(), prizes.size(), id);
        return detail(guild, id);
    }

    DataObject awards(Guild guild, long id) {
        DataArray array = DataArray.empty();
        for (EventRepository.AwardRow a : service.awards(guild.getIdLong(), id)) {
            DataArray history = DataArray.empty();
            service.awardHistory(guild.getIdLong(), id, a.id()).forEach(h -> history.add(DataObject.empty().put("from", h.from().name()).put("to", h.to().name())
                    .put("byName", h.by() == null ? null : name(guild, h.by())).put("note", h.note()).put("at", h.at().toString())));
            array.add(DataObject.empty().put("id", Long.toString(a.id())).put("place", a.place()).put("userId", Long.toString(a.userId())).put("userName", name(guild, a.userId()))
                    .put("kind", a.kind().name()).put("description", a.description()).put("amount", a.amount()).put("status", a.status().name()).put("history", history));
        }
        return DataObject.empty().put("awards", array);
    }

    // ---------- json ----------

    private EventInput input(DataObject body, Event existing) {
        Scope scope;
        ScoringMode mode;
        try {
            scope = body.hasKey("scope") ? Scope.valueOf(text(body, "scope").toUpperCase(Locale.ROOT)) : existing != null ? existing.scope() : Scope.PRIVATE;
            mode = body.hasKey("scoringMode") ? ScoringMode.valueOf(text(body, "scoringMode").toUpperCase(Locale.ROOT)) : existing != null ? existing.scoringMode() : ScoringMode.INDIVIDUAL;
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, Text.en("dashboard.choose_who_may_see_the_event_2"));
        }
        DataObject config = body.hasKey("config") && !body.isNull("config") ? body.getObject("config") : null;
        return new EventInput(body.hasKey("name") ? text(body, "name") : existing == null ? "" : existing.name(),
                body.hasKey("description") ? text(body, "description") : existing == null ? "" : existing.description(),
                existing != null ? existing.template() : text(body, "template").isEmpty() ? "custom" : text(body, "template"), scope, mode,
                instant(body, "startsAt", existing == null ? null : existing.startsAt()), instant(body, "endsAt", existing == null ? null : existing.endsAt()),
                body.hasKey("maxParticipants") ? (int) number(body, "maxParticipants", 0) : existing == null ? 0 : existing.maxParticipants(), config);
    }

    private DataObject eventJson(Event e) {
        DataObject config;
        try {
            config = DataObject.fromJson(e.config());
        } catch (RuntimeException ex) {
            config = DataObject.empty();
        }
        return DataObject.empty().put("id", Long.toString(e.id())).put("name", e.name()).put("description", e.description()).put("template", e.template())
                .put("status", e.status().name()).put("scope", e.scope().name()).put("scoringMode", e.scoringMode().name())
                .put("startsAt", e.startsAt() == null ? null : e.startsAt().toString()).put("endsAt", e.endsAt() == null ? null : e.endsAt().toString())
                .put("maxParticipants", e.maxParticipants()).put("config", config).put("createdAt", e.createdAt().toString()).put("archived", e.archived());
    }

    private static DataObject objectiveJson(ObjectiveSpec o) {
        DataArray prerequisites = DataArray.empty();
        o.prerequisites().forEach(prerequisites::add);
        return DataObject.empty().put("key", o.key()).put("label", o.label()).put("description", o.description()).put("points", o.points())
                .put("evidence", o.evidence().name()).put("target", o.target()).put("repeatLimit", o.repeatLimit()).put("prerequisites", prerequisites);
    }

    private DataObject claimJson(Guild guild, Claim c) {
        return DataObject.empty().put("id", Long.toString(c.id())).put("objective", c.objectiveKey()).put("userId", Long.toString(c.userId())).put("userName", name(guild, c.userId()))
                .put("kind", c.kind().name()).put("amount", c.amount()).put("note", c.note()).put("proofUrl", c.proofUrl()).put("status", c.status().name())
                .put("decidedByName", c.decidedBy() == null ? null : name(guild, c.decidedBy())).put("decisionNote", c.decisionNote())
                .put("createdAt", c.createdAt().toString()).put("decidedAt", c.decidedAt() == null ? null : c.decidedAt().toString());
    }

    // ---------- small helpers ----------

    /** An id from the path. A Discord id can have 19 digits (the newest user ids do), which still fits a long; one that doesn't is refused, not crashed on. */
    static long idOf(String raw, String what) {
        if (raw == null || !raw.matches("\\d{1,19}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid", what));
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid", what));
        }
    }

    private static Long optionalId(DataObject body, String key) {
        if (!body.hasKey(key) || body.isNull(key)) return null;
        String raw = body.getString(key, "").strip();
        if (raw.isEmpty()) return null;
        if (!raw.matches("\\d{1,20}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_id"));
        return Long.parseLong(raw);
    }

    private static String text(DataObject body, String key) {
        return body.hasKey(key) && !body.isNull(key) ? body.getString(key, "").strip() : "";
    }

    private static long number(DataObject body, String key, long fallback) {
        if (!body.hasKey(key) || body.isNull(key)) return fallback;
        try {
            return body.getLong(key);
        } catch (RuntimeException e) {
            throw new ApiError(400, Text.en("dashboard.must_be_a_whole_number_2", key));
        }
    }

    private static Instant instant(DataObject body, String key, Instant fallback) {
        if (!body.hasKey(key)) return fallback;
        if (body.isNull(key) || body.getString(key, "").isBlank()) return null;
        try {
            return Instant.parse(body.getString(key).strip());
        } catch (DateTimeParseException e) {
            throw new ApiError(400, Text.en("dashboard.must_be_a_date_and_time", key));
        }
    }

    private static String name(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getEffectiveName();
    }
}
