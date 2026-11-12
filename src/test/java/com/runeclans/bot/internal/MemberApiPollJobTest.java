package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.commands.runescape.RsInteractionListener;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.member.HubMemberPrivacy;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.permission.MemberAccess;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A poll a member starts on the website is queued as the same outbox job as a dashboard poll, never posted on the web request. Fake ids are built at run time. */
class MemberApiPollJobTest {
    private static final long GUILD = Long.parseLong("5".repeat(17) + "3");
    private static final long USER = Long.parseLong("4".repeat(17) + "3");
    private static final long CHANNEL = Long.parseLong("6".repeat(17) + "3");

    private PollService polls;
    private DiscordOutbox outbox;
    private MemberApi api;
    private Guild guild;

    @BeforeEach
    void setUp() {
        polls = mock(PollService.class);
        outbox = mock(DiscordOutbox.class);
        MemberAccess access = mock(MemberAccess.class);
        CommunitySettings community = mock(CommunitySettings.class);
        api = new MemberApi(mock(MemberProfileRepository.class), mock(PlayerLinkRepository.class), polls, mock(SignupService.class), access, mock(Debouncer.class),
                new SiteCache(), community, mock(SiteStatsRepository.class), mock(RsInteractionListener.class), mock(MemberEventsApi.class), mock(HighlightMemberApi.class),
                mock(ChatLevelMemberApi.class), outbox, mock(SkillWeekMemberApi.class), mock(HubMemberPrivacy.class));

        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        Member member = mock(Member.class);
        when(guild.getMemberById(USER)).thenReturn(member);
        when(access.isMemberTier(guild, member)).thenReturn(true);
        when(community.pollChannel(GUILD)).thenReturn(CHANNEL);
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.getIdLong()).thenReturn(CHANNEL);
        when(channel.getName()).thenReturn("polls");
        when(channel.canTalk()).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
    }

    private DataObject post(String body) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/internal/me/polls"));
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(out);
        int[] status = {-1};
        doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(any(Integer.class), any(Long.class));
        api.handle(exchange, guild);
        assertEquals(200, status[0], out.toString(StandardCharsets.UTF_8));
        return DataObject.fromJson(out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aMemberPollIsQueuedWithItsEndTimeAndTheJobIsAnswered() throws Exception {
        DiscordJob job = new DiscordJob(7L, PollJobs.CREATE, GUILD, USER, null, DataObject.empty(), DiscordJob.State.QUEUED, 0, 0, null, null, null, null, null);
        when(outbox.enqueue(eq(PollJobs.CREATE), eq(GUILD), eq(USER), isNull(), any())).thenReturn(job);
        Instant before = Instant.now();

        DataObject answer = post("{\"userId\":\"" + USER + "\",\"title\":\"Raid night?\",\"options\":[\"Yes\",\"No\"],\"multiple\":true,\"durationHours\":24}");

        assertTrue(answer.getBoolean("created"));
        assertTrue(answer.getBoolean("queued"));
        assertEquals("polls", answer.getString("channel"));
        assertEquals("7", answer.getObject("job").getString("id"));
        ArgumentCaptor<DataObject> payload = ArgumentCaptor.forClass(DataObject.class);
        verify(outbox).enqueue(eq(PollJobs.CREATE), eq(GUILD), eq(USER), isNull(), payload.capture());
        assertEquals(Long.toString(CHANNEL), payload.getValue().getString("channelId"));
        assertEquals("Raid night?", payload.getValue().getString("title"));
        assertTrue(payload.getValue().getBoolean("multiple"));
        assertEquals(MemberApi.MAX_WEBSITE_POLLS, payload.getValue().getInt(PollJobs.OWNER_LIMIT), "the job checks the member's limit again (B-117)");
        assertEquals(3, MemberApi.MAX_WEBSITE_POLLS);
        Instant closesAt = Instant.parse(payload.getValue().getString("closesAt"));
        assertTrue(!closesAt.isBefore(before.plus(Duration.ofHours(24))) && closesAt.isBefore(Instant.now().plus(Duration.ofHours(24)).plusSeconds(1)),
                "the end time is fixed when the member asks");
        verify(polls, never()).createPoll(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong());
        verify(polls, never()).createPoll(any(), any(), anyString(), anyBoolean(), anyBoolean(), any(), anyLong(), any());
    }
}
