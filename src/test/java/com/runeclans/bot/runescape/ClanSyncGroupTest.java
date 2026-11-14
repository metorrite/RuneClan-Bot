package com.runeclans.bot.runescape;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.alerts.LiveSwitch;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.tracking.TrackingEventRouter;
import com.runeclans.bot.tracking.TrackingIconCatalog;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A clan set up in two servers is fetched and polled once a day, and each server still ends up as if it had synced alone. */
class ClanSyncGroupTest {
    private RuneScapeApiClient apiClient;
    private ClanMemberRepository clanMembers;
    private ClanSyncService service;
    private GuildSettingsService settings;
    private Guild one;
    private Guild two;
    private VerificationRoleSyncService roleSync;
    private PlayerLinkService links;

    @BeforeEach
    void setUp() {
        apiClient = mock(RuneScapeApiClient.class);
        clanMembers = mock(ClanMemberRepository.class);
        settings = mock(GuildSettingsService.class);
        one = guild(1L, "Younglings");
        two = guild(2L, "  younglings ");

        when(apiClient.fetchClanRoster(anyString())).thenReturn(List.of(member("A"), member("B"), member("New")));
        when(clanMembers.getAll(1L, true)).thenReturn(List.of(row(1L, "A"), row(1L, "B")));
        when(clanMembers.getAll(2L, true)).thenReturn(List.of(row(2L, "A"), row(2L, "B")));

        service = new ClanSyncService(apiClient, clanMembers, settings,
                mock(TrackingEventRouter.class), mock(WeeklyDigestRepository.class), mock(TrackingIconCatalog.class),
                roleSync = mock(VerificationRoleSyncService.class), links = mock(PlayerLinkService.class), mock(HubNameChangeReads.class));
    }

    @AfterEach
    void forgetTheLiveSwitch() {
        AutoPostRouter.reset();
    }

    private void linkNewMember() {
        when(links.getLinkForRsn(1L, "New")).thenReturn(new PlayerLink(1L, 1L, 77L, "New", null, null, null));
    }

