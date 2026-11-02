package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The hub's polling list (T-736, plan C-8; runeclans-hub {@code GET /v1/polling/characters}): every character linked with RuneLink, each in
 * one group, so the poll tiers can track every linked player and not only the clans of the bot's servers and its own {@code /rsn} accounts.
 * <p>
 * The list is read whole (page by page) at most once per {@link #KEEP}, so the three tiers that ask for it within one cycle share one read.
 * When the hub client is off, or the hub answers 404 (an older hub that still had the "Track every linked player" switch, off), the list is empty and the
 * tiers poll what they polled before; that is logged once per change, not every run. When the hub can't be reached the last list is used
 * while it is under {@link #STALE_AFTER} old, then nothing.
 */
final class HubPollingList {
    private static final Logger log = LoggerFactory.getLogger(HubPollingList.class);
    static final String PATH = "/v1/polling/characters";
    static final int PAGE = 1000;
    /** A safety stop: 200 pages of 1,000 is far beyond today's players, and a cursor that never ends can't hold the poll lane. */
    static final int MAX_PAGES = 200;
    static final Duration KEEP = Duration.ofMinutes(5);
    static final Duration STALE_AFTER = Duration.ofHours(6);

    /** The hub's groups, each a list of names as the hub has them. */
    record Groups(List<String> clan, List<String> community, List<String> linked) {
        static final Groups EMPTY = new Groups(List.of(), List.of(), List.of());

        int size() {
            return clan.size() + community.size() + linked.size();
        }
    }

    private enum State { OFF, UNREACHABLE, ON }

    private final Function<String, RuneClansHubClient.Answer> get;
    private final Clock clock;
    private Groups last = Groups.EMPTY;
    private Instant readAt;
    private Instant okAt;
    private State logged;

    /** Reads through {@code hub}'s first-party key; with the client off, every read answers as the hub does with its switch off. */
    static HubPollingList from(RuneClansHubClient hub) {
        return new HubPollingList(path -> hub == null || !hub.enabled() ? new RuneClansHubClient.Answer(404, null) : hub.call("GET", path, null), Clock.systemUTC());
    }

    /** {@code get} answers a {@code GET path}, or null when the hub can't be reached. */
    HubPollingList(Function<String, RuneClansHubClient.Answer> get, Clock clock) {
        this.get = get;
        this.clock = clock;
    }

    /** The groups as of at most {@link #KEEP} ago. Runs on the poll-jobs lane, so a slow hub only delays the next job. */
    synchronized Groups groups() {
        Instant now = clock.instant();
        if (readAt != null && now.isBefore(readAt.plus(KEEP))) return last;
        readAt = now;
        List<String> clan = new ArrayList<>(), community = new ArrayList<>(), linked = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            RuneClansHubClient.Answer a = get.apply(PATH + "?limit=" + PAGE + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
            if (a == null || !a.ok() || a.json() == null) return failed(now, a != null && (a.status() == 404 || a.status() == 403));
            DataArray characters = a.json().optArray("characters").orElse(DataArray.empty());
            for (int i = 0; i < characters.length(); i++) {
                DataObject c = characters.getObject(i);
                String rsn = c.getString("rsn", "").strip();
                if (rsn.isEmpty()) continue;
                switch (c.getString("group", "")) {
                    case "clan" -> clan.add(rsn);
                    case "community" -> community.add(rsn);
                    default -> linked.add(rsn);
                }
            }
            cursor = a.json().isNull("nextCursor") ? null : a.json().getString("nextCursor", null);
            if (cursor == null || cursor.isBlank()) {
                last = new Groups(List.copyOf(clan), List.copyOf(community), List.copyOf(linked));
                okAt = now;
                note(State.ON, last.size() + " linked character(s) from the hub are polled with the clan, community and linked tiers.");
                return last;
            }
        }
        log.warn("The hub's polling list ran past {} pages; using the last complete list.", MAX_PAGES);
        return failed(now, false);
    }

    private Groups failed(Instant now, boolean off) {
        if (off) {
            last = Groups.EMPTY;
            okAt = null;
            note(State.OFF, "The hub's polling list can't be used (RUNECLANS_HUB_ENABLED off, or the hub answered 404/403): only the bot's own clans and /rsn accounts are polled.");
            return last;
        }
        note(State.UNREACHABLE, "The hub's polling list can't be read just now; the last list is used for up to " + STALE_AFTER.toHours() + " hours.");
        if (okAt == null || now.isAfter(okAt.plus(STALE_AFTER))) last = Groups.EMPTY;
        return last;
    }

    private void note(State state, String message) {
        if (state == logged) return;
        logged = state;
        if (state == State.UNREACHABLE) log.warn(message);
        else log.info(message);
    }
}
