package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.commands.signup.SignupPanelType;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.commands.signup.SignupSession;
import com.runeclans.bot.commands.signup.SignupType;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobRepository;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.RoleAction;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The dashboard's signup sheet as an outbox job (bug hunt B-112): what it makes, and what it takes down again when Discord says no. */
class SignupJobsTest {
    private static final long GUILD = 1L, ACTOR = 9L, PUBLIC = 3L, ADMIN = 4L, SIGNUP = 42L, ROLE = 77L;

    private JDA jda;
    private Guild guild;
    private SignupService signups;
    private SiteCache cache;
    private DiscordJobRepository repository;
    private SignupJobs jobs;
    private TextChannel publicChannel, adminChannel;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getIdLong()).thenReturn(GUILD);
        signups = mock(SignupService.class);
        cache = mock(SiteCache.class);
        repository = mock(DiscordJobRepository.class);
        when(repository.savePayload(anyLong(), any())).thenReturn(true);
        jobs = new SignupJobs(signups, cache, repository);
        publicChannel = channel(PUBLIC, "signups");
        adminChannel = channel(ADMIN, "staff");
        when(signups.storeSignup(anyLong(), any(), anyString(), any(), any(), any(), anyLong(), any())).thenReturn(SIGNUP);
        when(signups.getSessionById(SIGNUP)).thenReturn(new SignupSession(SIGNUP, GUILD, "Boss team", null, 10, SignupType.QUEUE, null, null));
    }

    private TextChannel channel(long id, String name) {
        TextChannel channel = mock(TextChannel.class);
        when(channel.canTalk()).thenReturn(true);
        when(channel.getId()).thenReturn(Long.toString(id));
        when(channel.getIdLong()).thenReturn(id);
        when(channel.getName()).thenReturn(name);
        when(guild.getTextChannelById(id)).thenReturn(channel);
        when(guild.getChannelById(TextChannel.class, id)).thenReturn(channel);
        return channel;
    }

    private static DiscordJob job(DataObject payload, int waits) {
        return new DiscordJob(5L, SignupJobs.CREATE, GUILD, ACTOR, null, payload, DiscordJob.State.RUNNING, waits + 1, waits, null, null, null, null, null);
    }

    private static DataObject queue() {
        return SignupJobs.createPayload(SignupType.QUEUE, PUBLIC, ADMIN, "Boss team", "Be ready", 10, null);
    }

    private static Message message(String id) {
        Message message = mock(Message.class);
        when(message.getId()).thenReturn(id);
        when(message.getJumpUrl()).thenReturn("https://discord.com/channels/1/3/" + id);
        return message;
    }

    @SuppressWarnings("unchecked")
    private void panelPosts(SignupPanelType panel, Object outcome) {
        when(signups.postPanel(eq(SIGNUP), any(), eq(panel), any())).thenAnswer(call -> {
            Function<RestAction<Message>, Message> send = call.getArgument(3);
            RestAction<Message> action = mock(RestAction.class);
            if (outcome instanceof RuntimeException e) when(action.complete(false)).thenThrow(e);
            else when(action.complete(false)).thenReturn((Message) outcome);
            return send.apply(action);
        });
    }

    private static ErrorResponseException missingPermissions() {
        ErrorResponseException refused = mock(ErrorResponseException.class);
        when(refused.isServerError()).thenReturn(false);
        when(refused.getErrorResponse()).thenReturn(ErrorResponse.MISSING_PERMISSIONS);
        return refused;
    }

    @Test
    void aSheetPostIsNeverRepeated() {
        assertFalse(jobs.idempotent(SignupJobs.CREATE));
    }

    @Test
    void aQueueSheetIsStoredThenBothPanelsPosted() {
        panelPosts(SignupPanelType.PUBLIC, message("100"));
        panelPosts(SignupPanelType.ADMIN, message("101"));

        DataObject result = jobs.run(jda, job(queue(), 0));

        verify(signups).storeSignup(GUILD, SignupType.QUEUE, "Boss team", "Be ready", null, 10, ACTOR, null);
        verify(signups).postPanel(eq(SIGNUP), eq(publicChannel), eq(SignupPanelType.PUBLIC), any());
        verify(signups).postPanel(eq(SIGNUP), eq(adminChannel), eq(SignupPanelType.ADMIN), any());
        assertEquals("42", result.getString("signupId"));
        assertEquals("100", result.getString("messageId"));
        assertTrue(result.getBoolean("adminPanel"));
        verify(cache).invalidate("signups");
    }

    @Test
    void aGoneChannelMakesNoSheet() {
        when(guild.getTextChannelById(ADMIN)).thenReturn(null);
        DiscordJobs.Refused refused = assertThrows(DiscordJobs.Refused.class, () -> jobs.run(jda, job(queue(), 0)));
        assertEquals("That channel no longer exists.", refused.getMessage());
        verify(signups, never()).storeSignup(anyLong(), any(), anyString(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void aRefusedPublicPanelTakesTheSheetDownAgain() {
        panelPosts(SignupPanelType.PUBLIC, missingPermissions());
        assertThrows(ErrorResponseException.class, () -> jobs.run(jda, job(queue(), 0)));
        verify(signups).discard(SIGNUP);
        verify(signups, never()).postPanel(anyLong(), eq(adminChannel), eq(SignupPanelType.ADMIN), any());
    }

    @Test
    void anUnclearFailureLeavesTheSheetBecauseItMayBeLive() {
        panelPosts(SignupPanelType.PUBLIC, new IllegalStateException("socket closed"));
        assertThrows(IllegalStateException.class, () -> jobs.run(jda, job(queue(), 0)));
        verify(signups, never()).discard(anyLong());
    }

    @Test
    void aRateLimitKeepsTheSheetAndTheNextTryPostsThatSameSheet() {
        panelPosts(SignupPanelType.PUBLIC, new DiscordJobs.RetryLater(Duration.ofSeconds(2), "wait"));
        assertThrows(DiscordJobs.RetryLater.class, () -> jobs.run(jda, job(queue(), 0)));
        verify(signups, never()).discard(anyLong());
        ArgumentCaptor<DataObject> saved = ArgumentCaptor.forClass(DataObject.class);
        verify(repository).savePayload(eq(5L), saved.capture());
        assertEquals("42", saved.getValue().getString(SignupJobs.SIGNUP_ID));

        // the next try: same sheet, no second store
        panelPosts(SignupPanelType.PUBLIC, message("100"));
        panelPosts(SignupPanelType.ADMIN, message("101"));
        jobs.run(jda, job(saved.getValue(), 1));
        verify(signups, times(1)).storeSignup(anyLong(), any(), anyString(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void aPublicPanelAlreadyPostedIsNotPostedAgainWhenTheAdminPanelHadToWait() {
        when(signups.hasPanel(SIGNUP, SignupPanelType.PUBLIC)).thenReturn(true);
        panelPosts(SignupPanelType.ADMIN, message("101"));
        DataObject kept = DataObject.fromJson(queue().toJson()).put(SignupJobs.SIGNUP_ID, "42");
        DataObject result = jobs.run(jda, job(kept, 1));
        verify(signups, never()).postPanel(anyLong(), any(), eq(SignupPanelType.PUBLIC), any());
        assertTrue(result.getBoolean("adminPanel"));
    }

    @Test
    void aRefusedAdminPanelLeavesTheLiveSheetAndSaysSo() {
        panelPosts(SignupPanelType.PUBLIC, message("100"));
        panelPosts(SignupPanelType.ADMIN, missingPermissions());
        DataObject result = jobs.run(jda, job(queue(), 0));
        assertFalse(result.getBoolean("adminPanel"));
        verify(signups, never()).discard(anyLong());
    }

    @Test
    void aSavedPayloadThatFailsUndoesTheStoredSheet() {
        when(repository.savePayload(anyLong(), any())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> jobs.run(jda, job(queue(), 0)));
        verify(signups).discard(SIGNUP);
        verify(signups, never()).postPanel(anyLong(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aGroupSheetMakesItsRoleFirstAndTakesItDownWhenThePostIsRefused() {
        RoleAction create = mock(RoleAction.class);
        when(guild.createRole()).thenReturn(create);
        when(create.setName(anyString())).thenReturn(create);
        when(create.setMentionable(anyBoolean())).thenReturn(create);
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(ROLE);
        try {
            when(create.complete(false)).thenReturn(role);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        when(guild.getRoleById(ROLE)).thenReturn(role);
        AuditableRestAction<Void> delete = mock(AuditableRestAction.class);
        when(role.delete()).thenReturn(delete);
        panelPosts(SignupPanelType.PUBLIC, missingPermissions());

        DataObject group = SignupJobs.createPayload(SignupType.GROUP, PUBLIC, ADMIN, "Raid group", null, null, null);
        assertThrows(ErrorResponseException.class, () -> jobs.run(jda, job(group, 0)));

        verify(signups).storeSignup(GUILD, SignupType.GROUP, "Raid group", null, null, null, ACTOR, ROLE);
        verify(signups).discard(SIGNUP);
        verify(role).delete();
    }

    // ---------- the API queues the sheet instead of posting it on the web request ----------

    @Test
    void theDashboardQueuesASheetAndReturnsTheJob() {
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        CommunityAdminApi api = new CommunityAdminApi(mock(PollService.class), signups, mock(SiteStatsRepository.class), mock(PlayerLinkRepository.class),
                mock(Debouncer.class), cache, outbox);
        Member actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(ACTOR);
        when(actor.getId()).thenReturn(Long.toString(ACTOR));
        DiscordJob queued = new DiscordJob(6L, SignupJobs.CREATE, GUILD, ACTOR, null, DataObject.empty(), DiscordJob.State.QUEUED, 0, 0, null, null, null, null, null);
        when(outbox.enqueue(eq(SignupJobs.CREATE), eq(GUILD), eq(ACTOR), isNull(), any())).thenReturn(queued);

        DataObject answer = api.createSignup(guild, actor, DataObject.empty().put("type", "submission").put("title", "Screenshots")
                .put("signupChannelId", Long.toString(PUBLIC)).put("adminChannelId", Long.toString(ADMIN)).put("max", 5)
                .put("fields", DataArray.empty().add(DataObject.empty().put("label", "Proof").put("type", "TEXT"))));

        assertTrue(answer.getBoolean("queued"));
        assertEquals("6", answer.getObject("job").getString("id"));
        ArgumentCaptor<DataObject> payload = ArgumentCaptor.forClass(DataObject.class);
        verify(outbox).enqueue(eq(SignupJobs.CREATE), eq(GUILD), eq(ACTOR), isNull(), payload.capture());
        assertEquals("SUBMISSION", payload.getValue().getString("type"));
        assertEquals(5, payload.getValue().getInt("max"));
        assertTrue(payload.getValue().getString("fields").contains("Proof"));
        verify(signups, never()).createSubmissionSession(any(), any(), any(), anyString(), any(), any(), anyLong());
        verify(signups, never()).storeSignup(anyLong(), any(), anyString(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void theUsualRulesStillRefuseABadSheetBeforeAnythingIsQueued() {
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        CommunityAdminApi api = new CommunityAdminApi(mock(PollService.class), signups, mock(SiteStatsRepository.class), mock(PlayerLinkRepository.class),
                mock(Debouncer.class), cache, outbox);
        Member actor = mock(Member.class);
        assertThrows(TicketAdminApi.ApiError.class, () -> api.createSignup(guild, actor, DataObject.empty().put("type", "SUBMISSION").put("title", "No questions")
                .put("signupChannelId", Long.toString(PUBLIC)).put("adminChannelId", Long.toString(ADMIN))));
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void certainlyNotSentCoversRefusalsButNotTimeouts() {
        assertTrue(DiscordJobs.certainlyNotSent(missingPermissions()));
        assertTrue(DiscordJobs.certainlyNotSent(new DiscordJobs.Refused("no")));
        assertFalse(DiscordJobs.certainlyNotSent(new IllegalStateException("timeout")));
        assertSame(DiscordJobs.Refused.class, new DiscordJobs.Refused("x").getClass());
    }
}
