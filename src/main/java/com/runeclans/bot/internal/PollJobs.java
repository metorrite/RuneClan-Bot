package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The Discord side of a poll made on the dashboard ({@link CommunityAdminApi#createPoll}), run by the outbox so the page never waits on
 * Discord and can follow the result. {@code poll.create} stores the poll and posts its message; when Discord refuses the post, the poll is
 * closed again ({@link PollService#createPollPosted}), so no open poll is ever left without a message. Not idempotent: a second run would
 * make a second poll.
 */
@BService
public class PollJobs implements DiscordJobHandler {
    static final String CREATE = "poll.create";

    private final PollService polls;
    private final CommunitySettings community;
    private final SiteCache cache;

    public PollJobs(PollService polls, CommunitySettings community, SiteCache cache) {
        this.polls = polls;
        this.community = community;
        this.cache = cache;
    }

    @Override
    public String kind() {
        return CREATE;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    /** Everything a poll needs, checked by the API first. {@code closesAt} is fixed when the poll is asked for, not when the bot gets to it. */
    static DataObject createPayload(long channelId, String title, boolean anonymous, boolean multiple, List<String> options, Instant closesAt) {
        DataArray labels = DataArray.empty();
        options.forEach(labels::add);
        return DataObject.empty().put("channelId", Long.toString(channelId)).put("title", title).put("anonymous", anonymous).put("multiple", multiple)
                .put("options", labels).put("closesAt", closesAt == null ? null : closesAt.toString());
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        DataObject p = job.payload();
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, p.getLong("channelId"));
        if (channel == null) throw new DiscordJobs.Refused("That channel no longer exists.");
        if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + " any more.");
        List<String> options = new ArrayList<>();
        DataArray labels = p.getArray("options");
        for (int i = 0; i < labels.length(); i++) options.add(labels.getString(i));
        Instant closesAt = p.isNull("closesAt") ? null : Instant.parse(p.getString("closesAt"));

        Message[] sent = new Message[1];
        long pollId = polls.createPollPosted(guild, channel, p.getString("title"), p.getBoolean("anonymous", false), p.getBoolean("multiple", false),
                options, job.actorId(), closesAt, action -> sent[0] = DiscordJobs.send(action));
        // on the hub the poll carries its end time and the hub closes it; on the bot's own tables the poll closer does
        if (closesAt != null && !polls.onHub()) community.scheduleClose(pollId, closesAt.atOffset(ZoneOffset.UTC));
        cache.invalidate("polls");
        return DataObject.empty().put("pollId", Long.toString(pollId)).put("messageId", sent[0].getId()).put("channelId", channel.getId())
                .put("jumpUrl", sent[0].getJumpUrl());
    }
}
