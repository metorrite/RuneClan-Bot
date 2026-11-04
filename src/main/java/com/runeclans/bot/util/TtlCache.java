package com.runeclans.bot.util;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A tiny cache whose entries expire a fixed time after they were stored. Used where several servers would ask a rate-limited service
 * for the same thing within moments of each other (two servers tracking the same clan at the daily sync): the first asks, the rest
 * reuse the answer. Only values the caller chooses to {@link #put} are kept, so a failed lookup is never remembered.
 */
public final class TtlCache<K, V> {
    private record Entry<V>(V value, long storedAtMillis) {}

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final LongSupplier clock;

    public TtlCache(Duration ttl) {
        this(ttl, System::currentTimeMillis);
    }

    /** For tests: a clock that returns milliseconds. */
    TtlCache(Duration ttl, LongSupplier clock) {
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
    }

    public Optional<V> get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (clock.getAsLong() - entry.storedAtMillis() >= ttlMillis) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public void put(K key, V value) {
        entries.put(key, new Entry<>(value, clock.getAsLong()));
        if (entries.size() > 256) {
            long now = clock.getAsLong();
            entries.values().removeIf(e -> now - e.storedAtMillis() >= ttlMillis);
        }
    }

    /** Forgets {@code key} now. */
    public void remove(K key) {
        entries.remove(key);
    }
}
