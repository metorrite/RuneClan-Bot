package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobRepository;
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
 * Discord and can follow the result, and of a poll a member starts on the website ({@link MemberApi}). {@code poll.create} stores the poll
 * and posts its message; when Discord refuses the post, the poll is closed again ({@link PollService#postStored}), so no open poll is ever
 * left without a message. When the post has to wait (a rate limit), the stored poll's id goes into the job's payload and the next try
 * posts that same poll, so waiting leaves no closed empty polls behind. Not idempotent: after an unclear failure a second run could post
 * twice, so the outbox never runs it again by itself.
 */
@BService
public class PollJobs implements DiscordJobHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PollJobs.class);
    static final String CREATE = "poll.create";
    /** Payload key: the poll a try already stored, once one has. */
    static final String POLL_ID = "pollId";

    private final PollService polls;
    private final CommunitySettings community;
    private final SiteCache cache;
    private final DiscordJobRepository jobs;

    public PollJobs(PollService polls, CommunitySettings community, SiteCache cache, DiscordJobRepository jobs) {
        this.polls = polls;
        this.jobs = jobs;
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
        DataObject p = job.payload();
        // a try that had to wait (a rate limit) kept the poll it stored, and named it in the payload: this try posts that one
        String kept = p.getString(POLL_ID, null);
        Guild guild;
        GuildMessageChannel channel;
        try {
            guild = DiscordJobs.guild(jda, job);
            channel = guild.getChannelById(GuildMessageChannel.class, p.getLong("channelId"));
            if (channel == null) throw new DiscordJobs.Refused("That channel no longer exists.");
            if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + " any more.");
        } catch (DiscordJobs.Refused | DiscordJobs.RetryLater e) {
            // this job ends here, or waits once more: a poll an earlier try kept is closed once the job can't post it any more
            if (kept != null && (e instanceof DiscordJobs.Refused || DiscordJobs.lastWait(job))) polls.discard(Long.parseLong(kept));
            throw e;
        }
        List<String> options = new ArrayList<>();
        DataArray labels = p.getArray("options");
        for (int i = 0; i < labels.length(); i++) options.add(labels.getString(i));
        Instant closesAt = p.isNull("closesAt") ? null : Instant.parse(p.getString("closesAt"));

        long pollId;
        if (kept != null) {
            pollId = Long.parseLong(kept);
        } else {
            pollId = polls.storePoll(guild, channel, p.getString("title"), p.getBoolean("anonymous", false), p.getBoolean("multiple", false),
                    options, job.actorId(), closesAt);
            try {
                jobs.savePayload(job.id(), DataObject.fromJson(p.toJson()).put(POLL_ID, Long.toString(pollId)));
            } catch (RuntimeException e) {
                polls.discard(pollId); // nothing was posted; a later try would not know this poll
                throw e;
            }
        }
        Message message = polls.postStored(pollId, channel, DiscordJobs::send, !DiscordJobs.lastWait(job));
        // on the hub the poll carries its end time and the hub closes it; on the bot's own tables the poll closer does
        // the post went out: a failure in these follow-up steps is logged, never a failed job (the page would offer to post it again)
        if (closesAt != null && !polls.onHub()) {
            try {
                community.scheduleClose(pollId, closesAt.atOffset(ZoneOffset.UTC));
            } catch (RuntimeException e) {
                log.warn("Poll {} was posted but its close at {} couldn't be scheduled", pollId, closesAt, e);
            }
        }
        cache.invalidate("polls");
        return DataObject.empty().put("pollId", Long.toString(pollId)).put("messageId", message.getId()).put("channelId", channel.getId())
                .put("jumpUrl", message.getJumpUrl());
    }
}
