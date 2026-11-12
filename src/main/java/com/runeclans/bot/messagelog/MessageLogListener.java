package com.runeclans.bot.messagelog;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.config.PrivilegedIntents;
import com.runeclans.bot.highlight.HighlightService;
import com.runeclans.bot.messagelog.MessageLogRepository.Cached;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the text of messages briefly and writes edits, deletes, bulk deletes and voice join/leave/move to the channels a server chose. Does nothing unless
 * the server switched it on. Message text is stored only for servers that log edits or deletes, never from direct messages, bots, webhooks or excluded
 * channels, and only for the retention the server set (a purge removes older text). Without the Message Content intent there is no text to keep, so the
 * message logs stay empty and say so once in the log. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class MessageLogListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(MessageLogListener.class);

    private final MessageLogRepository repository;
    // Writing a row per chat message must not slow the gateway thread: a single worker, and a full queue drops (and says so) rather than grows.
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(5000), (ThreadFactory) r -> {
        Thread t = new Thread(r, "message-log-writer");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());
    private final AtomicBoolean warnedNoContent = new AtomicBoolean();

    public MessageLogListener(MessageLogRepository repository) {
        this.repository = repository;
    }

    static String describeAttachments(List<Message.Attachment> attachments) {
        StringBuilder out = new StringBuilder();
        for (Message.Attachment a : attachments) out.append(a.getFileName()).append(": ").append(a.getUrl()).append('\n');
        return out.toString().stripTrailing();
    }

    private boolean wanted(Guild guild, MessageLogConfig config) {
        if (!config.cachesMessages()) return false;
        if (!PrivilegedIntents.messageContent()) {
            if (warnedNoContent.compareAndSet(false, true)) log.warn("Message logs are switched on in a server but the Message Content intent is off, so there is no message text to log.");
            return false;
        }
        return true;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (AutoPostRouter.skipSweep("message log")) return; // posts log entries in the real server: only the live bot does that (B-82 follow-up)
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        try {
            MessageLogConfig config = repository.config(event.getGuild().getIdLong());
            if (!wanted(event.getGuild(), config)) return;
            if (config.excludedChannels().contains(HighlightService.rootChannelId(event.getGuildChannel()))) return;
            Message m = event.getMessage();
            Cached cached = new Cached(m.getIdLong(), event.getGuild().getIdLong(), event.getChannel().getIdLong(), m.getAuthor().getIdLong(), m.getContentRaw(), describeAttachments(m.getAttachments()));
            writer.execute(() -> repository.put(cached));
        } catch (Exception e) {
            log.error("The message log failed to remember a message in guild {}", event.getGuild().getId(), e);
        }
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        if (AutoPostRouter.skipSweep("message log")) return;
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        try {
            Guild guild = event.getGuild();
            MessageLogConfig config = repository.config(guild.getIdLong());
            if (!wanted(guild, config) || config.excludedChannels().contains(HighlightService.rootChannelId(event.getGuildChannel()))) return;
            Message m = event.getMessage();
            String after = m.getContentRaw();
            String attachments = describeAttachments(m.getAttachments());
            writer.execute(() -> {
                try {
                    var before = repository.get(m.getIdLong());
                    repository.put(new Cached(m.getIdLong(), guild.getIdLong(), event.getChannel().getIdLong(), m.getAuthor().getIdLong(), after, attachments));
                    // an update with the same text (a link preview arriving) is not an edit; an unknown "before" cannot be shown
                    if (before.isEmpty() || before.get().content().equals(MessageLogRepository.clip(after)) || config.editChannelId() == null) return;
                    send(guild, config.editChannelId(), MessageLogMessage.edited(m.getAuthor().getIdLong(), event.getChannel().getIdLong(), m.getIdLong(), guild.getId(), before.get().content(), after, attachments));
                } catch (Exception e) {
                    log.error("The message log failed to record an edit in guild {}", guild.getId(), e);
                }
            });
        } catch (Exception e) {
            log.error("The message log failed on an edit in guild {}", event.getGuild().getId(), e);
        }
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        if (AutoPostRouter.skipSweep("message log")) return;
        if (!event.isFromGuild()) return;
        try {
            Guild guild = event.getGuild();
            MessageLogConfig config = repository.config(guild.getIdLong());
            if (!config.cachesMessages()) return;
            long id = event.getMessageIdLong();
            writer.execute(() -> {
                var cached = repository.get(id);
                repository.remove(List.of(id));
                // a message that was never remembered (a bot's, an excluded channel's, or older than the retention) is not logged: there is nothing to show
                if (cached.isPresent() && config.deleteChannelId() != null) send(guild, config.deleteChannelId(), MessageLogMessage.deleted(cached.get()));
            });
        } catch (Exception e) {
            log.error("The message log failed on a delete in guild {}", event.getGuild().getId(), e);
        }
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        if (AutoPostRouter.skipSweep("message log")) return;
        try {
            Guild guild = event.getGuild();
            MessageLogConfig config = repository.config(guild.getIdLong());
            if (!config.cachesMessages()) return;
            List<Long> ids = event.getMessageIds().stream().map(Long::parseLong).toList();
            long channel = event.getChannel().getIdLong();
            writer.execute(() -> {
                List<Cached> known = repository.take(ids);
                if (config.deleteChannelId() != null && !config.excludedChannels().contains(channel)) send(guild, config.deleteChannelId(), MessageLogMessage.bulkDeleted(channel, ids.size(), known));
            });
        } catch (Exception e) {
            log.error("The message log failed on a bulk delete in guild {}", event.getGuild().getId(), e);
        }
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (AutoPostRouter.skipSweep("message log")) return;
        try {
            Guild guild = event.getGuild();
            MessageLogConfig config = repository.config(guild.getIdLong());
            if (!config.enabled() || config.voiceChannelId() == null || event.getMember().getUser().isBot()) return;
            Long left = event.getChannelLeft() == null ? null : event.getChannelLeft().getIdLong();
            Long joined = event.getChannelJoined() == null ? null : event.getChannelJoined().getIdLong();
            if (left == null && joined == null) return;
            send(guild, config.voiceChannelId(), MessageLogMessage.voice(event.getMember().getIdLong(), left, joined));
        } catch (Exception e) {
            log.error("The voice log failed in guild {}", event.getGuild().getId(), e);
        }
    }

    private static void send(Guild guild, long channelId, MessageEmbed embed) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null || !channel.canTalk()) return;
        channel.sendMessageEmbeds(embed).setAllowedMentions(List.of()).queue(ok -> {}, error -> log.warn("Couldn't write to the log channel {}: {}", channelId, error.getMessage()));
    }
}
