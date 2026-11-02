package com.runeclans.bot.runescape;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The first poll of a newly linked player (Drake 2026-11-02: everyone who links is polled once on linking, then on their tier's cycle). Each run
 * of the "first polls" job hands this every name the bot tracks (the hub's polling list and the {@code /rsn} accounts) and gets back the ones
 * with no stats snapshot at all, so a new link is polled within minutes instead of waiting for the linked tier's daily cycle. The same check
 * backfills anyone linked earlier who was never polled.
 * <p>
 * A name RuneMetrics can't answer for (a private profile, a name change) stays without a snapshot, so a name is offered again only after
 * {@link #RETRY}. That memory lives in the bot's process: a restart offers every unpolled name once more. Each run offers at most the number
 * it is given, so a big backlog (a restart, or many links at once) drains a few names at a time instead of crowding out the clan tier.
 */
final class FirstPolls {
    /** How long before a name that still has no snapshot is offered again. */
    static final Duration RETRY = Duration.ofHours(6);

    private final Clock clock;
    private final Map<String, Instant> offered = new HashMap<>();

    FirstPolls(Clock clock) {
        this.clock = clock;
    }

    /**
     * The names of {@code names} (once each, first spelling, compared ignoring case) that have no snapshot and were not offered within
     * {@link #RETRY}, at most {@code max} of them; those count as offered now. {@code polled} answers which of the lower-cased names it is given have any snapshot.
     */
    synchronized List<String> due(Collection<String> names, Function<Collection<String>, Set<String>> polled, int max) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String raw : names) {
            if (raw == null || raw.isBlank()) continue;
            String rsn = raw.strip();
            unique.putIfAbsent(rsn.toLowerCase(Locale.ROOT), rsn);
        }
        if (unique.isEmpty()) {
            offered.clear();
            return List.of();
        }
        Set<String> have = polled.apply(unique.keySet());
        Instant now = clock.instant();
        // forget names that got their snapshot, or that nobody tracks any more, so the memory stays as small as the list
        offered.keySet().removeIf(key -> have.contains(key) || !unique.containsKey(key));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : unique.entrySet()) {
            if (out.size() >= max) break;
            if (have.contains(e.getKey())) continue;
            Instant at = offered.get(e.getKey());
            if (at != null && now.isBefore(at.plus(RETRY))) continue;
            offered.put(e.getKey(), now);
            out.add(e.getValue());
        }
        return out;
    }
}
