package com.runeclans.bot.runescape;

import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.tracking.TrackingEventRouter;
import com.runeclans.bot.tracking.TrackingGroup;
import com.runeclans.bot.tracking.TrackingIconCatalog;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import com.runeclans.bot.tracking.TrackingRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-1160: the bot's Discord side of the hub's name changes: admin decisions through the hub's routes, and the one rename line. */
class HubNameChangesTest {
    private static final long GUILD = 1L;
    private static final String CLAN = "11111111-1111-1111-1111-111111111111";
    private static final String ADMIN = "1".repeat(18);

    private RuneClansHubClient hub;
    private HubNameChanges service;

    @BeforeEach
    void setUp() {
        hub = mock(RuneClansHubClient.class);
        GuildSettingsService settings = mock(GuildSettingsService.class);
        GuildSettings effective = mock(GuildSettings.class);
        when(effective.clanName()).thenReturn("Younglings");
        when(settings.getEffective(GUILD)).thenReturn(effective);
        when(hub.call("GET", "/v1/clans/by-name/Younglings", null)).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("clanId", CLAN)));
        service = new HubNameChanges(mock(HubNameChangeStore.class), hub, new HubNameChangeReads(hub), settings, mock(ClanSyncService.class),
                mock(TrackingEventRouter.class), mock(TrackingIconCatalog.class), mock(VerificationRoleSyncService.class), mock(DiscordOutbox.class),
                mock(PlayerLinkService.class));
    }

    private static DataObject change(long id, String status, boolean canUndo) {
        return DataObject.empty().put("id", id).put("from", "Old Name").put("to", "New Name").put("confidence", "likely")
                .put("basis", "Every skill fits.").put("status", status).put("canUndo", canUndo);
    }

    @Test
    void theWaitingListIsTheHubsListForTheServersClan() {
        when(hub.call("GET", "/v1/clans/" + CLAN + "/name-changes?status=open", null))
                .thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("changes", DataArray.empty().add(change(4, "open", false)))));

        var list = service.changes(GUILD, "open");

        assertTrue(list.ok());
        assertEquals(List.of(new HubNameChanges.Change(4, "Old Name", "New Name", "likely", "Every skill fits.", "open", false)), list.value());
    }

    @Test
    void linkAndUndoCallTheHubAsTheDiscordAdmin() {
        when(hub.call(eq("PUT"), eq("/v1/clans/" + CLAN + "/name-changes/4"), anyString())).thenReturn(new RuneClansHubClient.Answer(200, change(4, "linked", true)));
        when(hub.call(eq("POST"), eq("/v1/clans/" + CLAN + "/name-changes/4/undo"), anyString())).thenReturn(new RuneClansHubClient.Answer(200, change(4, "undone", false)));

        assertEquals("linked", service.decide(GUILD, 4, "link", Long.parseLong(ADMIN)).value().status());
        assertEquals("undone", service.undo(GUILD, 4, Long.parseLong(ADMIN)).value().status());

        verify(hub).call("PUT", "/v1/clans/" + CLAN + "/name-changes/4", DataObject.empty().put("decision", "link").put("decidedBy", "bot:" + ADMIN).toString());
        verify(hub).call("POST", "/v1/clans/" + CLAN + "/name-changes/4/undo", DataObject.empty().put("decidedBy", "bot:" + ADMIN).toString());
    }

    @Test
    void theHubsRefusalsReadPlainly() {
        when(hub.call(eq("PUT"), anyString(), anyString())).thenReturn(new RuneClansHubClient.Answer(409, DataObject.empty().put("error", "already_decided")));
        when(hub.call(eq("POST"), anyString(), anyString())).thenReturn(new RuneClansHubClient.Answer(409, DataObject.empty().put("error", "undo_expired")));

        var decided = service.decide(GUILD, 4, "reject", 1L);
        var undone = service.undo(GUILD, 4, 1L);

        assertFalse(decided.ok());
        assertEquals("Someone already decided this one.", decided.problem());
        assertTrue(undone.problem().contains("30 days"));
        assertTrue(HubNameChanges.problem(null).contains("didn't answer"));
    }

    @Test
    void aServerWhoseClanIsNotOnTheHubGetsAPlainAnswerAndNoDecisionCall() {
        when(hub.call("GET", "/v1/clans/by-name/Younglings", null)).thenReturn(new RuneClansHubClient.Answer(404, DataObject.empty().put("error", "not_found")));

        assertFalse(service.changes(GUILD, "open").ok());
        assertFalse(service.decide(GUILD, 4, "link", 1L).ok());
        verify(hub, never()).call(eq("PUT"), anyString(), anyString());
    }

    @Test
    void aClanLookupTheHubDidNotAnswerIsAPlainProblemNotNoClan() {
        when(hub.call("GET", "/v1/clans/by-name/Younglings", null)).thenReturn(new RuneClansHubClient.Answer(503, DataObject.empty()));

        assertTrue(service.changes(GUILD, "open").problem().contains("didn't answer"));
        assertThrows(HubNameChangeReads.Unanswered.class, () -> new HubNameChangeReads(hub).clanId("Younglings"));
        assertThrows(HubNameChangeReads.Unanswered.class, () -> new HubNameChangeReads(hub).waitingOldNames("Younglings"));
    }

    @Test
    void theWaitingOldNamesAreTheOpenChangesFromNames() {
        when(hub.call("GET", "/v1/clans/" + CLAN + "/name-changes?status=open&limit=100", null))
                .thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("changes", DataArray.empty().add(change(4, "open", false)))));

        assertEquals(java.util.Set.of("old name"), new HubNameChangeReads(hub).waitingOldNames("Younglings"));
    }

    @Test
    void theRenameLineReadsLikeTheJoinAndLeaveLines() {
        assertEquals("<:c:1> **Old Name** is now **New Name**. (Nov 14)", HubNameChanges.line("<:c:1>", "Old Name", "New Name", Instant.parse("2026-11-14T08:00:00Z")));
        assertEquals("**A** is now **B**. (Jan 2)", HubNameChanges.line(null, "A", "B", Instant.parse("2026-01-02T23:59:00Z")));
    }

    private static HubNameChanges.LiveDiscord liveDiscord(boolean posting, GuildMessageChannel channel) {
        TrackingRepository repository = mock(TrackingRepository.class);
        TrackingPostingToggle toggle = mock(TrackingPostingToggle.class);
        when(toggle.isEnabled()).thenReturn(posting);
        when(repository.isEnabled(GUILD, TrackingGroup.CLAN_JOINS_LEAVES.name())).thenReturn(true);
        when(repository.getAllDestinations(GUILD)).thenReturn(Map.of(TrackingGroup.CLAN_JOINS_LEAVES.name(), List.of(new TrackingRepository.Destination(7L, 9L))));
        HubNameChanges.LiveDiscord discord = new HubNameChanges.LiveDiscord(new TrackingEventRouter(repository, toggle), mock(TrackingIconCatalog.class),
                mock(VerificationRoleSyncService.class), mock(DiscordOutbox.class), mock(PlayerLinkService.class));
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getChannelById(GuildMessageChannel.class, 9L)).thenReturn(channel);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        discord.jda = jda;
        return discord;
    }

    @Test
    void aLinkedRenamePostsOneLineToTheJoinsAndLeavesChannel() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class, Answers.RETURNS_DEEP_STUBS);

        liveDiscord(true, channel).announce(GUILD, "Old Name", "New Name");

        verify(channel, times(1)).sendMessage(org.mockito.ArgumentMatchers.<CharSequence>argThat(t -> t.toString().contains("**Old Name** is now **New Name**")));
    }

    @Test
    void withPostingOffNothingIsPosted() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class, Answers.RETURNS_DEEP_STUBS);

        liveDiscord(false, channel).announce(GUILD, "Old Name", "New Name");

        verify(channel, never()).sendMessage(any(CharSequence.class));
    }
}
