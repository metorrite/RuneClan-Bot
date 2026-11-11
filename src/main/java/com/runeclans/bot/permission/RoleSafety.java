package com.runeclans.bot.permission;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.LongFunction;

/**
 * The permission-group half of "may the bot hand this role out to members?" (B-03, B-55). A role is refused when holding it would
 * put someone in one of the bot's staff groups:
 * <ul>
 *   <li>it is one of the roles of a built-in group (Admin, Support, Developer, Event coordinator), or</li>
 *   <li>a group that counts higher roles ({@code includeHigher}, the Admin group's default) has a role at or below it, so anyone
 *       holding it would pass {@link PermissionGroupService#isMember} for that group.</li>
 * </ul>
 * A server's own groups carry no built-in powers, so their roles stay assignable unless the higher-roles rule catches them.
 * <p>
 * {@code MemberApi.safeToAssign} asks this, so every path that hands a role to members (self-roles, role panels, chat-level rewards,
 * autoroles, forms, giveaways, colour roles, verification roles, community roles) refuses the same roles. The groups come from
 * {@link PermissionGroupService}, which installs itself when the bot connects; until then nothing is refused here (no guild exists yet).
 */
public final class RoleSafety {
    /** Where a server's groups come from. */
    public interface Groups {
        List<PermissionGroup> of(long guildId);
    }

    private static volatile Groups installed;

    private RoleSafety() {}

    static void install(Groups groups) {
        installed = groups;
    }

    /** Test hook: forget the installed groups. */
    static void uninstall() {
        installed = null;
    }

    /** The name of the staff group {@code role} would put its holders in, or null when it gives no staff rights. */
    public static String staffGroupGiven(Guild guild, Role role) {
        Groups groups = installed;
        if (groups == null || role == null) return null;
        return staffGroupGiven(guild.getIdLong(), role.getIdLong(), role.getPosition(), groups.of(guild.getIdLong()), id -> {
            Role r = guild.getRoleById(id);
            return r == null ? null : r.getPosition();
        });
    }

    /** The decision on its own: {@code positionOf} answers a role's position, or null for a role the server no longer has. */
    static String staffGroupGiven(long guildId, long roleId, int rolePosition, List<PermissionGroup> groups, LongFunction<Integer> positionOf) {
        for (PermissionGroup group : groups) {
            List<Long> roles = PermissionGroupService.withoutEveryone(guildId, group.roleIds());
            if (group.builtin() && roles.contains(roleId)) return group.name();
            if (!group.includeHigher()) continue;
            OptionalInt lowest = roles.stream().map(positionOf::apply).filter(Objects::nonNull).mapToInt(Integer::intValue).min();
            if (lowest.isPresent() && rolePosition >= lowest.getAsInt()) return group.name();
        }
        return null;
    }

    /** The sentence the dashboard shows for a role refused here. */
    public static String refusal(String roleName, String groupName) {
        return "@" + roleName + " gives the bot's " + groupName + " rights to whoever holds it (it is in that permission group, or sits at or above its roles), so the bot won't hand it out.";
    }
}
