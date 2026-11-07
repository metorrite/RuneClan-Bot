package com.runeclans.bot.runescape;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Reads RuneMetrics' own raw activity-date string ({@code "23-Sep-2026 23:30"}, see {@link PlayerActivity#date()}).
 * <p>
 * <b>What the string is.</b> It is the wall-clock time of the event in <i>UK local time</i> (Europe/London: GMT in winter, BST in summer), not UTC. It was
 * mistaken for UTC at first, which made everything read one hour late during British Summer Time. The evidence is in our own data: read as UTC, 19% of
 * activities appear to have happened after we first polled them; read as Europe/London, none of 5,553 do, and the closest is 0.4 minutes after the event,
 * so an entry also shows up in the log within about a minute of the event, and the time is when the event happened, not when it was logged.
 * <p>
 * Whether the zone is really "UK local time" or a fixed UTC+1 can't be told from summer data alone (they are the same until the clocks go back on
 * 2026-10-25); the zone can be overridden with the {@code RUNEMETRICS_TIME_ZONE}
 * environment variable without a code change.
 */
public final class RuneMetricsDates {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH);
    private static final ZoneId ZONE = zoneFromEnvironment();

    private RuneMetricsDates() {}

    private static ZoneId zoneFromEnvironment() {
        String raw = System.getenv("RUNEMETRICS_TIME_ZONE");
        if (raw != null && !raw.isBlank()) {
            try {
                return ZoneId.of(raw.strip());
            } catch (DateTimeException ignored) {
                // fall through to the default
            }
        }
        return ZoneId.of("Europe/London");
    }

    /** The zone RuneMetrics' dates are written in. */
    public static ZoneId zone() {
        return ZONE;
    }

    /** The wall-clock reading as written, or null if {@code raw} doesn't parse. */
    public static LocalDateTime parseOrNull(String raw) {
        try {
            return raw == null ? null : LocalDateTime.parse(raw.strip(), FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The real moment the event happened, or null if {@code raw} doesn't parse. A time inside the hour that repeats when the clocks go back is read as
     * the first of the two (daylight time), the one {@link ZoneId} resolves to by default.
     */
    public static Instant toInstant(String raw) {
        LocalDateTime local = parseOrNull(raw);
        return local == null ? null : local.atZone(ZONE).toInstant();
    }

    /** {@link LocalDateTime#MIN} if {@code raw} doesn't parse, so one malformed row sorts first instead of throwing. For ordering within one batch only. */
    public static LocalDateTime parse(String raw) {
        LocalDateTime parsed = parseOrNull(raw);
        return parsed == null ? LocalDateTime.MIN : parsed;
    }
}
