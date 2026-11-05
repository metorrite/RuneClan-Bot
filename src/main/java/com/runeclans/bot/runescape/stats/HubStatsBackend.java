package com.runeclans.bot.runescape.stats;

import com.runeclans.bot.internal.SiteStatsRepository;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.ClanMemberRepository;
import com.runeclans.bot.runescape.PlayerActivity;
import com.runeclans.bot.runescape.PlayerLinkRepository.SkillHistoryPoint;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.runeclans.bot.runescape.RuneScapeSkillCatalog;
import com.runeclans.bot.runescape.SkillValue;
import com.runeclans.bot.runescape.SkillXpPoint;
import com.runeclans.bot.runescape.WeeklyDigestRepository.CitadelActivityRow;
import com.runeclans.bot.tracking.CitadelWeek;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@link StatsBackend} on the RuneClans hub's stats routes (RuneClan-API docs/API.md "Player stats and polling"), through the bot's first-party
 * key: {@code POST /v1/players/stats} for the newest snapshots with skills (100 names a call), {@code GET /v1/players/{rsn}/stats/history},
 * {@code .../stats/skills} and {@code .../activity} for one player's history and adventure log, and, for a server whose clan is registered on
 * RuneClans ({@code GET /v1/guilds/{id}/site} names its {@code clanId}), {@code POST /v1/clans/{clanId}/stats/gains} and {@code .../skill-gains}
 * for the leaderboard and recap windows. Everything else is worked out here from the members' own history and activity lines, the way the
 * SQL in {@code SiteStatsRepository} did, so every feature keeps its shape.
 * <p>
 * Reads go through the hub client's cache ({@link RuneClansHubClient#read}: a few minutes fresh, the last answer while the hub is down,
 * emptied by the feed's {@code stats.updated} / {@code player.activity} for that player), and a server's members are read side by side on a
 * small pool, so a leaderboard of a few hundred members is a few hundred cached calls spread over a second or two, not a few hundred seconds.
 * A clan past {@link #MAX_MEMBERS} members is answered for its first {@value #MAX_MEMBERS} (by name) and logged once. The hub answers at most
 * {@value #PAGE} rows per call, so "all-time" views (records, cap streaks) see each member's newest {@value #PAGE} snapshots or lines.
 */
public final class HubStatsBackend implements StatsBackend {
    private static final Logger log = LoggerFactory.getLogger(HubStatsBackend.class);
    static final int PAGE = 1000;
    static final int BATCH = 100;
    static final int MAX_MEMBERS = 600;
    static final int THREADS = 6;
    static final Duration FRESH = Duration.ofMinutes(2);
    static final Duration FRESH_HISTORY = Duration.ofMinutes(5);
    static final Duration FRESH_SITE = Duration.ofMinutes(10);
    /** Slack behind a window's start, so the snapshot at or before it is in the fetched history (every tracked player is polled at least daily). */
    static final Duration BEFORE_WINDOW = Duration.ofDays(2);
    static final String CAPPED = "Capped at my Clan Citadel";
    static final String VISITED = "Visited my Clan Citadel";
    static final long TWO_HUNDRED_MILLION = 200_000_000L;

    /** What the backend needs from the hub client: a cached {@code GET} and a plain call. Tests pass their own. */
    public interface Hub {
        /** {@link RuneClansHubClient#read}: null when off, or down with nothing cached. */
        RuneClansHubClient.Read read(String path, Duration keepFresh, String... tags);

        /** {@link RuneClansHubClient#call}: null when off or unreachable. */
        RuneClansHubClient.Answer call(String method, String path, String body);
    }

    /** A server's clan roster, from the bot's own table. */
    public interface Roster {
        List<ClanMemberRepository.ClanMemberRow> members(long guildId, boolean activeOnly);
    }

    /** One snapshot as the hub lists it. */
    record Snap(long id, Instant at, int totalLevel, long totalXp, int combatLevel, int questsComplete, int questsStarted, int questsNotStarted) {
        StatsSnapshotRow row() {
            return new StatsSnapshotRow(id, at.atOffset(ZoneOffset.UTC), totalLevel, totalXp, combatLevel, questsComplete, questsStarted, questsNotStarted);
        }

        LocalDate day() {
            return at.atOffset(ZoneOffset.UTC).toLocalDate();
        }
    }

    /** One adventure-log line as the hub lists it; {@code occurredAt} null when the source's date couldn't be read. */
    record Line(String rsn, String text, String details, String date, Instant occurredAt, Instant recordedAt, String type) {
        Instant happened() {
            return occurredAt != null ? occurredAt : recordedAt;
        }

        boolean capped() {
            return special() && text != null && text.startsWith(CAPPED);
        }

        private boolean special() {
            return "rs_plog_special_event".equals(type);
        }

        boolean citadel() {
            return special() && text != null && (text.startsWith(CAPPED) || text.startsWith(VISITED));
        }

        PlayerActivity activity() {
            return new PlayerActivity(date, text, details, type);
        }

        SiteStatsRepository.ActivityRow row(String memberName) {
            return new SiteStatsRepository.ActivityRow(memberName, text, details, date, recordedAt.atOffset(ZoneOffset.UTC), type);
        }
    }

    private record Cached<T>(T value, Instant at) {}

    /** How long a fan-out over a clan's members may take in all: the website gives up on the bot after 8 seconds. */
    static final Duration BUDGET = Duration.ofSeconds(6);

    /** The hub had no answer (unreachable, busy, or failing), as opposed to answering "nothing there". */
    static final class HubDown extends RuntimeException {
        HubDown(String what) {
            super(what);
        }
    }

    private final Hub hub;
    private final Roster roster;
    private final Clock clock;
    private final ExecutorService pool;
    private volatile Duration budget = BUDGET;
    /** Skill-gains answers ({@code POST}, so the client doesn't cache them), by clan, name and window, kept {@link #FRESH_HISTORY}. */
    private final ConcurrentHashMap<String, Cached<Map<Integer, Long>>> skillGains = new ConcurrentHashMap<>();
    /** The newest batch answers, by their sorted names, a couple of minutes (the client caches only GETs). */
    private final ConcurrentHashMap<String, Cached<Map<String, DataObject>>> batches = new ConcurrentHashMap<>();
    /** The skills of the newest snapshots seen, by the hub's snapshot id (what {@link #skillsForSnapshot} answers). */
    private final Map<Long, List<SkillValue>> skillsById = java.util.Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<SkillValue>> eldest) {
            return size() > 5_000;
        }
    });
    private final Set<Long> warnedLarge = ConcurrentHashMap.newKeySet();

    /** On the bot's hub client and roster table, with a small pool for reading a clan's members side by side. */
    public static HubStatsBackend of(RuneClansHubClient client, ClanMemberRepository members) {
        Hub hub = new Hub() {
            @Override
            public RuneClansHubClient.Read read(String path, Duration keepFresh, String... tags) {
                return client.read(path, keepFresh, tags);
            }

            @Override
            public RuneClansHubClient.Answer call(String method, String path, String body) {
                return client.call(method, path, body);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "hub-stats-reads");
            t.setDaemon(true);
            return t;
        });
        return new HubStatsBackend(hub, members::getAll, Clock.systemUTC(), pool);
    }

    /** {@code pool} null runs member reads one after another on the caller's thread (tests). */
    public HubStatsBackend(Hub hub, Roster roster, Clock clock, ExecutorService pool) {
        this.hub = hub;
        this.roster = roster;
        this.clock = clock;
        this.pool = pool;
    }

    /** A shorter overall time for a fan-out (tests). */
    HubStatsBackend withBudget(Duration budget) {
        this.budget = budget;
        return this;
    }

    // ---------------------------------------------------------------- one player

    @Override
    public StatsSnapshotRow latest(String rsn) {
        return latestMany(List.of(rsn)).get(key(rsn));
    }

    @Override
    public Map<String, StatsSnapshotRow> latestMany(Collection<String> rsns) {
        Map<String, StatsSnapshotRow> out = new HashMap<>();
        latestJson(rsns, false).forEach((k, json) -> out.put(k, snap(json).row()));
        return out;
    }

    /** The hub's newest snapshot (with skills) per lower-cased name, in batches of {@value #BATCH}, each batch kept {@link #FRESH}. */
    private Map<String, DataObject> latestJson(Collection<String> rsns) {
        return latestJson(rsns, true);
    }

    /**
     * {@code strict}: a batch the hub couldn't answer fails the whole read ({@link StatsUnavailableException}), for views of a clan that must
     * not come up short in silence; otherwise those names are left out, for reads that treat a missing name as "no data".
     */
    private Map<String, DataObject> latestJson(Collection<String> rsns, boolean strict) {
        List<String> names = new ArrayList<>(new LinkedHashSet<>(rsns.stream().filter(r -> r != null && !r.isBlank()).map(String::strip).toList()));
        names.sort(Comparator.comparing(HubStatsBackend::key));
        Map<String, DataObject> out = new HashMap<>();
        Instant now = clock.instant();
        batches.values().removeIf(c -> c.at().plus(FRESH).isBefore(now));
        for (int i = 0; i < names.size(); i += BATCH) {
            List<String> chunk = names.subList(i, Math.min(names.size(), i + BATCH));
            String batchKey = String.join("\n", chunk.stream().map(HubStatsBackend::key).toList());
            Cached<Map<String, DataObject>> cached = batches.get(batchKey);
            if (cached == null) {
                Map<String, DataObject> fetched;
                try {
                    fetched = fetchLatest(chunk);
                } catch (HubDown e) {
                    if (strict) throw new StatsUnavailableException("the hub couldn't answer a batch of " + chunk.size() + " names: " + e.getMessage());
                    continue; // the hub couldn't answer: these names have no snapshot for now
                }
                cached = new Cached<>(fetched, now);
                if (batches.size() > 500) batches.clear();
                batches.put(batchKey, cached);
            }
            out.putAll(cached.value());
        }
        return out;
    }

    /**
     * Forgets the batch answers that hold {@code rsn}, so the next read after the hub's {@code stats.updated} fetches the new snapshot
     * (the per-player GET answers are dropped by their {@link #playerTag} in the client's cache).
     */
    public void evictPlayer(String rsn) {
        if (rsn == null || rsn.isBlank()) return;
        String k = key(rsn);
        batches.keySet().removeIf(batchKey -> {
            for (String name : batchKey.split("\n")) if (name.equals(k)) return true;
            return false;
        });
        skillGains.keySet().removeIf(cacheKey -> cacheKey.contains("|" + k + "|"));
    }

    private Map<String, DataObject> fetchLatest(List<String> chunk) {
        DataObject body = DataObject.empty().put("rsns", DataArray.fromCollection(chunk));
        RuneClansHubClient.Answer a = hub.call("POST", "/v1/players/stats", body.toString());
        if (a == null || a.status() == 429 || a.status() >= 500) throw new HubDown(a == null ? "unreachable" : "status " + a.status());
        Map<String, DataObject> out = new HashMap<>();
        if (!a.ok() || a.json() == null) return out;
        DataArray players = a.json().optArray("players").orElse(DataArray.empty());
        for (int i = 0; i < players.length(); i++) {
            DataObject p = players.getObject(i);
            String rsn = p.getString("rsn", null);
            if (rsn == null) continue;
            out.put(key(rsn), p);
            if (p.hasKey("skills") && !p.isNull("skills")) skillsById.put(p.getLong("snapshotId"), skills(p.getArray("skills")));
        }
        return out;
    }

    @Override
    public List<SkillValue> skillsForSnapshot(long snapshotId) {
        List<SkillValue> known = skillsById.get(snapshotId);
        if (known != null) return known;
        // the LRU is bounded, but a batch answer still cached lists the snapshot with its skills: read them from there
        for (Cached<Map<String, DataObject>> batch : batches.values()) {
            for (DataObject p : batch.value().values()) {
                if (p.getLong("snapshotId", -1) == snapshotId && p.hasKey("skills") && !p.isNull("skills")) {
                    List<SkillValue> skills = skills(p.getArray("skills"));
                    skillsById.put(snapshotId, skills);
                    return skills;
                }
            }
        }
        return List.of();
    }

    @Override
    public List<StatsSnapshotRow> history(String rsn, int limit) {
        List<Snap> snaps = orEmpty(() -> snaps(rsn, null, Math.max(1, Math.min(PAGE, limit))));
        List<StatsSnapshotRow> out = new ArrayList<>(snaps.size());
        for (int i = snaps.size() - 1; i >= 0; i--) out.add(snaps.get(i).row()); // newest first
        return out;
    }

    @Override
    public List<StatsSnapshotRow> snapshotsSince(String rsn, OffsetDateTime since) {
        return orEmpty(() -> snaps(rsn, since.toInstant(), PAGE)).stream().map(Snap::row).toList();
    }

    @Override
    public List<SkillXpPoint> skillXpHistory(String rsn, int skillId, OffsetDateTime since) {
        return orEmpty(() -> skillPoints(rsn, skillId, since)).stream().map(p -> new SkillXpPoint(p.timestamp(), p.xp())).toList();
    }

    @Override
    public List<SkillHistoryPoint> allSkillsXpHistorySince(String rsn, OffsetDateTime since) {
        List<Callable<List<SkillHistoryPoint>>> reads = new ArrayList<>();
        for (int skill = 0; skill < RuneScapeSkillCatalog.skillCount(); skill++) {
            int id = skill;
            reads.add(() -> skillPoints(rsn, id, since));
        }
        List<SkillHistoryPoint> out = new ArrayList<>();
        for (List<SkillHistoryPoint> points : sideBySide(reads, List.<SkillHistoryPoint>of())) out.addAll(points);
        out.sort(Comparator.comparing(SkillHistoryPoint::timestamp).thenComparingInt(SkillHistoryPoint::skillId));
        return out;
    }

    /** {@code GET /v1/players/{rsn}/stats/skills?skill=&days=&limit=}: one skill's points since {@code since}, oldest first. */
    private List<SkillHistoryPoint> skillPoints(String rsn, int skillId, OffsetDateTime since) {
        long days = Math.max(1, Math.min(400, Duration.between(since.toInstant(), clock.instant()).toDays() + 1));
        DataObject json = get("/v1/players/" + path(rsn) + "/stats/skills?skill=" + skillId + "&days=" + days + "&limit=" + PAGE, FRESH_HISTORY, rsn);
        List<SkillHistoryPoint> out = new ArrayList<>();
        if (json == null) return out;
        DataArray points = json.optArray("points").orElse(DataArray.empty());
        for (int i = 0; i < points.length(); i++) {
            DataObject p = points.getObject(i);
            Instant at = instant(p.getString("at", null));
            if (at == null || at.isBefore(since.toInstant())) continue;
            out.add(new SkillHistoryPoint(skillId, at.atOffset(ZoneOffset.UTC), p.getLong("xp", 0)));
        }
        out.sort(Comparator.comparing(SkillHistoryPoint::timestamp));
        return out;
    }

    @Override
    public List<PlayerActivity> recentActivities(String rsn, int limit) {
        List<Line> lines = orEmpty(() -> lines(rsn, null, Math.max(1, Math.min(PAGE, limit))));
        List<PlayerActivity> out = new ArrayList<>(lines.size());
        for (int i = lines.size() - 1; i >= 0; i--) out.add(lines.get(i).activity()); // newest first
        return out;
    }

    @Override
    public List<PlayerActivity> activitiesSince(String rsn, OffsetDateTime since) {
        return orEmpty(() -> lines(rsn, since.toInstant(), PAGE)).stream().map(Line::activity).toList();
    }

    @Override
    public List<String> recentActivityTexts(Collection<String> rsns, int limit) {
        int each = Math.max(1, Math.min(PAGE, limit));
        List<Line> all = new ArrayList<>();
        for (List<Line> lines : sideBySide(rsns.stream().<Callable<List<Line>>>map(r -> () -> lines(r, null, each)).toList(), List.<Line>of())) all.addAll(lines);
        all.sort(Comparator.comparing(Line::recordedAt).reversed());
        return all.stream().limit(limit).map(Line::text).toList();
    }

    // ---------------------------------------------------------------- a server's clan

    @Override
    public Map<String, StatsSnapshotRow> membersLatest(long guildId) {
        Map<String, StatsSnapshotRow> out = new HashMap<>();
        latestJson(members(guildId, true).keySet(), true).forEach((k, json) -> out.put(k, snap(json).row())); // strict: a members page is never quietly short
        return out;
    }

    @Override
    public List<SiteStatsRepository.Gain> xpGainsBetween(long guildId, OffsetDateTime from, OffsetDateTime to) {
        Map<String, String> members = members(guildId, true);
        Map<String, Long> gains = new TreeMap<>();
        Optional<String> clan = clanIdOf(guildId);
        if (clan.isPresent()) {
            DataObject body = DataObject.empty().put("from", from.toInstant().toString()).put("to", to.toInstant().toString()).put("limit", PAGE);
            RuneClansHubClient.Answer a = hub.call("POST", "/v1/clans/" + clan.get() + "/stats/gains", body.toString());
            if (a != null && a.ok() && a.json() != null) {
                DataArray arr = a.json().optArray("gains").orElse(DataArray.empty());
                for (int i = 0; i < arr.length(); i++) {
                    DataObject g = arr.getObject(i);
                    String k = key(g.getString("rsn", ""));
                    if (members.containsKey(k)) gains.put(k, g.getLong("gained", 0));
                }
                return gains.entrySet().stream().map(e -> new SiteStatsRepository.Gain(members.get(e.getKey()), e.getValue())).toList();
            }
        }
        // no registered clan (or the hub didn't answer the clan route): each member's own history
        Map<String, List<Snap>> histories = memberSnaps(members, from.toInstant().minus(BEFORE_WINDOW));
        histories.forEach((k, snaps) -> {
            Snap end = lastAtOrBefore(snaps, to.toInstant());
            Snap start = startOf(snaps, from.toInstant());
            if (end != null && start != null) gains.put(k, end.totalXp() - start.totalXp());
        });
        return gains.entrySet().stream().map(e -> new SiteStatsRepository.Gain(members.get(e.getKey()), e.getValue())).toList();
    }

    @Override
    public OffsetDateTime firstSnapshotAt(long guildId) {
        Optional<String> clan = clanIdOf(guildId);
        if (clan.isPresent()) {
            DataObject json = orNull(() -> get("/v1/clans/" + clan.get() + "/stats/day-points?days=400", FRESH_HISTORY, null));
            if (json != null) {
                DataArray points = json.optArray("points").orElse(DataArray.empty());
                LocalDate first = null;
                for (int i = 0; i < points.length(); i++) {
                    DataObject p = points.getObject(i);
                    if (p.getInt("players", 0) <= 0) continue;
                    LocalDate day;
                    try {
                        day = LocalDate.parse(p.getString("day", ""));
                    } catch (RuntimeException e) {
                        continue; // a point without a readable day says nothing about when the data starts
                    }
                    if (first == null || day.isBefore(first)) first = day;
                }
                if (first != null) return first.atStartOfDay().atOffset(ZoneOffset.UTC);
            }
        }
        Instant earliest = null;
        for (List<Snap> snaps : memberSnaps(members(guildId, true), null).values()) {
            if (!snaps.isEmpty() && (earliest == null || snaps.getFirst().at().isBefore(earliest))) earliest = snaps.getFirst().at();
        }
        return earliest == null ? null : earliest.atOffset(ZoneOffset.UTC);
    }

    @Override
    public List<SiteStatsRepository.Capper> cappersBetween(long guildId, OffsetDateTime from, OffsetDateTime to, int limit) {
        Map<String, String> members = members(guildId, true);
        List<SiteStatsRepository.Capper> fromClan = clanCappers(guildId, members, from.toInstant(), to.toInstant(), limit);
        if (fromClan != null) return fromClan;
        return cappers(memberLines(members, from.toInstant()), from.toInstant(), to.toInstant(), limit);
    }

    @Override
    public List<SiteStatsRepository.Capper> topCappers(long guildId, int limit) {
        Map<String, String> members = members(guildId, true);
        List<SiteStatsRepository.Capper> fromClan = clanCappers(guildId, members, ALL_TIME, clock.instant(), limit);
        if (fromClan != null) return fromClan;
        return cappers(memberLines(members, null), null, null, limit);
    }

    /** Far enough back to mean "ever" on the hub's cappers route. */
    private static final Instant ALL_TIME = Instant.parse("2000-01-01T00:00:00Z");

    /**
     * {@code GET /v1/clans/{id}/stats/cappers}: one read for the whole clan (the hub counts a cap per line that says "capped", the bot's rule), kept
     * to the bot's roster and spelt its way. A player caps at most once a Citadel week, so caps and weeks capped are the same count. Null when the
     * server has no registered clan or the hub didn't answer the route, and the members' own lines are read instead.
     */
    private List<SiteStatsRepository.Capper> clanCappers(long guildId, Map<String, String> members, Instant from, Instant to, int limit) {
        Optional<String> clan = clanIdOf(guildId);
        if (clan.isEmpty()) return null;
        DataObject json = orNull(() -> get("/v1/clans/" + clan.get() + "/stats/cappers?from=" + from + "&to=" + to + "&limit=" + PAGE, FRESH_HISTORY, null));
        if (json == null) return null;
        DataArray arr = json.optArray("cappers").orElse(DataArray.empty());
        List<SiteStatsRepository.Capper> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            DataObject c = arr.getObject(i);
            String k = key(c.getString("rsn", ""));
            int caps = c.getInt("caps", 0);
            if (members.containsKey(k) && caps > 0) out.add(new SiteStatsRepository.Capper(members.get(k), caps, caps));
        }
        out.sort(Comparator.comparingInt(SiteStatsRepository.Capper::weeksCapped).reversed().thenComparing(Comparator.comparingInt(SiteStatsRepository.Capper::totalCaps).reversed())
                .thenComparing(SiteStatsRepository.Capper::rsn));
        return out.stream().limit(limit).toList();
    }

    private static List<SiteStatsRepository.Capper> cappers(Map<String, Member> lines, Instant from, Instant to, int limit) {
        List<SiteStatsRepository.Capper> out = new ArrayList<>();
        lines.forEach((k, m) -> {
            Set<LocalDate> weeks = new LinkedHashSet<>();
            int caps = 0;
            for (Line l : m.lines()) {
                if (!l.capped() || !inWindow(l.happened(), from, to)) continue;
                caps++;
                weeks.add(CitadelWeek.weekStartOf(l.happened().atOffset(ZoneOffset.UTC)));
            }
            if (caps > 0) out.add(new SiteStatsRepository.Capper(m.name(), weeks.size(), caps));
        });
        out.sort(Comparator.comparingInt(SiteStatsRepository.Capper::weeksCapped).reversed().thenComparing(Comparator.comparingInt(SiteStatsRepository.Capper::totalCaps).reversed())
                .thenComparing(SiteStatsRepository.Capper::rsn));
        return out.stream().limit(limit).toList();
    }

    @Override
    public List<SiteStatsRepository.SkillGain> skillGainsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        Map<String, String> members = members(guildId, true);
        List<String> names = rsn == null ? new ArrayList<>(members.values()) : members.containsKey(key(rsn)) ? List.of(rsn) : List.of();
        Optional<String> clan = clanIdOf(guildId);
        if (names.isEmpty() || clan.isEmpty()) {
            if (clan.isEmpty() && !names.isEmpty()) noteNoClan(guildId, "skill gains");
            return List.of();
        }
        Instant now = clock.instant();
        skillGains.values().removeIf(c -> c.at().plus(FRESH_HISTORY).isBefore(now));
        java.util.function.Function<String, String> cacheKeyOf = name -> clan.get() + "|" + key(name) + "|" + from.toInstant() + "|" + to.toInstant();
        // T-918: the members not cached yet go to the hub in one call per chunk, not one call each (about 200 for a big clan)
        List<String> missing = names.stream().filter(name -> !skillGains.containsKey(cacheKeyOf.apply(name))).toList();
        boolean batched = true;
        for (int i = 0; i < missing.size() && batched; i += SKILL_GAINS_CHUNK) {
            batched = skillGainsBatch(clan.get(), missing.subList(i, Math.min(missing.size(), i + SKILL_GAINS_CHUNK)), from, to, cacheKeyOf, now);
        }
        List<Callable<Map<Integer, Long>>> reads = names.stream().<Callable<Map<Integer, Long>>>map(name -> () -> {
            String cacheKey = cacheKeyOf.apply(name);
            Cached<Map<Integer, Long>> cached = skillGains.get(cacheKey);
            if (cached != null) return cached.value();
            // a hub without the batch mode yet: one call for this member, as before
            DataObject body = DataObject.empty().put("rsn", name).put("from", from.toInstant().toString()).put("to", to.toInstant().toString());
            RuneClansHubClient.Answer a = hub.call("POST", "/v1/clans/" + clan.get() + "/stats/skill-gains", body.toString());
            if (a == null || a.status() == 429 || a.status() >= 500) throw new HubDown(a == null ? "unreachable" : "status " + a.status());
            Map<Integer, Long> gains = a.ok() && a.json() != null ? skillsOf(a.json()) : Map.of();
            remember(cacheKey, gains, now);
            return gains;
        }).toList();
        Map<Integer, Long> total = new HashMap<>();
        for (Map<Integer, Long> one : sideBySide(reads, Map.<Integer, Long>of())) one.forEach((skill, gain) -> total.merge(skill, gain, Long::sum));
        return total.entrySet().stream().filter(e -> e.getValue() > 0).sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .map(e -> new SiteStatsRepository.SkillGain(e.getKey(), e.getValue())).toList();
    }

    /** Names per batched skill-gains call (the hub takes up to 5,000). */
    static final int SKILL_GAINS_CHUNK = 1_000;

    /**
     * One batched skill-gains call (T-918) for {@code names}: caches every member's gains, an empty map for one the hub has no two snapshots for.
     * False when the hub answered without the batch shape (an older hub), so the caller asks one member at a time instead.
     */
    private boolean skillGainsBatch(String clanId, List<String> names, OffsetDateTime from, OffsetDateTime to, java.util.function.Function<String, String> cacheKeyOf, Instant now) {
        DataArray rsns = DataArray.empty();
        names.forEach(rsns::add);
        DataObject body = DataObject.empty().put("rsns", rsns).put("from", from.toInstant().toString()).put("to", to.toInstant().toString());
        RuneClansHubClient.Answer a = hub.call("POST", "/v1/clans/" + clanId + "/stats/skill-gains", body.toString());
        if (a == null || a.status() == 429 || a.status() >= 500) throw new HubDown(a == null ? "unreachable" : "status " + a.status());
        if (!a.ok() || a.json() == null || !a.json().hasKey("players")) return false;
        Map<String, Map<Integer, Long>> byKey = new HashMap<>();
        DataArray players = a.json().getArray("players");
        for (int i = 0; i < players.length(); i++) {
            DataObject p = players.getObject(i);
            byKey.put(key(p.getString("rsn", "")), skillsOf(p));
        }
        for (String name : names) remember(cacheKeyOf.apply(name), byKey.getOrDefault(key(name), Map.of()), now);
        return true;
    }

    private static Map<Integer, Long> skillsOf(DataObject answer) {
        Map<Integer, Long> gains = new HashMap<>();
        DataArray arr = answer.optArray("skills").orElse(DataArray.empty());
        for (int i = 0; i < arr.length(); i++) {
            DataObject s = arr.getObject(i);
            gains.merge(s.getInt("skillId"), s.getLong("gained", 0), Long::sum);
        }
        return gains;
    }

    private void remember(String cacheKey, Map<Integer, Long> gains, Instant now) {
        if (skillGains.size() > 5_000) skillGains.clear();
        skillGains.put(cacheKey, new Cached<>(gains, now));
    }

    @Override
    public List<SiteStatsRepository.DayXp> dailyXpBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        Map<String, String> members = only(members(guildId, true), rsn);
        LocalDate firstDay = from.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        TreeMap<LocalDate, Long> perDay = new TreeMap<>();
        memberSnaps(members, from.toInstant().minus(Duration.ofDays(1))).forEach((k, snaps) -> {
            TreeMap<LocalDate, Long> daily = dailyMax(snaps.stream().filter(s -> s.at().isBefore(to.toInstant())).toList());
            Long previous = null;
            for (Map.Entry<LocalDate, Long> e : daily.entrySet()) {
                if (previous != null && !e.getKey().isBefore(firstDay)) {
                    long gain = e.getValue() - previous;
                    if (gain >= 0) perDay.merge(e.getKey(), gain, Long::sum);
                }
                previous = e.getValue();
            }
        });
        return perDay.entrySet().stream().map(e -> new SiteStatsRepository.DayXp(e.getKey(), e.getValue())).toList();
    }

    @Override
    public List<SiteStatsRepository.ActivityRow> activitiesBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        List<SiteStatsRepository.ActivityRow> out = new ArrayList<>();
        memberLines(only(members(guildId, true), rsn), from.toInstant()).forEach((k, m) -> {
            for (Line l : m.lines()) if (inWindow(l.recordedAt(), from.toInstant(), to.toInstant())) out.add(l.row(m.name()));
        });
        out.sort(Comparator.comparing(SiteStatsRepository.ActivityRow::recordedAt));
        return out;
    }

    @Override
    public Integer[] totalLevelsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) {
        List<Snap> snaps = orEmpty(() -> snaps(rsn, from.toInstant().minus(BEFORE_WINDOW), PAGE));
        Snap end = lastAtOrBefore(snaps, to.toInstant());
        Snap start = startOf(snaps, from.toInstant());
        return new Integer[]{start == null ? null : start.totalLevel(), end == null ? null : end.totalLevel()};
    }

    @Override
    public List<SiteStatsRepository.ActivityRow> recentActivities(long guildId, int limit) {
        int each = Math.max(1, Math.min(PAGE, limit));
        List<SiteStatsRepository.ActivityRow> out = new ArrayList<>();
        memberLines(members(guildId, true), null, each).forEach((k, m) -> m.lines().forEach(l -> out.add(l.row(m.name()))));
        out.sort(Comparator.comparing(SiteStatsRepository.ActivityRow::recordedAt).reversed());
        return out.stream().limit(limit).toList();
    }

    @Override
    public List<SiteStatsRepository.ActivityRow> activitiesStartingWith(long guildId, String prefix) {
        List<SiteStatsRepository.ActivityRow> out = new ArrayList<>();
        memberLines(members(guildId, true), null).forEach((k, m) -> {
            for (Line l : m.lines()) if (l.text() != null && l.text().startsWith(prefix)) out.add(l.row(m.name()));
        });
        out.sort(Comparator.comparing(SiteStatsRepository.ActivityRow::recordedAt).reversed());
        return out;
    }

    @Override
    public List<SiteStatsRepository.BigDay> biggestDays(long guildId, int limit) {
        Map<String, String> members = members(guildId, true);
        List<SiteStatsRepository.BigDay> out = new ArrayList<>();
        memberSnaps(members, null).forEach((k, snaps) -> {
            Long previous = null;
            for (Map.Entry<LocalDate, Long> e : dailyMax(snaps).entrySet()) {
                if (previous != null && e.getValue() - previous > 0) out.add(new SiteStatsRepository.BigDay(members.get(k), e.getKey(), e.getValue() - previous));
                previous = e.getValue();
            }
        });
        out.sort(Comparator.comparingLong(SiteStatsRepository.BigDay::xp).reversed());
        return out.stream().limit(limit).toList();
    }

    @Override
    public List<SiteStatsRepository.ClubMember> twoHundredMillionClub(long guildId, int limit) {
        Map<String, String> members = members(guildId, true);
        List<SiteStatsRepository.ClubMember> out = new ArrayList<>();
        latestJson(members.keySet()).forEach((k, json) -> {
            int skills = 0;
            for (SkillValue s : skills(json.optArray("skills").orElse(DataArray.empty()))) if (s.xp() >= TWO_HUNDRED_MILLION) skills++;
            if (skills > 0) out.add(new SiteStatsRepository.ClubMember(members.get(k), skills));
        });
        out.sort(Comparator.comparingInt(SiteStatsRepository.ClubMember::skills).reversed().thenComparing(SiteStatsRepository.ClubMember::rsn));
        return out.stream().limit(limit).toList();
    }

    @Override
    public List<SiteStatsRepository.CapWeek> citadelGrid(long guildId, int weeks) {
        Instant since = clock.instant().minus(Duration.ofDays(7L * (weeks + 1)));
        Map<String, Map<LocalDate, Boolean>> grid = new TreeMap<>();
        Map<String, String> names = new HashMap<>();
        memberLines(members(guildId, true), since).forEach((k, m) -> {
            for (Line l : m.lines()) {
                if (!l.citadel() || l.happened().isBefore(since)) continue;
                names.put(k, m.name());
                grid.computeIfAbsent(k, x -> new TreeMap<>()).merge(CitadelWeek.weekStartOf(l.happened().atOffset(ZoneOffset.UTC)), l.capped(), Boolean::logicalOr);
            }
        });
        List<SiteStatsRepository.CapWeek> out = new ArrayList<>();
        grid.forEach((k, weeksOf) -> weeksOf.forEach((week, capped) -> out.add(new SiteStatsRepository.CapWeek(names.get(k), week, capped))));
        return out;
    }

    @Override
    public List<SiteStatsRepository.CapWeek> allCapWeeks(long guildId) {
        List<SiteStatsRepository.CapWeek> out = new ArrayList<>();
        memberLines(members(guildId, true), null).forEach((k, m) -> {
            Set<LocalDate> weeks = new LinkedHashSet<>();
            for (Line l : m.lines()) if (l.capped()) weeks.add(CitadelWeek.weekStartOf(l.happened().atOffset(ZoneOffset.UTC)));
            for (LocalDate week : weeks) out.add(new SiteStatsRepository.CapWeek(m.name(), week, true));
        });
        return out;
    }

    @Override
    public List<SiteStatsRepository.WeekCount> citadelByWeek(long guildId, int weeks) {
        Instant since = clock.instant().minus(Duration.ofDays(7L * (weeks + 1)));
        TreeMap<LocalDate, Set<String>> visited = new TreeMap<>();
        TreeMap<LocalDate, Set<String>> capped = new TreeMap<>();
        memberLines(members(guildId, true), since).forEach((k, m) -> {
            for (Line l : m.lines()) {
                if (!l.citadel() || l.happened().isBefore(since)) continue;
                LocalDate week = CitadelWeek.weekStartOf(l.happened().atOffset(ZoneOffset.UTC));
                visited.computeIfAbsent(week, w -> new LinkedHashSet<>()).add(k);
                if (l.capped()) capped.computeIfAbsent(week, w -> new LinkedHashSet<>()).add(k);
            }
        });
        List<SiteStatsRepository.WeekCount> out = new ArrayList<>();
        visited.forEach((week, who) -> out.add(new SiteStatsRepository.WeekCount(week, capped.getOrDefault(week, Set.of()).size(), who.size())));
        return out;
    }

    @Override
    public List<SiteStatsRepository.SkillLeader> skillLeaders(long guildId, int perSkill) {
        Map<String, String> members = members(guildId, true);
        Map<Integer, List<SiteStatsRepository.SkillLeader>> bySkill = new TreeMap<>();
        latestJson(members.keySet()).forEach((k, json) -> {
            for (SkillValue s : skills(json.optArray("skills").orElse(DataArray.empty()))) {
                bySkill.computeIfAbsent(s.skillId(), x -> new ArrayList<>()).add(new SiteStatsRepository.SkillLeader(s.skillId(), members.get(k), s.level(), s.xp()));
            }
        });
        List<SiteStatsRepository.SkillLeader> out = new ArrayList<>();
        for (List<SiteStatsRepository.SkillLeader> leaders : bySkill.values()) {
            leaders.sort(Comparator.comparingLong(SiteStatsRepository.SkillLeader::xp).reversed().thenComparing(SiteStatsRepository.SkillLeader::rsn));
            out.addAll(leaders.subList(0, Math.min(perSkill, leaders.size())));
        }
        return out;
    }

    @Override
    public List<SiteStatsRepository.DayPoint> dailyHistory(long guildId, String rsn, int days) {
        Instant since = clock.instant().minus(Duration.ofDays(days));
        TreeMap<LocalDate, Snap> lastOfDay = new TreeMap<>();
        for (Snap s : orEmpty(() -> snaps(rsn, since, PAGE))) lastOfDay.put(s.day(), s); // oldest first, so the last one of a day stays
        return lastOfDay.entrySet().stream().map(e -> new SiteStatsRepository.DayPoint(e.getKey(), e.getValue().totalXp(), e.getValue().totalLevel())).toList();
    }

    @Override
    public SiteStatsRepository.CitadelTotals citadelFor(long guildId, String rsn) {
        int caps = 0, visits = 0;
        List<LocalDate> cappedWeeks = new ArrayList<>();
        for (Line l : orEmpty(() -> lines(rsn, null, PAGE))) {
            if (!l.citadel()) continue;
            if (l.capped()) {
                caps++;
                LocalDate week = CitadelWeek.weekStartOf(l.happened().atOffset(ZoneOffset.UTC));
                if (!cappedWeeks.contains(week)) cappedWeeks.add(week);
            } else {
                visits++;
            }
        }
        cappedWeeks.sort(null);
        return new SiteStatsRepository.CitadelTotals(caps, visits, cappedWeeks);
    }

    @Override
    public List<CitadelActivityRow> citadelActivityInWindow(long guildId, OffsetDateTime from, OffsetDateTime to) {
        List<CitadelActivityRow> out = new ArrayList<>();
        memberLines(members(guildId, false), from.toInstant()).forEach((k, m) -> {
            for (Line l : m.lines()) {
                if (l.citadel() && inWindow(l.happened(), from.toInstant(), to.toInstant())) out.add(new CitadelActivityRow(m.name(), l.text(), l.date(), l.occurredAt()));
            }
        });
        return out;
    }

    @Override
    public Map<String, OffsetDateTime> lastActivity(long guildId) {
        Map<String, OffsetDateTime> out = new HashMap<>();
        memberLines(members(guildId, true), null, 1).forEach((k, m) -> {
            if (!m.lines().isEmpty()) out.put(k, m.lines().getLast().recordedAt().atOffset(ZoneOffset.UTC));
        });
        return out;
    }

    // ---------------------------------------------------------------- building blocks

    /** A server's roster: lower-cased name to the name as the roster spells it, in name order; at most {@link #MAX_MEMBERS}. */
    private Map<String, String> members(long guildId, boolean activeOnly) {
        TreeMap<String, String> out = new TreeMap<>();
        for (ClanMemberRepository.ClanMemberRow m : roster.members(guildId, activeOnly)) {
            if (m.rsn() != null && !m.rsn().isBlank()) out.putIfAbsent(key(m.rsn()), m.rsn().strip());
        }
        if (out.size() > MAX_MEMBERS) {
            if (warnedLarge.add(guildId)) log.warn("A clan has {} members; hub stats views answer for the first {} by name.", out.size(), MAX_MEMBERS);
            Map<String, String> cut = new TreeMap<>();
            for (Map.Entry<String, String> e : out.entrySet()) {
                if (cut.size() >= MAX_MEMBERS) break;
                cut.put(e.getKey(), e.getValue());
            }
            return cut;
        }
        return out;
    }

    private static Map<String, String> only(Map<String, String> members, String rsn) {
        if (rsn == null) return members;
        String k = key(rsn);
        return members.containsKey(k) ? Map.of(k, members.get(k)) : Map.of();
    }

    /** One member's name as the roster spells it, and their lines, oldest first. */
    record Member(String name, List<Line> lines) {}

    private Map<String, List<Snap>> memberSnaps(Map<String, String> members, Instant since) {
        List<String> keys = new ArrayList<>(members.keySet());
        List<List<Snap>> all = sideBySide(keys.stream().<Callable<List<Snap>>>map(k -> () -> snaps(members.get(k), since, PAGE)).toList(), List.<Snap>of());
        Map<String, List<Snap>> out = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) out.put(keys.get(i), all.get(i));
        return out;
    }

    private Map<String, Member> memberLines(Map<String, String> members, Instant since) {
        return memberLines(members, since, PAGE);
    }

    private Map<String, Member> memberLines(Map<String, String> members, Instant since, int limit) {
        List<String> keys = new ArrayList<>(members.keySet());
        List<List<Line>> all = sideBySide(keys.stream().<Callable<List<Line>>>map(k -> () -> lines(members.get(k), since, limit)).toList(), List.<Line>of());
        Map<String, Member> out = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) out.put(keys.get(i), new Member(members.get(keys.get(i)), all.get(i)));
        return out;
    }

    /** {@code GET /v1/players/{rsn}/stats/history}: the newest {@code limit} snapshots since {@code since} (null for any), oldest first. */
    private List<Snap> snaps(String rsn, Instant since, int limit) {
        DataObject json = get("/v1/players/" + path(rsn) + "/stats/history?limit=" + limit + (since == null ? "" : "&since=" + since), FRESH_HISTORY, rsn);
        List<Snap> out = new ArrayList<>();
        if (json == null) return out;
        DataArray arr = json.optArray("snapshots").orElse(DataArray.empty());
        for (int i = 0; i < arr.length(); i++) {
            Snap s = snap(arr.getObject(i));
            if (s.at() != null) out.add(s);
        }
        out.sort(Comparator.comparing(Snap::at));
        return out;
    }

    /** {@code GET /v1/players/{rsn}/activity}: the newest {@code limit} lines since {@code since} (null for any), oldest first. */
    private List<Line> lines(String rsn, Instant since, int limit) {
        DataObject json = get("/v1/players/" + path(rsn) + "/activity?limit=" + limit + (since == null ? "" : "&since=" + since), FRESH_HISTORY, rsn);
        List<Line> out = new ArrayList<>();
        if (json == null) return out;
        DataArray arr = json.optArray("activities").orElse(DataArray.empty());
        for (int i = 0; i < arr.length(); i++) {
            DataObject a = arr.getObject(i);
            Instant recorded = instant(a.getString("recordedAt", null));
            if (recorded == null) continue;
            out.add(new Line(a.getString("rsn", rsn), a.getString("text", null), a.getString("details", null), a.getString("date", null), instant(a.getString("occurredAt", null)), recorded, a.getString("type", null)));
        }
        out.sort(Comparator.comparing(Line::recordedAt));
        return out;
    }

    /** The hub clan behind a server, from its site record; empty when there is none or the hub can't say. */
    Optional<String> clanIdOf(long guildId) {
        DataObject json = orNull(() -> get("/v1/guilds/" + guildId + "/site", FRESH_SITE, null, "sites"));
        if (json == null || !json.hasKey("clanId") || json.isNull("clanId")) return Optional.empty();
        String id = json.getString("clanId", "");
        return id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    private final Set<Long> warnedNoClan = ConcurrentHashMap.newKeySet();

    private void noteNoClan(long guildId, String what) {
        if (warnedNoClan.add(guildId)) log.info("A server's clan isn't registered on RuneClans, so its {} can't be read from the hub until it is.", what);
    }

    /**
     * A cached {@code GET}, tagged {@code player:<name>} when it is one player's data. Null when the hub answered that there is nothing there
     * (404 and the like); {@link HubDown} when it didn't answer (off, unreachable, busy or failing, with nothing cached to stand in).
     */
    private DataObject get(String path, Duration fresh, String rsn, String... moreTags) {
        List<String> tags = new ArrayList<>(List.of(moreTags));
        if (rsn != null) tags.add(playerTag(rsn));
        RuneClansHubClient.Read read = hub.read(path, fresh, tags.toArray(String[]::new));
        if (read == null || read.answer() == null) throw new HubDown("no answer for " + path);
        if (read.answer().status() == 429 || read.answer().status() >= 500) throw new HubDown("status " + read.answer().status() + " for " + path);
        if (!read.answer().ok()) return null;
        return read.answer().json();
    }

    /** One player's read: the hub having no answer is "nothing known", as the bot's empty table would be. */
    private static <T> List<T> orEmpty(java.util.function.Supplier<List<T>> read) {
        try {
            return read.get();
        } catch (HubDown e) {
            return List.of();
        }
    }

    private static <T> T orNull(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (HubDown e) {
            return null;
        }
    }

    /** A read's outcome in a fan-out: its value, or that the hub didn't answer it. */
    private record Outcome<T>(T value, boolean failed) {
        static <T> Outcome<T> of(T value) {
            return new Outcome<>(value, false);
        }

        static <T> Outcome<T> missing() {
            return new Outcome<>(null, true);
        }
    }

    /**
     * Runs {@code reads} side by side on the pool (or one after another without one), all within {@link #budget}; a read that answered "nothing
     * there" stands in as {@code fallback}. A read the hub didn't answer, or that the budget cut off, fails the whole view
     * ({@link StatsUnavailableException}) rather than leaving its member out in silence. Pool reads are tried once, without the client's retry
     * sleeps: the budget is their patience.
     */
    private <T> List<T> sideBySide(List<Callable<T>> reads, T fallback) {
        List<Outcome<T>> outcomes = new ArrayList<>(reads.size());
        if (pool == null) {
            for (Callable<T> read : reads) outcomes.add(quietly(read, fallback));
        } else {
            long deadline = System.nanoTime() + budget.toNanos();
            List<Future<Outcome<T>>> futures = new ArrayList<>(reads.size());
            for (Callable<T> read : reads) futures.add(pool.submit(() -> RuneClansHubClient.withoutRetries(() -> quietly(read, fallback))));
            for (Future<Outcome<T>> future : futures) {
                try {
                    long left = deadline - System.nanoTime();
                    outcomes.add(left <= 0 ? Outcome.<T>missing() : future.get(left, TimeUnit.NANOSECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcomes.add(Outcome.missing());
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    outcomes.add(Outcome.missing());
                }
            }
            for (Future<Outcome<T>> future : futures) future.cancel(true); // whatever is still queued or running past the budget stops here
        }
        long failed = outcomes.stream().filter(Outcome::failed).count();
        if (failed > 0) {
            log.warn("{} of {} hub stats reads got no answer (down, busy, or over the {} budget): the view is unavailable rather than short.", failed, reads.size(), budget);
            throw new StatsUnavailableException(failed + " of " + reads.size() + " hub reads got no answer");
        }
        List<T> out = new ArrayList<>(outcomes.size());
        for (Outcome<T> o : outcomes) out.add(o.value());
        return out;
    }

    private static <T> Outcome<T> quietly(Callable<T> read, T fallback) {
        try {
            T value = read.call();
            return Outcome.of(value == null ? fallback : value);
        } catch (HubDown e) {
            return Outcome.missing();
        } catch (Exception e) {
            log.warn("A hub stats read failed: {}", e.toString());
            return Outcome.missing();
        }
    }

    // ---------------------------------------------------------------- shapes and small rules

    static Snap snap(DataObject json) {
        return new Snap(json.getLong("snapshotId", 0), instant(json.getString("at", null)), json.getInt("totalLevel", 0), json.getLong("totalXp", 0), json.getInt("combatLevel", 0),
                json.getInt("questsComplete", 0), json.getInt("questsStarted", 0), json.getInt("questsNotStarted", 0));
    }

    static List<SkillValue> skills(DataArray arr) {
        List<SkillValue> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            DataObject s = arr.getObject(i);
            out.add(new SkillValue(s.getInt("skillId"), s.getInt("level", 0), s.getLong("xp", 0), s.getInt("rank", 0)));
        }
        out.sort(Comparator.comparingInt(SkillValue::skillId));
        return out;
    }

    /** The newest snapshot at or before {@code at}, or null. */
    static Snap lastAtOrBefore(List<Snap> oldestFirst, Instant at) {
        Snap found = null;
        for (Snap s : oldestFirst) {
            if (s.at().isAfter(at)) break;
            found = s;
        }
        return found;
    }

    /** The window's start snapshot: the newest at or before {@code from}, else the first after it (the SQL's COALESCE), or null. */
    static Snap startOf(List<Snap> oldestFirst, Instant from) {
        Snap before = lastAtOrBefore(oldestFirst, from);
        if (before != null) return before;
        for (Snap s : oldestFirst) if (s.at().isAfter(from)) return s;
        return null;
    }

    /** Each UTC day's highest total XP, oldest day first. */
    static TreeMap<LocalDate, Long> dailyMax(List<Snap> snaps) {
        TreeMap<LocalDate, Long> out = new TreeMap<>();
        for (Snap s : snaps) out.merge(s.day(), s.totalXp(), Math::max);
        return out;
    }

    static boolean inWindow(Instant at, Instant from, Instant to) {
        return (from == null || !at.isBefore(from)) && (to == null || at.isBefore(to));
    }

    static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The cache tag of one player's answers: what {@code HubChanges} drops on the feed's {@code stats.updated} and {@code player.activity}. */
    public static String playerTag(String rsn) {
        return "player:" + key(rsn);
    }

    static String key(String rsn) {
        return rsn.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** A name as a path segment: the hub reads {@code _} as a space, so the name needs no percent-encoded spaces. */
    static String path(String rsn) {
        return URLEncoder.encode(rsn.strip().replaceAll("\\s+", " ").replace(' ', '_'), StandardCharsets.UTF_8);
    }
}
