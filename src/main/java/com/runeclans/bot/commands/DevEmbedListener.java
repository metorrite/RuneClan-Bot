package com.runeclans.bot.commands;

import com.runeclans.bot.announcement.PostMarkup;
import com.runeclans.bot.announcement.PostTextConverter;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.devtools.DevCommandFilter;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.checkbox.Checkbox;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;

/** The other half of {@link DevEmbedCommand}: takes what was pasted and posts it as an embed in the channel the command was used in. */
@BService
public class DevEmbedListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DevEmbedListener.class);
    static final String MODAL_ID = "devembed_modal:_";

    private final BotConfig botConfig;
    private final AdminRoleFilter adminRoleFilter;
    private final DevCommandFilter devAccess;

    public DevEmbedListener(BotConfig botConfig, AdminRoleFilter adminRoleFilter, DevCommandFilter devAccess) {
        this.botConfig = botConfig;
        this.adminRoleFilter = adminRoleFilter;
        this.devAccess = devAccess;
    }

    static Modal buildModal() {
        TextInput text = TextInput.create("devembed_text", TextInputStyle.PARAGRAPH)
                .setPlaceholder("Paste the text here (up to 4000 characters)")
                .setRequiredRange(1, 4000)
                .build();

        return Modal.create(MODAL_ID, "Post as an Embed")
                .addComponents(
                        Label.of("Text", text),
                        Label.of("Tidy the formatting", "Bold-only lines become headings and • bullets become list items",
                                Checkbox.of("devembed_convert", true)))
                .build();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().equals(MODAL_ID)) return;

        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null || botConfig.getLiveEnvironment()) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "commands.this_command_is_dev_only"));
                return;
            }
            if (!devAccess.isAllowed(event.getJDA(), member.getIdLong())) {
                Containers.replyEphemeral(event, Containers.WARNING, DevCommandFilter.refusal());
                return;
            }
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "commands.you_need_the_admin_role_or"));
                return;
            }

            String raw = event.getValue("devembed_text").getAsString();
            String text = event.getValue("devembed_convert").getAsBoolean() ? PostTextConverter.convert(raw) : raw.strip();

            PostMarkup.Parsed parsed = PostMarkup.parse(text, true);
            if (parsed.hasErrors()) {
                Containers.replyEphemeral(event, Containers.DANGER, Text.of(event, "commands.nothing_was_posted_fix_this_first"), parsed.problemsText());
                return;
            }
            if (!(event.getGuildChannel() instanceof GuildMessageChannel channel) || !channel.canTalk()) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "commands.i_can_t_post_in_this"));
                return;
            }

            event.deferReply(true).queue();
            channel.sendMessageComponents(List.of(parsed.toContainer(Containers.PRIMARY)))
                    .useComponentsV2(true)
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                    .queue(sent -> event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                                    "Posted." + (parsed.problems().isEmpty() ? "" : "\n" + parsed.problemsText())))).useComponentsV2(true).queue(),
                            error -> {
                                log.warn("Failed to post a /dev embed message in channel {}", channel.getIdLong(), error);
                                event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER,
                                        "Couldn't post it — check the bot can send messages here."))).useComponentsV2(true).queue();
                            });
        } catch (Exception e) {
            log.error("Unhandled exception in /dev embed", e);
            Containers.replyError(event);
        }
    }
}
