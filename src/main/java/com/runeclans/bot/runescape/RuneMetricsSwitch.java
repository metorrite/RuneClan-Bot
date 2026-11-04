package com.runeclans.bot.runescape;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Whether RuneMetrics is still asked at all ({@code RUNEMETRICS}). Jagex announced the RuneMetrics and Adventurer's Log APIs would close (first
 * for 2026-11-10); Drake's decision (2026-11-02, plan D-1): keep asking until Jagex really switches it off, with no date in the code, and let
 * the breaker ({@link RuneMetricsBreaker}) handle the day it dies. So {@code auto} (the default) and {@code on} ask; {@code off} stops now.
 * When off, profile polls answer {@link ProfileResult.Unavailable} without a request, as they would in an outage.
 */
public final class RuneMetricsSwitch {
    enum Mode { AUTO, ON, OFF }

    private RuneMetricsSwitch() {
    }

    /** From the {@code RUNEMETRICS} environment variable. */
    public static BooleanSupplier fromEnv() {
        return fromEnv(System::getenv, Clock.systemUTC());
    }

    /** @param env looks a variable up, null when unset */
    public static BooleanSupplier fromEnv(Function<String, String> env, Clock clock) {
        Mode mode = parse(env.apply("RUNEMETRICS"));
        return () -> on(mode, clock.instant());
    }

    static Mode parse(String raw) {
        if (raw == null) return Mode.AUTO;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true" -> Mode.ON;
            case "off", "false" -> Mode.OFF;
            default -> Mode.AUTO;
        };
    }

    static boolean on(Mode mode, Instant now) {
        return switch (mode) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> true; // D-1: no stop date; the breaker and the hub's source switch decide
        };
    }
}
