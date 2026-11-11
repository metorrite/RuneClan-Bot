package com.runeclans.bot.roletools;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.permission.RoleSafety;
import com.runeclans.bot.roletools.RoleRules.Holder;
import com.runeclans.bot.roletools.RoleRules.Mode;
import com.runeclans.bot.roletools.RoleRules.Status;
import com.runeclans.bot.roletools.RoleToolkitRepository.Job;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The role toolkit. {@code /role give} and {@code /role take} change one member's role, and only roles the caller's permission groups are allowed to touch
 * (Admins: any role the bot may hand out). Bulk jobs change many members at once, and always in two steps a person does: a dry run that only lists who
 * would change, then a button that starts it. A job never starts, resumes or carries on by itself, not even after a restart, and it re-checks every
 * member and the role just before each change. The Member / Guest / Unverified roles belong to the clan role sync and are refused here.
 */
@BService
public class RoleToolkitService {
    private static final Logger log = LoggerFactory.getLogger(RoleToolkitService.class);
    private static final int BATCH = 50;

    private final RoleToolkitRepository repository;
    private final GuildSettingsService settings;
    private final PermissionGroupService groups;
    private final DashboardAccess access;
    private volatile JDA jda;
    private final ExecutorService workers = Executors.newCachedThreadPool((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "role-bulk");
        thread.setDaemon(true);
        return thread;
    });
    /** Replaceable so tests need not wait between changes. */
    long pauseMillis = RoleRules.BULK_PAUSE_MILLIS;

    public RoleToolkitService(RoleToolkitRepository repository, GuildSettingsService settings, PermissionGroupService groups, DashboardAccess access) {
        this.repository = repository;
        this.settings = settings;
        this.groups = groups;
        this.access = access;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        RoleSafety.addPowerSource("role-toolkit", this::toolkitRefs);
    }

    private record Refs(long until, Set<String> refs) {}

    private final ConcurrentHashMap<Long, Refs> toolkitRefs = new ConcurrentHashMap<>();

    /**
     * The groups that may give or take roles with /role ({@code group:<key>} for each group with an allowed role), so the bot never hands
     * out a role of such a group (B-03). Held for 30 seconds: the role check runs often (every chat-level reward), the list rarely changes.
     */
    public Set<String> toolkitRefs(long guildId) {
        long now = System.currentTimeMillis();
        Refs cached = toolkitRefs.get(guildId);
        if (cached != null && cached.until() > now) return cached.refs();
        Set<String> refs = new HashSet<>();
        repository.allowed(guildId).forEach((key, roles) -> {
            if (!roles.isEmpty()) refs.add("group:" + key);
        });
        toolkitRefs.put(guildId, new Refs(now + 30_000, Set.copyOf(refs)));
        return refs;
    }

    /** For tests, which have no Discord connection to wait for. */
    void useJda(JDA jda) {
        this.jda = jda;
    }

    // ---------- who may do what ----------

    public boolean isAdmin(Guild guild, Member member) {
        return access.tierOf(guild, member) == DashboardAccess.Tier.ADMIN;
    }

    /** The roles the caller may give and take: every role on the list of any permission group they are in. */
    public Set<Long> allowedFor(Guild guild, Member member) {
        Set<Long> allowed = new HashSet<>();
        var perGroup = repository.allowed(guild.getIdLong());
        for (PermissionGroup group : groups.groups(guild.getIdLong())) {
            if (perGroup.containsKey(group.key()) && groups.isMember(guild, member, group.key())) allowed.addAll(perGroup.get(group.key()));
        }
        return allowed;
    }

    /** Why this role cannot be changed by the toolkit, or null if it can. */
    public String roleRefusal(Guild guild, Role role) {
        if (role == null) return RoleRules.roleRefusal(false, false, false, false, "");
        GuildSettings s = settings.getEffective(guild.getIdLong());
        Set<Long> syncOwned = new HashSet<>();
        for (Long id : new Long[]{s.verifiedClanRoleId(), s.verifiedNonClanRoleId(), s.unverifiedRoleId()}) if (id != null) syncOwned.add(id);
        return RoleRules.roleRefusal(true, role.isPublicRole(), syncOwned.contains(role.getIdLong()), MemberApi.safeToAssign(guild, role), role.getName());
    }

    /** Why {@code caller} may not give or take {@code role} for {@code target} with /role, or empty if they may. */
    public Optional<String> refuseChange(Guild guild, Member caller, Member target, Role role) {
        String problem = roleRefusal(guild, role);
        if (problem != null) return Optional.of(problem);
        boolean admin = isAdmin(guild, caller);
        if (!RoleRules.mayUse(admin, admin ? Set.of() : allowedFor(guild, caller), role.getIdLong())) {
            return Optional.of("You are not allowed to give or take @" + role.getName() + ". An admin can add it to your group's list on the dashboard.");
        }
        if (target.getUser().isBot() && !admin) return Optional.of("Roles of bots are changed by admins only.");
        if (!admin && !caller.canInteract(role)) return Optional.of("@" + role.getName() + " is above your own highest role.");
        if (!guild.getSelfMember().canInteract(target)) return Optional.of("The bot cannot change " + target.getEffectiveName() + "'s roles: they rank above the bot.");
        return Optional.empty();
    }

    // ---------- bulk jobs ----------

    /** The dry run. Changes no one's roles; it only works out and stores who a job would change. */
    public Job planBulk(Guild guild, Member actor, Mode mode, Role source, Role target) throws Exception {
        String problem = RoleRules.bulkRefusal(source.getIdLong(), target.getIdLong());
        if (problem == null) problem = roleRefusal(guild, target);
        if (problem != null) throw new IllegalArgumentException(problem);
        if (!isAdmin(guild, actor)) throw new SecurityException("Only an Admin can plan a bulk role change.");

        List<Member> holders = membersWith(guild, source);
        List<Holder> seen = new ArrayList<>();
        for (Member m : holders) seen.add(new Holder(m.getIdLong(), true, m.getRoles().contains(target)));
        List<Long> ids = RoleRules.affected(mode, seen);
        if (ids.size() > RoleRules.MAX_BULK_MEMBERS) throw new IllegalArgumentException("That would change " + ids.size() + " members, more than the " + RoleRules.MAX_BULK_MEMBERS + " allowed in one job. Choose a narrower source role.");
        return repository.plan(guild.getIdLong(), mode, source.getIdLong(), target.getIdLong(), actor.getIdLong(), ids);
    }

    /** How long the dry run waits for Discord to list a role's members: it runs inside a website request, which gives up after 8 s. */
    static final long LIST_WAIT_SECONDS = 6;

    /** Everyone holding the role (loaded from Discord if not cached). Separate so tests can stand in for Discord. */
    List<Member> membersWith(Guild guild, Role role) throws Exception {
        return guild.findMembersWithRoles(role).setTimeout(java.time.Duration.ofSeconds(LIST_WAIT_SECONDS)).get();
    }

    /** A person pressed Start (or Resume). Returns the reason it cannot, or null once it is running. */
    public String start(Guild guild, Job job) {
        if (job.status() != Status.PLANNED && job.status() != Status.PAUSED) return "This job is " + job.status().name().toLowerCase() + " and cannot be started.";
        if (repository.hasRunning(guild.getIdLong())) return "Another bulk job is running in this server. Pause or wait for it first.";
        Role target = guild.getRoleById(job.targetRoleId());
        String problem = roleRefusal(guild, target);
        if (problem != null) return problem;
        if (guild.getRoleById(job.sourceRoleId()) == null) return "The source role no longer exists.";
        if (!repository.move(job.id(), job.status(), Status.RUNNING)) return "Someone else already changed this job.";
        workers.submit(() -> run(guild.getIdLong(), job.id()));
        return null;
    }

    public boolean pause(Job job) {
        return RoleRules.canMove(job.status(), Status.PAUSED) && repository.move(job.id(), Status.RUNNING, Status.PAUSED);
    }

    public boolean cancel(Job job) {
        return RoleRules.canMove(job.status(), Status.CANCELLED) && repository.move(job.id(), job.status(), Status.CANCELLED);
    }

    void run(long guildId, long jobId) {
        try {
            while (true) {
                Job job = repository.get(guildId, jobId).orElse(null);
                if (job == null || job.status() != Status.RUNNING) return; // paused or cancelled by a person
                Guild guild = jda == null ? null : Shards.guild(jda, guildId);
                if (guild == null) {
                    repository.move(jobId, Status.RUNNING, Status.PAUSED);
                    return;
                }
                Role source = guild.getRoleById(job.sourceRoleId());
                Role target = guild.getRoleById(job.targetRoleId());
                if (source == null || roleRefusal(guild, target) != null) {
                    log.warn("Bulk job {} stopped: its roles are gone or no longer safe", jobId);
                    repository.move(jobId, Status.RUNNING, Status.PAUSED);
                    return;
                }
                List<Long> batch = repository.pending(jobId, BATCH);
                if (batch.isEmpty()) {
                    repository.move(jobId, Status.RUNNING, Status.DONE);
                    return;
                }
                for (long userId : batch) {
                    Job current = repository.get(guildId, jobId).orElse(null);
                    if (current == null || current.status() != Status.RUNNING) return;
                    repository.finishMember(jobId, userId, changeOne(guild, job, source, target, userId));
                    Thread.sleep(pauseMillis);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Bulk job {} failed", jobId, e);
            repository.move(jobId, Status.RUNNING, Status.PAUSED);
        }
    }

    /** One member of a job. Looks again before changing: they must still be in the server, still hold the source role, and still need the change. */
    private boolean changeOne(Guild guild, Job job, Role source, Role target, long userId) {
        try {
            Member member = guild.retrieveMemberById(userId).complete();
            boolean holdsSource = member.getRoles().contains(source);
            boolean holdsTarget = member.getRoles().contains(target);
            if (RoleRules.affected(job.mode(), List.of(new Holder(userId, holdsSource, holdsTarget))).isEmpty()) return true; // nothing left to do for them
            if (!guild.getSelfMember().canInteract(member)) return false;
            if (job.mode() == Mode.ADD) guild.addRoleToMember(member, target).reason("Bulk role change #" + job.id()).complete();
            else guild.removeRoleFromMember(member, target).reason("Bulk role change #" + job.id()).complete();
            return true;
        } catch (RuntimeException e) {
            log.debug("Bulk job {}: could not change user {}: {}", job.id(), userId, e.getMessage());
            return false;
        }
    }
}
