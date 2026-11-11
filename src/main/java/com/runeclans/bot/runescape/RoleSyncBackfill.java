package com.runeclans.bot.runescape;

import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.jobs.JobRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.function.Predicate;

/**
 * Fixes the Member / Guest roles of people who linked their account before the clan role sync existed, or whose change was missed: someone linked and in
 * the clan who is still Guest (promote), or linked and no longer in the clan who still holds Member (demote). It works in two steps that only a person
 * takes: {@link #plan} lists who would change and changes nobody; {@link #start} changes exactly the people a person selected from a fresh list (never
 * a stored one), one every {@value #PAUSE_MILLIS} ms in the background. It touches only the three roles the sync owns (Member, Guest, Unverified), never
 * anyone's other roles, and nothing here ever runs by itself.
 */
@BService
public class RoleSyncBackfill {
    private static final Logger log = LoggerFactory.getLogger(RoleSyncBackfill.class);
    static final long PAUSE_MILLIS = 1_200;
    public static final int MAX_PER_RUN = 150;

    public enum Action { PROMOTE, DEMOTE }

    /** One person the backfill would change. */
    public record Entry(long userId, String name, String rsn, Action action, Set<Long> add, Set<Long> remove) {}

    /** The dry run. {@code problem} is set when nothing can be planned (the roles are not set up). */
    public record Plan(List<Entry> entries, int linkedAccounts, int notInServer, String problem) {}

    /** How a run is going. */
    public record Progress(int total, int done, int failed, boolean running) {}

