package com.runeclans.bot.testsupport;

import com.runeclans.bot.internal.SiteStatsRepository;
import com.runeclans.bot.runescape.PlayerActivity;
import com.runeclans.bot.runescape.PlayerLinkRepository.SkillHistoryPoint;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.runeclans.bot.runescape.RuneMetricsDates;
import com.runeclans.bot.runescape.SkillValue;
import com.runeclans.bot.runescape.SkillXpPoint;
import com.runeclans.bot.runescape.WeeklyDigestRepository.CitadelActivityRow;
import com.runeclans.bot.runescape.stats.StatsBackend;
import com.runeclans.bot.runescape.stats.StatsBackends;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * An in-memory stand-in for the hub's stats, for database tests of the bot's own tables (T-912): the bot no longer stores polled stats or
 * adventure-log lines, so a test that needs them says what the hub holds here. Clan reads cover the names {@link #member registered} for a
 * server, the way the hub answers for the clan's active roster. Simple on purpose: enough for the tenancy and page tests, not a hub.
 */
public final class MemoryStats implements StatsBackend {
    private record Snapshot(long id, String rsn, OffsetDateTime at, int totalLevel, long totalXp, int combatLevel, List<SkillValue> skills) {
        StatsSnapshotRow row() {
            return new StatsSnapshotRow(id, at, totalLevel, totalXp, combatLevel, 0, 0, 0);
        }
    }

    private record Line(String rsn, PlayerActivity activity, OffsetDateTime at) {}

    private final Map<Long, Map<String, String>> rosters = new HashMap<>();
    private final Map<String, List<Snapshot>> snapshots = new HashMap<>();
    private final Map<String, List<Line>> lines = new HashMap<>();
    private long nextId = 1;

    /** This backend as the bot's stats source. */
    public StatsBackends backends() {
        return StatsBackends.of(this);
    }

    private static String key(String rsn) {
        return rsn.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** {@code rsn} is on server {@code guildId}'s clan roster. */
    public synchronized MemoryStats member(long guildId, String rsn) {
        rosters.computeIfAbsent(guildId, g -> new LinkedHashMap<>()).put(key(rsn), rsn);
        return this;
    }

    /** One poll of {@code rsn} at {@code at}. */
    public synchronized MemoryStats snapshot(String rsn, OffsetDateTime at, int totalLevel, long totalXp, int combatLevel, List<SkillValue> skills) {
        snapshots.computeIfAbsent(key(rsn), k -> new ArrayList<>()).add(new Snapshot(nextId++, rsn, at, totalLevel, totalXp, combatLevel, List.copyOf(skills)));
        snapshots.get(key(rsn)).sort(Comparator.comparing(Snapshot::at));
        return this;
    }

    /** Adventure-log lines of {@code rsn}, timed by their own date. */
    public synchronized MemoryStats activities(String rsn, List<PlayerActivity> activities) {
        for (PlayerActivity a : activities) {
            var at = RuneMetricsDates.toInstant(a.date());
            lines.computeIfAbsent(key(rsn), k -> new ArrayList<>()).add(new Line(rsn, a, at == null ? OffsetDateTime.now(ZoneOffset.UTC) : at.atOffset(ZoneOffset.UTC)));
        }
        return this;
    }

    public synchronized void clear() {
        rosters.clear();
        snapshots.clear();
        lines.clear();
    }

    private Map<String, String> roster(long guildId) {
        return rosters.getOrDefault(guildId, Map.of());
    }

    private List<Snapshot> snaps(String rsn) {
        return snapshots.getOrDefault(key(rsn), List.of());
    }

    private List<Line> linesOf(String rsn) {
        return lines.getOrDefault(key(rsn), List.of());
    }

    private Snapshot atOrBefore(String rsn, OffsetDateTime t) {
        Snapshot best = null;
        for (Snapshot s : snaps(rsn)) if (!s.at().isAfter(t)) best = s;
        return best;
    }

    private Snapshot firstAfter(String rsn, OffsetDateTime t) {
        for (Snapshot s : snaps(rsn)) if (s.at().isAfter(t)) return s;
        return null;
    }

    private List<String> names(long guildId, String rsn) {
        if (rsn == null) return new ArrayList<>(roster(guildId).values());
        return roster(guildId).containsKey(key(rsn)) ? List.of(rsn) : List.of();
    }

    private static SiteStatsRepository.ActivityRow row(Line l) {
        return new SiteStatsRepository.ActivityRow(l.rsn(), l.activity().text(), l.activity().details(), l.activity().date(), l.at(), l.activity().type());
    }

    private List<Line> clanLines(long guildId) {
        List<Line> out = new ArrayList<>();
        for (String k : roster(guildId).keySet()) out.addAll(lines.getOrDefault(k, List.of()));
        out.sort(Comparator.comparing(Line::at).reversed());
        return out;
    }

    private static boolean capped(Line l) {
        return l.activity().text().startsWith("Capped at my Clan Citadel");
    }

    private static LocalDate citadelWeek(OffsetDateTime at) {
        return at.minusDays(2).minusMinutes(10).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ---------------------------------------------------------------- one player

    @Override public synchronized StatsSnapshotRow latest(String rsn) {
        var s = snaps(rsn);
        return s.isEmpty() ? null : s.getLast().row();
    }

    @Override public synchronized Map<String, StatsSnapshotRow> latestMany(Collection<String> rsns) {
        Map<String, StatsSnapshotRow> out = new HashMap<>();
        for (String r : rsns) {
            var l = latest(r);
            if (l != null) out.put(key(r), l);
        }
        return out;
    }

    @Override public synchronized List<SkillValue> skillsForSnapshot(long snapshotId) {
        for (var list : snapshots.values()) for (Snapshot s : list) if (s.id() == snapshotId) return s.skills();
        return List.of();
    }

    @Override public synchronized List<StatsSnapshotRow> history(String rsn, int limit) {
        return snaps(rsn).reversed().stream().limit(limit).map(Snapshot::row).toList();
    }

    @Override public synchronized List<StatsSnapshotRow> snapshotsSince(String rsn, OffsetDateTime since) {
        return snaps(rsn).stream().filter(s -> !s.at().isBefore(since)).map(Snapshot::row).toList();
    }

    @Override public synchronized List<SkillXpPoint> skillXpHistory(String rsn, int skillId, OffsetDateTime since) {
        List<SkillXpPoint> out = new ArrayList<>();
        for (Snapshot s : snaps(rsn)) if (!s.at().isBefore(since)) for (SkillValue v : s.skills()) if (v.skillId() == skillId) out.add(new SkillXpPoint(s.at(), v.xp()));
        return out;
    }

    @Override public synchronized List<SkillHistoryPoint> allSkillsXpHistorySince(String rsn, OffsetDateTime since) {
        List<SkillHistoryPoint> out = new ArrayList<>();
        for (Snapshot s : snaps(rsn)) if (!s.at().isBefore(since)) for (SkillValue v : s.skills()) out.add(new SkillHistoryPoint(v.skillId(), s.at(), v.xp()));
        out.sort(Comparator.comparingInt(SkillHistoryPoint::skillId).thenComparing(SkillHistoryPoint::timestamp));
        return out;
    }

    @Override public synchronized List<PlayerActivity> recentActivities(String rsn, int limit) {
        return linesOf(rsn).reversed().stream().limit(limit).map(Line::activity).toList();
    }

    @Override public synchronized List<PlayerActivity> activitiesSince(String rsn, OffsetDateTime since) {
        return linesOf(rsn).stream().filter(l -> !l.at().isBefore(since)).map(Line::activity).toList();
    }

    @Override public synchronized List<String> recentActivityTexts(Collection<String> rsns, int limit) {
        List<Line> all = new ArrayList<>();
        for (String r : rsns) all.addAll(linesOf(r));
        all.sort(Comparator.comparing(Line::at).reversed());
        return all.stream().limit(limit).map(l -> l.activity().text()).toList();
    }

    // ---------------------------------------------------------------- a server's clan

    @Override public synchronized Map<String, StatsSnapshotRow> membersLatest(long guildId) {
        return latestMany(roster(guildId).values());
    }

    @Override public synchronized List<SiteStatsRepository.Gain> xpGainsBetween(long guildId, OffsetDateTime from, OffsetDateTime to) {
        List<SiteStatsRepository.Gain> out = new ArrayList<>();
        for (String rsn : roster(guildId).values()) {
            Snapshot end = atOrBefore(rsn, to);
            Snapshot start = atOrBefore(rsn, from);
            if (start == null) start = firstAfter(rsn, from);
            if (end != null && start != null) out.add(new SiteStatsRepository.Gain(rsn, end.totalXp() - start.totalXp()));
        }
        return out;
    }

    @Override public synchronized OffsetDateTime firstSnapshotAt(long guildId) {
        return roster(guildId).values().stream().flatMap(r -> snaps(r).stream()).map(Snapshot::at).min(Comparator.naturalOrder()).orElse(null);
    }

    @Override public synchronized List<SiteStatsRepository.Capper> cappersBetween(long guildId, OffsetDateTime from, OffsetDateTime to, int limit) {
        Map<String, List<Line>> caps = new TreeMap<>();
        for (Line l : clanLines(guildId)) if (capped(l) && !l.at().isBefore(from) && l.at().isBefore(to)) caps.computeIfAbsent(l.rsn(), k -> new ArrayList<>()).add(l);
        return caps.entrySet().stream().map(e -> new SiteStatsRepository.Capper(e.getKey(), (int) e.getValue().stream().map(l -> citadelWeek(l.at())).distinct().count(), e.getValue().size()))
                .sorted(Comparator.comparingInt(SiteStatsRepository.Capper::weeksCapped).reversed()).limit(limit).toList();
    }

    @Override public synchronized List<SiteStatsRepository.SkillGain> skillGainsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        Map<Integer, Long> gains = new TreeMap<>();
        for (String r : names(guildId, rsn)) {
            Snapshot end = atOrBefore(r, to);
            Snapshot start = atOrBefore(r, from);
            if (start == null) start = firstAfter(r, from);
            if (end == null || start == null) continue;
            Map<Integer, Long> before = new HashMap<>();
            for (SkillValue v : start.skills()) before.put(v.skillId(), v.xp());
            for (SkillValue v : end.skills()) {
                long g = v.xp() - before.getOrDefault(v.skillId(), v.xp());
                if (g > 0) gains.merge(v.skillId(), g, Long::sum);
            }
        }
        return gains.entrySet().stream().map(e -> new SiteStatsRepository.SkillGain(e.getKey(), e.getValue())).toList();
    }

    @Override public synchronized List<SiteStatsRepository.DayXp> dailyXpBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        Map<LocalDate, Long> days = new TreeMap<>();
        for (String r : names(guildId, rsn)) {
            Snapshot previous = atOrBefore(r, from);
            for (Snapshot s : snaps(r)) {
                if (!s.at().isAfter(from) || s.at().isAfter(to)) continue;
                if (previous != null && s.totalXp() > previous.totalXp()) days.merge(s.at().toLocalDate(), s.totalXp() - previous.totalXp(), Long::sum);
                previous = s;
            }
        }
        return days.entrySet().stream().map(e -> new SiteStatsRepository.DayXp(e.getKey(), e.getValue())).toList();
    }

    @Override public synchronized List<SiteStatsRepository.ActivityRow> activitiesBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        List<String> who = names(guildId, rsn).stream().map(MemoryStats::key).toList();
        return clanLines(guildId).stream().filter(l -> who.contains(key(l.rsn())) && !l.at().isBefore(from) && l.at().isBefore(to)).map(MemoryStats::row).toList();
    }

    @Override public synchronized Integer[] totalLevelsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        Snapshot end = atOrBefore(rsn, to);
        Snapshot start = atOrBefore(rsn, from);
        if (start == null) start = firstAfter(rsn, from);
        return new Integer[]{start == null ? null : start.totalLevel(), end == null ? null : end.totalLevel()};
    }

    @Override public synchronized List<SiteStatsRepository.ActivityRow> recentActivities(long guildId, int limit) {
        return clanLines(guildId).stream().limit(limit).map(MemoryStats::row).toList();
    }

    @Override public synchronized List<SiteStatsRepository.ActivityRow> activitiesStartingWith(long guildId, String prefix) {
        return clanLines(guildId).stream().filter(l -> l.activity().text().startsWith(prefix)).map(MemoryStats::row).toList();
    }

    @Override public synchronized List<SiteStatsRepository.BigDay> biggestDays(long guildId, int limit) {
        List<SiteStatsRepository.BigDay> out = new ArrayList<>();
        for (String r : roster(guildId).values()) {
            Snapshot previous = null;
            for (Snapshot s : snaps(r)) {
                if (previous != null && s.totalXp() > previous.totalXp()) out.add(new SiteStatsRepository.BigDay(r, s.at().toLocalDate(), s.totalXp() - previous.totalXp()));
                previous = s;
            }
        }
        out.sort(Comparator.comparingLong(SiteStatsRepository.BigDay::xp).reversed());
        return out.stream().limit(limit).toList();
    }

    @Override public synchronized List<SiteStatsRepository.ClubMember> twoHundredMillionClub(long guildId, int limit) {
        List<SiteStatsRepository.ClubMember> out = new ArrayList<>();
        for (String r : roster(guildId).values()) {
            var s = snaps(r);
            if (s.isEmpty()) continue;
            int n = (int) s.getLast().skills().stream().filter(v -> v.xp() >= 200_000_000L).count();
            if (n > 0) out.add(new SiteStatsRepository.ClubMember(r, n));
        }
        out.sort(Comparator.comparingInt(SiteStatsRepository.ClubMember::skills).reversed());
        return out.stream().limit(limit).toList();
    }

    @Override public synchronized List<SiteStatsRepository.CapWeek> citadelGrid(long guildId, int weeks) {
        LocalDate first = citadelWeek(OffsetDateTime.now(ZoneOffset.UTC)).minusWeeks(weeks - 1L);
        return allCapWeeks(guildId).stream().filter(c -> !c.weekStart().isBefore(first)).toList();
    }

    @Override public synchronized List<SiteStatsRepository.CapWeek> allCapWeeks(long guildId) {
        return clanLines(guildId).stream().filter(MemoryStats::capped).map(l -> new SiteStatsRepository.CapWeek(l.rsn(), citadelWeek(l.at()), true)).distinct().toList();
    }

    @Override public synchronized List<SiteStatsRepository.WeekCount> citadelByWeek(long guildId, int weeks) {
        Map<LocalDate, Integer> capped = new TreeMap<>();
        for (var c : citadelGrid(guildId, weeks)) capped.merge(c.weekStart(), 1, Integer::sum);
        return capped.entrySet().stream().map(e -> new SiteStatsRepository.WeekCount(e.getKey(), e.getValue(), e.getValue())).toList();
    }

    @Override public synchronized List<SiteStatsRepository.Capper> topCappers(long guildId, int limit) {
        return cappersBetween(guildId, OffsetDateTime.now(ZoneOffset.UTC).minusYears(50), OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), limit);
    }

    @Override public synchronized List<SiteStatsRepository.SkillLeader> skillLeaders(long guildId, int perSkill) {
        Map<Integer, List<SiteStatsRepository.SkillLeader>> bySkill = new TreeMap<>();
        for (String r : roster(guildId).values()) {
            var s = snaps(r);
            if (s.isEmpty()) continue;
            for (SkillValue v : s.getLast().skills()) bySkill.computeIfAbsent(v.skillId(), k -> new ArrayList<>()).add(new SiteStatsRepository.SkillLeader(v.skillId(), r, v.level(), v.xp()));
        }
        List<SiteStatsRepository.SkillLeader> out = new ArrayList<>();
        for (var list : bySkill.values()) {
            list.sort(Comparator.comparingLong(SiteStatsRepository.SkillLeader::xp).reversed());
            out.addAll(list.stream().limit(perSkill).toList());
        }
        return out;
    }

    @Override public synchronized List<SiteStatsRepository.DayPoint> dailyHistory(long guildId, String rsn, int days) {
        if (names(guildId, rsn).isEmpty()) return List.of();
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(Duration.ofDays(days));
        Map<LocalDate, Snapshot> last = new TreeMap<>();
        for (Snapshot s : snaps(rsn)) if (!s.at().isBefore(since)) last.put(s.at().toLocalDate(), s);
        return last.entrySet().stream().map(e -> new SiteStatsRepository.DayPoint(e.getKey(), e.getValue().totalXp(), e.getValue().totalLevel())).toList();
    }

    @Override public synchronized SiteStatsRepository.CitadelTotals citadelFor(long guildId, String rsn) {
        List<Line> mine = linesOf(rsn);
        List<LocalDate> weeks = mine.stream().filter(MemoryStats::capped).map(l -> citadelWeek(l.at())).distinct().sorted().toList();
        int visits = (int) mine.stream().filter(l -> l.activity().text().startsWith("Visited my Clan Citadel")).count();
        return new SiteStatsRepository.CitadelTotals((int) mine.stream().filter(MemoryStats::capped).count(), visits, weeks);
    }

    @Override public synchronized List<CitadelActivityRow> citadelActivityInWindow(long guildId, OffsetDateTime from, OffsetDateTime to) {
        return clanLines(guildId).stream().filter(l -> (capped(l) || l.activity().text().startsWith("Visited my Clan Citadel")) && !l.at().isBefore(from) && l.at().isBefore(to))
                .map(l -> new CitadelActivityRow(l.rsn(), l.activity().text(), l.activity().date(), l.at().toInstant())).toList();
    }

    @Override public synchronized Map<String, OffsetDateTime> lastActivity(long guildId) {
        Map<String, OffsetDateTime> out = new HashMap<>();
        for (Line l : clanLines(guildId)) out.merge(key(l.rsn()), l.at(), (a, b) -> a.isAfter(b) ? a : b);
        return out;
    }
}
