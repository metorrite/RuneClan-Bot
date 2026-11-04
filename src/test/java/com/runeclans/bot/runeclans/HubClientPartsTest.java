package com.runeclans.bot.runeclans;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hub client's retries, cache, change eviction and write queue (runeclans-hub PLATFORM-DESIGN B-0.7), with a fake hub and clock. */
class HubClientPartsTest {
    private static final String KEY = "rsh_" + "k".repeat(43);

    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-25T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static RuneClansHubClient.Answer ok(String field) {
        return new RuneClansHubClient.Answer(200, DataObject.empty().put("v", field));
    }

    private static RuneClansSettings on() {
        return RuneClansSettings.from(Map.of("RUNECLANS_HUB_ENABLED", "true", "RUNECLANS_HUB_URL", "http://localhost:8090", "RUNECLANS_HUB_API_KEY", KEY)::get);
    }

    @Test
    void headersReachTheHubOnEveryTry() {
        List<Map<String, String>> seen = new ArrayList<>();
        RuneClansHubClient.Transport inner = new RuneClansHubClient.Transport() {
            @Override
            public RuneClansHubClient.Answer call(String method, String path, String body) {
                throw new AssertionError("headers dropped");
            }

            @Override
            public RuneClansHubClient.Answer call(String method, String path, String body, Map<String, String> headers) {
                seen.add(headers);
                return seen.size() == 1 ? new RuneClansHubClient.Answer(503, null) : ok("x");
            }
        };
        HubRetry retry = new HubRetry(inner, w -> { });
        assertTrue(retry.call("PUT", "/v1/requests/x/status", "{}", Map.of("If-Match", "4")).ok());
        assertEquals(List.of(Map.of("If-Match", "4"), Map.of("If-Match", "4")), seen);
        assertEquals(200, new RuneClansHubClient(on(), retry).call("PUT", "/v1/x", "{}", Map.of("If-Match", "1")).status());
        assertNull(new RuneClansHubClient(RuneClansSettings.OFF, inner).call("PUT", "/v1/x", "{}", Map.of("If-Match", "1")), "off: never called");
    }

    @Test
    void aCallNeverOutlivesTheWebsitesPatience() {
        assertEquals(Duration.ofSeconds(8), RuneClansHubClient.REQUEST_TIMEOUT, "the website gives up on the bot after 8 seconds");
    }

    @Test
    void aReadWithItsOwnBudgetIsTriedOnceWithoutTheSleeps() {
        List<Duration> waits = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HubRetry down = new HubRetry((m, p, b) -> {
            calls.incrementAndGet();
            return new RuneClansHubClient.Answer(503, null);
        }, waits::add);
        assertEquals(503, RuneClansHubClient.withoutRetries(() -> down.call("GET", "/v1/x", null)).status());
        assertEquals(1, calls.get(), "one try inside withoutRetries");
        assertTrue(waits.isEmpty(), "and no sleep");
        calls.set(0);
        assertEquals(503, down.call("GET", "/v1/x", null).status());
        assertEquals(1 + HubRetry.BACKOFF.size(), calls.get(), "outside it, the retries are back");
    }

