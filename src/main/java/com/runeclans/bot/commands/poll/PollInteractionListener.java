package com.runeclans.bot.commands.poll;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.permission.MemberAccess;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.checkbox.Checkbox;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything that happens after {@code /poll}: the vote buttons on a posted poll (open to anyone who can
 * see it), and the panel's Create New Poll form, page buttons and End confirmation (Member level and
 * above, with ending limited to a poll's starter or an admin).
 */
@BService
public class PollInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(PollInteractionListener.class);

    static final int MAX_TITLE = 150;
    static final int MAX_OPTION = 100;
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 6;

    private final PollService pollService;
    private final PollPanel pollPanel;
    private final MemberAccess memberAccess;
    private final AdminRoleFilter adminRoleFilter;

    public PollInteractionListener(PollService pollService, PollPanel pollPanel, MemberAccess memberAccess, AdminRoleFilter adminRoleFilter) {
        this.pollService = pollService;
        this.pollPanel = pollPanel;
        this.memberAccess = memberAccess;
        this.adminRoleFilter = adminRoleFilter;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;

        String id = event.getComponentId();
        if (!id.startsWith("poll_")) return;

        try {
            if (id.startsWith("poll_vote:")) {
                handleVote(event, id);
            } else if (id.startsWith("poll_mine:")) {
                handleMyVotes(event, id);
            } else {
                handlePanelButton(event, id);
            }
        } catch (Exception e) {
            if (com.runeclans.bot.hubcommunity.HubCommunityReplies.reply(event, e)) return;
            log.error("Unhandled exception in poll button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null || !event.getModalId().equals("poll_new_modal:_")) return;

        try {
            handleCreateModal(event);
        } catch (Exception e) {
            if (com.runeclans.bot.hubcommunity.HubCommunityReplies.reply(event, e)) return;
            log.error("Unhandled exception in poll modal interaction", e);
            Containers.replyError(event);
        }
    }

    // --- The panel ---

    private void handlePanelButton(ButtonInteractionEvent event, String id) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (member == null) return;

        if (!memberAccess.isMemberTier(guild, member)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.polls_are_for_clan_members_link"));
            return;
        }
        boolean admin = adminRoleFilter.isAuthorized(guild, member);
        String[] parts = id.split(":");

        switch (parts[0]) {
            case "poll_new" -> {
                if (!admin && pollService.activePollsOwnedBy(guild.getIdLong(), member.getIdLong()).size() >= PollService.MAX_ACTIVE_PER_MEMBER) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.you_already_have_active_polls_end", PollService.MAX_ACTIVE_PER_MEMBER));
                    return;
                }
                event.replyModal(buildCreateModal()).queue();
            }
            case "poll_page" -> showPanel(event, member, admin, parseIntOr(parts[1], 0), null);
            case "poll_end_cancel" -> showPanel(event, member, admin, 0, null);
            case "poll_end" -> {
                PollSession poll = manageablePoll(event, parts[1], member, admin);
                if (poll != null) event.editComponents(List.of(pollPanel.buildEndConfirm(poll))).useComponentsV2(true).queue();
            }
            case "poll_end_go" -> {
                PollSession poll = manageablePoll(event, parts[1], member, admin);
                if (poll == null) return;

                if (parts.length > 2 && parts[2].equals("1")) dmResults(event, poll);
                pollService.closePoll(guild, poll.pollId());
                showPanel(event, member, admin, 0, "✅ Ended **“" + poll.title() + "”**.");
            }
        }
    }

    /** The poll {@code idText} names, if it's still active and the clicker may end it; otherwise tells them why not and returns {@code null}. */
    private PollSession manageablePoll(ButtonInteractionEvent event, String idText, Member member, boolean admin) {
        PollSession poll = pollService.getSessionById(parseLongOr(idText, -1));
        if (poll == null || poll.guildId() != event.getGuild().getIdLong()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.that_poll_has_already_ended"));
            return null;
        }
        if (!PollService.canManage(poll, member.getIdLong(), admin)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.only_the_person_who_started_a"));
            return null;
        }
        return poll;
    }

    private void showPanel(ButtonInteractionEvent event, Member member, boolean admin, int page, String notice) {
        event.editComponents(List.of(pollPanel.build(event.getGuild().getIdLong(), member.getIdLong(), admin, page, notice)))
                .useComponentsV2(true).queue();
    }

    private void dmResults(ButtonInteractionEvent event, PollSession poll) {
        String summary = pollService.buildResultsSummary(poll.pollId());
        event.getUser().openPrivateChannel().queue(
                dm -> dm.sendMessageComponents(List.of(Containers.toast(Containers.PRIMARY, summary)))
                        .useComponentsV2(true)
                        .queue(null, err -> log.warn("Failed to DM poll results to {}", event.getUser().getIdLong(), err)),
                err -> log.warn("Could not open DM channel to {}", event.getUser().getIdLong(), err));
    }

    // --- Creating ---

    static Modal buildCreateModal() {
        TextInput title = TextInput.create("poll_title", TextInputStyle.SHORT)
                .setPlaceholder("What are you asking?")
                .setRequiredRange(1, MAX_TITLE)
                .build();
        TextInput options = TextInput.create("poll_options", TextInputStyle.PARAGRAPH)
                .setPlaceholder("One option per line — " + MIN_OPTIONS + " to " + MAX_OPTIONS)
                .setRequiredRange(1, 700)
                .build();

        return Modal.create("poll_new_modal:_", "Create a Poll")
                .addComponents(
                        Label.of("Question", title),
                        Label.of("Options (one per line)", options),
                        Label.of("Allow multiple votes", "Each person can vote for more than one option", Checkbox.of("poll_multi")),
                        Label.of("Anonymous", "Hide who voted for what", Checkbox.of("poll_anon")))
                .build();
    }

    private void handleCreateModal(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (member == null || !memberAccess.isMemberTier(guild, member)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.polls_are_for_clan_members_link"));
            return;
        }
        boolean admin = adminRoleFilter.isAuthorized(guild, member);

        String title = event.getValue("poll_title").getAsString().trim();
        List<String> options = parseOptions(event.getValue("poll_options").getAsString());
        boolean multiple = event.getValue("poll_multi").getAsBoolean();
        boolean anonymous = event.getValue("poll_anon").getAsBoolean();

        String problem = validate(title, options);
        if (problem != null) {
            Containers.replyEphemeral(event, Containers.WARNING, problem);
            return;
        }
        if (!(event.getGuildChannel() instanceof GuildMessageChannel channel) || !channel.canTalk()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.i_can_t_post_a_poll"));
            return;
        }
        if (!admin && pollService.activePollsOwnedBy(guild.getIdLong(), member.getIdLong()).size() >= PollService.MAX_ACTIVE_PER_MEMBER) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.you_already_have_active_polls_end", PollService.MAX_ACTIVE_PER_MEMBER));
            return;
        }

        pollService.createPoll(guild, channel, title, anonymous, multiple, options, member.getIdLong());
        event.editComponents(List.of(pollPanel.build(guild.getIdLong(), member.getIdLong(), admin, 0, "✅ Poll posted in <#" + channel.getIdLong() + ">.")))
                .useComponentsV2(true).queue();
    }

    /** One option per non-blank line, trimmed. */
    static List<String> parseOptions(String raw) {
        List<String> options = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String option = line.strip();
            if (!option.isEmpty()) options.add(option);
        }
        return options;
    }

    /** {@code null} if the poll is fine; otherwise what to tell the person. */
    static String validate(String title, List<String> options) {
        if (title.isBlank()) return "A poll needs a question.";
        if (title.length() > MAX_TITLE) return "The question is too long — keep it under " + MAX_TITLE + " characters.";
        if (options.size() < MIN_OPTIONS) return "A poll needs at least " + MIN_OPTIONS + " options — put each on its own line.";
        if (options.size() > MAX_OPTIONS) return "A poll can have at most " + MAX_OPTIONS + " options — you gave " + options.size() + ".";

        Set<String> seen = new HashSet<>();
        for (String option : options) {
            if (option.length() > MAX_OPTION) return "The option “" + option.substring(0, 20) + "…” is longer than " + MAX_OPTION + " characters.";
            if (!seen.add(option.toLowerCase(Locale.ROOT))) return "“" + option + "” is listed twice — every option needs to be different.";
        }
        return null;
    }

    // --- Voting ---

    private void handleVote(ButtonInteractionEvent event, String id) {
        String[] parts = id.split(":");
        if (parts.length != 3) return;

        long pollId;
        int optionNumber;
        try {
            pollId = Long.parseLong(parts[1]);
            optionNumber = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }

        PollSession session = pollService.getSessionById(pollId);
        if (session == null || !"ACTIVE".equalsIgnoreCase(session.status())) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.this_poll_is_no_longer_active"));
            return;
        }

        List<PollOption> options = pollService.getOptions(pollId);
        PollOption option = options.stream()
                .filter(o -> o.optionNumber() == optionNumber)
                .findFirst()
                .orElse(null);

        if (option == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.this_poll_option_no_longer_exists"));
            return;
        }

        PollService.VoteResult result = pollService.toggleVote(pollId, option.optionId(), event.getUser().getIdLong());

        if (result == PollService.VoteResult.POLL_CLOSED) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.this_poll_is_no_longer_active"));
            return;
        }

        String feedback = switch (result) {
            case ADDED    -> "✅  Your vote for **" + option.label() + "** has been recorded.";
            case REMOVED  -> "🗑️  Your vote for **" + option.label() + "** has been removed.";
            case SWITCHED -> "🔄  Switched your vote to **" + option.label() + "**.";
            default       -> "✅  Vote updated.";
        };

        pollService.updateMessage(event.getGuild(), pollId);

        // The poll itself looks the same to everyone, so the reply is where you see your own picks.
        Containers.replyThenDelete(event, Containers.SUCCESS, Duration.ofSeconds(8),
                feedback + "\n" + describeMine(pollService.myVoteLines(pollId, event.getUser().getIdLong())));
    }

    /** The My Votes button: a private, short-lived list of what you've picked in this poll. */
    private void handleMyVotes(ButtonInteractionEvent event, String id) {
        long pollId = parseLongOr(id.substring("poll_mine:".length()), -1);
        PollSession session = pollService.getSessionById(pollId);
        if (session == null || !"ACTIVE".equalsIgnoreCase(session.status())) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.this_poll_is_no_longer_active"));
            return;
        }
        Containers.replyThenDelete(event, Containers.INFO, Duration.ofSeconds(10), describeMine(pollService.myVoteLines(pollId, event.getUser().getIdLong())));
    }

    static String describeMine(List<String> lines) {
        return lines.isEmpty() ? "**Your votes:** none yet" : "**Your votes:**\n" + String.join("\n", lines);
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLongOr(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
