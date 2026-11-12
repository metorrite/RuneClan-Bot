package com.runeclans.bot.accountgate;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.accountgate.AccountGateConfig.Action;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Instant;
import java.util.List;

/**
 * Checks the age of each account that joins. A too-new account is reported to the alert channel and, if the server chose kick, removed
 * after a DM saying when it may come back. Bots are never touched. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class AccountGateListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(AccountGateListener.class);

    private final AccountGateRepository repository;

    public AccountGateListener(AccountGateRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        try {
            User user = event.getUser();
            if (user.isBot()) return;
            if (AutoPostRouter.skipSweep("account gate")) return; // kicks, bans and alerts in the real server: only the live bot does that (T-187, B-82)
            Guild guild = event.getGuild();
            AccountGateConfig config = repository.get(guild.getIdLong());
            Instant created = user.getTimeCreated().toInstant();
            Instant now = Instant.now();
            if (config.exempt(user.getIdLong())) return;

            // a name rule is checked first: it can be stronger than the age check, and a person already removed needs no second action
            String rule = config.nameRulesEnabled() ? NameRules.firstMatch(config.nameRules(), List.of(user.getName(), user.getEffectiveName(), event.getMember().getEffectiveName())) : null;
            if (rule != null) {
                String taken = removeForName(guild, event.getMember(), config, rule, created);
                alertName(guild, config, user, rule, taken);
                return;
            }

            if (!config.enabled() || !config.tooNew(created, now)) return;

            boolean kicked = false;
            if (config.action() == Action.KICK) {
                kicked = kick(guild, event.getMember(), config, created);
            }
            alert(guild, config, user, created, now, kicked);
        } catch (Exception e) {
            log.error("Unhandled exception checking a new account in guild {}", event.getGuild().getId(), e);
        }
    }

    /** Takes the name rule's action; returns what was done ("kicked", "banned") or null when it was only reported (or the bot may not). */
    private String removeForName(Guild guild, Member member, AccountGateConfig config, String rule, Instant created) {
        Action action = config.nameAction();
        if (action == Action.ALERT) return null;
        if (!guild.getSelfMember().canInteract(member)) return null;
        String reason = "Name matched the rule \"" + rule + "\"";
        if (action == Action.KICK) {
            if (!guild.getSelfMember().hasPermission(Permission.KICK_MEMBERS)) return null;
            member.getUser().openPrivateChannel()
                    .flatMap(channel -> channel.sendMessage("You were removed from **" + guild.getName() + "** because your name isn't allowed there."))
                    .queue(ok -> kickNow(guild, member, reason), error -> kickNow(guild, member, reason));
            return "kicked";
        }
        if (!guild.getSelfMember().hasPermission(Permission.BAN_MEMBERS)) return null;
        member.getUser().openPrivateChannel()
                .flatMap(channel -> channel.sendMessage("You were banned from **" + guild.getName() + "** because your name isn't allowed there."))
                .queue(ok -> banNow(guild, member, reason), error -> banNow(guild, member, reason));
        return "banned";
    }

    private static void banNow(Guild guild, Member member, String reason) {
        guild.ban(member, 0, java.util.concurrent.TimeUnit.SECONDS).reason(reason)
                .queue(ok -> {}, error -> log.warn("Couldn't ban {} from {}: {}", member.getId(), guild.getId(), error.getMessage()));
    }

    private static void alertName(Guild guild, AccountGateConfig config, User user, String rule, String taken) {
        if (config.alertChannelId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, config.alertChannelId());
        if (channel == null || !channel.canTalk()) return;
        EmbedBuilder embed = new EmbedBuilder().setTitle(taken == null ? "Name rule matched" : "Name rule matched: member " + taken)
                .setColor(taken == null ? new Color(0xFEE75C) : new Color(0xED4245))
                .addField("Member", user.getAsMention() + " (" + user.getName() + ")", true)
                .addField("Rule", "`" + rule + "`", true)
                .setFooter("ID " + user.getId()).setTimestamp(Instant.now());
        channel.sendMessageEmbeds(embed.build()).queue(ok -> {}, error -> log.warn("Couldn't post the name-rule alert in {}: {}", channel.getId(), error.getMessage()));
    }

    private boolean kick(Guild guild, Member member, AccountGateConfig config, Instant created) {
        if (!guild.getSelfMember().hasPermission(Permission.KICK_MEMBERS) || !guild.getSelfMember().canInteract(member)) return false;
        String reason = "Account is younger than " + config.minAgeDays() + " days";
        member.getUser().openPrivateChannel()
                .flatMap(channel -> channel.sendMessage("You were removed from **" + guild.getName() + "** because your Discord account is newer than "
                        + config.minAgeDays() + " days. You are welcome to join again from <t:" + config.eligibleAt(created).getEpochSecond() + ":f>."))
                .queue(ok -> kickNow(guild, member, reason), error -> kickNow(guild, member, reason));
        return true;
    }

    private static void kickNow(Guild guild, Member member, String reason) {
        guild.kick(member).reason(reason).queue(ok -> {}, error -> log.warn("Couldn't kick new account {} from {}: {}", member.getId(), guild.getId(), error.getMessage()));
    }

    private static void alert(Guild guild, AccountGateConfig config, User user, Instant created, Instant now, boolean kicked) {
        if (config.alertChannelId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, config.alertChannelId());
        if (channel == null || !channel.canTalk()) return;
        EmbedBuilder embed = new EmbedBuilder().setTitle(kicked ? "New account removed" : "New account joined")
                .setColor(kicked ? new Color(0xED4245) : new Color(0xFEE75C))
                .addField("Member", user.getAsMention() + " (" + user.getName() + ")", true)
                .addField("Account age", AccountGateConfig.age(created, now), true)
                .addField("Created", "<t:" + created.getEpochSecond() + ":f>", true)
                .setFooter("ID " + user.getId()).setTimestamp(now);
        channel.sendMessageEmbeds(embed.build()).queue(ok -> {}, error -> log.warn("Couldn't post the new-account alert in {}: {}", channel.getId(), error.getMessage()));
    }
}
