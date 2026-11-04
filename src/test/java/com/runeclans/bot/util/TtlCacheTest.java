package com.runeclans.bot.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtlCacheTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final TtlCache<String, String> cache = new TtlCache<>(Duration.ofSeconds(90), now::get);

    @Test
    void nothingIsKnownUntilSomethingIsStored() {
        assertTrue(cache.get("younglings").isEmpty());
    }

    @Test
    void aStoredValueIsReusedWithinTheTtl() {
        cache.put("younglings", "roster");
        now.addAndGet(89_000);

        assertEquals("roster", cache.get("younglings").orElseThrow());
    }

    @Test
    void aStoredValueExpiresAtTheTtl() {
        cache.put("younglings", "roster");
        now.addAndGet(90_000);

        assertTrue(cache.get("younglings").isEmpty());
    }

    @Test
    void keysAreIndependent() {
        cache.put("a", "1");

        assertTrue(cache.get("b").isEmpty());
    }

    @Test
    void storingAgainRestartsTheClock() {
        cache.put("a", "1");
        now.addAndGet(80_000);
        cache.put("a", "2");
        now.addAndGet(80_000);

        assertEquals("2", cache.get("a").orElseThrow());
    }

    @Test
    void aRemovedValueIsGone() {
        cache.put("younglings", "roster");
        cache.remove("younglings");
        assertTrue(cache.get("younglings").isEmpty());
    }
}
