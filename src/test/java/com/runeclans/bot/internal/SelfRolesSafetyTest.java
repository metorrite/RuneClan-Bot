package com.runeclans.bot.internal;

import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.RoleSafetyTesting;
import com.runeclans.bot.member.MemberProfileRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-03 on the dashboard: Self-assignable roles refuse a role that would make whoever picks it bot staff, and save nothing. */
class SelfRolesSafetyTest {
    private static final long G = 10L;

    private MemberProfileRepository members;
    private ClanAdminApi api;
    private Guild guild;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        members = mock(MemberProfileRepository.class);
        api = new ClanAdminApi(members, null, null, null, null, null, null, null, null);
        guild = mock(Guild.class);
        self = mock(SelfMember.class);
        when(guild.getIdLong()).thenReturn(G);
        when(guild.getSelfMember()).thenReturn(self);
        RoleSafetyTesting.install(List.of(new PermissionGroup(1, G, PermissionGroup.ADMIN, "Admin", true, true, List.of(50L))));
        role(50, 5);
    }

    @AfterEach
    void forget() {
        RoleSafetyTesting.uninstall();
    }

    private void role(long id, int position) {
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(id);
        when(role.getName()).thenReturn("Role" + id);
        when(role.getPosition()).thenReturn(position);
        when(role.getPermissions()).thenReturn(EnumSet.noneOf(Permission.class));
        when(self.canInteract(role)).thenReturn(true);
        when(guild.getRoleById(id)).thenReturn(role);
    }

    private static DataObject body(long roleId) {
        return DataObject.fromJson("{\"roles\": [{\"roleId\": \"" + roleId + "\"}]}");
    }

    @Test
    void aRoleAboveTheAdminRoleIsRefusedWithTheReason() {
        role(80, 8);

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.saveSelfRoles(guild, mock(Member.class), body(80)));

        assertEquals(400, thrown.status);
        assertTrue(thrown.getMessage().contains("Admin"), thrown.getMessage());
        verify(members, never()).replaceSelfRoles(anyLong(), anyList());
    }

    @Test
    void theAdminRoleItselfIsRefused() {
        assertThrows(TicketAdminApi.ApiError.class, () -> api.saveSelfRoles(guild, mock(Member.class), body(50)));
        verify(members, never()).replaceSelfRoles(anyLong(), anyList());
    }

    @Test
    void anOrdinaryRoleIsSaved() {
        role(20, 2);

        api.saveSelfRoles(guild, mock(Member.class), body(20));

        verify(members).replaceSelfRoles(anyLong(), anyList());
    }
}
