package com.runeclans.bot.rolepanel;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.rolepanel.RolePanel.RoleButton;
import com.runeclans.bot.rolepanel.RolePanelLogic.Change;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The buttons and drop-down of a role panel. Choosing takes or drops roles for the person who chose, and only they see the answer.
 * The panel and role are looked up from the database each time, so a button left on an old message can never hand out a role the
 * panel no longer lists, and a role that has since become unsafe is refused. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class RolePanelListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(RolePanelListener.class);

    private final RolePanelRepository repository;

    public RolePanelListener(RolePanelRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(RolePanelMessage.ID_PREFIX)) return;

        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) return;
            String[] parts = id.substring(RolePanelMessage.ID_PREFIX.length()).split(":");
            if (parts.length != 2) return;
            long panelId = Long.parseLong(parts[0]);
            long roleId = Long.parseLong(parts[1]);

            Optional<RolePanel> found = repository.get(guild.getIdLong(), panelId);
            Optional<RoleButton> button = found.flatMap(p -> p.buttons().stream().filter(b -> b.roleId() == roleId).findFirst());
            if (found.isEmpty() || button.isEmpty()) {
                event.reply(Text.of(event, "rolepanel.that_button_isn_t_part_of")).setEphemeral(true).queue();
                return;
            }
            Role role = guild.getRoleById(roleId);
            if (role == null || !RolePanelService.canGive(guild, role)) {
                event.reply(Text.of(event, "rolepanel.jonnybot_can_t_hand_out_that")).setEphemeral(true).queue();
                return;
            }
            Change change = RolePanelLogic.button(found.get(), button.get(), heldBy(member), role.getName());
            apply(event, guild, member, found.get(), change);
        } catch (Exception e) {
            log.error("Unhandled exception in role panel button '{}'", id, e);
            Containers.replyError(event); // always answer, or Discord shows "This interaction failed" (B-104)
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(RolePanelMessage.ID_PREFIX) || !id.endsWith(RolePanelMessage.MENU_SUFFIX)) return;

        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) return;
            long panelId = Long.parseLong(id.substring(RolePanelMessage.ID_PREFIX.length(), id.length() - RolePanelMessage.MENU_SUFFIX.length()));
            Optional<RolePanel> found = repository.get(guild.getIdLong(), panelId);
            if (found.isEmpty()) {
                event.reply(Text.of(event, "rolepanel.that_menu_isn_t_part_of")).setEphemeral(true).queue();
                return;
            }
            RolePanel panel = found.get();
            List<Long> selected = event.getValues().stream().filter(v -> v.matches("\\d{1,20}")).map(Long::parseLong).toList();
            Change change = RolePanelLogic.menu(panel, selected, heldBy(member), roleId -> {
                Role r = guild.getRoleById(roleId);
                return r == null ? "a role" : r.getName();
            });
            apply(event, guild, member, panel, change);
        } catch (Exception e) {
            log.error("Unhandled exception in role panel menu '{}'", id, e);
            Containers.replyError(event); // always answer, or Discord shows "This interaction failed" (B-104)
        }
    }

    private static Set<Long> heldBy(Member member) {
        Set<Long> held = new HashSet<>();
        member.getRoles().forEach(r -> held.add(r.getIdLong()));
        return held;
    }

    /** Carries the change out (only for roles the bot may hand out) and tells the person what happened. */
    private void apply(IReplyCallback event, Guild guild, Member member, RolePanel panel, Change change) {
        if (change.none()) {
            event.reply(change.message()).setEphemeral(true).queue();
            return;
        }
        List<Role> add = change.add().stream().map(guild::getRoleById).filter(r -> r != null && RolePanelService.canGive(guild, r)).toList();
        List<Role> remove = change.remove().stream().map(guild::getRoleById).filter(r -> r != null && RolePanelService.canGive(guild, r)).toList();
        if (add.isEmpty() && remove.isEmpty()) {
            event.reply(Text.of(event, "rolepanel.jonnybot_can_t_hand_out_that")).setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(hook -> guild.modifyMemberRoles(member, add, remove).reason("Role panel: " + panel.name()).queue(
                done -> hook.editOriginal(change.message()).queue(),
                error -> {
                    log.warn("Role panel {}: couldn't change the roles of {}", panel.id(), member.getId(), error);
                    hook.editOriginal(Text.of(event, "rolepanel.something_went_wrong_changing_your_roles")).queue();
                }));
    }
}
