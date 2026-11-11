package com.runeclans.bot.internal;

import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.runescape.ClanVerificationService;
import com.runeclans.bot.runescape.PlayerLinkService;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.RoleSafetyTesting;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerSetupAdminApiTest {
    private static final long GUILD = 10L;
    private static final long ACTOR = 77L;

    private GuildSettingsService settings;
    private ClanVerificationService clanVerification;
    private PlayerLinkService links;
    private ServerSetupAdminApi api;
    private Guild guild;
    private Member actor;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        settings = mock(GuildSettingsService.class);
        clanVerification = mock(ClanVerificationService.class);
        links = mock(PlayerLinkService.class);
        api = new ServerSetupAdminApi(settings, clanVerification, links);

        guild = mock(Guild.class);
        actor = mock(Member.class);
        self = mock(SelfMember.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getSelfMember()).thenReturn(self);
        when(actor.getIdLong()).thenReturn(ACTOR);
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, null, null, null, null, null, null, null, null, true, null, null, null));
    }

    private Role role(long id, boolean managed, boolean interactable) {
        Role role = mock(Role.class);
        when(role.isManaged()).thenReturn(managed);
        when(role.getName()).thenReturn("Role" + id);
        when(guild.getRoleById(id)).thenReturn(role);
        when(self.canInteract(role)).thenReturn(interactable);
        when(role.getIdLong()).thenReturn(id);
        when(role.getPermissions()).thenReturn(EnumSet.noneOf(Permission.class));
        return role;
    }

    // ---------- reading the request ----------

    @Test
    void anIdFieldIsAbsentClearedOrSet() {
        var problems = new ArrayList<String>();
        DataObject body = DataObject.fromJson("{\"a\": null, \"b\": \"\", \"c\": \"123\"}");

        assertEquals(false, ServerSetupAdminApi.id(body, "missing", problems).present());
        assertEquals(new ServerSetupAdminApi.Field(true, null, null, false), ServerSetupAdminApi.id(body, "a", problems));
        assertEquals(new ServerSetupAdminApi.Field(true, null, null, false), ServerSetupAdminApi.id(body, "b", problems));
        assertEquals(123L, ServerSetupAdminApi.id(body, "c", problems).value());
        assertTrue(problems.isEmpty());
    }

    @Test
    void anIdThatIsNotDigitsIsAProblemNotACrash() {
        var problems = new ArrayList<String>();

        ServerSetupAdminApi.id(DataObject.fromJson("{\"x\": \"<@&5>\"}"), "x", problems);

        assertEquals(1, problems.size());
    }

    @Test
    void aBlankClanNameClearsAndALongOneIsRefused() {
        var problems = new ArrayList<String>();

        assertNull(ServerSetupAdminApi.text(DataObject.fromJson("{\"clanName\": \"  \"}"), "clanName", problems).text());
        assertEquals(0, problems.size());
        ServerSetupAdminApi.text(DataObject.fromJson("{\"clanName\": \"" + "x".repeat(31) + "\"}"), "clanName", problems);
        assertEquals(1, problems.size());
    }

    // ---------- saving ----------

    @Test
    void roleAndChannelChoicesAreSavedTogetherWhenEverythingChecksOut() {
        role(500, false, true);
        role(600, false, true);

        api.save(guild, actor, DataObject.fromJson("{\"verifiedClanRoleId\": \"500\", \"onboardingRoleId\": \"600\"}"));

        verify(settings).updateVerificationRoleSettings(GUILD, 500L, null, null);
        verify(settings).updateOnboardingRole(GUILD, 600L);
    }

    @Test
    void anInviteLinkIsCleanedUpSavedAndCanBeCleared() {
        api.save(guild, actor, DataObject.fromJson("{\"inviteUrl\": \"discord.com/invite/abc123\"}"));
        verify(settings).updateInviteUrl(GUILD, "https://discord.gg/abc123");

        api.save(guild, actor, DataObject.fromJson("{\"inviteUrl\": \"\"}"));
        verify(settings).updateInviteUrl(GUILD, null);
    }

    @Test
    void somethingThatIsNotADiscordInviteRefusesTheSave() {
        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, DataObject.fromJson("{\"inviteUrl\": \"https://example.com/x\"}")));

        assertEquals(400, thrown.status);
        verify(settings, never()).updateInviteUrl(anyLong(), any());
    }

    @Test
    void aRoleJonnyBotCannotGiveOutRefusesTheWholeSaveAndChangesNothing() {
        role(500, false, true);
        role(700, false, false); // above JonnyBot's own role

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"verifiedClanRoleId\": \"500\", \"unverifiedRoleId\": \"700\"}")));

        assertEquals(400, thrown.status);
        assertTrue(thrown.problems.getFirst().contains("Move the bot's own role above"));
        verify(settings, never()).updateVerificationRoleSettings(anyLong(), any(), any(), any());
    }

    @AfterEach
    void forgetGroups() {
        RoleSafetyTesting.uninstall();
    }

    @Test
    void aVerificationRoleWithAdministratorIsRefused() {
        Role role = role(500, false, true);
        when(role.getPermissions()).thenReturn(EnumSet.of(Permission.ADMINISTRATOR));

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"verifiedClanRoleId\": \"500\"}")));

        assertTrue(thrown.problems.getFirst().contains("powerful permissions"), thrown.problems.getFirst());
        verify(settings, never()).updateVerificationRoleSettings(anyLong(), any(), any(), any());
    }

    @Test
    void aVerificationRoleThatWouldMakeMembersBotStaffIsRefused() {
        role(500, false, true);
        RoleSafetyTesting.install(List.of(new PermissionGroup(1, GUILD, PermissionGroup.DEVELOPER, "Developer", true, false, List.of(500L))));

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"onboardingRoleId\": \"500\"}")));

        assertTrue(thrown.problems.getFirst().contains("Developer rights"), thrown.problems.getFirst());
        verify(settings, never()).updateOnboardingRole(anyLong(), any());
    }

    @Test
    void aRoleThatDoesNotExistInThisServerIsRefused() {
        var thrown = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"onboardingRoleId\": \"999\"}")));

        assertTrue(thrown.problems.getFirst().contains("doesn't exist in this server"));
    }

    @Test
    void aClanTheAskerDoesNotHelpRunIsRefusedWithEveryUnmetRequirement() {
        var checks = List.of(
                new ClanVerificationService.Check("The clan **Foo** exists", true, "10 members found."),
                new ClanVerificationService.Check("You hold the Admin rank or higher", false, "**Bar** is a Recruit — that's below Admin."));
        when(clanVerification.verify(GUILD, ACTOR, "Foo")).thenReturn(new ClanVerificationService.Result(checks));

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"clanName\": \"Foo\"}")));

        assertEquals(1, thrown.problems.size());
        assertTrue(thrown.problems.getFirst().contains("Admin rank"));
        verify(settings, never()).updateClanName(anyLong(), anyString());
    }

    @Test
    void aVerifiedClanIsSavedAndSwitchedOn() {
        var checks = List.of(new ClanVerificationService.Check("ok", true, "fine"));
        when(clanVerification.verify(GUILD, ACTOR, "Foo")).thenReturn(new ClanVerificationService.Result(checks));

        api.save(guild, actor, DataObject.fromJson("{\"clanName\": \"Foo\"}"));

        verify(links).adoptAccounts(GUILD, ACTOR);
        verify(settings).updateClanName(GUILD, "Foo");
        verify(settings).setClanEnabled(GUILD, true);
    }

    @Test
    void savingTheSameClanAgainIsNotCheckedAgain() {
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, "Foo", null, null, null, null, null, null, null, true, "Foo", null, null));

        api.save(guild, actor, DataObject.fromJson("{\"clanName\": \"foo\"}"));

        verify(clanVerification, never()).verify(anyLong(), anyLong(), anyString());
        verify(settings).updateClanName(GUILD, "foo");
    }
}
