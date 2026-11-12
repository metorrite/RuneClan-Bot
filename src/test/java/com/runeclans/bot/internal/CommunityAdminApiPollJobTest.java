package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A dashboard poll is queued as an outbox job and never posted on the web request (bug hunt B-77). */
class CommunityAdminApiPollJobTest {
    private static final long GUILD = 1L;
    private static final long ACTOR = 9L;
    private static final long CHANNEL = 3L;

    @Test
    void aDashboardPollIsQueuedAndTheJobTicketReturned() {
        PollService polls = mock(PollService.class);
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        CommunityAdminApi api = new CommunityAdminApi(polls, mock(SignupService.class), mock(SiteStatsRepository.class), mock(PlayerLinkRepository.class),
                mock(Debouncer.class), mock(SiteCache.class), outbox);
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(true);
        when(channel.getIdLong()).thenReturn(CHANNEL);
        when(channel.getName()).thenReturn("raids");
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        Member actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(ACTOR);
        when(actor.getId()).thenReturn(Long.toString(ACTOR));
        DiscordJob job = new DiscordJob(5L, PollJobs.CREATE, GUILD, ACTOR, null, DataObject.empty(), DiscordJob.State.QUEUED, 0, 0, null, null, null, null, null);
        when(outbox.enqueue(eq(PollJobs.CREATE), eq(GUILD), eq(ACTOR), isNull(), any())).thenReturn(job);

        Instant before = Instant.now();
        DataObject answer = api.createPoll(guild, actor, DataObject.empty().put("title", "Raid night?").put("channelId", Long.toString(CHANNEL))
                .put("options", DataArray.empty().add("Yes").add("No")).put("durationHours", 2).put("multiple", true));

        assertTrue(answer.getBoolean("queued"));
        assertEquals("5", answer.getObject("job").getString("id"));
        ArgumentCaptor<DataObject> payload = ArgumentCaptor.forClass(DataObject.class);
        verify(outbox).enqueue(eq(PollJobs.CREATE), eq(GUILD), eq(ACTOR), isNull(), payload.capture());
        assertEquals("Raid night?", payload.getValue().getString("title"));
        assertEquals(2, payload.getValue().getArray("options").length());
        assertTrue(payload.getValue().getBoolean("multiple"));
        assertTrue(payload.getValue().isNull(PollJobs.OWNER_LIMIT), "a dashboard poll has no per-person limit");
        Instant closesAt = Instant.parse(payload.getValue().getString("closesAt"));
        assertTrue(!closesAt.isBefore(before.plus(Duration.ofHours(2))), "the end time is fixed when the poll is asked for");
        verify(polls, never()).createPoll(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(), any(), anyLong(), any());
    }
}
