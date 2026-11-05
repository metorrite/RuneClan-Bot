package com.runeclans.bot.ops;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ops alert rules (T-674, docs/SCALING.md section 7), as plain logic over one sample a minute so they are tested without a clock or
 * Discord. A rule that starts firing gives one alert, a reminder each hour it keeps firing, and one "recovered" line when it stops:
 * <ul>
 *     <li><b>health</b>: an {@code OPS_HEALTH_URLS} address failed {@link #HEALTH_FAILS} checks in a row (a slow deploy is not an alert);</li>
 *     <li><b>queue</b>: the oldest due Discord job has waited more than {@link #QUEUE_AGE_LIMIT};</li>
 *     <li><b>429</b>: Discord answered 429 at least {@code threshold} times within {@link #SPIKE_WINDOW}.</li>
 * </ul>
 * Not thread-safe: the ops lane calls it one sample at a time.
 */
public final class OpsAlertRules {
    public static final int HEALTH_FAILS = 3;
    public static final Duration QUEUE_AGE_LIMIT = Duration.ofMinutes(5);
    public static final Duration SPIKE_WINDOW = Duration.ofMinutes(5);
    public static final Duration REMIND_AFTER = Duration.ofHours(1);

    /** One minute's look. {@code health} maps each checked address to whether it answered 2xx; a negative number means "couldn't read". */
    public record Sample(Instant at, Map<String, Boolean> health, double outboxOldestSeconds, long outboxDue, long discord429Total) {}

    /** A line for the ops channel. */
    public record Alert(String rule, boolean recovered, String text) {}

    private record Count(Instant at, long total) {}

    private final int spikeThreshold;
    private final Map<String, Integer> healthFails = new HashMap<>();
    private final Map<String, Instant> firing = new LinkedHashMap<>();
    private final Deque<Count> counts429 = new ArrayDeque<>();

    public OpsAlertRules(int spikeThreshold) {
        this.spikeThreshold = Math.max(1, spikeThreshold);
    }

    public List<Alert> evaluate(Sample s) {
        List<Alert> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : s.health().entrySet()) {
            String where = describe(e.getKey());
            int fails = Boolean.TRUE.equals(e.getValue()) ? 0 : healthFails.getOrDefault(e.getKey(), 0) + 1;
            healthFails.put(e.getKey(), fails);
            rule(out, s.at(), "health:" + e.getKey(), fails >= HEALTH_FAILS,
                    "Health check failing: " + where + " has not answered OK " + fails + " times in a row.",
                    "Health check recovered: " + where + " answers OK again.");
        }

        if (s.outboxOldestSeconds() >= 0) {
            rule(out, s.at(), "queue", s.outboxOldestSeconds() > QUEUE_AGE_LIMIT.toSeconds(),
                    "Discord job queue is behind: " + s.outboxDue() + " due, the oldest waiting " + minutes(s.outboxOldestSeconds()) + ".",
                    "Discord job queue caught up.");
        }

        if (s.discord429Total() >= 0) {
            counts429.addLast(new Count(s.at(), s.discord429Total()));
            while (counts429.size() > 1 && Duration.between(counts429.peekFirst().at(), s.at()).compareTo(SPIKE_WINDOW) > 0) counts429.removeFirst();
            long inWindow = s.discord429Total() - counts429.peekFirst().total();
            rule(out, s.at(), "429", inWindow >= spikeThreshold,
                    "Discord 429 spike: " + inWindow + " rate-limited answers in the last " + SPIKE_WINDOW.toMinutes() + " minutes (threshold " + spikeThreshold + ").",
                    "Discord 429s are back under the threshold.");
        }
        return out;
    }

    private void rule(List<Alert> out, Instant now, String key, boolean bad, String alert, String recovered) {
        Instant last = firing.get(key);
        if (bad) {
            if (last == null || !Duration.between(last, now).minus(REMIND_AFTER).isNegative()) {
                firing.put(key, now);
                out.add(new Alert(key, false, (last == null ? "" : "Still: ") + alert));
            }
        } else if (last != null) {
            firing.remove(key);
            out.add(new Alert(key, true, recovered));
        }
    }

    /** What is firing right now, for the weekly post. */
    public List<String> firing() {
        return List.copyOf(firing.keySet());
    }

    /** host and path of an address, never its query (which could hold a token). */
    static String describe(String url) {
        try {
            URI u = URI.create(url);
            return (u.getHost() == null ? "?" : u.getHost()) + (u.getPath() == null ? "" : u.getPath());
        } catch (IllegalArgumentException e) {
            return "an address in OPS_HEALTH_URLS";
        }
    }

    static String minutes(double seconds) {
        long s = Math.round(seconds);
        return s < 120 ? s + " s" : (s / 60) + " min";
    }
}
