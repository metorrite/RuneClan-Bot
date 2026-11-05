package com.runeclans.bot.ops;


import java.util.List;
import java.util.Locale;

/**
 * The weekly numbers post (T-674): plain words for the owner, so a replica or plan change is never a surprise (docs/SCALING.md section 7).
 * Counters live in memory, so "this week" is since the last post when there was one, else since the bot started.
 */
public final class OpsReport {
    private OpsReport() {}

    /** The counters at the last post, to give week-on-week differences. */
    public static final class Previous {
        long requests = -1;
        long errors = -1;
        long discord429 = -1;
    }

    public static String text(String botName, OpsMetrics.Snapshot snap, List<MetricRegistry.Gauge> gauges, MetricRegistry registry,
                              List<String> firing, long guilds, Previous previous) {
        long requests = registry.counterTotal(OpsTelemetry.REQUESTS);
        long errors = registry.counterWhere(OpsTelemetry.REQUESTS, "status", "5xx");
        long discord429 = registry.counterTotal(OpsTelemetry.DISCORD_429);
        boolean sinceLast = previous.requests >= 0;
        String span = sinceLast ? "since last week's post" : "since the bot started";

        StringBuilder b = new StringBuilder();
        b.append("**").append(botName).append(" weekly numbers**\n");
        if (guilds >= 0) b.append("- Servers: ").append(guilds).append('\n');
        b.append("- Internal API (website calls) ").append(span).append(": ")
                .append(requests - Math.max(0, previous.requests)).append(" requests, ")
                .append(errors - Math.max(0, previous.errors)).append(" server errors")
                .append(requests == 0 ? " (counted only with METRICS_ENABLED=on)" : "").append('\n');
        b.append("- Discord job queue: ").append(snap.outboxDue() < 0 ? "unknown" : snap.outboxDue() + " due")
                .append(snap.outboxOldestSeconds() > 0 ? ", oldest waiting " + OpsAlertRules.minutes(snap.outboxOldestSeconds()) : "").append('\n');
        b.append("- Discord 429s ").append(span).append(": ").append(discord429 - Math.max(0, previous.discord429)).append('\n');
        double waiting = gauge(gauges, "rc_db_pool_waiting");
        double waitSeconds = gauge(gauges, "rc_db_pool_wait_seconds");
        double borrows = gauge(gauges, "rc_db_pool_borrows");
        if (borrows > 0) {
            b.append("- Database pool: ").append(fmt(gauge(gauges, "rc_db_pool_active"))).append(" of ").append(fmt(gauge(gauges, "rc_db_pool_max")))
                    .append(" in use now, ").append(fmt(waiting)).append(" waiting, average wait ")
                    .append(String.format(Locale.ROOT, "%.1f ms", 1000 * waitSeconds / borrows)).append('\n');
        }
        double used = gauge(gauges, "rc_jvm_heap_used_bytes");
        double max = gauge(gauges, "rc_jvm_heap_max_bytes");
        b.append("- Memory (heap): ").append(mb(used)).append(max > 0 ? " of " + mb(max) : "").append('\n');
        b.append(firing.isEmpty() ? "- No ops alert is firing." : "- Firing now: " + String.join(", ", firing) + ".").append('\n');
        previous.requests = requests;
        previous.errors = errors;
        previous.discord429 = discord429;
        return b.toString();
    }

    static double gauge(List<MetricRegistry.Gauge> gauges, String name) {
        for (MetricRegistry.Gauge g : gauges) if (g.name().equals(name) && g.labels().isEmpty()) return g.value();
        return -1;
    }

    private static String mb(double bytes) {
        return bytes < 0 ? "unknown" : Math.round(bytes / (1024 * 1024)) + " MB";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
