package com.runeclans.bot.internal;

import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.permission.PermissionGroupService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-60: a permission-groups save is checked as a whole before anything changes, and a role deleted in Discord no longer blocks every save. */
class PermissionsAdminApiSaveTest {
    private static final long GUILD = 1L, KEPT = 20L, DELETED = 21L, EVERYONE = 1L;

    private PermissionGroupService groups;
    private PermissionsAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        groups = mock(PermissionGroupService.class);
        DashboardAccess access = mock(DashboardAccess.class);
        api = new PermissionsAdminApi(groups, access);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn(Long.toString(GUILD));
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("9");
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.ADMIN);
        Role kept = mock(Role.class);
        when(guild.getRoleById(KEPT)).thenReturn(kept);
        Role everyone = mock(Role.class);
        when(everyone.isPublicRole()).thenReturn(true);
        when(guild.getRoleById(EVERYONE)).thenReturn(everyone);
        when(groups.groups(GUILD)).thenReturn(List.of());
    }

    private static DataObject body(long... roleIds) {
        DataArray roles = DataArray.empty();
        for (long id : roleIds) roles.add(Long.toString(id));
        return DataObject.empty().put("groups", DataArray.empty().add(DataObject.empty().put("key", "admin").put("name", "Admin").put("roleIds", roles)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRoleDeletedInDiscordIsDroppedAndTheRestIsSaved() {
        api.save(guild, actor, body(KEPT, DELETED));
        ArgumentCaptor<List<PermissionGroupService.Draft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(groups).save(eq(GUILD), drafts.capture());
        assertEquals(List.of(KEPT), drafts.getValue().getFirst().roleIds());
    }

    @Test
    void aRefusedSaveSavesNothing() {
        TicketAdminApi.ApiError error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body(KEPT, EVERYONE)));
        assertEquals(400, error.status);
        verify(groups, never()).save(anyLong(), any());
    }
}
