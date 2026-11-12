package com.runeclans.bot.autorole;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.ShardRouting;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import net.dv8tion.jda.api.JDA;

/**
 * Gives a new member the roles their server chose. Only ever ordinary roles the bot is allowed to hand out (see
 * {@link MemberApi#safeToAssign}): a role with powerful permissions is refused even if it was saved earlier, because roles can change
 * after a server chose them. A missing role or one the bot can no longer give is skipped and logged, never an error that stops the rest.
 */
@BService
public class AutoroleService {
    private static final Logger log = LoggerFactory.getLogger(AutoroleService.class);

    private final AutoroleRepository repository;
    private final ScheduledExecutorService executor = BotScheduler.lane("autorole");

    public AutoroleService(AutoroleRepository repository) {
        this.repository = repository;
    }

    /** A real join: gives this server's autoroles, now or after its delay. Never throws; a failure is logged. */
    public void onJoin(Member member) {
        if (AutoPostRouter.skipSweep("autorole on join")) return; // gives roles in the real server: only the live bot does that (T-187, B-82)
        Guild guild = member.getGuild();
        AutoroleConfig config = repository.get(guild.getIdLong());
        if (!config.enabled() || !config.anythingFor(member.getUser().isBot())) return;

        if (config.delaySeconds() <= 0) {
            give(member, config);
        } else {
            // kept in the database, so a restart during the wait doesn't lose them
            repository.addPending(guild.getIdLong(), member.getIdLong(), Instant.now().plusSeconds(config.delaySeconds()));
        }
    }

    /** They left before their roles were due: nothing to give. */
    public void onLeave(long guildId, long userId) {
        repository.removePending(guildId, userId);
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> sweep(jda), 30, 15, TimeUnit.SECONDS);
    }

    /** Gives the roles of everyone whose delay has passed. */
    void sweep(JDA jda) {
        if (AutoPostRouter.skipSweep("delayed autorole")) return; // gives roles in the real server: only the live bot does that (T-187)
        try {
            for (AutoroleRepository.Pending due : repository.duePending(Instant.now(), 100)) {
                Guild guild = Shards.guild(jda, due.guildId());
                if (guild == null) {
                    // only a server this process can see is missing from is really left; another shard's server waits for its owner (T-700)
                    if (ShardRouting.gone(jda, due.guildId())) repository.removePending(due.guildId(), due.userId());
                    continue;
                }
                // looked up afresh: they may have left in the meantime, and a settings change during the wait should apply
                guild.retrieveMemberById(due.userId()).queue(member -> {
                    give(member, repository.get(due.guildId()));
                    repository.removePending(due.guildId(), due.userId());
                }, error -> repository.removePending(due.guildId(), due.userId()));
            }
        } catch (Exception e) {
            log.error("Autorole sweep failed", e);
        }
    }

    /** Whether the bot may take {@code role} away from members: an ordinary role, below the bot's own. (Taking a powerful role away is harmless, so it is allowed.) */
    public static boolean canTakeAway(Guild guild, Role role) {
        return !role.isPublicRole() && !role.isManaged() && guild.getSelfMember().canInteract(role);
    }

    private void give(Member member, AutoroleConfig config) {
        if (!config.enabled()) return;
        Guild guild = member.getGuild();
        List<Role> toAdd = new ArrayList<>();
        for (long roleId : config.rolesFor(member.getUser().isBot())) {
            Role role = guild.getRoleById(roleId);
            if (role == null) {
                log.warn("Autorole: role {} in guild {} no longer exists", roleId, guild.getId());
            } else if (!MemberApi.safeToAssign(guild, role)) {
                log.warn("Autorole: @{} in guild {} can't be given (managed, too powerful, or above the bot's role)", role.getName(), guild.getId());
            } else if (!member.getRoles().contains(role)) {
                toAdd.add(role);
            }
        }
        List<Role> toRemove = new ArrayList<>();
        for (long roleId : config.removalsFor(member.getUser().isBot())) {
            Role role = guild.getRoleById(roleId);
            if (role == null) {
                log.warn("Autorole: role {} to take away in guild {} no longer exists", roleId, guild.getId());
            } else if (!canTakeAway(guild, role)) {
                log.warn("Autorole: @{} in guild {} can't be taken away (managed, or above the bot's role)", role.getName(), guild.getId());
            } else if (member.getRoles().contains(role) && !toAdd.contains(role)) {
                toRemove.add(role);
            }
        }
        // One request per role (add or remove just that role), never modifyMemberRoles: that sends the member's whole role list built from
        // the cached roles, so on a join it would overwrite the kept roles RoleKeepService gives in the same moment, or be overwritten by them (B-89).
        for (Role role : toAdd) {
            guild.addRoleToMember(member, role).reason("Autorole").queue(
                    done -> {},
                    error -> log.warn("Autorole: couldn't give {} @{} in guild {}", member.getId(), role.getName(), guild.getId(), error));
        }
        for (Role role : toRemove) {
            guild.removeRoleFromMember(member, role).reason("Autorole").queue(
                    done -> {},
                    error -> log.warn("Autorole: couldn't take @{} from {} in guild {}", role.getName(), member.getId(), guild.getId(), error));
        }
    }
}
