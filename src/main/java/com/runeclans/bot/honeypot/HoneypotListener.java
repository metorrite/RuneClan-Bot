package com.runeclans.bot.honeypot;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.honeypot.HoneypotRules.Trigger;
import com.runeclans.bot.moderation.ModAction;
import com.runeclans.bot.moderation.ModCase;
import com.runeclans.bot.moderation.ModService;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Acts on whoever posts in a server's decoy channel or takes its decoy role, if that server switched the honeypot on. The decision is
 * {@link HoneypotRules}; the action goes through {@link ModService}, so the same hierarchy and protected-role rules apply, the member gets
 * the DM with the appeal link, and a numbered case is written. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class HoneypotListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(HoneypotListener.class);
    /** One action per person is enough: a burst of messages in the decoy channel must not repeat it. */
    private static final long REPEAT_GUARD_MILLIS = 60_000;

    private final HoneypotRepository repository;
    private final ModService mod;
    private final AdminRoleFilter admins;
    private final ConcurrentHashMap<String, Long> recent = new ConcurrentHashMap<>();

    public HoneypotListener(HoneypotRepository repository, ModService mod, AdminRoleFilter admins) {
        this.repository = repository;
        this.mod = mod;
        this.admins = admins;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (AutoPostRouter.skipSweep("honeypot")) return; // deletes, kicks, bans and alerts in the real server: only the live bot does that (T-187, B-82)
        try {
            if (!event.isFromGuild() || event.isWebhookMessage() || event.getAuthor().isBot() || event.getMember() == null) return;
            Guild guild = event.getGuild();
            HoneypotConfig config = repository.get(guild.getIdLong());
            if (!config.enabled() || config.channelId() == null || config.channelId() != event.getChannel().getIdLong()) return;
            Member member = event.getMember();
            if (!decide(config, guild, member, Trigger.POSTED_IN_CHANNEL, event.getChannel().getIdLong(), 0)) return;
            // the decoy message goes too, so a raid bot's links don't sit there for real members
            event.getMessage().delete().queue(ok -> {}, error -> log.debug("Couldn't delete the decoy message in {}: {}", event.getChannel().getId(), error.getMessage()));
            act(guild, member, config, Trigger.POSTED_IN_CHANNEL);
        } catch (Exception e) {
            log.error("Honeypot failed for a message in guild {}", event.getGuild().getId(), e);
        }
    }

    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        if (AutoPostRouter.skipSweep("honeypot")) return;
        try {
            Guild guild = event.getGuild();
            HoneypotConfig config = repository.get(guild.getIdLong());
            if (!config.enabled() || config.roleId() == null) return;
            Role decoy = event.getRoles().stream().filter(r -> r.getIdLong() == config.roleId()).findFirst().orElse(null);
            if (decoy == null) return;
            Member member = event.getMember();
            if (!decide(config, guild, member, Trigger.TOOK_ROLE, 0, decoy.getIdLong())) return;
            act(guild, member, config, Trigger.TOOK_ROLE);
        } catch (Exception e) {
            log.error("Honeypot failed for a role change in guild {}", event.getGuild().getId(), e);
        }
    }

    private boolean decide(HoneypotConfig config, Guild guild, Member member, Trigger trigger, long channelId, long roleId) {
        boolean staff = member.hasPermission(Permission.ADMINISTRATOR) || admins.isAuthorized(guild, member);
        List<Long> roles = member.getRoles().stream().map(Role::getIdLong).toList();
        return HoneypotRules.shouldAct(config, trigger, channelId, roleId, member.getUser().isBot(), guild.getOwnerIdLong() == member.getIdLong(), staff, roles);
    }

    private void act(Guild guild, Member member, HoneypotConfig config, Trigger trigger) {
        long now = System.currentTimeMillis();
        recent.values().removeIf(at -> now - at > REPEAT_GUARD_MILLIS);
        if (recent.putIfAbsent(guild.getId() + ":" + member.getId(), now) != null) return;
        ModAction action = config.action().modAction;
        mod.execute(guild, null, member.getUser(), member, action, HoneypotRules.reason(trigger), action == ModAction.TIMEOUT ? config.timeoutMinutes() : null,
                action == ModAction.BAN ? config.deleteDays() : 0, ModCase.HONEYPOT).whenComplete((outcome, error) -> {
            if (error != null) log.warn("Honeypot action on {} in {} failed: {}", member.getId(), guild.getId(), error.getMessage());
            else if (!outcome.done()) log.info("Honeypot did not act on {} in {}: {}", member.getId(), guild.getId(), outcome.message());
            else log.info("Honeypot: {} {} in {} (case {})", member.getId(), action, guild.getId(), outcome.modCase().caseNo());
        });
    }
}
