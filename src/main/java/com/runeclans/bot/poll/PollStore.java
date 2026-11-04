package com.runeclans.bot.poll;

import com.runeclans.bot.commands.poll.PollOption;
import com.runeclans.bot.commands.poll.PollSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where {@code PollService} keeps polls (runeclans-hub PHASE-3 F3): the bot's own tables ({@link PollRepository}) or the RuneClans hub
 * ({@code hubcommunity.HubPollStore}), picked per call by the {@code hub-community} switch. Polls, options and voters are named by whole
 * numbers either way (Discord buttons carry them); a vote is by Discord user id.
 */
public interface PollStore {
    /** The hub's side: one {@link #create} and one {@link #setVotes} per change instead of the bot's step-by-step writes. */
    default boolean hub() {
        return false;
    }

    /** A new poll with its options in one go (the hub's side; the bot's tables use {@link #createPoll} and {@link #addOption}). */
    default Created create(long guildId, long channelId, String title, boolean anonymous, boolean multipleVotes, List<String> labels, long createdByUserId,
                           Instant closesAt) {
        throw new UnsupportedOperationException("the bot's tables make a poll step by step");
    }

    /** Replaces a member's picks in one write (empty takes every vote back): the hub's side. */
    default void setVotes(long pollId, long userId, Set<Long> optionIds) {
        throw new UnsupportedOperationException("the bot's tables change votes one by one");
    }

    record Created(long pollId, List<PollOption> options) {}

    long createPoll(long guildId, long channelId, String title, boolean anonymous, boolean multipleVotes, long createdByUserId);

    long addOption(long pollId, int optionNumber, String label);

    void saveMessageId(long pollId, long messageId);

    List<PollSession> getAllActivePolls();

    List<PollSession> getActivePolls(long guildId);

    PollSession getPollById(long pollId);

    List<PollOption> getOptions(long pollId);

    boolean addVote(long pollId, long optionId, long userId);

    boolean removeVote(long pollId, long optionId, long userId);

    void removeAllVotesForUser(long pollId, long userId);

    boolean hasVotedForOption(long pollId, long optionId, long userId);

    boolean hasVotedInPoll(long pollId, long userId);

    Set<Long> getUserVotes(long pollId, long userId);

    Map<Long, Integer> getVoteCounts(long pollId);

    Map<Long, List<Long>> getVotersByOption(long pollId);

    void closePoll(long pollId);
}
