package com.runeclans.bot.leagues;

import com.runeclans.bot.events.ClanVsClan;
import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Reports a league round's event to the hub (T-759, RuneClan docs/ALLIANCE-LEAGUE.md 10): when a clan-vs-clan event attached to a round
 * reaches DONE, every participating clan's score (or player count, for a {@code participation} round) goes to the hub through its write
 * queue and lands on the round's table as proposed by the host clan; a cancelled event is detached, so the round waits for a result by
 * hand. Everything runs on its own lane, never on the event engine's thread. Off (the hub client off): nothing here runs. The report
 * goes through the hub write queue, so it is lost only when that queue is full or the saved write expires before the hub answers, or
 * when the process restarts between the event's stage change and the report; the host clan then enters the result by hand.
 */
@BService
public class LeagueReporter {
    private static final Logger log = LoggerFactory.getLogger(LeagueReporter.class);

    /** How a report ended, for the log and the dashboard's "Report now". */
    public record Outcome(boolean ok, int status, String message) {
        static Outcome fail(int status, String message) {
            return new Outcome(false, status, message);
        }
    }

    private final HubEvents events;
    private final LeagueHub hub;
    private final ScheduledExecutorService lane = BotScheduler.lane("leagues");

    public LeagueReporter(HubEvents events, LeagueHub hub, EventChanges changes) {
        this.events = events;
        this.hub = hub;
        changes.subscribe(change -> {
            if (change.kind() == EventChanges.Kind.STAGE && hub.enabled()) lane.execute(() -> onStage(change.guildId(), change.eventId()));
        });
    }

    void onStage(long guildId, long eventId) {
        try {
            Event event = events.eventById(eventId).filter(e -> e.guildId() == guildId).orElse(null);
            if (event == null || !ClanVsClan.is(event.template())) return;
            if (event.status() == Status.DONE) {
                Outcome outcome = report(event);
                if (outcome.ok()) log.info("League: reported event {} of server {} to the hub", eventId, guildId);
                else if (outcome.status() != 404) log.warn("League: event {} of server {} was not reported ({}): {}", eventId, guildId, outcome.status(), outcome.message());
            } else if (event.status() == Status.CANCELLED) {
                detach(event);
            }
        } catch (RuntimeException e) {
            log.warn("League: could not handle the stage of event {} of server {}", eventId, guildId, e);
        }
    }

    /** The round this event is attached to, if any (404 when the server has no clan on the hub, or the event is attached nowhere). */
    public Optional<LeagueRounds.Attached> roundOf(Event event) {
        var answer = hub.guildLeagues(event.guildId());
        if (answer == null || !answer.ok() || answer.json() == null) return Optional.empty();
        return LeagueRounds.attached(answer.json(), event.id());
    }

    /**
     * Sends the event's clan results to the hub for the round it is attached to. Not ok when the event is attached to no round, the round is
     * over, a participating clan has no clan on the hub, or the hub refuses it (its message is passed on).
     */
    public Outcome report(Event event) {
        if (!hub.enabled()) return Outcome.fail(503, "The RuneClans hub is off on this bot.");
        if (!ClanVsClan.is(event.template())) return Outcome.fail(400, "Only a clan-vs-clan event can be a league round's event.");
        var answer = hub.guildLeagues(event.guildId());
        if (answer == null) return Outcome.fail(503, "Can't reach RuneClans right now. Try again in a minute.");
        if (!answer.ok() || answer.json() == null) return Outcome.fail(404, "This server's clan has no leagues on RuneClans.");
        Optional<LeagueRounds.Attached> attached = LeagueRounds.attached(answer.json(), event.id());
        if (attached.isEmpty()) return Outcome.fail(404, "This event isn't attached to a league round.");
        LeagueRounds.Attached round = attached.get();
        if (round.over()) return Outcome.fail(409, "Round " + round.number() + " of " + round.leagueName() + " is " + round.status() + "; it takes no result now.");
        if (!round.isHost()) return Outcome.fail(403, "Only the host clan's server reports a round's event.");
        List<Standing> standings;
        try {
            standings = events.standings(event.guildId(), event.id());
        } catch (RuntimeException e) {
            return Outcome.fail(500, "The event's standings couldn't be read.");
        }
        DataObject config = DataObject.fromJson(event.config() == null || event.config().isBlank() ? "{}" : event.config());
        Map<String, Long> byClan = ClanVsClan.hubTeams(config);
        Optional<DataArray> clans;
        if (!byClan.isEmpty()) {
            // a hub event names its clans by hub clan id already: no server lookup, and a clan whose server the bot isn't in still counts
            List<String> clanIds = new java.util.ArrayList<>(byClan.keySet());
            Map<Long, Long> teams = new java.util.LinkedHashMap<>();
            for (int i = 0; i < clanIds.size(); i++) teams.put((long) i, byClan.get(clanIds.get(i)));
            List<LeagueRounds.ClanResult> results = LeagueRounds.results(round.metric(), standings, teams, events.participants(event.id()));
            clans = LeagueRounds.clansJson(results, i -> Optional.of(clanIds.get(i.intValue())));
        } else {
            Map<Long, Long> teams = ClanVsClan.teams(config);
            if (teams == null || teams.isEmpty()) return Outcome.fail(400, "The event has no participating clans yet.");
            List<LeagueRounds.ClanResult> results = LeagueRounds.results(round.metric(), standings, teams, events.participants(event.id()));
            clans = LeagueRounds.clansJson(results, hub::clanIdOf);
        }
        if (clans.isEmpty()) return Outcome.fail(409, "A participating clan has no clan on RuneClans, so this round's result is entered by hand on the website.");
        HubWriteQueue.Result result = hub.report(round.leagueId(), round.number(), event.guildId(), clans.get(), "Reported by RuneClan Bot from " + event.name());
        return outcomeOf(result, "The hub didn't take the result.");
    }

    /** Detaches a cancelled event from its round, so the round waits for a result by hand. */
    public Outcome detach(Event event) {
        Optional<LeagueRounds.Attached> attached = roundOf(event);
        if (attached.isEmpty()) return Outcome.fail(404, "This event isn't attached to a league round.");
        LeagueRounds.Attached round = attached.get();
        if (round.over()) return Outcome.fail(409, "Round " + round.number() + " is " + round.status() + ".");
        Outcome outcome = outcomeOf(hub.attach(round.leagueId(), round.number(), event.guildId(), null, null, null), "The hub didn't detach the event.");
        if (outcome.ok()) log.info("League: detached the cancelled event {} from round {} of {}", event.id(), round.number(), round.leagueId());
        return outcome;
    }

    /** A queued write's outcome: sent, saved for later (202), or refused with the hub's message. */
    public static Outcome outcomeOf(HubWriteQueue.Result result, String fallback) {
        return switch (result.outcome()) {
            case SENT -> new Outcome(true, 200, "Sent to RuneClans.");
            case QUEUED -> new Outcome(true, 202, "Saved; it reaches RuneClans shortly.");
            case REFUSED -> Outcome.fail(result.answer() == null ? 503 : result.answer().status(), LeagueHub.refusal(result, fallback));
        };
    }
}
