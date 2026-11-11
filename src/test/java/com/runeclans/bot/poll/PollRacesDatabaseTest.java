package com.runeclans.bot.poll;

import com.runeclans.bot.commands.poll.PollOption;
import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Polls on the bot's own tables: quick clicks on a one-answer poll leave one answer (bug hunt B-49), and a dashboard poll whose post
 * Discord refuses is not left open without a message (B-77). Skipped without a local Postgres.
 */
class PollRacesDatabaseTest {
    private static final long GUILD = 987654321012381L;
    private static final long CHANNEL = 987654321012382L;
    private static final long OWNER = 61L;
    private static final long VOTER = 62L;

    private ConnectionSupplier db;
    private PollRepository polls;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        polls = new PollRepository(db, new PollDatabaseInitializer(db));
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            for (String sql : new String[]{
                    "DELETE FROM younglings.poll_vote WHERE poll_id IN (SELECT poll_id FROM younglings.poll WHERE guild_id = ?)",
                    "DELETE FROM younglings.poll_option WHERE poll_id IN (SELECT poll_id FROM younglings.poll WHERE guild_id = ?)",
                    "DELETE FROM younglings.poll WHERE guild_id = ?"}) {
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, GUILD);
                    s.executeUpdate();
                }
            }
        }
    }

    private Guild guild() {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        return guild;
    }

    private GuildMessageChannel channel() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class, Mockito.RETURNS_DEEP_STUBS);
        when(channel.getIdLong()).thenReturn(CHANNEL);
        return channel;
    }

    private static Message message(long id) {
        Message message = mock(Message.class);
        when(message.getIdLong()).thenReturn(id);
        return message;
    }

    @RepeatedTest(5)
    void quickClicksOnAOneAnswerPollLeaveOneAnswer() throws Exception {
        PollService service = new PollService(polls);
        List<String> labels = List.of("A", "B", "C", "D", "E");
        long pollId = service.createPollPosted(guild(), channel(), "Pick one", false, false, labels, OWNER, null, action -> message(777L));
        List<PollOption> options = service.getOptions(pollId);

        ExecutorService pool = Executors.newFixedThreadPool(options.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> clicks = new ArrayList<>();
        for (PollOption option : options) {
            clicks.add(pool.submit(() -> {
                go.await();
                return service.toggleVote(pollId, option.optionId(), VOTER);
            }));
        }
        go.countDown();
        for (Future<?> click : clicks) click.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, polls.getUserVotes(pollId, VOTER).size(), "a one-answer poll keeps one answer per member");
        assertEquals(1, polls.getVoteCounts(pollId).values().stream().mapToInt(Integer::intValue).sum());
    }

    private long activePolls() {
        return polls.getActivePolls(GUILD).size();
    }

    private RuntimeException failPost(PollService service, RuntimeException failure) {
        return assertThrows(RuntimeException.class, () -> service.createPollPosted(guild(), channel(), "Raid night?", false, false,
                List.of("Yes", "No"), OWNER, null, action -> {
                    throw failure;
                }));
    }

    @Test
    void aPollWhosePostDiscordRefusesIsClosedAgain() {
        PollService service = new PollService(polls);
        ErrorResponseException refused = mock(ErrorResponseException.class);
        when(refused.isServerError()).thenReturn(false);

        assertEquals(refused, failPost(service, refused), "the post's own error goes on to the job");
        assertEquals(0, activePolls(), "no open poll is left behind");

        failPost(service, new DiscordJobs.Refused("The bot can't post there"));
        failPost(service, new DiscordJobs.RetryLater(java.time.Duration.ofSeconds(5), "rate limited"));
        assertEquals(0, activePolls(), "a refusal or a rate limit before sending leaves nothing open");
    }

    @Test
    void aPollWhosePostMayHaveGoneOutStaysOpen() {
        PollService service = new PollService(polls);
        ErrorResponseException serverError = mock(ErrorResponseException.class);
        when(serverError.isServerError()).thenReturn(true);

        failPost(service, serverError);
        failPost(service, new IllegalStateException("timed out"));

        assertEquals(2, activePolls(), "after an unclear failure the message may be live, so its buttons must keep working");
    }

    @Test
    void aPostedPollWhoseMessageIdCantBeSavedStillCounts() {
        PollStore broken = Mockito.spy(polls);
        Mockito.doThrow(new RuntimeException("database hiccup")).when(broken).saveMessageId(Mockito.anyLong(), Mockito.anyLong());
        PollService service = new PollService(broken);

        long pollId = service.createPollPosted(guild(), channel(), "Raid night?", false, false, List.of("Yes", "No"), OWNER, null, action -> message(999L));

        assertEquals("ACTIVE", polls.getPollById(pollId).status(), "the post went out: the poll stays open");
    }

    @Test
    void aPostedPollKeepsItsMessage() {
        PollService service = new PollService(polls);
        long pollId = service.createPollPosted(guild(), channel(), "Raid night?", false, false, List.of("Yes", "No"), OWNER, null, action -> message(888L));

        assertEquals(888L, polls.getPollById(pollId).messageId());
        assertEquals(888L, service.getSessionById(pollId).messageId());
        assertEquals("ACTIVE", polls.getPollById(pollId).status());
    }

    @Test
    void aPostThatHasToWaitKeepsItsPollForTheNextTry() {
        PollService service = new PollService(polls);
        long pollId = service.storePoll(guild(), channel(), "Raid night?", false, false, List.of("Yes", "No"), OWNER, null);

        assertThrows(DiscordJobs.RetryLater.class, () -> service.postStored(pollId, channel(), action -> {
            throw new DiscordJobs.RetryLater(java.time.Duration.ofSeconds(5), "rate limited");
        }, true));
        assertEquals(1, activePolls(), "the poll waits for the next try");

        service.postStored(pollId, channel(), action -> message(555L), true);

        assertEquals(555L, polls.getPollById(pollId).messageId());
        assertEquals(1, activePolls(), "waiting left no extra poll behind");
        assertEquals(1, pollRows(), "and no closed empty one either");
    }

    @Test
    void aPollIsNeverPostedTwice() {
        PollService service = new PollService(polls);
        long pollId = service.storePoll(guild(), channel(), "Raid night?", false, false, List.of("Yes", "No"), OWNER, null);
        service.postStored(pollId, channel(), action -> message(556L), true);

        DiscordJobs.Refused again = assertThrows(DiscordJobs.Refused.class, () -> service.postStored(pollId, channel(), action -> message(557L), true));
        assertEquals("That poll is already posted.", again.getMessage());
        assertEquals(556L, polls.getPollById(pollId).messageId());
    }

    @Test
    void aClosedPollIsNeverPosted() {
        PollService service = new PollService(polls);
        long pollId = service.storePoll(guild(), channel(), "Raid night?", false, false, List.of("Yes", "No"), OWNER, null);
        service.discard(pollId);

        assertThrows(DiscordJobs.Refused.class, () -> service.postStored(pollId, channel(), action -> message(558L), true));
        assertEquals(0, activePolls());
    }

    @Test
    void aWaitThatEndsTheJobClosesThePoll() {
        PollService service = new PollService(polls);
        long pollId = service.storePoll(guild(), channel(), "Raid night?", false, false, List.of("Yes", "No"), OWNER, null);

        assertThrows(DiscordJobs.RetryLater.class, () -> service.postStored(pollId, channel(), action -> {
            throw new DiscordJobs.RetryLater(java.time.Duration.ofSeconds(5), "rate limited");
        }, false));
        assertEquals(0, activePolls(), "no try follows, so nothing is kept open");
    }

    private long pollRows() {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("SELECT count(*) FROM younglings.poll WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            try (java.sql.ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aPollIsClosedOnlyWhenTheStoreSaysSo() {
        PollService service = new PollService(polls);
        long pollId = service.storePoll(guild(), channel(), "Raid night?", false, false, List.of("Yes", "No"), OWNER, null);
        assertFalse(service.isClosed(pollId));
        service.discard(pollId);
        assertTrue(service.isClosed(pollId));

        PollStore broken = Mockito.spy(polls);
        Mockito.doThrow(new RuntimeException("database hiccup")).when(broken).getPollById(Mockito.anyLong());
        assertThrows(RuntimeException.class, () -> new PollService(broken).isClosed(pollId), "a failed read is never taken for a closed poll");
    }

    @Test
    void aPollMessagePingsNobody() {
        PollService service = new PollService(polls);
        GuildMessageChannel channel = channel();
        long pollId = service.storePoll(guild(), channel, "<@123> raid?", false, false, List.of("Yes", "No"), OWNER, null);

        service.postStored(pollId, channel, action -> message(559L), true);

        Mockito.verify(channel.sendMessageComponents(Mockito.anyList()).useComponentsV2(true)).setAllowedMentions(com.runeclans.bot.discord.Mentions.NONE);
    }
}
