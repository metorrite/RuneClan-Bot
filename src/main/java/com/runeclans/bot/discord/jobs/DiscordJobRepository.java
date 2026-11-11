package com.runeclans.bot.discord.jobs;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.discord.jobs.DiscordJob.State;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The Discord job outbox's table (T-456). Queueing is one insert, so a website request that queues a job answers at once. Taking jobs to run
 * uses {@code FOR UPDATE SKIP LOCKED}, so two bot processes (a deploy overlapping the old one) never run the same job twice.
 *
 * <p>Claims are per process (T-696, docs/SCALING-STATE.md M3, M4): a running job carries {@code claimed_by} (the {@link
 * com.runeclans.bot.util.ProcessId} that took it) and {@code claimed_at}, which that process refreshes while it lives. Finishing a job only
 * counts for the process that holds its claim, and recovery only touches jobs whose claim has expired, so a starting process never ends the
 * jobs a live one is running.
 */
@BService
public class DiscordJobRepository {
    private static final Logger log = LoggerFactory.getLogger(DiscordJobRepository.class);
    private static final String COLUMNS = "id, kind, guild_id, actor_id, dedupe_key, payload, state, attempts, waits, last_error, result, next_attempt_at, created_at, updated_at";

    private final ConnectionSupplier connectionSupplier;

    public DiscordJobRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "discord jobs", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // One row per change the website asked of Discord. payload holds what the job needs; result what it answered. Finished rows are
                // kept a few days so the website can still read how a change ended, then swept.
                """
                CREATE TABLE IF NOT EXISTS younglings.discord_job (
                    id BIGSERIAL PRIMARY KEY,
                    kind TEXT NOT NULL,
                    guild_id BIGINT NOT NULL,
                    actor_id BIGINT NOT NULL,
                    dedupe_key TEXT NULL,
                    payload TEXT NOT NULL DEFAULT '{}',
                    state TEXT NOT NULL DEFAULT 'queued',
                    attempts INT NOT NULL DEFAULT 0,
                    waits INT NOT NULL DEFAULT 0,
                    last_error TEXT NULL,
                    result TEXT NULL,
                    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS discord_job_due ON younglings.discord_job (next_attempt_at) WHERE state IN ('queued', 'retrying');
                """,

                // at most one waiting job per key: a newer change of the same thing replaces the one not yet started
                """
                CREATE UNIQUE INDEX IF NOT EXISTS discord_job_waiting_key ON younglings.discord_job (dedupe_key)
                    WHERE dedupe_key IS NOT NULL AND state IN ('queued', 'retrying');
                """,

                // T-696: which process runs a job, and when it last said it still does (null on rows from before claims)
                """
                ALTER TABLE younglings.discord_job ADD COLUMN IF NOT EXISTS claimed_by TEXT NULL;
                """,

                """
                ALTER TABLE younglings.discord_job ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ NULL;
                """,

