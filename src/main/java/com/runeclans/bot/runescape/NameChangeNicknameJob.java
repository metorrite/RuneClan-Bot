package com.runeclans.bot.runescape;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.Locale;

/**
 * A linked player's nickname follows the hub's name change (T-1160, {@link HubNameChanges}): where their nickname (or, with none, their
 * Discord name) holds the old RuneScape name, it is swapped for the new one, e.g. "Drake | Old Name" becomes "Drake | New Name". A nickname
 * without the old name is left alone. Running it twice is harmless (the second run finds the new name and does nothing).
 */
@BService
public class NameChangeNicknameJob implements DiscordJobHandler {
    public static final String KIND = "member.nickname-follow";
    static final int MAX_NICKNAME_LENGTH = 32;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean idempotent() {
        return true;
    }

    static DataObject payload(long userId, String from, String to) {
        return DataObject.empty().put("userId", Long.toString(userId)).put("from", from).put("to", to);
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        Member member = DiscordJobs.member(guild, job.payload().getLong("userId"));
        String current = member.getNickname() != null ? member.getNickname() : member.getEffectiveName();
        String next = swapped(current, job.payload().getString("from"), job.payload().getString("to"));
        if (next == null) return DataObject.empty().put("changed", false);
        if (!guild.getSelfMember().canInteract(member)) throw new DiscordJobs.Refused("The bot can't change that member's nickname (their role is above the bot's).");
        DiscordJobs.send(member.modifyNickname(next).reason("RuneScape name change"));
        return DataObject.empty().put("changed", true).put("nickname", next);
    }

    /**
     * {@code current} with {@code from} swapped for {@code to} (any case), or null when nothing should change: it doesn't hold the old name,
     * already holds the new one, or would be too long for Discord.
     */
    static String swapped(String current, String from, String to) {
        if (current == null || from == null || to == null || from.isBlank()) return null;
        String lower = current.toLowerCase(Locale.ROOT);
        if (lower.contains(to.toLowerCase(Locale.ROOT))) return null;
        int at = lower.indexOf(from.toLowerCase(Locale.ROOT));
        if (at < 0) return null;
        String next = current.substring(0, at) + to + current.substring(at + from.length());
        return next.length() > MAX_NICKNAME_LENGTH ? null : next;
    }
}
