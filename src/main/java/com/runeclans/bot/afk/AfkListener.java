package com.runeclans.bot.afk;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.afk.AfkRepository.Afk;
import com.runeclans.bot.hub.HubService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Looks at each message in a server for two things: the author was away and has just spoken (so they are back), and the message
 * mentions someone who is away (so the sender is told). Its replies mention nobody and clean themselves up. Registered with JDA by
 * {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class AfkListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(AfkListener.class);
    private static final int MAX_NOTED = 3;

    private final AfkService service;
    private final HubService hub;

    public AfkListener(AfkService service, HubService hub) {
        this.service = service;
        this.hub = hub;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (AutoPostRouter.skipSweep("AFK")) return; // replies and changes nicknames in the real server: only the live bot does that (B-82 follow-up)
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        try {
            long guildId = event.getGuild().getIdLong();
            Message message = event.getMessage();
            HubService.AfkPolicy policy = hub.afkPolicy(guildId);
            if (isIgnored(policy, event)) return;

            if (service.clear(guildId, event.getAuthor().getIdLong())) {
                if (policy.nicknameTag() && event.getMember() != null) AfkNicknames.markBack(event.getGuild(), event.getMember());
                reply(message, "Welcome back, " + event.getAuthor().getEffectiveName() + "! I've taken you off the away list.", 8);
            }

            List<String> lines = new ArrayList<>();
            for (Member mentioned : message.getMentions().getMembers()) {
                if (mentioned.getIdLong() == event.getAuthor().getIdLong() || mentioned.getUser().isBot()) continue;
                service.get(guildId, mentioned.getIdLong()).ifPresent((Afk afk) -> lines.add(AfkService.line(mentioned.getEffectiveName(), afk)));
                if (lines.size() >= MAX_NOTED) break;
            }
            if (!lines.isEmpty()) reply(message, String.join("\n", lines), 20);
        } catch (Exception e) {
            log.error("AFK check failed for a message in guild {}", event.getGuild().getId(), e);
        }
    }

    /** Whether the server asked for away status to be left alone in this channel (or the channel a thread belongs to). */
    private static boolean isIgnored(HubService.AfkPolicy policy, MessageReceivedEvent event) {
        if (policy.ignoreChannelIds().isEmpty()) return false;
        if (policy.ignoreChannelIds().contains(event.getChannel().getIdLong())) return true;
        return event.getChannel() instanceof net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel thread
                && policy.ignoreChannelIds().contains(thread.getParentChannel().getIdLong());
    }

    private static void reply(Message message, String text, int deleteAfterSeconds) {
        if (!message.getChannel().canTalk()) return;
        message.reply(text).mentionRepliedUser(false).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                .queue(sent -> sent.delete().queueAfter(deleteAfterSeconds, TimeUnit.SECONDS, ok -> {}, error -> {}), error -> {});
    }
}