                """
                CREATE INDEX IF NOT EXISTS discord_job_running ON younglings.discord_job (claimed_by) WHERE state = 'running';
                """
        ));
    }

    /**
     * Queues a job, or, when a job with the same key is still waiting, gives it this payload instead (and starts it afresh). One statement,
     * so two requests at once can't queue the same thing twice.
     */
    public DiscordJob enqueue(String kind, long guildId, long actorId, String dedupe, DataObject payload) {
        String sql = "INSERT INTO younglings.discord_job (kind, guild_id, actor_id, dedupe_key, payload) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL AND state IN ('queued', 'retrying') "
                + "DO UPDATE SET payload = EXCLUDED.payload, actor_id = EXCLUDED.actor_id, state = 'queued', attempts = 0, waits = 0, last_error = NULL, "
                + "next_attempt_at = now(), updated_at = now() RETURNING " + COLUMNS;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, kind);
            s.setLong(2, guildId);
            s.setLong(3, actorId);
            s.setString(4, dedupe);
            s.setString(5, payload.toString());
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return read(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not queue a " + kind + " job", e);
        }
    }

    /** Whether any job (in any state, until the sweep drops finished ones) has this dedupe key: for posts that must never go out twice. */
    public boolean known(String dedupe) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM younglings.discord_job WHERE dedupe_key = ? LIMIT 1")) {
            s.setString(1, dedupe);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not look up a job key", e);
        }
    }

    public Optional<DiscordJob> get(long id) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM younglings.discord_job WHERE id = ?")) {
            s.setLong(1, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read job " + id, e);
        }
    }

    /**
     * The jobs with these ids that this person asked for (others are left out, as if they didn't exist); with a {@code guildId}, only those
     * about that server.
     */
    public List<DiscordJob> find(Collection<Long> ids, Long guildId, long actorId) {
        if (ids.isEmpty()) return List.of();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM younglings.discord_job WHERE id = ANY (?) AND (CAST(? AS BIGINT) IS NULL OR guild_id = ?) AND actor_id = ? ORDER BY id")) {
            s.setArray(1, c.createArrayOf("bigint", ids.toArray()));
            setGuild(s, 2, guildId);
            s.setLong(4, actorId);
            List<DiscordJob> jobs = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) jobs.add(read(rs));
            }
            return jobs;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read jobs", e);
        }
    }

    /**
     * The shards a process runs, for claiming only the jobs of servers it can reach ({@code (guild_id >> 22) % total}, Discord's formula).
     * {@code null} where a filter is taken means every server (one process with every shard, or no shard manager).
     */
    public record ShardSet(int total, List<Integer> owned) {
        public ShardSet {
            owned = List.copyOf(owned);
        }
    }

    /**
     * Takes up to {@code limit} jobs that are due, oldest first, marks them running and claims them for {@code claimant}. With {@code shards},
     * only jobs for servers on those shards.
     */
    public List<DiscordJob> claimDue(String claimant, int limit, ShardSet shards) {
        String shardFilter = shards == null ? "" : "AND ((guild_id >> 22) % ?) = ANY (?) ";
        String sql = "UPDATE younglings.discord_job SET state = 'running', attempts = attempts + 1, claimed_by = ?, claimed_at = now(), updated_at = now() "
                + "WHERE id IN (SELECT id FROM younglings.discord_job WHERE state IN ('queued', 'retrying') AND next_attempt_at <= now() " + shardFilter
                + "ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            s.setString(i++, claimant);
            if (shards != null) {
                s.setLong(i++, shards.total());
                s.setArray(i++, c.createArrayOf("bigint", shards.owned().stream().map(Integer::longValue).toArray()));
            }
            s.setInt(i, limit);
            List<DiscordJob> jobs = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) jobs.add(read(rs));
            }
            jobs.sort((a, b) -> Long.compare(a.id(), b.id()));
            return jobs;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not take due jobs", e);
        }
    }

    /** Says this process still runs the jobs it claimed: refreshes their {@code claimed_at}. How many it holds. */
    public int heartbeat(String claimant) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET claimed_at = now() WHERE state = 'running' AND claimed_by = ?")) {
            s.setString(1, claimant);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not refresh job claims", e);
        }
    }

    /** Ends a job this process claimed as done. Does nothing when the claim is no longer this process's (it expired and was recovered). */
    public void done(String claimant, long id, DataObject result) {
        finish(claimant, id, "done", null, result);
    }

    /** Ends a job this process claimed as failed (same rule as {@link #done}). */
    public void failed(String claimant, long id, String error) {
        finish(claimant, id, "failed", error, null);
    }

    private void finish(String claimant, long id, String state, String error, DataObject result) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = ?, last_error = ?, result = ?, updated_at = now() "
                     + "WHERE id = ? AND state = 'running' AND claimed_by = ?")) {
            s.setString(1, state);
            s.setString(2, error);
            s.setString(3, result == null ? null : result.toString());
            s.setLong(4, id);
            s.setString(5, claimant);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not finish job " + id, e);
        }
    }

    /**
     * Replaces a running job's payload, so a later try picks up where this one got to (a poll job keeps the poll it stored, T-1031). False
     * when the job isn't running any more. Never changes a job that is waiting or finished.
     */
    public boolean savePayload(long id, DataObject payload) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET payload = ?, updated_at = now() WHERE id = ? AND state = 'running'")) {
            s.setString(1, payload.toString());
            s.setLong(2, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not save job " + id, e);
        }
    }

    /**
     * Puts a running job back to wait until {@code at}. If a newer change with the same key was queued meanwhile, that one wins and this one
     * ends as done: the newer change already says what the end state should be. Only for the process holding the job's claim.
     */
    public void retryAt(String claimant, long id, Instant at, String error, boolean rateLimit) {
        try (Connection c = connectionSupplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = 'retrying', last_error = ?, next_attempt_at = ?, waits = waits + ?, updated_at = now() "
                    + "WHERE id = ? AND state = 'running' AND claimed_by = ? AND NOT EXISTS (SELECT 1 FROM younglings.discord_job o WHERE o.dedupe_key = discord_job.dedupe_key "
                    + "AND o.id <> discord_job.id AND o.state IN ('queued', 'retrying'))")) {
                s.setString(1, error);
                s.setTimestamp(2, Timestamp.from(at));
                s.setInt(3, rateLimit ? 1 : 0);
                s.setLong(4, id);
                s.setString(5, claimant);
                if (s.executeUpdate() == 1) return;
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = 'done', last_error = 'Replaced by a newer change.', updated_at = now() "
                    + "WHERE id = ? AND state = 'running' AND claimed_by = ?")) {
                s.setLong(1, id);
                s.setString(2, claimant);
                s.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not put job " + id + " back", e);
        }
    }

    /** Sends a failed job again from the start (the website's Retry). False when it isn't this person's failed job (in this server, when given). */
    public boolean requeue(long id, Long guildId, long actorId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = 'queued', attempts = 0, waits = 0, last_error = NULL, next_attempt_at = now(), updated_at = now() "
                     + "WHERE id = ? AND (CAST(? AS BIGINT) IS NULL OR guild_id = ?) AND actor_id = ? AND state = 'failed' AND (dedupe_key IS NULL OR NOT EXISTS (SELECT 1 FROM younglings.discord_job o "
                     + "WHERE o.dedupe_key = discord_job.dedupe_key AND o.state IN ('queued', 'retrying')))")) {
            s.setLong(1, id);
            setGuild(s, 2, guildId);
            s.setLong(4, actorId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not requeue job " + id, e);
        }
    }

    /**
     * Jobs left running by a process that is gone go back to waiting (for an idempotent kind) or end as failed with a plain reason (a post
     * might already be in the channel, so it is never sent twice by itself). Gone means the claim was not refreshed for {@code expiry} (a row
     * claimed before claims existed counts from when it was taken). A job another live process is running keeps a fresh claim and is left
     * alone, and {@code claimant}'s own jobs never are touched: it is still running them.
     */
    public int recover(String claimant, Collection<String> idempotentKinds, Duration expiry) {
        double seconds = expiry.toMillis() / 1000.0;
        return endRunning("claimed_by IS DISTINCT FROM ? AND COALESCE(claimed_at, updated_at) < now() - make_interval(secs => ?)", idempotentKinds, (s, i) -> {
            s.setString(i, claimant);
            s.setDouble(i + 1, seconds);
            return i + 2;
        });
    }

    /**
     * On a graceful shutdown: this process's own running jobs end the same way as {@link #recover} would end them later (put back or failed),
     * so the website doesn't show them running until the claim expires. A job that finishes after this changes nothing (it is no longer
     * running).
     */
    public int release(String claimant, Collection<String> idempotentKinds) {
        return endRunning("claimed_by = ?", idempotentKinds, (s, i) -> {
            s.setString(i, claimant);
            return i + 1;
        });
    }

    /** Fills the placeholders of a {@code where} clause starting at an index, and answers the next free index. */
    private interface Binder {
        int bind(PreparedStatement s, int index) throws SQLException;
    }

    private int endRunning(String where, Collection<String> idempotentKinds, Binder binder) {
        String running = "state = 'running' AND " + where;
        try (Connection c = connectionSupplier.getConnection()) {
            int n;
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = 'retrying', next_attempt_at = now(), updated_at = now(), "
                    + "last_error = 'The bot restarted while doing this; trying again.' WHERE " + running + " AND kind = ANY (?)")) {
                int next = binder.bind(s, 1);
                s.setArray(next, c.createArrayOf("text", idempotentKinds.toArray()));
                n = s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = 'failed', updated_at = now(), "
                    + "last_error = 'The bot restarted while doing this, so it may or may not have happened. Check Discord before trying again.' WHERE " + running)) {
                binder.bind(s, 1);
                n += s.executeUpdate();
            }
            return n;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not recover jobs", e);
        }
    }

    /** Forgets finished jobs older than {@code before}. */
    public int sweep(Instant before) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.discord_job WHERE state IN ('done', 'failed') AND updated_at < ?")) {
            s.setTimestamp(1, Timestamp.from(before));
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not sweep jobs", e);
        }
    }

    /** Fills two placeholders, {@code (? IS NULL OR guild_id = ?)}, with an optional server. */
    private static void setGuild(PreparedStatement s, int index, Long guildId) throws SQLException {
        if (guildId == null) {
            s.setNull(index, java.sql.Types.BIGINT);
            s.setNull(index + 1, java.sql.Types.BIGINT);
        } else {
            s.setLong(index, guildId);
            s.setLong(index + 1, guildId);
        }
    }

    private static DiscordJob read(ResultSet rs) throws SQLException {
        String result = rs.getString("result");
        return new DiscordJob(rs.getLong("id"), rs.getString("kind"), rs.getLong("guild_id"), rs.getLong("actor_id"), rs.getString("dedupe_key"),
                DataObject.fromJson(rs.getString("payload")), State.ofKey(rs.getString("state")).orElse(State.FAILED), rs.getInt("attempts"), rs.getInt("waits"),
                rs.getString("last_error"), result == null ? null : DataObject.fromJson(result), instant(rs, "next_attempt_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
