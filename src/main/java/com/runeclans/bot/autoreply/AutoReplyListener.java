package com.runeclans.bot.autoreply;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers messages that match a server's automatic replies. Only the first matching reply answers a message, and a reply stays quiet
 * for its cooldown after answering anywhere in the server. Bots (this one included), webhooks and direct messages never trigger one,
 * and the answer mentions nobody except, if the reply uses {@code {user}}, the person who wrote the message. Registered with JDA by
 * {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class AutoReplyListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(AutoReplyListener.class);

    private final AutoReplyRepository repository;
    private final Map<Long, Long> lastAnswered = new ConcurrentHashMap<>();

    public AutoReplyListener(AutoReplyRepository repository) {
        this.repository = repository;
    }

    /** Fills the reply's variables: {user} is the person who wrote the message, {server} the server's name. */
    static String fill(String reply, String authorMention, String serverName) {
        return reply.replace("{user}", authorMention).replace("{server}", serverName);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (AutoPostRouter.skipSweep("auto-reply")) return; // replies in the real server: only the live bot does that (B-82 follow-up)
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        try {
            Message message = event.getMessage();
            String content = message.getContentRaw();
            long now = System.currentTimeMillis();
            for (AutoReply reply : repository.forGuild(event.getGuild().getIdLong())) {
                if (!reply.enabled() || !reply.matches(content)) continue;
                Long last = lastAnswered.get(reply.id());
                if (last != null && now - last < reply.cooldownSeconds() * 1000L) return; // matched, but still resting: say nothing, and don't try a later reply
                if (!message.getChannel().canTalk()) return;
                lastAnswered.put(reply.id(), now);
                String text = fill(reply.reply(), event.getAuthor().getAsMention(), event.getGuild().getName());
                message.reply(text.length() > 2000 ? text.substring(0, 2000) : text).mentionRepliedUser(false)
                        .setAllowedMentions(EnumSet.of(Message.MentionType.USER)).mention(event.getAuthor())
                        .queue(sent -> {}, error -> log.debug("Automatic reply {} couldn't be sent", reply.id()));
                return; // one answer per message
            }
        } catch (Exception e) {
            log.error("Automatic replies failed for a message in guild {}", event.getGuild().getId(), e);
        }
    }
}