    private final GuildSettingsService settings;
    private final ClanMemberRepository clanMembers;
    private final PlayerLinkService links;
    /** Each server's latest run, shared by all bot processes so any of them can show its progress (T-698). */
    private final JobRepository jobs;
    private final ExecutorService worker = Executors.newCachedThreadPool((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "role-sync-backfill");
        thread.setDaemon(true);
        return thread;
    });
    /** Replaceable so tests need not wait between changes. */
    long pauseMillis = PAUSE_MILLIS;

    public RoleSyncBackfill(GuildSettingsService settings, ClanMemberRepository clanMembers, PlayerLinkService links, JobRepository jobs) {
        this.jobs = jobs;
        this.settings = settings;
        this.clanMembers = clanMembers;
        this.links = links;
    }

    // ---------- the decision (pure) ----------

    /**
     * What to change for one person, or empty if they are already right. A person in the clan who lacks the Member role gets it, and loses Guest and
     * Unverified (the same swap the sync does when someone joins). A person not in the clan who holds Member gets Guest and loses Member (the same swap
     * as when someone leaves); that needs both roles to be set. A role that no longer exists is never used.
     */
    static Optional<Entry> decide(long userId, String name, String rsn, boolean inClan, Set<Long> held, Long clanRole, Long nonClanRole, Long unverifiedRole, Predicate<Long> exists) {
        Set<Long> add = new HashSet<>();
        Set<Long> remove = new HashSet<>();
        if (inClan) {
            if (clanRole == null || !exists.test(clanRole) || held.contains(clanRole)) return Optional.empty();
            add.add(clanRole);
            if (nonClanRole != null && !nonClanRole.equals(clanRole) && held.contains(nonClanRole)) remove.add(nonClanRole);
            if (unverifiedRole != null && !unverifiedRole.equals(clanRole) && held.contains(unverifiedRole)) remove.add(unverifiedRole);
            return Optional.of(new Entry(userId, name, rsn, Action.PROMOTE, add, remove));
        }
        if (clanRole == null || nonClanRole == null || !exists.test(clanRole) || !exists.test(nonClanRole) || clanRole.equals(nonClanRole)) return Optional.empty();
        if (!held.contains(clanRole)) return Optional.empty();
        remove.add(clanRole);
        if (!held.contains(nonClanRole)) add.add(nonClanRole);
        return Optional.of(new Entry(userId, name, rsn, Action.DEMOTE, add, remove));
    }

    // ---------- the dry run ----------

    /** Works out who would change. Changes nobody. */
    public Plan plan(Guild guild) {
        long guildId = guild.getIdLong();
        GuildSettings s = settings.getEffective(guildId);
        if (s.verifiedClanRoleId() == null) return new Plan(List.of(), 0, 0, "The Member role is not set up (Server setup > Verification), so there is nothing to fix.");

        Map<Long, List<PlayerLink>> byUser = new LinkedHashMap<>();
        for (PlayerLink link : links.getAllLinks(guildId)) byUser.computeIfAbsent(link.discordUserId(), k -> new ArrayList<>()).add(link);

        Map<Long, Member> members = new java.util.HashMap<>();
        try {
            // a website request waits for this (and gives up after 8 s), so Discord gets a time limit; a slow answer reads as "try again"
            for (Member m : guild.retrieveMembersByIds(byUser.keySet()).setTimeout(java.time.Duration.ofSeconds(6)).get()) members.put(m.getIdLong(), m);
        } catch (Exception e) {
            log.warn("Could not load the linked members of guild {} for the role fix", guildId, e);
            return new Plan(List.of(), byUser.size(), 0, "Discord could not list the members just now. Try again in a moment.");
        }

        List<Entry> entries = new ArrayList<>();
        int notInServer = 0;
        for (var each : byUser.entrySet()) {
            Member member = members.get(each.getKey());
            if (member == null) {
                notInServer++;
                continue;
            }
            if (member.getUser().isBot()) continue;
            String inClanRsn = each.getValue().stream().map(PlayerLink::rsn).filter(rsn -> clanMembers.isActiveMember(guildId, rsn)).findFirst().orElse(null);
            Set<Long> held = new HashSet<>();
            for (Role role : member.getRoles()) held.add(role.getIdLong());
            decide(member.getIdLong(), member.getEffectiveName(), inClanRsn != null ? inClanRsn : each.getValue().getFirst().rsn(), inClanRsn != null, held,
                    s.verifiedClanRoleId(), s.verifiedNonClanRoleId(), s.unverifiedRoleId(), id -> guild.getRoleById(id) != null).ifPresent(entries::add);
        }
        entries.sort(java.util.Comparator.comparing(Entry::action).thenComparing(e -> e.name().toLowerCase()));
        return new Plan(entries, byUser.size(), notInServer, null);
    }

    // ---------- the button ----------

    /**
     * Progress of the last run in this server, or null if none ran. A run whose process stopped part way reads as not running. Throws
     * {@link IllegalStateException} when the database can't be reached, so a caller never reads that as "nothing running".
     */
    public Progress progress(long guildId) {
        return jobs.latest(JobRepository.ROLE_FIX, guildId).map(row -> new Progress(row.total(), row.done(), row.failed(), row.live(jobs.now()))).orElse(null);
    }

    /**
     * A person pressed Apply with a list of people they selected. A FRESH plan is made and only the people in both lists are changed, so a stale or
     * edited list can never change anyone who no longer needs it. Returns a refusal, or null once the run has started.
     */
    public String start(Guild guild, Set<Long> selected, long actorId) {
        if (selected.isEmpty()) return "Select at least one person.";
        if (selected.size() > MAX_PER_RUN) return "Select at most " + MAX_PER_RUN + " people at a time.";
        Progress current;
        try {
            current = progress(guild.getIdLong());
        } catch (IllegalStateException e) {
            return "The bot could not reach its database just now. Try again in a moment.";
        }
        if (current != null && current.running()) return "A fix is already running in this server.";
        Plan plan = plan(guild);
        if (plan.problem() != null) return plan.problem();
        List<Entry> todo = plan.entries().stream().filter(e -> selected.contains(e.userId())).toList();
        if (todo.isEmpty()) return "None of those people need a change any more.";

        JobRepository.Claim claim;
        try {
            claim = jobs.claim(JobRepository.ROLE_FIX, guild.getIdLong(), "role-fix");
        } catch (IllegalStateException e) {
            log.warn("Could not start the role fix in guild {}", guild.getIdLong(), e);
            return "The bot could not reach its database just now. Try again in a moment.";
        }
        // the slot is taken across every bot process: a second press elsewhere is refused here
        if (!claim.won()) return "A fix is already running in this server.";
        save(guild.getIdLong(), claim.row(), todo.size(), 0, 0, true);
        worker.submit(() -> run(guild, todo, actorId, claim.row()));
        return null;
    }

    /** Saves the run's progress; false once another run has taken the server's slot over (this one went quiet too long), so it must stop. */
    private boolean save(long guildId, JobRepository.Row started, int total, int done, int failed, boolean running) {
        JobRepository.Row row = new JobRepository.Row(started.runId(), started.kind(), running ? "running" : "done", done, total, failed, "", started.startedAt(),
                running ? null : jobs.now(), jobs.now());
        return running ? jobs.save(JobRepository.ROLE_FIX, guildId, row) : jobs.saveFinal(JobRepository.ROLE_FIX, guildId, row);
    }

    void run(Guild guild, List<Entry> todo, long actorId, JobRepository.Row started) {
        int done = 0;
        int failed = 0;
        ScheduledFuture<?> alive = jobs.keepAlive(JobRepository.ROLE_FIX, guild.getIdLong(), started.runId());
        boolean mine = true;
        try {
            for (Entry entry : todo) {
                // before each change: a run that went quiet and was taken over stops before it touches anyone else
                mine = save(guild.getIdLong(), started, todo.size(), done, failed, true);
                if (!mine) break;
                try {
                    Member member = guild.retrieveMemberById(entry.userId()).complete();
                    List<Role> add = entry.add().stream().map(guild::getRoleById).filter(r -> r != null && MemberApi.safeToAssign(guild, r)).toList();
                    List<Role> remove = entry.remove().stream().map(guild::getRoleById).filter(r -> r != null).toList();
                    if (guild.getSelfMember().canInteract(member)) {
                        guild.modifyMemberRoles(member, add, remove).reason("Clan role fix by " + actorId).complete();
                        done++;
                    } else {
                        failed++;
                    }
                } catch (RuntimeException e) {
                    log.debug("Role fix: could not change user {}: {}", entry.userId(), e.getMessage());
                    failed++;
                }
                mine = save(guild.getIdLong(), started, todo.size(), done, failed, true);
                if (!mine) {
                    log.warn("Role fix in guild {}: another run took over after this one went quiet; stopping", guild.getIdLong());
                    break;
                }
                Thread.sleep(pauseMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            alive.cancel(false);
            if (mine) save(guild.getIdLong(), started, todo.size(), done, failed, false);
        }
    }
}
