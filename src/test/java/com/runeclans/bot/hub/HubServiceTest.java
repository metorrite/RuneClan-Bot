package com.runeclans.bot.hub;

import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.permission.PermissionGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HubServiceTest {
    private static final long GUILD = 1L;
    private static final long CHANNEL = 50L;

    private static HubSettings settings(HubCommand command, boolean enabled, boolean customAccess, List<Long> channels) {
        return new HubSettings(GUILD, command.key(), enabled, customAccess, List.of("group:cweb"), channels, "{}");
    }

    private static HubService.Decision decide(HubCommand command, HubSettings settings, boolean admin, boolean inAllowedList, long channel, Long parent) {
        return HubService.decide(command, settings, admin, channel, parent, () -> inAllowedList);
    }

    // ---------- the generic rule ----------

    @Test
    void aCommandNobodyHasChangedBehavesAsItAlwaysHas() {
        HubSettings untouched = HubSettings.defaults(GUILD, "signup");

        assertFalse(decide(HubCommand.SIGNUP, untouched, false, false, CHANNEL, null).allowed(), "signups were Admin-only");
        assertTrue(decide(HubCommand.SIGNUP, untouched, true, false, CHANNEL, null).allowed());
        assertTrue(decide(HubCommand.POLL, HubSettings.defaults(GUILD, "poll"), false, false, CHANNEL, null).allowed(), "the command checks its own rule");
        assertTrue(decide(HubCommand.COMBAT_ACHIEVEMENTS, HubSettings.defaults(GUILD, "ca"), false, false, CHANNEL, null).allowed());
    }

    @Test
    void aCommandSwitchedOffIsOffForAdminsToo() {
        var decision = decide(HubCommand.POLL, settings(HubCommand.POLL, false, false, List.of()), true, false, CHANNEL, null);

        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("/poll"));
    }

    @Test
    void membersMustUseTheCommandInTheChosenChannelsButAdminsMayUseItAnywhere() {
        HubSettings limited = settings(HubCommand.WRAPPED, true, false, List.of(60L, 61L));

        var wrong = decide(HubCommand.WRAPPED, limited, false, false, CHANNEL, null);
        assertFalse(wrong.allowed());
        assertTrue(wrong.message().contains("<#60>") && wrong.message().contains("<#61>"));
        assertTrue(decide(HubCommand.WRAPPED, limited, false, false, 60L, null).allowed());
        assertTrue(decide(HubCommand.WRAPPED, limited, true, false, CHANNEL, null).allowed(), "admins are exempt from the channel limit");
    }

    @Test
    void aThreadCountsAsItsParentChannel() {
        HubSettings limited = settings(HubCommand.WRAPPED, true, false, List.of(60L));

        assertTrue(decide(HubCommand.WRAPPED, limited, false, false, 999L, 60L).allowed());
        assertFalse(decide(HubCommand.WRAPPED, limited, false, false, 999L, 61L).allowed());
    }

    @Test
    void customAccessLetsOnlyAdminsAndTheListedGroupsAndRolesIn() {
        HubSettings custom = settings(HubCommand.SIGNUP, true, true, List.of());

        assertTrue(decide(HubCommand.SIGNUP, custom, false, true, CHANNEL, null).allowed(), "a Web Dev group member can now open /signup");
        assertFalse(decide(HubCommand.SIGNUP, custom, false, false, CHANNEL, null).allowed());
        assertTrue(decide(HubCommand.SIGNUP, custom, true, false, CHANNEL, null).allowed(), "admins always can");
    }

    @Test
    void customAccessNarrowsACommandEveryoneCouldUse() {
        HubSettings custom = settings(HubCommand.COMBAT_ACHIEVEMENTS, true, true, List.of());

        assertFalse(decide(HubCommand.COMBAT_ACHIEVEMENTS, custom, false, false, CHANNEL, null).allowed());
        assertTrue(decide(HubCommand.COMBAT_ACHIEVEMENTS, custom, false, true, CHANNEL, null).allowed());
    }

    @Test
    void theChannelLimitIsCheckedBeforeWhoMayUseIt() {
        HubSettings both = settings(HubCommand.SIGNUP, true, true, List.of(60L));

        var decision = decide(HubCommand.SIGNUP, both, false, true, CHANNEL, null);

        assertFalse(decision.allowed());
        assertTrue(decision.message().startsWith("Use `/signup` in"));
    }

    @Test
    void everyHubCommandCanBeFoundByItsSlashNameAndKey() {
        for (HubCommand command : HubCommand.values()) {
            assertEquals(command, HubCommand.ofSlashName(command.slashName()).orElseThrow());
            assertEquals(command, HubCommand.ofKey(command.key()).orElseThrow());
        }
        assertTrue(HubCommand.ofSlashName("rsadmin").isEmpty());
    }

    // ---------- signup extras ----------

    private HubService service(HubRepository repository) {
        return new HubService(repository, mock(AdminRoleFilter.class), mock(PermissionGroupService.class));
    }

    @Test
    void signupSettingsRoundTripThroughTheirJson() {
        var policy = new HubService.SignupPolicy(77L, true, List.of(80L, 81L));

        assertEquals(policy, HubService.parseSignup(HubService.writeSignup(policy)));
    }

    @Test
    void junkInTheStoredJsonMeansNoSignupRules() {
        assertEquals(HubService.SignupPolicy.NONE, HubService.parseSignup("{not json"));
        assertEquals(HubService.SignupPolicy.NONE, HubService.parseSignup(""));
        assertEquals(HubService.SignupPolicy.NONE, HubService.parseSignup("{}"));
    }

    @Test
    void aFixedAdminChannelOverridesWhateverWasPicked() {
        HubRepository repository = mock(HubRepository.class);
        var locked = new HubSettings(GUILD, "signup", true, false, List.of(), List.of(), HubService.writeSignup(new HubService.SignupPolicy(77L, true, List.of())));
        when(repository.all(GUILD)).thenReturn(Map.of("signup", locked));

        assertEquals(77L, service(repository).adminChannelFor(GUILD, 5L));
    }

    @Test
    void anAdminChannelThatIsSetButNotFixedIsOnlyADefaultSoThePickStands() {
        HubRepository repository = mock(HubRepository.class);
        var unlocked = new HubSettings(GUILD, "signup", true, false, List.of(), List.of(), HubService.writeSignup(new HubService.SignupPolicy(77L, false, List.of())));
        when(repository.all(GUILD)).thenReturn(Map.of("signup", unlocked));

        assertEquals(5L, service(repository).adminChannelFor(GUILD, 5L));
    }

    @Test
    void publicPanelsMayGoAnywhereUnlessTheServerListedChannels() {
        HubRepository repository = mock(HubRepository.class);
        when(repository.all(GUILD)).thenReturn(Map.of());
        assertNull(service(repository).publicChannelProblem(GUILD, 5L), "no rule saved: anywhere");

        HubRepository limited = mock(HubRepository.class);
        var rule = new HubSettings(GUILD, "signup", true, false, List.of(), List.of(), HubService.writeSignup(new HubService.SignupPolicy(null, false, List.of(80L))));
        when(limited.all(GUILD)).thenReturn(Map.of("signup", rule));
        HubService hub = service(limited);

        assertNull(hub.publicChannelProblem(GUILD, 80L));
        assertTrue(hub.publicChannelProblem(GUILD, 5L).contains("<#80>"));
    }

    @Test
    void savingDropsTheCachedSettingsSoTheNextCheckSeesThem() {
        HubRepository repository = mock(HubRepository.class);
        when(repository.all(GUILD)).thenReturn(Map.of());
        HubService hub = service(repository);
        hub.get(GUILD, HubCommand.POLL);

        hub.save(HubSettings.defaults(GUILD, "poll"));
        hub.get(GUILD, HubCommand.POLL);

        verify(repository, org.mockito.Mockito.times(2)).all(GUILD);
    }

    // ---------- what the role check refuses (B-03) ----------

    @Test
    void staffRefsAreTheChoicesOnAdminOnlyCommandsWithTheirOwnAccess() {
        HubRepository repository = mock(HubRepository.class);
        when(repository.all(1L)).thenReturn(java.util.Map.of(
                HubCommand.BAN.key(), new HubSettings(1L, HubCommand.BAN.key(), true, true, List.of("group:mods", "role:5"), List.of(), "{}"),
                HubCommand.POLL.key(), new HubSettings(1L, HubCommand.POLL.key(), true, true, List.of("group:members"), List.of(), "{}"),
                HubCommand.KICK.key(), new HubSettings(1L, HubCommand.KICK.key(), true, false, List.of("group:ignored"), List.of(), "{}")));

        assertEquals(java.util.Set.of("group:mods", "role:5"), service(repository).staffRefs(1L),
                "a member command (poll) gives no staff rights, and a list that isn't in use (customAccess off) counts for nothing");
    }
}