    @Test
    void theDailyRunOnABotThatIsNotLiveLeavesMemberRolesAlone() {
        // T-187: the daily background sync on the dev bot would give and take Member/Guest roles in the real server
        linkNewMember();
        LiveSwitch.set(false);

        var results = service.syncAndPollGroup(List.of(one), List.of(), true);

        assertEquals(1, results.get(1L).newMembers(), "the roster is still synced");
        verify(roleSync, never()).syncRoles(any(), org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void theDailyRunOnTheLiveBotAndAnAdminsSyncNowStillApplyRoles() {
        linkNewMember();
        LiveSwitch.set(true);
        service.syncAndPollGroup(List.of(one), List.of(), true);
        verify(roleSync, times(1)).syncRoles(one, 77L, "New");

        LiveSwitch.set(false);
        service.syncAndPoll(one); // an admin's "sync now" is something they ran on purpose
        verify(roleSync, times(2)).syncRoles(one, 77L, "New");
    }

    private Guild guild(long id, String clan) {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(id);
        when(settings.getEffective(id)).thenReturn(new GuildSettings(id, clan, null, null, null, null, null, null, null, true, clan, null, null));
        return guild;
    }

    private static ClanMemberRepository.ClanMemberRow row(long guildId, String rsn) {
        return new ClanMemberRepository.ClanMemberRow(0, guildId, rsn, "Recruit", null, null, true, 0, 0, null);
    }

    private static RuneScapeApiClient.ClanMember member(String rsn) {
        return new RuneScapeApiClient.ClanMember(rsn, "Recruit", 1_000, 0);
    }

    @Test
    void twoServersOfOneClanShareOneFetchAndOnePollPerPlayer() {
        Map<Long, ClanSyncService.SyncResult> results = service.syncAndPollGroup(List.of(one, two));

        verify(apiClient, times(1)).fetchClanRoster(anyString());

        // each server still gets its own roster rows and result
        verify(clanMembers).upsert(eq(1L), eq("New"), anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        verify(clanMembers).upsert(eq(2L), eq("New"), anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        assertEquals(1, results.get(1L).newMembers());
        assertEquals(1, results.get(2L).newMembers());
        assertEquals(3, results.get(2L).rosterSize());
    }

    @Test
    void aSingleServerStillSyncsLikeBefore() {
        var result = service.syncAndPoll(one);

        verify(apiClient, times(1)).fetchClanRoster("Younglings");
        assertEquals(3, result.rosterSize());
        assertEquals(1, result.newMembers());
    }

    @Test
    void aServerWithNoClanIsSkipped() {
        Guild none = guild(3L, null);
        when(settings.getEffective(3L)).thenReturn(new GuildSettings(3L, null, null, null, null, null, null, null, null, true, null, null, null));

        assertEquals(0, service.syncAndPoll(none).rosterSize());
        verify(apiClient, never()).fetchClanRoster(anyString());
    }

    @Test
    void clansAreGroupedIgnoringCaseAndSpacing() {
        assertEquals(ClanSyncService.clanKey("Eternal  Rising"), ClanSyncService.clanKey(" eternal_rising "));

        Guild other = guild(9L, "Someone Else");
        var groups = ClanSyncScheduler.groupByClan(List.of(one, two, other, guild(10L, null)), id -> settings.getEffective(id).clanName());

        assertEquals(2, groups.size());
        assertEquals(List.of(one, two), groups.get("younglings"));
        assertTrue(groups.containsKey("someone else"));
    }

    @Test
    void oneServerFailingDoesNotStopTheOtherOrTheNextClan() {
        // a failure while rewriting server 1's roster is logged and server 2 still syncs
        when(clanMembers.getAll(1L, true)).thenThrow(new RuntimeException("db down"));

        Map<Long, ClanSyncService.SyncResult> results = service.syncAndPollGroup(List.of(one, two));

        assertTrue(results.containsKey(2L));
        assertTrue(!results.containsKey(1L));
    }

    private void botlessClan(long id, String clan) {
        when(settings.getEffective(id)).thenReturn(new GuildSettings(id, clan, null, null, null, null, null, null, null, true, clan, null, null));
    }

    @Test
    void theDailyPollingIncludesTheClansOfBotlessSites() {
        botlessClan(20L, "YOUNGLINGS");
        botlessClan(21L, "Only Online");
        botlessClan(22L, null);

        var groups = ClanSyncScheduler.groupByClan(List.of(one, two), List.of(20L, 21L, 22L), id -> settings.getEffective(id).clanName());

        assertEquals(2, groups.size(), "a bot-less site with no clan name is left out");
        assertEquals(List.of(one, two), groups.get("younglings").guilds());
        assertEquals(List.of(20L), groups.get("younglings").botlessGuildIds(), "the same clan as two bot servers: still one group");
        assertEquals(List.of(), groups.get("only online").guilds());
        assertEquals(List.of(21L), groups.get("only online").botlessGuildIds(), "a clan only a bot-less site shows is polled too");
    }

    @Test
    void aBotlessSiteGetsItsOwnRosterRowsFromTheSharedFetchWithNothingPostedInDiscord() {
        botlessClan(20L, "Younglings");
        TrackingEventRouter router = mock(TrackingEventRouter.class);
        service = new ClanSyncService(apiClient, clanMembers, settings, router, mock(WeeklyDigestRepository.class),
                mock(TrackingIconCatalog.class), mock(VerificationRoleSyncService.class), mock(PlayerLinkService.class), mock(HubNameChangeReads.class));
        when(clanMembers.getAll(20L, true)).thenReturn(List.of(row(20L, "A"), row(20L, "Gone")));

        Map<Long, ClanSyncService.SyncResult> results = service.syncAndPollGroup(List.of(one), List.of(20L));

        verify(apiClient, times(1)).fetchClanRoster(anyString());
        verify(clanMembers).upsert(eq(20L), eq("New"), anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        verify(clanMembers).markInactive(20L, "gone");
        assertEquals(2, results.get(20L).newMembers(), "B and New were not on that site's roster yet");
        assertEquals(1, results.get(20L).departedMembers());
        verify(router, times(1)).dispatchAll(eq(one), any()); // only the bot's own server gets join/leave posts
    }

    @Test
    void aClanOnlyBotlessSitesShowIsFetchedOnItsOwn() {
        botlessClan(20L, "Only Online");

        Map<Long, ClanSyncService.SyncResult> results = service.syncAndPollGroup(List.of(), List.of(20L));

        verify(apiClient, times(1)).fetchClanRoster("Only Online");
        assertEquals(3, results.get(20L).rosterSize());
    }
}
