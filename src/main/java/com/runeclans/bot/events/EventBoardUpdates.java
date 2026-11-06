package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.util.BotScheduler;
import com.runeclans.bot.discord.Shards;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The posts a bingo's update rules ask for besides the board (T-650): a recap of the day at the daily reset, and milestone announcements (first line,
 * first blackout, first time anyone finishes a tile). Every one is off unless an organiser turns it on ({@link BoardUpdates}). Posts go through the
 * Discord outbox ({@link EventUpdatePostJob}) with no mentions, each is noted first so it is posted once ({@link EventUpdateRepository}), and a bot that
 * is not live posts none ({@link AutoPostRouter}). The board's own timing lives in {@link EventBoardPanels}. The event, its tiles, standings and
 * progress log are the hub's (T-934).
 */
@BService
public class EventBoardUpdates {
    private static final Logger log = LoggerFactory.getLogger(EventBoardUpdates.class);
    private static final long DELAY_SECONDS = 5;

    private final HubEvents events;
    private final EventSpaceRepository space;
    private final EventUpdateRepository posts;
    private final DiscordOutbox outbox;
    private final ScheduledExecutorService executor = BotScheduler.lane("event-board-updates");
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private volatile JDA jda;
    /** The reset whose recaps this run of the bot has already gone through. */
    private volatile Instant recapsDoneFor;

