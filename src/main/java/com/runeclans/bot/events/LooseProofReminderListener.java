package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.config.PrivilegedIntents;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.highlight.HighlightService;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Reminds a player who posts a picture in their own event channel (a player's or team's channel) without {@code /submit}, if the running event asked for it
 * ({@code config.submissions.remind}, off by default). One reminder per player per event per hour; staff, the event's organisers and bots are never reminded; the
 * reminder mentions nobody and removes itself after two minutes. Needs the Message Content intent (without it a message carries no attachments to look at).
 * Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class LooseProofReminderListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(LooseProofReminderListener.class);
    static final long EVERY_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final EventSubmissions submissions;
    private final HubEvents service;
    private final AdminRoleFilter adminRoleFilter;
    private final Map<String, Long> reminded = new ConcurrentHashMap<>();

    public LooseProofReminderListener(EventSubmissions submissions, HubEvents service, AdminRoleFilter adminRoleFilter) {
        this.submissions = submissions;
        this.service = service;
        this.adminRoleFilter = adminRoleFilter;
    }

    /** Whether the key of an event channel is one a player sends proof from: their own, or their team's. The shared channels of an event are for chatting. */
    static boolean isProofChannel(String key) {
        return key != null && (key.startsWith("player:") || key.startsWith("team:"));
    }

    static boolean hasPicture(Iterable<Message.Attachment> attachments) {
        for (Message.Attachment a : attachments) {
            String type = a.getContentType();
            if (a.isImage() || (type != null && type.startsWith("image/"))) return true;
        }
        return false;
    }

    /** Whether this reminder is due: not for someone who may run the event, and not again within the hour. Stamps the time when it is due. */
    boolean due(long eventId, long userId, boolean mayOrganise, long now) {
        if (mayOrganise) return false;
        String key = eventId + ":" + userId;
        Long last = reminded.get(key);
        if (last != null && now - last < EVERY_MILLIS) return false;
        reminded.put(key, now);
        return true;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (AutoPostRouter.skipSweep("loose-proof reminder")) return; // replies in the real server: only the live bot does that (B-82 follow-up)
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage() || event.getMessage().getAttachments().isEmpty()) return;
        try {
            if (!PrivilegedIntents.messageContent() || !hasPicture(event.getMessage().getAttachments())) return;
            long guildId = event.getGuild().getIdLong();
            EventSubmit.Channel channel = submissions.channelOf(guildId, HighlightService.rootChannelId(event.getGuildChannel()));
            if (channel == null || !isProofChannel(channel.key())) return;
            EventRules.Status status;
            Event target;
            try {
                target = service.event(guildId, channel.eventId());
            } catch (EventProblem problem) {
                return;
            }
            status = target.status();
            if (status != EventRules.Status.RUNNING || !service.settingsOf(target).submissions().remind()) return;
            Member member = event.getMember();
            boolean staff = member != null && (member.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(event.getGuild(), member));
            if (!due(target.id(), event.getAuthor().getIdLong(), service.mayOrganise(target, event.getAuthor().getIdLong(), staff), System.currentTimeMillis())) return;
            if (!event.getChannel().canTalk()) return;
            event.getMessage().reply("📸 Proof only counts when it's sent with `/submit event` (pick the tile or objective and attach your screenshot). A picture posted here on its own isn't seen by the reviewers.")
                    .mentionRepliedUser(false).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                    .queue(sent -> sent.delete().queueAfter(2, TimeUnit.MINUTES, ok -> {}, error -> {}), error -> log.debug("Couldn't send a proof reminder"));
        } catch (Exception e) {
            log.error("The proof reminder failed for a message in guild {}", event.getGuild().getId(), e);
        }
    }
}
