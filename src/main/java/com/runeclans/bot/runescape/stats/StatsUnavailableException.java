package com.runeclans.bot.runescape.stats;

/**
 * A stats view could only be answered in part, or not at all, because the hub didn't answer some of the reads behind it (down, busy, or over
 * the time budget). Thrown instead of a quietly shorter leaderboard, so the internal API answers 503 "try again" and nothing caches the gap.
 */
public class StatsUnavailableException extends RuntimeException {
    public StatsUnavailableException(String message) {
        super(message);
    }
}
