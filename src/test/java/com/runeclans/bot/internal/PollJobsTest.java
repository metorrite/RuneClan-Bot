package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobRepository;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The dashboard's poll post as an outbox job (bug hunt B-77): what it does when it runs, and when it gives up without making a poll. */
class PollJobsTest {
    private static final long GUILD = 1L;
    private static final long ACTOR = 9L;
    private static final long CHANNEL = 3L;

    private JDA jda;
    private Guild guild;
    private PollService polls;
    private CommunitySettings community;
    private SiteCache cache;
    private DiscordJobRepository repository;
    private PollJobs jobs;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getIdLong()).thenReturn(GUILD);
        polls = mock(PollService.class);
        community = mock(CommunitySettings.class);
        cache = mock(SiteCache.class);
        repository = mock(DiscordJobRepository.class);
        jobs = new PollJobs(polls, community, cache, repository);
    }

    private static DiscordJob job(DataObject payload) {
        return job(payload, 0);
    }

    private static DiscordJob job(DataObject payload, int waits) {
        return new DiscordJob(5L, PollJobs.CREATE, GUILD, ACTOR, null, payload, DiscordJob.State.RUNNING, waits + 1, waits, null, null, null, null, null);
    }

    private static DataObject payload(Instant closesAt) {
        return PollJobs.createPayload(CHANNEL, "Raid?", false, false, List.of("Yes", "No"), closesAt);
    }

    private GuildMessageChannel postable() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(true);
        when(channel.getId()).thenReturn(Long.toString(CHANNEL));
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        return channel;
    }

    /** postStored answers a posted message, running the post through the outbox's send like the real one. */
    private void postsAs(Message message) {
        when(polls.postStored(anyLong(), any(), any(), anyBoolean())).thenAnswer(call -> {
            java.util.function.Function<net.dv8tion.jda.api.requests.RestAction<Message>, Message> send = call.getArgument(2);
            @SuppressWarnings("unchecked")
            net.dv8tion.jda.api.requests.RestAction<Message> action = mock(net.dv8tion.jda.api.requests.RestAction.class);
            when(action.complete(false)).thenReturn(message);
            return send.apply(action);
        });
    }

    private void neverStored() {
        verify(polls, never()).storePoll(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any());
    }

    @Test
    void aPollPostIsNeverRepeated() {
        assertFalse(jobs.idempotent(PollJobs.CREATE), "a second run would make a second poll");
    }

    @Test
    void aGoneChannelMakesNoPoll() {
        DiscordJobs.Refused refused = assertThrows(DiscordJobs.Refused.class, () -> jobs.run(jda, job(payload(null))));
        assertEquals("That channel no longer exists.", refused.getMessage());
        neverStored();
    }

    @Test
    void aChannelTheBotCanNoLongerPostInMakesNoPoll() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(false);
        when(channel.getName()).thenReturn("raids");
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        assertThrows(DiscordJobs.Refused.class, () -> jobs.run(jda, job(payload(null))));
        neverStored();
    }

    @Test
    void aPostedPollOnTheBotsTablesIsScheduledToCloseAtTheTimeAskedFor() {
        GuildMessageChannel channel = postable();
        Message message = mock(Message.class);
        when(message.getId()).thenReturn("77");
        when(message.getJumpUrl()).thenReturn("https://discord.com/channels/1/3/77");
        Instant closesAt = Instant.parse("2026-11-12T18:00:00Z");
        when(polls.storePoll(eq(guild), eq(channel), eq("Raid?"), eq(false), eq(true), eq(List.of("Yes", "No")), eq(ACTOR), eq(closesAt))).thenReturn(42L);
        postsAs(message);

        DataObject result = jobs.run(jda, job(PollJobs.createPayload(CHANNEL, "Raid?", false, true, List.of("Yes", "No"), closesAt)));

        assertEquals("42", result.getString("pollId"));
        assertEquals("77", result.getString("messageId"));
        verify(polls).postStored(eq(42L), eq(channel), any(), eq(true));
        verify(community).scheduleClose(42L, OffsetDateTime.ofInstant(closesAt, ZoneOffset.UTC));
        verify(cache).invalidate("polls");
    }

    @Test
    void theStoredPollIsNamedInTheJobBeforeItIsPosted() {
        GuildMessageChannel channel = postable();
        when(polls.storePoll(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any())).thenReturn(42L);
        when(polls.postStored(anyLong(), any(), any(), anyBoolean())).thenThrow(new DiscordJobs.RetryLater(java.time.Duration.ofSeconds(3), "wait"));

        assertThrows(DiscordJobs.RetryLater.class, () -> jobs.run(jda, job(payload(null))));

        ArgumentCaptor<DataObject> saved = ArgumentCaptor.forClass(DataObject.class);
        verify(repository).savePayload(eq(5L), saved.capture());
        assertEquals("42", saved.getValue().getString(PollJobs.POLL_ID));
        assertEquals("Raid?", saved.getValue().getString("title"), "the rest of the payload is kept");
        verify(polls).postStored(eq(42L), eq(channel), any(), eq(true));
        verify(polls, never()).discard(anyLong());
    }

    @Test
    void aTryAfterAWaitPostsThePollTheFirstTryStored() {
        GuildMessageChannel channel = postable();
        Message message = mock(Message.class);
        when(message.getId()).thenReturn("79");
        postsAs(message);

        DataObject result = jobs.run(jda, job(payload(null).put(PollJobs.POLL_ID, "42"), 1));

        assertEquals("42", result.getString("pollId"));
        neverStored();
        verify(repository, never()).savePayload(anyLong(), any());
        verify(polls).postStored(eq(42L), eq(channel), any(), eq(true));
    }

    @Test
    void theLastAllowedWaitClosesThePoll() {
        GuildMessageChannel channel = postable();
        when(polls.postStored(anyLong(), any(), any(), anyBoolean())).thenThrow(new DiscordJobs.RetryLater(java.time.Duration.ofSeconds(3), "wait"));

        assertThrows(DiscordJobs.RetryLater.class, () -> jobs.run(jda, job(payload(null).put(PollJobs.POLL_ID, "42"), DiscordJobs.MAX_RATE_LIMIT_WAITS)));

        // the job fails after this wait, so the poll must not be kept for a try that never comes
        verify(polls).postStored(eq(42L), eq(channel), any(), eq(false));
    }

    @Test
    void aKeptPollIsClosedWhenItsChannelIsGone() {
        assertThrows(DiscordJobs.Refused.class, () -> jobs.run(jda, job(payload(null).put(PollJobs.POLL_ID, "42"), 1)));
        verify(polls).discard(42L);
    }

    @Test
    void aKeptPollWaitsWhileTheServerIsUnavailable() {
        when(jda.getGuildById(GUILD)).thenReturn(null);
        when(jda.isUnavailable(GUILD)).thenReturn(true);
        assertThrows(DiscordJobs.RetryLater.class, () -> jobs.run(jda, job(payload(null).put(PollJobs.POLL_ID, "42"), 1)));
        verify(polls, never()).discard(anyLong());
    }

    @Test
    void aStoredPollTheJobCantNameIsClosed() {
        postable();
        when(polls.storePoll(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any())).thenReturn(42L);
        when(repository.savePayload(anyLong(), any())).thenThrow(new IllegalStateException("database hiccup"));

        assertThrows(IllegalStateException.class, () -> jobs.run(jda, job(payload(null))));

        verify(polls).discard(42L);
        verify(polls, never()).postStored(anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void aFailedPostFailsTheJobAndSchedulesNothing() {
        postable();
        when(polls.storePoll(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any())).thenReturn(42L);
        when(polls.postStored(anyLong(), any(), any(), anyBoolean())).thenThrow(new IllegalStateException("Discord said no"));

        assertThrows(IllegalStateException.class, () -> jobs.run(jda, job(payload(Instant.parse("2026-11-12T18:00:00Z")))));
        verify(community, never()).scheduleClose(anyLong(), any());
    }

    @Test
    void aCloseThatCantBeScheduledStillReportsThePost() {
        postable();
        Message message = mock(Message.class);
        when(message.getId()).thenReturn("78");
        when(polls.storePoll(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any())).thenReturn(44L);
        postsAs(message);
        org.mockito.Mockito.doThrow(new RuntimeException("database hiccup")).when(community).scheduleClose(anyLong(), any());

        DataObject result = jobs.run(jda, job(payload(Instant.parse("2026-11-12T18:00:00Z"))));

        assertEquals("44", result.getString("pollId"));
    }

    @Test
    void aPollOnTheHubIsClosedByTheHub() {
        postable();
        when(polls.onHub()).thenReturn(true);
        Message message = mock(Message.class);
        when(polls.storePoll(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any())).thenReturn(43L);
        postsAs(message);

        jobs.run(jda, job(payload(Instant.parse("2026-11-12T18:00:00Z"))));

        verify(community, never()).scheduleClose(anyLong(), any());
    }
}
