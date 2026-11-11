package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.runescape.ClanPointsRepository;
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

    /** How long the job waits for Discord to answer about linked members it doesn't have in its member cache. */
    static final java.time.Duration MEMBER_WAIT = java.time.Duration.ofSeconds(10);
    /** The most ids Discord takes in one member request. */
    static final int MEMBER_BATCH = 100;

    /**
     * A hub entry's payload ({@code template} and {@code promoted}): the message with the linked members' server names filled in, from the
     * bot's links in this server; else the payload's {@code text}. Names are matched the way the hub folds them (case, spaces, underscores:
     * {@link ClanPointsRepository#key}), so a link saved as "Foo_Bar" names the hub's "foo bar" (B-37). Members the bot hasn't cached are
     * asked from Discord once, with a time limit; one that still can't be found is listed by RuneScape name only.
     */
    String textFor(Guild guild, DataObject payload) {
        String fallback = payload.getString("text", "");
        if (!payload.hasKey("promoted") || payload.isNull("promoted")) return fallback;
        List<RankAdvisorRules.Promoted> promoted;
        Map<String, String> names = new HashMap<>();
        try {
            promoted = promotedOf(payload);
            if (promoted.isEmpty()) return fallback;
            Map<String, Long> linked = linkedIds(cockpit.links(guild.getIdLong()), promoted);
            Map<Long, String> byId = memberNames(guild, new java.util.HashSet<>(linked.values()));
            linked.forEach((key, id) -> {
                String name = byId.get(id);
                if (name != null) names.put(key, name);
            });
        } catch (RuntimeException e) {
            return fallback; // an unreadable list, or no links: the hub's text says the same with RuneScape names
        }
        String text = RankAdvisorRules.weeklyText(payload.getString("template", ""), RankAdvisorRules.withNames(promoted, names));
        return text.isBlank() ? fallback : text;
    }

    /** The Discord id linked to each promoted member, by the member's own {@code rsnKey}, with both sides folded as the hub folds names. */
    static Map<String, Long> linkedIds(Map<String, Long> links, List<RankAdvisorRules.Promoted> promoted) {
        Map<String, Long> folded = new HashMap<>();
        links.forEach((name, id) -> folded.putIfAbsent(ClanPointsRepository.key(name), id));
        Map<String, Long> out = new HashMap<>();
        for (RankAdvisorRules.Promoted p : promoted) {
            Long id = folded.get(ClanPointsRepository.key(p.rsnKey()));
            if (id == null) id = folded.get(ClanPointsRepository.key(p.rsn()));
            if (id != null) out.put(p.rsnKey(), id);
        }
        return out;
    }

    /** Server names of these members: cached ones as they are, the rest asked from Discord in batches of {@link #MEMBER_BATCH}, each within {@link #MEMBER_WAIT} (a batch that fails names none). Runs on the outbox lane, never a JDA callback thread, where the blocking wait is refused. */
    static Map<Long, String> memberNames(Guild guild, java.util.Set<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            net.dv8tion.jda.api.entities.Member m = guild.getMemberById(id);
            if (m != null) out.put(id, m.getEffectiveName());
            else missing.add(id);
        }
        if (missing.isEmpty()) return out;
        for (int from = 0; from < missing.size(); from += MEMBER_BATCH) {
            long[] batch = missing.subList(from, Math.min(missing.size(), from + MEMBER_BATCH)).stream().mapToLong(Long::longValue).toArray();
            try {
                for (net.dv8tion.jda.api.entities.Member m : guild.retrieveMembersByIds(false, batch).setTimeout(MEMBER_WAIT).get()) {
                    out.put(m.getIdLong(), m.getEffectiveName());
                }
            } catch (RuntimeException e) {
                // Discord didn't answer in time, or the bot may not ask: those members are listed by RuneScape name
            }
        }
        return out;
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
            String key = r.getString("rsnKey", ClanPointsRepository.key(rsn));
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
