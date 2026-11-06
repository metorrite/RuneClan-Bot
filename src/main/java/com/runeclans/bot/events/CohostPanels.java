package com.runeclans.bot.events;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.JoinLinks;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.events.EventCohostRepository.PendingJoin;
import com.runeclans.bot.events.EventRepository.Event;
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
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Signup panels in co-host servers (T-221, design {@code docs/EVENTS-CROSS-SERVER.md} sections 3 and 5). When a co-host accepts it picks a panel
 * channel; the host's event lifecycle opens the panel there while signups are open, shows it closed once they close, and deletes it when the event is
 * over. Pressing Join on it:
 * <ul>
 *   <li>in the host's Discord already: joined at once, with the co-host as the server they came through (their origin);</li>
 *   <li>not in it: an answer only they see with the host's invite, and a pending join that completes when they join the host's Discord (until signups
 *       close). the bot never adds or messages anyone outside the host's server (D10).</li>
 * </ul>
 * Optional, off by default: when the event starts, a short announcement in each co-host's panel channel. Like the other automatic posts, nothing is
 * posted or changed on its own by a bot that isn't the live one ({@link AutoPostRouter}). The panel is its own small message rather than a generic signup,
 * because a generic signup's Join only records an entry and can't answer with the host's invite at the moment it is pressed. The event, who co-hosts
 * it, the roster and the channel each co-host chose for its panel are the hub's (T-934, T-941); only the id of the panel message the bot posted is
 * the bot's own ({@code hub_event_cohost_panel}).
 */
