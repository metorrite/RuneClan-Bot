package com.runeclans.bot.rolepanel;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.rolepanel.RolePanel.RoleButton;
import com.runeclans.bot.rolepanel.RolePanelLogic.ReactionChange;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.EmojiUnion;
import net.dv8tion.jda.api.events.message.react.GenericMessageReactionEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The emoji reactions of a role panel. Reacting with a panel's emoji on its message gives the role, and taking the reaction back drops it,
 * unless the panel is add-only or remove-only (see {@link RolePanelLogic#reaction}). Only a message that is a reactions panel is looked at,
 * so every other reaction in the server costs one indexed lookup and nothing else. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class RolePanelReactionListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(RolePanelReactionListener.class);

    private final RolePanelRepository repository;

    public RolePanelReactionListener(RolePanelRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        if (AutoPostRouter.skipSweep("role panel reactions")) return; // gives and takes roles in the real server: only the live bot does that (B-82 follow-up)
        handle(event, true);
    }

    @Override
    public void onMessageReactionRemove(MessageReactionRemoveEvent event) {
        if (AutoPostRouter.skipSweep("role panel reactions")) return;
        handle(event, false);
    }

    /** The button of the panel whose emoji this is, if any. */
    static Optional<RoleButton> buttonFor(RolePanel panel, EmojiUnion emoji) {
        return panel.buttons().stream().filter(b -> sameEmoji(b.emoji(), emoji.getAsReactionCode())).findFirst();
    }

    static boolean sameEmoji(String stored, String reactionCode) {
        Emoji parsed = RolePanelMessage.emoji(stored);
        return parsed != null && parsed.getAsReactionCode().equals(reactionCode);
    }

    private void handle(GenericMessageReactionEvent event, boolean added) {
        if (!event.isFromGuild() || event.getUserIdLong() == event.getJDA().getSelfUser().getIdLong()) return;
        try {
            Guild guild = event.getGuild();
            if (!repository.mayHaveReactionPanel(guild.getIdLong(), event.getMessageIdLong())) return; // no reactions panel here: byMessage would find none
            Optional<RolePanel> found = repository.byMessage(guild.getIdLong(), event.getMessageIdLong());
            if (found.isEmpty() || found.get().style() != RolePanel.Style.REACTIONS) return;
            RolePanel panel = found.get();
            Optional<RoleButton> button = buttonFor(panel, event.getEmoji());
            if (button.isEmpty()) return;
            Role role = guild.getRoleById(button.get().roleId());
            if (role == null || !RolePanelService.canGive(guild, role)) return;

            event.retrieveUser().queue(user -> {
                if (user.isBot()) return;
                guild.retrieveMemberById(user.getIdLong()).queue(member -> {
                    Set<Long> held = new HashSet<>();
                    member.getRoles().forEach(r -> held.add(r.getIdLong()));
                    ReactionChange result = RolePanelLogic.reaction(panel, role.getIdLong(), added, held);
                    List<Role> add = result.change().add().stream().map(guild::getRoleById).filter(r -> r != null && RolePanelService.canGive(guild, r)).toList();
                    List<Role> remove = result.change().remove().stream().map(guild::getRoleById).filter(r -> r != null && RolePanelService.canGive(guild, r)).toList();
                    if (!add.isEmpty() || !remove.isEmpty()) {
                        guild.modifyMemberRoles(member, add, remove).reason("Role panel: " + panel.name())
                                .queue(done -> {}, error -> log.warn("Role panel {}: couldn't change the roles of {}", panel.id(), member.getId(), error));
                    }
                    if (added) takeBackReactions(event, user, panel, result);
                }, error -> log.debug("Role panel {}: member {} is gone", panel.id(), user.getId()));
            }, error -> log.debug("Role panel {}: reacting user is gone", panel.id()));
        } catch (Exception e) {
            log.error("Role panel reaction handling failed", e);
        }
    }

    /** Takes the person's own reactions back when the panel asks (remove-only, or unique mode moving to another emoji). Needs Manage Messages; without it nothing happens. */
    private void takeBackReactions(GenericMessageReactionEvent event, User user, RolePanel panel, ReactionChange result) {
        GuildMessageChannel channel = event.getGuildChannel();
        if (!event.getGuild().getSelfMember().hasPermission(channel, net.dv8tion.jda.api.Permission.MESSAGE_MANAGE)) return;
        if (result.dropThisReaction()) channel.removeReactionById(event.getMessageIdLong(), event.getEmoji(), user).queue(ok -> {}, error -> {});
        for (long roleId : result.dropOtherReactionsOfRoles()) {
            panel.buttons().stream().filter(b -> b.roleId() == roleId).findFirst().map(b -> RolePanelMessage.emoji(b.emoji())).ifPresent(emoji ->
                    channel.removeReactionById(event.getMessageIdLong(), emoji, user).queue(ok -> {}, error -> {}));
        }
    }
}
