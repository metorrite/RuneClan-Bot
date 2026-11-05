package com.runeclans.bot.internal;

import com.runeclans.bot.internal.CockpitRules.CapWeek;
import com.runeclans.bot.runescape.ClanPointsRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The rank-up advisor on the site admin's Clan health page (T-594), the pure part: the site owner's rules per clan rank, and who meets the
 * rules for their next rank. It only advises: Jagex gives no way to change an in-game rank, so staff rank the member in game and tick them off
 * here; a tick hides them until they meet the rules for the rank after.
 * <p>
 * The facts are the bot's own: the clan Hiscores roster (rank, and the XP total the bot saw when the member reached their current rank), the
 * days since they joined, the stored Citadel cap weeks (from members' Adventurer's Logs, like the Clan health caps list) and the clan points
 * ledger. Nothing here asks RuneMetrics.
 */
public final class RankAdvisorRules {
    private RankAdvisorRules() {}

    /** The in-game clan ranks, lowest to highest (the clan Hiscores always use Jagex's names). */
    public static final List<String> LADDER = ClanPointsRepository.STANDARD_RANK_NAMES;
    /** Ranks a rule may be set for: everything above Recruit, below Owner (nobody is promoted to Recruit, and the owner rank is handed over). */
    public static final List<String> TARGETS = LADDER.subList(1, LADDER.size() - 1);
    /** Citadel caps count over this many Citadel weeks, this week included. */
    public static final int CAP_WINDOW_WEEKS = 12;
    public static final long MAX_XP = 50_000_000_000L;
    public static final int MAX_DAYS = 3650;
    public static final long MAX_POINTS = 1_000_000_000L;
    /** Ticks show under "Ticked off recently" this long; kept for {@link #TICK_KEEP_DAYS} so the member stays hidden until the game shows the rank. */
    public static final int TICK_SHOW_DAYS = 14;
    public static final int TICK_KEEP_DAYS = 120;
    /**
     * The weekly congrats message (Drake's T-594 answer): the owner's text, at most this long, with {@code {list}} (one line per member
     * promoted, Discord name and RuneScape name) and {@code {count}} filled in. Text without {@code {list}} gets the list under it.
     */
    public static final int MAX_CONGRATS = 1000;
    public static final String DEFAULT_CONGRATS = "Congratulations to everyone promoted this week!\n{list}";
    /** A rank's staff check (what staff look at by hand, like a Combat Achievements tier): at most this long. */
    public static final int MAX_STAFF_CHECK = 120;
    /** Discord's limit for one message: a longer weekly post goes out as several. */
    public static final int DISCORD_MAX = 2000;
    /** A due member not promoted after this many weeks is dropped from the weekly update (they are listed again while still due). */
    public static final int DUE_KEEP_WEEKS = 12;
    /** The promotion week turns over this long after the Citadel reset, like the weekly digest, so the roster has seen the last ranks. */
    public static final java.time.Duration WEEK_TURN_DELAY = java.time.Duration.ofHours(12);

    /** Staff holds: Pending review (meets the numbers, needs a staff check) and Ignore indefinitely (an alt: never listed). */
    public static final String PENDING = "pending";
    public static final String IGNORED = "ignored";

    /**
     * One rank's rule; a null number isn't checked, and a rule with no number set lists nobody. {@code staffCheck}: what staff check by hand
     * before ranking someone up (a Combat Achievements tier, say), shown beside the rank's rows; null for none.
     */
    public record Rule(String rank, Long minXp, Integer minDays, Integer minCaps, Long minPoints, String staffCheck) {
        public Rule(String rank, Long minXp, Integer minDays, Integer minCaps, Long minPoints) {
            this(rank, minXp, minDays, minCaps, minPoints, null);
        }

        public boolean empty() {
            return minXp == null && minDays == null && minCaps == null && minPoints == null;
        }

        /** Nothing to save at all: no number and no staff check. */
        public boolean blank() {
            return empty() && (staffCheck == null || staffCheck.isBlank());
        }
    }

    /** A staff hold on a member: {@code kind} {@link #PENDING} or {@link #IGNORED}. */
    public record Hold(String rsnKey, String rsn, String kind, long by, String byName, OffsetDateTime at) {
        public boolean ignored() {
            return IGNORED.equals(kind);
        }
    }

