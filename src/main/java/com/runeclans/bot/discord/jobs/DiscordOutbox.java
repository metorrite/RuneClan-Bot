package com.runeclans.bot.discord.jobs;

import com.runeclans.bot.config.BotRole;
import com.runeclans.bot.discord.jobs.DiscordJobs.Fail;
import com.runeclans.bot.discord.jobs.DiscordJobs.Next;
import com.runeclans.bot.discord.jobs.DiscordJobs.Retry;
import com.runeclans.bot.util.BotScheduler;
import com.runeclans.bot.util.ProcessId;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The Discord job outbox (T-456): website changes that end in a Discord request are saved as a {@link DiscordJob} and answered at once; this
 * runs them in the background on its own {@link BotScheduler#lane}, one at a time, oldest first. A rate limit puts the job back as
 * "retrying" until Discord allows it (never an error); a fault that may pass is tried again with growing waits; Discord's plain "no" ends it
 * as failed with the reason in words. The website polls {@code /internal/discord-jobs} for the state.
 *
 * <p>Several bot processes can drain the same table (T-696, docs/SCALING-STATE.md M3, M4): each claims jobs under its own {@link ProcessId},
 * only for servers on the shards it runs, and refreshes its claims every {@link #HEARTBEAT} on a lane of their own (so a slow job never lets
 * them lapse). A job whose claim has not been refreshed for {@link #CLAIM_EXPIRY} belonged to a process that is gone; any process puts it
 * back or ends it, as a restart did before. A graceful stop ends the process's own running jobs at once, so with one process this is the
 * same outbox as before; only a crash (no shutdown hook) leaves its jobs running until their claim expires, about {@link #CLAIM_EXPIRY}.
 */
@BService
public class DiscordOutbox {
    private static final Logger log = LoggerFactory.getLogger(DiscordOutbox.class);
    /** How many due jobs one pass takes. */
    static final int BATCH = 10;
    /** Finished jobs are kept this long, so a page opened later can still say how a change ended. */
    static final Duration KEEP_FINISHED = Duration.ofDays(3);
    /** How often a process says it still runs the jobs it claimed. */
    static final Duration HEARTBEAT = Duration.ofSeconds(20);
    /** A claim not refreshed for this long belongs to a process that is gone. Several heartbeats, so one slow database trip never loses it. */
    static final Duration CLAIM_EXPIRY = Duration.ofMinutes(2);
    /** How often a process looks for jobs a gone process left running. */
    static final Duration RECOVER_EVERY = Duration.ofSeconds(30);

    private final DiscordJobRepository repository;
    private final Map<String, DiscordJobHandler> handlers = new HashMap<>();
    private final ScheduledExecutorService lane = BotScheduler.lane("discord-jobs");
    private final ScheduledExecutorService claims = BotScheduler.lane("discord-job-claims");
    private final String claimant = ProcessId.get();
    private volatile JDA jda;
    /** Set when the process is stopping: it takes no more jobs. */
    private volatile boolean stopping;

    public DiscordOutbox(DiscordJobRepository repository, List<DiscordJobHandler> handlers) {
        this.repository = repository;
        for (DiscordJobHandler handler : handlers) {
            for (String kind : handler.kinds()) this.handlers.put(kind, handler);
        }
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        // T-670: jobs need the gateway's servers, so the api role only queues them (its jda stays null: enqueue doesn't drain here)
        if (!BotRole.current().outbox()) return;
        jda = event.getJda();
        // a lane stops repeating a task that throws, so each repeating task keeps going through a failed database trip: a heartbeat that
        // stopped for good would let this process's claims expire while it still runs them
        claims.scheduleWithFixedDelay(keepGoing("refresh claims", () -> repository.heartbeat(claimant)), HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
        claims.scheduleWithFixedDelay(keepGoing("recover", this::recover), 0, RECOVER_EVERY.toSeconds(), TimeUnit.SECONDS);
        lane.scheduleWithFixedDelay(keepGoing("drain", this::drain), 2, 1, TimeUnit.SECONDS);
        // a graceful stop ends this process's running jobs at once, as a restart always did, instead of after the claim expires
        Runtime.getRuntime().addShutdownHook(new Thread(keepGoing("release claims", () -> {
            stop(); // first, so this process claims nothing more while it stops
            release();
        }), "discord-jobs-release"));
        lane.scheduleWithFixedDelay(keepGoing("sweep", () -> repository.sweep(Instant.now().minus(KEEP_FINISHED))), 10, 60 * 60, TimeUnit.SECONDS);
    }

    /** A repeating task that logs a failure and keeps its schedule, instead of being cancelled by it. */
    static Runnable keepGoing(String what, Runnable task) {
        // the stack trace only for the first failure in a row: a database outage would otherwise log one every second
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        return () -> {
            try {
                task.run();
                if (failures.getAndSet(0) > 0) log.info("Discord jobs: {} works again", what);
            } catch (Throwable t) {
                if (failures.getAndIncrement() == 0) log.warn("Discord jobs: {} failed; trying again on the next turn", what, t);
                else log.debug("Discord jobs: {} failed again: {}", what, t.toString());
            }
        };
    }

    /** Puts back or ends the jobs a gone process left running (never this process's own). */
    int recover() {
        int recovered = repository.recover(claimant, idempotentKinds(), CLAIM_EXPIRY);
        if (recovered > 0) log.info("Discord jobs: {} left running by a bot process that stopped were put back or ended", recovered);
        return recovered;
    }

    /** Stops taking jobs (the shutdown hook; tests). */
    void stop() {
        stopping = true;
    }

    /** Ends this process's own running jobs (graceful shutdown). */
    int release() {
        int released = repository.release(claimant, idempotentKinds());
        if (released > 0) log.info("Discord jobs: {} running at shutdown were put back or ended", released);
        return released;
    }

    /** The name this process claims jobs under. */
    String claimant() {
        return claimant;
    }

    /**
     * The shards whose servers this process may take jobs for: null (every server) when it runs every shard or has no shard manager. A shard
     * still starting is left out until it runs, so its jobs wait instead of failing on a server the process can't see yet.
     */
    static DiscordJobRepository.ShardSet ownShards(JDA jda) {
        ShardManager manager = jda == null ? null : jda.getShardManager();
        if (manager == null) return null;
        int total = manager.getShardsTotal();
        List<Integer> owned = manager.getShards().stream().map(shard -> shard.getShardInfo().getShardId()).distinct().sorted().toList();
        return owned.size() >= total ? null : new DiscordJobRepository.ShardSet(total, owned);
    }

    /** The kinds that are safe to run twice: only these are tried again after a restart mid-way. */
    List<String> idempotentKinds() {
        return handlers.entrySet().stream().filter(e -> e.getValue().idempotent(e.getKey())).map(Map.Entry::getKey).sorted().toList();
    }

    /**
     * Saves a job and starts it soon. Answers straight away: nothing here talks to Discord. A job still waiting with the same {@code dedupe} key
     * is replaced by this one (the newest change of the same thing wins).
     */
    public DiscordJob enqueue(String kind, long guildId, long actorId, String dedupe, DataObject payload) {
        if (!handlers.containsKey(kind)) throw new IllegalArgumentException("No handler for Discord job kind " + kind);
        DiscordJob job = repository.enqueue(kind, guildId, actorId, dedupe, payload);
        if (jda != null) lane.execute(this::drain);
        return job;
    }

    /** Whether a job with this dedupe key was ever queued (any state, until the sweep drops finished ones). */
    public boolean known(String dedupe) {
        return repository.known(dedupe);
    }

    /** The jobs with these ids that this person asked for (about this server, when given). */
    public List<DiscordJob> find(Collection<Long> ids, Long guildId, long actorId) {
        return repository.find(ids, guildId, actorId);
    }

    public Optional<DiscordJob> get(long id) {
        return repository.get(id);
    }

    /** Sends one of this person's failed jobs again (the website's Retry). */
    public boolean retry(long id, Long guildId, long actorId) {
        boolean again = repository.requeue(id, guildId, actorId);
        if (again && jda != null) lane.execute(this::drain);
        return again;
    }

    private void drain() {
        JDA current = jda;
        if (current != null) drain(current);
    }

    /** Runs every job that is due, a batch at a time. On the lane only (or in a test). */
    void drain(JDA jda) {
        DiscordJobRepository.ShardSet shards = ownShards(jda);
        while (!stopping) {
            List<DiscordJob> due = repository.claimDue(claimant, BATCH, shards);
            for (DiscordJob job : due) runOne(jda, job);
            if (due.size() < BATCH) return;
        }
    }

    void runOne(JDA jda, DiscordJob job) {
        DiscordJobHandler handler = handlers.get(job.kind());
        if (handler == null) {
            repository.failed(claimant, job.id(), "The bot no longer knows how to do this.");
            return;
        }
        try {
            DataObject result = handler.run(jda, job);
            repository.done(claimant, job.id(), result);
            log.debug("Discord job {} ({}) done after {} tries", job.id(), job.kind(), job.attempts());
        } catch (Throwable error) {
            int realTries = job.attempts() - job.waits();
            Next next = DiscordJobs.after(error, realTries, job.waits(), handler.idempotent(job.kind()), Instant.now());
            switch (next) {
                case Retry retry -> {
                    if (!retry.rateLimit()) log.info("Discord job {} ({}) will be tried again: {}", job.id(), job.kind(), error.toString());
                    repository.retryAt(claimant, job.id(), retry.at(), retry.reason(), retry.rateLimit());
                    long ms = Math.max(0, Duration.between(Instant.now(), retry.at()).toMillis());
                    // wake up for it on time instead of on the next pass
                    lane.schedule((Runnable) this::drain, ms + 50, TimeUnit.MILLISECONDS);
                }
                case Fail fail -> {
                    log.info("Discord job {} ({}) failed: {} ({})", job.id(), job.kind(), fail.reason(), error.toString());
                    repository.failed(claimant, job.id(), fail.reason());
                }
            }
        }
    }
}
