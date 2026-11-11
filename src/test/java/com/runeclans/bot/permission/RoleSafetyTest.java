package com.runeclans.bot.permission;

import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.hub.HubService;
import com.runeclans.bot.roletools.RoleToolkitService;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleSafetyTest {
    private static final long G = 1L;
    // positions: Member 2, Mods 3, Officer 5, Colour 8 (above Officer, below the bot)
    private static final Map<Long, Integer> POSITIONS = Map.of(20L, 2, 50L, 5, 80L, 8, 30L, 3);

    private static PermissionGroup group(String key, String name, boolean builtin, boolean includeHigher, Long... roles) {
        return new PermissionGroup(0, G, key, name, builtin, includeHigher, List.of(roles));
    }

    private static String why(long roleId, List<PermissionGroup> groups, String... powered) {
        return RoleSafety.why(G, roleId, POSITIONS.get(roleId), groups, POSITIONS::get, Set.of(powered));
    }

    @AfterEach
    void forget() {
        RoleSafety.uninstall();
    }

    @Test
    void theAdminRoleItselfAndAnythingAboveItAreRefused() {
        var groups = List.of(group(PermissionGroup.ADMIN, "Admin", true, true, 50L));

        assertTrue(why(50L, groups).contains("Admin"));
        assertTrue(why(80L, groups).contains("at or above")); // a colour role placed high so its colour shows
        assertNull(why(20L, groups));
    }

    @Test
    void anyBuiltInGroupRoleIsRefusedEvenWithoutHigherRoles() {
        var groups = List.of(group(PermissionGroup.DEVELOPER, "Developer", true, false, 30L),
                group(PermissionGroup.EVENT_COORDINATOR, "Event coordinator", true, false, 20L));

        assertTrue(why(30L, groups).contains("Developer"));
        assertTrue(why(20L, groups).contains("Event coordinator"));
        assertNull(why(80L, groups)); // higher roles don't count for these groups
    }

    @Test
    void aServersOwnGroupWithNoRightsStaysAssignable() {
        // a "Members" group holding the verified role, or a high includeHigher group nobody gave rights to
        assertNull(why(20L, List.of(group("c1", "Members", false, false, 20L))));
        assertNull(why(80L, List.of(group("c2", "Web Dev", false, true, 50L))));
    }

    @Test
    void aServersOwnGroupAllowedOnAStaffCommandOrTheToolkitIsRefused() {
        var mods = group("c3", "Mods", false, false, 30L);
        assertTrue(why(30L, List.of(mods), "group:c3").contains("Mods"));
        assertNull(why(20L, List.of(mods), "group:c3"), "another role of the server is untouched");

        var webDev = group("c2", "Web Dev", false, true, 50L);
        assertNotNull(why(80L, List.of(webDev), "group:c2"), "and with higher roles counting, roles above it too");
    }

    @Test
    void aRoleAllowedOnAStaffCommandByItselfIsRefused() {
        assertTrue(why(20L, List.of(), "role:20").contains("by itself"));
        assertNull(why(30L, List.of(), "role:20"));
    }

    @Test
    void everyoneAndDeletedRolesInAGroupNeverCount() {
        // @everyone (id = guild id) at position 0 would otherwise make every role "higher"; 99 is a deleted role
        var groups = List.of(group(PermissionGroup.ADMIN, "Admin", true, true, G, 99L));

        assertNull(why(20L, groups));
        assertNull(why(80L, groups));
    }

    @Test
    void anEmptyGroupRefusesNothing() {
        assertNull(why(80L, List.of(group(PermissionGroup.ADMIN, "Admin", true, true))));
    }

    // ---------- wiring ----------

    private static Guild guildWithOfficer() {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(G);
        Role officer = mock(Role.class);
        when(officer.getPosition()).thenReturn(5);
        when(guild.getRoleById(50L)).thenReturn(officer);
        return guild;
    }

    private static Role role(long id, int position) {
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(id);
        when(role.getName()).thenReturn("Role" + id);
        when(role.getPosition()).thenReturn(position);
        return role;
    }

    @Test
    void thePermissionGroupServiceWiresItselfInWhenTheBotConnects() {
        PermissionGroupRepository repository = mock(PermissionGroupRepository.class);
        when(repository.list(G)).thenReturn(List.of(group(PermissionGroup.ADMIN, "Admin", true, true, 50L), group(PermissionGroup.SUPPORT, "Support", true, false),
                group(PermissionGroup.DEVELOPER, "Developer", true, false), group(PermissionGroup.EVENT_COORDINATOR, "Event coordinator", true, false)));
        Guild guild = guildWithOfficer();
        Role high = role(80L, 8);

        PermissionGroupService service = new PermissionGroupService(repository, mock(GuildSettingsService.class));
        assertFalse(RoleSafety.isInstalled());
        assertNull(RoleSafety.refusal(guild, high)); // nothing installed until the bot connects

        service.onJdaReady(null);
        assertTrue(RoleSafety.isInstalled());
        assertTrue(RoleSafety.refusal(guild, high).contains("Admin"));
    }

    @Test
    void everyServiceThatFeedsTheCheckDoesSoOnConnect() throws Exception {
        // if a refactor drops one of these listeners, the check silently stops refusing (B-03)
        for (Class<?> type : List.of(PermissionGroupService.class, HubService.class, RoleToolkitService.class)) {
            var method = type.getMethod("onJdaReady", InjectedJDAEvent.class);
            assertNotNull(method.getAnnotation(BEventListener.class), type.getSimpleName() + ".onJdaReady must be a @BEventListener");
        }
    }

    @Test
    void aPowerSourceIsConsulted() {
        RoleSafety.install(id -> List.of(group("c3", "Mods", false, false, 30L)));
        Guild guild = guildWithOfficer();
        Role mods = role(30L, 3);
        assertNull(RoleSafety.refusal(guild, mods));

        RoleSafety.addPowerSource("test", id -> Set.of("group:c3"));
        assertTrue(RoleSafety.refusal(guild, mods).contains("Mods"));
    }

    @Test
    void aDatabaseOutageRefusesWithAReasonInsteadOfThrowing() {
        RoleSafety.install(id -> {
            throw new IllegalStateException("database down");
        });

        String refusal = RoleSafety.refusal(guildWithOfficer(), role(20L, 2));

        assertTrue(refusal.contains("couldn't be checked"), refusal);
    }
}
