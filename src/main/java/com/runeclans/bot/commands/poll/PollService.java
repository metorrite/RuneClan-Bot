package com.runeclans.bot.commands.poll;

import com.runeclans.bot.poll.PollStore;
import com.runeclans.bot.util.TtlCache;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creating, voting in, updating and closing polls, plus the lookups the {@code /poll} panel needs (a
 * member's own active polls, or every active poll for an admin). How a poll looks is {@link PollView}'s job.
 */
@BService
public class PollService {
    private static final Logger log = LoggerFactory.getLogger(PollService.class);

    /** How many polls a regular member can have running at once — admins are unlimited. */
    public static final int MAX_ACTIVE_PER_MEMBER = 5;

    /**
     * How long a lookup that found no active poll is remembered. A stale button clicked over and over then costs one query per window,
     * and a poll another process makes (the website's {@code api} process) is never hidden for longer than this.
     */
    static final Duration MISS_TTL = Duration.ofSeconds(10);

    // Postgres is the source of truth; these maps are a cache of the active polls (warmed at start-up, filled on a miss by
    // getSessionById, so a poll made by another process works without a restart).
    // ConcurrentHashMap: JDA/BotCommands can dispatch interaction callbacks (button clicks) from
    // a pooled executor rather than a single thread, so these maps can be read/written
    // concurrently from separate polls' interactions.
    private final Map<Long, PollSession> activePollsById = new ConcurrentHashMap<>();
    private final Map<Long, List<PollOption>> optionsByPollId = new ConcurrentHashMap<>();
    private final TtlCache<Long, Boolean> recentMisses = new TtlCache<>(MISS_TTL);
    private final PollStore pollRepository;

    /** {@code pollRepository}: the bot's own tables or the hub, by the {@code hub-community} switch ({@code hubcommunity.CommunityStores}). */
    public PollService(PollStore pollRepository) {
        this.pollRepository = pollRepository;
        loadActivePolls();
    }

    private void loadActivePolls() {
        try {
            List<PollSession> polls = pollRepository.getAllActivePolls();
            for (PollSession poll : polls) {
                activePollsById.put(poll.pollId(), poll);
                optionsByPollId.put(poll.pollId(), pollRepository.getOptions(poll.pollId()));
                log.info("Loaded poll {} '{}' for guild {}", poll.pollId(), poll.title(), poll.guildId());
            }
        } catch (Exception e) {
            log.error("Failed to load active polls from database — starting with empty state", e);
        }
    }

    // --- Creation ---

    /** @return the new poll's id */
    public long createPoll(Guild guild, GuildMessageChannel channel, String title, boolean anonymous,
                           boolean multipleVotes, List<String> optionLabels, long createdByUserId) {
        return createPoll(guild, channel, title, anonymous, multipleVotes, optionLabels, createdByUserId, null);
    }

    /**
     * As above, with an end time: on the hub ({@code hub-community}) the hub closes it then; on the bot's own tables the caller schedules
     * the close ({@code CommunitySettings.scheduleClose}), so {@code closesAt} is not used there.
     */
    public long createPoll(Guild guild, GuildMessageChannel channel, String title, boolean anonymous,
                           boolean multipleVotes, List<String> optionLabels, long createdByUserId, java.time.Instant closesAt) {
        Stored stored = store(guild, channel, title, anonymous, multipleVotes, optionLabels, createdByUserId, closesAt);
        long pollId = stored.session().pollId();
        post(channel, stored).queue(message -> posted(stored.session(), message),
                error -> log.warn("Failed to post poll {} '{}' in channel {}", pollId, title, channel.getIdLong(), error));
        return pollId;
    }

    /**
     * {@link #createPoll} for a caller that must know Discord took the post: the dashboard's outbox job, which shows the page the result.
     * {@code send} runs the post and waits for Discord (the outbox's {@code DiscordJobs::send}). When Discord certainly didn't take it
     * ({@link #certainlyNotPosted}), the poll is closed again before the error goes on, so no open poll is left without a message. After
     * an unclear failure (a timeout, a Discord server error) the message may be live, so the poll stays open: closing it would turn every
     * button on a posted poll into "that poll has ended". A failure to record the posted message is only logged: the post went out.
     */
    public long createPollPosted(Guild guild, GuildMessageChannel channel, String title, boolean anonymous, boolean multipleVotes,
                                 List<String> optionLabels, long createdByUserId, java.time.Instant closesAt,
                                 java.util.function.Function<net.dv8tion.jda.api.requests.RestAction<Message>, Message> send) {
        long pollId = storePoll(guild, channel, title, anonymous, multipleVotes, optionLabels, createdByUserId, closesAt);
        postStored(pollId, channel, send, false);
        return pollId;
    }

    /**
     * The first half of {@link #createPollPosted}: stores the poll (open, no message yet) and answers its id, for a caller that posts it with
     * {@link #postStored}, possibly over several tries.
     */
    public long storePoll(Guild guild, GuildMessageChannel channel, String title, boolean anonymous, boolean multipleVotes,
                          List<String> optionLabels, long createdByUserId, java.time.Instant closesAt) {
        return store(guild, channel, title, anonymous, multipleVotes, optionLabels, createdByUserId, closesAt).session().pollId();
    }

    /**
     * Posts a poll {@link #storePoll} stored, the way {@link #createPollPosted} does. With {@code keepWhenLater}, a "not now" before sending
     * ({@link com.runeclans.bot.discord.jobs.DiscordJobs.RetryLater}, a rate limit) keeps the poll for the next try instead of closing it, so
     * a post that has to wait never leaves one closed empty poll per wait. A poll that is no longer open (closed after an earlier refusal, or
     * ended) is never posted.
     */
    public Message postStored(long pollId, GuildMessageChannel channel,
                              java.util.function.Function<net.dv8tion.jda.api.requests.RestAction<Message>, Message> send, boolean keepWhenLater) {
        PollSession session = getSessionById(pollId);
        if (session == null || !"ACTIVE".equalsIgnoreCase(session.status())) {
            throw new com.runeclans.bot.discord.jobs.DiscordJobs.Refused("That poll was closed before it could be posted.");
        }
        if (session.messageId() != null) {
            throw new com.runeclans.bot.discord.jobs.DiscordJobs.Refused("That poll is already posted.");
        }
        Stored stored = new Stored(session, getOptions(pollId));
        Message message;
        try {
            message = send.apply(post(channel, stored));
        } catch (RuntimeException e) {
            if (keepWhenLater && e instanceof com.runeclans.bot.discord.jobs.DiscordJobs.RetryLater) {
                log.debug("Poll {} '{}' waits to be posted: {}", pollId, session.title(), e.getMessage());
            } else if (certainlyNotPosted(e)) {
                discard(pollId);
            } else {
                log.warn("Poll {} '{}' may or may not have been posted in channel {}; it stays open", pollId, session.title(), channel.getIdLong(), e);
            }
            throw e;
        }
        try {
            posted(session, message);
        } catch (RuntimeException e) {
            log.warn("Poll {} was posted as message {} but its message id couldn't be saved", pollId, message.getIdLong(), e);
        }
        return message;
    }

    /**
     * Whether a failed post certainly never reached the channel: Discord answered with a refusal (a 4xx error such as missing permissions),
     * JDA refused before sending (missing permission), or the outbox refused or held it back for a rate limit before sending.
     */
    static boolean certainlyNotPosted(RuntimeException e) {
        if (e instanceof net.dv8tion.jda.api.exceptions.ErrorResponseException error) return !error.isServerError();
        return e instanceof net.dv8tion.jda.api.exceptions.PermissionException
                || e instanceof com.runeclans.bot.discord.jobs.DiscordJobs.Refused
                || e instanceof com.runeclans.bot.discord.jobs.DiscordJobs.RetryLater;
    }

    private record Stored(PollSession session, List<PollOption> options) {}

    private Stored store(Guild guild, GuildMessageChannel channel, String title, boolean anonymous, boolean multipleVotes,
                         List<String> optionLabels, long createdByUserId, java.time.Instant closesAt) {
        long pollId;
        List<PollOption> options = new ArrayList<>();
        if (pollRepository.hub()) {
            PollStore.Created created = pollRepository.create(guild.getIdLong(), channel.getIdLong(), title, anonymous, multipleVotes, optionLabels, createdByUserId, closesAt);
            pollId = created.pollId();
            options.addAll(created.options());
        } else {
            pollId = pollRepository.createPoll(guild.getIdLong(), channel.getIdLong(), title, anonymous, multipleVotes, createdByUserId);
            for (int i = 0; i < optionLabels.size(); i++) {
                long optionId = pollRepository.addOption(pollId, i + 1, optionLabels.get(i));
                options.add(new PollOption(optionId, pollId, i + 1, optionLabels.get(i)));
            }
        }

        PollSession session = new PollSession(pollId, guild.getIdLong(), channel.getIdLong(),
                null, title, anonymous, multipleVotes, "ACTIVE", createdByUserId);
        activePollsById.put(pollId, session);
        optionsByPollId.put(pollId, options);
        return new Stored(session, options);
    }

    private static net.dv8tion.jda.api.requests.RestAction<Message> post(GuildMessageChannel channel, Stored stored) {
        return channel.sendMessageComponents(List.of(PollView.build(stored.session(), stored.options(), Map.of(), Map.of(), false)))
                .useComponentsV2(true);
    }

    private void posted(PollSession session, Message message) {
        long pollId = session.pollId();
        pollRepository.saveMessageId(pollId, message.getIdLong());
        activePollsById.put(pollId, new PollSession(pollId, session.guildId(), session.channelId(),
                message.getIdLong(), session.title(), session.anonymous(), session.multipleVotes(), "ACTIVE", session.createdByUserId()));
        log.info("Created poll {} '{}' in guild {}", pollId, session.title(), session.guildId());
    }

    /** Closes a poll whose message Discord never took, and forgets it here. A failed close is only logged: the post's error matters more. */
    public void discard(long pollId) {
        try {
            pollRepository.closePoll(pollId);
        } catch (RuntimeException e) {
            log.warn("Couldn't close poll {} after its post failed", pollId, e);
        }
        activePollsById.remove(pollId);
        optionsByPollId.remove(pollId);
    }

    // --- Lookup ---

    /**
     * The active poll with this id, or null. A cache miss reads the one row from the database (load-through), so a poll made by another
     * process (the website) works here without a restart. A miss that finds nothing is remembered for {@link #MISS_TTL} only.
     */
    public PollSession getSessionById(long pollId) {
        PollSession cached = activePollsById.get(pollId);
        return cached != null ? cached : loadThrough(pollId);
    }

    private PollSession loadThrough(long pollId) {
        if (recentMisses.get(pollId).isPresent()) return null;
        PollSession stored;
        try {
            stored = pollRepository.getPollById(pollId);
        } catch (com.runeclans.bot.hubcommunity.HubCommunityProblem e) {
            throw e; // the hub couldn't answer: say so, never "this poll is closed"
        } catch (RuntimeException e) {
            log.warn("Could not look up poll {} after a cache miss", pollId, e);
            return null; // a failed read is not remembered
        }
        if (stored == null || !"ACTIVE".equalsIgnoreCase(stored.status())) {
            recentMisses.put(pollId, Boolean.TRUE);
            return null;
        }
        PollSession raced = activePollsById.putIfAbsent(pollId, stored);
        return raced != null ? raced : stored;
    }

    /**
     * A poll's options in order. Cached only for an active poll that is already posted: a poll another process is still creating writes
     * its options one by one before it posts the message, so a half-written list is never kept.
     */
    public List<PollOption> getOptions(long pollId) {
        List<PollOption> cached = optionsByPollId.get(pollId);
        if (cached != null) return cached;
        List<PollOption> stored = pollRepository.getOptions(pollId);
        PollSession session = activePollsById.get(pollId);
        if (session != null && session.messageId() != null && !stored.isEmpty()) optionsByPollId.putIfAbsent(pollId, stored);
        return stored;
    }

    /**
     * Every active poll in the guild, newest first — what an admin sees in the {@code /poll} panel. Read from the database (one query for
     * the server), so polls made or ended by another process are counted; the cache only fills in what it already knows.
     */
    public List<PollSession> activePolls(long guildId) {
        List<PollSession> stored;
        try {
            stored = pollRepository.getActivePolls(guildId);
        } catch (com.runeclans.bot.hubcommunity.HubCommunityProblem e) {
            throw e; // the hub couldn't answer: say so, never "no polls"
        } catch (RuntimeException e) {
            log.warn("Could not list active polls for guild {}; using the cached ones", guildId, e);
            return cachedActivePolls(guildId);
        }
        Set<Long> ids = new HashSet<>();
        List<PollSession> polls = new ArrayList<>();
        for (PollSession poll : stored) {
            ids.add(poll.pollId());
            polls.add(activePollsById.merge(poll.pollId(), poll, PollService::newerPosting));
        }
        // Ended by another process: drop them here too. One closed here is gone from the map already.
        activePollsById.values().removeIf(poll -> poll.guildId() == guildId && !ids.contains(poll.pollId()));
        optionsByPollId.keySet().retainAll(activePollsById.keySet());
        polls.sort(Comparator.comparingLong(PollSession::pollId).reversed());
        return polls;
    }

    /**
     * Keeps the cached copy unless it was cached before the poll's message was posted (by another process, which saves the message id
     * only after posting) and the row now has the id: without it this process could never edit the poll's message.
     */
    private static PollSession newerPosting(PollSession cached, PollSession stored) {
        return cached.messageId() == null && stored.messageId() != null ? stored : cached;
    }

    /** A cached poll still waiting for its message id: re-reads the row, since another process may have posted it since. */
    private PollSession withPostedMessage(PollSession session) {
        if (session.messageId() != null) return session;
        PollSession stored;
        try {
            stored = pollRepository.getPollById(session.pollId());
        } catch (RuntimeException e) {
            log.warn("Could not re-read poll {} for its message id", session.pollId(), e);
            return session;
        }
        if (stored == null || stored.messageId() == null || !"ACTIVE".equalsIgnoreCase(stored.status())) return session;
        PollSession merged = activePollsById.computeIfPresent(session.pollId(), (id, cached) -> newerPosting(cached, stored));
        return merged != null ? merged : stored;
    }

    private List<PollSession> cachedActivePolls(long guildId) {
        return activePollsById.values().stream()
                .filter(poll -> poll.guildId() == guildId)
                .sorted(Comparator.comparingLong(PollSession::pollId).reversed())
                .toList();
    }

    /** The active polls {@code userId} started — what a member sees in the {@code /poll} panel. */
    public List<PollSession> activePollsOwnedBy(long guildId, long userId) {
        return activePolls(guildId).stream().filter(poll -> poll.createdByUserId() == userId).toList();
    }

    public int totalVotes(long pollId) {
        return pollRepository.getVoteCounts(pollId).values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Whoever started a poll may end it, and so may an admin; nobody else. */
    public static boolean canManage(PollSession poll, long userId, boolean isAdmin) {
        return isAdmin || poll.createdByUserId() == userId;
    }

    /**
     * What {@code userId} has voted for in an active poll, as lines like "1️⃣ Option label" in option order
     * — empty if they haven't voted. The public message is the same for everyone, so this is how a person
     * sees *their own* picks (in their private reply, and behind the My Votes button).
     */
    public List<String> myVoteLines(long pollId, long userId) {
        java.util.Set<Long> mine = pollRepository.getUserVotes(pollId, userId);
        List<String> lines = new ArrayList<>();
        for (PollOption option : getOptions(pollId)) {
            if (mine.contains(option.optionId())) lines.add(PollView.NUMBER_EMOJIS[option.optionNumber() - 1] + " " + option.label());
        }
        return lines;
    }

    /** The option numbers {@code userId} has picked in a poll — what the website highlights as "your vote". */
    public java.util.Set<Integer> myOptionNumbers(long pollId, long userId) {
        java.util.Set<Long> mine = pollRepository.getUserVotes(pollId, userId);
        java.util.Set<Integer> numbers = new java.util.TreeSet<>();
        for (PollOption option : getOptions(pollId)) if (mine.contains(option.optionId())) numbers.add(option.optionNumber());
        return numbers;
    }

    // --- Voting ---

    public enum VoteResult { ADDED, REMOVED, SWITCHED, POLL_CLOSED }

    /**
     * One member's vote changes one at a time. A change is several reads and writes (clear then add for a one-answer poll; read the
     * picks then write them all on the hub), so two clicks at once, or a click plus a website vote, could otherwise both pass and leave
     * two answers in a one-answer poll or lose a pick. Striped by (poll, member): different members never wait on each other for long.
     * On the bot's own tables one bot process serves every vote (Discord buttons and the website's polls/vote route), so this covers them
     * all. On the hub ({@code hub-community}) website votes go to the hub directly, so this only covers votes that pass through the bot;
     * the hub path holds the lock across its two hub calls, which are time-limited by the hub client.
     */
    private final Object[] voteLocks = newVoteLocks();

    private static Object[] newVoteLocks() {
        Object[] locks = new Object[64];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private Object voteLock(long pollId, long userId) {
        return voteLocks[Math.floorMod(java.util.Objects.hash(pollId, userId), voteLocks.length)];
    }

    public VoteResult toggleVote(long pollId, long optionId, long userId) {
        synchronized (voteLock(pollId, userId)) {
            return toggleVoteLocked(pollId, optionId, userId);
        }
    }

    private VoteResult toggleVoteLocked(long pollId, long optionId, long userId) {
        PollSession session = getSessionById(pollId);
        if (session == null || !"ACTIVE".equalsIgnoreCase(session.status())) return VoteResult.POLL_CLOSED;

        if (pollRepository.hub()) return toggleOnHub(session, optionId, userId);

        boolean alreadyVotedThis = pollRepository.hasVotedForOption(pollId, optionId, userId);

        if (alreadyVotedThis) {
            pollRepository.removeVote(pollId, optionId, userId);
            return VoteResult.REMOVED;
        }

        if (!session.multipleVotes()) {
            boolean hadPriorVote = pollRepository.hasVotedInPoll(pollId, userId);
            pollRepository.removeAllVotesForUser(pollId, userId);
            pollRepository.addVote(pollId, optionId, userId);
            return hadPriorVote ? VoteResult.SWITCHED : VoteResult.ADDED;
        }

        pollRepository.addVote(pollId, optionId, userId);
        return VoteResult.ADDED;
    }

    /** The hub's side of {@link #toggleVote}: the member's whole answer in one write, so the poll never shows a half-made switch. */
    private VoteResult toggleOnHub(PollSession session, long optionId, long userId) {
        java.util.Set<Long> mine = new java.util.LinkedHashSet<>(pollRepository.getUserVotes(session.pollId(), userId));
        VoteResult result;
        if (mine.remove(optionId)) {
            result = VoteResult.REMOVED;
        } else if (!session.multipleVotes()) {
            result = mine.isEmpty() ? VoteResult.ADDED : VoteResult.SWITCHED;
            mine = java.util.Set.of(optionId);
        } else {
            mine.add(optionId);
            result = VoteResult.ADDED;
        }
        pollRepository.setVotes(session.pollId(), userId, mine);
        return result;
    }

    /** Polls live on the RuneClans hub right now ({@code hub-community}): the hub closes timed polls itself. */
    public boolean onHub() {
        return pollRepository.hub();
    }

    /** Forgets a poll this process has cached (the hub feed said it changed or went), so the next look reads it again. */
    public void forget(long pollId) {
        activePollsById.remove(pollId);
        optionsByPollId.remove(pollId);
        recentMisses.remove(pollId);
    }

    // --- Message updates ---

    public void updateMessage(Guild guild, long pollId) {
        PollSession found = getSessionById(pollId);
        if (found == null) return;
        PollSession session = withPostedMessage(found);
        if (session.messageId() == null) return;

        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, session.channelId());
        if (channel == null) return;

        List<PollOption> options = getOptions(pollId);
        Map<Long, Integer> counts = pollRepository.getVoteCounts(pollId);
        Map<Long, List<Long>> voters = session.anonymous() ? Map.of() : pollRepository.getVotersByOption(pollId);

        channel.retrieveMessageById(session.messageId()).queue(
                msg -> replaceContent(msg, PollView.build(session, options, counts, voters, false)),
                err -> log.warn("Failed to retrieve message for poll {}", pollId));
    }

    /** Swaps a poll message's whole contents — components and the bar images they carry. */
    private void replaceContent(Message message, Container container) {
        message.editMessageComponents(List.of(container)).useComponentsV2(true).queue(
                success -> {}, error -> log.warn("Failed to update poll message {}", message.getIdLong(), error));
    }

    // --- Closing ---

    public void closePoll(Guild guild, long pollId) {
        PollSession session = getSessionById(pollId);
        if (session == null) return;

        pollRepository.closePoll(pollId);
        showClosed(guild, session);
    }

    /**
     * Shows a poll as ended on its message and drops it from the cache, without closing it in the store: the hub closed it already (its
     * end time, or the website), and the feed said so.
     */
    public void showClosed(Guild guild, long pollId) {
        PollSession session = activePollsById.get(pollId);
        if (session == null) session = pollRepository.getPollById(pollId);
        if (session == null) {
            forget(pollId);
            return;
        }
        showClosed(guild, withPostedMessage(session));
    }

    private void showClosed(Guild guild, PollSession session) {
        long pollId = session.pollId();
        if (session.messageId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, session.channelId());
            if (channel != null) {
                List<PollOption> options = getOptions(pollId);
                Map<Long, Integer> counts = pollRepository.getVoteCounts(pollId);
                Map<Long, List<Long>> voters = session.anonymous() ? Map.of() : pollRepository.getVotersByOption(pollId);

                PollSession closed = new PollSession(pollId, session.guildId(), session.channelId(),
                        session.messageId(), session.title(), session.anonymous(), session.multipleVotes(), "CLOSED", session.createdByUserId());

                channel.retrieveMessageById(session.messageId()).queue(
                        msg -> replaceContent(msg, PollView.build(closed, options, counts, voters, true)),
                        err -> log.warn("Failed to retrieve message to close poll {}", pollId));
            }
        }

        activePollsById.remove(pollId);
        optionsByPollId.remove(pollId);
        log.info("Closed poll {} '{}'", pollId, session.title());
    }

    // --- Results DM ---

    public String buildResultsSummary(long pollId) {
        PollSession session = activePollsById.get(pollId);
        if (session == null) session = pollRepository.getPollById(pollId); // an ended poll is no longer cached
        if (session == null) return "Poll not found.";

        List<PollOption> options = getOptions(pollId);
        Map<Long, Integer> counts = pollRepository.getVoteCounts(pollId);
        Map<Long, List<Long>> voters = pollRepository.getVotersByOption(pollId);

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("**📊 Full Results: ").append(session.title()).append("**\n");
        sb.append("Total votes: **").append(total).append("**\n\n");

        for (PollOption option : options) {
            int votes = counts.getOrDefault(option.optionId(), 0);
            double pct = total > 0 ? votes * 100.0 / total : 0.0;
            sb.append(PollView.NUMBER_EMOJIS[option.optionNumber() - 1]).append(" **").append(option.label()).append("**")
              .append(" — ").append(votes).append(votes == 1 ? " vote" : " votes")
              .append(String.format(" (%.1f%%)", pct)).append("\n");

            List<Long> voterIds = voters.getOrDefault(option.optionId(), List.of());
            if (voterIds.isEmpty()) {
                sb.append("*No votes*\n");
            } else {
                for (long uid : voterIds) sb.append("• <@").append(uid).append(">\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }
}
