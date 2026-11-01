package com.runeclans.bot.runeclans;

import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The platform switches the owner sets in RuneClan Admin > Switches on the website (runeclans-hub docs/SWITCHES.md), as RuneClan Bot reads
 * them: {@code GET /v1/platform/switches} with the bot's first-party key, asked every {@value #EVERY_SECONDS} seconds on its own lane (never
 * in a Discord interaction or a website request). A failed ask keeps the last answer until a new one comes (T-817 review: the three switches
 * the bot reads guard data, so a hub outage must not send site writes back to the bot's old tables, drop hide-me saves, or swap the hub's
 * beta lists for the bot's stale ones). Before the first answer, or with the hub client off, there is no panel value and every switch
 * follows its Railway variable or its default.
 * <p>
 * The rule is the website's and the hub's: the bot's own Railway variable wins while set. For an on/off switch {@code on}/{@code true}/
 * {@code 1}/{@code yes} holds it on and any other value holds it off (the emergency kill switch; a typo counts as off); for a choice one of
 * its values holds it at that value and any other value at its default; only unset or blank lets the panel decide. The panel can't see the
 * bot's variables, so after "Hand over" on the panel delete them on the bot's Railway service too.
 */
@BConfiguration
public class PanelSwitches {
    private static final Logger log = LoggerFactory.getLogger(PanelSwitches.class);
    static final String PATH = "/v1/platform/switches";
    static final long EVERY_SECONDS = 30;

    /** One ask of the hub: its {@code switches} object, or null when it couldn't answer. */
    interface Fetch {
        DataObject switches();
    }

    private final Fetch fetch;
    private final Clock clock;
    /** The last answer and when it came, as one snapshot. */
    private record Answer(DataObject switches, Instant at) {}

    private volatile Answer last;
    /** The hub has stopped answering since the last answer (logged once per change, not on every round). */
    private volatile boolean failing;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static PanelSwitches panelSwitches(RuneClansHubClient hub) {
        if (!hub.enabled()) return none();
        PanelSwitches switches = new PanelSwitches(() -> {
            RuneClansHubClient.Answer a = hub.call("GET", PATH, null);
            DataObject answer = answerOf(a);
            if (answer == null) throw new IllegalStateException(a == null ? "no answer" : "status " + a.status());
            return answer;
        }, Clock.systemUTC());
        ScheduledExecutorService lane = BotScheduler.lane("panel-switches");
        lane.scheduleWithFixedDelay(switches::refresh, 0, EVERY_SECONDS, TimeUnit.SECONDS);
        return switches;
    }

    PanelSwitches(Fetch fetch, Clock clock) {
        this.fetch = fetch;
        this.clock = clock;
    }

    /** No panel: every switch follows its Railway variable or its default (the hub client off, and tests). */
    public static PanelSwitches none() {
        return new PanelSwitches(() -> null, Clock.systemUTC());
    }

    /** Panel values from a fixed map, as if the hub had just answered (for tests elsewhere). */
    public static PanelSwitches of(Map<String, String> values) {
        DataObject json = DataObject.empty();
        values.forEach(json::put);
        PanelSwitches switches = new PanelSwitches(() -> json, Clock.systemUTC());
        switches.refresh();
        return switches;
    }

    static DataObject answerOf(RuneClansHubClient.Answer a) {
        if (a == null || !a.ok() || a.json() == null || !a.json().hasKey("switches")) return null;
        try {
            DataObject switches = a.json().getObject("switches");
            return switches.keys().isEmpty() ? null : switches; // an empty list is a broken hub (the real one lists every switch), not "all off"
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Asks the hub once; keeps the answer when there is one, else the last one stands. Runs on the lane. */
    void refresh() {
        DataObject answer;
        String why = null;
        try {
            answer = fetch.switches();
        } catch (RuntimeException e) {
            answer = null;
            why = e.getMessage();
        }
        if (answer != null) {
            last = new Answer(answer, clock.instant());
            if (failing) log.info("The hub's Switches panel answers again.");
            failing = false;
        } else if (!failing) {
            failing = true;
            Answer kept = last;
            if (kept == null) log.warn("The hub's Switches panel can't be read ({}): switches follow Railway or their defaults until it answers.", why);
            else log.warn("The hub's Switches panel can't be read ({}): the last answer, from {}, stands until it answers.", why, kept.at());
        }
    }

    /** The panel's value of switch {@code id} ("on", "hub"), or null before the first answer (see the class comment). */
    public String value(String id) {
        Answer now = last;
        if (now == null) return null;
        Object v = now.switches().opt(id).orElse(null);
        return v == null ? null : v.toString().strip().toLowerCase(Locale.ROOT);
    }

    /** An on/off switch: Railway's {@code railway} value while it holds it, else the panel's {@code id}; off when neither says on. */
    public BooleanSupplier toggle(String id, String railway) {
        Boolean held = hold(railway);
        if (held != null) return () -> held;
        return () -> "on".equals(value(id));
    }

    /**
     * A choice: Railway's {@code railway} value while it is one of {@code values}, else the panel's {@code id} when it is one of them, else
     * {@code fallback} (the switch's default).
     */
    public Supplier<String> choice(String id, String railway, Collection<String> values, String fallback) {
        String raw = railway == null ? null : railway.strip().toLowerCase(Locale.ROOT);
        if (raw != null && values.contains(raw)) return () -> raw;
        if (raw != null && !raw.isEmpty()) {
            log.warn("A switch variable on Railway has a value that isn't one of its choices; it holds the switch at {}.", fallback);
            return () -> fallback;
        }
        return () -> {
            String v = value(id);
            return v != null && values.contains(v) ? v : fallback;
        };
    }

    /**
     * A Railway value as a hold: on/true/1/yes holds on, off/false/0/no holds off, any other value set holds off too (before the panel, only
     * an on word turned these on, so a typo stays the kill switch); unset or blank lets the panel decide.
     */
    public static Boolean hold(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1", "yes" -> true;
            case "off", "false", "0", "no" -> false;
            default -> {
                log.warn("A switch variable on Railway has a value that is neither on nor off; it holds the switch off.");
                yield false;
            }
        };
    }
}
