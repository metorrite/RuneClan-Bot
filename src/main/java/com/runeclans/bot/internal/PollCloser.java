package com.runeclans.bot.internal;

import com.runeclans.bot.discord.ShardRouting;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Closes polls that were given an end time. Each minute it asks the database which are due, so a restart never
 * loses a deadline — a poll whose time passed while the bot was down is simply closed on the next tick.
 */
@BService
public class PollCloser {
    private static final Logger log = LoggerFactory.getLogger(PollCloser.class);

    private final CommunitySettings settings;
    private final PollService polls;
    private final SiteCache cache;
    private final ScheduledExecutorService executor = BotScheduler.lane("poll-closer");

    public PollCloser(CommunitySettings settings, PollService polls, SiteCache cache) {
        this.settings = settings;
        this.polls = polls;
        this.cache = cache;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> {
            try {
                closeDue(jda);
            } catch (Exception e) {
                log.error("Closing due polls failed", e);
            }
        }, 45, 60, TimeUnit.SECONDS);
    }

    void closeDue(JDA jda) {
        if (AutoPostRouter.diverting()) return; // closing a poll edits and announces in the real channel
        if (polls.onHub()) return; // hub-community: the hub closes timed polls and the feed shows them ended (HubCommunityFeed)
        for (long pollId : settings.due(OffsetDateTime.now())) {
            var poll = polls.getSessionById(pollId);
            Guild guild = poll == null ? null : Shards.guild(jda, poll.guildId());
            if (guild == null) {
                // the poll is gone, or the bot has left its server: either way there is nothing left to close. A server on another
                // process's shard, or one not loaded yet, keeps its end time for the process that can close it (T-700).
                if (poll == null || ShardRouting.gone(jda, poll.guildId())) settings.clearSchedule(pollId);
                continue;
            }
            polls.closePoll(guild, pollId); // does nothing if it was already ended by hand
            settings.clearSchedule(pollId);
            cache.invalidate("polls");
            log.info("Closed poll {} at its scheduled end", pollId);
        }
    }
}
