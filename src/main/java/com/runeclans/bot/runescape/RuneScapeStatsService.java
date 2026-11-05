package com.runeclans.bot.runescape;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.stats.HubRefresh;
import com.runeclans.bot.runescape.stats.StatsBackends;
import com.runeclans.bot.tracking.ClassifiedEntry;
import com.runeclans.bot.tracking.TrackingEventClassifier;
import com.runeclans.bot.tracking.TrackingEventRouter;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@BService
public class RuneScapeStatsService {
    private final PlayerLinkRepository repository;
    private final TrackingEventClassifier classifier;
    private final TrackingEventRouter router;
    private final ClanMemberRepository clanMemberRepository;
    private final StatsBackends backends;
    private final RuneClansHubClient hub;

    // Set once JDA is ready (see #onJdaReady) — same reasoning/pattern as InternalApiServer's own
    // `jda` field. Only needed to resolve a Guild to post tracking announcements to; every other
    // method here works fine without it, so it's fine to be null briefly right after boot.
    private volatile JDA jda;

    public RuneScapeStatsService(PlayerLinkRepository repository,
                                  TrackingEventClassifier classifier, TrackingEventRouter router,
                                  ClanMemberRepository clanMemberRepository, StatsBackends backends, RuneClansHubClient hub) {
        this.backends = backends;
        this.hub = hub;
        this.repository = repository;
        this.classifier = classifier;
        this.router = router;
        this.clanMemberRepository = clanMemberRepository;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    /**
     * The same announcement for lines that reached the bot another way: the hub's {@code player.activity} feed while the hub polls (T-851,
     * {@code HubActivityPosts}). One call per player per batch, so repeats collapse as they do from one poll.
     */
    public void announce(String rsn, List<PlayerActivity> newActivities) {
        if (newActivities.isEmpty()) return;

        JDA currentJda = jda;
        if (currentJda == null) return;
        List<Guild> guilds = new ArrayList<>();
        for (long guildId : clanMemberRepository.activeGuildIds(rsn)) {
            Guild guild = Shards.guild(currentJda, guildId);
            if (guild != null) guilds.add(guild);
        }
        if (guilds.isEmpty()) return;

        List<ClassifiedEntry> entries = new ArrayList<>();
        for (ActivityRun run : collapseConsecutive(newActivities)) {
            classifier.classify(rsn, run.activity(), run.count()).ifPresent(entries::add);
        }
        for (Guild guild : guilds) router.dispatchAll(guild, entries);
    }

    private record ActivityRun(PlayerActivity activity, int count) {}

    private record TimedActivity(PlayerActivity activity, java.time.LocalDateTime time) {}

    // A farming session's kills (or a grab of the same drop landing more than once) don't actually
    // need to be back-to-back in RuneMetrics' own feed to belong together — a level-up, a different
    // drop, or any other activity logged mid-session shouldn't split "defeated Arch-Glacor x30" into
    // several shorter runs just because it happened to interrupt the raw list. A gap this size or
    // bigger between two same-activity entries is treated as two separate sessions instead (so an
    // actual return trip hours later still reports as its own line, not folded into a stale run).
    private static final Duration SAME_RUN_GAP = Duration.ofHours(1);

    /**
     * RuneMetrics reports every individual repeat of the same activity (a grind session killing the
     * same boss, or landing the same drop twice at once) as its own separate line rather than
     * aggregating them itself — left as-is, this would flood the tracking feed with one identical line
     * per repeat. Groups every activity in the batch by identical ({@link PlayerActivity#text()},
     * {@link PlayerActivity#details()}) — not just adjacent occurrences, so something else logged
     * mid-session doesn't fracture the run — then splits each group back into separate runs wherever
     * two consecutive repeats (by RuneMetrics' own relative timestamp) are more than {@link #SAME_RUN_GAP}
     * apart. {@link TrackingEventClassifier} sees each run's whole count and renders it as one line
     * ("defeated X 10 times") instead of ten; the final list is re-sorted chronologically so dispatch
     * order still reads top-to-bottom like the raw feed did.
     */
    private static List<ActivityRun> collapseConsecutive(List<PlayerActivity> activities) {
        Map<String, List<TimedActivity>> byActivity = new LinkedHashMap<>();
        for (PlayerActivity activity : activities) {
            byActivity.computeIfAbsent(activityKey(activity), k -> new ArrayList<>())
                    .add(new TimedActivity(activity, RuneMetricsDates.parse(activity.date())));
        }

        List<ActivityRun> runs = new ArrayList<>();
        for (List<TimedActivity> group : byActivity.values()) {
            group.sort(Comparator.comparing(TimedActivity::time));

            TimedActivity latestInRun = null;
            int count = 0;
            for (TimedActivity timed : group) {
                if (latestInRun != null && Duration.between(latestInRun.time(), timed.time()).compareTo(SAME_RUN_GAP) <= 0) {
                    count++;
                    latestInRun = timed; // group is sorted ascending, so this is always the latest so far
                } else {
                    if (latestInRun != null) runs.add(new ActivityRun(latestInRun.activity(), count));
                    latestInRun = timed;
                    count = 1;
                }
            }
            if (latestInRun != null) runs.add(new ActivityRun(latestInRun.activity(), count));
        }

        runs.sort(Comparator.comparing(run -> RuneMetricsDates.parse(run.activity().date())));
        return runs;
    }

    private static String activityKey(PlayerActivity activity) {
        return activity.text() + "\u0000" + activity.details();
    }

    // ---------- the hub's polling (T-851) ----------

    /** Asks the hub to poll {@code rsn} now on behalf of Discord user {@code discordId} (the person who pressed): what "Update now" does while the hub polls. */
    public HubRefresh.Result refreshViaHub(String rsn, long discordId) {
        return HubRefresh.ask(hub, rsn, discordId);
    }

    /**
     * The hub's newest snapshot of {@code rsn} as a {@link ProfileResult}, for the cards that render one straight from a poll: the player's skills
     * and newest log lines as the hub holds them, {@link ProfileResult.Unavailable} when the hub has nothing (never polled, hidden, or down).
     * A Refresh just queued shows up on the next read, once the hub's poll lands.
     */
    public ProfileResult latestAsResult(String rsn) {
        PlayerLinkRepository.StatsSnapshotRow latest = repository.getLatestSnapshot(rsn);
        if (latest == null) return new ProfileResult.Unavailable();
        List<SkillValue> skills = repository.getSkillsForSnapshot(latest.snapshotId());
        List<PlayerActivity> activities = repository.getRecentActivities(rsn, 20);
        return new ProfileResult.Found(new RuneScapeProfile(rsn, latest.totalLevel(), latest.totalXp(), latest.combatLevel(), latest.questsComplete(),
                latest.questsStarted(), latest.questsNotStarted(), skills, activities));
    }

    public PlayerLinkRepository.StatsSnapshotRow getLatestSnapshot(String rsn) {
        return repository.getLatestSnapshot(rsn);
    }

    /** The newest snapshot of each name, keyed by the lower-cased name. */
    public java.util.Map<String, PlayerLinkRepository.StatsSnapshotRow> getLatestSnapshots(java.util.Collection<String> rsns) {
        return repository.getLatestSnapshots(rsns);
    }

    /** Snapshot history, most recent first — the data source for a "gains over time" view. */
    public List<PlayerLinkRepository.StatsSnapshotRow> getSnapshotHistory(String rsn, int limit) {
        return repository.getSnapshotHistory(rsn, limit);
    }

    public List<SkillValue> getSkillsForSnapshot(long snapshotId) {
        return repository.getSkillsForSnapshot(snapshotId);
    }

    public List<PlayerActivity> getRecentActivities(String rsn, int limit) {
        return repository.getRecentActivities(rsn, limit);
    }

    /** One skill's XP at each poll over the last {@code days} days, oldest first — the XP chart's data source. */
    public List<SkillXpPoint> getSkillXpHistory(String rsn, int skillId, int days) {
        return repository.getSkillXpHistory(rsn, skillId, java.time.OffsetDateTime.now().minusDays(days));
    }

    /** Every snapshot since {@code since}, oldest first. */
    public List<PlayerLinkRepository.StatsSnapshotRow> getSnapshotsSince(String rsn, java.time.OffsetDateTime since) {
        return repository.getSnapshotsSince(rsn, since);
    }

    /** Activities recorded since {@code since}, oldest first. */
    public List<PlayerActivity> getActivitiesSince(String rsn, java.time.OffsetDateTime since) {
        return repository.getActivitiesSince(rsn, since);
    }

    /** Every skill's XP at every poll since {@code since} in one query — the stacked-bar chart's data source. */
    public List<PlayerLinkRepository.SkillHistoryPoint> getAllSkillsXpHistorySince(String rsn, java.time.OffsetDateTime since) {
        return repository.getAllSkillsXpHistorySince(rsn, since);
    }

    static String serializeSkills(List<SkillValue> skills) {
        DataArray array = DataArray.empty();
        for (SkillValue skill : skills) {
            array.add(DataObject.empty()
                    .put("id", skill.skillId())
                    .put("level", skill.level())
                    .put("xp", skill.xp())
                    .put("rank", skill.rank()));
        }
        return array.toString();
    }
}
