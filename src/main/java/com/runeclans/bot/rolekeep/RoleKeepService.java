package com.runeclans.bot.rolekeep;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.rolekeep.RoleKeepRepository.Kept;
import com.runeclans.bot.rolekeep.RoleKeepRepository.Temp;
import com.runeclans.bot.rolekeep.RoleKeepRules.Source;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Roles that outlast a visit. <b>Sticky roles</b>: a server's list of roles that come back when a member rejoins. <b>Role persist</b>: an admin marks a
 * role on one member to be given back whenever they rejoin. <b>Temp roles</b>: a role that is taken off by itself after a time, kept across restarts and
 * rejoins. Safety first: the Member / Guest / Unverified roles belong to the clan role sync and are never touched here (that sync always wins); a staff
 * permission role is never given back automatically; a role the bot may not hand out is refused. The checks run when something is set, when it is stored,
 * and again right before it is given back. Nothing here listens to role changes, so it can never answer one of its own changes with another.
 */
@BService
public class RoleKeepService {
    private static final Logger log = LoggerFactory.getLogger(RoleKeepService.class);
    private static final int EXPIRY_BATCH = 100;

    private final RoleKeepRepository repository;
    private final GuildSettingsService settings;
    private final PermissionGroupService groups;
    private final ScheduledExecutorService executor = BotScheduler.lane("role-keep");

