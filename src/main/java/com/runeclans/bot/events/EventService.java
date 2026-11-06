package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Claim;
import com.runeclans.bot.events.EventRepository.ClaimStatus;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Entry;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventRepository.AwardRow;
import com.runeclans.bot.events.PrizePlan.Place;
import com.runeclans.bot.events.PrizeRules.AwardStatus;
import com.runeclans.bot.events.PrizeRules.BuyInStatus;
import com.runeclans.bot.events.PrizeRules.PrizeKind;
import com.runeclans.bot.events.TeamRules.Kind;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.points.PointsRules;
import com.runeclans.bot.points.PointsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The event engine's rules applied to real events: who may do what in which stage, one answer for every way of getting here (the dashboard
 * today, Discord and the plugin later). Refusals are {@link EventProblem}s with a reason an admin can read. Standings are never stored: they
 * are worked out from the progress log by {@link EventScoring}.
 */
@BService
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    /** A server can have this many events that aren't finished, so one server can't pile up unlimited work. */
    public static final int MAX_OPEN_EVENTS = 20;
    public static final int MAX_PENDING_CLAIMS_PER_PLAYER = 5;
    /** The objective key stored on a verification claim, which has no objective. */
    public static final String VERIFICATION_KEY = "verification";

    /** The settings of an event as admins send them. */
    public record EventInput(String name, String description, String template, Scope scope, ScoringMode scoringMode, Instant startsAt, Instant endsAt,
                             int maxParticipants, DataObject config) {
        public EventInput withConfig(DataObject newConfig) {
            return new EventInput(name, description, template, scope, scoringMode, startsAt, endsAt, maxParticipants, newConfig);
        }
    }

    public record Recorded(long progressId, long total, int completions) {}

    /** Where a join ended up: in a place, or on the waitlist at {@code position} (1 is next). */
    public record Joined(boolean waiting, int position) {}

    private final EventRepository repository;
    private final EventTemplates templates;
    private final EventKinds kinds;
    private final PointsService points;
    private final EventChanges changes;

    public EventService(EventRepository repository, EventTemplates templates, EventKinds kinds, PointsService points, EventChanges changes) {
        this.repository = repository;
        this.templates = templates;
        this.kinds = kinds;
        this.points = points;
        this.changes = changes;
    }

    // ---------- events ----------

    public long createEvent(long guildId, long actorId, EventInput in) {
        // wholeSpan is skill of the week's own (T-541): never taken from the dashboard, a setup or a saved template
        if (in.config() != null && in.config().hasKey("wholeSpan")) in = in.withConfig(DataObject.fromJson(in.config().toJson()).remove("wholeSpan"));
        return create(guildId, actorId, in);
    }

    /** {@link #createEvent} for skill of the week (T-541), the one caller whose competition may count its whole span ({@code wholeSpan}). */
    long createWholeSpanEvent(long guildId, long actorId, EventInput in) {
        return create(guildId, actorId, in);
    }

    private long create(long guildId, long actorId, EventInput in) {
        EventTemplate template = templates.byKey(in.template()).orElseThrow(() -> new EventProblem(400, Text.of(guildId, "events.problem.that_kind_of_event_doesn_t")));
        DataObject config = in.config() == null ? DataObject.empty() : in.config();
        List<String> problems = new ArrayList<>(EventRules.eventProblems(in.name(), in.description(), in.maxParticipants(), in.startsAt(), in.endsAt()));
        problems.addAll(template.configProblems(config));
        problems.addAll(EventSettings.problems(config));
        String quota = QueueRules.quotaProblem(in.maxParticipants(), QueueRules.quotaSetting(config).host(), List.of());
        if (quota != null) problems.add(quota);
        if (!problems.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.the_event_can_t_be_created"), problems);
        try {
            config = template.prepare(config);
        } catch (IllegalArgumentException e) {
            throw new EventProblem(400, Text.of(guildId, "events.problem.the_event_can_t_be_created"), List.of(e.getMessage()));
        }
        List<ObjectiveSpec> starterObjectives = template.starterObjectives(config);
        List<String> bonusProblems = EventBonuses.problems(config, starterObjectives.stream().map(ObjectiveSpec::key).collect(java.util.stream.Collectors.toSet()));
        if (!bonusProblems.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.the_event_can_t_be_created"), bonusProblems);
        if (repository.countOpen(guildId) >= MAX_OPEN_EVENTS) throw new EventProblem(409, Text.of(guildId, "events.problem.a_server_can_have_unfinished_events", MAX_OPEN_EVENTS));
        List<String> starterProblems = checkObjectives(starterObjectives);
        if (!starterProblems.isEmpty()) {
            log.error("Template {} produced invalid starter objectives: {}", template.key(), starterProblems);
            throw new EventProblem(500, Text.of(guildId, "events.problem.that_template_couldn_t_set_itself"));
        }
        ScoringMode mode = template.fixedScoringMode() != null ? template.fixedScoringMode() : in.scoringMode();
        long id = repository.createEvent(guildId, template.key(), in.name().strip(), clean(in.description()), in.scope(), mode, in.startsAt(), in.endsAt(),
                in.maxParticipants(), config.toString(), actorId);
        if (!starterObjectives.isEmpty()) repository.replaceObjectives(id, starterObjectives);
        if (template.managesTeams()) repository.event(guildId, id).ifPresent(e -> template.syncTeams(e, false));
        return id;
    }

    public Event event(long guildId, long id) {
        return repository.event(guildId, id).filter(e -> !QuickGroups.isGroup(e)).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_event_doesn_t_exist_in")));
    }

    public void updateEvent(long guildId, long id, EventInput in) {
        Event existing = event(guildId, id);
        if (!EventRules.editable(existing.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.an_event_can_only_be_changed"));
        EventTemplate template = templates.byKey(existing.template()).orElseThrow(() -> new EventProblem(409, Text.of(guildId, "events.problem.this_event_template_no_longer_exists")));
        DataObject config = in.config() == null ? DataObject.fromJson(existing.config()) : DataObject.fromJson(in.config().toString());
        if (CompetitionTemplate.countsWholeSpan(DataObject.fromJson(existing.config()))) {
            // skill of the week (T-541): its dates are the week's, the span the tracker counts for everyone, so they stay as they are
            in = new EventInput(in.name(), in.description(), in.template(), in.scope(), in.scoringMode(), existing.startsAt(), existing.endsAt(), in.maxParticipants(), in.config());
        }
        if (in.config() != null) {
            DataObject kept = DataObject.fromJson(existing.config());
            for (String key : template.fixedSettings()) {
                if (kept.hasKey(key)) config.put(key, kept.get(key));
                else config.remove(key);
            }
            keepOrganisers(kept, config); // organisers are changed with their own call, so a settings save can't wipe them
            keepChannels(kept, config); // so are the roles and channels, on the Channels tab
            keepTakedown(kept, config); // only JonnyBot staff take an event off the events hub or put it back
            keepUpdates(kept, config); // a bingo's board update rules (T-650) are saved on the Updates tab, so a stale settings copy can't wipe them
        }
        List<String> problems = new ArrayList<>(EventRules.eventProblems(in.name(), in.description(), in.maxParticipants(), in.startsAt(), in.endsAt()));
        problems.addAll(template.configProblems(config));
        problems.addAll(EventSettings.problems(config));
        // seat quotas (T-220): the host's and the co-hosts' together may not be more than the player limit
        String quota = QueueRules.quotaProblem(in.maxParticipants(), QueueRules.quotaSetting(config).host(), repository.cohostQuotas(id).values());
        if (quota != null) problems.add(quota);
        if (!problems.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.the_event_can_t_be_saved"), problems);
        ScoringMode mode = template.fixedScoringMode() != null ? template.fixedScoringMode() : in.scoringMode();
        if (!repository.updateEvent(guildId, id, in.name().strip(), clean(in.description()), in.scope(), mode, in.startsAt(), in.endsAt(), in.maxParticipants(), config.toString())) {
            throw new EventProblem(409, Text.of(guildId, "events.problem.the_event_moved_on_while_you"));
        }
        changes.publish(EventChanges.Kind.SETUP, guildId, id);
    }

    public void moveStage(long guildId, long id, Status to) {
        Event event = event(guildId, id);
        if (!EventRules.canMove(event.status(), to)) {
            throw new EventProblem(409, Text.of(guildId, "events.problem.an_event_can_t_go_from", label(event.status()), label(to)));
        }
        if (to == Status.RUNNING && repository.objectives(id).isEmpty()) throw new EventProblem(409, Text.of(guildId, "events.problem.add_at_least_one_objective_before"));
        if (to == Status.RUNNING) {
            // an event that follows the setup checklist, or asks for verification before the start, can't start until that is settled
            EventSettings settings = settingsOf(event);
            boolean gated = settings.setupMode() != EventPrep.Mode.OFF || (settings.verification().required() && settings.verification().gate() == EventRoster.Gate.START);
            if (gated) {
                EventPrep.Prep prep = prepOf(event);
                if (!prep.ready()) throw new EventProblem(409, Text.of(guildId, "events.problem.the_event_isn_t_ready_to"), prep.blockers());
            }
        }
        if (!repository.moveStage(guildId, id, event.status(), to)) throw new EventProblem(409, Text.of(guildId, "events.problem.someone_else_already_moved_this_event"));
        changes.publish(EventChanges.Kind.STAGE, guildId, id);
    }

    /** Deletes an event and everything in it. Only a draft or an event that is over: cancel or finish one that is running first. */
    public void deleteEvent(long guildId, long id) {
        event(guildId, id);
        // deleting the event would forget which roles and channels are its own, leaving them behind on Discord
        if (repository.setup(id).spaceApplied()) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_has_roles_and_channels"));
        if (!repository.deleteEvent(guildId, id)) throw new EventProblem(409, Text.of(guildId, "events.problem.cancel_or_finish_the_event_before"));
    }

    /** Hides a finished event from the dashboard's list ({@code archived} true) or brings it back. */
    public void archive(long guildId, long id, boolean archived) {
        event(guildId, id);
        if (!repository.setArchived(guildId, id, archived)) throw new EventProblem(409, Text.of(guildId, "events.problem.only_a_finished_or_cancelled_event"));
    }

    public int archiveFinished(long guildId) {
        return repository.archiveFinished(guildId);
    }

    // ---------- objectives, teams, players ----------

    public List<ObjectiveSpec> objectives(long eventId) {
        return repository.objectives(eventId);
    }

    public void setObjectives(long guildId, long id, List<ObjectiveSpec> objectives) {
        Event event = event(guildId, id);
        if (!EventRules.editable(event.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.objectives_can_only_be_changed_while"));
        List<String> problems = new ArrayList<>(checkObjectives(objectives));
        problems.addAll(EventBonuses.problems(DataObject.fromJson(event.config()), objectives.stream().map(ObjectiveSpec::key).collect(java.util.stream.Collectors.toSet())));
        if (!problems.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.the_objectives_can_t_be_saved"), problems.stream().distinct().toList());
        repository.replaceObjectives(id, objectives);
        changes.publish(EventChanges.Kind.SETUP, guildId, id);
    }

    /** Problems with each objective and with the set as a whole. */
    public static List<String> checkObjectives(List<ObjectiveSpec> objectives) {
        List<String> problems = new ArrayList<>();
        for (ObjectiveSpec o : objectives) problems.addAll(EventRules.objectiveProblems(o));
        problems.addAll(EventRules.objectiveSetProblems(objectives));
        return problems.stream().distinct().toList();
    }

    /** Adds a team. A captain, if one is named, is put on the team and made its captain (they have to be a player who may be on a team). */
    public long addTeam(long guildId, long id, String name, Long captainId) {
        Event event = event(guildId, id);
        if (!EventRules.editable(event.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.teams_can_only_be_changed_while"));
        String problem = EventRules.teamNameProblem(name);
        if (problem != null) throw new EventProblem(400, problem);
        OptionalLong team = repository.addTeam(id, name.strip(), null, EventRules.MAX_TEAMS);
        if (team.isEmpty()) throw new EventProblem(409, Text.of(guildId, "events.problem.there_is_already_a_team_with", EventRules.MAX_TEAMS));
        if (captainId != null) {
            try {
                setTeam(guildId, id, captainId, team.getAsLong());
                setCaptain(guildId, id, team.getAsLong(), captainId);
            } catch (EventProblem e) {
                repository.removeTeam(id, team.getAsLong());
                throw e;
            }
        }
        repository.touchTeams(id);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
        return team.getAsLong();
    }

    public void removeTeam(long guildId, long id, long teamId) {
        Event event = event(guildId, id);
        if (!EventRules.editable(event.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.teams_can_only_be_changed_while"));
        if (!repository.removeTeam(id, teamId)) throw new EventProblem(404, Text.of(guildId, "events.problem.that_team_doesn_t_exist"));
        repository.touchTeams(id);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    public void renameTeam(long guildId, long id, long teamId, String name) {
        Event event = event(guildId, id);
        if (!EventRules.editable(event.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.teams_can_only_be_changed_while"));
        String problem = EventRules.teamNameProblem(name);
        if (problem != null) throw new EventProblem(400, problem);
        int renamed = repository.renameTeam(id, teamId, name.strip());
        if (renamed < 0) throw new EventProblem(409, Text.of(guildId, "events.problem.there_is_already_a_team_with_2"));
        if (renamed == 0) throw new EventProblem(404, Text.of(guildId, "events.problem.that_team_doesn_t_exist"));
        repository.touchTeams(id);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    /** Names a team's captain, who has to be a player on that team; null for none. */
    public void setCaptain(long guildId, long id, long teamId, Long captainId) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over"));
        if (repository.teams(id).stream().noneMatch(t -> t.id() == teamId)) throw new EventProblem(404, Text.of(guildId, "events.problem.that_team_doesn_t_exist"));
        if (!repository.setCaptain(id, teamId, captainId)) throw new EventProblem(409, Text.of(guildId, "events.problem.a_captain_has_to_be_a"));
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    /** The bonuses in the event's settings. */
    public List<EventBonuses.Bonus> bonusesOf(Event event) {
        try {
            return EventBonuses.parse(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Replaces an event's settings and objectives together (used when a template regenerates what it built, like a bingo board). Only while the event is being built. */
    public void reconfigure(long guildId, long id, DataObject config, List<ObjectiveSpec> objectives) {
        Event event = event(guildId, id);
        if (!EventRules.editable(event.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.this_can_only_be_changed_while"));
        keepOrganisers(DataObject.fromJson(event.config()), config);
        keepUpdates(DataObject.fromJson(event.config()), config);
        List<String> problems = new ArrayList<>(checkObjectives(objectives));
        problems.addAll(EventSettings.problems(config));
        problems.addAll(EventBonuses.problems(config, objectives.stream().map(ObjectiveSpec::key).collect(java.util.stream.Collectors.toSet())));
        if (!problems.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.that_can_t_be_saved_yet"), problems.stream().distinct().toList());
        if (!repository.updateEvent(guildId, id, event.name(), event.description(), event.scope(), event.scoringMode(), event.startsAt(), event.endsAt(), event.maxParticipants(), config.toString())) {
            throw new EventProblem(409, Text.of(guildId, "events.problem.the_event_moved_on_while_you"));
        }
        repository.replaceObjectives(id, objectives);
        changes.publish(EventChanges.Kind.SETUP, guildId, id);
    }

    /**
     * Changes what a bingo board is drawn on ({@link BingoLooks}); {@code null} goes back to how the board was first drawn. Only the picture changes, never the
     * tiles or rules, so it is allowed at any stage until the event is over, and the live board is redrawn straight away.
     */
    public void changeLook(long guildId, long id, String look) {
        Event event = event(guildId, id);
        if (!ClanVsClan.plays(event, BingoTemplate.KEY)) throw new EventProblem(409, Text.of(guildId, "events.problem.only_a_bingo_has_a_board"));
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over_so_its"));
        if (look != null && !BingoLooks.valid(look)) throw new EventProblem(400, Text.of(guildId, "events.problem.choose_a_board_look_from_the"));
        DataObject config = DataObject.fromJson(event.config());
        DataObject board = DataObject.fromJson((config.hasKey("board") && !config.isNull("board") ? config.getObject("board") : DataObject.empty()).toString());
        if (look == null) board.remove("look");
        else board.put("look", look);
        config.put("board", board);
        if (!repository.setConfig(guildId, id, config.toString())) throw new EventProblem(404, Text.of(guildId, "events.problem.that_event_doesn_t_exist_in"));
        changes.publish(EventChanges.Kind.SETUP, guildId, id);
    }

    private static void keepOrganisers(DataObject from, DataObject into) {
        if (from.hasKey("organisers") && !from.isNull("organisers")) into.put("organisers", from.get("organisers"));
        else into.remove("organisers");
    }

    /** {@code updates} (T-650) has its own route ({@code PUT events/{id}/updates}): a settings save or a re-rolled board keeps what was there. */
    static void keepUpdates(DataObject from, DataObject into) {
        if (from.hasKey("updates") && !from.isNull("updates")) into.put("updates", from.get("updates"));
        else into.remove("updates");
    }

    private static void keepChannels(DataObject from, DataObject into) {
        if (from.hasKey("channels") && !from.isNull("channels")) into.put("channels", from.get("channels"));
        else into.remove("channels");
    }

    /** {@code hub.takedown} is JonnyBot staff's (T-216/T-218): a settings save keeps what was there and can't add one. The rest of {@code hub} is the host's. */
    static void keepTakedown(DataObject from, DataObject into) {
        Object old = null;
        try {
            if (from.hasKey("hub") && !from.isNull("hub") && from.getObject("hub").hasKey("takedown") && !from.getObject("hub").isNull("takedown")) old = from.getObject("hub").get("takedown");
        } catch (RuntimeException ignored) {
            // an unreadable hub section has nothing to keep
        }
        DataObject hub;
        try {
            hub = into.hasKey("hub") && !into.isNull("hub") ? into.getObject("hub") : null;
        } catch (RuntimeException e) {
            into.remove("hub");
            hub = null;
        }
        if (old == null) {
            if (hub != null) hub.remove("takedown");
            return;
        }
        if (hub == null) {
            hub = DataObject.empty();
            into.put("hub", hub);
        }
        hub.put("takedown", old);
    }

    // ---------- roles and channels ----------

    /**
     * Saves the choices for an event's roles and channels ({@code config.channels}, see {@link EventSpace}), whole: what is not in {@code channels} goes back
     * to its default. Allowed until the event is over; a space that already exists is brought in line with them the next time it is updated.
     */
    public void setChannelSettings(long guildId, long id, DataObject channels) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over_so_its_2"));
        DataObject config = DataObject.fromJson(event.config());
        config.put("channels", DataObject.fromJson(channels.toString()));
        List<String> problems = EventSpace.problems(config);
        if (!problems.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.the_channels_can_t_be_saved"), problems);
        if (!repository.setConfig(guildId, id, config.toString())) throw new EventProblem(404, Text.of(guildId, "events.problem.that_event_doesn_t_exist_in"));
        changes.publish(EventChanges.Kind.SETUP, guildId, id);
    }

    /**
     * Saves what happens to the roles and channels once the event is over ({@code config.wrapup}, see {@link EventWrapup}), whole. Allowed at any time, also
     * after the event finished, because that is when it matters.
     */
    public void setWrapupSettings(long guildId, long id, DataObject wrapup) {
        Event event = event(guildId, id);
        DataObject config = DataObject.fromJson(event.config());
        config.put("wrapup", DataObject.fromJson(wrapup.toString()));
        List<String> problems = EventWrapup.problems(config);
        if (!problems.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.the_wrap_up_settings_can_t"), problems);
        if (!repository.setConfig(guildId, id, config.toString())) throw new EventProblem(404, Text.of(guildId, "events.problem.that_event_doesn_t_exist_in"));
        changes.publish(EventChanges.Kind.SETUP, guildId, id);
    }

    /** The players who hold a place and count right now, whom the event's roles and channels are for: those on the waitlist and any not verified in time don't. */
    public List<Participant> countedPlayers(Event event) {
        EventSettings settings = settingsOf(event);
        EventRoster.Gate moment = teamMoment(event);
        return repository.participants(event.id()).stream().filter(p -> !p.waiting() && EventRoster.counts(p.verification(), settings.verification(), moment)).toList();
    }

    // ---------- organisers ----------

    /** Whether this person may run the event from Discord: staff always, otherwise only someone named an organiser of it. */
    public boolean mayOrganise(Event event, long userId, boolean staff) {
        return staff || settingsOf(event).isOrganiser(userId);
    }

    public List<Long> organisers(long guildId, long id) {
        return new ArrayList<>(settingsOf(event(guildId, id)).organisers());
    }

    /**
     * Names or removes organisers of one event, at any stage until it is finished. Removing someone who isn't one, or adding someone who already is,
     * is fine. Returns the organisers after the change.
     */
    public List<Long> changeOrganisers(long guildId, long id, java.util.Collection<Long> add, java.util.Collection<Long> remove) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over_so_its_3"));
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>(settingsOf(event).organisers());
        ids.addAll(add);
        ids.removeAll(remove);
        if (ids.size() > EventSettings.MAX_ORGANISERS) throw new EventProblem(400, Text.of(guildId, "events.problem.an_event_can_have_up_to", EventSettings.MAX_ORGANISERS));
        DataObject config = DataObject.fromJson(event.config());
        if (ids.isEmpty()) {
            config.remove("organisers");
        } else {
            net.dv8tion.jda.api.utils.data.DataArray array = net.dv8tion.jda.api.utils.data.DataArray.empty();
            ids.forEach(i -> array.add(Long.toString(i)));
            config.put("organisers", array);
        }
        if (!repository.setConfig(guildId, id, config.toString())) throw new EventProblem(404, Text.of(guildId, "events.problem.that_event_doesn_t_exist_in"));
        return new ArrayList<>(ids);
    }

    public EventSettings settingsOf(Event event) {
        try {
            return EventSettings.parse(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return EventSettings.defaults();
        }
    }

    /** The event's settings as the engine reads them. */
    public EventSettings settings(Event event) {
        return settingsOf(event);
    }

    /**
     * Adds a player. {@code staff} is true when an organiser adds them (late entries, past the signup deadline) rather than the player. The player's
     * kind comes from {@code kindOverride} or is worked out; the event's team policy decides whether they may join and share a team. A full event
     * puts them on the waitlist, ordered by the host's queue policy.
     */
    public Joined join(long guildId, long id, long userGuildId, long userId, Long teamId, boolean staff, Kind kindOverride) {
        return join(guildId, id, userGuildId, userId, teamId, staff, kindOverride, !staff);
    }

    /**
     * As above. {@code applyQuota} decides whether the seat quota of the player's server applies (T-220): it does for anyone signing up themselves,
     * including from a Discord signup, and not for staff adding a late entry.
     */
    public Joined join(long guildId, long id, long userGuildId, long userId, Long teamId, boolean staff, Kind kindOverride, boolean applyQuota) {
        Event event = event(guildId, id);
        if (!EventRules.acceptsJoins(event.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_isn_t_taking_signups"));
        var barred = repository.ban(id, userId);
        if (barred.isPresent()) {
            throw new EventProblem(403, staff ? Text.of(guildId, "events.problem.that_player_was_removed_from_this", barred.get().reason()) : Text.of(guildId, "events.problem.you_can_t_take_part_in", barred.get().reason()));
        }
        EventSettings settings = settingsOf(event);
        if (!staff && (repository.setup(id).signupsClosed() || !settings.selfSignupOpen(Instant.now()))) throw new EventProblem(409, Text.of(guildId, "events.problem.signups_have_closed_ask_an_organiser"));
        Kind kind = kindOverride != null ? kindOverride : kinds.kindOf(guildId, userGuildId, userId);
        if (!TeamRules.mayJoin(settings.teamPolicy(), kind)) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_isn_t_open_to", describe(kind)));
        if (teamId != null) {
            checkTeam(id, settings, teamId, kind, userId);
            // a new player hasn't been verified, so before the event runs they can't be put straight onto a team when it needs verification by now
            if (teamMoment(event) == EventRoster.Gate.TEAMS && !EventRoster.counts(EventRoster.Verification.PENDING, settings.verification(), EventRoster.Gate.TEAMS)) {
                throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_needs_players_verified_before"));
            }
        }
        if (teamId == null) teamId = templateTeam(event, userGuildId, userId, staff);
        if (event.scoringMode() == ScoringMode.TEAM && teamId == null && event.status() == Status.RUNNING) {
            throw new EventProblem(400, Text.of(guildId, "events.problem.pick_a_team_this_event_is"));
        }
        Integer originQuota = applyQuota && event.maxParticipants() > 0 ? quotas(event, settings, Instant.now()).limitFor(userGuildId) : null;
        var waiting = repository.join(id, userId, userGuildId, teamId, kind, settings.buyIn().enabled() ? BuyInStatus.DUE : BuyInStatus.NONE, event.maxParticipants(), originQuota);
        if (waiting.isEmpty()) throw new EventProblem(409, Text.of(guildId, "events.problem.that_player_is_already_in_the"));
        repository.setSource(id, userId, staff ? EventRepository.RosterSource.STAFF : EventRepository.RosterSource.SELF);
        repository.touchRoster(id);
        repository.clearRefusal(id, userId);
        // a player on the waitlist holds no place, so has no team until they move up
        if (waiting.get() && teamId != null) repository.setTeam(id, userId, null);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
        // a player who joined on their own is added to the event's Discord signup too, so the two lists stay the same
        if (!staff) changes.publish(new EventChanges.Change(EventChanges.Kind.PLAYER_JOINED, guildId, id, 0, userId, false, ""));
        if (!waiting.get()) return new Joined(false, 0);
        return new Joined(true, QueueRules.position(settings.queue(), event.guildId(), queueEntries(id), userId));
    }

    /**
     * The team the event's template puts a joining player on (clan vs clan: their clan's), or null. A refusal stands for a player joining themselves; staff
     * adding someone the template can't place add them with no team. A player who can't go straight onto a team yet (verification by teams) gets none and
     * is placed at Close signups.
     */
    private Long templateTeam(Event event, long userGuildId, long userId, boolean staff) {
        EventTemplate template = templates.byKey(event.template()).orElse(null);
        if (template == null || !template.managesTeams()) return null;
        Long team;
        try {
            team = template.teamOnJoin(event, userGuildId, userId, staff);
        } catch (EventProblem e) {
            if (staff) return null;
            throw e;
        }
        if (team == null) return null;
        if (teamMoment(event) == EventRoster.Gate.TEAMS && !EventRoster.counts(EventRoster.Verification.PENDING, settingsOf(event).verification(), EventRoster.Gate.TEAMS)) return null;
        return team;
    }

    /** Lets the event's template bring the teams it keeps up to date (clan vs clan: one per clan); {@code recheck} also re-places the players. */
    public void syncTemplateTeams(Event event, boolean recheck) {
        EventTemplate template = templates.byKey(event.template()).orElse(null);
        if (template == null || !template.managesTeams()) return;
        template.syncTeams(event, recheck);
        repository.touchTeams(event.id());
        changes.publish(EventChanges.Kind.TEAMS, event.guildId(), event.id());
    }

    private List<QueueRules.Entry> queueEntries(long id) {
        return waitingEntries(repository.participants(id));
    }

    private static List<QueueRules.Entry> waitingEntries(List<EventRepository.Participant> players) {
        return players.stream().filter(EventRepository.Participant::waiting).map(p -> new QueueRules.Entry(p.userId(), p.kind(), p.joinedAt(), p.guildId())).toList();
    }

    /** Where a waiting player stands (1 is next), in the event's queue order; 0 when they aren't waiting. */
    public int queuePosition(Event event, long userId) {
        return QueueRules.position(settingsOf(event).queue(), event.guildId(), queueEntries(event.id()), userId);
    }

    /**
     * The seat quotas in force for an event at {@code now} (T-220): the host's from its settings and each accepted co-host's, released once signups have
     * closed or the release time has passed. {@link QueueRules.Quotas#NONE} when nobody has a quota.
     */
    public QueueRules.Quotas quotas(Event event, EventSettings settings, Instant now) {
        QueueRules.QuotaSetting setting = QueueRules.quotaSetting(configOf(event));
        java.util.Map<Long, Integer> seats = new java.util.LinkedHashMap<>(repository.cohostQuotas(event.id()));
        if (setting.host() != null) seats.put(event.guildId(), setting.host());
        if (seats.isEmpty()) return QueueRules.Quotas.NONE;
        boolean released = QueueRules.released(now, settings.signupClosesAt(), repository.setup(event.id()).signupsClosed(), setting.releaseHours());
        return new QueueRules.Quotas(seats, released);
    }

    private static DataObject configOf(Event event) {
        try {
            return DataObject.fromJson(event.config());
        } catch (RuntimeException e) {
            return DataObject.empty();
        }
    }

    /**
     * Gives free places to the waitlist, in the event's queue order, while each player's server has room under its quota (T-220). Called when a place
     * frees up, when quotas change, and by the signup sweep once quota places are released. Returns how many moved in.
     */
    public int fillSeats(Event event) {
        if (event.maxParticipants() <= 0) return 0;
        EventSettings settings = settingsOf(event);
        QueueRules.Quotas quotas = quotas(event, settings, Instant.now());
        int moved = 0;
        for (int guard = 0; guard < EventRules.MAX_PARTICIPANTS; guard++) {
            List<EventRepository.Participant> players = repository.participants(event.id());
            if (players.stream().filter(p -> !p.waiting()).count() >= event.maxParticipants()) break;
            java.util.Map<Long, Integer> activeByOrigin = new java.util.HashMap<>();
            for (EventRepository.Participant p : players) if (!p.waiting()) activeByOrigin.merge(p.guildId(), 1, Integer::sum);
            QueueRules.Entry next = QueueRules.next(settings.queue(), event.guildId(), waitingEntries(players), quotas, activeByOrigin);
            if (next == null || !repository.promote(event.id(), next.userId(), event.maxParticipants())) break;
            moved++;
        }
        if (moved > 0) repository.touchRoster(event.id());
        return moved;
    }

    private void checkTeam(long id, EventSettings settings, long teamId, Kind kind, long userId) {
        if (repository.teams(id).stream().noneMatch(t -> t.id() == teamId)) throw new EventProblem(404, Text.en("events.problem.that_team_doesn_t_exist"));
        List<Kind> mates = repository.participants(id).stream().filter(p -> p.userId() != userId && p.teamId() != null && p.teamId() == teamId).map(EventRepository.Participant::kind).toList();
        if (!TeamRules.mayShareTeam(settings.teamPolicy(), kind, mates)) throw new EventProblem(409, Text.en("events.problem.this_event_doesn_t_let_share", describe(kind)));
    }

    private static String describe(Kind kind) {
        return switch (kind) {
            case HOME_CLAN -> "clan members";
            case HOME_OTHER -> "players who aren't in the clan";
            case PARTNER -> "players from other servers";
        };
    }

    /** Removes a player and gives their place to the next person waiting. A player who has paid a buy-in must be refunded first, so nothing is lost. */
    public void leave(long guildId, long id, long userId) {
        Event event = event(guildId, id);
        var player = repository.participant(id, userId).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the")));
        if (player.buyIn() == BuyInStatus.PAID) throw new EventProblem(409, Text.of(guildId, "events.problem.they_have_paid_a_buy_in"));
        if (!repository.leave(id, userId)) throw new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the"));
        repository.touchRoster(id);
        repository.touchTeams(id);
        if (!player.waiting()) fillSeats(event);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
        // and out of the event's Discord signup, so removing someone here removes them there
        changes.publish(new EventChanges.Change(EventChanges.Kind.PLAYER_LEFT, guildId, id, 0, userId, false, ""));
    }

    public static final int MAX_BAN_REASON = 300;

    /**
     * Removes a player (if they are in the event) and bars them from taking part again, with the reason. Their Discord signup entry goes too. A player who
     * has paid a buy-in must be refunded first, as with any removal. Also works for someone who isn't in the event yet, to keep them out.
     */
    public void ban(long guildId, long id, long userId, String reason, long actorId) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over"));
        String cleanReason = clean(reason);
        if (cleanReason.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.say_why_so_it_can_be"));
        if (cleanReason.length() > MAX_BAN_REASON) throw new EventProblem(400, Text.of(guildId, "events.problem.keep_the_reason_to_characters_or", MAX_BAN_REASON));
        if (repository.participant(id, userId).isPresent()) leave(guildId, id, userId);
        repository.addBan(id, userId, cleanReason, actorId);
        repository.clearRefusal(id, userId);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    /** Lets a barred player take part again. They are not put back on the roster: they sign up again like anyone. */
    public void unban(long guildId, long id, long userId) {
        event(guildId, id);
        if (!repository.removeBan(id, userId)) throw new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_barred_from"));
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    public List<EventRepository.Ban> bans(long id) {
        return repository.bans(id);
    }

    // ---------- closing signups, the roster and verification ----------

    public EventRepository.Setup setupOf(long eventId) {
        return repository.setup(eventId);
    }

    /** The setup checklist for an event: what is done, what to do now, and what stops it from starting. */
    public EventPrep.Prep prepOf(Event event) {
        EventSettings settings = settingsOf(event);
        EventRepository.Setup setup = repository.setup(event.id());
        List<Participant> players = repository.participants(event.id());
        List<EventRoster.Verification> held = players.stream().filter(p -> !p.waiting()).map(Participant::verification).toList();
        EventPrep.TeamFacts teams = event.scoringMode() == ScoringMode.TEAM ? teamFactsOf(event, settings, players, setup) : null;
        EventPrep.SpaceFacts space = settings.channels().enabled() ? new EventPrep.SpaceFacts(setup.spaceApplied(), setup.spaceComplete(), setup.spaceNote()) : null;
        EventPrep.Prep prep = EventPrep.of(new EventPrep.Facts(event.status(), settings.setupMode(), !repository.objectives(event.id()).isEmpty(), setup.signupsClosed(), setup.rosterConfirmed(),
                held.size(), players.size() - held.size(), settings.verification(), EventRoster.tally(held, settings.verification()), teams, space,
                setup.rosterChangedSinceConfirmed(), teams != null && setup.teamsChangedSinceFinal()));
        EventTemplate template = templates.byKey(event.template()).orElse(null);
        if (template == null) return prep;
        List<EventPrep.Step> steps = new ArrayList<>(prep.steps());
        List<String> warnings = new ArrayList<>(prep.warnings());
        template.checklist(event, steps, warnings);
        return new EventPrep.Prep(steps, prep.blockers(), warnings);
    }

    /** The teams as the setup checklist and "finalise" look at them. */
    private EventPrep.TeamFacts teamFactsOf(Event event, EventSettings settings, List<Participant> players, EventRepository.Setup setup) {
        List<EventRepository.Team> teams = repository.teams(event.id());
        java.util.Set<Long> teamIds = new java.util.HashSet<>();
        teams.forEach(t -> teamIds.add(t.id()));
        EventRoster.Gate moment = teamMoment(event);
        java.util.Map<Long, Integer> members = new HashMap<>();
        int pool = 0;
        int unassigned = 0;
        int excludedOnTeams = 0;
        for (Participant p : players) {
            if (p.waiting()) continue;
            boolean onTeam = p.teamId() != null && teamIds.contains(p.teamId());
            if (onTeam) members.merge(p.teamId(), 1, Integer::sum);
            if (EventRoster.counts(p.verification(), settings.verification(), moment)) {
                pool++;
                if (!onTeam) unassigned++;
            } else if (onTeam) {
                excludedOnTeams++;
            }
        }
        List<String> empty = teams.stream().filter(t -> members.getOrDefault(t.id(), 0) == 0).map(EventRepository.Team::name).toList();
        return new EventPrep.TeamFacts(new TeamBuilder.Facts(teams.size(), pool, unassigned, empty, excludedOnTeams), setup.teamsFinalised());
    }

    // ---------- building the teams ----------

    /** The moment players are counted at when teams are built: when teams are made, or, once the event runs, when it started. */
    static EventRoster.Gate teamMoment(Event event) {
        return event.status() == Status.RUNNING || event.status() == Status.SCORING || event.status() == Status.DONE ? EventRoster.Gate.START : EventRoster.Gate.TEAMS;
    }

    /**
     * Why a player can't be put on a team right now, in words, or null when they can: they hold a place in the event and, when the event asks for verification,
     * have passed it by now (verified, or let through by an organiser).
     */
    public String teamBlock(Event event, Participant player) {
        return teamBlock(event, player, "That player");
    }

    /** {@link #teamBlock(Event, Participant)} with the player called {@code who} in the sentence (a name, for a list of reasons). */
    public String teamBlock(Event event, Participant player, String who) {
        if (player.waiting()) return who + " is on the waitlist, so they can't be put on a team yet.";
        EventRoster.Setting verification = settingsOf(event).verification();
        if (EventRoster.counts(player.verification(), verification, teamMoment(event))) return null;
        return switch (EventRoster.effective(player.verification(), verification)) {
            case REJECTED -> who + (who.endsWith("s") ? "'" : "'s") + " verification was turned down, so they can't be put on a team. Verify or waive them first.";
            default -> who + " hasn't been verified, which this event needs before teams are made. Verify or waive them first.";
        };
    }

    /**
     * Puts many players on teams, or takes them off (a null team), all or nothing: every move is checked first, against the teams as they will be once all of
     * them are made, and nothing changes if any of them can't be. {@code names} turns a player's id into what to call them in the reasons. Returns how many
     * players were changed.
     */
    public int assignTeams(long guildId, long id, Map<Long, Long> assignments, java.util.function.LongFunction<String> names) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over"));
        if (assignments.isEmpty()) return 0;
        EventSettings settings = settingsOf(event);
        Map<Long, Participant> players = new HashMap<>();
        repository.participants(id).forEach(p -> players.put(p.userId(), p));
        java.util.Set<Long> teamIds = new java.util.HashSet<>();
        repository.teams(id).forEach(t -> teamIds.add(t.id()));
        List<String> problems = new ArrayList<>();
        Map<Long, Long> teamAfter = new HashMap<>();
        players.values().forEach(p -> teamAfter.put(p.userId(), p.teamId()));
        for (Map.Entry<Long, Long> move : assignments.entrySet()) {
            Participant p = players.get(move.getKey());
            String who = names.apply(move.getKey());
            if (p == null) {
                problems.add(who + " isn't in the event.");
                continue;
            }
            if (move.getValue() != null) {
                if (!teamIds.contains(move.getValue())) {
                    problems.add("The team " + who + " was moved to doesn't exist.");
                    continue;
                }
                String block = teamBlock(event, p, who);
                if (block != null) {
                    problems.add(block);
                    continue;
                }
            }
            teamAfter.put(move.getKey(), move.getValue());
        }
        // the teams as they will be: everyone on a team must be allowed to share it with the others
        Map<Long, List<Participant>> membersAfter = new HashMap<>();
        for (Participant p : players.values()) {
            Long team = teamAfter.get(p.userId());
            if (team != null && teamIds.contains(team)) membersAfter.computeIfAbsent(team, k -> new ArrayList<>()).add(p);
        }
        for (List<Participant> members : membersAfter.values()) {
            for (Participant p : members) {
                List<Kind> mates = members.stream().filter(o -> o.userId() != p.userId()).map(Participant::kind).toList();
                if (!TeamRules.mayShareTeam(settings.teamPolicy(), p.kind(), mates)) {
                    problems.add(names.apply(p.userId()) + ": this event doesn't let " + describe(p.kind()) + " share a team with some of the players on that team.");
                }
            }
        }
        if (!problems.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.those_teams_can_t_be_set"), problems.stream().distinct().toList());
        int changed = repository.setTeams(id, assignments);
        repository.touchTeams(id);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
        return changed;
    }

    /** Records how strong an organiser thinks a player is, 1 (weakest) to 5 (strongest), or clears it with null. */
    public void setRating(long guildId, long id, long userId, Integer rating) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over"));
        if (rating != null && !TeamBuilder.validRating(rating)) throw new EventProblem(400, Text.of(guildId, "events.problem.a_rating_is_from_weakest_to", TeamBuilder.MIN_RATING, TeamBuilder.MAX_RATING));
        if (!repository.setRating(id, userId, rating)) throw new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the"));
    }

    /**
     * Calls the teams final: the event is scored by team, every player who may be on a team is on one, no team is empty and nobody who hasn't passed
     * verification is on a team. A managed event also needs its roster confirmed first, so the teams are made from the final list. Players can still be moved
     * afterwards (the checklist says so if that leaves someone without a team).
     */
    public void finaliseTeams(long guildId, long id, long actorId) {
        Event event = event(guildId, id);
        if (event.scoringMode() != ScoringMode.TEAM) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_scored_by_player"));
        if (!EventRules.editable(event.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.teams_can_only_be_finalised_while"));
        EventSettings settings = settingsOf(event);
        EventRepository.Setup setup = repository.setup(id);
        if (settings.setupMode() != EventPrep.Mode.OFF && !setup.rosterConfirmed()) throw new EventProblem(409, Text.of(guildId, "events.problem.confirm_the_roster_first_so_the"));
        List<String> problems = TeamBuilder.problems(teamFactsOf(event, settings, repository.participants(id), setup).teams());
        if (!problems.isEmpty()) throw new EventProblem(409, Text.of(guildId, "events.problem.the_teams_can_t_be_final"), problems);
        if (!repository.finaliseTeams(id, actorId)) throw new EventProblem(409, Text.of(guildId, "events.problem.the_teams_are_already_final"));
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    /** Takes back "the teams are final", so they can be reworked. */
    public void reopenTeams(long guildId, long id) {
        Event event = event(guildId, id);
        if (!EventRules.editable(event.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.teams_can_only_be_reopened_while"));
        if (!repository.reopenTeams(id)) throw new EventProblem(409, Text.of(guildId, "events.problem.the_teams_aren_t_final"));
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    /**
     * Closes signups: from now players can't join themselves (staff can still add late entries). The Discord signup is paused and the roster synced one last
     * time by {@link EventSignups#closeSignups}, which is what callers use; this is the rule itself.
     */
    public void closeSignups(long guildId, long id, long actorId) {
        Event event = event(guildId, id);
        if (event.status() != Status.SIGNUPS) throw new EventProblem(409, Text.of(guildId, "events.problem.signups_can_only_be_closed_while"));
        if (!repository.closeSignups(id, actorId)) throw new EventProblem(409, Text.of(guildId, "events.problem.signups_are_already_closed"));
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    /** Opens signups again. The roster has to be confirmed again once they are closed. */
    public void reopenSignups(long guildId, long id) {
        Event event = event(guildId, id);
        if (!EventPrep.canReopenSignups(event.status(), repository.setup(id).signupsClosed())) throw new EventProblem(409, Text.of(guildId, "events.problem.signups_aren_t_closed_or_the"));
        if (!repository.reopenSignups(id)) throw new EventProblem(409, Text.of(guildId, "events.problem.signups_aren_t_closed"));
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    /** Says the roster has been checked and is final. Signups have to be closed first. */
    public void confirmRoster(long guildId, long id, long actorId) {
        Event event = event(guildId, id);
        EventRepository.Setup setup = repository.setup(id);
        if (event.status() != Status.SIGNUPS) throw new EventProblem(409, Text.of(guildId, "events.problem.the_roster_can_only_be_confirmed"));
        if (!setup.signupsClosed()) throw new EventProblem(409, Text.of(guildId, "events.problem.close_signups_first_so_the_roster"));
        if (setup.rosterConfirmed() && !setup.rosterChangedSinceConfirmed()) throw new EventProblem(409, Text.of(guildId, "events.problem.the_roster_is_already_confirmed"));
        if (!repository.confirmRoster(id, actorId)) throw new EventProblem(409, Text.of(guildId, "events.problem.the_roster_is_already_confirmed"));
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    /** An organiser's decision on a player's verification: verified, turned down (with a reason), waived, or back to pending. Only for an event that asks for it. */
    public void setVerification(long guildId, long id, long userId, EventRoster.Verification to, long actorId, String note) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over"));
        if (!settingsOf(event).verification().required()) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_doesn_t_ask_for"));
        if (!EventRoster.settable(to)) throw new EventProblem(400, Text.of(guildId, "events.problem.choose_verified_waived_turned_down_or"));
        String cleanNote = clean(note);
        if (cleanNote.length() > EventRules.MAX_NOTE) throw new EventProblem(400, Text.of(guildId, "events.problem.keep_the_note_to_characters_or", EventRules.MAX_NOTE));
        if (to == EventRoster.Verification.REJECTED && cleanNote.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.say_why_they_were_turned_down"));
        repository.participant(id, userId).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the")));
        if (!repository.setVerification(id, userId, to, actorId, cleanNote)) throw new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the"));
        repository.touchRoster(id);
        changes.publish(EventChanges.Kind.ROSTER, guildId, id);
    }

    /** Whether a player may take part once the event runs: always without verification, otherwise only when verified or waived. */
    public boolean mayPlay(Event event, Participant player) {
        return EventRoster.counts(player.verification(), settingsOf(event).verification(), EventRoster.Gate.START);
    }

    /** Puts a player on a team, or takes them off it (null). A player who holds no place, or hasn't passed the verification the event needs, can't be put on one. */
    public void setTeam(long guildId, long id, long userId, Long teamId) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over"));
        var player = repository.participant(id, userId).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the")));
        if (teamId != null) {
            String block = teamBlock(event, player);
            if (block != null) throw new EventProblem(409, block);
            checkTeam(id, settingsOf(event), teamId, player.kind(), userId);
        }
        if (!repository.setTeam(id, userId, teamId)) throw new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the"));
        repository.touchTeams(id);
        changes.publish(EventChanges.Kind.TEAMS, guildId, id);
    }

    // ---------- buy-ins and the pot ----------

    /** Moves a player's buy-in along: due, paid, refunded. Only allowed moves; the same change twice is refused. */
    public void setBuyIn(long guildId, long id, long userId, BuyInStatus to) {
        Event event = event(guildId, id);
        if (!settingsOf(event).buyIn().enabled()) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_has_no_buy_in"));
        var player = repository.participant(id, userId).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the")));
        if (!PrizeRules.canMoveBuyIn(player.buyIn(), to)) throw new EventProblem(409, Text.of(guildId, "events.problem.a_buy_in_can_t_go", player.buyIn().name().toLowerCase(Locale.ROOT), to.name().toLowerCase(Locale.ROOT)));
        if (!repository.moveBuyIn(id, userId, player.buyIn(), to)) throw new EventProblem(409, Text.of(guildId, "events.problem.someone_else_already_changed_that_buy"));
    }

    /** The pot so far: paid buy-ins less refunds. Zero when the event has no buy-in. */
    public long pot(long guildId, long id) {
        Event event = event(guildId, id);
        return PrizeRules.pot(settingsOf(event).buyIn(), repository.participants(id).stream().map(EventRepository.Participant::buyIn).toList());
    }

    // ---------- prizes and awards ----------

    public List<PrizePlan.Prize> prizes(long id) {
        return repository.prizes(id);
    }

    /** Replaces the event's prizes. Allowed until awards have been worked out, so an award is never left pointing at a prize that changed. */
    public void setPrizes(long guildId, long id, List<PrizePlan.Prize> prizes) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED || !repository.awards(id).isEmpty()) {
            throw new EventProblem(409, Text.of(guildId, "events.problem.prizes_can_t_be_changed_once"));
        }
        List<String> problems = new ArrayList<>();
        for (PrizePlan.Prize p : prizes) problems.addAll(PrizePlan.problems(p));
        problems.addAll(PrizePlan.setProblems(prizes));
        boolean usesPot = prizes.stream().anyMatch(p -> p.percentOfPot() > 0);
        if (usesPot && !settingsOf(event).buyIn().enabled()) problems.add("A prize can only be a share of the pot when the event has a buy-in.");
        if (!problems.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.the_prizes_can_t_be_saved"), problems.stream().distinct().toList());
        repository.replacePrizes(id, prizes);
    }

    /**
     * Works out who is owed what from the final places, the prizes and the pot, and records it. Only once the event is in scoring or done, and
     * safe to repeat: an award that already exists is left alone. The places are the standings (most points first) unless the event is won another
     * way ({@link WinModes}): first to a line or the whole board gives them in the order those were finished. A prize draw records its own awards
     * when it is drawn ({@link #recordAwards}).
     */
    public List<AwardRow> generateAwards(long guildId, long id) {
        Event event = event(guildId, id);
        if (event.status() != Status.SCORING && event.status() != Status.DONE) throw new EventProblem(409, Text.of(guildId, "events.problem.awards_are_worked_out_once_the"));
        WinModes.Settings win = WinModes.of(DataObject.fromJson(event.config()));
        if (win.draws()) throw new EventProblem(409, "This event is won by a prize draw: its awards are recorded when the winners are drawn (How it's won tab).");
        List<String> subjects = switch (win.mode()) {
            case FIRST_LINE, FIRST_BLACKOUT -> WinModes.finishOrder(win.mode(), repository.objectives(id), bonusesOf(event), progressLog(event));
            default -> standings(guildId, id).stream().map(Standing::subject).toList();
        };
        return recordAwards(event, subjects);
    }

    /**
     * Records the awards for these places ({@code subjects}, place 1 first: "u:id" or "t:id"; a null place is skipped but keeps its number) at any stage,
     * for a prize draw. Safe to repeat. Awards start pending: nothing is paid until staff mark them paid.
     */
    public List<AwardRow> recordAwards(long guildId, long id, List<String> subjects) {
        return recordAwards(event(guildId, id), subjects);
    }

    private List<AwardRow> recordAwards(Event event, List<String> subjects) {
        long id = event.id();
        List<PrizePlan.Prize> prizes = repository.prizes(id);
        if (prizes.isEmpty()) throw new EventProblem(409, Text.of(event.guildId(), "events.problem.this_event_has_no_prizes_yet"));
        var participants = repository.participants(id);
        var teams = repository.teams(id);
        List<Place> places = new ArrayList<>();
        for (String subject : subjects) places.add(placeOf(subject, teams, participants));
        long pot = PrizeRules.pot(settingsOf(event).buyIn(), participants.stream().map(EventRepository.Participant::buyIn).toList());
        for (PrizePlan.Award a : PrizePlan.plan(prizes, places, pot)) repository.addAward(id, a);
        return repository.awards(id);
    }

    /** A place in the results: a player, or a team with its members (captain first, then who joined first, never anyone on the waitlist). */
    private static Place placeOf(String subject, List<EventRepository.Team> teams, List<EventRepository.Participant> participants) {
        if (subject == null) return new Place("", List.of());
        long subjectId = Long.parseLong(subject.substring(2));
        if (!subject.startsWith("t:")) return new Place(subject, List.of(subjectId));
        var team = teams.stream().filter(t -> t.id() == subjectId).findFirst();
        List<Long> members = new ArrayList<>();
        team.map(EventRepository.Team::captainId).ifPresent(c -> {
            if (c != null && participants.stream().anyMatch(p -> p.userId() == c && !p.waiting())) members.add(c);
        });
        participants.stream().filter(p -> p.teamId() != null && p.teamId() == subjectId && !p.waiting() && !members.contains(p.userId())).map(EventRepository.Participant::userId).forEach(members::add);
        return new Place(subject, members);
    }

    public List<AwardRow> awards(long guildId, long id) {
        event(guildId, id);
        return repository.awards(id);
    }

    /**
     * Moves an award along (pending, paid, revoked, refunded) and records who did it. A clan points award pays out through the points ledger when it is
     * marked paid and takes the points back when it is revoked or refunded after being paid, under idempotency keys so a repeat can't pay twice.
     */
    public void moveAward(long guildId, long id, long awardId, AwardStatus to, long actorId, String note) {
        Event event = event(guildId, id);
        AwardRow award = repository.award(id, awardId).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_award_doesn_t_exist")));
        if (!PrizeRules.canMoveAward(award.status(), to)) throw new EventProblem(409, Text.of(guildId, "events.problem.an_award_can_t_go_from", award.status().name().toLowerCase(Locale.ROOT), to.name().toLowerCase(Locale.ROOT)));
        String cleanNote = clean(note);
        if (cleanNote.length() > EventRules.MAX_NOTE) throw new EventProblem(400, Text.of(guildId, "events.problem.keep_the_note_to_characters_or", EventRules.MAX_NOTE));
        if (award.kind() == PrizeKind.CLAN_POINTS && award.amount() > 0) {
            String reason = "Event prize: " + event.name();
            PointsService.Result result;
            if (to == AwardStatus.PAID) {
                result = points.apply(guildId, award.userId(), award.amount(), reason, PointsRules.Category.EVENT, "event-award:" + awardId, actorId);
            } else if (award.status() == AwardStatus.PAID) {
                result = points.apply(guildId, award.userId(), -award.amount(), "Event prize taken back: " + event.name(), PointsRules.Category.ADJUSTMENT, "event-award-reversal:" + awardId, actorId);
            } else {
                result = null;
            }
            if (result != null && result.outcome() == PointsService.Outcome.INSUFFICIENT) throw new EventProblem(409, Text.of(guildId, "events.problem.they_have_already_spent_those_points"));
            if (result != null && result.outcome() == PointsService.Outcome.INVALID) throw new EventProblem(400, result.message());
        }
        if (!repository.moveAward(id, awardId, award.status(), to, actorId, cleanNote)) throw new EventProblem(409, Text.of(guildId, "events.problem.someone_else_already_changed_that_award"));
    }

    public List<EventRepository.AwardStep> awardHistory(long guildId, long id, long awardId) {
        event(guildId, id);
        repository.award(id, awardId).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_award_doesn_t_exist")));
        return repository.awardHistory(awardId);
    }

    // ---------- progress ----------

    /** The progress key prefix of an XP tile counted from the hub's XP ledger (T-543). */
    static final String XP_TILE_KEY_PREFIX = "xpt:";

    /**
     * XP tile progress from the XP tracker's final pass while the event is being scored (T-706): XP gained up to the planned end (or the move to
     * scoring, if sooner) still counts, so the last minute or two before an organiser finishes isn't lost. Nothing else is taken once the event stops running.
     */
    static boolean finalXpTile(Event event, Source source, String sourceKey) {
        return event.status() == Status.SCORING && source == Source.AUTO && sourceKey != null && sourceKey.startsWith(XP_TILE_KEY_PREFIX);
    }

    /**
     * Records progress for a player. Checks the event is running, the player is in it, the objective accepts progress from this source, its
     * prerequisites are done, and it can still count; trims the amount so an objective never goes past its last completion. An organiser
     * ({@link Source#MANUAL}) may also enter a negative amount to undo earlier progress. {@code sourceKey} makes a retry harmless.
     */
    public Recorded recordProgress(long guildId, long id, String objectiveKey, long userId, long amount, Source source, String sourceKey, Long actorId, String note) {
        Event event = event(guildId, id);
        if (!EventRules.acceptsProgress(event.status()) && !finalXpTile(event, source, sourceKey)) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_isn_t_running_so"));
        if (amount == 0) throw new EventProblem(400, Text.of(guildId, "events.problem.the_amount_can_t_be_zero"));
        if (amount < 0 && source != Source.MANUAL) throw new EventProblem(400, Text.of(guildId, "events.problem.only_an_organiser_can_take_progress"));
        String cleanNote = clean(note);
        if (cleanNote.length() > EventRules.MAX_NOTE) throw new EventProblem(400, Text.of(guildId, "events.problem.keep_the_note_to_characters_or", EventRules.MAX_NOTE));

        List<ObjectiveSpec> objectives = repository.objectives(id);
        ObjectiveSpec objective = objectives.stream().filter(o -> o.key().equals(objectiveKey)).findFirst().orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_objective_doesn_t_exist")));
        if (!EventRules.evidenceAllows(objective.evidence(), source)) throw new EventProblem(409, Text.of(guildId, "events.problem.doesn_t_accept_progress_that_way", objective.label()));
        Participant player = repository.participant(id, userId).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the")));
        if (player.waiting()) throw new EventProblem(409, Text.of(guildId, "events.problem.that_player_is_on_the_waitlist"));
        if (source != Source.MANUAL && !mayPlay(event, player)) throw new EventProblem(409, Text.of(guildId, "events.problem.that_player_hasn_t_been_verified"));

        boolean byTeam = event.scoringMode() == ScoringMode.TEAM;
        if (byTeam && player.teamId() == null) throw new EventProblem(409, Text.of(guildId, "events.problem.put_that_player_in_a_team"));
        long subject = byTeam ? player.teamId() : userId;

        long total = repository.total(id, objectiveKey, subject, byTeam);
        long capacity = objective.target() * objective.repeatLimit();
        long effective = amount;
        if (amount > 0) {
            Map<String, ObjectiveSpec> byKey = new HashMap<>();
            for (ObjectiveSpec o : objectives) byKey.put(o.key(), o);
            Map<String, Long> totals = new HashMap<>();
            for (String prerequisite : objective.prerequisites()) totals.put(prerequisite, repository.total(id, prerequisite, subject, byTeam));
            if (!EventScoring.available(objective, totals, byKey)) throw new EventProblem(409, Text.of(guildId, "events.problem.isn_t_unlocked_yet_finish_what", objective.label()));
            long remaining = capacity - total;
            if (remaining <= 0) throw new EventProblem(409, Text.of(guildId, byTeam ? "events.problem.already_complete_for_team" : "events.problem.already_complete_for_player", objective.label()));
            effective = Math.min(amount, remaining);
        } else {
            effective = -Math.min(-amount, total); // never reverse more than there is
            if (effective == 0) throw new EventProblem(409, Text.of(guildId, "events.problem.there_is_no_progress_to_take"));
        }

        OptionalLong row = repository.addProgress(id, objectiveKey, userId, player.teamId(), effective, source, sourceKey, actorId, cleanNote);
        if (row.isEmpty()) throw new EventProblem(409, Text.of(guildId, "events.problem.that_has_already_been_counted"));
        long after = Math.max(0, total + effective);
        changes.publish(EventChanges.Kind.PROGRESS, guildId, id);
        return new Recorded(row.getAsLong(), after, EventScoring.completions(objective, after));
    }

    // ---------- claims ----------

    /** A player's proof for an objective, waiting for an organiser. */
    public long submitClaim(long guildId, long id, String objectiveKey, long userId, long amount, String note, String proofUrl) {
        Event event = event(guildId, id);
        if (!EventRules.acceptsProgress(event.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_isn_t_running_so_2"));
        ObjectiveSpec objective = repository.objectives(id).stream().filter(o -> o.key().equals(objectiveKey)).findFirst()
                .orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_objective_doesn_t_exist")));
        if (!EventRules.evidenceAllows(objective.evidence(), Source.CLAIM)) throw new EventProblem(409, Text.of(guildId, "events.problem.can_t_be_claimed_with_proof", objective.label()));
        Participant claimant = repository.participant(id, userId).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the")));
        if (!mayPlay(event, claimant)) throw new EventProblem(409, Text.of(guildId, "events.problem.you_haven_t_been_verified_for", EventRoster.askText(settingsOf(event).verification())));
        if (amount < 1 || amount > objective.target()) throw new EventProblem(400, Text.of(guildId, "events.problem.claim_between_1_and", objective.target()));
        String cleanNote = clean(note);
        if (cleanNote.length() > EventRules.MAX_NOTE) throw new EventProblem(400, Text.of(guildId, "events.problem.keep_the_note_to_characters_or", EventRules.MAX_NOTE));
        if (cleanNote.length() < 3 && (proofUrl == null || proofUrl.isBlank())) throw new EventProblem(400, Text.of(guildId, "events.problem.say_what_you_did_or_attach"));
        String linkProblem = EventRules.proofUrlProblem(proofUrl);
        if (linkProblem != null) throw new EventProblem(400, linkProblem);
        if (repository.pendingClaimsFor(id, userId) >= MAX_PENDING_CLAIMS_PER_PLAYER) {
            throw new EventProblem(409, Text.of(guildId, "events.problem.you_already_have_claims_waiting_wait", MAX_PENDING_CLAIMS_PER_PLAYER));
        }
        return repository.addClaim(id, objectiveKey, userId, amount, cleanNote, proofUrl == null || proofUrl.isBlank() ? null : proofUrl.strip());
    }

    /**
     * A player's proof for the event's verification (no objective, no points): approving it verifies them, denying it turns them down with the
     * reason, and they may send new proof. Only for an event that asks for verification, from a player who isn't already verified or waived.
     */
    public long submitVerification(long guildId, long id, long userId, String note, String proofUrl) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over"));
        if (!settingsOf(event).verification().required()) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_doesn_t_ask_for_2"));
        Participant player = repository.participant(id, userId).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_player_isn_t_in_the")));
        if (player.verification() == EventRoster.Verification.VERIFIED || player.verification() == EventRoster.Verification.WAIVED) {
            throw new EventProblem(409, Text.of(guildId, "events.problem.you_re_already_verified_for_this"));
        }
        String cleanNote = clean(note);
        if (cleanNote.length() > EventRules.MAX_NOTE) throw new EventProblem(400, Text.of(guildId, "events.problem.keep_the_note_to_characters_or", EventRules.MAX_NOTE));
        if (proofUrl == null || proofUrl.isBlank()) throw new EventProblem(400, Text.of(guildId, "events.problem.attach_the_proof_the_organisers_asked"));
        String linkProblem = EventRules.proofUrlProblem(proofUrl);
        if (linkProblem != null) throw new EventProblem(400, linkProblem);
        if (repository.pendingVerificationClaimsFor(id, userId) > 0) throw new EventProblem(409, Text.of(guildId, "events.problem.your_verification_is_already_waiting_for"));
        return repository.addClaim(id, EventRepository.ClaimKind.VERIFICATION, VERIFICATION_KEY, userId, 1, cleanNote, proofUrl.strip());
    }

    /**
     * Approves or denies a pending claim. Approving counts its amount as progress under the key {@code claim:<id>}, so even a repeat after a
     * crash counts it once. The claim is checked against the event first, so an approval that can't be counted is refused before anything changes.
     * A verification claim verifies the player instead (or turns them down with the reason).
     */
    public Claim decideClaim(long guildId, long id, long claimId, boolean approve, String note, long actorId) {
        Event event = event(guildId, id);
        Claim claim = repository.claim(id, claimId).orElseThrow(() -> new EventProblem(404, Text.of(guildId, "events.problem.that_claim_doesn_t_exist")));
        if (claim.status() != ClaimStatus.PENDING) throw new EventProblem(409, Text.of(guildId, "events.problem.someone_has_already_decided_that_claim"));
        String cleanNote = clean(note);
        if (cleanNote.length() > EventRules.MAX_NOTE) throw new EventProblem(400, Text.of(guildId, "events.problem.keep_the_note_to_characters_or", EventRules.MAX_NOTE));
        if (!approve && cleanNote.isEmpty()) throw new EventProblem(400, Text.of(guildId, "events.problem.say_why_it_was_denied_so"));
        boolean verification = claim.kind() == EventRepository.ClaimKind.VERIFICATION;
        if (verification && (event.status() == Status.DONE || event.status() == Status.CANCELLED)) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_over"));
        if (ClanVsClan.is(event.template()) && ClanVsClan.settings(DataObject.fromJson(event.config())).reviewByOtherClan()) {
            // clans review each other's proof: nobody decides proof from their own clan's team
            Long claimTeam = repository.participant(id, claim.userId()).map(Participant::teamId).orElse(null);
            Long actorTeam = repository.participant(id, actorId).map(Participant::teamId).orElse(null);
            if (claimTeam != null && claimTeam.equals(actorTeam)) throw new EventProblem(403, Text.of(guildId, "events.problem.in_this_event_the_clans_review"));
        }
        if (!verification && approve && !EventRules.acceptsProgress(event.status())) throw new EventProblem(409, Text.of(guildId, "events.problem.the_event_isn_t_running_so"));

        if (!repository.decideClaim(id, claimId, approve ? ClaimStatus.APPROVED : ClaimStatus.DENIED, actorId, cleanNote.isEmpty() ? null : cleanNote)) {
            throw new EventProblem(409, Text.of(guildId, "events.problem.someone_has_already_decided_that_claim"));
        }
        if (verification) {
            try {
                setVerification(guildId, id, claim.userId(), approve ? EventRoster.Verification.VERIFIED : EventRoster.Verification.REJECTED, actorId,
                        approve ? "Approved proof #" + claimId : cleanNote);
            } catch (EventProblem e) {
                // verification was switched off or the player left meanwhile: the proof stays decided, with nothing to change
                log.warn("Decided verification claim {} of event {} couldn't be applied: {}", claimId, id, e.getMessage());
            }
        } else if (approve) {
            try {
                Recorded recorded = recordProgress(guildId, id, claim.objectiveKey(), claim.userId(), claim.amount(), Source.CLAIM, "claim:" + claimId, actorId, "Approved claim #" + claimId);
                repository.linkClaimProgress(claimId, recorded.progressId());
            } catch (EventProblem e) {
                // the objective filled up or the player left between the check and now: the claim stays approved, with nothing to count
                log.warn("Approved claim {} of event {} couldn't be counted: {}", claimId, id, e.getMessage());
            }
        }
        changes.publish(new EventChanges.Change(EventChanges.Kind.CLAIM_DECIDED, guildId, id, claimId, claim.userId(), approve, cleanNote));
        return repository.claim(id, claimId).orElse(claim);
    }

    // ---------- standings ----------

    /** Standings from the progress log. Subjects are "u:&lt;userId&gt;" for players or "t:&lt;teamId&gt;" when the event scores by team. */
    public List<Standing> standings(long guildId, long id) {
        Event event = event(guildId, id);
        List<Entry> log = progressLog(event);
        List<ObjectiveSpec> objectives = repository.objectives(id);
        List<Standing> usual = EventScoring.standings(objectives, log, bonusesOf(event));
        EventTemplate template = templates.byKey(event.template()).orElse(null);
        List<Standing> own = template == null ? null : template.standings(event, objectives, repository.progress(id), usual);
        return own == null ? usual : own;
    }

    /** The event's progress log as the standings read it: one entry per row, for the player, or their team when the event scores by team. */
    public List<Entry> progressLog(Event event) {
        boolean byTeam = event.scoringMode() == ScoringMode.TEAM;
        List<Entry> log = new ArrayList<>();
        for (EventRepository.Progress p : repository.progress(event.id())) {
            if (byTeam && p.teamId() == null) continue;
            log.add(new Entry(byTeam ? "t:" + p.teamId() : "u:" + p.userId(), p.objectiveKey(), p.amount(), p.createdAt()));
        }
        return log;
    }

    private static String clean(String text) {
        return text == null ? "" : text.strip();
    }

    private static String label(Status status) {
        return switch (status) {
            case DRAFT -> "a draft";
            case SIGNUPS -> "taking signups";
            case RUNNING -> "running";
            case SCORING -> "scoring";
            case DONE -> "done";
            case CANCELLED -> "cancelled";
        };
    }
}
