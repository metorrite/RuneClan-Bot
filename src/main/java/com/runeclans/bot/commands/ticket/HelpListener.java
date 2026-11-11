package com.runeclans.bot.commands.ticket;

import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.ticket.TicketModels.HelpSettings;
import com.runeclans.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The two buttons on the helper guidelines message: agreeing hands out the PVM Helper role, and stepping down takes it back. Only the
 * PVM Helper role is touched here; PVM Helper+ is given and removed by hand.
 */
@BService
public class HelpListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(HelpListener.class);

    private final TicketRepository repository;

    public HelpListener(TicketRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.equals(HelpOnboarding.ACCEPT_ID) && !id.equals(HelpOnboarding.LEAVE_ID) && !id.equals(HelpOnboarding.PINGS_ID)) return;

        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) return;

            HelpSettings settings = repository.getHelpSettings(guild.getIdLong());
            Role role = settings.helperRoleId() == null ? null : guild.getRoleById(settings.helperRoleId());
            if (role == null && !id.equals(HelpOnboarding.PINGS_ID)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.the_pvm_helper_role_isn_t"));
                return;
            }

            boolean has = role != null && member.getRoles().contains(role);
            if (id.equals(HelpOnboarding.ACCEPT_ID)) accept(event, guild, member, role, has);
            else if (id.equals(HelpOnboarding.PINGS_ID)) showPingSettings(event, guild, member, settings);
            else leave(event, guild, member, role, has);
        } catch (Exception e) {
            log.error("Unhandled exception in helper guidelines button '{}'", id, e);
            Containers.replyError(event);
        }
    }

    /** Opens the clicker's own ping settings (only they see it), or tells them to sign up first. */
    private void showPingSettings(ButtonInteractionEvent event, Guild guild, Member member, HelpSettings settings) {
        HelpPingService.HelperLevel level = HelpPingService.levelOf(settings, member);
        if (level == HelpPingService.HelperLevel.NONE) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.sign_up_first_press_i_agree"));
            return;
        }
        event.replyComponents(HelpOnboarding.pingSettings(settings, level, repository.getPingTiers(guild.getIdLong(), member.getIdLong())))
                .useComponentsV2(true).setEphemeral(true).queue();
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().equals(HelpOnboarding.PING_SELECT_ID)) return;
        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) return;

            HelpSettings settings = repository.getHelpSettings(guild.getIdLong());
            HelpPingService.HelperLevel level = HelpPingService.levelOf(settings, member);
            if (level == HelpPingService.HelperLevel.NONE) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.you_re_not_a_pvm_helper"));
                return;
            }
            // Only the tiers this helper's role allows are kept, whatever the form claims to have sent.
            Set<String> allowed = new HashSet<>(HelpRules.allowedTiers(settings, level == HelpPingService.HelperLevel.HELPER_PLUS));
            Set<String> chosen = new HashSet<>(event.getValues());
            chosen.retainAll(allowed);
            repository.setPingTiers(guild.getIdLong(), member.getIdLong(), chosen);
            event.editComponents(HelpOnboarding.pingSettings(settings, level, chosen)).useComponentsV2(true).queue();
        } catch (Exception e) {
            log.error("Unhandled exception in the helper ping settings", e);
            Containers.replyError(event);
        }
    }

    private void accept(ButtonInteractionEvent event, Guild guild, Member member, Role role, boolean has) {
        if (has) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "ticket.you_re_already_a_pvm_helper"));
            return;
        }
        if (!guild.getSelfMember().canInteract(role)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.i_can_t_hand_out_that"));
            return;
        }
        if (!MemberApi.safeToAssign(guild, role)) {
            Containers.replyEphemeral(event, Containers.WARNING, MemberApi.handOutRefusal(guild, role));
            return;
        }
        event.deferReply(true).queue();
        guild.addRoleToMember(member, role).reason("Accepted the PVM Helper guidelines").queue(
                done -> event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                        Text.of(event, "ticket.you_re_now_a_pvm_helper")))).useComponentsV2(true).queue(),
                error -> {
                    log.warn("Couldn't give {} the PVM Helper role", member.getId(), error);
                    event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER, Text.of(event, "ticket.i_couldn_t_give_you_the")))).useComponentsV2(true).queue();
                });
    }

    private void leave(ButtonInteractionEvent event, Guild guild, Member member, Role role, boolean has) {
        if (!has) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "ticket.you_don_t_have_the_pvm"));
            return;
        }
        if (!guild.getSelfMember().canInteract(role)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.i_can_t_change_that_role"));
            return;
        }
        event.deferReply(true).queue();
        repository.setPingTiers(guild.getIdLong(), member.getIdLong(), Set.of()); // no pings for someone who isn't a helper
        guild.removeRoleFromMember(member, role).reason("Stepped down as a PVM Helper").queue(
                done -> event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS, Text.of(event, "ticket.you_re_no_longer_a_pvm")))).useComponentsV2(true).queue(),
                error -> {
                    log.warn("Couldn't take the PVM Helper role from {}", member.getId(), error);
                    event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER, Text.of(event, "ticket.i_couldn_t_remove_the_role")))).useComponentsV2(true).queue();
                });
    }
}
