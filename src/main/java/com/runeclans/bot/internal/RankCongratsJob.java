package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts the rank-up advisor's weekly congrats message (T-594, Drake's answer: the members due last week who got their rank), queued by
 * {@link SiteRankAdvisor#sweep} when the site's owner turned auto post on, or, once sites live on the hub (T-976 part 2), by
 * {@link HubRankWeekPosts} from the hub's {@code site.rank_week} entry. The hub's entry carries the owner's text and the list; the job fills in
 * the linked members' names in this server (the hub never sends Discord accounts) and falls back to the hub's text. A message over Discord's limit goes out as several posts, cut at
 * line ends. The text is the site owner's, so it allows no mentions at all. Not idempotent: a second run would post it twice, so the outbox
 * never repeats it after a fault that leaves it unclear whether Discord took it.
 */
@BService
public class RankCongratsJob implements DiscordJobHandler {
    static final String KIND = "rankup.congrats";

    private final SiteCockpitStore cockpit;

    public RankCongratsJob(SiteCockpitStore cockpit) {
        this.cockpit = cockpit;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    static DataObject payload(long channelId, String text) {
        return DataObject.empty().put("channelId", channelId).put("text", text);
    }

    /**
     * A hub entry's payload ({@code template} and {@code promoted}): the message with the linked members' server names filled in, from the
     * bot's links in this server; else the payload's {@code text}.
     */
    String textFor(Guild guild, DataObject payload) {
        String fallback = payload.getString("text", "");
        if (!payload.hasKey("promoted") || payload.isNull("promoted")) return fallback;
        List<RankAdvisorRules.Promoted> promoted = promotedOf(payload);
        if (promoted.isEmpty()) return fallback;
        Map<String, String> names = new HashMap<>();
        try {
            cockpit.links(guild.getIdLong()).forEach((key, id) -> {
                net.dv8tion.jda.api.entities.Member m = guild.getMemberById(id);
                if (m != null) names.put(key, m.getEffectiveName());
            });
        } catch (RuntimeException e) {
            return fallback; // the names are a nicety: the hub's text says the same with RuneScape names
        }
        String text = RankAdvisorRules.weeklyText(payload.getString("template", ""), RankAdvisorRules.withNames(promoted, names));
        return text.isBlank() ? fallback : text;
    }

    /** The hub's list: {@code [{rsnKey, rsn, from, to}]}, unreadable rows left out. */
    static List<RankAdvisorRules.Promoted> promotedOf(DataObject payload) {
        List<RankAdvisorRules.Promoted> out = new ArrayList<>();
        net.dv8tion.jda.api.utils.data.DataArray rows = payload.getArray("promoted");
        for (int i = 0; i < rows.length(); i++) {
            DataObject r = rows.getObject(i);
            String rsn = r.getString("rsn", "");
            String to = r.getString("to", "");
            if (rsn.isBlank() || to.isBlank()) continue;
            String key = r.getString("rsnKey", CockpitRules.keyOf(rsn));
            out.add(new RankAdvisorRules.Promoted(key, rsn, r.getString("from", ""), to, null));
        }
        return out;
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, job.payload().getLong("channelId"));
        if (channel == null) throw new DiscordJobs.Refused("The congrats channel no longer exists. Pick another on the Clan health page.");
        if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + " any more.");
        String text = textFor(guild, job.payload());
        if (text.isBlank()) throw new DiscordJobs.Refused("The congrats message is empty.");
        Message first = null;
        for (String part : RankAdvisorRules.chunks(text, RankAdvisorRules.DISCORD_MAX)) {
            Message sent = DiscordJobs.send(channel.sendMessage(part).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)));
            if (first == null) first = sent;
        }
        if (first == null) throw new DiscordJobs.Refused("The congrats message is empty.");
        return DataObject.empty().put("messageId", first.getId()).put("channelId", channel.getId()).put("jumpUrl", first.getJumpUrl());
    }
}
