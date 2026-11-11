package com.runeclans.bot.permission;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * The permission half of "may the bot hand this role out to members?" (B-03, B-55). A role is refused when holding it would give
 * someone bot staff rights:
 * <ul>
 *   <li>it is one of the roles of a built-in group (Admin, Support, Developer, Event coordinator);</li>
 *   <li>a group that counts higher roles ({@code includeHigher}, the Admin group's default) has a role at or below it, so anyone
 *       holding it would pass {@link PermissionGroupService#isMember} for that group;</li>
 *   <li>it is in one of the server's own groups, or is named by itself ({@code role:<id>}), where a power source says that group or
 *       role has real rights: a staff command's allow-list ({@code hub.HubService}) or the role toolkit ({@code roletools}).</li>
 * </ul>
 * A server's own group with no such rights (a "Members" group holding the verified role, say) stays assignable, so role sync keeps working.
 * <p>
 * {@code MemberApi.safeToAssign} asks this, so every path that hands a role to members refuses the same roles. The groups come from
 * {@link PermissionGroupService} and the power sources from the services that own them; each plugs itself in when the bot connects (not in
 * its constructor, so tests that build those services never leak into others). Until then nothing is refused here: no guild exists yet.
 */
public final class RoleSafety {
    private static final Logger log = LoggerFactory.getLogger(RoleSafety.class);

    /** Where a server's groups come from. */
    public interface Groups {
        List<PermissionGroup> of(long guildId);
    }

    /** A source of saved permission choices ({@code group:<key>} or {@code role:<id>}) that give real rights in one server. */
    public interface PowerSource {
        Set<String> refs(long guildId);
    }

    private static volatile Groups installed;
    private static final Map<String, PowerSource> SOURCES = new ConcurrentHashMap<>();

    private RoleSafety() {}

    static void install(Groups groups) {
        installed = groups;
        log.info("Role safety: permission groups connected");
    }

    /** Registers (or replaces) the power source called {@code name}. */
    public static void addPowerSource(String name, PowerSource source) {
        SOURCES.put(name, source);
    }

    /** Whether the permission groups are connected; for the wiring tests. */
    static boolean isInstalled() {
        return installed != null;
    }

    /** Test hook: forget the installed groups and power sources. */
    static void uninstall() {
        installed = null;
        SOURCES.clear();
    }

    /** The full sentence the dashboard shows when {@code role} would give staff rights, or null when it gives none. */
    public static String refusal(Guild guild, Role role) {
        Groups groups = installed;
        if (groups == null || role == null) return null;
        long guildId = guild.getIdLong();
        String why;
        try {
            Set<String> powered = new HashSet<>();
            for (PowerSource source : SOURCES.values()) powered.addAll(source.refs(guildId));
            why = why(guildId, role.getIdLong(), role.getPosition(), groups.of(guildId), id -> {
                Role r = guild.getRoleById(id);
                return r == null ? null : r.getPosition();
            }, powered);
        } catch (RuntimeException e) {
            // the database is down: fail closed, but with an answer the page can show instead of an error
            log.warn("Role safety: couldn't read the permission groups of guild {}", guildId, e);
            why = "couldn't be checked against the bot's permission groups just now. Try again in a moment";
        }
        return why == null ? null : "@" + role.getName() + " " + why + ", so the bot won't hand it out.";
    }

    /**
     * The decision on its own: why the role gives staff rights (the end of a sentence that starts with its name), or null.
     * {@code positionOf} answers a role's position, or null for a role the server no longer has.
     */
    static String why(long guildId, long roleId, int rolePosition, List<PermissionGroup> groups, LongFunction<Integer> positionOf, Set<String> poweredRefs) {
        for (PermissionGroup group : groups) {
            List<Long> roles = PermissionGroupService.withoutEveryone(guildId, group.roleIds());
            if (group.builtin() && roles.contains(roleId)) {
                return "is in the bot's " + group.name() + " permission group (whoever holds it gets the bot's " + group.name() + " rights)";
            }
            if (!group.builtin() && roles.contains(roleId) && poweredRefs.contains("group:" + group.key())) {
                return "is in the " + group.name() + " permission group, which may use staff commands or the role toolkit";
            }
            // a server's own group with no rights gives nothing, however high its roles sit
            if (!group.includeHigher() || !(group.builtin() || poweredRefs.contains("group:" + group.key()))) continue;
            OptionalInt lowest = roles.stream().map(positionOf::apply).filter(Objects::nonNull).mapToInt(Integer::intValue).min();
            if (lowest.isPresent() && rolePosition >= lowest.getAsInt()) {
                return "sits at or above the roles of the " + group.name() + " permission group (whoever holds it counts as " + group.name() + ")";
            }
        }
        if (poweredRefs.contains("role:" + roleId)) return "may use staff commands or the role toolkit by itself";
        return null;
    }
}
