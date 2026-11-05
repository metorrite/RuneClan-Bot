package com.runeclans.bot.runeclans;

import java.net.URI;
import java.util.Locale;
import java.util.function.Function;

/**
 * Whether JonnyBot reads and writes plugin data through the RuneClans hub (runeclans-hub docs/HUB-DESIGN.md section 7). Off unless
 * {@code RUNECLANS_HUB_ENABLED=true} and both {@code HUB_API_URL} and {@code RUNECLANS_HUB_API_KEY} are set; off, the bot's own RuneLink
 * and plugin ingest code does everything exactly as before. The key is never logged ({@link #toString()} hides it).
 *
 * @param enabled whether the hub is used
 * @param baseUrl the hub's address without a trailing slash, e.g. {@code https://api.runeclans.com}, or inside the Railway project
 *                {@code http://<service>.railway.internal:8090}
 * @param apiKey  JonnyBot's first-party API key ({@code rsh_...}) with {@code link:resolve}, {@code players:read}, {@code feed:read},
 *                {@code guilds:write}
 * @param problem why the hub is off although {@code RUNECLANS_HUB_ENABLED} is true (names variables, never values), or null
 */
public record RuneClansSettings(boolean enabled, String baseUrl, String apiKey, String problem) {
    public static final RuneClansSettings OFF = new RuneClansSettings(false, null, null, null);

    public static RuneClansSettings from(Function<String, String> env) {
        if (!"true".equalsIgnoreCase(trimmed(env.apply("RUNECLANS_HUB_ENABLED")))) return OFF;
        String url = trimmed(env.apply("HUB_API_URL"));
        String key = trimmed(env.apply("RUNECLANS_HUB_API_KEY"));
        StringBuilder problems = new StringBuilder();
        if (url == null || !allowedBaseUrl(url)) {
            problems.append(" HUB_API_URL must be an https address (plain http only for a private host: localhost, *.railway.internal or a private IP).");
        }
        if (key == null || !key.matches("rsh_[A-Za-z0-9_-]{43}")) problems.append(" RUNECLANS_HUB_API_KEY must be a hub API key (rsh_...).");
        if (!problems.isEmpty()) return new RuneClansSettings(false, null, null, "RUNECLANS_HUB_ENABLED is true but:" + problems);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return new RuneClansSettings(true, url, key, null);
    }

    /**
     * https anywhere; plain http only to a host that can't be on the public internet (Railway's private network, localhost, a private
     * IPv4 range), so the bot can reach the hub inside the Railway project without TLS but never sends its key in clear over the internet.
     * Decided from the URL alone, without DNS.
     */
    static boolean allowedBaseUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null) return false;
        if ("https".equalsIgnoreCase(uri.getScheme())) return true;
        return "http".equalsIgnoreCase(uri.getScheme()) && privateHost(host);
    }

    static boolean privateHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.equals("localhost") || host.equals("::1") || host.endsWith(".railway.internal")) return true;
        String[] p = host.split("\\.");
        if (p.length != 4) return false;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!p[i].matches("\\d{1,3}")) return false;
            o[i] = Integer.parseInt(p[i]);
            if (o[i] > 255) return false;
        }
        return o[0] == 127 || o[0] == 10 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31) || (o[0] == 192 && o[1] == 168);
    }

    private static String trimmed(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    @Override
    public String toString() {
        return "RuneClansSettings[enabled=" + enabled + ", baseUrl=" + baseUrl + ", key=hidden]";
    }
}