@BService
public class CohostPanels extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(CohostPanels.class);
    static final String JOIN = "cohost_join:";
    /** A pending join with no signup deadline and no start time lasts this long. */
    static final Duration PENDING_FALLBACK = Duration.ofDays(30);
    /** The host's switch in the event settings: announce the start in the co-hosts' panel channels (off unless switched on). */
    public static final String ANNOUNCE_START = "cohostAnnounceStart";
    private static final long REDRAW_DELAY_SECONDS = 5;
    /** The panel and the announcement go into another server's channel, written from the host's event name: they never ping anyone there. */
    static final java.util.EnumSet<net.dv8tion.jda.api.entities.Message.MentionType> NO_PINGS = java.util.EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class);

    /** Where a co-host's signup panel is: its channel (the hub's co-host row) and the message the bot posted (null when none is up). */
    public record Panel(long eventId, long guildId, Long channelId, Long messageId) {}

    private final HubEvents events;
    private final EventCohostRepository cohosts;
    private final JoinLinks joinLinks;
    private final ClanProfileRepository profiles;
    private final ScheduledExecutorService executor = BotScheduler.lane("cohost-panels");
    private final Map<Long, ScheduledFuture<?>> redraws = new ConcurrentHashMap<>();
    private volatile JDA jda;

    public CohostPanels(HubEvents events, EventCohostRepository cohosts, JoinLinks joinLinks, ClanProfileRepository profiles, EventChanges changes) {
        this.events = events;
        this.cohosts = cohosts;
        this.joinLinks = joinLinks;
        this.profiles = profiles;
        changes.subscribe(change -> {
            if (change.kind() == EventChanges.Kind.STAGE && "deleted".equals(change.note())) executor.execute(() -> gone(change.eventId()));
            else if (change.kind() == EventChanges.Kind.STAGE) executor.execute(() -> onStage(change.eventId()));
            else if (change.kind() == EventChanges.Kind.ROSTER) redrawSoon(change.eventId());
        });
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> {
            try {
                cohosts.purgeExpiredPendingJoins(Instant.now());
            } catch (RuntimeException e) {
                log.warn("Purging expired pending joins failed", e);
            }
        }, 10, 24 * 60, TimeUnit.MINUTES);
    }

    // ---------- the rules (pure) ----------

    /** When a pending join stops waiting: the signup deadline, else the start, else a month from now. */
    static Instant pendingExpiry(Instant signupClosesAt, Instant startsAt, Instant now) {
        if (signupClosesAt != null && signupClosesAt.isAfter(now)) return signupClosesAt;
        if (startsAt != null && startsAt.isAfter(now)) return startsAt;
        return now.plus(PENDING_FALLBACK);
    }

    /** Whether a co-host's panel takes joins now: the event takes signups, they haven't been closed, and the deadline hasn't passed. */
    boolean open(Event event) {
        return event.status() == Status.SIGNUPS && !events.setup(event.id()).signupsClosed() && events.settingsOf(event).selfSignupOpen(Instant.now());
    }

    // ---------- the panel ----------

    /** The host as players see it: its listed clan's name, else its server's name. */
    String hostName(long hostGuildId) {
        String listed = profiles.get(hostGuildId).filter(p -> p.visibility() == ClanProfile.Visibility.PUBLIC).map(ClanProfile::displayName).filter(n -> n != null && !n.isBlank()).orElse(null);
        if (listed != null) return listed;
        Guild guild = jda == null ? null : Shards.guild(jda, hostGuildId);
        return guild == null ? "the host's server" : guild.getName();
    }

    /** The host's one join link ({@link JoinLinks}: the same one its hub page and events show), or null. */
    String inviteOf(long hostGuildId) {
        return joinLinks.of(hostGuildId, profiles.get(hostGuildId).map(ClanProfile::applyUrl).orElse(null));
    }

    Container panel(Event event) {
        boolean open = open(event);
        long players = events.participants(event.id()).stream().filter(p -> !p.waiting()).count();
        StringBuilder text = new StringBuilder("## ").append(event.name()).append("\nHosted by **").append(hostName(event.guildId())).append("**, together with this server.");
        if (event.startsAt() != null) text.append("\nStarts <t:").append(event.startsAt().getEpochSecond()).append(":F>");
        text.append("\n").append(players).append(event.maxParticipants() > 0 ? " of " + event.maxParticipants() : "").append(players == 1 ? " player" : " players");
        text.append("\n\n").append(open ? "Press **Join** to take part. You play from the host's Discord: if you aren't in it yet, you'll get its invite."
                : event.status() == Status.SIGNUPS ? "Signups have closed." : "Signups are over.");
        Button join = Button.success(JOIN + event.id(), open ? Text.of(event.guildId(), "events.join") : Text.of(event.guildId(), "events.signups_closed")).withDisabled(!open);
        return Containers.card(open ? Containers.PRIMARY : Color.GRAY, TextDisplay.of(text.toString()), ActionRow.of(join));
    }

    /** The panels of the event's accepted co-hosts that chose a channel: a co-host clan whose server the bot isn't in has none. */
    public List<Panel> panels(long eventId) {
        JDA client = jda;
        List<Long> candidates = client == null ? List.of() : Shards.guilds(client).stream().map(Guild::getIdLong).toList();
        List<Panel> out = new java.util.ArrayList<>();
        for (HubEvents.Cohost c : events.cohosts(eventId)) {
            if (!c.status().equals(EventCohostRepository.Status.ACCEPTED.name()) || c.panelChannelId() == null) continue;
            events.guildOf(c.clanId(), candidates).ifPresent(guildId -> out.add(new Panel(eventId, guildId, c.panelChannelId(), events.map().cohostPanel(eventId, guildId).map(HubEventMap.CohostPanel::messageId).orElse(null))));
        }
        return out;
    }

    /** One co-host server's panel, whatever its status (its channel null until chosen), or empty when the server's clan doesn't co-host the event. */
    public java.util.Optional<Panel> panel(long eventId, long guildId) {
        UUID clan = events.clanOf(guildId).orElse(null);
        if (clan == null) return java.util.Optional.empty();
        return events.cohosts(eventId).stream().filter(c -> c.clanId().equals(clan)).findFirst()
                .map(c -> new Panel(eventId, guildId, c.panelChannelId(), events.map().cohostPanel(eventId, guildId).map(HubEventMap.CohostPanel::messageId).orElse(null)));
    }

    /** A co-host server's staff chose (or cleared) its panel channel: it goes on the hub's co-host row, the old panel comes down and a new one goes up. */
    public void setChannel(Event event, long cohostGuildId, Long channelId, Actor actor) {
        UUID clan = events.clanOf(cohostGuildId).orElseThrow(() -> new EventProblem(409, HubEvents.NO_CLAN_TEXT));
        remove(event, cohostGuildId);
        events.setCohostChannel(event.id(), clan, channelId, actor);
        if (channelId != null) post(event, cohostGuildId);
    }

    private void setMessage(Panel p, Long messageId) {
        if (messageId == null || p.channelId() == null) events.map().clearCohostPanel(p.eventId(), p.guildId());
        else events.map().setCohostPanel(p.eventId(), p.guildId(), p.channelId(), messageId);
    }

    private TextChannel channelOf(Panel p) {
        Guild guild = jda == null ? null : Shards.guild(jda, p.guildId());
        return guild == null || p.channelId() == null ? null : guild.getTextChannelById(p.channelId());
    }

    /** Whether the bot can post the panel in {@code channel}. */
    public static boolean canPost(TextChannel channel) {
        return channel != null && channel.getGuild().getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND);
    }

    /** Posts (or redraws) one co-host's panel now; a staff action, so it isn't held back on a bot that isn't live. Nothing while signups aren't open yet. */
    public void post(Event event, long cohostGuildId) {
        panel(event.id(), cohostGuildId).ifPresent(p -> show(event, p, true));
    }

    /** Takes one co-host's panel down (it was removed, or its channel changed). */
    public void remove(Event event, long cohostGuildId) {
        panel(event.id(), cohostGuildId).ifPresent(this::delete);
    }

    private void show(Event event, Panel p, boolean postIfMissing) {
        TextChannel channel = channelOf(p);
        if (channel == null) return;
        if (p.messageId() == null) {
            if (!postIfMissing || event.status() != Status.SIGNUPS || !canPost(channel)) return;
            channel.sendMessageComponents(List.of(panel(event))).useComponentsV2(true).setAllowedMentions(NO_PINGS).queue(
                    m -> setMessage(p, m.getIdLong()),
                    e -> log.info("Couldn't post the co-host panel of event {} in server {}: {}", event.id(), p.guildId(), e.getMessage()));
            return;
        }
        channel.editMessageComponentsById(p.messageId(), List.of(panel(event))).useComponentsV2(true).setAllowedMentions(NO_PINGS).queue(ok -> { }, e -> {
            if (e instanceof ErrorResponseException r && r.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) setMessage(p, null);
            else log.debug("Couldn't redraw the co-host panel of event {} in server {}: {}", event.id(), p.guildId(), e.getMessage());
        });
    }

    private void delete(Panel p) {
        setMessage(p, null);
        TextChannel channel = channelOf(p);
        if (channel == null || p.messageId() == null) return;
        channel.deleteMessageById(p.messageId()).queue(ok -> { }, e -> log.debug("Couldn't delete the co-host panel of event {}: {}", p.eventId(), e.getMessage()));
    }

    /** The event's lifecycle: panels go up while signups are open, show closed after, and go when it is over; the start may be announced. */
    void onStage(long eventId) {
        if (AutoPostRouter.diverting()) return;
        try {
            Event event = events.eventById(eventId).orElse(null);
            if (event == null) return;
            List<Panel> panels = panels(eventId);
            switch (event.status()) {
                case SIGNUPS -> panels.forEach(p -> show(event, p, true));
                case RUNNING, SCORING -> {
                    panels.forEach(p -> show(event, p, false));
                    cohosts.deletePendingJoins(eventId, null);
                    if (event.status() == Status.RUNNING && announceStart(event)) panels.forEach(p -> announce(event, p));
                }
                case DRAFT, DONE, CANCELLED -> {
                    panels.forEach(this::delete);
                    cohosts.deletePendingJoins(eventId, null);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't bring the co-host panels of event {} up to date", eventId, e);
        }
    }

    /** The event is gone from the hub: its panels come down and nobody waits to join it any more. */
    void gone(long eventId) {
        try {
            // the hub no longer lists its co-hosts: the panels the bot remembers posting come down
            for (HubEventMap.CohostPanel p : events.map().cohostPanels(eventId)) delete(new Panel(eventId, p.guildId(), p.channelId(), p.messageId()));
            cohosts.deletePendingJoins(eventId, null);
        } catch (RuntimeException e) {
            log.warn("Couldn't take down the co-host panels of deleted event {}", eventId, e);
        }
    }

    /** The roster changed (someone joined, signups closed or opened again): redraw the panels a few seconds later, once. */
    void redrawSoon(long eventId) {
        ScheduledFuture<?> before = redraws.put(eventId, executor.schedule(() -> {
            redraws.remove(eventId);
            if (AutoPostRouter.diverting()) return;
            try {
                Event event = events.eventById(eventId).orElse(null);
                if (event == null) return;
                if (event.status() == Status.SIGNUPS && events.setup(eventId).signupsClosed()) cohosts.deletePendingJoins(eventId, null);
                for (Panel p : panels(eventId)) if (p.messageId() != null) show(event, p, false);
            } catch (RuntimeException e) {
                log.warn("Couldn't redraw the co-host panels of event {}", eventId, e);
            }
        }, REDRAW_DELAY_SECONDS, TimeUnit.SECONDS));
        if (before != null) before.cancel(false);
    }

    public static boolean announceStart(Event event) {
        try {
            DataObject config = DataObject.fromJson(event.config());
            return config.hasKey(ANNOUNCE_START) && !config.isNull(ANNOUNCE_START) && config.getBoolean(ANNOUNCE_START);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void announce(Event event, Panel p) {
        TextChannel channel = channelOf(p);
        if (!canPost(channel)) return;
        String invite = inviteOf(event.guildId());
        String text = "**" + event.name() + "** has started, hosted by **" + hostName(event.guildId()) + "**." + (invite == null ? "" : " Players take part from its Discord: " + invite);
        channel.sendMessageComponents(List.of(Containers.toast(Containers.INFO, text))).useComponentsV2(true).setAllowedMentions(NO_PINGS).queue(ok -> { },
                e -> log.debug("Couldn't announce the start of event {} in server {}: {}", event.id(), p.guildId(), e.getMessage()));
    }

    // ---------- Join on a co-host's panel ----------

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent interaction) {
        String id = interaction.getComponentId();
        if (!id.startsWith(JOIN) || interaction.getGuild() == null) return;
        long eventId;
        try {
            eventId = Long.parseLong(id.substring(JOIN.length()));
        } catch (NumberFormatException e) {
            return;
        }
        long origin = interaction.getGuild().getIdLong();
        long userId = interaction.getUser().getIdLong();
        Event event = events.eventById(eventId).orElse(null);
        if (event == null) {
            Containers.replyEphemeral(interaction, Containers.WARNING, Text.of(interaction, "events.this_event_doesn_t_exist_any"));
            return;
        }
        UUID originClan = acceptedCohost(eventId, origin);
        if (originClan == null) {
            Containers.replyEphemeral(interaction, Containers.WARNING, Text.of(interaction, "events.this_server_doesn_t_co_host"));
            return;
        }
        if (!open(event)) {
            Containers.replyEphemeral(interaction, Containers.WARNING, Text.of(interaction, "events.signups_for_have_closed", event.name()));
            return;
        }
        Guild host = Shards.guild(interaction.getJDA(), event.guildId());
        if (host == null) {
            Containers.replyEphemeral(interaction, Containers.WARNING, Text.of(interaction, "events.the_host_server_can_t_be"));
            return;
        }
        interaction.deferReply(true).queue();
        host.retrieveMemberById(userId).queue(member -> answer(interaction, joinNow(event, originClan, userId)), error -> {
            if (error instanceof ErrorResponseException r && (r.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER || r.getErrorResponse() == ErrorResponse.UNKNOWN_USER)) {
                answer(interaction, joinLater(event, origin, userId, Instant.now()));
            } else {
                answer(interaction, "Couldn't check whether you're in the host's Discord. Try again in a moment.");
            }
        });
    }

    private static void answer(ButtonInteractionEvent interaction, String text) {
        interaction.getHook().sendMessageComponents(List.of(Containers.toast(Containers.INFO, text))).useComponentsV2(true).setEphemeral(true).queue();
    }

    /** The hub clan of the co-host server {@code origin}, when it accepted the invitation to co-host the event; null otherwise. */
    UUID acceptedCohost(long eventId, long origin) {
        try {
            UUID clan = events.clanOf(origin).orElse(null);
            if (clan == null) return null;
            return events.cohosts(eventId).stream().filter(c -> c.clanId().equals(clan) && c.status().equals(EventCohostRepository.Status.ACCEPTED.name()))
                    .findFirst().map(HubEvents.Cohost::clanId).orElse(null);
        } catch (RuntimeException e) {
            log.info("Couldn't check whether server {} co-hosts event {}: {}", origin, eventId, e.getMessage());
            return null;
        }
    }

    /** They are in the host's Discord: join with the co-host's clan as where they came through (the hub counts them against its seats). */
    String joinNow(Event event, UUID originClan, long userId) {
        try {
            EventService.Joined joined = events.join(event.guildId(), event.id(), userId, originClan, events.self(event.guildId()));
            cohosts.deletePendingJoin(event.id(), userId);
            String where = "You play from **" + hostName(event.guildId()) + "**'s Discord.";
            return joined.waiting() ? "**" + event.name() + "** is full, so you're on the waitlist (number " + joined.position() + "). " + where
                    : "You're in **" + event.name() + "**! " + where;
        } catch (EventProblem e) {
            return e.getMessage();
        }
    }

    /** They aren't in the host's Discord yet: remember it, and give them the invite. They join when they get there, until signups close. */
    String joinLater(Event event, long origin, long userId, Instant now) {
        cohosts.savePendingJoin(event.id(), userId, origin, pendingExpiry(events.settingsOf(event).signupClosesAt(), event.startsAt(), now));
        String host = hostName(event.guildId());
        String invite = inviteOf(event.guildId());
        return "To take part you need to be in **" + host + "**'s Discord, where the event happens. "
                + (invite == null ? "Ask your staff for an invite to it." : "Join it here: " + invite)
                + "\nWhen you join it you're added to **" + event.name() + "** automatically, as long as signups are still open.";
    }

    /** Someone joined a server: any pending join for an event that server hosts completes now. */
    @BEventListener
    public void onMemberJoin(GuildMemberJoinEvent join) {
        completePendingJoins(join.getGuild().getIdLong(), join.getUser().getIdLong(), Instant.now());
    }

    /** Completes a user's pending joins for events {@code hostGuildId} hosts. Returns how many joined. */
    int completePendingJoins(long hostGuildId, long userId, Instant now) {
        int joined = 0;
        try {
            for (PendingJoin p : cohosts.pendingJoins(userId, hostGuildId, now)) {
                try {
                    Event event = events.eventById(p.eventId()).orElse(null);
                    UUID originClan = event == null ? null : acceptedCohost(event.id(), p.originGuildId());
                    if (event != null && originClan != null && open(event)) {
                        events.join(event.guildId(), event.id(), userId, originClan, events.self(event.guildId()));
                        joined++;
                        log.info("User {} joined event {} on arriving in its host's Discord (pending since pressing Join in server {})", userId, event.id(), p.originGuildId());
                    }
                } catch (EventProblem e) {
                    log.info("User {}'s pending join for event {} didn't go through: {}", userId, p.eventId(), e.getMessage());
                } finally {
                    cohosts.deletePendingJoin(p.eventId(), userId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't complete the pending joins of user {} in server {}", userId, hostGuildId, e);
        }
        return joined;
    }
}
