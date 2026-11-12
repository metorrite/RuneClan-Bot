package com.runeclans.bot.starboard;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.starboard.StarboardLogic.Action;
import com.runeclans.bot.starboard.StarboardLogic.Reactor;
import com.runeclans.bot.starboard.StarboardRepository.Post;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.attribute.IAgeRestrictedChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.EmojiUnion;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Keeps the starboard in step with reactions: when a message gains enough of the server's chosen reaction it is copied to the
 * starboard channel, the count on that copy follows later reactions, and the copy is taken down if the message falls below the
 * threshold again. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class StarboardListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(StarboardListener.class);
    private static final Color GOLD = new Color(0xFFAC33);

    private final StarboardRepository repository;

    public StarboardListener(StarboardRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        if (AutoPostRouter.skipSweep("starboard")) return; // posts and edits in the real server: only the live bot does that (B-82 follow-up)
        if (event.isFromGuild()) handle(event.getGuild(), event.getGuildChannel(), event.getMessageIdLong(), event.getEmoji(), event);
    }

    @Override
    public void onMessageReactionRemove(MessageReactionRemoveEvent event) {
        if (AutoPostRouter.skipSweep("starboard")) return;
        if (event.isFromGuild()) handle(event.getGuild(), event.getGuildChannel(), event.getMessageIdLong(), event.getEmoji(), event);
    }

    private void handle(Guild guild, GuildMessageChannel channel, long messageId, EmojiUnion emoji, net.dv8tion.jda.api.events.message.react.GenericMessageReactionEvent event) {
        try {
            StarboardConfig config = repository.get(guild.getIdLong());
            if (!config.enabled() || config.channelId() == null || channel.getIdLong() == config.channelId()) return;
            Emoji wanted = Emoji.fromFormatted(config.emoji());
            if (!wanted.getAsReactionCode().equals(emoji.getAsReactionCode())) return;
            if (channel instanceof IAgeRestrictedChannel restricted && restricted.isNSFW()) return; // never copy out of an age-restricted channel
            Long parentId = channel instanceof net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel thread ? thread.getParentChannel().getIdLong() : null;
            GuildChannel visibilityOf = channel instanceof net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel thread ? thread.getParentChannel() : channel;
            boolean visible = !config.skipHiddenChannels() || guild.getPublicRole().hasPermission(visibilityOf, Permission.VIEW_CHANNEL);
            if (!StarboardLogic.sourceAllowed(config, channel.getIdLong(), parentId, visible)) return;
            int threshold = StarboardLogic.thresholdFor(config, channel.getIdLong(), parentId);

            GuildMessageChannel board = guild.getChannelById(GuildMessageChannel.class, config.channelId());
            if (board == null || !board.canTalk()) return;

            event.retrieveMessage().queue(message -> {
                var reaction = message.getReaction(wanted);
                if (reaction == null) {
                    apply(guild, config, threshold, board, channel, message, 0);
                    return;
                }
                reaction.retrieveUsers().limit(100).queue(users -> {
                    List<Reactor> reactors = new ArrayList<>();
                    users.forEach(u -> reactors.add(new Reactor(u.getIdLong(), u.isBot())));
                    apply(guild, config, threshold, board, channel, message, StarboardLogic.count(reactors, message.getAuthor().getIdLong(), config.selfStar()));
                }, error -> log.debug("Starboard: couldn't read the reactors of {}", messageId));
            }, error -> log.debug("Starboard: message {} is gone", messageId));
        } catch (Exception e) {
            log.error("Starboard handling failed in guild {}", guild.getId(), e);
        }
    }

    private void apply(Guild guild, StarboardConfig config, int threshold, GuildMessageChannel board, GuildMessageChannel source, Message message, int stars) {
        var existing = repository.post(guild.getIdLong(), message.getIdLong());
        Action action = StarboardLogic.decide(stars, threshold, existing.isPresent());
        String headline = StarboardLogic.headline(config.emoji(), stars, source.getIdLong());

        switch (action) {
            case NOTHING -> {
            }
            case POST -> board.sendMessage(headline).setEmbeds(embed(message)).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                    .queue(sent -> repository.savePost(new Post(guild.getIdLong(), source.getIdLong(), message.getIdLong(), sent.getIdLong(), stars)),
                            error -> log.warn("Starboard: couldn't post message {}", message.getId(), error));
            case UPDATE -> {
                Post post = existing.orElseThrow();
                board.editMessageById(post.boardMessageId(), headline).queue(
                        edited -> repository.savePost(new Post(post.guildId(), post.sourceChannelId(), post.sourceMessageId(), post.boardMessageId(), stars)),
                        error -> repository.deletePost(guild.getIdLong(), message.getIdLong())); // someone deleted the copy: forget it, a later star posts it again
            }
            case REMOVE -> {
                Post post = existing.orElseThrow();
                board.deleteMessageById(post.boardMessageId()).queue(ok -> {}, error -> {});
                repository.deletePost(guild.getIdLong(), message.getIdLong());
            }
        }
    }

    private static MessageEmbed embed(Message message) {
        EmbedBuilder embed = new EmbedBuilder().setColor(GOLD)
                .setAuthor(message.getAuthor().getEffectiveName(), message.getJumpUrl(), message.getAuthor().getEffectiveAvatarUrl())
                .setTimestamp(message.getTimeCreated().toInstant() == null ? Instant.now() : message.getTimeCreated().toInstant());
        String text = message.getContentDisplay();
        if (!text.isBlank()) embed.setDescription(text.length() > 1800 ? text.substring(0, 1800) + "…" : text);
        message.getAttachments().stream().filter(Message.Attachment::isImage).findFirst().ifPresent(a -> embed.setImage(a.getUrl()));
        embed.addField("Source", "[Jump to the message](" + message.getJumpUrl() + ")", false);
        return embed.build();
    }
}
