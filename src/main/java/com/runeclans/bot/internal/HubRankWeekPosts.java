package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.runeclans.HubFeedListener;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rank-up advisor's weekly congrats post once sites live on the hub (T-976 part 2): the hub turns each site's promotion week over and,
 * with the site owner's auto post on, sends feed entry {@value #KIND} once ({@code {siteId, guildId, channelId, weekStart, template,
 * promoted, text}}). This queues it as a {@link RankCongratsJob} in the Discord job outbox (so it runs in the process holding the server's
 * shard, names the linked members there, and allows no mentions), never while the bot's posting is off. A repeated entry posts once: an
 * entry whose server and week were already queued (any state, {@link DiscordOutbox#known}) is skipped, and so is one that arrives after
 * {@link #TOO_LATE} (B-47: the outbox forgets finished jobs after three days, so a feed replay later than that would post again). A post that can't be queued is logged and lost, not
 * retried: the page shows the message to copy.
 */
@BService
public class HubRankWeekPosts implements HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(HubRankWeekPosts.class);
    static final String KIND = "site.rank_week";
    /**
     * A week's post is queued at most this long after the week's start date. The hub turns the week about 12 hours after the Wednesday reset
     * and posts within two days of that, so a normal post arrives by about 2.5 days. It must not be longer than the outbox keeps a finished
     * job ({@code DiscordOutbox.KEEP_FINISHED}, 3 days): the job is queued no earlier than the week's start, so until this passes a replayed
     * entry still finds it and posts nothing. A week the hub turns late (a roster outage) and posts after this gets no auto post; the
     * Promotions page still shows the message to copy.
     */
    static final java.time.Duration TOO_LATE = java.time.Duration.ofDays(3);

    private final DiscordOutbox outbox;
    private final TrackingPostingToggle posting;
    /** The time the stale check uses; tests fix it. */
    java.time.Clock clock = java.time.Clock.systemUTC();

    public HubRankWeekPosts(DiscordOutbox outbox, TrackingPostingToggle posting) {
        this.outbox = outbox;
        this.posting = posting;
    }

    /** One post to queue: the server, the outbox's dedupe key and the job's payload. */
    record Plan(long guildId, String dedupe, DataObject payload, java.time.LocalDate week) {}

    /** Whether the plan's week is past the time its post may still go out. */
    static boolean stale(Plan plan, java.time.Instant now) {
        return plan.week().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plus(TOO_LATE).isBefore(now);
    }

    @Override
    public void onHubFeed(String kind, DataObject entry) {
        if (!KIND.equals(kind)) return;
        try {
            Plan plan = plan(entry);
            if (plan == null) return;
            if (stale(plan, clock.instant())) {
                log.info("Rank-up advisor: skipped an old weekly congrats entry for server {}", plan.guildId());
                return;
            }
            if (outbox.known(plan.dedupe())) return; // already posted (or queued): a replayed entry
            if (!posting.isEnabled()) {
                log.info("Rank-up advisor: the hub's weekly congrats post for server {} wasn't queued (posting is off)", plan.guildId());
                return;
            }
            outbox.enqueue(RankCongratsJob.KIND, plan.guildId(), 0L, plan.dedupe(), plan.payload());
        } catch (RuntimeException e) {
            log.warn("Rank-up advisor: the hub's weekly congrats post couldn't be queued: {}", e.toString());
        }
    }

    /** The post a {@value #KIND} entry asks for, or null when the entry isn't readable (no server, channel or text). */
    static Plan plan(DataObject entry) {
        DataObject data = entry.optObject("data").orElse(null);
        if (data == null) return null;
        String guild = data.getString("guildId", "");
        String channel = data.getString("channelId", "");
        String week = data.getString("weekStart", "");
        String text = data.getString("text", "");
        if (!guild.matches("\\d{15,20}") || !channel.matches("\\d{15,20}") || !week.matches("\\d{4}-\\d{2}-\\d{2}") || text.isBlank()) return null;
        long guildId = Long.parseLong(guild);
        DataObject payload = RankCongratsJob.payload(Long.parseLong(channel), text).put("template", data.getString("template", ""));
        if (data.hasKey("promoted") && !data.isNull("promoted")) payload.put("promoted", data.getArray("promoted"));
        java.time.LocalDate start;
        try {
            start = java.time.LocalDate.parse(week);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
        return new Plan(guildId, "rankweek:" + guildId + ":" + week, payload, start);
    }
}
