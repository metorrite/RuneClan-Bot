package com.runeclans.bot.runescape;

import java.time.Duration;

/**
 * A player's profile as the hub holds it ({@link RuneScapeStatsService#latestAsResult}), or why there is none. The bot fetches nothing from
 * Jagex itself; {@code Unavailable} is "the hub has nothing for this name right now".
 */
public sealed interface ProfileResult {
    /** A public profile was fetched successfully. */
    record Found(RuneScapeProfile profile) implements ProfileResult {}

    /** {@code error: "PROFILE_PRIVATE"} — the account exists but has hidden its Adventurer's Log. */
    record Private() implements ProfileResult {}

    /** {@code error: "NO_PROFILE"} (verified live) — no RuneMetrics profile for this name, real or not. */
    record NotFound() implements ProfileResult {}

    /**
     * {@code HTTP 429} — the API asked us to back off. Kept distinct from {@link Unavailable} because
     * the right response is different: the poll coordinator slows its whole request rate and
     * puts this RSN back in the queue for a later retry instead of just counting it as a failed poll and
     * moving on at the normal cadence, which would only make the rate limit worse.
     * {@code retryAfter} is the server's own {@code Retry-After} hint, if it sent one parseable as a
     * plain number of seconds; {@code null} otherwise.
     */
    record RateLimited(Duration retryAfter) implements ProfileResult {}

    /** The request failed outright (network error, non-2xx status, unrecognized error code, etc). */
    record Unavailable() implements ProfileResult {}
}