    public RoleKeepService(RoleKeepRepository repository, GuildSettingsService settings, PermissionGroupService groups) {
        this.repository = repository;
        this.settings = settings;
        this.groups = groups;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> expire(jda), 1, 1, TimeUnit.MINUTES);
    }

    // ---------- safety ----------

    /** Why this role cannot be kept, timed or made sticky, or null. */
    public String refusal(Guild guild, Role role) {
        if (role == null) return RoleKeepRules.roleRefusal(false, false, false, false, false, "");
        return RoleKeepRules.roleRefusal(true, role.isPublicRole(), syncOwned(guild).contains(role.getIdLong()), staffRoles(guild).contains(role.getIdLong()), MemberApi.safeToAssign(guild, role), role.getName());
    }

    /** Whether the role may be given back automatically right now. */
    boolean mayRestore(Guild guild, long roleId) {
        return refusal(guild, guild.getRoleById(roleId)) == null;
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

    // ---------- leaving and rejoining ----------

    /** A member left: remember the sticky-list roles they held. */
    public void onLeave(Guild guild, long userId, List<Role> held) {
        Set<Long> sticky = repository.sticky(guild.getIdLong());
        if (sticky.isEmpty()) return;
        Set<Long> heldIds = new HashSet<>();
        for (Role role : held) heldIds.add(role.getIdLong());
        for (long roleId : RoleKeepRules.rolesToStore(heldIds, sticky, id -> mayRestore(guild, id))) {
            repository.keep(guild.getIdLong(), userId, roleId, Source.STICKY, 0);
        }
    }

    /** A member joined: give back what is remembered for them and any timed role still running. */
    public void onJoin(Member member) {
        if (AutoPostRouter.skipSweep("kept roles on join")) return; // gives roles in the real server: only the live bot does that (T-187, B-82)
        Guild guild = member.getGuild();
        long guildId = guild.getIdLong();
        Set<Long> remembered = new HashSet<>();
        for (Kept kept : repository.keptFor(guildId, member.getIdLong())) remembered.add(kept.roleId());
        Set<Long> temp = new HashSet<>();
        for (Temp t : repository.tempFor(guildId, member.getIdLong())) temp.add(t.roleId());
        if (remembered.isEmpty() && temp.isEmpty()) return;

        Set<Long> held = new HashSet<>();
        for (Role role : member.getRoles()) held.add(role.getIdLong());
        List<Role> give = RoleKeepRules.toReapply(remembered, temp, held, id -> mayRestore(guild, id)).stream().map(guild::getRoleById).toList();
        // what came from the sticky list is given once; it is remembered again only if they leave again
        repository.forgetSticky(guildId, member.getIdLong());
        if (give.isEmpty() || !guild.getSelfMember().canInteract(member)) return;
        log.info("Giving {} kept role(s) back to {} in guild {}", give.size(), member.getId(), guildId);
        // one request per role, never modifyMemberRoles: that sends the whole role list from the cached member and would overwrite (or be
        // overwritten by) the autoroles AutoroleService gives on the same join (B-89)
        for (Role role : give) {
            guild.addRoleToMember(member, role).reason("Roles kept from before they left")
                    .queue(ok -> {}, e -> log.warn("Could not give kept role {} back to {}: {}", role.getId(), member.getId(), e.getMessage()));
        }
    }

    // ---------- the things a person sets ----------

    /** Replaces the sticky list. Returns the first refusal, or null once saved. */
    public String saveSticky(Guild guild, Set<Long> roleIds) {
        if (roleIds.size() > RoleKeepRules.MAX_STICKY_ROLES) return "At most " + RoleKeepRules.MAX_STICKY_ROLES + " sticky roles.";
        for (long id : roleIds) {
            String problem = refusal(guild, guild.getRoleById(id));
            if (problem != null) return problem;
        }
        repository.setSticky(guild.getIdLong(), roleIds);
        return null;
    }

    /** /rolepersist add: remember the role for this member, and give it now if they lack it. Returns a refusal or null. */
    public String persist(Guild guild, long actorId, Member target, Role role) {
        String problem = refusal(guild, role);
        if (problem != null) return problem;
        if (target.getUser().isBot()) return "Bots do not rejoin; there is nothing to keep.";
        if (repository.countManual(guild.getIdLong(), target.getIdLong()) >= RoleKeepRules.MAX_PERSISTED_PER_MEMBER) return "That member already has the most kept roles allowed.";
        repository.keep(guild.getIdLong(), target.getIdLong(), role.getIdLong(), Source.MANUAL, actorId);
        if (!target.getRoles().contains(role) && guild.getSelfMember().canInteract(target)) {
            guild.addRoleToMember(target, role).reason("Kept role added by " + actorId).queue(ok -> {}, e -> log.warn("Could not give {} the kept role: {}", target.getId(), e.getMessage()));
        }
        return null;
    }

    /** /rolepersist remove: stop remembering. The role they hold now is left alone. */
    public boolean unpersist(Guild guild, long userId, long roleId) {
        return repository.forget(guild.getIdLong(), userId, roleId);
    }

    /** /temprole: give the role now and take it off after {@code length}. Returns a refusal or null. */
    public String giveTemp(Guild guild, long actorId, Member target, Role role, Duration length) {
        String problem = refusal(guild, role);
        if (problem != null) return problem;
        if (!guild.getSelfMember().canInteract(target)) return "The bot cannot change " + target.getEffectiveName() + "'s roles: they rank above the bot.";
        repository.setTemp(guild.getIdLong(), target.getIdLong(), role.getIdLong(), Instant.now().plus(length), actorId);
        if (!target.getRoles().contains(role)) {
            guild.addRoleToMember(target, role).reason("Temporary role from " + actorId).queue(ok -> {}, e -> log.warn("Could not give {} the temporary role: {}", target.getId(), e.getMessage()));
        }
        return null;
    }

    /** Ends a timed role now: takes it off the member (if they are here and still hold it) and forgets it. */
    public boolean endTemp(Guild guild, Temp temp) {
        if (!repository.deleteTemp(guild.getIdLong(), temp.id())) return false;
        takeOff(guild, temp);
        return true;
    }

    // ---------- the clock ----------

    void expire(JDA jda) {
        if (AutoPostRouter.skipSweep("timed role")) return; // takes roles away in the real server: only the live bot does that (T-187)
        try {
            for (Temp temp : repository.expired(EXPIRY_BATCH)) {
                Guild guild = Shards.guild(jda, temp.guildId());
                if (guild == null) continue; // the bot is not there right now: try again next round
                if (repository.deleteTemp(temp.guildId(), temp.id())) takeOff(guild, temp);
            }
        } catch (RuntimeException e) {
            log.error("Timed role check failed", e);
        }
    }

    /** Takes a timed role off, but never one the clan role sync owns, and only if the member is here and still has it. */
    private void takeOff(Guild guild, Temp temp) {
        Role role = guild.getRoleById(temp.roleId());
        if (role == null || syncOwned(guild).contains(role.getIdLong())) return;
        guild.retrieveMemberById(temp.userId()).queue(member -> {
            if (member.getRoles().contains(role) && guild.getSelfMember().canInteract(role) && guild.getSelfMember().canInteract(member)) {
                guild.removeRoleFromMember(member, role).reason("Temporary role ended").queue(ok -> {}, e -> log.warn("Could not end the temporary role of {}: {}", member.getId(), e.getMessage()));
            }
        }, e -> log.debug("Timed role member {} is not in the server", temp.userId()));
    }
}