    /**
     * One roster member's facts. {@code xpGained}: XP on the clan Hiscores since the bot saw them reach their current rank (null before it has
     * a starting point). {@code joined}: when they joined the clan (staff-set join date, else when the roster first showed them). {@code caps}:
     * capped weeks in the window, null when the bot has no Adventurer's Log entry of theirs in it (a private profile, most likely).
     */
    public record Facts(String rsn, String rank, Long xpGained, OffsetDateTime rankSince, OffsetDateTime joined, Integer caps, long points) {
        String key() {
            return CockpitRules.keyOf(rsn);
        }
    }

    /** A staff tick: {@code rsnKey} was ranked to {@code rank} in game (as staff say). */
    public record Tick(String rsnKey, String rank, long by, String byName, OffsetDateTime at) {}

    /**
     * One part of a rule: what it needs, what the member has (null: unknown), and whether it is met. {@code unavailable}: the bot can't count
     * this part for anyone right now (Citadel caps once RuneMetrics is off, T-725), so it is left out of the count instead of failing it.
     */
    public record Check(String what, long need, Long have, boolean met, boolean unavailable) {
        public Check(String what, long need, Long have, boolean met) {
            this(what, need, have, met, false);
        }

        /** Whether this part is counted at all (an unavailable part is skipped). */
        public boolean counts() {
            return !unavailable;
        }
    }

    /** A member who meets every part of the rule for {@code target}. */
    public record Ready(Facts member, String target, List<Check> checks) {}