    public EventBoardUpdates(HubEvents events, EventSpaceRepository space, EventUpdateRepository posts, DiscordOutbox outbox, EventChanges changes) {
        this.events = events;
        this.space = space;
        this.posts = posts;
        this.outbox = outbox;
        changes.subscribe(this::onChange);
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> {
            try {
                recaps(Instant.now());
            } catch (RuntimeException e) {
                log.warn("Could not post the daily bingo recaps", e);
            }
        }, 30, 60, TimeUnit.SECONDS);
    }

    private void onChange(EventChanges.Change change) {
        boolean progress = change.kind() == EventChanges.Kind.PROGRESS || (change.kind() == EventChanges.Kind.CLAIM_DECIDED && change.approved());
        if (!progress || jda == null) return;
        long eventId = change.eventId();
        ScheduledFuture<?> old = pending.put(eventId, executor.schedule(() -> {
            pending.remove(eventId);
            try {
                milestones(change.guildId(), eventId, Instant.now());
            } catch (RuntimeException e) {
                log.warn("Could not check the milestones of event {}", eventId, e);
            }
        }, DELAY_SECONDS, TimeUnit.SECONDS));
        if (old != null) old.cancel(false);
    }

    /** Announces the milestones the event has switched on and newly reached. Returns how many were handed to the outbox. */
    int milestones(long guildId, long eventId, Instant now) {
        Event event;
        try {
            event = events.event(guildId, eventId);
        } catch (EventProblem e) {
            return 0;
        }
        if (event.status() != Status.RUNNING || !ClanVsClan.plays(event, BingoTemplate.KEY)) return 0;
        DataObject config = DataObject.fromJson(event.config());
        BoardUpdates.Settings rules = BoardUpdates.of(config);
        if (!rules.milestones()) return 0;
        // a milestone says who is ahead, which an event hiding its standings keeps quiet: nothing is claimed, so nothing stale is posted later either
        if (!EventStandings.tableShown(EventStandings.of(config), event.status())) return 0;
        if (AutoPostRouter.diverting()) {
            log.info("Event {}: milestone posts skipped, this bot is not live", eventId);
            return 0;
        }
        Guild guild = guild(guildId);
        if (guild == null) return 0;
        Long channel = channelFor(event, rules);
        if (channel == null) {
            log.info("Event {}: a milestone was reached but there is nowhere to post it (no announcements or board channel)", eventId);
            return 0;
        }
        List<ObjectiveSpec> objectives = events.objectives(eventId);
        List<BoardUpdates.Milestone> fresh = BoardUpdates.toAnnounce(rules, BoardUpdates.milestones(objectives, events.bonusesOf(event), entries(event)), now);
        Function<String, String> nameOf = names(guild, eventId);
        int sent = 0;
        for (BoardUpdates.Milestone m : fresh) {
            String text = BoardUpdates.milestoneText(event.name(), m, nameOf.apply(m.subject()));
            if (!posts.claim(eventId, m.key(), m.kind().name(), text)) continue;
            outbox.enqueue(EventUpdatePostJob.KIND, guildId, 0L, "event-update:" + eventId + ":" + m.key(), EventUpdatePostJob.payload(eventId, channel, m.key(), text));
            sent++;
        }
        if (sent > 0) log.info("Event {}: {} milestone post(s) queued", eventId, sent);
        return sent;
    }

    /**
     * After each daily reset, once per run of the bot: posts the recap of the day that ended for every running bingo that asks for one. A bot that was down
     * at reset catches up within {@link BoardUpdates#RECAP_WINDOW}; the post key makes sure a day is never recapped twice.
     */
    int recaps(Instant now) {
        Instant reset = BoardUpdates.latestReset(now);
        if (reset.equals(recapsDoneFor) || now.isAfter(reset.plus(BoardUpdates.RECAP_WINDOW))) return 0;
        if (jda == null) return 0;
        if (AutoPostRouter.diverting()) {
            recapsDoneFor = reset;
            log.info("Daily bingo recaps skipped: this bot is not live");
            return 0;
        }
        int sent = 0;
        for (Event event : events.eventsInStatus(Status.RUNNING)) {
            try {
                if (recap(event, reset)) sent++;
            } catch (RuntimeException e) {
                log.warn("Could not post the daily recap of event {}", event.id(), e);
            }
        }
        recapsDoneFor = reset;
        if (sent > 0) log.info("Daily bingo recaps: {} queued", sent);
        return sent;
    }

    /** One event's recap of the day that ended at {@code reset}. True when it was handed to the outbox. */
    boolean recap(Event event, Instant reset) {
        if (!ClanVsClan.plays(event, BingoTemplate.KEY)) return false;
        DataObject config = DataObject.fromJson(event.config());
        BoardUpdates.Settings rules = BoardUpdates.of(config);
        if (!rules.recap()) return false;
        Guild guild = guild(event.guildId());
        if (guild == null) return false;
        Long channel = channelFor(event, rules);
        if (channel == null) {
            log.info("Event {}: no recap posted, there is no announcements or board channel", event.id());
            return false;
        }
        List<ObjectiveSpec> objectives = events.objectives(event.id());
        List<BoardUpdates.Finished> finished = BoardUpdates.finishedBetween(objectives, entries(event), reset.minus(java.time.Duration.ofDays(1)), reset);
        List<Standing> standings = events.standings(event.guildId(), event.id());
        EventStandings.Visibility visibility = EventStandings.of(config);
        String text = BoardUpdates.recapText(event.name(), reset, finished, standings, names(guild, event.id()), EventStandings.tableShown(visibility, event.status()),
                EventStandings.hiddenText(visibility));
        if (text == null) return false; // a quiet day posts nothing
        String key = BoardUpdates.recapKey(reset);
        if (!posts.claim(event.id(), key, "RECAP", text)) return false;
        outbox.enqueue(EventUpdatePostJob.KIND, event.guildId(), 0L, "event-update:" + event.id() + ":" + key, EventUpdatePostJob.payload(event.id(), channel, key, text));
        return true;
    }

    /** Where the posts go: the channel the organiser chose, else the event's announcements channel, else its live board channel. */
    public Long channelFor(Event event, BoardUpdates.Settings rules) {
        if (rules.channelId() != null) return rules.channelId();
        for (EventSpaceRepository.ChannelRow row : space.channels(event.id())) {
            if (row.key().equals(EventSpace.Kind.ANNOUNCEMENTS.key())) return row.channelId();
        }
        return EventSpaces.boardChannelOf(event, space);
    }

    private List<EventScoring.Entry> entries(Event event) {
        boolean byTeam = event.scoringMode() == EventRules.ScoringMode.TEAM;
        List<EventScoring.Entry> entries = new ArrayList<>();
        for (EventRepository.Progress p : events.progress(event.id())) {
            if (byTeam && p.teamId() == null) continue;
            entries.add(new EventScoring.Entry(byTeam ? "t:" + p.teamId() : "u:" + p.userId(), p.objectiveKey(), p.amount(), p.createdAt()));
        }
        return entries;
    }

    private Function<String, String> names(Guild guild, long eventId) {
        var teams = events.teams(eventId);
        return subject -> EventBoardPanels.nameOf(guild, subject, teams);
    }

    private Guild guild(long guildId) {
        JDA client = jda;
        return client == null ? null : Shards.guild(client, guildId);
    }
}
