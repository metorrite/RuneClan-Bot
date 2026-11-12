package com.runeclans.bot.modlog;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.modlog.ModLogMessage.Kind;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateTimeOutEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Writes bans, unbans and timeouts to the server's moderation log channel. Who did it and why come from the audit log and the ban list,
 * and are left out (not guessed) when JonnyBot lacks the permission to read them. Registered with JDA by {@code Bot} because it is a
 * {@link BotListener}.
 */
@BService
public class ModLogListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(ModLogListener.class);
    /** The audit log entry for something that just happened is at most seconds old; older ones are about something else. */
    private static final Duration RECENT = Duration.ofMinutes(2);

    private final ModLogRepository repository;

    public ModLogListener(ModLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onGuildBan(GuildBanEvent event) {
        if (AutoPostRouter.skipSweep("moderation log")) return; // posts in the real server: only the live bot does that (B-82 follow-up)
        record(event.getGuild(), event.getUser(), Kind.BAN, null);
    }

    @Override
    public void onGuildUnban(GuildUnbanEvent event) {
        if (AutoPostRouter.skipSweep("moderation log")) return;
        record(event.getGuild(), event.getUser(), Kind.UNBAN, null);
    }

    @Override
    public void onGuildMemberUpdateTimeOut(GuildMemberUpdateTimeOutEvent event) {
        if (AutoPostRouter.skipSweep("moderation log")) return;
        OffsetDateTime now = event.getNewTimeOutEnd();
        boolean started = now != null && now.isAfter(OffsetDateTime.now());
        boolean ended = now == null && event.getOldTimeOutEnd() != null;
        if (!started && !ended) return;
        record(event.getGuild(), event.getUser(), started ? Kind.TIMEOUT : Kind.TIMEOUT_END, started ? now.toInstant() : null);
    }

    private void record(Guild guild, User target, Kind kind, Instant until) {
        try {
            ModLogConfig config = repository.get(guild.getIdLong());
            if (!config.enabled() || config.channelId() == null) return;
            boolean isBan = kind == Kind.BAN || kind == Kind.UNBAN;
            if (isBan ? !config.logBans() : !config.logTimeouts()) return;
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, config.channelId());
            if (channel == null || !channel.canTalk()) return;

            ActionType action = isBan ? (kind == Kind.BAN ? ActionType.BAN : ActionType.UNBAN) : ActionType.MEMBER_UPDATE;
            if (!guild.getSelfMember().hasPermission(net.dv8tion.jda.api.Permission.VIEW_AUDIT_LOGS)) {
                post(channel, kind, target, null, null, until);
                return;
            }
            guild.retrieveAuditLogs().type(action).limit(10).queue(entries -> {
                Long moderator = null;
                String reason = null;
                for (var entry : entries) {
                    if (entry.getTargetIdLong() != target.getIdLong()) continue;
                    if (entry.getTimeCreated().toInstant().isBefore(Instant.now().minus(RECENT))) continue;
                    moderator = entry.getUserIdLong();
                    reason = entry.getReason();
                    break;
                }
                post(channel, kind, target, moderator, reason, until);
            }, error -> post(channel, kind, target, null, null, until));
        } catch (Exception e) {
            log.error("Unhandled exception writing the moderation log for guild {}", guild.getId(), e);
        }
    }

    private static void post(GuildMessageChannel channel, Kind kind, User target, Long moderator, String reason, Instant until) {
        channel.sendMessageEmbeds(ModLogMessage.embed(kind, target.getIdLong(), target.getName(), moderator, reason, until, Instant.now()))
                .queue(ok -> {}, error -> log.warn("Couldn't write to the moderation log in {}: {}", channel.getId(), error.getMessage()));
    }
}
