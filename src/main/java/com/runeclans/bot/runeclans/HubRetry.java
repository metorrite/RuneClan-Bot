package com.runeclans.bot.runeclans;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Retries for calls to the RuneClans hub (runeclans-hub PLATFORM-DESIGN B-0.7): a call the hub didn't answer (unreachable), or answered
 * {@code 429}, {@code 502}, {@code 503} or {@code 504}, is tried again after {@link #BACKOFF} (or the hub's {@code Retry-After}, at most
 * {@link #MAX_WAIT}). Only calls that are safe to repeat are retried ({@code GET}, {@code PUT}, {@code DELETE}); a {@code POST} gets one try,
 * and writes the hub can't take right now go to {@link HubWriteQueue}. The waits add up to well under Discord's 3-second answer limit.
 */
final class HubRetry implements RuneClansHubClient.Transport {
    static final List<Duration> BACKOFF = List.of(Duration.ofMillis(200), Duration.ofMillis(600));
    static final Duration MAX_WAIT = Duration.ofSeconds(1);
    private static final Set<String> REPEATABLE = Set.of("GET", "PUT", "DELETE");
    private static final Set<Integer> TRY_AGAIN = Set.of(429, 502, 503, 504);

    /** Waits; replaced in tests so they don't sleep. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final RuneClansHubClient.Transport inner;
    private final Sleeper sleeper;

    HubRetry(RuneClansHubClient.Transport inner, Sleeper sleeper) {
        this.inner = inner;
        this.sleeper = sleeper;
    }

    static HubRetry around(RuneClansHubClient.Transport inner) {
        return new HubRetry(inner, d -> Thread.sleep(d.toMillis()));
    }

    @Override
    public RuneClansHubClient.Answer call(String method, String path, String body) {
        return call(method, path, body, Map.of());
    }

    /** True on a thread that is inside {@link #withoutRetries}: one try, no sleeps. */
    private static final ThreadLocal<Boolean> NO_RETRY = ThreadLocal.withInitial(() -> false);

    /** Runs {@code work} with every hub call on this thread made once, without the retry sleeps: for reads that have a budget of their own (T-851). */
    static <T> T withoutRetries(java.util.function.Supplier<T> work) {
        Boolean before = NO_RETRY.get();
        NO_RETRY.set(true);
        try {
            return work.get();
        } finally {
            NO_RETRY.set(before);
        }
    }

    @Override
    public RuneClansHubClient.Answer call(String method, String path, String body, Map<String, String> headers) {
        boolean repeatable = REPEATABLE.contains(method.toUpperCase(Locale.ROOT)) && !NO_RETRY.get();
        RuneClansHubClient.Answer answer = inner.call(method, path, body, headers);
        for (int attempt = 0; repeatable && attempt < BACKOFF.size() && worthRetrying(answer); attempt++) {
            Duration wait = BACKOFF.get(attempt);
            if (answer != null && answer.retryAfterSeconds() != null) wait = Duration.ofSeconds(answer.retryAfterSeconds());
            if (wait.compareTo(MAX_WAIT) > 0) return answer; // the hub asked for longer than a click can wait
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return answer;
            }
            answer = inner.call(method, path, body, headers);
        }
        return answer;
    }

    /** No answer at all, or an answer that means "busy, try again shortly". */
    static boolean worthRetrying(RuneClansHubClient.Answer answer) {
        return answer == null || TRY_AGAIN.contains(answer.status());
    }
}
