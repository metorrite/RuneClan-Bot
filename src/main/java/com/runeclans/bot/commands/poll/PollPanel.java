package com.runeclans.bot.commands.poll;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.discord.Pagination;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code /poll} panel: a Create New Poll button, then the active polls the viewer may manage —
 * their own, or every one in the server for an admin — each with an End button. One panel for both,
 * so what an admin sees is simply the same list without the "yours only" filter.
 */
@BService
public class PollPanel {
    static final int PAGE_SIZE = 5;

    private final PollService pollService;

    public PollPanel(PollService pollService) {
        this.pollService = pollService;
    }

    /** @param notice an optional one-line result of what was just done ("Poll created…"), shown above the list */
    public Container build(long guildId, long viewerId, boolean admin, int pageIndex, String notice) {
        List<PollSession> polls = admin ? pollService.activePolls(guildId) : pollService.activePollsOwnedBy(guildId, viewerId);
        var page = Pagination.paginate(polls, pageIndex, PAGE_SIZE);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# Polls\n-# " + (admin
                ? "You're an admin, so this lists every active poll in the server and you can end any of them."
                : "Create a poll in this channel, or end one you started.")));
        if (notice != null) children.add(TextDisplay.of(notice));
        children.add(ActionRow.of(Button.success("poll_new:_", "Create New Poll")));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        if (polls.isEmpty()) {
            children.add(TextDisplay.of(admin ? "There are no active polls." : "You have no active polls."));
        } else {
            children.add(TextDisplay.of("**Active polls (" + polls.size() + ")**"));
            for (PollSession poll : page.items()) {
                children.add(Section.of(Button.danger("poll_end:" + poll.pollId(), "End"), TextDisplay.of(describe(poll, viewerId, admin))));
            }
            if (!page.isSinglePage()) children.add(Pagination.navRow(page, "poll_page:"));
        }

        children.add(Containers.autoCloseNote());
        return Containers.card(Containers.PRIMARY, children);
    }

    private String describe(PollSession poll, long viewerId, boolean admin) {
        StringBuilder text = new StringBuilder("**").append(poll.title()).append("**\n<#").append(poll.channelId()).append(">  ·  ")
                .append(pollService.totalVotes(poll.pollId())).append(" votes");
        if (poll.messageId() != null) {
            text.append("  ·  [jump](https://discord.com/channels/").append(poll.guildId()).append("/").append(poll.channelId())
                    .append("/").append(poll.messageId()).append(")");
        }
        if (admin && poll.createdByUserId() != viewerId) text.append("\nstarted by <@").append(poll.createdByUserId()).append(">");
        return text.toString();
    }

    /**
     * Ending a poll can't be undone, so it asks first — with the option to be DMed the full voter breakdown, except on an anonymous poll,
     * whose voters were promised nobody sees who voted for what.
     */
    public Container buildEndConfirm(PollSession poll) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.danger("poll_end_go:" + poll.pollId() + ":0", "End Poll"));
        if (!poll.anonymous()) buttons.add(Button.danger("poll_end_go:" + poll.pollId() + ":1", "End Poll & DM Me Who Voted"));
        buttons.add(Button.secondary("poll_end_cancel:_", "Cancel"));
        return Containers.card(Containers.DANGER,
                TextDisplay.of("### End “" + poll.title() + "”?\n" + pollService.totalVotes(poll.pollId())
                        + " votes so far. The poll's message stays, closed, with the final results — it can't be reopened."),
                ActionRow.of(buttons),
                Containers.autoCloseNote());
    }
}
