package com.runeclans.bot.eventannounce;

import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.discord.Mentions;
import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.eventannounce.EventAnnounceRepository.Tracked;
import com.runeclans.bot.eventannounce.EventAnnounceRules.Card;
import com.runeclans.bot.eventannounce.EventAnnounceRules.Settings;
import com.runeclans.bot.eventannounce.EventAnnounceRules.State;
import com.runeclans.bot.eventannounce.EventAnnounceRules.Step;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Announces a server's Discord scheduled events in a channel, keeps the announcement in step as the event changes, says when it starts, and
 * tidies up when it ends. Nothing is announced until a server turns this on and picks a channel. Every change goes through one background thread, one
 * after another, so an event that is edited right after it is created can't be announced twice or leave a stale message, and each event is claimed in
 * the database before anything is posted. A temporary "interested" role is made only if the server asked for one and has room under Discord's 250 roles,
 * and it is deleted when the event ends or is cancelled; a sweep at start-up and every fifteen minutes catches events that ended while the bot was off.
 */
@BService
public class EventAnnounceService {
    private static final Logger log = LoggerFactory.getLogger(EventAnnounceService.class);
    public static final String BUTTON_PREFIX = "ea:";
    static final Permission[] CHANNEL_NEEDED = {Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS};

    private final EventAnnounceRepository repository;
    private volatile JDA jda;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("event-announcer"));
    private final ScheduledExecutorService sweeper = BotScheduler.lane("event-announcer-sweep");

    public EventAnnounceService(EventAnnounceRepository repository) {
        this.repository = repository;
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        sweeper.scheduleWithFixedDelay(() -> worker.execute(this::sweep), 60, 900, TimeUnit.SECONDS);
    }

    /** Why the announcer can't post in this channel, in words for the person setting it up, or null when it can. */
    public static String problemWith(GuildMessageChannel channel) {
        if (!channel.getGuild().getSelfMember().hasPermission(channel, CHANNEL_NEEDED)) {
            return "The bot needs the View Channel, Send Messages and Embed Links permissions in #" + channel.getName() + ".";
        }
        return null;
    }

    // ---------- what the listener hands in ----------

    public void created(ScheduledEvent event) {
        worker.execute(() -> guarded("announce", event, () -> announce(event)));
    }

    public void changed(ScheduledEvent event) {
        worker.execute(() -> guarded("update", event, () -> update(event)));
    }

    public void deleted(long guildId, long eventId) {
        worker.execute(() -> {
            try {
                repository.tracked(eventId).ifPresent(t -> finish(guildId, t, null, State.CANCELED));
            } catch (RuntimeException e) {
                log.warn("Event announcer: could not tidy up deleted event {}", eventId, e);
            }
        });
    }

    private void guarded(String what, ScheduledEvent event, Runnable work) {
        if (AutoPostRouter.diverting()) return; // announcements are tracked and edited later: a bot that is not live makes none
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("Event announcer: could not {} event {} in guild {}", what, event.getId(), event.getGuild().getId(), e);
        }
    }

    // ---------- announce, update, start, end ----------

    void announce(ScheduledEvent event) {
        Guild guild = event.getGuild();
        Settings settings = repository.settings(guild.getIdLong());
        if (!settings.active()) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, settings.channelId());
        if (channel == null || problemWith(channel) != null) return;
        if (!repository.claim(event.getIdLong(), guild.getIdLong())) return; // already announced

        Role role = null;
        boolean roleSaved = false;
        Message sent = null;
        try {
            if (settings.useRole() && guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES) && EventAnnounceRules.canMakeRole(guild.getRoles().size())) {
                role = guild.createRole().setName(EventAnnounceRules.roleName(event.getName())).setPermissions(Collections.emptyList()).setMentionable(true)
                        .reason("Role for the event " + event.getName()).complete();
                repository.setRole(event.getIdLong(), role.getIdLong()); // saved before posting, so the role never outlives the event untracked
                roleSaved = true;
            } else if (settings.useRole()) {
                log.info("Event announcer: no role made for event {} in guild {} (missing Manage Roles or the server is close to 250 roles)", event.getId(), guild.getId());
            }
            MessageCreateBuilder message = new MessageCreateBuilder().setEmbeds(embed(event, EventAnnounceRules.state(event.getStatus().name())).build())
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
            if (role != null) message.setComponents(ActionRow.of(Button.primary(BUTTON_PREFIX + event.getId(), "I'm interested")));
            sent = channel.sendMessage(message.build()).complete();
            repository.setMessage(event.getIdLong(), channel.getIdLong(), sent.getIdLong(), role == null ? null : role.getIdLong());
            repository.setState(event.getIdLong(), EventAnnounceRules.state(event.getStatus().name()), false);
        } catch (RuntimeException e) {
            if (sent == null) undoAnnounce(event.getIdLong(), role, roleSaved);
            throw e;
        }
    }

    /**
     * The announcement couldn't be posted (B-102): delete the role it made and give the claim back, so no orphan role stays in the server. If the role
     * can't be deleted now, its id stays saved and the sweep or the event's end deletes it.
     */
    private void undoAnnounce(long eventId, Role role, boolean roleSaved) {
        if (role == null) {
            releaseQuietly(eventId);
            return;
        }
        role.delete().reason("The event's announcement couldn't be posted").queue(ok -> worker.execute(() -> releaseQuietly(eventId)), error -> {
            if (roleSaved) {
                log.info("Event announcer: could not delete role {} of unposted event {}; it is deleted when the event ends", role.getId(), eventId);
            } else {
                log.warn("Event announcer: role {} of unposted event {} could be neither saved nor deleted; delete it by hand", role.getId(), eventId, error);
            }
        });
    }

    private void releaseQuietly(long eventId) {
        try {
            repository.release(eventId);
        } catch (RuntimeException e) {
            log.warn("Event announcer: could not release the claim on event {}", eventId, e);
        }
    }

    private void update(ScheduledEvent event) {
        Tracked tracked = repository.tracked(event.getIdLong()).orElse(null);
        if (tracked == null) {
            // never announced (the announcer was off, or this event was created before it was on): a new event only gets announced when it is created
            return;
        }
        State now = EventAnnounceRules.state(event.getStatus().name());
        Step step = EventAnnounceRules.step(tracked.state(), now, tracked.startAnnounced());
        if (step == Step.END) {
            finish(event.getGuild().getIdLong(), tracked, event, now);
            return;
        }
        edit(event.getGuild(), tracked, embed(event, now));
        boolean started = tracked.startAnnounced();
        if (step == Step.START) {
            started = true;
            announceStart(event, tracked);
        }
        repository.setState(event.getIdLong(), now, started);
    }

    private void announceStart(ScheduledEvent event, Tracked tracked) {
        Guild guild = event.getGuild();
        Settings settings = repository.settings(guild.getIdLong());
        if (!settings.notifyStart() || tracked.channelId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, tracked.channelId());
        if (channel == null || problemWith(channel) != null) return;
        Role role = tracked.roleId() == null ? null : guild.getRoleById(tracked.roleId());
        String text = "**" + event.getName() + "** is starting now! " + (role == null ? "" : role.getAsMention()) + "\n" + url(event);
        var action = channel.sendMessage(text.strip());
        // the event's name is staff text from Discord: only the chosen role pings
        Mentions.onlyRoles(action, role == null ? new long[0] : new long[]{role.getIdLong()}).queue(ok -> {}, error -> {});
    }

    /** The event ended or was cancelled (or deleted): show it so, take the button away and delete its role. */
    private void finish(long guildId, Tracked tracked, ScheduledEvent event, State state) {
        Guild guild = jda == null ? null : Shards.guild(jda, guildId);
        if (guild != null && tracked.channelId() != null && tracked.messageId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, tracked.channelId());
            if (channel != null) {
                var card = event == null ? null : EventAnnounceRules.card(event.getName(), event.getDescription(), event.getStartTime().toEpochSecond(),
                        event.getEndTime() == null ? null : event.getEndTime().toEpochSecond(), where(event), state);
                MessageEditBuilder edit = new MessageEditBuilder().setComponents(Collections.emptyList());
                if (card != null) edit.setEmbeds(toEmbed(card, event).build());
                channel.editMessageById(tracked.messageId(), edit.build()).queue(ok -> {}, error -> log.debug("Event announcer: could not edit the message of event {}", tracked.eventId()));
            }
        }
        deleteRole(guild, tracked);
        repository.setState(tracked.eventId(), state, tracked.startAnnounced());
    }

    private void deleteRole(Guild guild, Tracked tracked) {
        if (tracked.roleId() == null) return;
        Role role = guild == null ? null : guild.getRoleById(tracked.roleId());
        if (role != null) role.delete().reason("The event is over").queue(ok -> {}, error -> log.debug("Event announcer: could not delete role {}", tracked.roleId()));
        repository.clearRole(tracked.eventId());
    }

    private void edit(Guild guild, Tracked tracked, EmbedBuilder embed) {
        if (tracked.channelId() == null || tracked.messageId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, tracked.channelId());
        if (channel == null) return;
        channel.editMessageEmbedsById(tracked.messageId(), embed.build()).queue(ok -> {}, error -> log.debug("Event announcer: could not edit the message of event {}", tracked.eventId()));
    }

    /** Catches events that ended, were cancelled or were deleted while the bot was off, and any role left behind. */
    void sweep() {
        if (AutoPostRouter.diverting()) return;
        try {
            JDA jda = this.jda;
            if (jda == null) return;
            for (Tracked tracked : repository.open()) {
                Guild guild = Shards.guild(jda, tracked.guildId());
                if (guild == null) continue;
                ScheduledEvent event = guild.getScheduledEventById(tracked.eventId());
                if (event == null) {
                    finish(tracked.guildId(), tracked, null, State.CANCELED);
                    continue;
                }
                State now = EventAnnounceRules.state(event.getStatus().name());
                if (!EventAnnounceRules.needsRole(now)) {
                    finish(tracked.guildId(), tracked, event, now);
                } else if (now != tracked.state()) {
                    update(event);
                }
            }
        } catch (Exception e) {
            log.error("Event announcer sweep failed", e);
        }
    }

    // ---------- the button ----------

    /** Gives or takes the event's role for someone who pressed the button. Returns what to tell them. */
    public String toggleInterest(Guild guild, net.dv8tion.jda.api.entities.Member member, long eventId) {
        Tracked tracked = repository.tracked(eventId).orElse(null);
        if (tracked == null || tracked.guildId() != guild.getIdLong() || tracked.roleId() == null || !EventAnnounceRules.needsRole(tracked.state())) {
            return "That event is over, so there is nothing to sign up for.";
        }
        Role role = guild.getRoleById(tracked.roleId());
        if (role == null) return "That event's role no longer exists.";
        if (member.getRoles().contains(role)) {
            guild.removeRoleFromMember(member, role).reason("No longer interested in the event").queue(ok -> {}, error -> {});
            return "Okay, you won't be told when it starts.";
        }
        if (!MemberApi.safeToAssign(guild, role)) return "The bot can't hand out this event's role. Ask a server admin to check it.";
        guild.addRoleToMember(member, role).reason("Interested in the event").queue(ok -> {}, error -> {});
        return "You will be pinged when it starts.";
    }

    // ---------- how it looks ----------

    private static String where(ScheduledEvent event) {
        var channel = event.getChannel();
        if (channel != null) return channel.getAsMention();
        String location = event.getLocation();
        return location == null || location.isBlank() ? null : location;
    }

    static String url(ScheduledEvent event) {
        return "https://discord.com/events/" + event.getGuild().getId() + "/" + event.getId();
    }

    private static EmbedBuilder embed(ScheduledEvent event, State state) {
        Card card = EventAnnounceRules.card(event.getName(), event.getDescription(), event.getStartTime().toEpochSecond(),
                event.getEndTime() == null ? null : event.getEndTime().toEpochSecond(), where(event), state);
        return toEmbed(card, event);
    }

    private static EmbedBuilder toEmbed(Card card, ScheduledEvent event) {
        EmbedBuilder embed = new EmbedBuilder().setTitle(card.title(), url(event)).setColor(card.color()).setFooter(card.footer());
        if (!card.description().isEmpty()) embed.setDescription(card.description());
        embed.addField("When", card.when(), false);
        if (card.where() != null) embed.addField("Where", card.where(), false);
        return embed;
    }
}
