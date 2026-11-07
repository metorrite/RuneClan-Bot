package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.embedder.EmbedderRepository;
import com.runeclans.bot.embedder.EmbedderService;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventSpaceOps;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.giveaway.Giveaway;
import com.runeclans.bot.giveaway.GiveawayRepository;
import com.runeclans.bot.giveaway.GiveawayService;
import com.runeclans.bot.lockdown.LockdownService;
import com.runeclans.bot.rolepanel.RolePanelRepository;
import com.runeclans.bot.rolepanel.RolePanelService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The outbox jobs that took over the dashboard's remaining Discord writes (T-456 phase 2): what each does when it runs, and when it gives up. */
class OutboxPhase2JobsTest {
    private static final long GUILD = 1L;
    private static final long ACTOR = 9L;

    private JDA jda;
    private Guild guild;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn(Long.toString(GUILD));
    }

    private static DiscordJob job(String kind, DataObject payload) {
        return new DiscordJob(5L, kind, GUILD, ACTOR, null, payload, DiscordJob.State.RUNNING, 1, 0, null, null, null, null, null);
    }

    @Test
    void postingIsNeverRepeatedAfterAnUnclearFaultButTakingThingsDownIs() {
        DiscordJobHandler embedder = new EmbedderJobs(mock(EmbedderRepository.class), mock(EmbedderService.class));
        assertEquals(List.of(EmbedderJobs.POST, EmbedderJobs.EDIT, EmbedderJobs.DELETE), embedder.kinds());
        assertFalse(embedder.idempotent(EmbedderJobs.POST));
        assertTrue(embedder.idempotent(EmbedderJobs.EDIT));
        assertTrue(embedder.idempotent(EmbedderJobs.DELETE));

        DiscordJobHandler giveaways = new GiveawayJobs(mock(GiveawayRepository.class), mock(GiveawayService.class));
        assertFalse(giveaways.idempotent(GiveawayJobs.START));
        assertFalse(giveaways.idempotent(GiveawayJobs.END), "ending draws and announces winners");
        assertFalse(giveaways.idempotent(GiveawayJobs.REROLL));
        assertTrue(giveaways.idempotent(GiveawayJobs.DELETE));

        DiscordJobHandler panels = new RolePanelJobs(mock(RolePanelRepository.class), mock(RolePanelService.class));
        assertFalse(panels.idempotent(RolePanelJobs.POST));
        assertTrue(panels.idempotent(RolePanelJobs.TAKE_DOWN));

        DiscordJobHandler lockdown = new LockdownJobs(mock(LockdownService.class));
        assertFalse(lockdown.idempotent(LockdownJobs.LOCK));
        assertFalse(lockdown.idempotent(LockdownJobs.START));
        assertTrue(lockdown.idempotent(LockdownJobs.UNLOCK));
        assertTrue(lockdown.idempotent(LockdownJobs.END));

        assertFalse(new AnnounceJob().idempotent(AnnounceJob.KIND));
        assertTrue(new EventSpaceSyncJob(mock(HubEvents.class), mock(EventSpaceOps.class)).idempotent(EventSpaceSyncJob.KIND));
    }

    @Test
    void anAnnouncementPostsExactlyTheCheckedTextAndPings() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(guild.getChannelById(GuildMessageChannel.class, 20L)).thenReturn(channel);
        when(channel.canTalk()).thenReturn(true);
        when(channel.getId()).thenReturn("20");
        when(channel.getName()).thenReturn("news");
        MessageCreateAction action = mock(MessageCreateAction.class);
        when(channel.sendMessage("<@&30> Raid tonight")).thenReturn(action);
        when(action.setAllowedMentions(any())).thenReturn(action);
        when(action.mentionRoles(any(long[].class))).thenReturn(action);
        Message sent = mock(Message.class);
        when(sent.getId()).thenReturn("40");
        try {
            when(action.complete(false)).thenReturn(sent);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        DataObject result = new AnnounceJob().run(jda, job(AnnounceJob.KIND,
                AnnounceJob.payload(20L, "<@&30> Raid tonight", EnumSet.noneOf(Message.MentionType.class), List.of(30L))));

        assertEquals("40", result.getString("messageId"));
        verify(action).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
        verify(action).mentionRoles(new long[]{30L});
    }

    @Test
    void anAnnouncementForAChannelThatWentAwayEndsWithTheReason() {
        DiscordJobs.Refused refused = assertThrows(DiscordJobs.Refused.class, () -> new AnnounceJob().run(jda, job(AnnounceJob.KIND,
                AnnounceJob.payload(20L, "hi", EnumSet.noneOf(Message.MentionType.class), List.of()))));
        assertEquals("That channel no longer exists.", refused.getMessage());
    }

    @Test
    void anEmbedderPostForAnEntryThatIsAlreadyPostedOrDeletedIsRefused() {
        EmbedderRepository repository = mock(EmbedderRepository.class);
        EmbedderService service = mock(EmbedderService.class);
        EmbedderJobs jobs = new EmbedderJobs(repository, service);
        DataObject payload = EmbedderJobs.postPayload(5L, 20L, new EmbedderService.Author("Tester", null));

        assertThrows(DiscordJobs.Refused.class, () -> jobs.run(jda, job(EmbedderJobs.POST, payload)), "deleted on the website meanwhile");

        when(repository.get(GUILD, 5L)).thenReturn(new EmbedderRepository.Stored(5L, GUILD, "Rules", "{\"messageType\":\"MESSAGE\",\"content\":\"Hi\"}", 20L, 30L, ACTOR,
                OffsetDateTime.now(), OffsetDateTime.now()));
        assertThrows(DiscordJobs.Refused.class, () -> jobs.run(jda, job(EmbedderJobs.POST, payload)), "already posted");
        verify(service, never()).post(any(), any(), any(), anyLong());
    }

    @Test
    void anEmbedderPostMarksTheEntryPostedOnlyOnceDiscordHasIt() {
        EmbedderRepository repository = mock(EmbedderRepository.class);
        EmbedderService service = mock(EmbedderService.class);
        when(repository.get(GUILD, 5L)).thenReturn(new EmbedderRepository.Stored(5L, GUILD, "Rules", "{\"messageType\":\"MESSAGE\",\"content\":\"Hi\"}", null, null, ACTOR,
                OffsetDateTime.now(), null));
        when(service.post(eq(guild), any(), any(), eq(20L))).thenReturn(30L);

        DataObject result = new EmbedderJobs(repository, service).run(jda, job(EmbedderJobs.POST, EmbedderJobs.postPayload(5L, 20L, new EmbedderService.Author("Tester", null))));

        verify(repository).markPosted(GUILD, 5L, 20L, 30L);
        assertEquals("30", result.getString("messageId"));
    }

    @Test
    void aGiveawayTheServiceRefusesEndsWithItsReason() {
        GiveawayRepository repository = mock(GiveawayRepository.class);
        GiveawayService service = mock(GiveawayService.class);
        Giveaway g = mock(Giveaway.class);
        when(repository.get(GUILD, 3L)).thenReturn(Optional.of(g));
        when(service.end(guild, g)).thenThrow(new GiveawayService.GiveawayException("That giveaway has already ended."));

        DiscordJobs.Refused refused = assertThrows(DiscordJobs.Refused.class, () -> new GiveawayJobs(repository, service).run(jda, job(GiveawayJobs.END, GiveawayJobs.giveawayPayload(3L))));
        assertEquals("That giveaway has already ended.", refused.getMessage());
    }

    @Test
    void aRolePanelDeletedBeforeTheBotGotToItIsNotPosted() {
        RolePanelService service = mock(RolePanelService.class);
        RolePanelRepository repository = mock(RolePanelRepository.class);
        when(repository.get(GUILD, 4L)).thenReturn(Optional.empty());

        assertThrows(DiscordJobs.Refused.class, () -> new RolePanelJobs(repository, service).run(jda, job(RolePanelJobs.POST, RolePanelJobs.postPayload(4L, 20L))));
        verify(service, never()).post(any(), any(), anyLong());
    }

    @Test
    void lockOutcomesSayDoneWhenTheChannelIsAsAskedAndWhyOtherwise() {
        StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
        when(channel.getGuild()).thenReturn(guild);
        when(channel.getName()).thenReturn("general");

        assertEquals("done", LockdownJobs.outcome(LockdownService.Outcome.DONE, channel, true).getString("outcome"));
        assertEquals("already", LockdownJobs.outcome(LockdownService.Outcome.ALREADY, channel, true).getString("outcome"));
        assertEquals("not_locked", LockdownJobs.outcome(LockdownService.Outcome.NOT_LOCKED, channel, false).getString("outcome"));
        assertThrows(DiscordJobs.Refused.class, () -> LockdownJobs.outcome(LockdownService.Outcome.FAILED, channel, true));
        assertThrows(DiscordJobs.Refused.class, () -> LockdownJobs.outcome(LockdownService.Outcome.NO_PERMISSION, channel, false));
    }

    @Test
    void anEventRoleSyncWithChangesThatDidntGoThroughEndsAsFailedWithHowMany() {
        HubEvents service = mock(HubEvents.class);
        EventSpaceOps ops = mock(EventSpaceOps.class);
        when(ops.syncMembers(eq(guild), any())).thenReturn(2);

        DiscordJobs.Refused refused = assertThrows(DiscordJobs.Refused.class, () -> new EventSpaceSyncJob(service, ops).run(jda, job(EventSpaceSyncJob.KIND, EventSpaceSyncJob.payload(8L))));
        assertTrue(refused.getMessage().startsWith("2 role changes"));

        when(service.event(GUILD, 8L)).thenThrow(new EventProblem(404, "That event doesn't exist."));
        assertThrows(DiscordJobs.Refused.class, () -> new EventSpaceSyncJob(service, ops).run(jda, job(EventSpaceSyncJob.KIND, EventSpaceSyncJob.payload(8L))));
    }
}
