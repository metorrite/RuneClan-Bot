package com.runeclans.bot.commandchannel;

import com.runeclans.bot.alerts.AutoPostRouter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The enforcement half of command-only channels. Discord gives a bot a message only *after* it's
 * already posted, so there is no way to stop one before it appears — this deletes it as fast as the
 * event arrives (it may flash for a moment) and answers with a short notice.
 * <p>
 * The notice can't be a true ephemeral: Discord only allows those as the response to an interaction (a
 * slash command or button), never to a typed message. So it's a normal message that mentions the
 * sender and deletes itself after {@value #NOTICE_LIFETIME_SECONDS} seconds. If someone fires off several
 * messages at once, every one is deleted but the notice is only posted once per
 * {@value #NOTICE_COOLDOWN_MILLIS}ms per person per channel, so the cleanup doesn't become its own spam.
 * <p>
 * Never touches: other bots (including this one's own slash-command responses, which arrive as
 * bot-authored messages), webhooks, Discord's system messages, or anyone the group's rules exempt.
 */
@BService
public class CommandChannelListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(CommandChannelListener.class);

    static final int NOTICE_LIFETIME_SECONDS = 10;
    static final long NOTICE_COOLDOWN_MILLIS = 8_000;
    private static final int COOLDOWN_MAP_TRIM_THRESHOLD = 500;

    private final CommandChannelService service;
    private final ConcurrentHashMap<String, Long> lastNoticeAt = new ConcurrentHashMap<>();
    // Channels already logged as missing the Manage Messages permission — once is enough per run.
    private final Set<Long> warnedMissingPermission = ConcurrentHashMap.newKeySet();

    public CommandChannelListener(CommandChannelService service) {
        this.service = service;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (AutoPostRouter.skipSweep("command-only channel")) return; // deletes messages and posts notices in the real server: only the live bot does that (B-82 follow-up)
        try {
            if (!event.isFromGuild() || event.isWebhookMessage() || event.getAuthor().isBot()) return;

            Message message = event.getMessage();
            if (message.getType().isSystem()) return;

            Member member = event.getMember();
            if (member == null) return;

            Guild guild = event.getGuild();
            CommandChannelGroup group = service.findGroup(guild.getIdLong(), event.getChannel().getIdLong());
            if (group == null || !group.enabled()) return;
            if (!service.appliesTo(group, member, guild)) return;

            enforce(event, message, member, group);
        } catch (Exception e) {
            // A bug here must never take the whole event stream down with it.
            log.error("Command-channel check failed for message {} in channel {}", event.getMessageIdLong(), event.getChannel().getIdLong(), e);
        }
    }

    private void enforce(MessageReceivedEvent event, Message message, Member member, CommandChannelGroup group) {
        GuildMessageChannel channel = event.getGuildChannel(); // the union type is itself a GuildMessageChannel

        if (!event.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            if (warnedMissingPermission.add(channel.getIdLong())) {
                log.warn("Command-only channel {} in guild {} needs the 'Manage Messages' permission for the bot — its messages can't be deleted.",
                        channel.getIdLong(), event.getGuild().getIdLong());
            }
            return;
        }

        message.delete().queue(
                success -> sendNotice(channel, member, group),
                new ErrorHandler()
                        .ignore(ErrorResponse.UNKNOWN_MESSAGE) // someone else (or a moderator) got there first
                        .handle(Throwable.class, throwable -> log.warn("Failed to delete a message in command-only channel {}", channel.getIdLong(), throwable)));
    }

    private void sendNotice(GuildMessageChannel channel, Member member, CommandChannelGroup group) {
        if (!channel.canTalk()) return;
        if (!shouldSendNotice(channel.getIdLong(), member.getIdLong())) return;

        // Only the sender can be pinged — an admin-written custom notice containing @everyone or a role
        // mention must never actually fire it.
        channel.sendMessage(member.getAsMention() + " " + CommandChannelService.messageFor(group))
                .setAllowedMentions(EnumSet.of(Message.MentionType.USER))
                .queue(sent -> sent.delete().queueAfter(NOTICE_LIFETIME_SECONDS, TimeUnit.SECONDS, null,
                                new ErrorHandler().ignore(ErrorResponse.UNKNOWN_MESSAGE)),
                        error -> log.warn("Failed to post a command-channel notice in channel {}", channel.getIdLong(), error));
    }

    private boolean shouldSendNotice(long channelId, long userId) {
        long now = System.currentTimeMillis();
        if (lastNoticeAt.size() > COOLDOWN_MAP_TRIM_THRESHOLD) {
            lastNoticeAt.values().removeIf(at -> now - at > NOTICE_COOLDOWN_MILLIS);
        }

        String key = channelId + ":" + userId;
        Long previous = lastNoticeAt.get(key);
        if (previous != null && now - previous < NOTICE_COOLDOWN_MILLIS) return false;
        lastNoticeAt.put(key, now);
        return true;
    }
}
