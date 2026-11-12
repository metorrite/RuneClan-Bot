package com.runeclans.bot.tempvoice;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.tempvoice.TempVoiceRules.Action;
import com.runeclans.bot.tempvoice.TempVoiceRules.Temp;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Temporary voice channels, as members meet them: joining a hub, leaving a channel, and the panel's buttons, menus and boxes. Every control checks the
 * channel's owner in the database each time, so a panel left in a channel can't be used by anyone else, and a channel that is no longer temporary is
 * ignored. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class TempVoiceListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(TempVoiceListener.class);

    private final TempVoiceRepository repository;
    private final TempVoiceService service;

    public TempVoiceListener(TempVoiceRepository repository, TempVoiceService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (AutoPostRouter.skipSweep("temporary voice")) return; // creates and deletes channels in the real server: only the live bot does that (B-82 follow-up)
        try {
            if (event.getChannelLeft() != null) service.onLeave(event.getGuild(), event.getChannelLeft());
            if (event.getChannelJoined() != null) service.onJoin(event.getMember(), event.getChannelJoined());
        } catch (Exception e) {
            log.error("Temporary voice failed for a voice update in guild {}", event.getGuild().getId(), e);
        }
    }

    // ---------- buttons ----------

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String[] id = TempVoiceRules.parseId(event.getComponentId());
        if (id == null) return;
        try {
            Context ctx = context(event.getGuild(), event.getMember(), id[2]);
            if (ctx == null) {
                event.reply(Text.of(event, "tempvoice.that_voice_channel_isn_t_a")).setEphemeral(true).queue();
                return;
            }
            Action action = TempVoiceRules.action(id[1]);
            if (action == null) return;
            String refused = TempVoiceRules.refusal(action, ctx.temp.ownerId(), ctx.member.getIdLong(), TempVoiceService.inChannel(ctx.channel, ctx.temp.ownerId()), TempVoiceService.inChannel(ctx.channel, ctx.member.getIdLong()));
            if (refused != null) {
                event.reply(refused).setEphemeral(true).queue();
                return;
            }
            switch (action) {
                case RENAME -> event.replyModal(Modal.create("tv:renamemodal:" + ctx.channel.getId(), "Rename your channel")
                        .addComponents(Label.of("New name", TextInput.create("tv_name", TextInputStyle.SHORT).setRequired(true).setMaxLength(TempVoiceRules.MAX_NAME).setValue(ctx.channel.getName()).build())).build()).queue();
                case LIMIT -> event.replyModal(Modal.create("tv:limitmodal:" + ctx.channel.getId(), "Member limit")
                        .addComponents(Label.of("Most people allowed (0 for no limit)", TextInput.create("tv_limit", TextInputStyle.SHORT).setRequired(true).setMaxLength(2).setValue(Integer.toString(ctx.channel.getUserLimit())).build())).build()).queue();
                case LOCK -> {
                    service.lock(ctx.channel, true);
                    event.reply(Text.of(event, "tempvoice.locked_only_people_you_permit_can")).setEphemeral(true).queue();
                }
                case UNLOCK -> {
                    service.lock(ctx.channel, false);
                    event.reply(Text.of(event, "tempvoice.unlocked_anyone_who_can_see_the")).setEphemeral(true).queue();
                }
                case PERMIT -> pickUser(event, "tv:permitsel:" + ctx.channel.getId(), "Choose who may join (even while it is locked)");
                case KICK -> pickUser(event, "tv:kicksel:" + ctx.channel.getId(), "Choose who to remove (they can't rejoin until permitted)");
                case TRANSFER -> pickUser(event, "tv:transfersel:" + ctx.channel.getId(), "Choose who becomes the owner");
                case CLAIM -> {
                    service.transfer(ctx.channel, ctx.temp.ownerId(), ctx.member);
                    event.reply(Text.of(event, "tempvoice.you_are_the_owner_of_this")).setEphemeral(true).queue();
                }
            }
        } catch (Exception e) {
            log.error("Temporary voice button '{}' failed", event.getComponentId(), e);
            Containers.replyError(event); // always answer, or Discord shows "This interaction failed" (B-104)
        }
    }

    private static void pickUser(ButtonInteractionEvent event, String id, String placeholder) {
        event.reply(Text.of(event, "tempvoice.pick_a_member")).setEphemeral(true)
                .addComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(EntitySelectMenu.create(id, EntitySelectMenu.SelectTarget.USER).setPlaceholder(placeholder).setRequiredRange(1, 1).build())).queue();
    }

    // ---------- the user menus ----------

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        String[] id = TempVoiceRules.parseId(event.getComponentId());
        if (id == null) return;
        try {
            Context ctx = context(event.getGuild(), event.getMember(), id[2]);
            if (ctx == null) {
                event.reply(Text.of(event, "tempvoice.that_voice_channel_isn_t_a")).setEphemeral(true).queue();
                return;
            }
            Action action = switch (id[1]) {
                case "permitsel" -> Action.PERMIT;
                case "kicksel" -> Action.KICK;
                case "transfersel" -> Action.TRANSFER;
                default -> null;
            };
            if (action == null) return;
            String refused = TempVoiceRules.refusal(action, ctx.temp.ownerId(), ctx.member.getIdLong(), true, true);
            if (refused != null) {
                event.reply(refused).setEphemeral(true).queue();
                return;
            }
            Member target = event.getMentions().getMembers().stream().findFirst().orElse(null);
            if (target == null || !TempVoiceRules.validTarget(ctx.temp.ownerId(), target.getIdLong(), target.getUser().isBot())) {
                event.reply(Text.of(event, "tempvoice.choose_another_member_not_yourself_and")).setEphemeral(true).queue();
                return;
            }
            switch (action) {
                case PERMIT -> {
                    service.permit(ctx.channel, target);
                    event.reply(Text.of(event, "tempvoice.can_join_now", target.getAsMention())).setAllowedMentions(java.util.List.of()).setEphemeral(true).queue();
                }
                case KICK -> {
                    service.kick(ctx.channel, target);
                    event.reply(Text.of(event, "tempvoice.was_removed_and_can_t_rejoin", target.getAsMention())).setAllowedMentions(java.util.List.of()).setEphemeral(true).queue();
                }
                case TRANSFER -> {
                    if (!TempVoiceService.inChannel(ctx.channel, target.getIdLong())) {
                        event.reply(Text.of(event, "tempvoice.they_need_to_be_in_the")).setEphemeral(true).queue();
                        return;
                    }
                    service.transfer(ctx.channel, ctx.temp.ownerId(), target);
                    event.reply(Text.of(event, "tempvoice.owns_the_channel_now", target.getAsMention())).setAllowedMentions(java.util.List.of()).setEphemeral(true).queue();
                }
                default -> { }
            }
        } catch (Exception e) {
            log.error("Temporary voice menu '{}' failed", event.getComponentId(), e);
            Containers.replyError(event); // always answer, or Discord shows "This interaction failed" (B-104)
        }
    }

    // ---------- the boxes ----------

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String[] id = TempVoiceRules.parseId(event.getModalId());
        if (id == null) return;
        try {
            Context ctx = context(event.getGuild(), event.getMember(), id[2]);
            if (ctx == null) {
                event.reply(Text.of(event, "tempvoice.that_voice_channel_isn_t_a")).setEphemeral(true).queue();
                return;
            }
            String refused = TempVoiceRules.refusal(Action.RENAME, ctx.temp.ownerId(), ctx.member.getIdLong(), true, true);
            if (refused != null) {
                event.reply(refused).setEphemeral(true).queue();
                return;
            }
            switch (id[1]) {
                case "renamemodal" -> {
                    String name = service.rename(ctx.channel, event.getValue("tv_name").getAsString());
                    event.reply(Text.of(event, "tempvoice.renamed_to_discord_only_allows_a", name)).setEphemeral(true).queue();
                }
                case "limitmodal" -> {
                    int limit = TempVoiceRules.parseLimit(event.getValue("tv_limit").getAsString());
                    if (limit < 0) {
                        event.reply(Text.of(event, "tempvoice.type_a_number_from_0_to", TempVoiceRules.MAX_USER_LIMIT)).setEphemeral(true).queue();
                        return;
                    }
                    service.limit(ctx.channel, limit);
                    event.reply(limit == 0 ? Text.of(event, "tempvoice.no_limit") : Text.of(event, "tempvoice.limited_to_people", limit)).setEphemeral(true).queue();
                }
                default -> { }
            }
        } catch (Exception e) {
            log.error("Temporary voice box '{}' failed", event.getModalId(), e);
            Containers.replyError(event); // always answer, or Discord shows "This interaction failed" (B-104)
        }
    }

    /** The member, voice channel and its record for a control, or null when the control doesn't point at a live temporary channel of this server. */
    private Context context(Guild guild, Member member, String channelId) {
        if (guild == null || member == null) return null;
        Optional<Temp> temp = repository.channel(Long.parseLong(channelId));
        if (temp.isEmpty() || temp.get().guildId() != guild.getIdLong()) return null;
        VoiceChannel channel = guild.getVoiceChannelById(temp.get().channelId());
        return channel == null ? null : new Context(member, channel, temp.get());
    }

    private record Context(Member member, VoiceChannel channel, Temp temp) {}
}
