package com.runeclans.bot.ops;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.database.DatabaseSource;
import com.runeclans.bot.discord.Shards;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The bot's numbers for {@code /metrics}, the ops alerts and the weekly post (T-674): the Discord job outbox, the RuneMetrics poll budget,
 * Discord 429s, the database pool, the gateway and the JVM heap. Reads them when asked; keeps nothing but the JDA it is given.
 */
@BService
public class OpsMetrics {
    private static final Logger log = LoggerFactory.getLogger(OpsMetrics.class);

    /** What the ops alert rules look at, read once a minute. A value that couldn't be read is -1. */
    public record Snapshot(long outboxDue, double outboxOldestSeconds, long discord429Total, double heapUsedRatio) {}

    private final ConnectionSupplier connections;
    private final BotConfig config;
    private volatile JDA jda;

    public OpsMetrics(ConnectionSupplier connections, BotConfig config) {
        this.connections = connections;
        this.config = config;
        OpsTelemetry.configure(config.isMetricsEnabled() && config.getMetricsToken() != null, config.isRequestLogEnabled());
        if (config.isMetricsEnabled() && config.getMetricsToken() == null) {
            log.warn("METRICS_ENABLED is on but METRICS_TOKEN is {}: /metrics stays off.",
                    config.hasMetricsTokenSetting() ? "shorter than " + MetricsAccess.MIN_TOKEN_LENGTH + " characters" : "not set");
        }
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    /** The Prometheus text for {@code /metrics}. */
    public String render() {
        return OpsTelemetry.registry().render(gauges());
    }

    /** The outbox's due jobs and how long the oldest has waited past its time, or {@code {-1, -1}} when the database can't say. */
    double[] outbox() {
        String sql = "SELECT count(*), COALESCE(EXTRACT(EPOCH FROM (now() - min(next_attempt_at))), 0) FROM younglings.discord_job "
                + "WHERE state IN ('queued', 'retrying') AND next_attempt_at <= now()";
        try (Connection c = connections.getConnection(); PreparedStatement s = c.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
            rs.next();
            return new double[]{rs.getLong(1), rs.getDouble(2)};
        } catch (SQLException e) {
            log.warn("Reading the Discord job outbox for the ops numbers failed: {}", e.getSQLState());
            return new double[]{-1, -1};
        }
    }

    public Snapshot snapshot() {
        double[] outbox = outbox();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        double ratio = heap.getMax() > 0 ? heap.getUsed() / (double) heap.getMax() : -1;
        return new Snapshot((long) outbox[0], outbox[1], OpsTelemetry.registry().counterTotal(OpsTelemetry.DISCORD_429), ratio);
    }

    List<MetricRegistry.Gauge> gauges() {
        List<MetricRegistry.Gauge> g = new ArrayList<>();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        g.add(MetricRegistry.Gauge.of("rc_jvm_heap_used_bytes", "JVM heap in use.", heap.getUsed()));
        g.add(MetricRegistry.Gauge.of("rc_jvm_heap_committed_bytes", "JVM heap reserved from the system.", heap.getCommitted()));
        g.add(MetricRegistry.Gauge.of("rc_jvm_heap_max_bytes", "JVM heap limit (-1 when unbounded).", heap.getMax()));
        g.add(MetricRegistry.Gauge.of("rc_jvm_threads", "Live JVM threads.", ManagementFactory.getThreadMXBean().getThreadCount()));
        g.add(MetricRegistry.Gauge.of("rc_process_uptime_seconds", "Seconds since the process started.", ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0));

        if (connections instanceof DatabaseSource source) {
            DatabaseSource.PoolStats pool = source.poolStats();
            g.add(MetricRegistry.Gauge.of("rc_db_pool_active", "Database connections in use.", pool.active()));
            g.add(MetricRegistry.Gauge.of("rc_db_pool_idle", "Database connections idle.", pool.idle()));
            g.add(MetricRegistry.Gauge.of("rc_db_pool_waiting", "Threads waiting for a database connection right now.", pool.waiting()));
            g.add(MetricRegistry.Gauge.of("rc_db_pool_max", "The pool's connection limit.", pool.max()));
            g.add(MetricRegistry.Gauge.of("rc_db_pool_borrows", "Connections handed out since start.", pool.borrows()));
            g.add(MetricRegistry.Gauge.of("rc_db_pool_wait_seconds", "Total time spent waiting for a connection since start.", pool.waitSeconds()));
        }

        double[] outbox = outbox();
        Map<String, String> outboxLabel = Map.of("queue", "discord_job");
        g.add(new MetricRegistry.Gauge("rc_queue_depth", "Jobs due and not yet done, per queue.", outboxLabel, outbox[0]));
        g.add(new MetricRegistry.Gauge("rc_queue_oldest_age_seconds", "How long the oldest due job has waited, per queue.", outboxLabel, outbox[1]));


        JDA current = jda;
        if (current != null) {
            g.add(MetricRegistry.Gauge.of("rc_discord_gateway_events", "Gateway events received since the connection started.", current.getResponseTotal()));
            g.add(MetricRegistry.Gauge.of("rc_discord_gateway_ping_seconds", "The last gateway heartbeat round trip.", current.getGatewayPing() / 1000.0));
            g.add(MetricRegistry.Gauge.of("rc_discord_guilds", "Servers the bot is in.", Shards.guilds(current).size()));
        }
        return g;
    }
}
