package com.runeclans.bot.commands.configure;

import com.runeclans.bot.announcement.AnnouncementRepository;
import com.runeclans.bot.commands.embed.EmbedService;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.permission.RoleSafetyTesting;
import com.runeclans.bot.runescape.ClanVerificationService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.EnumSet;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-55 in Discord: /configure's verification and onboarding roles refuse a role the bot won't hand out, and save nothing. */
class ConfigureRoleSafetyTest {
    private static final long GUILD_ID = 1L;

    private GuildSettingsService settingsService;
    private ConfigureInteractionListener listener;
    private Guild guild;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        settingsService = mock(GuildSettingsService.class);
        listener = new ConfigureInteractionListener(settingsService, mock(ClanVerificationService.class), mock(AnnouncementRepository.class),
                mock(EmbedService.class), mock(PermissionGroupService.class));
        guild = mock(Guild.class);
        self = mock(SelfMember.class);
        when(guild.getIdLong()).thenReturn(GUILD_ID);
        when(guild.getSelfMember()).thenReturn(self);
        when(settingsService.getEffective(GUILD_ID)).thenReturn(new GuildSettings(GUILD_ID, null, null, null, null, null, null, null, null, true, null, null, null));
    }

    @AfterEach
    void forget() {
        RoleSafetyTesting.uninstall();
    }

    private Role role(long id, int position, EnumSet<Permission> permissions) {
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(id);
        when(role.getName()).thenReturn("Role" + id);
        when(role.getPosition()).thenReturn(position);
        when(role.getPermissions()).thenReturn(permissions);
        when(self.canInteract(role)).thenReturn(true);
        when(guild.getRoleById(id)).thenReturn(role);
        return role;
    }

    private void linkRole(String field, long roleId) {
        ModalInteractionEvent event = mock(ModalInteractionEvent.class, Answers.RETURNS_DEEP_STUBS);
        Member admin = mock(Member.class);
        when(admin.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(admin);
        when(event.getModalId()).thenReturn("configure_role_link_modal:" + field);
        when(event.getValue("link_value").getAsString()).thenReturn(Long.toString(roleId));
        listener.onModalInteraction(event);
    }

    @Test
    void aVerifiedRoleWithAdministratorIsRefused() {
        role(500, 2, EnumSet.of(Permission.ADMINISTRATOR));

        linkRole("verified_clan_role", 500);

        verify(settingsService, never()).updateVerificationRoleSettings(anyLong(), any(), any(), any());
    }

    @Test
    void anOnboardingRoleAtOrAboveTheAdminRoleIsRefused() {
        role(50, 5, EnumSet.noneOf(Permission.class));
        role(600, 8, EnumSet.noneOf(Permission.class));
        RoleSafetyTesting.install(List.of(new PermissionGroup(1, GUILD_ID, PermissionGroup.ADMIN, "Admin", true, true, List.of(50L))));

        linkRole("onboarding_role", 600);

        verify(settingsService, never()).updateOnboardingRole(anyLong(), any());
    }

    @Test
    void anOrdinaryRoleIsSaved() {
        role(50, 5, EnumSet.noneOf(Permission.class));
        role(700, 2, EnumSet.noneOf(Permission.class));
        RoleSafetyTesting.install(List.of(new PermissionGroup(1, GUILD_ID, PermissionGroup.ADMIN, "Admin", true, true, List.of(50L))));

        linkRole("verified_clan_role", 700);

        verify(settingsService).updateVerificationRoleSettings(GUILD_ID, 700L, null, null);
    }
}
