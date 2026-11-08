package com.runeclans.bot.clanprofile;

import com.runeclans.bot.clanprofile.ClanProfile.Visibility;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules for a clan profile, with no database or Discord in them so they can be tested: what text is acceptable, what makes a URL
 * safe to show, who counts as clan leadership, when a clan is verified, and whether a profile is listed publicly right now.
 */
public final class ProfileRules {
    public static final int MAX_NAME = 40;
    public static final int MAX_TAGLINE = 120;
    public static final int MAX_DESCRIPTION = 1500;
    public static final int MAX_REQUIREMENTS = 600;
    public static final int MAX_URL = 300;
    public static final int MIN_SLUG = 3;
    public static final int MAX_SLUG = 32;

    /**
     * Addresses no clan may take. A clan's address is also its website's subdomain ({@code <slug>.runeclans.com}, T-290), so this is the one
     * list for both: words that collide with the directory's own pages, and the platform's own subdomains (mail, status, the API and so on).
     */
    public static final Set<String> RESERVED_SLUGS = Set.of("admin", "api", "new", "search", "recruiting", "events", "leaderboards", "leaderboard", "report",
            "about", "www", "login", "logout", "dashboard", "settings", "docs", "faq", "install", "privacy", "terms", "clans", "directory", "jonnybot",
            // the bot's own face (bot.runeclans.com since the RuneClan Bot rebrand; jonnybot. redirects there)
            "bot",
            // the website's own pages under /clans/<slug> (the clan list's browse view)
            "browse",
            // the platform's subdomains (T-290)
            "link", "hub", "auth", "mail", "email", "smtp", "status", "cdn", "assets", "static", "img", "images", "media", "dev", "staging", "beta",
            "test", "preview", "app", "apps", "help", "support", "blog", "news", "shop", "store", "builder", "manage", "plugins", "account", "accounts",
            "signin", "signup", "register", "oauth", "sso", "webhook", "webhooks", "ws", "socket", "runeclans", "rsclans");

    /**
     * Parts of an address that would pass a clan off as Jagex, the platform or its staff, or that are offensive. Checked per dash-separated
     * word ({@link #BLOCKED_WORDS}) or anywhere in the address ({@link #BLOCKED_ANYWHERE}, for words that are never innocent inside another).
     */
    static final Set<String> BLOCKED_WORDS = Set.of("official", "staff", "mod", "mods", "moderator", "moderators", "admin", "admins", "jmod", "jmods", "support",
            "verified", "security", "nazi", "nazis", "rape", "rapist", "porn", "porno", "sex", "sexy", "anal", "nude", "nudes", "xxx", "hentai", "milf", "cock", "dick",
            "pussy", "slut", "whore", "fag", "fags", "kike", "spic", "chink", "coon", "tranny");
    static final List<String> BLOCKED_ANYWHERE = List.of("jagex", "jmod", "runeclans", "rsclans", "fuck", "shit", "cunt", "nigg", "faggot", "retard", "hitler",
            "kkk", "porn", "hentai", "nigger", "nigga");

    /** How long an address a clan gave up stays held for it (others can't take it; its old address forwards to the new one). */
    public static final java.time.Duration SLUG_GRACE = java.time.Duration.ofDays(30);

    /** Clan ranks that may speak for the clan and so may verify it. */
    private static final Set<String> LEADERSHIP_RANKS = Set.of("owner", "deputy owner");

    /** Whether a profile is shown in the directory ({@code LISTED}), reachable only by its link ({@code UNLISTED}) or not shown at all. */
    public enum Listing { HIDDEN, UNLISTED, LISTED }

    private ProfileRules() {}

    // ---------- slugs ----------

