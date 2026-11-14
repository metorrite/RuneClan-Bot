package com.runeclans.bot.runescape;

import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.tracking.TrackingEventRouter;
import com.runeclans.bot.tracking.TrackingIconCatalog;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What a clan roster refresh does to linked players' Discord roles: joins get Member, leaves go back to Guest, and the safety guards that stop a bad day from demoting people. */
class ClanSyncRoleTest {
    private static final long GUILD = 1L;

    private RuneScapeApiClient apiClient;
    private ClanMemberRepository clanMembers;
    private VerificationRoleSyncService roleSync;
    private PlayerLinkService linkService;
    private ClanSyncService service;
    private Guild guild;

    @BeforeEach
    void setUp() {
        apiClient = mock(RuneScapeApiClient.class);
        clanMembers = mock(ClanMemberRepository.class);
        roleSync = mock(VerificationRoleSyncService.class);
        linkService = mock(PlayerLinkService.class);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);

        GuildSettingsService settings = mock(GuildSettingsService.class);
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, "Younglings", null, null, null, 2L, 1L, 1L, null, true, "Younglings", null, null));

        service = new ClanSyncService(apiClient, clanMembers, settings,
                mock(TrackingEventRouter.class), mock(WeeklyDigestRepository.class),
                mock(TrackingIconCatalog.class), roleSync, linkService);
    }

    private static ClanMemberRepository.ClanMemberRow row(String rsn) {
        return new ClanMemberRepository.ClanMemberRow(0, GUILD, rsn, "Recruit", null, null, true, 0, 0, null);
    }

    private static RuneScapeApiClient.ClanMember member(String rsn) {
        return new RuneScapeApiClient.ClanMember(rsn, "Recruit", 1_000, 0);
    }

    private void roster(List<String> before, List<String> now) {
        when(clanMembers.getAll(GUILD, true)).thenReturn(before.stream().map(ClanSyncRoleTest::row).toList());
        when(apiClient.fetchClanRoster("Younglings")).thenReturn(now.stream().map(ClanSyncRoleTest::member).toList());
    }

    private void link(String rsn, long discordUserId) {
        when(linkService.getLinkForRsn(GUILD, rsn)).thenReturn(new PlayerLink(1, GUILD, discordUserId, rsn, "ADMIN", null, null));
    }

    @Test
    void aLinkedPlayerWhoJoinsIsGivenMemberStraightAway() {
        roster(List.of("Old"), List.of("Old", "New"));
        link("New", 42L);

        var result = service.refreshRosterIfStale(guild);

        assertTrue(result.refreshed());
        assertEquals(Set.of(42L), result.joinedDiscordUserIds());
        verify(roleSync).syncRoles(guild, 42L, "New");
        verify(roleSync, never()).syncLeftClan(any(), anyLong());
    }

    @Test
    void aNewNameNobodyHasLinkedChangesNoRoles() {
        roster(List.of("Old"), List.of("Old", "New"));

        var result = service.refreshRosterIfStale(guild);

        assertTrue(result.refreshed());
        assertEquals(Set.of(), result.joinedDiscordUserIds());
        verify(roleSync, never()).syncRoles(any(), anyLong(), anyString());
    }

    @Test
    void aLinkedPlayerWhoLeavesGoesBackToGuest() {
        roster(List.of("Old", "Gone"), List.of("Old"));
        link("Gone", 7L);

        service.refreshRosterIfStale(guild);

        verify(roleSync).syncLeftClan(guild, 7L);
    }

    @Test
    void aVanishedNameIsALeaveEvenWhenANewNameAppearedTheHubLinksRenames() {
        // T-1160: the bot no longer guesses renames; if the hub later links Gone -> Renamed, HubNameChanges gives the roles back
        roster(List.of("Old", "Gone"), List.of("Old", "Renamed"));
        link("Gone", 7L);

        service.refreshRosterIfStale(guild);

        verify(roleSync).syncLeftClan(guild, 7L);
    }

    @Test
    void aMassDisappearanceIsTreatedAsABadResponseAndNobodyIsDemoted() {
        List<String> before = new ArrayList<>();
        for (int i = 0; i < 10; i++) before.add("Member" + i);
        roster(before, List.of("Member0", "Member1"));
        for (String name : before) link(name, 100L + before.indexOf(name));

        service.refreshRosterIfStale(guild);

        verify(roleSync, never()).syncLeftClan(any(), anyLong());
    }

    @Test
    void aHandfulOfDeparturesIsStillAppliedInASmallClan() {
        roster(List.of("A", "B", "C"), List.of("A"));
        link("B", 20L);
        link("C", 30L);

        service.refreshRosterIfStale(guild);

        verify(roleSync).syncLeftClan(guild, 20L);
        verify(roleSync).syncLeftClan(guild, 30L);
    }

    @Test
    void thePlayersWhoseNamesJoinedOrLeftAreTheOnlyOnesTouched() {
        roster(List.of("Stays", "Gone"), List.of("Stays", "New"));
        link("Stays", 1L);
        link("New", 2L);
        link("Gone", 3L);

        service.refreshRosterIfStale(guild);

        verify(roleSync, never()).syncRoles(any(), org.mockito.ArgumentMatchers.eq(1L), anyString());
        verify(roleSync).syncRoles(guild, 2L, "New");
        verify(roleSync).syncLeftClan(guild, 3L);
        verify(roleSync, times(1)).syncLeftClan(any(), anyLong());
    }

    @Test
    void aSecondRefreshWithinAMinuteDoesNotAskJagexAgain() {
        roster(List.of("Old"), List.of("Old", "New"));
        link("New", 42L);

        var first = service.refreshRosterIfStale(guild);
        var second = service.refreshRosterIfStale(guild);

        assertTrue(first.refreshed());
        assertFalse(second.refreshed());
        assertEquals(Set.of(), second.joinedDiscordUserIds());
        verify(apiClient, times(1)).fetchClanRoster("Younglings");
    }

    @Test
    void anEmptyOrFailedFetchChangesNothing() {
        roster(List.of("Old", "Other"), List.of());
        link("Old", 5L);

        var result = service.refreshRosterIfStale(guild);

        assertFalse(result.refreshed());
        verify(roleSync, never()).syncLeftClan(any(), anyLong());
        verify(clanMembers, never()).markInactive(anyLong(), anyString());
    }
}
