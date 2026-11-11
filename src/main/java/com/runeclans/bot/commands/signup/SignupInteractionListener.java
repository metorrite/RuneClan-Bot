package com.runeclans.bot.commands.signup;

import com.runeclans.bot.discord.Mentions;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.devtools.DevCommandFilter;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.discord.Pagination;
import com.runeclans.bot.hub.HubService;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@BService
public class SignupInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(SignupInteractionListener.class);
    /**
     * The /dev signups bulk-close buttons. They carry a ':' like every other signup button, because {@link #onButtonInteraction} ignores
     * ids without one (before, these buttons were silently dropped and Discord said the interaction failed).
     */
    public static final String DEV_CLOSE_ALL = "signup_dev_close_all:ask", DEV_CLOSE_ALL_CONFIRM = "signup_dev_close_all:confirm",
            DEV_CLOSE_ALL_CANCEL = "signup_dev_close_all:cancel";
    private final SignupService signupService;
    private final BotConfig botConfig;
    private final AdminRoleFilter adminRoleFilter;
    private final HubService hub;
    private final DevCommandFilter devAccess;

    public SignupInteractionListener(SignupService signupService, BotConfig botConfig, AdminRoleFilter adminRoleFilter, HubService hub,
                                     DevCommandFilter devAccess) {
        this.hub = hub;
        this.devAccess = devAccess;
        this.signupService = signupService;
        this.botConfig = botConfig;
        this.adminRoleFilter = adminRoleFilter;
    }

    private boolean isAdmin(ButtonInteractionEvent event) {
        var member = event.getMember();
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }

    private boolean isAdmin(ModalInteractionEvent event) {
        var member = event.getMember();
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }

    private void replyNoPermission(ButtonInteractionEvent event) {
        Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.you_don_t_have_permission_to"));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;

        String id = event.getComponentId();

        if (!id.startsWith("signup_") || !id.contains(":")) return;

        try {
            handleButton(event, id);
        } catch (Exception e) {
            if (com.runeclans.bot.hubcommunity.HubCommunityReplies.reply(event, e)) return;
            log.error("Unhandled exception in signup button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private void handleButton(ButtonInteractionEvent event, String id) {
        if (id.startsWith("signup_builder_")) {
            handleBuilderButton(event, id);
            return;
        }
        if (id.startsWith("signup_dev_close_all:")) {
            handleDevCloseAll(event, id);
            return;
        }
        if (id.startsWith("signup_hub_")) {
            handleHubButton(event, id);
            return;
        }
        if (id.startsWith("signup_view_full")) {
            handleViewFullList(event, id);
            return;
        }

        String action = id.split(":")[0];
        long signupId = signupService.parseSignupId(id);

        switch (action) {

            // --- Public: join ---
            case "signup_join" -> {
                SignupSession session = signupService.getSessionById(signupId);
                if (session == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.this_signup_no_longer_exists"));
                    return;
                }

                if (!signupService.isSignupActive(signupId)) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.this_signup_is_currently_paused"));
                    return;
                }

                switch (session.type()) {
                    case QUEUE -> {
                        TextInput usernameInput = TextInput.create("signup_username", TextInputStyle.SHORT)
                                .setPlaceholder("Enter your username / RSN")
                                .setRequired(true)
                                .setRequiredRange(1, 50)
                                .build();

                        Modal modal = Modal.create("signup_submit:" + signupId, "Sign up")
                                .addComponents(Label.of("Username", usernameInput))
                                .build();

                        event.replyModal(modal).queue();
                    }

                    case GROUP -> {
                        boolean added = signupService.addUser(
                                event.getGuild(), signupId,
                                event.getUser().getIdLong(),
                                String.valueOf(event.getUser().getIdLong()),
                                null
                        );

                        if (!added) {
                            Containers.replyEphemeral(event, Containers.WARNING,
                                    Text.of(event, "signup.you_are_already_in_this_group"));
                            return;
                        }

                        signupService.updateMessages(event.getGuild(), signupId);
                        Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "signup.you_have_joined_the_group"));
                    }

                    case SUBMISSION -> {
                        List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                        Modal modal = buildSubmissionModal(signupId, fields);
                        event.replyModal(modal).queue();
                    }
                }
            }

            // --- Public: leave ---
            case "signup_leave" -> {
                SignupSession session = signupService.getSessionById(signupId);
                String leaveWarning = session != null && session.type() == SignupType.QUEUE
                        ? "Are you sure you want to leave this queue? If you sign up again later, you will be added to the bottom and lose your current spot."
                        : "Are you sure you want to leave?";

                Container confirm = Containers.card(Containers.WARNING,
                        TextDisplay.of(leaveWarning),
                        ActionRow.of(
                                Button.danger("signup_leave_confirm:" + signupId, "Yes, leave"),
                                Button.secondary("signup_leave_cancel:" + signupId, "Cancel")
                        ));

                event.replyComponents(List.of(confirm)).useComponentsV2(true).setEphemeral(true).queue();
            }

            case "signup_leave_cancel" -> Containers.editThenDelete(event, Containers.WARNING, Duration.ofSeconds(3), Text.of(event, "signup.leave_cancelled"));

            case "signup_leave_confirm" -> {
                boolean removed = signupService.removeUser(
                        event.getGuild(), signupId, event.getUser().getIdLong());

                if (!removed) {
                    Containers.editThenDelete(event, Containers.WARNING, Duration.ofSeconds(3), Text.of(event, "signup.you_are_not_currently_signed_up"));
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);
                Containers.editThenDelete(event, Containers.SUCCESS, Duration.ofSeconds(3), Text.of(event, "signup.you_have_been_removed"));
            }

            // --- Admin: queue-only ---
            case "signup_next" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry newFirst = signupService.next(signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                notifyNewFirst(event, signupId, newFirst);
                Containers.replyThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.moved_to_next_signup"));
            }

            case "signup_skip" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry newFirst = signupService.skip(signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                notifyNewFirst(event, signupId, newFirst);
                Containers.replyThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.skipped_current_first_signup"));
            }

            case "signup_remove" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry newFirst = signupService.remove(signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                notifyNewFirst(event, signupId, newFirst);
                Containers.replyThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.removed_current_first_signup"));
            }

            case "signup_notify" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry first = signupService.getFirst(signupId);

                if (first == null) {
                    Containers.replyThenDelete(event, Containers.WARNING, Text.of(event, "signup.there_is_nobody_to_notify"));
                    return;
                }

                notifyNewFirst(event, signupId, first);
                Containers.replyThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.notified_the_current_first_signup"));
            }

            // --- Admin: group-only ---
            case "signup_notify_all" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupSession session = signupService.getSessionById(signupId);
                if (session == null || session.groupRoleId() == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.group_role_not_found"));
                    return;
                }

                SignupMessage publicMessage = signupService.getFirstActivePublicMessage(signupId);
                if (publicMessage == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.no_public_panel_found_to_send"));
                    return;
                }

                var channel = event.getGuild().getTextChannelById(publicMessage.channelId());
                if (channel == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.signup_channel_not_found"));
                    return;
                }

                signupService.deletePingMessages(event.getGuild(), signupId);

                long guildId = event.getGuild().getIdLong();
                long channelId = channel.getIdLong();
                Mentions.onlyRoles(channel.sendMessageComponents(List.of(Containers.toast(Containers.PRIMARY, "<@&" + session.groupRoleId() + ">")))
                        .useComponentsV2(true), session.groupRoleId())
                        .queue(msg -> signupService.savePingMessage(signupId, guildId, channelId, msg.getIdLong()));

                Containers.replyThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.group_notified"));
            }

            // --- Admin: pick random (submission) / pick winner (group) ---
            case "signup_pick_random", "signup_pick_winner" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                List<SignupEntry> entries = signupService.getEntries(signupId);
                if (entries.isEmpty()) {
                    Containers.replyThenDelete(event, Containers.WARNING, Text.of(event, "signup.there_are_no_entries_to_pick"));
                    return;
                }

                String label = action.equals("signup_pick_random") ? "random submission" : "winner";

                Container confirm = Containers.card(Containers.WARNING,
                        TextDisplay.of("Pick a " + label + " from **" + entries.size() + "** entr"
                                + (entries.size() == 1 ? "y" : "ies") + "? **This will close the signup.**"),
                        ActionRow.of(
                                Button.success("signup_pick_confirm:" + signupId, "Yes, pick!"),
                                Button.secondary("signup_pick_cancel:" + signupId, "Cancel")
                        ));

                event.replyComponents(List.of(confirm)).useComponentsV2(true).setEphemeral(true).queue();
            }

            case "signup_pick_confirm" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                // Capture session and winner before deleteSignup clears entries
                SignupSession session = signupService.getSessionById(signupId);
                SignupEntry winner = signupService.pickRandom(signupId);

                if (winner == null || session == null) {
                    Containers.editThenDelete(event, Containers.WARNING, Text.of(event, "signup.no_entries_found_nothing_was_picked"));
                    return;
                }

                // Post winner announcement in the public channel
                SignupMessage publicMessage = signupService.getFirstActivePublicMessage(signupId);
                if (publicMessage != null) {
                    var channel = event.getGuild().getTextChannelById(publicMessage.channelId());
                    if (channel != null) {
                        channel.sendMessageComponents(List.of(signupService.buildWinnerContainer(session, winner)))
                                .useComponentsV2(true)
                                .queue();
                    }
                }

                // Close the signup
                signupService.deleteSignup(event.getGuild(), signupId);

                Containers.editThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.winner_picked_the_signup_is_now"));
            }

            case "signup_pick_cancel" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                Containers.editThenDelete(event, Containers.WARNING, Duration.ofSeconds(3), Text.of(event, "signup.pick_cancelled"));
            }

            // --- Admin: remove member/entry by Discord ID ---
            case "signup_admin_remove" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupSession session = signupService.getSessionById(signupId);
                String label = session != null && session.type() == SignupType.GROUP ? "member" : "entry";

                TextInput discordInput = TextInput.create("signup_admin_remove_discord", TextInputStyle.SHORT)
                        .setPlaceholder("Mention user or paste Discord ID")
                        .setRequired(true)
                        .setRequiredRange(1, 100)
                        .build();

                Modal modal = Modal.create("signup_admin_remove_submit:" + signupId, "Remove " + label)
                        .addComponents(Label.of("Discord @ / ID", discordInput))
                        .build();

                event.replyModal(modal).queue();
            }

            // --- Admin: add (modal content differs by type) ---
            case "signup_admin_add" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupSession session = signupService.getSessionById(signupId);
                if (session == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.this_signup_no_longer_exists"));
                    return;
                }

                event.replyModal(buildAdminAddModal(session)).queue();
            }

            // --- Admin: shared ---
            case "signup_clear" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                signupService.clear(event.getGuild(), signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                Containers.replyThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.cleared_all_entries"));
            }

            case "signup_pause" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                String newStatus = signupService.togglePause(signupId);

                if (newStatus == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.this_signup_no_longer_exists"));
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);
                String message = "ACTIVE".equalsIgnoreCase(newStatus) ? "Signup resumed." : "Signup paused.";
                Containers.replyThenDelete(event, Containers.SUCCESS, message);
            }

            case "signup_delete" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                Container confirm = Containers.card(Containers.DANGER,
                        TextDisplay.of("Are you sure you want to delete this signup?"),
                        ActionRow.of(
                                Button.danger("signup_delete_confirm:" + signupId, "Yes, delete"),
                                Button.secondary("signup_delete_cancel:" + signupId, "Cancel")
                        ));

                event.replyComponents(List.of(confirm)).useComponentsV2(true).setEphemeral(true).queue();
            }

            case "signup_delete_cancel" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                Containers.editThenDelete(event, Containers.WARNING, Duration.ofSeconds(3), Text.of(event, "signup.delete_cancelled"));
            }

            case "signup_delete_confirm" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                signupService.deleteSignup(event.getGuild(), signupId);

                Containers.editThenDelete(event, Containers.SUCCESS, Duration.ofSeconds(3), Text.of(event, "signup.signup_deleted"));
            }
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) return;

        String modalId = event.getModalId();

        if (!modalId.startsWith("signup_") || !modalId.contains(":")) return;

        try {
            handleModal(event, modalId);
        } catch (Exception e) {
            if (com.runeclans.bot.hubcommunity.HubCommunityReplies.reply(event, e)) return;
            log.error("Unhandled exception in signup modal interaction '{}'", modalId, e);
            Containers.replyError(event);
        }
    }

    private void handleModal(ModalInteractionEvent event, String modalId) {
        if (modalId.startsWith("signup_builder_")) {
            handleBuilderModal(event, modalId);
            return;
        }
        if (modalId.startsWith("signup_hub_")) {
            handleHubModal(event, modalId);
            return;
        }

        String action = modalId.split(":")[0];
        long signupId = signupService.parseSignupId(modalId);

        switch (action) {

            case "signup_submit" -> {
                SignupSession session = signupService.getSessionById(signupId);
                if (session == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.this_signup_no_longer_exists"));
                    return;
                }

                long userId = event.getUser().getIdLong();
                boolean added = false;

                switch (session.type()) {
                    case QUEUE -> {
                        String username = event.getValue("signup_username").getAsString().trim();
                        added = signupService.addUser(event.getGuild(), signupId, userId, username, null);
                    }
                    case SUBMISSION -> {
                        List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                        List<String> values = new ArrayList<>();
                        for (int i = 0; i < fields.size(); i++) {
                            var valueHolder = event.getValue("signup_field_" + i);
                            values.add(valueHolder != null ? valueHolder.getAsString().trim() : "");
                        }
                        String submissionValue = SubmissionField.serializeValues(values);
                        added = signupService.addUser(event.getGuild(), signupId, userId,
                                String.valueOf(userId), submissionValue);
                    }
                    case GROUP -> {
                        Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.unexpected_modal_for_this_signup_type"));
                        return;
                    }
                }

                if (!added) {
                    Containers.replyEphemeral(event, Containers.WARNING,
                            Text.of(event, "signup.you_are_already_signed_up_the"));
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);
                Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "signup.you_have_been_added"));
            }

            case "signup_admin_add_submit" -> {
                if (!isAdmin(event)) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.you_don_t_have_permission_to"));
                    return;
                }

                SignupSession session = signupService.getSessionById(signupId);
                if (session == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.this_signup_no_longer_exists"));
                    return;
                }

                String discordRaw = event.getValue("signup_admin_discord").getAsString().trim();
                long userId;

                try {
                    userId = parseUserId(discordRaw);
                } catch (NumberFormatException e) {
                    Containers.replyThenDelete(event, Containers.WARNING, Text.of(event, "signup.invalid_discord_user_please_mention_the"));
                    return;
                }

                boolean added = false;

                switch (session.type()) {
                    case QUEUE -> {
                        String rsn = event.getValue("signup_admin_rsn").getAsString().trim();
                        added = signupService.addManualUser(event.getGuild(), signupId, userId, rsn,
                                null, event.getUser().getIdLong());
                    }
                    case GROUP -> {
                        added = signupService.addManualUser(event.getGuild(), signupId, userId,
                                String.valueOf(userId), null, event.getUser().getIdLong());
                    }
                    case SUBMISSION -> {
                        List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                        List<String> values = new ArrayList<>();
                        for (int i = 0; i < fields.size(); i++) {
                            var valueHolder = event.getValue("signup_field_" + i);
                            values.add(valueHolder != null ? valueHolder.getAsString().trim() : "");
                        }
                        String submissionValue = SubmissionField.serializeValues(values);
                        added = signupService.addManualUser(event.getGuild(), signupId, userId,
                                String.valueOf(userId), submissionValue, event.getUser().getIdLong());
                    }
                }

                if (!added) {
                    Containers.replyThenDelete(event, Containers.WARNING,
                            Text.of(event, "signup.that_user_or_entry_is_already"));
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);
                Containers.replyThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.added_to_the_signup", userId));
            }

            case "signup_admin_remove_submit" -> {
                if (!isAdmin(event)) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.you_don_t_have_permission_to"));
                    return;
                }

                String discordRaw = event.getValue("signup_admin_remove_discord").getAsString().trim();
                long userId;

                try {
                    userId = parseUserId(discordRaw);
                } catch (NumberFormatException e) {
                    Containers.replyThenDelete(event, Containers.WARNING, Text.of(event, "signup.invalid_discord_user_please_mention_the"));
                    return;
                }

                boolean removed = signupService.removeUser(event.getGuild(), signupId, userId);

                if (!removed) {
                    Containers.replyThenDelete(event, Containers.WARNING, Text.of(event, "signup.that_user_is_not_in_this"));
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);
                Containers.replyThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.removed_from_the_signup", userId));
            }
        }
    }

    // --- /dev signups: bulk close (dev only, double-checked here even though @Test already keeps
    // the slash command itself out of production) ---

    private void handleDevCloseAll(ButtonInteractionEvent event, String id) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.this_command_is_dev_only"));
            return;
        }
        // Checked on every press, the confirm included: the buttons can be pressed by anyone who can see them.
        if (!devAccess.isAllowed(event.getJDA(), event.getUser().getIdLong())) {
            Containers.replyEphemeral(event, Containers.WARNING, DevCommandFilter.refusal());
            return;
        }

        switch (id) {
            case DEV_CLOSE_ALL -> {
                Container confirm = Containers.card(Containers.DANGER,
                        TextDisplay.of("Are you sure? This closes **every active signup on every server** the bot is in."),
                        ActionRow.of(
                                Button.danger(DEV_CLOSE_ALL_CONFIRM, "Yes, close everything"),
                                Button.secondary(DEV_CLOSE_ALL_CANCEL, "Cancel")
                        ));
                event.replyComponents(List.of(confirm)).useComponentsV2(true).setEphemeral(true).queue();
            }

            case DEV_CLOSE_ALL_CONFIRM -> {
                int count = signupService.closeAllActiveSignups(event.getJDA());
                Containers.edit(event, Containers.SUCCESS, Text.of(event, "signup.closed_signup_across_all_servers", count));
            }

            case DEV_CLOSE_ALL_CANCEL -> Containers.editThenDelete(event, Containers.WARNING, Duration.ofSeconds(3), Text.of(event, "signup.cancelled"));
        }
    }

    // --- Per-signup "View Full List" pager (opened from the entries section of any panel) ---

    /**
     * Handles both {@code signup_view_full:<id>} (the initial button on a shared panel — replies
     * with a fresh ephemeral pager) and {@code signup_view_full_page:<id>:<page>} (Prev/Next inside
     * that pager — edits it in place). Distinguishing by the button's own ID rather than a separate
     * dispatch keeps this stateless: the page number lives entirely in the button, never server-side.
     */
    private void handleViewFullList(ButtonInteractionEvent event, String id) {
        String[] parts = id.split(":");
        long signupId = Long.parseLong(parts[1]);
        int page = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

        SignupSession session = signupService.getSessionById(signupId);
        if (session == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.this_signup_no_longer_exists"));
            return;
        }

        Container container = signupService.buildEntriesListContainer(session, page);

        if (id.startsWith("signup_view_full_page:")) {
            event.editComponents(List.of(container)).useComponentsV2(true).queue();
        } else {
            event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
        }
    }

    // --- /signup hub: list/post/refresh (queue/group/submission builders reuse signup_builder_* as-is) ---

    private void handleHubButton(ButtonInteractionEvent event, String id) {
        if (id.startsWith("signup_hub_list_page:")) {
            replySignupList(event, Integer.parseInt(id.split(":")[1]), true);
            return;
        }

        switch (id) {
            case "signup_hub_list:_" -> replySignupList(event, 0, false);

            case "signup_hub_post:_" -> {
                List<SignupSession> visible = signupService.getVisibleSignups(event.getGuild().getIdLong());
                if (visible.isEmpty()) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.there_are_no_current_signups_to"));
                    return;
                }
                event.replyModal(buildHubPostModal(visible)).queue();
            }

            case "signup_hub_refresh:_" -> {
                Member member = event.getMember();
                if (member == null || !adminRoleFilter.isAuthorized(event.getGuild(), member)) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.you_need_the_admin_role_or"));
                    return;
                }
                refreshAllPanels(event, event.getGuild());
            }
        }
    }

    private void handleHubModal(ModalInteractionEvent event, String modalId) {
        if (!modalId.equals("signup_hub_post_modal:_")) return;

        SignupPanelType panelType = SignupPanelType.valueOf(
                event.getValue("signup_hub_post_type").getAsStringList().getFirst());
        long signupId = Long.parseLong(event.getValue("signup_hub_post_signup").getAsStringList().getFirst());

        Guild guild = event.getGuild();
        var channelMapping = event.getValue("signup_hub_post_channel");
        TextChannel channel = (channelMapping != null && !channelMapping.getAsLongList().isEmpty())
                ? guild.getTextChannelById(channelMapping.getAsLongList().getFirst())
                : event.getChannel().asTextChannel();

        if (channel == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.that_channel_isn_t_a_usable"));
            return;
        }
        String notAllowed = hub.publicChannelProblem(guild.getIdLong(), channel.getIdLong());
        if (notAllowed != null) {
            Containers.replyEphemeral(event, Containers.WARNING, notAllowed);
            return;
        }

        try {
            signupService.postSignupEmbed(guild, channel, signupId, panelType);
            Containers.replyThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.posted_signup_panel", panelType));
        } catch (IllegalArgumentException e) {
            Containers.replyThenDelete(event, Containers.WARNING, Text.of(event, "signup.that_signup_no_longer_exists"));
        }
    }

    private void replySignupList(ButtonInteractionEvent event, int pageIndex, boolean isPageNav) {
        List<SignupSession> signups = signupService.getVisibleSignups(event.getGuild().getIdLong());

        if (signups.isEmpty()) {
            Containers.replyThenDelete(event, Containers.INFO, Text.of(event, "signup.there_are_no_current_signups"));
            return;
        }

        var page = Pagination.paginate(signups, pageIndex);

        StringBuilder text = new StringBuilder();
        for (SignupSession signup : page.items()) {
            String status = signupService.getSignupStatus(signup.signupId());
            text.append("**").append(signup.signupId()).append("** — ").append(signup.title())
                    .append("\nType: `").append(signup.type().name()).append("` • Status: `").append(status).append("`\n\n");
        }

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# Current Signups (" + signups.size() + ")"));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of(text.toString().trim()));
        if (!page.isSinglePage()) {
            children.add(Pagination.navRow(page, "signup_hub_list_page:"));
        }

        Container container = Containers.card(Containers.INFO, children);

        if (isPageNav) {
            event.editComponents(List.of(container)).useComponentsV2(true).queue();
        } else {
            event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
        }
    }

    private void refreshAllPanels(ButtonInteractionEvent event, Guild guild) {
        List<SignupSession> signups = signupService.getVisibleSignups(guild.getIdLong());

        if (signups.isEmpty()) {
            Containers.replyThenDelete(event, Containers.INFO, Text.of(event, "signup.no_active_signup_panels_to_update"));
            return;
        }

        int count = signups.size();
        for (SignupSession signup : signups) {
            signupService.updateMessages(guild, signup.signupId());
        }

        Containers.replyThenDelete(event, Containers.SUCCESS,
                Text.of(event, "signup.refreshing_signup_panel_changes_will_appear", count, (count == 1 ? "" : "s")));
    }

    /** Signup titles double as option labels — capped at Discord's 25-option-per-select limit. */
    private Modal buildHubPostModal(List<SignupSession> visible) {
        StringSelectMenu typeSelect = StringSelectMenu.create("signup_hub_post_type")
                .addOption("Public", SignupPanelType.PUBLIC.name())
                .addOption("Admin", SignupPanelType.ADMIN.name())
                .setRequiredRange(1, 1)
                .build();

        StringSelectMenu.Builder signupSelectBuilder = StringSelectMenu.create("signup_hub_post_signup")
                .setRequiredRange(1, 1)
                .setPlaceholder("Which signup?");

        int limit = Math.min(visible.size(), SelectMenu.OPTIONS_MAX_AMOUNT);
        for (int i = 0; i < limit; i++) {
            SignupSession signup = visible.get(i);
            String label = "#" + signup.signupId() + " — " + truncate(signup.title(), 70) + " (" + signup.type() + ")";
            signupSelectBuilder.addOption(truncate(label, 100), String.valueOf(signup.signupId()));
        }

        EntitySelectMenu channelSelect = EntitySelectMenu.create("signup_hub_post_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setRequired(false)
                .setRequiredRange(0, 1)
                .setPlaceholder("Defaults to this channel if left blank")
                .build();

        return Modal.create("signup_hub_post_modal:_", "Post a Signup Panel")
                .addComponents(
                        Label.of("Panel type", typeSelect),
                        Label.of("Signup", signupSelectBuilder.build()),
                        Label.of("Channel (optional)", channelSelect)
                )
                .build();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // --- /signupbuilder: buttons ---

    private void handleBuilderButton(ButtonInteractionEvent event, String id) {
        String action = id.split(":")[0];

        switch (action) {
            case "signup_builder_type" -> event.replyModal(buildTypeModal(id.split(":")[1])).queue();

            case "signup_builder_add_field" -> {
                long userId = event.getUser().getIdLong();
                if (signupService.getSubmissionDraft(userId) == null) {
                    Containers.replyEphemeral(event, Containers.WARNING,
                            Text.of(event, "signup.this_builder_session_has_expired_start"));
                    return;
                }

                event.replyModal(buildFieldModal()).queue();
            }

            case "signup_builder_finish" -> {
                long userId = event.getUser().getIdLong();
                SubmissionDraft draft = signupService.getSubmissionDraft(userId);

                if (draft == null) {
                    Containers.editThenDelete(event, Containers.WARNING, Text.of(event, "signup.this_builder_session_has_expired_start"));
                    return;
                }

                Guild guild = event.getGuild();
                TextChannel adminChannel = guild.getTextChannelById(draft.adminChannelId());
                TextChannel publicChannel = guild.getTextChannelById(draft.publicChannelId());

                if (adminChannel == null || publicChannel == null) {
                    Containers.editThenDelete(event, Containers.WARNING, Text.of(event, "signup.one_of_the_selected_channels_no"));
                    signupService.cancelSubmissionDraft(userId);
                    return;
                }

                signupService.createSubmissionSession(guild, publicChannel, adminChannel, draft.title(),
                        draft.fields(), draft.maxEntries(), userId);
                signupService.cancelSubmissionDraft(userId);

                Containers.editThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.submission_signup_created_with_field", draft.title(), draft.fields().size()));
            }

            case "signup_builder_cancel" -> {
                signupService.cancelSubmissionDraft(event.getUser().getIdLong());
                Containers.editThenDelete(event, Containers.WARNING, Duration.ofSeconds(3), Text.of(event, "signup.cancelled"));
            }
        }
    }

    // --- /signupbuilder: modals ---

    private void handleBuilderModal(ModalInteractionEvent event, String modalId) {
        String action = modalId.split(":")[0];

        switch (action) {
            case "signup_builder_submit" -> {
                String type = modalId.split(":")[1];
                String title = event.getValue("signup_builder_title").getAsString().trim();
                Guild guild = event.getGuild();
                long userId = event.getUser().getIdLong();

                // the server may have fixed its signup admin channel, or limited where the public panel can go (Hub settings)
                long adminChannelId = hub.adminChannelFor(guild.getIdLong(), event.getValue("signup_builder_admin_channel").getAsLongList().getFirst());
                long publicChannelId = event.getValue("signup_builder_public_channel").getAsLongList().getFirst();
                String notAllowed = hub.publicChannelProblem(guild.getIdLong(), publicChannelId);
                if (notAllowed != null) {
                    Containers.replyThenDelete(event, Containers.WARNING, notAllowed);
                    return;
                }
                TextChannel adminChannel = guild.getTextChannelById(adminChannelId);
                TextChannel publicChannel = guild.getTextChannelById(publicChannelId);

                if (adminChannel == null || publicChannel == null) {
                    Containers.replyThenDelete(event, Containers.WARNING, Text.of(event, "signup.one_of_the_selected_channels_isn"));
                    return;
                }

                // The type modal is always opened from a button on the /signup hub message, so
                // editComponents() here edits *that* message in place — no need to track its ID
                // ourselves (ModalInteractionEvent#getMessage() carries it automatically for any
                // modal opened from a component).
                switch (type) {
                    case "QUEUE" -> {
                        String notify = event.getValue("signup_builder_notify").getAsString().trim();
                        Integer max = parseOptionalPositiveInt(event, "signup_builder_max");

                        if (max == INVALID_NUMBER) {
                            replyInvalidNumber(event);
                            return;
                        }

                        signupService.createQueueSession(guild, publicChannel, adminChannel, title, notify, max, userId);
                        Containers.editThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.queue_signup_created", title));
                    }

                    case "GROUP" -> {
                        signupService.createGroupSession(guild, publicChannel, adminChannel, title, userId);
                        Containers.editThenDelete(event, Containers.SUCCESS, Text.of(event, "signup.group_signup_created_a_role_is", title));
                    }

                    case "SUBMISSION" -> {
                        Integer max = parseOptionalPositiveInt(event, "signup_builder_max");

                        if (max == INVALID_NUMBER) {
                            replyInvalidNumber(event);
                            return;
                        }

                        signupService.startSubmissionDraft(userId, guild.getIdLong(),
                                adminChannelId, publicChannelId, title, max);
                        SubmissionDraft draft = signupService.getSubmissionDraft(userId);

                        event.editComponents(List.of(draftContainer(draft)))
                                .useComponentsV2(true)
                                .queue();
                    }
                }
            }

            case "signup_builder_field_submit" -> {
                long userId = event.getUser().getIdLong();

                String label = event.getValue("signup_builder_field_label").getAsString().trim();
                String type = event.getValue("signup_builder_field_type").getAsStringList().getFirst();
                boolean required = "YES".equals(event.getValue("signup_builder_field_required").getAsStringList().getFirst());

                SubmissionField field = new SubmissionField(label, type, required);

                boolean added = signupService.addDraftField(userId, field);
                if (!added) {
                    Containers.replyEphemeral(event, Containers.WARNING,
                            Text.of(event, "signup.this_builder_session_has_expired_or"));
                    return;
                }

                // Same as above: this modal was opened from the "Add Field" button on the
                // Submission Builder status message, so editComponents() updates that exact message.
                SubmissionDraft draft = signupService.getSubmissionDraft(userId);
                event.editComponents(List.of(draftContainer(draft)))
                        .useComponentsV2(true)
                        .queue();
            }
        }
    }

    private static final Integer INVALID_NUMBER = Integer.MIN_VALUE;

    /** Returns {@code null} for blank input (unlimited), the parsed value, or {@link #INVALID_NUMBER} on bad input. */
    private Integer parseOptionalPositiveInt(ModalInteractionEvent event, String inputId) {
        var value = event.getValue(inputId);
        if (value == null) return null;

        String raw = value.getAsString().trim();
        if (raw.isBlank()) return null;

        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : INVALID_NUMBER;
        } catch (NumberFormatException e) {
            return INVALID_NUMBER;
        }
    }

    private void replyInvalidNumber(ModalInteractionEvent event) {
        Containers.replyThenDelete(event, Containers.WARNING,
                Text.of(event, "signup.that_number_field_must_be_a"));
    }

    private Container draftContainer(SubmissionDraft draft) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("**Submission Builder — " + draft.title() + "**"));

        if (draft.fields().isEmpty()) {
            children.add(TextDisplay.of("*No fields yet — add at least one.*"));
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < draft.fields().size(); i++) {
                SubmissionField field = draft.fields().get(i);
                sb.append(i + 1).append(". **").append(field.label()).append("** (")
                        .append(field.type()).append(field.required() ? ", required" : ", optional").append(")\n");
            }
            children.add(TextDisplay.of(sb.toString()));
        }

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(ActionRow.of(draftButtons(draft)));

        return Containers.card(Containers.PRIMARY, children);
    }

    private List<Button> draftButtons(SubmissionDraft draft) {
        List<Button> buttons = new ArrayList<>();

        if (draft.fields().size() < 3) {
            buttons.add(Button.primary("signup_builder_add_field:_", "Add Field"));
        }
        if (!draft.fields().isEmpty()) {
            buttons.add(Button.success("signup_builder_finish:_", "Create Signup"));
        }
        buttons.add(Button.danger("signup_builder_cancel:_", "Cancel"));

        return buttons;
    }

    /** Both channels are always explicit picks — never implicitly "wherever /signup was run". */
    private Label adminChannelSelect() {
        return Label.of("Admin channel", EntitySelectMenu.create("signup_builder_admin_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder("Where admin controls get posted")
                .setRequiredRange(1, 1)
                .build());
    }

    private Label publicChannelSelect() {
        return Label.of("Public channel", EntitySelectMenu.create("signup_builder_public_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder("Where the public signup panel gets posted")
                .setRequiredRange(1, 1)
                .build());
    }

    private Modal buildTypeModal(String type) {
        TextInput titleInput = TextInput.create("signup_builder_title", TextInputStyle.SHORT)
                .setPlaceholder("Signup title")
                .setRequired(true)
                .setRequiredRange(1, 100)
                .build();

        return switch (type) {
            case "QUEUE" -> {
                TextInput notifyInput = TextInput.create("signup_builder_notify", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("Message sent when a user reaches the front of the queue")
                        .setRequired(true)
                        .setRequiredRange(1, 300)
                        .build();
                TextInput maxInput = TextInput.create("signup_builder_max", TextInputStyle.SHORT)
                        .setPlaceholder("Max signups — leave blank for unlimited")
                        .setRequired(false)
                        .setRequiredRange(0, 10)
                        .build();

                // Exactly 5 of 5 components Discord allows in one modal — no room to spare here.
                yield Modal.create("signup_builder_submit:QUEUE", "New Queue Signup")
                        .addComponents(
                                Label.of("Title", titleInput),
                                Label.of("Notification message", notifyInput),
                                Label.of("Max signups (optional)", maxInput),
                                adminChannelSelect(),
                                publicChannelSelect()
                        )
                        .build();
            }

            case "GROUP" -> Modal.create("signup_builder_submit:GROUP", "New Group Signup")
                    .addComponents(
                            Label.of("Title", titleInput),
                            adminChannelSelect(),
                            publicChannelSelect()
                    )
                    .build();

            case "SUBMISSION" -> {
                TextInput maxInput = TextInput.create("signup_builder_max", TextInputStyle.SHORT)
                        .setPlaceholder("Max submissions — leave blank for unlimited")
                        .setRequired(false)
                        .setRequiredRange(0, 10)
                        .build();

                yield Modal.create("signup_builder_submit:SUBMISSION", "New Submission Signup")
                        .addComponents(
                                Label.of("Title", titleInput),
                                Label.of("Max submissions (optional)", maxInput),
                                adminChannelSelect(),
                                publicChannelSelect()
                        )
                        .build();
            }

            default -> throw new IllegalStateException("Unknown signup builder type: " + type);
        };
    }

    private Modal buildFieldModal() {
        TextInput labelInput = TextInput.create("signup_builder_field_label", TextInputStyle.SHORT)
                .setPlaceholder("e.g. Movie, Song, Idea")
                .setRequired(true)
                .setRequiredRange(1, 45)
                .build();

        StringSelectMenu typeSelect = StringSelectMenu.create("signup_builder_field_type")
                .addOption("Text", SubmissionField.TYPE_TEXT)
                .addOption("Link", SubmissionField.TYPE_LINK)
                .addOption("Image", SubmissionField.TYPE_IMAGE)
                .setDefaultValues(SubmissionField.TYPE_TEXT)
                .setRequiredRange(1, 1)
                .build();

        StringSelectMenu requiredSelect = StringSelectMenu.create("signup_builder_field_required")
                .addOption("Yes", "YES")
                .addOption("No", "NO")
                .setDefaultValues("YES")
                .setRequiredRange(1, 1)
                .build();

        return Modal.create("signup_builder_field_submit:_", "Add Field")
                .addComponents(
                        Label.of("Field label", labelInput),
                        Label.of("Type", typeSelect),
                        Label.of("Required?", requiredSelect)
                )
                .build();
    }

    // --- Modal builders ---

    private Modal buildSubmissionModal(long signupId, List<SubmissionField> fields) {
        Modal.Builder mb = Modal.create("signup_submit:" + signupId, "Submit");

        for (int i = 0; i < fields.size(); i++) {
            SubmissionField field = fields.get(i);
            String placeholder = switch (field.type()) {
                case SubmissionField.TYPE_IMAGE -> "Paste an image URL";
                case SubmissionField.TYPE_LINK  -> "Paste a link URL";
                default                         -> "Enter " + field.label().toLowerCase();
            };

            TextInput.Builder inputBuilder = TextInput.create("signup_field_" + i, TextInputStyle.SHORT)
                    .setPlaceholder(placeholder)
                    .setRequired(field.required());
            if (field.required()) inputBuilder.setRequiredRange(1, 200);
            else inputBuilder.setRequiredRange(0, 200);

            String labelText = field.required() ? field.label() : field.label() + " (optional)";
            mb.addComponents(Label.of(labelText, inputBuilder.build()));
        }

        return mb.build();
    }

    private Modal buildAdminAddModal(SignupSession session) {
        long signupId = session.signupId();

        TextInput discordInput = TextInput.create("signup_admin_discord", TextInputStyle.SHORT)
                .setPlaceholder("Mention user or paste Discord ID")
                .setRequired(true)
                .setRequiredRange(1, 100)
                .build();

        return switch (session.type()) {
            case QUEUE -> {
                TextInput rsnInput = TextInput.create("signup_admin_rsn", TextInputStyle.SHORT)
                        .setPlaceholder("Enter RSN / username")
                        .setRequired(true)
                        .setRequiredRange(1, 50)
                        .build();

                yield Modal.create("signup_admin_add_submit:" + signupId, "Add user to signup")
                        .addComponents(
                                Label.of("RSN / Username", rsnInput),
                                Label.of("Discord @ / ID", discordInput)
                        )
                        .build();
            }

            case GROUP -> Modal.create("signup_admin_add_submit:" + signupId, "Add member to group")
                    .addComponents(Label.of("Discord @ / ID", discordInput))
                    .build();

            case SUBMISSION -> {
                List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                Modal.Builder mb = Modal.create("signup_admin_add_submit:" + signupId, "Add entry");
                mb.addComponents(Label.of("Discord @ / ID", discordInput));

                for (int i = 0; i < fields.size(); i++) {
                    SubmissionField field = fields.get(i);
                    String placeholder = switch (field.type()) {
                        case SubmissionField.TYPE_IMAGE -> "Paste an image URL";
                        case SubmissionField.TYPE_LINK  -> "Paste a link URL";
                        default                         -> "Enter " + field.label().toLowerCase();
                    };

                    TextInput.Builder inputBuilder = TextInput.create("signup_field_" + i, TextInputStyle.SHORT)
                            .setPlaceholder(placeholder)
                            .setRequired(field.required());
                    if (field.required()) inputBuilder.setRequiredRange(1, 200);
                    else inputBuilder.setRequiredRange(0, 200);

                    String labelText = field.required() ? field.label() : field.label() + " (optional)";
                    mb.addComponents(Label.of(labelText, inputBuilder.build()));
                }

                yield mb.build();
            }
        };
    }

    // --- Helpers ---

    private void notifyNewFirst(ButtonInteractionEvent event, long signupId, SignupEntry newFirst) {
        if (newFirst == null || event.getGuild() == null) return;

        SignupSession session = signupService.getSessionById(signupId);
        if (session == null || session.notificationMessage() == null) return;

        SignupMessage publicMessage = signupService.getFirstActivePublicMessage(signupId);
        if (publicMessage == null) return;

        var channel = event.getGuild().getTextChannelById(publicMessage.channelId());
        if (channel == null) return;

        signupService.deletePingMessages(event.getGuild(), signupId);

        long guildId = event.getGuild().getIdLong();
        long channelId = channel.getIdLong();
        channel.sendMessageComponents(List.of(Containers.toast(Containers.PRIMARY,
                        "<@" + newFirst.userId() + ">, " + session.notificationMessage())))
                .useComponentsV2(true)
                .queue(msg -> signupService.savePingMessage(signupId, guildId, channelId, msg.getIdLong()));
    }

    private long parseUserId(String input) {
        String cleaned = input
                .replace("<@", "")
                .replace("!", "")
                .replace(">", "")
                .trim();

        return Long.parseLong(cleaned);
    }
}
