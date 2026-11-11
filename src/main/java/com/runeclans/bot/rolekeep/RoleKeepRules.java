package com.runeclans.bot.rolekeep;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The decisions of role persist, temp roles and sticky roles that need neither Discord nor a database, so they can be tested on their own. */
public final class RoleKeepRules {
    public static final Duration MIN_TEMP = Duration.ofMinutes(1);
    public static final Duration MAX_TEMP = Duration.ofDays(365);
    public static final int MAX_STICKY_ROLES = 25;
    public static final int MAX_PERSISTED_PER_MEMBER = 25;

    private static final Pattern PART = Pattern.compile("(\\d{1,4})\\s*([smhdw])", Pattern.CASE_INSENSITIVE);

    private RoleKeepRules() {}

    /** Where a remembered role came from. */
    public enum Source { STICKY, MANUAL }

    /** A length of time such as {@code 30m}, {@code 2h}, {@code 7d}, {@code 1w} or {@code 1d12h}. Empty if it is not one or is out of range. */
    public static Optional<Duration> parseDuration(String text) {
        if (text == null) return Optional.empty();
        String rest = text.strip().toLowerCase();
        if (rest.isEmpty()) return Optional.empty();
        Matcher matcher = PART.matcher(rest);
        Duration total = Duration.ZERO;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) return Optional.empty();
            end = matcher.end();
            long n = Long.parseLong(matcher.group(1));
            total = total.plus(switch (matcher.group(2).charAt(0)) {
                case 's' -> Duration.ofSeconds(n);
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                case 'd' -> Duration.ofDays(n);
                default -> Duration.ofDays(7 * n);
            });
        }
        if (end != rest.length() || total.compareTo(MIN_TEMP) < 0 || total.compareTo(MAX_TEMP) > 0) return Optional.empty();
        return Optional.of(total);
    }

    /**
     * The roles to remember when a member leaves: the ones they hold that are on the server's sticky list, and that are still safe to give back.
     * A role the filter refuses (the clan sync's, a staff role, one the bot cannot hand out) is never remembered, whatever the list says.
     */
    public static List<Long> rolesToStore(Set<Long> held, Set<Long> stickyList, LongPredicate mayRestore) {
        List<Long> store = new ArrayList<>();
        for (long role : held) if (stickyList.contains(role) && mayRestore.test(role)) store.add(role);
        return store;
    }

    /**
     * The roles to give back when a member returns: everything remembered or still timed that they do not hold yet, in a stable order, filtered by
     * the same safety check again (the roles may have changed since they were stored). Running it twice for the same member gives nothing the second time.
     */
    public static List<Long> toReapply(Set<Long> remembered, Set<Long> activeTemp, Set<Long> held, LongPredicate mayRestore) {
        Set<Long> wanted = new LinkedHashSet<>(remembered);
        wanted.addAll(activeTemp);
        List<Long> give = new ArrayList<>();
        for (long role : wanted) if (!held.contains(role) && mayRestore.test(role)) give.add(role);
        return give;
    }

    /** Why a role cannot be kept, timed or made sticky, or null. Built from facts looked up elsewhere so it stays pure. */
    public static String roleRefusal(boolean exists, boolean everyone, boolean syncOwned, boolean staffRole, boolean safeToAssign, String roleName) {
        if (!exists) return "That role does not exist in this server.";
        if (everyone) return "@everyone cannot be kept or timed.";
        if (syncOwned) return "@" + roleName + " is kept by the clan role sync (Member / Guest / Unverified), which always wins, so it cannot be kept or timed here.";
        if (staffRole) return "@" + roleName + " is one of this server's staff permission roles, which are never given back or timed automatically.";
        if (!safeToAssign) return "The bot will not hand out @" + roleName + ": it is above the bot's own role, managed by an integration, carries powerful permissions, or would make its holder bot staff.";
        return null;
    }
}
