package com.runeclans.bot.highlight;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.config.PrivilegedIntents;
import com.runeclans.bot.highlight.HighlightRepository.Keyword;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends a member a direct message when one of their words is said in a server channel they can see. Does nothing unless the server switched
 * Highlights on. Never tells a member about a channel they cannot see, never about their own messages, and at most one message per chat
 * message however many of their words it holds. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class HighlightListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(HighlightListener.class);
    private static final int EXCERPT = 220;

    private final HighlightRepository repository;
    private final Map<Long, Long> lastSent = new ConcurrentHashMap<>();
    private final AtomicBoolean warnedNoContent = new AtomicBoolean();

    public HighlightListener(HighlightRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (AutoPostRouter.skipSweep("highlight")) return; // DMs members in the real server: only the live bot does that (B-82 follow-up)
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        try {
            Guild guild = event.getGuild();
            HighlightConfig config = repository.config(guild.getIdLong());
            if (!config.enabled()) return;
            if (!PrivilegedIntents.messageContent()) {
                if (warnedNoContent.compareAndSet(false, true)) log.warn("Highlights is switched on in a server but the Message Content intent is off, so it cannot read messages.");
                return;
            }
            GuildChannel channel = event.getGuildChannel();
            if (config.excludedChannels().contains(HighlightService.rootChannelId(channel))) return;
            String content = event.getMessage().getContentDisplay();
            if (content.isBlank()) return;

            long now = System.currentTimeMillis();
            Map<Long, List<String>> hits = matchesByMember(repository.keywords(guild.getIdLong()), content, event.getAuthor().getIdLong(), config, now);
            for (Map.Entry<Long, List<String>> hit : hits.entrySet()) notify(event, config, hit.getKey(), hit.getValue());
        } catch (Exception e) {
            log.error("Highlights failed for a message in guild {}", event.getGuild().getId(), e);
        }
    }

    /** The words that fired, per member: not the author, and not a word still resting after sending that member a message. Stamps the ones it returns. */
    Map<Long, List<String>> matchesByMember(List<Keyword> keywords, String content, long authorId, HighlightConfig config, long now) {
        Map<Long, List<String>> hits = new LinkedHashMap<>();
        for (Keyword k : keywords) {
            if (k.userId() == authorId || !HighlightMatcher.matches(content, k.word(), k.wholeWord())) continue;
            Long last = lastSent.get(k.id());
            if (last != null && now - last < config.cooldownSeconds() * 1000L) continue;
            lastSent.put(k.id(), now);
            hits.computeIfAbsent(k.userId(), u -> new ArrayList<>()).add(k.word());
        }
        return hits;
    }

    private void notify(MessageReceivedEvent event, HighlightConfig config, long userId, List<String> words) {
        Guild guild = event.getGuild();
        Message message = event.getMessage();
        GuildChannel channel = event.getGuildChannel();
        if (repository.ignoredBy(guild.getIdLong(), userId).contains(HighlightService.rootChannelId(channel))) return;
        guild.retrieveMemberById(userId).queue(member -> {
            if (member.getUser().isBot()) return;
            if (!config.allowedRoles().isEmpty() && member.getRoles().stream().noneMatch(r -> config.allowedRoles().contains(r.getIdLong()))) return;
            // the privacy rule: only a member who can read the channel is ever told what was said in it
            if (!HighlightService.canSee(member, channel)) return;
            String text = "🔔 **" + String.join("**, **", words) + "** in " + channel.getAsMention() + " (" + guild.getName() + ")\n> "
                    + excerpt(message.getAuthor().getEffectiveName(), message.getContentDisplay()) + "\n" + message.getJumpUrl()
                    + "\n-# Change your words with `/highlight` or on your website profile.";
            member.getUser().openPrivateChannel()
                    .flatMap(dm -> dm.sendMessage(text).setSuppressEmbeds(true).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)))
                    .queue(ok -> {}, error -> log.debug("Highlights couldn't message {}", userId));
        }, error -> {});
    }

    static String excerpt(String author, String content) {
        String plain = MarkdownSanitizer.escape(content.strip().replaceAll("\\s+", " "));
        if (plain.length() > EXCERPT) plain = plain.substring(0, EXCERPT) + "…";
        return "**" + MarkdownSanitizer.escape(author) + ":** " + plain;
    }
}
