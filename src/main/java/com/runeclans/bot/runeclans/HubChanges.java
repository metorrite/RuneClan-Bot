package com.runeclans.bot.runeclans;

import net.dv8tion.jda.api.utils.data.DataObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * What a hub change means for the bot's {@link HubCache} (runeclans-hub PLATFORM-DESIGN 2.4, 9.2). A change arrives as a feed entry or a
 * webhook, both {@code {"kind", "data"}}; changes made through the hub's one write path carry {@code data.item} and {@code data.id}, and the
 * cache drops every answer tagged {@code <item>:<id>}. {@code access.changed} drops every {@code access} answer (gates are few and change
 * rarely), and any {@code site.*} or {@code community.*} change every {@code sites} answer (a server's site and its role mappings, T-505; a
 * community's mapped Discord role is one of them, T-732), and so does {@code feature.changed} (the request handler roles of
 * {@code requests.discord} join a server's role mappings, T-734). Webhooks are checked with {@link #signatureValid} before they are trusted.
 */
final class HubChanges {
    /** A webhook signed more than this long ago (or ahead) is refused, so a captured one can't be replayed later. */
    static final long MAX_SIGNATURE_AGE_SECONDS = 300;

    private HubChanges() {}

    /** The cache tag for one hub item, as answers are tagged when cached. */
    static String tag(String item, String id) {
        return item + ":" + id;
    }

    /** Empties what this change makes out of date. Returns how many cached answers were dropped. */
    static int evict(HubCache cache, String kind, DataObject data) {
        if (kind == null) return 0;
        int dropped = 0;
        if (kind.equals("access.changed") || kind.startsWith("access.")) dropped += cache.evictTag("access");
        if (kind.startsWith("site.") || kind.startsWith("community.") || kind.equals("feature.changed")) dropped += cache.evictTag("sites");
        // a league, its terms, a round or a result moved (T-759): the server's league answers are read again
        if (kind.startsWith("league.")) dropped += cache.evictTag("leagues");
        // a poll found the player's profile or a new log line (T-851): the player's cached stats answers are read again
        if ((kind.equals("stats.updated") || kind.equals("player.activity")) && data != null && data.hasKey("rsn") && !data.isNull("rsn")) {
            dropped += cache.evictTag(com.runeclans.bot.runescape.stats.HubStatsBackend.playerTag(data.getString("rsn")));
        }
        if (data != null && data.hasKey("item") && data.hasKey("id") && !data.isNull("item") && !data.isNull("id")) {
            dropped += cache.evictTag(tag(data.getString("item"), data.getString("id")));
        }
        return dropped;
    }

    /**
     * True when {@code header} ({@code X-Hub-Signature: t=<unix>,v1=<hex>}) is the hub's HMAC-SHA256 of {@code "t.body"} under the webhook's
     * secret, signed within {@link #MAX_SIGNATURE_AGE_SECONDS} of {@code nowSeconds}.
     */
    static boolean signatureValid(String secret, String header, String body, long nowSeconds) {
        if (secret == null || secret.isEmpty() || header == null || body == null) return false;
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String p = part.strip();
            if (p.startsWith("t=")) {
                try {
                    t = Long.parseLong(p.substring(2));
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (p.startsWith("v1=")) {
                v1 = p.substring(3);
            }
        }
        if (t == null || v1 == null || Math.abs(nowSeconds - t) > MAX_SIGNATURE_AGE_SECONDS) return false;
        String expected = sign(secret, t + "." + body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), v1.getBytes(StandardCharsets.US_ASCII));
    }

    static String sign(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }
}