    @Test
    void retriesOnlyWhatIsSafeToRepeat() {
        List<Duration> waits = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HubRetry flaky = new HubRetry((m, p, b) -> calls.incrementAndGet() == 1 ? null : ok("x"), waits::add);
        assertTrue(flaky.call("GET", "/v1/x", null).ok());
        assertEquals(2, calls.get());
        assertEquals(List.of(HubRetry.BACKOFF.get(0)), waits);

        calls.set(0);
        waits.clear();
        HubRetry down = new HubRetry((m, p, b) -> {
            calls.incrementAndGet();
            return new RuneClansHubClient.Answer(503, null);
        }, waits::add);
        assertEquals(503, down.call("PUT", "/v1/x", "{}").status());
        assertEquals(1 + HubRetry.BACKOFF.size(), calls.get(), "tries, then gives up");
        assertEquals(HubRetry.BACKOFF, waits);

        calls.set(0);
        assertEquals(503, down.call("POST", "/v1/x", "{}").status());
        assertEquals(1, calls.get(), "a POST is never repeated");

        calls.set(0);
        assertEquals(404, new HubRetry((m, p, b) -> {
            calls.incrementAndGet();
            return new RuneClansHubClient.Answer(404, null);
        }, waits::add).call("GET", "/v1/x", null).status());
        assertEquals(1, calls.get(), "a refusal is final");

        // the hub's Retry-After: waited when short, not at all when longer than a click can wait
        waits.clear();
        calls.set(0);
        HubRetry busy = new HubRetry((m, p, b) -> calls.incrementAndGet() == 1 ? new RuneClansHubClient.Answer(429, null, 1) : ok("x"), waits::add);
        assertTrue(busy.call("GET", "/v1/x", null).ok());
        assertEquals(List.of(Duration.ofSeconds(1)), waits);
        calls.set(0);
        HubRetry slow = new HubRetry((m, p, b) -> {
            calls.incrementAndGet();
            return new RuneClansHubClient.Answer(429, null, 30);
        }, waits::add);
        assertEquals(429, slow.call("GET", "/v1/x", null).status());
        assertEquals(1, calls.get());

        assertEquals(30, RuneClansHubClient.retryAfter(" 30 "));
        assertNull(RuneClansHubClient.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertNull(RuneClansHubClient.retryAfter(null));
    }

    @Test
    void cacheIsBoundedAndAges() {
        TestClock clock = new TestClock();
        HubCache cache = new HubCache(3, clock);
        for (int i = 0; i < 5; i++) cache.put("k" + i, ok("v" + i), Set.of());
        assertEquals(3, cache.size(), "size cap respected");
        assertNull(cache.fresh("k0"));
        assertNull(cache.fresh("k1"));
        assertNotNull(cache.fresh("k2"));
        // least recently used goes first: k2 was just read, so k3 is dropped for the next one
        cache.put("k5", ok("v5"), Set.of());
        assertNotNull(cache.fresh("k2"));
        assertNull(cache.fresh("k3"));

        clock.advance(HubCache.FRESH.plusSeconds(1));
        assertNull(cache.fresh("k2"));
        HubCache.Hit stale = cache.lookup("k2");
        assertNotNull(stale);
        assertTrue(stale.stale());
        clock.advance(HubCache.STALE_LIMIT);
        assertNull(cache.lookup("k2"), "too old even for a hub-down answer");
    }

    @Test
    void anAnswerCanStayFreshLonger() {
        TestClock clock = new TestClock();
        HubCache cache = new HubCache(10, clock);
        cache.put("/v1/access/servers", ok("gates"), Set.of("access"), Duration.ofMinutes(10));
        cache.put("long", ok("x"), Set.of(), Duration.ofHours(1));
        clock.advance(HubCache.FRESH.plusSeconds(1));
        assertNotNull(cache.fresh("/v1/access/servers"), "gates stay fresh 10 minutes");
        clock.advance(Duration.ofMinutes(8));
        assertNull(cache.fresh("/v1/access/servers"));
        assertTrue(cache.lookup("/v1/access/servers").stale());
        clock.advance(HubCache.STALE_LIMIT.minus(Duration.ofMinutes(10)));
        assertNull(cache.lookup("long"), "never kept past the stale limit, however long it asked for");
    }

    @Test
    void hubChangesEmptyTheCache() {
        HubCache cache = new HubCache(100, new TestClock());
        cache.put("/v1/clans/a", ok("a"), Set.of(HubChanges.tag("clan", "a")));
        cache.put("/v1/clans/by-name/x", ok("a"), Set.of(HubChanges.tag("clan", "a")));
        cache.put("/v1/clans/b", ok("b"), Set.of(HubChanges.tag("clan", "b")));
        cache.put("/v1/access/guilds/1", ok("g"), Set.of("access"));
        // the hub's one write path puts item and id in every change
        assertEquals(2, HubChanges.evict(cache, "clan.updated", DataObject.empty().put("item", "clan").put("id", "a").put("rev", 2)));
        assertNull(cache.fresh("/v1/clans/a"));
        assertNotNull(cache.fresh("/v1/clans/b"));
        assertEquals(0, HubChanges.evict(cache, "plugin.event", DataObject.empty().put("type", "DROP")));
        assertEquals(1, HubChanges.evict(cache, "access.changed", DataObject.empty()));
        assertNull(cache.fresh("/v1/access/guilds/1"));
        // a server's site and its role mappings (T-505): any site change empties them
        cache.put("/v1/guilds/1/site", ok("s"), Set.of("sites"));
        cache.put("/v1/guilds/1/role-mappings", ok("r"), Set.of("sites"));
        assertEquals(2, HubChanges.evict(cache, "site.published", DataObject.empty()));
        assertNull(cache.fresh("/v1/guilds/1/site"));
        // a community's mapped Discord role is one of the role mappings (T-732)
        cache.put("/v1/guilds/1/role-mappings", ok("r"), Set.of("sites"));
        assertEquals(1, HubChanges.evict(cache, "community.changed", DataObject.empty()));
        // a league, its terms, a round or a result moved (T-759)
        cache.put("/v1/guilds/1/leagues", ok("l"), Set.of("leagues"));
        assertEquals(1, HubChanges.evict(cache, "league.results", DataObject.empty()));
        assertEquals(0, HubChanges.evict(cache, null, null));
    }

    @Test
    void webhookSignaturesAreChecked() {
        String secret = "whsec_" + "s".repeat(43);
        String body = "{\"id\":\"1\",\"kind\":\"clan.updated\",\"data\":{}}";
        long t = 1_761_400_000L;
        String header = "t=" + t + ",v1=" + HubChanges.sign(secret, t + "." + body);
        assertTrue(HubChanges.signatureValid(secret, header, body, t + 10));
        assertFalse(HubChanges.signatureValid("whsec_other", header, body, t));
        assertFalse(HubChanges.signatureValid(secret, header, body.replace("1", "2"), t));
        assertFalse(HubChanges.signatureValid(secret, header, body, t + HubChanges.MAX_SIGNATURE_AGE_SECONDS + 1), "an old one can't be replayed");
        assertFalse(HubChanges.signatureValid(secret, "v1=abc", body, t));
        assertFalse(HubChanges.signatureValid(secret, "t=x,v1=abc", body, t));
        assertFalse(HubChanges.signatureValid(secret, null, body, t));
    }

    @Test
    void writesWaitWhileTheHubIsDown() {
        TestClock clock = new TestClock();
        AtomicReference<RuneClansHubClient.Answer> hub = new AtomicReference<>(null);
        List<String> sent = new ArrayList<>();
        HubWriteQueue queue = new HubWriteQueue((m, p, b) -> {
            RuneClansHubClient.Answer a = hub.get();
            if (a != null && a.ok()) sent.add(p);
            return a;
        }, clock, 2);
        List<HubWriteQueue.Outcome> told = new ArrayList<>();

        assertEquals(HubWriteQueue.Outcome.QUEUED, queue.submit(new HubWriteQueue.Write("POST", "/v1/a", "{}", r -> told.add(r.outcome()))).outcome());
        assertEquals(HubWriteQueue.Outcome.QUEUED, queue.submit(new HubWriteQueue.Write("POST", "/v1/b", "{}", r -> told.add(r.outcome()))).outcome());
        assertEquals(HubWriteQueue.Outcome.REFUSED, queue.submit(new HubWriteQueue.Write("POST", "/v1/c", "{}", null)).outcome(), "full: try again");
        assertEquals(0, queue.drain(), "still down: nothing sent, nothing dropped");
        assertEquals(2, queue.size());

        hub.set(ok("x"));
        assertEquals(2, queue.drain());
        assertEquals(List.of("/v1/a", "/v1/b"), sent);
        assertEquals(List.of(HubWriteQueue.Outcome.SENT, HubWriteQueue.Outcome.SENT), told);
        assertEquals(0, queue.size());

        // held too long: dropped, and the owner is told to try again
        hub.set(null);
        told.clear();
        queue.submit(new HubWriteQueue.Write("POST", "/v1/d", "{}", r -> told.add(r.outcome())));
        clock.advance(HubWriteQueue.MAX_AGE.plusSeconds(1));
        hub.set(ok("x"));
        assertEquals(0, queue.drain());
        assertEquals(List.of(HubWriteQueue.Outcome.REFUSED), told);
        assertFalse(sent.contains("/v1/d"));

        // a refusal from the hub is final, never queued
        hub.set(new RuneClansHubClient.Answer(409, DataObject.empty().put("error", "changed_elsewhere")));
        HubWriteQueue.Result refused = queue.submit(new HubWriteQueue.Write("PUT", "/v1/e", "{}", null));
        assertEquals(HubWriteQueue.Outcome.REFUSED, refused.outcome());
        assertEquals(409, refused.answer().status());
        assertEquals(0, queue.size());
    }

    @Test
    void readsAnswerFromTheCacheWhenTheHubIsDown() {
        TestClock clock = new TestClock();
        AtomicReference<RuneClansHubClient.Answer> answer = new AtomicReference<>(ok("first"));
        AtomicInteger calls = new AtomicInteger();
        RuneClansHubClient hub = new RuneClansHubClient(on(), (m, p, b) -> {
            calls.incrementAndGet();
            return answer.get();
        }).withClock(clock);

        RuneClansHubClient.Read first = hub.read("/v1/clans/a", HubChanges.tag("clan", "a"));
        assertFalse(first.fromCache());
        assertEquals("first", hub.read("/v1/clans/a").answer().json().getString("v"));
        assertEquals(1, calls.get(), "the second read came from the cache");

        // a change from the hub empties it; the next read asks again
        answer.set(ok("second"));
        hub.onHubChange("clan.updated", DataObject.empty().put("item", "clan").put("id", "a"));
        assertEquals("second", hub.read("/v1/clans/a", HubChanges.tag("clan", "a")).answer().json().getString("v"));
        assertEquals(2, calls.get());

        // hub down: the cached copy, marked stale once past its fresh time
        answer.set(null);
        clock.advance(HubCache.FRESH.plusSeconds(1));
        RuneClansHubClient.Read down = hub.read("/v1/clans/a");
        assertTrue(down.fromCache());
        assertTrue(down.stale());
        assertEquals("second", down.answer().json().getString("v"));
        assertNull(hub.read("/v1/clans/never-read"), "down and nothing cached");

        // a refusal is never cached
        answer.set(new RuneClansHubClient.Answer(404, null));
        assertEquals(404, hub.read("/v1/clans/gone").answer().status());
        assertNull(hub.cache().fresh("/v1/clans/gone"));

        RuneClansHubClient off = new RuneClansHubClient(RuneClansSettings.OFF, (m, p, b) -> {
            throw new AssertionError("an off client never calls");
        });
        assertNull(off.read("/v1/clans/a"));
        assertEquals(HubWriteQueue.Outcome.REFUSED, off.write("POST", "/v1/x", "{}", null).outcome());
        assertEquals(0, off.drainWrites());
    }
}