    /** "The Younglings!" becomes "the-younglings": lower case, letters and digits, single dashes, at most {@link #MAX_SLUG} characters. */
    public static String slugify(String text) {
        if (text == null) return "";
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG) slug = slug.substring(0, MAX_SLUG).replaceAll("-+$", "");
        return slug;
    }

    /** Why a slug can't be used, or null when it can. */
    public static String slugProblem(String slug) {
        return slugProblem(slug, true);
    }

    /** {@link #slugProblem(String)}; {@code filter} false skips the offensive/impersonation word check (the owner's override, D4), never the shape or reserved words. */
    public static String slugProblem(String slug, boolean filter) {
        if (slug == null || slug.length() < MIN_SLUG || slug.length() > MAX_SLUG) return "The address must be " + MIN_SLUG + " to " + MAX_SLUG + " characters.";
        if (!slug.matches("[a-z0-9]+(-[a-z0-9]+)*")) return "The address can only use lower-case letters, digits and single dashes.";
        if (RESERVED_SLUGS.contains(slug)) return "\"" + slug + "\" is reserved. Choose another address.";
        if (filter && blocked(slug)) return "That address can't be used: it could be mistaken for Jagex, the site or its staff, or it isn't suitable. Choose another.";
        return null;
    }

    /**
     * Innocent words that contain a never-innocent-looking part, so the "anywhere" check lets them through (the Scunthorpe problem). Compared
     * on the joined, normalised address with the allowed word cut out before the "anywhere" check.
     */
    static final List<String> ALLOWED_CONTAINING = List.of("scunthorpe", "cockburn", "dickens", "shitake", "shiitake", "mishit", "classhit");

    /** Letters people swap for look-alike digits and symbols ("fvck" and "sh1t" are the same word). */
    private static final java.util.Map<Character, Character> LEET = java.util.Map.of('0', 'o', '1', 'i', '3', 'e', '4', 'a', '5', 's', '7', 't', '8', 'b',
            '@', 'a', '$', 's', '!', 'i');

    /**
     * Whether an address holds a blocked word. It is read the way a person reads it: split on dashes and digits into words, and a word is blocked
     * only as a whole ("white-canal" is fine; "anal" would not be). The never-innocent parts are also looked for inside the joined address
     * ("whiteporn"), after look-alike letters ("sh1t", "n1gg") are undone, repeated letters are squeezed ("fuuuck") and a few innocent words that
     * merely contain one are cut out ("scunthorpe").
     */
    static boolean blocked(String slug) {
        String lower = slug.toLowerCase(Locale.ROOT);
        for (String variant : new String[]{lower, leet(lower)}) {
            for (String word : variant.split("[^a-z]+")) {
                if (word.isEmpty()) continue;
                if (BLOCKED_WORDS.contains(word) || BLOCKED_WORDS.contains(squeezeLong(word))) return true;
            }
            String joined = variant.replaceAll("[^a-z]", "");
            for (String ok : ALLOWED_CONTAINING) joined = joined.replace(ok, "");
            for (java.util.regex.Pattern part : ANYWHERE_PATTERNS) if (part.matcher(joined).find()) return true;
        }
        return false;
    }

    private static String leet(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) out.append(LEET.getOrDefault(c, c));
        return out.toString();
    }

    /** "seeex" becomes "sex": a run of three or more of the same letter is cut to one (two stay, since real words have them). */
    private static String squeezeLong(String text) {
        return text.replaceAll("(.)\\1{2,}", "$1");
    }

    /** Each never-innocent part allowing any letter to be repeated ("fuuuck"); built once. */
    private static final List<java.util.regex.Pattern> ANYWHERE_PATTERNS = BLOCKED_ANYWHERE.stream()
            .map(part -> java.util.regex.Pattern.compile(part.chars().mapToObj(c -> java.util.regex.Pattern.quote(String.valueOf((char) c)) + "+")
                    .collect(java.util.stream.Collectors.joining())))
            .toList();

    // ---------- fields ----------

    /** What is wrong with the text fields of a profile, as words for the person editing it (empty when it can be saved). */
    public static List<String> fieldProblems(String displayName, String tagline, String description, String requirements, String logoUrl, String applyUrl,
                                             Integer colour, String timezone) {
        List<String> problems = new ArrayList<>();
        if (displayName == null || displayName.strip().isEmpty()) problems.add("Give the clan a name to show.");
        else if (displayName.strip().length() > MAX_NAME) problems.add("The name can be at most " + MAX_NAME + " characters.");
        if (tagline != null && tagline.strip().length() > MAX_TAGLINE) problems.add("The tagline can be at most " + MAX_TAGLINE + " characters.");
        if (description != null && description.strip().length() > MAX_DESCRIPTION) problems.add("The description can be at most " + MAX_DESCRIPTION + " characters.");
        if (requirements != null && requirements.strip().length() > MAX_REQUIREMENTS) problems.add("The requirements can be at most " + MAX_REQUIREMENTS + " characters.");
        String logo = urlProblem(logoUrl);
        if (logo != null) problems.add("Logo: " + logo);
        String apply = urlProblem(applyUrl);
        if (apply != null) problems.add("Apply link: " + apply);
        if (colour != null && (colour < 0 || colour > 0xFFFFFF)) problems.add("That colour isn't valid.");
        if (timezone != null && !timezone.isBlank() && !validTimezone(timezone)) problems.add("That time zone isn't one the bot knows.");
        return problems;
    }

    /** Only plain https links are accepted, so a listing can't carry a script or an insecure resource. Null or blank means none. */
    static String urlProblem(String url) {
        if (url == null || url.isBlank()) return null;
        String u = url.strip();
        if (u.length() > MAX_URL) return "must be " + MAX_URL + " characters or fewer.";
        if (!u.regionMatches(true, 0, "https://", 0, 8)) return "must start with https://";
        if (u.matches(".*\\s.*")) return "can't contain spaces.";
        if (u.length() <= 8 || !u.substring(8).contains(".")) return "doesn't look like a web address.";
        return null;
    }

    static boolean validTimezone(String zone) {
        try {
            ZoneId.of(zone.strip());
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    // ---------- verification ----------

    /** Whether a rank in the clan roster may speak for the clan ("Owner" or "Deputy Owner"). */
    public static boolean isLeadership(String clanRank) {
        return clanRank != null && LEADERSHIP_RANKS.contains(clanRank.strip().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    /**
     * Why this person cannot verify the clan right now, in words, or null when they can.
     *
     * @param clanName       the server's configured RuneScape clan (null if none)
     * @param rosterSize     how many active members the last roster sync found (0 if the clan wasn't found)
     * @param claimedByOther another server has already verified this clan
     * @param linkedNames    how many RuneScape names the person has linked in this server
     * @param ranksOnRoster  the clan ranks of those linked names that are on the roster
     */
    public static String verificationProblem(String clanName, int rosterSize, boolean claimedByOther, int linkedNames, List<String> ranksOnRoster) {
        if (clanName == null || clanName.isBlank()) return "Set this server's RuneScape clan in Server setup first.";
        if (rosterSize == 0) return "I couldn't find any members for the clan \"" + clanName + "\". Check the name in Server setup, or sync the clan roster and try again.";
        if (claimedByOther) return "The clan \"" + clanName + "\" has already been verified by another server. If this is your clan, ask the bot's owner to look into it.";
        if (linkedNames == 0) return "Link your RuneScape name in this server first (with /rs), so the bot can see your rank in the clan.";
        if (ranksOnRoster.isEmpty()) return "None of the RuneScape names you linked here is on the \"" + clanName + "\" roster.";
        if (ranksOnRoster.stream().noneMatch(ProfileRules::isLeadership)) {
            return "Only a clan Owner or Deputy Owner can verify a clan. Your rank is " + String.join(" / ", ranksOnRoster) + ".";
        }
        return null;
    }

    /** A profile is verified while the clan it was verified for is still the server's configured clan. */
    public static boolean verified(ClanProfile profile, String currentClanName) {
        return profile.verifiedClanName() != null && currentClanName != null && profile.verifiedClanName().equalsIgnoreCase(currentClanName.strip());
    }

    // ---------- visibility ----------

    /**
     * Where a profile shows up right now. A suspended profile is hidden. Public needs a verified clan, so a clan that is renamed or
     * un-verified drops out of the directory instead of staying listed under a name nobody has checked.
     */
    public static Listing listing(ClanProfile profile, String currentClanName) {
        if (profile.suspended()) return Listing.HIDDEN;
        return switch (profile.visibility()) {
            case PRIVATE -> Listing.HIDDEN;
            case UNLISTED -> Listing.UNLISTED;
            case PUBLIC -> verified(profile, currentClanName) ? Listing.LISTED : Listing.HIDDEN;
        };
    }

    /** What stops this profile being set to {@code wanted}, or null when it can be. */
    public static String visibilityProblem(ClanProfile profile, String currentClanName, Visibility wanted) {
        if (wanted == Visibility.PRIVATE) return null;
        if (profile.suspended()) return "This listing has been suspended by the bot's owner" + (profile.suspendedReason() == null || profile.suspendedReason().isBlank() ? "." : ": " + profile.suspendedReason());
        if (wanted == Visibility.PUBLIC && !verified(profile, currentClanName)) return "Verify the clan first: a public listing needs a clan Owner or Deputy Owner to verify it.";
        return null;
    }
}
