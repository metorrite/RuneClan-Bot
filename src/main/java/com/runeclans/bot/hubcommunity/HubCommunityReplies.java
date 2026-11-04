package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.discord.Mentions;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

/**
 * Tells the member who clicked what the hub said (ticket F3), from a poll or signup listener's catch block: "changed elsewhere", "saved,
 * will sync shortly", "can't reach RuneClans" or the hub's own refusal, instead of the generic error.
 */
public final class HubCommunityReplies {
    private HubCommunityReplies() {}

    /** The hub problem behind {@code e}, if it is one (also when wrapped). */
    public static HubCommunityProblem problemIn(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HubCommunityProblem p) return p;
        }
        return null;
    }

    /** Replies with the hub's words when {@code e} is a hub problem; false (nothing sent) otherwise. */
    public static boolean reply(IReplyCallback event, Throwable e) {
        HubCommunityProblem problem = problemIn(e);
        if (problem == null) return false;
        try {
            var colour = problem.kind() == HubCommunityProblem.Kind.QUEUED ? Containers.INFO : Containers.WARNING;
            if (!event.isAcknowledged()) Containers.replyEphemeral(event, colour, problem.getMessage());
            else event.getHook().sendMessage(problem.getMessage()).setEphemeral(true).setAllowedMentions(Mentions.NONE).queue(ok -> {}, fail -> {});
        } catch (RuntimeException ignored) {
            // the member already got an answer, or the interaction expired: nothing more to tell
        }
        return true;
    }
}
