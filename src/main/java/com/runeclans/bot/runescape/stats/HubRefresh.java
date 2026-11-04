package com.runeclans.bot.runescape.stats;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The hub's Refresh ({@code POST /v1/players/{rsn}/refresh}, T-848): what "Update now", the admin lookup and the website's Refresh call while
 * the hub polls. The body names the person who pressed ({@code discordId}), so the hub's per-person limit (5 refreshes inside the wait) counts
 * them and not the bot's key, which carries every user of the bot. The hub answers 202 when it queued the poll, 429 with the seconds to wait
 * when the player was refreshed recently (or the person, or the whole site, pressed too often), 404 for a name it doesn't track and 409 while
 * its polling is off.
 */
public final class HubRefresh {
    /** How a refresh ended. {@code retryAfterSeconds} is set on {@link Outcome#WAIT}. */
    public record Result(Outcome outcome, long retryAfterSeconds, String code) {
        public boolean queued() {
            return outcome == Outcome.QUEUED;
        }
    }

    public enum Outcome {
        /** The hub queued an interactive poll; its result reaches the bot through the feed within a minute or so. */
        QUEUED,
        /** Too soon: the player, the person or the site was refreshed recently. */
        WAIT,
        /** The hub doesn't track that name. */
        UNKNOWN,
        /** The hub refused (its polling is off) or couldn't be reached. */
        UNAVAILABLE
    }

    private HubRefresh() {
    }

    /** Asks the hub to poll {@code rsn} now, on behalf of Discord user {@code discordId} (0 when nobody in particular pressed). */
    public static Result ask(RuneClansHubClient hub, String rsn, long discordId) {
        if (hub == null || !hub.enabled()) return new Result(Outcome.UNAVAILABLE, 0, "hub_off");
        DataObject body = DataObject.empty();
        if (discordId > 0) body.put("discordId", Long.toString(discordId));
        String path = "/v1/players/" + URLEncoder.encode(rsn.strip().replaceAll("\\s+", " ").replace(' ', '_'), StandardCharsets.UTF_8) + "/refresh";
        return of(hub.call("POST", path, body.toString()));
    }

    static Result of(RuneClansHubClient.Answer a) {
        if (a == null) return new Result(Outcome.UNAVAILABLE, 0, "unreachable");
        String code = a.json() == null ? null : a.json().getString("error", null);
        if (a.ok()) return new Result(Outcome.QUEUED, 0, null);
        if (a.status() == 429) {
            long seconds = a.json() != null && a.json().hasKey("retryAfterSeconds") && !a.json().isNull("retryAfterSeconds") ? a.json().getLong("retryAfterSeconds") : a.retryAfterSeconds() == null ? 60 : a.retryAfterSeconds();
            return new Result(Outcome.WAIT, Math.max(1, seconds), code);
        }
        if (a.status() == 404) return new Result(Outcome.UNKNOWN, 0, code);
        return new Result(Outcome.UNAVAILABLE, 0, code == null ? "status " + a.status() : code);
    }
}
