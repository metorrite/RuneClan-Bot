package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordOutbox;
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

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** The one "may the bot hand this role out?" check (B-03, B-55), and the colour-role path that used to skip it. */
class RoleHandOutTest {
    private static final long G = 10L;

    private Guild guild;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        guild = mock(Guild.class);
        self = mock(SelfMember.class);
        when(guild.getIdLong()).thenReturn(G);
        when(guild.getSelfMember()).thenReturn(self);
    }

    @AfterEach
    void forget() {
        RoleSafetyTesting.uninstall();
    }

    private Role role(long id, String name, int position) {
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(role.getPosition()).thenReturn(position);
        when(role.getPermissions()).thenReturn(EnumSet.noneOf(Permission.class));
        when(self.canInteract(role)).thenReturn(true);
        when(guild.getRoleById(id)).thenReturn(role);
        return role;
    }

    private void adminGroup(long roleId) {
        RoleSafetyTesting.install(List.of(new PermissionGroup(1, G, PermissionGroup.ADMIN, "Admin", true, true, List.of(roleId))));
    }

    @Test
    void anOrdinaryRoleBelowTheAdminRoleMayBeHandedOut() {
        role(50, "Officer", 5);
        Role notify = role(20, "Notify", 2);
        adminGroup(50);

        assertTrue(MemberApi.safeToAssign(guild, notify));
        assertNull(MemberApi.handOutRefusal(guild, notify));
    }

    @Test
    void aRoleAboveTheAdminRoleIsRefusedWithTheReason() {
        role(50, "Officer", 5);
        Role notify = role(80, "Notify", 8);
        adminGroup(50);

        assertFalse(MemberApi.safeToAssign(guild, notify));
        assertTrue(MemberApi.handOutRefusal(guild, notify).contains("Admin"));
    }

    @Test
    void powerfulPermissionsAreNamed() {
        Role role = role(20, "Mods", 2);
        when(role.getPermissions()).thenReturn(EnumSet.of(Permission.BAN_MEMBERS));

        assertTrue(MemberApi.handOutRefusal(guild, role).contains("Ban Members"));
    }

    @Test
    void withoutGroupsInstalledOnlyTheDiscordChecksApply() {
        Role role = role(80, "Notify", 8);

        assertTrue(MemberApi.safeToAssign(guild, role));
    }

    @Test
    void pickingAColourWhoseExistingRoleGivesAdminIsRefusedBeforeAnythingIsQueued() {
        role(50, "Officer", 5);
        Role red = role(80, "Color: Red", 8);
        when(guild.getRolesByName(anyString(), any(Boolean.class))).thenReturn(List.of(red));
        adminGroup(50);
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        Member member = mock(Member.class);
        String key = firstColourKey();

        var thrown = assertThrows(ServerProfile.Refused.class, () -> ServerProfile.setColorRole(outbox, guild, member, DataObject.empty().put("color", key)));

        assertEquals(403, thrown.status);
        assertTrue(thrown.getMessage().contains("Admin"));
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void aColourWithNoRoleYetIsFine() {
        when(guild.getRolesByName(anyString(), any(Boolean.class))).thenReturn(List.of());

        assertNull(ServerProfile.existingColorRoleRefusal(guild, firstColourKey()));
    }

    private static String firstColourKey() {
        return "Red";
    }
}
