package com.runeclans.bot.giveaway;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.i18n.Text;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * The Enter button on a giveaway. Pressing it enters the person; pressing it again takes them out. Only the presser sees the answer.
 * Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class GiveawayListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(GiveawayListener.class);

    private final GiveawayRepository repository;
    private final GiveawayService service;

    public GiveawayListener(GiveawayRepository repository, GiveawayService service) {
        this.repository = repository;
        this.service = service;
    }

    /** A 🎉 on a giveaway that allows it enters the person; someone without the required role has their reaction taken off again. */
    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        if (!event.isFromGuild() || event.getUserIdLong() == event.getJDA().getSelfUser().getIdLong() || !isEntryEmoji(event.getEmoji())) return;
        try {
            Giveaway g = repository.byMessage(event.getMessageIdLong()).orElse(null);
            if (g == null || !g.reactionEntry() || !g.open() || !g.endsAt().isAfter(Instant.now())) return;
            Member member = event.getMember();
            if (member == null || member.getUser().isBot()) return;
            if (g.requiredRoleId() != null && member.getRoles().stream().noneMatch(r -> r.getIdLong() == g.requiredRoleId())) {
                event.getReaction().removeReaction(member.getUser()).queue(ok -> {}, error -> {});
                return;
            }
            repository.addEntry(g.id(), g.guildId(), member.getIdLong());
            service.refresh(event.getGuild(), g);
        } catch (Exception e) {
            log.error("Unhandled exception in giveaway reaction", e);
        }
    }

    @Override
    public void onMessageReactionRemove(MessageReactionRemoveEvent event) {
        if (!event.isFromGuild() || !isEntryEmoji(event.getEmoji())) return;
        try {
            Giveaway g = repository.byMessage(event.getMessageIdLong()).orElse(null);
            if (g == null || !g.reactionEntry() || !g.open()) return;
            repository.removeEntry(g.id(), event.getUserIdLong());
            service.refresh(event.getGuild(), g);
        } catch (Exception e) {
            log.error("Unhandled exception in giveaway reaction", e);
        }
    }

    private static boolean isEntryEmoji(net.dv8tion.jda.api.entities.emoji.EmojiUnion emoji) {
        return emoji.getType() == net.dv8tion.jda.api.entities.emoji.Emoji.Type.UNICODE && Giveaway.ENTRY_EMOJI.equals(emoji.getName());
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("gw:")) return;
        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) return;
            Optional<Giveaway> found = repository.byId(Long.parseLong(id.substring(3)));
            if (found.isEmpty() || found.get().guildId() != guild.getIdLong()) {
                event.reply(Text.of(event, "giveaway.that_giveaway_isn_t_running_any")).setEphemeral(true).queue();
                return;
            }
            Giveaway g = found.get();
            if (!g.open() || !g.endsAt().isAfter(Instant.now())) {
                event.reply(Text.of(event, "giveaway.this_giveaway_has_ended")).setEphemeral(true).queue();
                return;
            }
            if (g.requiredRoleId() != null && member.getRoles().stream().noneMatch(r -> r.getIdLong() == g.requiredRoleId())) {
                event.reply(Text.of(event, "giveaway.this_giveaway_is_only_for_members", g.requiredRoleId())).setEphemeral(true).queue();
                return;
            }
            boolean in = repository.toggleEntry(g.id(), g.guildId(), member.getIdLong());
            event.reply(in ? Text.of(event, "giveaway.you_re_in_press_the_button") : Text.of(event, "giveaway.you_ve_left_the_giveaway")).setEphemeral(true).queue();
            try {
                service.refresh(guild, g); // the entry count on the message; the member already has their answer
            } catch (RuntimeException e) {
                log.warn("Couldn't refresh giveaway {} after an entry", g.id(), e);
            }
        } catch (Exception e) {
            log.error("Unhandled exception in giveaway button '{}'", id, e);
            Containers.replyError(event); // always answer, or Discord shows "This interaction failed" (B-104)
        }
    }
}
