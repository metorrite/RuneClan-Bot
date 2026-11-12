package com.runeclans.bot.servertag;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.moderation.ModAction;
import com.runeclans.bot.moderation.ModCase;
import com.runeclans.bot.moderation.ModService;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.user.update.UserUpdatePrimaryGuildEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks at the server tag a member shows (the "primary server" on their Discord profile) when they join and when they change it, and
 * reports or acts on a tag from a flagged server, if this server switched that on. The decision is {@link ServerTagRules}; an action goes
 * through {@link ModService} (same hierarchy and protected-role rules, DM with the appeal link, a numbered case). Registered with JDA
 * by {@code Bot} because it is a {@link BotListener}. Profile changes are only seen for members JDA has cached.
 */
@BService
public class ServerTagListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(ServerTagListener.class);
    /** A profile can change several times in a row; one report/action per person is enough for a while. */
    private static final long REPEAT_GUARD_MILLIS = 10 * 60_000;

    private final ServerTagRepository repository;
    private final ModService mod;
    private final AdminRoleFilter admins;
    private final ConcurrentHashMap<String, Long> recent = new ConcurrentHashMap<>();

    public ServerTagListener(ServerTagRepository repository, ModService mod, AdminRoleFilter admins) {
        this.repository = repository;
        this.mod = mod;
        this.admins = admins;
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        check(event.getGuild(), event.getMember());
    }

    @Override
    public void onUserUpdatePrimaryGuild(UserUpdatePrimaryGuildEvent event) {
        try {
            User user = event.getUser();
            for (Guild guild : Shards.mutualGuilds(user)) {
                Member member = guild.getMember(user);
                if (member != null) check(guild, member);
            }
        } catch (Exception e) {
            log.error("Server-tag check failed for a profile change of {}", event.getUser().getId(), e);
        }
    }

    void check(Guild guild, Member member) {
        if (AutoPostRouter.skipSweep("server tag")) return; // reports and acts in the real server: only the live bot does that (T-187, B-82)
        try {
            ServerTagConfig config = repository.get(guild.getIdLong());
            if (!config.enabled()) return;
            User.PrimaryGuild primary = member.getUser().getPrimaryGuild();
            if (primary == null) return;
            boolean staff = member.hasPermission(Permission.ADMINISTRATOR) || admins.isAuthorized(guild, member);
            List<Long> roles = member.getRoles().stream().map(Role::getIdLong).toList();
            if (!ServerTagRules.shouldAct(config, primary.getIdLong(), primary.isIdentityEnabled(), member.getUser().isBot(), guild.getOwnerIdLong() == member.getIdLong(), staff, roles)) return;

            long now = System.currentTimeMillis();
            recent.values().removeIf(at -> now - at > REPEAT_GUARD_MILLIS);
            if (recent.putIfAbsent(guild.getId() + ":" + member.getId(), now) != null) return;

            String reason = ServerTagRules.reason(primary.getTag(), primary.getIdLong());
            ModAction action = config.action().modAction;
            if (action == null) {
                alert(guild, config, member, primary, "Alert only: nothing was done to them.");
                return;
            }
            mod.execute(guild, null, member.getUser(), member, action, reason, action == ModAction.TIMEOUT ? config.timeoutMinutes() : null, 0, ModCase.SERVER_TAG)
                    .whenComplete((outcome, error) -> {
                        if (error != null) {
                            log.warn("Server-tag action on {} in {} failed: {}", member.getId(), guild.getId(), error.getMessage());
                            return;
                        }
                        alert(guild, config, member, primary, outcome.done() ? "They were " + action.past + " (case #" + outcome.modCase().caseNo() + ")." : "Not done: " + outcome.message());
                    });
        } catch (Exception e) {
            log.error("Server-tag check failed for {} in {}", member.getId(), guild.getId(), e);
        }
    }

    private static void alert(Guild guild, ServerTagConfig config, Member member, User.PrimaryGuild primary, String outcome) {
        if (config.alertChannelId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, config.alertChannelId());
        if (channel == null || !channel.canTalk()) return;
        String tag = primary.getTag() == null ? "" : primary.getTag().replace("`", "").strip();
        if (tag.length() > 8) tag = tag.substring(0, 8);
        EmbedBuilder embed = new EmbedBuilder().setTitle("Flagged server tag").setColor(new Color(0xFEE75C))
                .addField("Member", member.getAsMention() + " (" + member.getUser().getName() + ")", true)
                .addField("Tag", "`" + tag + "`", true)
                .addField("Flagged server", "`" + primary.getIdLong() + "`", true)
                .setDescription(outcome).setFooter("ID " + member.getId());
        channel.sendMessageEmbeds(embed.build()).queue(ok -> {}, error -> log.warn("Couldn't post the server-tag alert in {}: {}", channel.getId(), error.getMessage()));
    }
}