    /** The ladder position of a rank name (any case), or -1 for a name that isn't an in-game rank. */
    public static int indexOf(String rank) {
        if (rank == null) return -1;
        String k = rank.strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < LADDER.size(); i++) if (LADDER.get(i).toLowerCase(Locale.ROOT).equals(k)) return i;
        return -1;
    }

    /** The rank name as the ladder spells it, or null. */
    public static String canonical(String rank) {
        int i = indexOf(rank);
        return i < 0 ? null : LADDER.get(i);
    }

    public static boolean isTarget(String rank) {
        String c = canonical(rank);
        return c != null && TARGETS.contains(c);
    }

    public static long daysSince(OffsetDateTime from, OffsetDateTime now) {
        return from == null ? 0 : Math.max(0, java.time.Duration.between(from, now).toDays());
    }

    /** Every part the rule sets, checked against the member, with Citadel caps counted. */
    public static List<Check> check(Rule rule, Facts m, OffsetDateTime now) {
        return check(rule, m, now, true);
    }

    /**
     * Every part the rule sets, checked against the member. {@code capsAvailable} false: a caps part
     * is marked unavailable and not counted, so ranks still work on XP, days and points. The owner's {@code minCaps} stays saved for when caps
     * return.
     */
    public static List<Check> check(Rule rule, Facts m, OffsetDateTime now, boolean capsAvailable) {
        List<Check> out = new ArrayList<>();
        if (rule.minXp() != null) out.add(new Check("xp", rule.minXp(), m.xpGained(), m.xpGained() != null && m.xpGained() >= rule.minXp()));
        if (rule.minDays() != null) {
            Long days = m.joined() == null ? null : daysSince(m.joined(), now);
            out.add(new Check("days", rule.minDays(), days, days != null && days >= rule.minDays()));
        }
        if (rule.minCaps() != null && !capsAvailable) out.add(new Check("caps", rule.minCaps(), null, false, true));
        else if (rule.minCaps() != null) {
            Long caps = m.caps() == null ? null : (long) m.caps();
            out.add(new Check("caps", rule.minCaps(), caps, caps != null && caps >= rule.minCaps()));
        }
        if (rule.minPoints() != null) out.add(new Check("points", rule.minPoints(), m.points(), m.points() >= rule.minPoints()));
        return out;
    }

    /**
     * Who meets the rules for their next rank. The next rank is the one above their in-game rank, or above the highest rank staff ticked them
     * off for that the game doesn't show yet (a tick hides them until they meet the rule for the rank after). A rank with no rule lists nobody.
     * Highest target first, then the longest waiting at their rank.
     */
    public static List<Ready> ready(List<Facts> members, Map<String, Rule> rules, List<Tick> ticks, OffsetDateTime now) {
        return ready(members, rules, ticks, now, true);
    }

    /**
     * As {@link #ready(List, Map, List, OffsetDateTime)}; with {@code capsAvailable} false a caps part isn't counted, and a rule whose only
     * part is caps lists nobody (it has nothing left to check, and listing the whole rank would be wrong).
     */
    public static List<Ready> ready(List<Facts> members, Map<String, Rule> rules, List<Tick> ticks, OffsetDateTime now, boolean capsAvailable) {
        return ready(members, rules, ticks, now, capsAvailable, Set.of());
    }

    /** As above, leaving out the members staff hold ({@code held}: their keys, Pending review or Ignored): they are listed apart. */
    public static List<Ready> ready(List<Facts> members, Map<String, Rule> rules, List<Tick> ticks, OffsetDateTime now, boolean capsAvailable,
                                    Set<String> held) {
        Map<String, Set<Integer>> ticked = tickedByMember(ticks);
        List<Ready> out = new ArrayList<>();
        for (Facts m : members) {
            if (held.contains(m.key())) continue;
            Ready next = next(m, rules, ticked, now, capsAvailable);
            if (next != null && next.checks().stream().filter(Check::counts).allMatch(Check::met)) out.add(next);
        }
        out.sort(Comparator.comparingInt((Ready r) -> indexOf(r.target())).reversed()
                .thenComparing(r -> r.member().rankSince() == null ? OffsetDateTime.MAX : r.member().rankSince())
                .thenComparing(r -> r.member().key()));
        return out;
    }

    private static Map<String, Set<Integer>> tickedByMember(List<Tick> ticks) {
        Map<String, Set<Integer>> ticked = new HashMap<>();
        for (Tick t : ticks) {
            int i = indexOf(t.rank());
            if (i >= 0) ticked.computeIfAbsent(t.rsnKey(), k -> new HashSet<>()).add(i);
        }
        return ticked;
    }

    /**
     * The member's next rank and its rule's parts checked (met or not), or null when there is nothing to check: no next rank a rule can be set
     * for, no rule for it, or no part of the rule that counts. The next rank is the one above their in-game rank, or above the highest rank
     * staff ticked them off for that the game doesn't show yet.
     */
    static Ready next(Facts m, Map<String, Rule> rules, Map<String, Set<Integer>> ticked, OffsetDateTime now, boolean capsAvailable) {
        int at = indexOf(m.rank());
        if (at < 0) return null;
        int target = at + 1;
        Set<Integer> mine = ticked.getOrDefault(m.key(), Set.of());
        while (mine.contains(target)) target++;
        if (target >= LADDER.size() || !TARGETS.contains(LADDER.get(target))) return null;
        Rule rule = rules.get(LADDER.get(target));
        if (rule == null || rule.empty()) return null;
        // Ticked off for a rank the game doesn't show yet: the XP counted so far was gained at the old rank, so it can't count toward
        // the rank after. XP at the new rank starts once the roster shows it (until then it is unknown, so an XP rule isn't met).
        Facts counted = target > at + 1 && m.xpGained() != null
                ? new Facts(m.rsn(), m.rank(), null, m.rankSince(), m.joined(), m.caps(), m.points()) : m;
        List<Check> checks = check(rule, counted, now, capsAvailable);
        if (checks.stream().noneMatch(Check::counts)) return null;
        return new Ready(m, LADDER.get(target), checks);
    }

    /** Someone staff put on Pending review: their next rank's checks ({@code next} null when no rule applies now) and whether they meet it. */
    public record Pending(Facts member, Hold hold, Ready next) {
        public boolean meets() {
            return next != null && next.checks().stream().filter(Check::counts).allMatch(Check::met);
        }
    }

    /**
     * The Pending review list (Drake's T-594 answer): members staff held for a check by hand, lowest current rank first (the order staff go
     * through it each week), then by name. Members who left the clan drop off.
     */
    public static List<Pending> pending(List<Facts> members, Map<String, Rule> rules, List<Tick> ticks, List<Hold> holds, OffsetDateTime now,
                                        boolean capsAvailable) {
        Map<String, Hold> byKey = new HashMap<>();
        for (Hold h : holds) if (PENDING.equals(h.kind())) byKey.put(h.rsnKey(), h);
        Map<String, Set<Integer>> ticked = tickedByMember(ticks);
        List<Pending> out = new ArrayList<>();
        for (Facts m : members) {
            Hold h = byKey.get(m.key());
            if (h != null) out.add(new Pending(m, h, next(m, rules, ticked, now, capsAvailable)));
        }
        out.sort(Comparator.comparingInt((Pending p) -> indexOf(p.member().rank())).thenComparing(p -> p.member().key()));
        return out;
    }

    /** Capped weeks per member key over the window (this Citadel week and the {@link #CAP_WINDOW_WEEKS} - 1 before it). */
    public static Map<String, Integer> capsByMember(List<CapWeek> grid, LocalDate thisWeekStart) {
        LocalDate from = thisWeekStart.minusWeeks(CAP_WINDOW_WEEKS - 1L);
        Map<String, Set<LocalDate>> weeks = new HashMap<>();
        for (CapWeek w : grid) {
            if (!w.capped() || w.weekStart().isBefore(from)) continue;
            weeks.computeIfAbsent(CockpitRules.keyOf(w.rsn()), k -> new HashSet<>()).add(w.weekStart());
        }
        Map<String, Integer> out = new HashMap<>();
        weeks.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    /** A member's caps in the window, or null when the bot has no Adventurer's Log entry of theirs since the window started. */
    public static Integer capsOf(String rsnKey, Map<String, Integer> capped, Map<String, OffsetDateTime> lastLog, LocalDate thisWeekStart) {
        OffsetDateTime windowStart = thisWeekStart.minusWeeks(CAP_WINDOW_WEEKS - 1L).atStartOfDay().atOffset(ZoneOffset.UTC);
        Integer caps = capped.get(rsnKey);
        if (caps != null) return caps;
        OffsetDateTime log = lastLog.get(rsnKey);
        return log == null || log.isBefore(windowStart) ? null : 0;
    }

    // ---------- the owner's edits ----------

    /** The congrats text as saved: trimmed, no control characters but new lines, at most {@link #MAX_CONGRATS}; blank is the default. */
    public static String cleanCongrats(String raw) {
        String s = raw == null ? "" : raw.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\n]]", "").strip();
        if (s.isEmpty()) return DEFAULT_CONGRATS;
        return s.length() > MAX_CONGRATS ? s.substring(0, MAX_CONGRATS) : s;
    }

    /** A rank's staff check as saved: one line, trimmed, at most {@link #MAX_STAFF_CHECK}; blank is none (null). */
    public static String cleanStaffCheck(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (s.isEmpty()) return null;
        return s.length() > MAX_STAFF_CHECK ? s.substring(0, MAX_STAFF_CHECK).strip() : s;
    }

    // ---------- the weekly update ----------

    /**
     * The promotion week {@code now} is in: the Citadel week (Wednesday reset), turned over {@link #WEEK_TURN_DELAY} after the reset so
     * the roster checks have seen the ranks given on the last evening.
     */
    public static LocalDate weekOf(OffsetDateTime now) {
        return RecapPeriod.citadelWeekStart(now.minus(WEEK_TURN_DELAY).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate());
    }

    /** Someone who was due for {@code target} (listed on the ready or pending list, or ticked) since {@code since}. */
    public record Due(String rsnKey, String rsn, String rank, String target, LocalDate since) {}

    /** Someone the weekly update congratulates: due for a rank and now at it in game (or ticked off for it). */
    public record Promoted(String rsnKey, String rsn, String fromRank, String toRank, String discordName) {}

    /** What the week's turn decides: who got their rank, and which due entries to drop (promoted, gone, ignored or too old). */
    public record Turn(List<Promoted> promoted, List<Due> drop) {}

    /**
     * Who was due before {@code week} and got the rank since: their in-game rank reached the target, or staff ticked them off for it. Members
     * who are ignored, or were due for more than {@link #DUE_KEEP_WEEKS} weeks, are dropped without a congrats. Someone missing from the roster
     * stays due (a roster sync outage must not wipe the list; once they have really left they age out). {@code discordNames}: the linked
     * Discord name per member key, when known (none: filled in when the message is shown or posted).
     */
    public static Turn turn(LocalDate week, List<Due> due, List<Facts> members, List<Tick> ticks, Set<String> ignored, Map<String, String> discordNames) {
        Map<String, Facts> byKey = new HashMap<>();
        for (Facts f : members) byKey.put(f.key(), f);
        Map<String, Set<Integer>> ticked = tickedByMember(ticks);
        Map<String, Promoted> promoted = new java.util.LinkedHashMap<>();
        List<Due> drop = new ArrayList<>();
        LocalDate oldest = week.minusWeeks(DUE_KEEP_WEEKS);
        for (Due d : due) {
            if (!d.since().isBefore(week)) continue;
            Facts f = byKey.get(d.rsnKey());
            int target = indexOf(d.target());
            if (target < 0 || ignored.contains(d.rsnKey())) {
                drop.add(d);
                continue;
            }
            if (f == null) {
                if (d.since().isBefore(oldest)) drop.add(d);
                continue;
            }
            int now = indexOf(f.rank());
            boolean got = now >= target || ticked.getOrDefault(d.rsnKey(), Set.of()).contains(target);
            if (got) {
                drop.add(d);
                String to = now >= target ? f.rank() : d.target();
                Promoted before = promoted.get(d.rsnKey());
                // due for two ranks in a row (ticked, then due again): one line, at the highest rank
                if (before == null || indexOf(to) > indexOf(before.toRank())) {
                    String from = before != null ? before.fromRank() : d.rank();
                    promoted.put(d.rsnKey(), new Promoted(d.rsnKey(), f.rsn(), from, to, discordNames.get(d.rsnKey())));
                }
            } else if (d.since().isBefore(oldest)) {
                drop.add(d);
            }
        }
        List<Promoted> list = new ArrayList<>(promoted.values());
        list.sort(Comparator.comparingInt((Promoted p) -> indexOf(p.toRank())).reversed().thenComparing(Promoted::rsnKey));
        return new Turn(list, drop);
    }

    /** One member's line: {@code • @Discord name (RuneScape name): Rank}, or without the Discord part when they aren't linked. */
    public static String congratsLine(Promoted p) {
        String rsn = net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(p.rsn());
        String who = p.discordName() == null || p.discordName().isBlank() ? rsn
                : "@" + net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(p.discordName().strip()) + " (" + rsn + ")";
        return "• " + noMassPings(who) + ": " + p.toRank();
    }

    /** {@code @everyone} / {@code @here} in a member's name broken with a zero-width space, so the copied text never pings a whole server. */
    static String noMassPings(String text) {
        return text.replaceAll("(?i)@(everyone|here)", "@\u200b$1");
    }

    /** The week's list with missing Discord names filled in from {@code names} (the linked members' names now), when known. */
    public static List<Promoted> withNames(List<Promoted> promoted, Map<String, String> names) {
        List<Promoted> out = new ArrayList<>();
        for (Promoted p : promoted) {
            String name = p.discordName() == null || p.discordName().isBlank() ? names.get(p.rsnKey()) : p.discordName();
            out.add(new Promoted(p.rsnKey(), p.rsn(), p.fromRank(), p.toRank(), name));
        }
        return out;
    }

    /** The weekly message: the owner's text with {@code {list}} and {@code {count}} filled in; empty when nobody was promoted. */
    public static String weeklyText(String template, List<Promoted> promoted) {
        if (promoted.isEmpty()) return "";
        String t = template == null || template.isBlank() ? DEFAULT_CONGRATS : template;
        String list = String.join("\n", promoted.stream().map(RankAdvisorRules::congratsLine).toList());
        String count = Integer.toString(promoted.size());
        String out = t.contains("{list}") ? t.replace("{list}", list) : t + "\n" + list;
        return out.replace("{count}", count);
    }

    /** The message cut into Discord-sized posts, at line ends where it can be (a single over-long line is cut where it must). */
    public static List<String> chunks(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            while (line.length() > max) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(line.substring(0, max));
                line = line.substring(max);
            }
            if (cur.length() + line.length() + (cur.isEmpty() ? 0 : 1) > max) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (!cur.isEmpty()) cur.append('\n');
            cur.append(line);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString());
        return out;
    }

    /** A rule part's value from the website: null (not checked) or a whole number in range; anything else is a problem in words. */
    static Long number(Object raw, long max, String what, List<String> problems) {
        if (raw == null) return null;
        if (raw instanceof Number n && (n instanceof Integer || n instanceof Long || n instanceof Short) && n.longValue() >= 0 && n.longValue() <= max) {
            return n.longValue();
        }
        problems.add(what + " must be a whole number from 0 to " + String.format(Locale.ROOT, "%,d", max) + ".");
        return null;
    }
}
