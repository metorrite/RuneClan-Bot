package com.runeclans.bot.rolerules;

import com.runeclans.bot.cachebus.CacheBus;
import com.runeclans.bot.cachebus.CacheInvalidator;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.rolerules.RoleRuleRules.Change;
import com.runeclans.bot.rolerules.RoleRuleRules.Rule;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Connected and trigger roles: when a member gains or loses roles, the server's rules add and take away other roles. Four guards keep it from ever
 * running away or fighting something else:
 * <ol>
 *   <li><b>Saved rules are checked as a whole.</b> A rule may not change a role that sets it off, and no set of rules may form a loop (see
 *       {@link RoleRuleRules#findCycle}).</li>
 *   <li><b>Targets are restricted.</b> A rule may never give or take the Member / Guest / Unverified roles (the clan role sync owns them and always wins),
 *       a staff permission-group role, or a role the bot may not hand out. The same check is made again before every change.</li>
 *   <li><b>Chains are cut.</b> A change a rule made that sets off another rule counts one step; a chain stops after {@value RoleRuleRules#MAX_CHAIN_DEPTH} steps.</li>
 *   <li><b>A member is rate-limited.</b> At most {@value RoleRuleRules#MAX_CHANGES_PER_MEMBER_PER_MINUTE} rule-made changes per member per minute.</li>
 * </ol>
 * Rules only react to changes that happen from now on; saving or switching on a rule changes nobody's roles by itself.
 */
@BService
public class RoleRuleService implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "role-rules";

    private static final Logger log = LoggerFactory.getLogger(RoleRuleService.class);
    private static final long CHAIN_WINDOW_MILLIS = 20_000;

    private record Hop(int depth, Set<Long> added, Set<Long> removed, long until) {}

    private final RoleRuleRepository repository;
    private final GuildSettingsService settings;
    private final PermissionGroupService groups;
    private final Map<String, Hop> hops = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Long>> recent = new ConcurrentHashMap<>();
    private final Map<Long, List<Rule>> cache = new ConcurrentHashMap<>();
    /** Replaceable so tests can move time without waiting. */
    LongSupplier clock = System::currentTimeMillis;

    public RoleRuleService(RoleRuleRepository repository, GuildSettingsService settings, PermissionGroupService groups) {
        this.repository = repository;
        this.settings = settings;
        this.groups = groups;
    }

    // ---------- safety ----------

    /** Why this role may not be given or taken by a rule, or null. */
    public String targetRefusal(Guild guild, Role role) {
        if (role == null) return "That role does not exist in this server.";
        if (role.isPublicRole()) return "@everyone cannot be given or taken.";
        if (syncOwned(guild).contains(role.getIdLong())) return "@" + role.getName() + " is kept by the clan role sync (Member / Guest / Unverified), which always wins, so rules cannot change it.";
        if (staffRoles(guild).contains(role.getIdLong())) return "@" + role.getName() + " is one of this server's staff permission roles, which rules never give or take away.";
        if (!MemberApi.safeToAssign(guild, role)) return MemberApi.handOutRefusal(guild, role);
        return null;
    }

    private Set<Long> syncOwned(Guild guild) {
        GuildSettings s = settings.getEffective(guild.getIdLong());
        Set<Long> ids = new HashSet<>();
        for (Long id : new Long[]{s.verifiedClanRoleId(), s.verifiedNonClanRoleId(), s.unverifiedRoleId()}) if (id != null) ids.add(id);
        return ids;
    }

    private Set<Long> staffRoles(Guild guild) {
        Set<Long> ids = new HashSet<>();
        for (PermissionGroup group : groups.groups(guild.getIdLong())) if (group.builtin()) ids.addAll(group.roleIds());
        return ids;
    }

    /** The reason a rule cannot be saved among the others, or null. Checks its shape, its target roles, and loops with the existing rules. */
    public String problemWith(Guild guild, Rule rule, List<Rule> others) {
        String shape = RoleRuleRules.shapeProblem(rule);
        if (shape != null) return shape;
        for (long id : concat(rule.add(), rule.remove())) {
            String refusal = targetRefusal(guild, guild.getRoleById(id));
            if (refusal != null) return refusal;
        }
        for (long id : rule.when()) if (guild.getRoleById(id) == null) return "A role that sets this rule off no longer exists.";
        List<Rule> all = new ArrayList<>(others.stream().filter(r -> r.id() != rule.id()).toList());
        all.add(new Rule(rule.id() == 0 ? -1 : rule.id(), rule.guildId(), rule.name(), rule.event(), rule.when(), rule.add(), rule.remove(), true));
        List<Long> cycle = RoleRuleRules.findCycle(all);
        if (!cycle.isEmpty()) {
            return "These rules would loop: " + String.join(" then ", cycle.stream().map(id -> roleName(guild, id)).toList()) + ". A rule cannot lead back to a role that sets off an earlier one.";
        }
        return null;
    }

    private static String roleName(Guild guild, long id) {
        Role role = guild.getRoleById(id);
        return role == null ? "(deleted role)" : "@" + role.getName();
    }

    private static List<Long> concat(List<Long> a, List<Long> b) {
        List<Long> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    // ---------- rules ----------

    public void changed(long guildId) {
        cache.remove(guildId);
        CacheBus.changed(CACHE_AREA, guildId);
    }

    private List<Rule> rules(long guildId) {
        return cache.computeIfAbsent(guildId, repository::list);
    }

    // ---------- reacting to role changes ----------

    /** A member's roles just changed ({@code changed} gained or lost). Applies whatever the rules ask, within the guards. */
    public void onRoleChange(Guild guild, Member member, Set<Long> changed, boolean added) {
        List<Rule> rules = rules(guild.getIdLong());
        if (rules.isEmpty() || member.getUser().isBot()) return;

        String key = guild.getIdLong() + ":" + member.getIdLong();
        long now = clock.getAsLong();
        int depth = depthOf(key, changed, added, now);
        if (!RoleRuleRules.chainAllowed(depth)) {
            log.warn("Role rules in guild {} stopped a chain of {} steps for user {}", guild.getIdLong(), depth, member.getId());
            return;
        }

        Set<Long> held = new HashSet<>();
        for (Role role : member.getRoles()) held.add(role.getIdLong());
        Change change = RoleRuleRules.changesFor(rules, held, changed, added);
        if (change.isEmpty()) return;

        // look again at every target right before the change: a role may have become the clan sync's or a staff role since the rule was saved
        List<Role> add = change.add().stream().map(guild::getRoleById).filter(r -> r != null && targetRefusal(guild, r) == null).toList();
        List<Role> remove = change.remove().stream().map(guild::getRoleById).filter(r -> r != null && targetRefusal(guild, r) == null).toList();
        if (add.isEmpty() && remove.isEmpty()) return;
        if (!guild.getSelfMember().canInteract(member)) return;
        if (!withinRate(key, now)) {
            log.warn("Role rules in guild {} hit the per-member limit for user {}", guild.getIdLong(), member.getId());
            return;
        }

        hops.put(key, new Hop(depth + 1, ids(add), ids(remove), now + CHAIN_WINDOW_MILLIS));
        log.info("Role rules: user {} in guild {}: add {}, remove {}", member.getId(), guild.getIdLong(), ids(add), ids(remove));
        guild.modifyMemberRoles(member, add, remove).reason("Role rule").queue(ok -> {}, e -> log.warn("Role rule change for {} failed: {}", member.getId(), e.getMessage()));
    }

    /** How many rule-made changes led to this one: 0 for a change nobody here made, else one more than the change it follows. */
    private int depthOf(String key, Set<Long> changed, boolean added, long now) {
        Hop hop = hops.get(key);
        if (hop == null || hop.until() < now) {
            hops.remove(key);
            return 0;
        }
        Set<Long> ours = added ? hop.added() : hop.removed();
        return ours.containsAll(changed) || changed.stream().anyMatch(ours::contains) ? hop.depth() : 0;
    }

    private boolean withinRate(String key, long now) {
        ArrayDeque<Long> times = recent.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < now - 60_000) times.pollFirst();
            if (times.size() >= RoleRuleRules.MAX_CHANGES_PER_MEMBER_PER_MINUTE) return false;
            times.addLast(now);
            return true;
        }
    }

    private static Set<Long> ids(List<Role> roles) {
        Set<Long> ids = new HashSet<>();
        for (Role role : roles) ids.add(role.getIdLong());
        return ids;
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
