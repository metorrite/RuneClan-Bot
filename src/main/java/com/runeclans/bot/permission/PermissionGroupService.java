package com.runeclans.bot.permission;

import com.runeclans.bot.cachebus.CacheBus;
import com.runeclans.bot.cachebus.CacheInvalidator;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A server's permission levels, and the one place that answers "is this member in that group?".
 * <p>
 * Every server has four built-in groups, Admin, Support and Developer (which carry the bot's tiers) and Event coordinator (events only) (see
 * {@link AdminRoleFilter} and {@link DashboardAccess}), and can add groups of its own, e.g. "Web Dev" and "Discord Dev", each
 * holding as many server roles as it likes. A group of the server's own has no powers by itself; a setting that asks "who may do
 * this?" can name it (as {@code group:<key>}, see {@link #matches}) or name a single server role ({@code role:<id>}).
 * <p>
 * The built-in groups are created the first time a server is looked at, from the single Admin, Support and Developer roles the
 * server had set before groups existed, so nothing changes for a server that never opens the editor. Answers are cached per
 * server and dropped whenever the groups are saved.
 */
@BService
public class PermissionGroupService implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "permission-groups";

    public static final int MAX_CUSTOM_GROUPS = 20;
    public static final int MAX_ROLES_PER_GROUP = 25;
    public static final int MAX_NAME_LENGTH = 32;

    /** What a save asks for. {@code key} is null for a group being created. */
    public record Draft(String key, String name, boolean includeHigher, List<Long> roleIds) {}

    /** The reasons a save was refused, each worded for the person editing the groups. */
    public static final class InvalidGroupsException extends RuntimeException {
        private final List<String> problems;

        public InvalidGroupsException(List<String> problems) {
            super("Those permission groups can't be saved yet.");
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    private final PermissionGroupRepository repository;
    private final GuildSettingsService legacySettings;
    private final ConcurrentHashMap<Long, List<PermissionGroup>> cache = new ConcurrentHashMap<>();

    public PermissionGroupService(PermissionGroupRepository repository, GuildSettingsService legacySettings) {
        this.repository = repository;
        this.legacySettings = legacySettings;
    }

    /**
     * Lets {@code MemberApi.safeToAssign} see this server's groups ({@link RoleSafety}), so no path hands out a role that makes its holder
     * bot staff. Done when the bot connects rather than in the constructor, so tests that build this service never leak it into others.
     */
    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        RoleSafety.install(this::groups);
    }

    // ---------- reading ----------

    /** All of the server's groups, built-ins first. */
    public List<PermissionGroup> groups(long guildId) {
        return cache.computeIfAbsent(guildId, this::loadAndSeed);
    }

    public Optional<PermissionGroup> group(long guildId, String key) {
        return groups(guildId).stream().filter(g -> g.key().equals(key)).findFirst();
    }

    public List<Long> roleIds(long guildId, String key) {
        return group(guildId, key).map(PermissionGroup::roleIds).orElse(List.of());
    }

    private List<PermissionGroup> loadAndSeed(long guildId) {
        List<PermissionGroup> existing = repository.list(guildId);
        Set<String> have = new HashSet<>();
        existing.forEach(g -> have.add(g.key()));
        if (have.containsAll(List.of(PermissionGroup.ADMIN, PermissionGroup.SUPPORT, PermissionGroup.DEVELOPER, PermissionGroup.EVENT_COORDINATOR))) return List.copyOf(existing);

        GuildSettings old = legacySettings.getEffective(guildId);
        List<PermissionGroup> missing = new ArrayList<>();
        if (!have.contains(PermissionGroup.ADMIN)) missing.add(builtin(guildId, PermissionGroup.ADMIN, "Admin", true, old.adminRoleId()));
        if (!have.contains(PermissionGroup.SUPPORT)) missing.add(builtin(guildId, PermissionGroup.SUPPORT, "Support", false, old.supportRoleId()));
        if (!have.contains(PermissionGroup.DEVELOPER)) missing.add(builtin(guildId, PermissionGroup.DEVELOPER, "Developer", false, old.developerRoleId()));
        if (!have.contains(PermissionGroup.EVENT_COORDINATOR)) missing.add(builtin(guildId, PermissionGroup.EVENT_COORDINATOR, "Event coordinator", false, null));
        repository.createMissing(guildId, missing);
        return List.copyOf(repository.list(guildId));
    }

    private static PermissionGroup builtin(long guildId, String key, String name, boolean includeHigher, Long roleId) {
        return new PermissionGroup(0, guildId, key, name, true, includeHigher, roleId == null ? List.of() : List.of(roleId));
    }

    // ---------- asking ----------

    /** Whether {@code member} is in the group: holds one of its roles, or (for a group that includes higher roles) any role ranked above its lowest. */
    public boolean isMember(Guild guild, Member member, String key) {
        Optional<PermissionGroup> group = group(guild.getIdLong(), key);
        if (group.isEmpty()) return false;
        // @everyone in a group (saved before it was refused) would rank every member above it, so it never counts
        List<Long> groupRoles = withoutEveryone(guild.getIdLong(), group.get().roleIds());
        if (groupRoles.isEmpty()) return false;

        List<Role> memberRoles = member.getRoles(); // highest role first
        OptionalInt lowest = groupRoles.stream()
                .map(guild::getRoleById).filter(java.util.Objects::nonNull)
                .mapToInt(Role::getPosition).min();
        return qualifies(memberRoles.stream().map(Role::getIdLong).toList(), memberRoles.isEmpty() ? -1 : memberRoles.getFirst().getPosition(),
                groupRoles, group.get().includeHigher(), lowest);
    }

    /** {@code roleIds} without @everyone (its id is the server's): a group holding it would hold the whole server (T-626). */
    static List<Long> withoutEveryone(long guildId, List<Long> roleIds) {
        return roleIds.stream().filter(id -> id != guildId).toList();
    }

    /** The decision itself, separate from looking things up so it can be tested on its own. */
    static boolean qualifies(Collection<Long> memberRoleIds, int memberTopPosition, Collection<Long> groupRoleIds, boolean includeHigher, OptionalInt lowestGroupPosition) {
        for (long id : groupRoleIds) {
            if (memberRoleIds.contains(id)) return true;
        }
        return includeHigher && lowestGroupPosition.isPresent() && memberTopPosition >= 0 && memberTopPosition >= lowestGroupPosition.getAsInt();
    }

    /**
     * Whether a member is covered by a saved permission choice: {@code group:<key>} for one of the server's groups, {@code role:<id>}
     * for a single server role. Anything else, or a group that no longer exists, matches nobody, so a stale setting fails closed.
     */
    public boolean matches(Guild guild, Member member, String ref) {
        if (ref == null) return false;
        if (ref.startsWith("group:")) return isMember(guild, member, ref.substring("group:".length()));
        if (ref.startsWith("role:")) {
            try {
                long roleId = Long.parseLong(ref.substring("role:".length()));
                return member.getRoles().stream().anyMatch(r -> r.getIdLong() == roleId);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    // ---------- writing ----------

    /**
     * Replaces one group's roles (what {@code /configure}'s role menus do), keeping everything else about it. Discord's role menu offers
     * @everyone, which is dropped (T-626): it would put the whole server in the group.
     */
    public void setRoles(long guildId, String key, List<Long> picked) {
        List<Long> roleIds = withoutEveryone(guildId, picked);
        List<PermissionGroup> target = new ArrayList<>();
        boolean found = false;
        for (PermissionGroup g : groups(guildId)) {
            if (g.key().equals(key)) {
                found = true;
                target.add(new PermissionGroup(g.groupId(), guildId, g.key(), g.name(), g.builtin(), g.includeHigher(), List.copyOf(roleIds)));
            } else {
                target.add(g);
            }
        }
        if (!found) throw new IllegalArgumentException("No such permission group: " + key);
        repository.replaceAll(guildId, target);
        cache.remove(guildId);
        CacheBus.changed(CACHE_AREA, guildId);
    }

    /**
     * Saves the groups the editor sends. Built-in groups keep their name and can't be removed; a built-in group the request
     * leaves out is left as it is. Groups of the server's own are created (no key), updated (known key) or, when left out,
     * deleted. Everything is checked first, so a refused save changes nothing.
     */
    public List<PermissionGroup> save(long guildId, List<Draft> drafts) {
        List<PermissionGroup> current = groups(guildId);
        List<String> problems = new ArrayList<>();
        List<PermissionGroup> target = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> usedKeys = new HashSet<>();
        int custom = 0;

        for (PermissionGroup existing : current) {
            if (!existing.builtin()) continue;
            Draft draft = drafts.stream().filter(d -> existing.key().equals(d.key())).findFirst().orElse(null);
            names.add(existing.name().toLowerCase(Locale.ROOT));
            usedKeys.add(existing.key());
            target.add(draft == null ? existing : new PermissionGroup(existing.groupId(), guildId, existing.key(), existing.name(), true,
                    draft.includeHigher(), cleanRoles(guildId, existing.name(), draft.roleIds(), problems)));
        }

        for (Draft draft : drafts) {
            if (draft.key() != null && PermissionGroup.isBuiltinKey(draft.key())) continue;
            String name = draft.name() == null ? "" : draft.name().strip();
            if (name.isEmpty()) {
                problems.add("Every group needs a name.");
                continue;
            }
            if (name.length() > MAX_NAME_LENGTH) problems.add("\"" + name + "\" is too long: group names are at most " + MAX_NAME_LENGTH + " characters.");
            if (!names.add(name.toLowerCase(Locale.ROOT))) problems.add("There is more than one group called \"" + name + "\".");
            if (++custom > MAX_CUSTOM_GROUPS) {
                problems.add("At most " + MAX_CUSTOM_GROUPS + " groups of your own.");
                break;
            }

            final String requested = draft.key();
            String key = requested;
            if (requested != null && current.stream().noneMatch(g -> g.key().equals(requested))) {
                problems.add("\"" + name + "\" was removed in the meantime. Reload the page and try again.");
                continue;
            }
            if (key == null) key = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            if (!usedKeys.add(key)) {
                problems.add("\"" + name + "\" appears twice.");
                continue;
            }
            target.add(new PermissionGroup(0, guildId, key, name, false, draft.includeHigher(), cleanRoles(guildId, name, draft.roleIds(), problems)));
        }

        if (!problems.isEmpty()) throw new InvalidGroupsException(problems);
        repository.replaceAll(guildId, target);
        cache.remove(guildId);
        CacheBus.changed(CACHE_AREA, guildId);
        return groups(guildId);
    }

    private static List<Long> cleanRoles(long guildId, String groupName, List<Long> roleIds, List<String> problems) {
        List<Long> unique = roleIds.stream().distinct().toList();
        if (unique.contains(guildId)) problems.add("\"" + groupName + "\" can't use @everyone: that would be everyone.");
        if (unique.size() > MAX_ROLES_PER_GROUP) problems.add("\"" + groupName + "\" can hold at most " + MAX_ROLES_PER_GROUP + " roles.");
        return unique;
    }

    // ---------- cross-process invalidation (CacheBus) ----------

    @Override
    public String cacheArea() {
        return CACHE_AREA;
    }

    @Override
    public void evictCached(long guildId) {
        cache.remove(guildId);
    }

    @Override
    public void evictAllCached() {
        cache.clear();
    }
}
