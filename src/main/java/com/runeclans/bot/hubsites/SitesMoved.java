package com.runeclans.bot.hubsites;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.Locale;

/**
 * Which of the bot's dashboard routes ({@code /internal/admin/...}) are site writes that move to the hub (T-506, D-19). With
 * {@code SITES_BACKEND=hub} they answer {@code 409 {"moved": "hub"}} and change nothing, so no write lands on the old side after the move
 * (one writer at a time, PLATFORM-DESIGN 6). Reads keep working through the 30 read-only days.
 * <p>
 * Moved: the site builder ({@code clan-site/...}: the site, layout, versions, publishing, roles, address, take-down), the clan profile,
 * the recruiting post, alliances, site pictures ({@code site-pictures}, {@code images/site/...}) and the owner's site tools
 * ({@code owner-sites/...}, also under {@code overall/}). Not moved: {@code clan-site/connections} (the Discord channel picks stay bot
 * mappings, {@link SiteChannelMappings}), {@code clan-site/clan-name} (the server's clan name, a bot setting), {@code clan-site/member-options}
 * (whether members may change their nickname and name colour through the bot: a Discord setting only the bot can act on, T-976) and
 * {@code clan-profile/verify} (verification is never edited on the hub's profile routes).
 */
public final class SitesMoved {
    private static final String ADMIN = "/internal/admin/";

    private SitesMoved() {}

    /** True for a write ({@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}) to a moved route; {@code path} is the full request path. */
    public static boolean movedWrite(String method, String path) {
        if (method == null || path == null || !path.startsWith(ADMIN)) return false;
        String m = method.toUpperCase(Locale.ROOT);
        if (m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS")) return false;
        String route = path.substring(ADMIN.length());
        if (route.startsWith("overall/")) route = route.substring("overall/".length());
        while (route.endsWith("/")) route = route.substring(0, route.length() - 1);
        String[] parts = route.isEmpty() ? new String[0] : route.split("/");
        if (parts.length == 0) return false;
        return switch (parts[0]) {
            case "clan-site" -> parts.length < 2 || !(parts[1].equals("connections") || parts[1].equals("clan-name") || parts[1].equals("member-options"));
            case "clan-profile" -> parts.length == 1;
            case "clan-recruiting", "alliances", "site-pictures", "owner-sites" -> true;
            case "images" -> parts.length >= 2 && parts[1].equals("site");
            default -> false;
        };
    }

    /** The answer a moved write gets (409): {@code moved} for the website, and words for anyone reading it. */
    public static DataObject answer() {
        return DataObject.empty().put("moved", "hub").put("error", "This site is managed on RuneClans now. Make the change there.");
    }
}
