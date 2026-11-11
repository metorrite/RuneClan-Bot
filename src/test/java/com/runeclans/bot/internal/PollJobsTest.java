package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        jobs = new PollJobs(polls, community, cache);
    }

    private static DiscordJob job(DataObject payload) {
        return new DiscordJob(5L, PollJobs.CREATE, GUILD, ACTOR, null, payload, DiscordJob.State.RUNNING, 1, 0, null, null, null, null, null);
    }

    @Test
    void aPollPostIsNeverRepeated() {
        assertFalse(jobs.idempotent(PollJobs.CREATE), "a second run would make a second poll");
    }

    @Test
    void aGoneChannelMakesNoPoll() {
        DiscordJobs.Refused refused = assertThrows(DiscordJobs.Refused.class,
                () -> jobs.run(jda, job(PollJobs.createPayload(CHANNEL, "Raid?", false, false, List.of("Yes", "No"), null))));
        assertEquals("That channel no longer exists.", refused.getMessage());
        verify(polls, never()).createPollPosted(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any(), any());
    }

    @Test
    void aChannelTheBotCanNoLongerPostInMakesNoPoll() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(false);
        when(channel.getName()).thenReturn("raids");
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        assertThrows(DiscordJobs.Refused.class, () -> jobs.run(jda, job(PollJobs.createPayload(CHANNEL, "Raid?", false, false, List.of("Yes", "No"), null))));
        verify(polls, never()).createPollPosted(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any(), any());
    }

    @Test
    void aPostedPollOnTheBotsTablesIsScheduledToCloseAtTheTimeAskedFor() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(true);
        when(channel.getId()).thenReturn(Long.toString(CHANNEL));
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        Message message = mock(Message.class);
        when(message.getId()).thenReturn("77");
        when(message.getJumpUrl()).thenReturn("https://discord.com/channels/1/3/77");
        Instant closesAt = Instant.parse("2026-11-12T18:00:00Z");
        when(polls.createPollPosted(eq(guild), eq(channel), eq("Raid?"), eq(false), eq(true), eq(List.of("Yes", "No")), eq(ACTOR), eq(closesAt), any()))
                .thenAnswer(call -> {
                    java.util.function.Function<net.dv8tion.jda.api.requests.RestAction<Message>, Message> send = call.getArgument(8);
                    @SuppressWarnings("unchecked")
                    net.dv8tion.jda.api.requests.RestAction<Message> action = mock(net.dv8tion.jda.api.requests.RestAction.class);
                    when(action.complete(false)).thenReturn(message);
                    send.apply(action);
                    return 42L;
                });

        DataObject result = jobs.run(jda, job(PollJobs.createPayload(CHANNEL, "Raid?", false, true, List.of("Yes", "No"), closesAt)));

        assertEquals("42", result.getString("pollId"));
        assertEquals("77", result.getString("messageId"));
        verify(community).scheduleClose(42L, OffsetDateTime.ofInstant(closesAt, ZoneOffset.UTC));
        verify(cache).invalidate("polls");
    }

    @Test
    void aFailedPostFailsTheJobAndSchedulesNothing() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        when(polls.createPollPosted(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("Discord said no"));

        assertThrows(IllegalStateException.class, () -> jobs.run(jda, job(PollJobs.createPayload(CHANNEL, "Raid?", false, false, List.of("Yes", "No"),
                Instant.parse("2026-11-12T18:00:00Z")))));
        verify(community, never()).scheduleClose(anyLong(), any());
    }

    @Test
    void aCloseThatCantBeScheduledStillReportsThePost() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        Message message = mock(Message.class);
        when(message.getId()).thenReturn("78");
        when(polls.createPollPosted(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any(), any())).thenAnswer(call -> {
            java.util.function.Function<net.dv8tion.jda.api.requests.RestAction<Message>, Message> send = call.getArgument(8);
            @SuppressWarnings("unchecked")
            net.dv8tion.jda.api.requests.RestAction<Message> action = mock(net.dv8tion.jda.api.requests.RestAction.class);
            when(action.complete(false)).thenReturn(message);
            send.apply(action);
            return 44L;
        });
        org.mockito.Mockito.doThrow(new RuntimeException("database hiccup")).when(community).scheduleClose(anyLong(), any());

        DataObject result = jobs.run(jda, job(PollJobs.createPayload(CHANNEL, "Raid?", false, false, List.of("Yes", "No"), Instant.parse("2026-11-12T18:00:00Z"))));

        assertEquals("44", result.getString("pollId"));
    }

    @Test
    void aPollOnTheHubIsClosedByTheHub() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        when(polls.onHub()).thenReturn(true);
        Message message = mock(Message.class);
        when(polls.createPollPosted(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any(), any())).thenAnswer(call -> {
            java.util.function.Function<net.dv8tion.jda.api.requests.RestAction<Message>, Message> send = call.getArgument(8);
            @SuppressWarnings("unchecked")
            net.dv8tion.jda.api.requests.RestAction<Message> action = mock(net.dv8tion.jda.api.requests.RestAction.class);
            when(action.complete(false)).thenReturn(message);
            send.apply(action);
            return 43L;
        });

        jobs.run(jda, job(PollJobs.createPayload(CHANNEL, "Raid?", false, false, List.of("Yes", "No"), Instant.parse("2026-11-12T18:00:00Z"))));

        verify(community, never()).scheduleClose(anyLong(), any());
    }
}
