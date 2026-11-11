package com.runeclans.bot.permission;

import com.runeclans.bot.configure.GuildSettingsService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleSafetyTest {
    private static final long G = 1L;
    // positions: Member 2, Officer 5, Colour 8 (above Officer, below the bot)
    private static final Map<Long, Integer> POSITIONS = Map.of(20L, 2, 50L, 5, 80L, 8, 30L, 3);

    private static PermissionGroup group(String key, String name, boolean builtin, boolean includeHigher, Long... roles) {
        return new PermissionGroup(0, G, key, name, builtin, includeHigher, List.of(roles));
    }

    private static String given(long roleId, List<PermissionGroup> groups) {
        return RoleSafety.staffGroupGiven(G, roleId, POSITIONS.get(roleId), groups, POSITIONS::get);
    }

    @Test
    void theAdminRoleItselfAndAnythingAboveItAreRefused() {
        var groups = List.of(group(PermissionGroup.ADMIN, "Admin", true, true, 50L));

        assertEquals("Admin", given(50L, groups));
        assertEquals("Admin", given(80L, groups)); // a colour role placed high so its colour shows
        assertNull(given(20L, groups));
    }

    @Test
    void anyBuiltInGroupRoleIsRefusedEvenWithoutHigherRoles() {
        var groups = List.of(group(PermissionGroup.DEVELOPER, "Developer", true, false, 30L),
                group(PermissionGroup.EVENT_COORDINATOR, "Event coordinator", true, false, 20L));

        assertEquals("Developer", given(30L, groups));
        assertEquals("Event coordinator", given(20L, groups));
        assertNull(given(80L, groups)); // higher roles don't count for these groups
    }

    @Test
    void aServersOwnGroupOnlyRefusesThroughHigherRoles() {
        assertNull(given(20L, List.of(group("c1", "Members", false, false, 20L))));
        assertEquals("Web Dev", given(80L, List.of(group("c2", "Web Dev", false, true, 50L))));
    }

    @Test
    void everyoneAndDeletedRolesInAGroupNeverCount() {
        // @everyone (id = guild id) at position 0 would otherwise make every role "higher"; 99 is a deleted role
        var groups = List.of(group(PermissionGroup.ADMIN, "Admin", true, true, G, 99L));

        assertNull(given(20L, groups));
        assertNull(given(80L, groups));
    }

    @Test
    void anEmptyGroupRefusesNothing() {
        assertNull(given(80L, List.of(group(PermissionGroup.ADMIN, "Admin", true, true))));
    }

    @AfterEach
    void forget() {
        RoleSafety.uninstall();
    }

    @Test
    void thePermissionGroupServiceWiresItselfInWhenTheBotConnects() {
        PermissionGroupRepository repository = mock(PermissionGroupRepository.class);
        when(repository.list(G)).thenReturn(List.of(group(PermissionGroup.ADMIN, "Admin", true, true, 50L), group(PermissionGroup.SUPPORT, "Support", true, false),
                group(PermissionGroup.DEVELOPER, "Developer", true, false), group(PermissionGroup.EVENT_COORDINATOR, "Event coordinator", true, false)));
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(G);
        Role officer = mock(Role.class);
        when(officer.getPosition()).thenReturn(5);
        when(guild.getRoleById(50L)).thenReturn(officer);
        Role high = mock(Role.class);
        when(high.getIdLong()).thenReturn(80L);
        when(high.getPosition()).thenReturn(8);

        PermissionGroupService service = new PermissionGroupService(repository, mock(GuildSettingsService.class));
        assertNull(RoleSafety.staffGroupGiven(guild, high)); // nothing installed until the bot connects

        service.onJdaReady(null);
        assertEquals("Admin", RoleSafety.staffGroupGiven(guild, high));
    }
}
