package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.commands.signup.SignupEntry;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.hubevents.HubEventMap;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The Discord signup attached to a hub event (T-934: the event itself lives on RuneClans, {@link HubEvents}). When an event opens signups (and has a
 * signup channel and a staff channel chosen) the bot posts a normal queue signup for it. Players who sign up there become the event's players (added
 * the way staff add players, so the team policy and the waitlist still apply). The signup closes by itself at the event's signup deadline and when the
 * event starts, and is removed when the event is done or cancelled. Staff can still add late entries by hand. A sweep every half minute keeps it in step.
 * <p>
 * Closing signups ({@link #closeSignups}) is the organiser's one action for it: the hub stops taking players' own joins, the Discord signup is paused,
 * and the roster is synced one last time, in that order, so nobody can slip in between.
 * <p>
 * The roster and the Discord signup are kept the same both ways until the event starts: a player who signs up in Discord is added to the roster, a player
 * who is taken off the signup in Discord is taken off the roster (and off their team), a player who joins on the website gets an entry in the signup, and a
 * player removed on the website loses their entry. Only players the signup follows ({@code hub_event_signup_entry}: those who came from it, or joined on their
 * own and were found on it) are mirrored: staff-added players have no entry to follow. Someone barred from the event is kept out of the roster and their
 * entry is taken off the signup. Website joins and leaves reach here through the hub feed ({@code hubevents.HubEventFeed}).
 */
@BService
public class EventSignups {
    private static final Logger log = LoggerFactory.getLogger(EventSignups.class);

    /** Where an event's signup stands, for the dashboard. */
    public record State(boolean attached, boolean active, int entries, boolean channelsChosen) {}

    /** What closing signups did: how many the last sync added, where the roster stands, and who the event's rules turned away (and why). */
    public record Closed(int added, int active, int waiting, List<EventRepository.Refusal> refused) {}

    private final HubEvents events;
    private final HubEventMap map;
    private final SignupService signups;
    private final EventChanges changes;
    /** How often the signup and the roster are compared. */
    static final int SWEEP_SECONDS = 30;

    private final ScheduledExecutorService executor = BotScheduler.lane("event-signups");
    private volatile JDA jda;

    public EventSignups(HubEvents events, SignupService signups, EventChanges changes) {
        this.events = events;
        this.map = events.map();
        this.signups = signups;
        this.changes = changes;
        changes.subscribe(change -> {
            if (change.kind() == EventChanges.Kind.PLAYER_JOINED || change.kind() == EventChanges.Kind.PLAYER_LEFT) executor.execute(() -> mirror(change));
            if (change.kind() == EventChanges.Kind.STAGE) executor.execute(() -> onStageChange(change));
            if (change.kind() == EventChanges.Kind.ROSTER && change.userId() == 0) executor.execute(() -> reconcile(change.guildId(), change.eventId()));
        });
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA client = event.getJda();
        this.jda = client;
        executor.scheduleWithFixedDelay(() -> sweep(client), SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
    }

    /** For tests: the JDA the mirror and the sweep look servers up in, without the framework's ready event. */
    void useJda(JDA client) {
        this.jda = client;
    }

    /** Copies a player who joined or left on the website (or with a command) onto the Discord signup, so the two lists stay the same. */
    void mirror(EventChanges.Change change) {
        JDA client = jda;
        if (client == null) return;
        try {
            Guild guild = Shards.guild(client, change.guildId());
            HubEventMap.Row row = map.event(change.eventId()).orElse(null);
            if (guild == null || row == null || row.signupId() == null || signups.getSessionById(row.signupId()) == null) return;
            long signupId = row.signupId();
            if (change.kind() == EventChanges.Kind.PLAYER_LEFT) {
                map.removeSignupEntry(change.eventId(), change.userId());
                if (signups.removeUser(guild, signupId, change.userId())) signups.updateMessages(guild, signupId);
                return;
            }
            if (signups.getEntries(signupId).stream().anyMatch(e -> e.userId() == change.userId())) {
                map.addSignupEntry(change.eventId(), change.userId());
                return;
            }
            Member member = guild.getMemberById(change.userId());
            String name = member == null ? "Website signup" : member.getEffectiveName();
            if (signups.addManualUser(guild, signupId, change.userId(), name, null, change.userId())) {
                map.addSignupEntry(change.eventId(), change.userId());
                signups.updateMessages(guild, signupId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not mirror a player of event {} onto its signup", change.eventId(), e);
        }
    }

    /**
     * The roster changed somewhere the bot can't tell who ({@code clan_event.participant} on the feed names people by hub id): the Discord signup is brought in
     * step with the hub's roster. A player who joined on their own and isn't on the signup is added to it; a player the signup follows who is no longer on
     * the roster is taken off it. Only while the event still takes signups and the signup is there to read.
     */
    void reconcile(long guildId, long eventId) {
        JDA client = jda;
        if (client == null) return;
        try {
            Guild guild = Shards.guild(client, guildId);
            HubEventMap.Row row = map.event(eventId).orElse(null);
            if (guild == null || row == null || row.signupId() == null || signups.getSessionById(row.signupId()) == null) return;
            Event event = events.event(guildId, eventId);
            if (event.status() != Status.SIGNUPS) return;
            long signupId = row.signupId();
            Set<Long> onSignup = new HashSet<>();
            signups.getEntries(signupId).forEach(e -> onSignup.add(e.userId()));
            Set<Long> onRoster = new HashSet<>();
            boolean changed = false;
            for (Participant p : events.participants(eventId)) {
                if (p.userId() == 0) continue;
                onRoster.add(p.userId());
                if (onSignup.contains(p.userId())) {
                    map.addSignupEntry(eventId, p.userId());
                } else if (p.source() == EventRepository.RosterSource.SELF) {
                    Member member = guild.getMemberById(p.userId());
                    if (signups.addManualUser(guild, signupId, p.userId(), member == null ? "Website signup" : member.getEffectiveName(), null, p.userId())) {
                        map.addSignupEntry(eventId, p.userId());
                        changed = true;
                    }
                }
            }
            for (long userId : map.signupEntries(eventId)) {
                if (onRoster.contains(userId)) continue;
                map.removeSignupEntry(eventId, userId);
                if (onSignup.contains(userId) && signups.removeUser(guild, signupId, userId)) changed = true;
            }
            if (changed) signups.updateMessages(guild, signupId);
        } catch (RuntimeException e) {
            log.warn("Could not bring the signup of event {} in step with its roster", eventId, e);
        }
    }

    /** A stage change seen on the hub feed (or made by the bot): the signup follows the event's new stage. */
    void onStageChange(EventChanges.Change change) {
        JDA client = jda;
        if (client == null) return;
        try {
            Guild guild = Shards.guild(client, change.guildId());
            if (guild == null) return;
            if (change.note().equals("deleted")) {
                HubEventMap.Row row = map.event(change.eventId()).orElse(null);
                if (row != null && row.signupId() != null) signups.deleteSignup(guild, row.signupId());
                return;
            }
            Event event = events.eventById(change.eventId()).orElse(null);
            if (event != null) onStage(guild, event.id(), event.status());
        } catch (RuntimeException e) {
            log.warn("Could not follow the stage change of event {}", change.eventId(), e);
        }
    }

    private static EventSettings settingsOf(Event event) {
        try {
            return EventSettings.parse(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return EventSettings.defaults();
        }
    }

    public State stateOf(Event event) {
        EventSettings settings = settingsOf(event);
        boolean chosen = settings.signupChannelId() != null && settings.signupAdminChannelId() != null;
        if (event.signupId() == null) return new State(false, false, 0, chosen);
        return new State(true, signups.isSignupActive(event.signupId()), signups.getEntries(event.signupId()).size(), chosen);
    }

    /** Posts the event's signup in its chosen channels. Only while the event is taking signups, and only once. Returns the signup's id. */
    public long open(Guild guild, long eventId, long actorId) {
        Event event = events.event(guild.getIdLong(), eventId);
        if (event.status() != Status.SIGNUPS) throw new EventProblem(409, Text.of(guild, "events.problem.open_the_event_signups_first"));
        if (event.signupId() != null) throw new EventProblem(409, Text.of(guild, "events.problem.this_event_already_has_a_signup"));
        EventSettings settings = settingsOf(event);
        if (settings.signupChannelId() == null || settings.signupAdminChannelId() == null) throw new EventProblem(409, Text.of(guild, "events.problem.choose_a_signup_channel_and_a"));
        TextChannel panel = guild.getTextChannelById(settings.signupChannelId());
        TextChannel staff = guild.getTextChannelById(settings.signupAdminChannelId());
        if (panel == null || staff == null) throw new EventProblem(400, Text.of(guild, "events.problem.one_of_the_signup_channels_no"));
        for (TextChannel channel : List.of(panel, staff)) {
            if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) {
                throw new EventProblem(400, Text.of(guild, "events.problem.jonnybot_needs_to_see_and_send", channel.getName()));
            }
        }
        long signupId = signups.createQueueSignup(guild, panel, staff, event.name(), event.description().isBlank() ? null : event.description(), null, actorId);
        map.setSignup(eventId, signupId);
        log.info("Event {} opened signup {} in guild {}", eventId, signupId, guild.getIdLong());
        return signupId;
    }

    /**
     * Closes the event's signups: the hub marks them closed first (so the website and {@code /events join} refuse new self-signups), the Discord signup is
     * paused, then whoever had signed up is added one final time. {@code actor} is who closed them (the hub closes them itself at the deadline, T-945).
     */
    public Closed closeSignups(Guild guild, long eventId, Actor actor) {
        events.closeSignups(guild.getIdLong(), eventId, actor);
        Event event = events.event(guild.getIdLong(), eventId);
        int added = 0;
        if (event.signupId() != null) {
            pause(guild, event.signupId());
            added = sync(guild, eventId);
        }
        return report(eventId, added);
    }

    /** Opens signups again: the hub takes players' own joins and the Discord signup is switched back on. */
    public void reopenSignups(Guild guild, long eventId, Actor actor) {
        events.reopenSignups(guild.getIdLong(), eventId, actor);
        Event event = events.event(guild.getIdLong(), eventId);
        if (event.signupId() != null && !signups.isSignupActive(event.signupId())) {
            signups.togglePause(event.signupId());
            signups.updateMessages(guild, event.signupId());
        }
    }

    private Closed report(long eventId, int added) {
        List<Participant> players = events.participants(eventId);
        int waiting = (int) players.stream().filter(Participant::waiting).count();
        return new Closed(added, players.size() - waiting, waiting, refusals(eventId));
    }

    /** Who the event's rules turned away at the signup (and why), for the dashboard. */
    public List<EventRepository.Refusal> refusals(long eventId) {
        return map.refusals(eventId).stream().map(r -> new EventRepository.Refusal(r.userId(), r.reason(), r.at())).toList();
    }

    /** Adds everyone who signed up to the event's players. Returns how many were added. Someone the event's rules turn away is left out. */
    public int sync(Guild guild, long eventId) {
        Event event = events.event(guild.getIdLong(), eventId);
        if (event.signupId() == null || !EventRules.acceptsJoins(event.status())) return 0;
        Set<Long> already = new HashSet<>();
        events.participants(eventId).forEach(p -> already.add(p.userId()));
        Set<Long> barred = new HashSet<>();
        java.util.Map<Long, String> whyBarred = new java.util.HashMap<>();
        events.bans(eventId).forEach(b -> { barred.add(b.userId()); whyBarred.put(b.userId(), b.reason()); });
        int added = 0;
        List<SignupEntry> entries = signups.getEntries(event.signupId());
        for (SignupEntry entry : entries) {
            if (barred.contains(entry.userId())) {
                // barred from this event: off the signup, and told why (once)
                if (signups.removeUser(guild, event.signupId(), entry.userId())) signups.updateMessages(guild, event.signupId());
                if (map.recordRefusal(eventId, entry.userId(), whyBarred.get(entry.userId()))) tellRefused(guild, event, entry.userId(), "you were barred from it: " + whyBarred.get(entry.userId()));
                continue;
            }
            if (already.contains(entry.userId())) {
                // already on the roster; a player who is on the signup is one the signup now follows
                map.addSignupEntry(eventId, entry.userId());
                continue;
            }
            try {
                // added by the bot itself, so the deadline doesn't refuse a signup made in time; as the player's own signup, the hub's queue and seat
                // quotas still apply (B-59)
                EventService.Joined joined = events.joinFromSignup(guild.getIdLong(), eventId, entry.userId(), events.self(guild.getIdLong()));
                map.addSignupEntry(eventId, entry.userId());
                map.clearRefusal(eventId, entry.userId());
                // on the waitlist (their side's places are taken): tell them, so they don't think they are in; it happens once, as they are on the
                // roster from now on
                if (joined.waiting()) tellWaiting(guild, event, entry.userId(), joined.position());
                else added++;
            } catch (EventProblem e) {
                log.debug("Signup entry {} wasn't added to event {}: {}", entry.userId(), eventId, e.getMessage());
                // remember why, so staff can see it on the dashboard, and tell the player once
                if (map.recordRefusal(eventId, entry.userId(), e.getMessage())) tellRefused(guild, event, entry.userId(), e.getMessage());
            }
        }
        prune(guild, event, entries);
        return added;
    }

    /**
     * Takes players off the roster (and their team) who were on the Discord signup and are not any more, because staff removed them there. Only while the
     * event is still taking signups, only players the signup follows, and only when the signup is really there to read: a signup that can't be found would
     * look empty and take everyone off.
     */
    private void prune(Guild guild, Event event, List<SignupEntry> entries) {
        if (event.signupId() == null || event.status() != Status.SIGNUPS || signups.getSessionById(event.signupId()) == null) return;
        Set<Long> onSignup = new HashSet<>();
        entries.forEach(e -> onSignup.add(e.userId()));
        Set<Long> onRoster = new HashSet<>();
        events.participants(event.id()).forEach(p -> onRoster.add(p.userId()));
        for (long userId : map.signupEntries(event.id())) {
            if (onSignup.contains(userId)) continue;
            if (!onRoster.contains(userId)) {
                map.removeSignupEntry(event.id(), userId);
                continue;
            }
            try {
                events.leave(guild.getIdLong(), event.id(), userId, events.self(guild.getIdLong()));
                map.removeSignupEntry(event.id(), userId);
                log.info("{} was taken off the roster of event {}: they are no longer on its Discord signup", userId, event.id());
            } catch (EventProblem e) {
                // for instance a paid buy-in, which has to be refunded before anyone is removed: leave them and tell staff on the dashboard
                log.info("{} stays on the roster of event {} although they left the signup: {}", userId, event.id(), e.getMessage());
                map.recordRefusal(event.id(), userId, "They were taken off the Discord signup but couldn't be taken off the roster: " + e.getMessage());
            }
        }
    }

    /** Takes players off the roster who left the Discord signup, without adding anyone: for an event whose signups are closed but not started. */
    public void syncRemovals(Guild guild, long eventId) {
        Event event = events.event(guild.getIdLong(), eventId);
        if (event.signupId() == null || event.status() != Status.SIGNUPS) return;
        prune(guild, event, signups.getEntries(event.signupId()));
    }

    /** DMs a player whose signup was turned away, with the reason. A closed DM is fine: the dashboard still shows it to staff. */
    private void tellWaiting(Guild guild, Event event, long userId, int position) {
        String place = position > 0 ? " (number " + position + ")" : "";
        String message = "You signed up for **" + event.name() + "**. It's full for now, so you're on the waitlist" + place + ". You'll get a place if one frees up.";
        guild.getJDA().retrieveUserById(userId).flatMap(user -> user.openPrivateChannel()).flatMap(dm -> dm.sendMessage(message))
                .queue(ok -> { }, error -> log.debug("Could not DM {} about a waitlisted signup: {}", userId, error.getMessage()));
    }

    private void tellRefused(Guild guild, Event event, long userId, String reason) {
        String message = "You signed up for **" + event.name() + "** but couldn't be added: " + reason + " If that looks wrong, ask an organiser to add you.";
        guild.getJDA().retrieveUserById(userId).flatMap(user -> user.openPrivateChannel()).flatMap(dm -> dm.sendMessage(message))
                .queue(ok -> { }, error -> log.debug("Could not DM {} about a refused signup: {}", userId, error.getMessage()));
    }

    /**
     * Called when an event changes stage: opening signups posts the Discord signup when the event has its channels chosen, starting the event closes
     * the signup, finishing or cancelling it removes the signup.
     */
    public void onStage(Guild guild, long eventId, Status to) {
        Event event = events.event(guild.getIdLong(), eventId);
        if (event.signupId() == null) {
            if (to == Status.SIGNUPS && stateOf(event).channelsChosen()) {
                try {
                    open(guild, eventId, guild.getJDA().getSelfUser().getIdLong());
                } catch (EventProblem e) {
                    log.info("The signup of event {} wasn't posted: {}", eventId, e.getMessage());
                }
            }
            return;
        }
        if (to == Status.RUNNING || to == Status.SCORING) {
            sync(guild, eventId);
            pause(guild, event.signupId());
        } else if (to == Status.DONE || to == Status.CANCELLED) {
            signups.deleteSignup(guild, event.signupId());
            map.setSignup(eventId, null);
        }
    }

    /** Pauses the Discord signup and redraws its panels, so they say PAUSED at once instead of when someone next clicks one. */
    private void pause(Guild guild, long signupId) {
        if (!signups.isSignupActive(signupId)) return;
        signups.togglePause(signupId);
        signups.updateMessages(guild, signupId);
    }

    void sweep(JDA jda) {
        if (AutoPostRouter.diverting() || !events.enabled()) return;
        try {
            for (HubEventMap.Row row : map.eventsWithSignup()) {
                Guild guild = Shards.guild(jda, row.guildId());
                if (guild == null) continue;
                try {
                    Event event = events.eventById(row.localId()).orElse(null);
                    if (event == null) {
                        // the hub no longer has it: the signup goes too
                        signups.deleteSignup(guild, row.signupId());
                        map.setSignup(row.localId(), null);
                        continue;
                    }
                    switch (event.status()) {
                        case SIGNUPS -> {
                            // an attached signup is kept in step until the event closes it, which the deadline sweep above does
                            // open: both ways; closed: the Discord signup is paused, but staff can still take people off it
                            if (!events.setup(event.id()).signupsClosed()) sync(guild, event.id());
                            else syncRemovals(guild, event.id());
                        }
                        case RUNNING, SCORING -> pause(guild, event.signupId());
                        case DONE, CANCELLED -> onStage(guild, event.id(), event.status());
                        default -> { }
                    }
                } catch (RuntimeException e) {
                    log.warn("Event signup sweep failed for event {}", row.localId(), e);
                }
            }
        } catch (RuntimeException e) {
            log.error("Event signup sweep failed", e);
        }
    }
}
