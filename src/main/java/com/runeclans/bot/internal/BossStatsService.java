package com.runeclans.bot.internal;

import com.runeclans.bot.internal.SiteStatsRepository.ActivityRow;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.tracking.DropItemCatalog;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Boss kills and drops for the website's PvM pages, over any time window the site asks for.
 * <p>
 * Everything comes from the adventure-log lines the bot already stores. A kill line names its boss; a drop line
 * ("I found a Torva platelegs") does not, so each drop is attributed to a boss using the wiki catalogue: if only one
 * boss can drop the item that is the answer, and if several can, the one that member killed most in the days around
 * the drop wins (falling back to the boss the clan has killed most). Whatever the log doesn't report stays at zero,
 * so richer data later fills in the same pages without any change here.
 */
@BService
public class BossStatsService {
    private static final int LIST_LIMIT = 500;
    private static final long ATTRIBUTION_DAYS_BEFORE = 7;
    private static final long ATTRIBUTION_DAYS_AFTER = 1;

    private final SiteStatsRepository stats;
    private final MemberProfileRepository profiles;
    private final WikiCatalog catalog;

    public BossStatsService(SiteStatsRepository stats, MemberProfileRepository profiles, WikiCatalog catalog) {
        this.stats = stats;
        this.profiles = profiles;
        this.catalog = catalog;
    }

    // ---------- gathering ----------

    private record BossRef(String key, String name, String image) {}

    private record Kill(String rsn, BossRef boss, int kills, OffsetDateTime at) {}

    private record DropEvent(String rsn, String itemName, String itemKey, BossRef boss, String date, OffsetDateTime at) {}

    private record Gathered(RecapPeriod period, List<Kill> kills, List<DropEvent> drops) {}

    private RecapPeriod period(long guildId, String token) {
        String t = token == null || token.isBlank() ? "all" : token;
        return RecapPeriod.parse(t, OffsetDateTime.now(ZoneOffset.UTC), stats.firstSnapshotAt(guildId)).orElse(null);
    }

    private BossRef bossRef(String rawName) {
        var known = catalog.canonical(rawName);
        if (known.isPresent()) return new BossRef(known.get().key(), known.get().name(), known.get().image());
        String clean = rawName.isEmpty() ? rawName : Character.toUpperCase(rawName.charAt(0)) + rawName.substring(1);
        return new BossRef(WikiCatalog.slug(rawName), clean, null);
    }

    private Gathered gather(long guildId, String periodToken) {
        RecapPeriod period = period(guildId, periodToken);
        if (period == null) return null;

        Set<String> hidden = profiles.hiddenRsns(guildId, false);
        List<Kill> kills = new ArrayList<>();
        List<ActivityRow> dropRows = new ArrayList<>();
        for (ActivityRow row : stats.activitiesBetween(guildId, null, period.from(), period.to())) {
            if (hidden.contains(row.rsn().toLowerCase(Locale.ROOT))) continue;
            var kind = ActivityKinds.kindOf(row.type(), row.text());
            var boss = kind == ActivityKinds.Kind.BOSS ? ActivityKinds.bossOf(row.text()) : java.util.Optional.<String>empty();
            if (boss.isPresent()) {
                kills.add(new Kill(row.rsn(), bossRef(boss.get()), ActivityKinds.killCount(row.text()), row.recordedAt()));
            } else if (kind == ActivityKinds.Kind.DROP) {
                dropRows.add(row);
            }
        }

        List<DropEvent> drops = new ArrayList<>();
        for (ActivityRow row : dropRows) {
            String item = ActivityKinds.dropOf(row.text()).orElseThrow();
            String itemKey = WikiCatalog.slug(item);
            drops.add(new DropEvent(row.rsn(), item, itemKey, attribute(itemKey, row.rsn(), row.recordedAt(), kills), row.date(), row.recordedAt()));
        }
        drops.sort(Comparator.comparing(DropEvent::at).reversed());
        return new Gathered(period, kills, drops);
    }

    /** Which boss a drop most likely came from; null when no catalogued boss can drop it. */
    private BossRef attribute(String itemKey, String rsn, OffsetDateTime at, List<Kill> kills) {
        List<WikiCatalog.Boss> candidates = catalog.bossesWithItem(itemKey);
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return ref(candidates.get(0));

        OffsetDateTime from = at.minusDays(ATTRIBUTION_DAYS_BEFORE);
        OffsetDateTime to = at.plusDays(ATTRIBUTION_DAYS_AFTER);
        WikiCatalog.Boss best = null;
        long bestKills = 0;
        for (WikiCatalog.Boss candidate : candidates) {
            long near = kills.stream().filter(k -> k.rsn().equalsIgnoreCase(rsn) && k.boss().key().equals(candidate.key()) && !k.at().isBefore(from) && !k.at().isAfter(to))
                    .mapToLong(Kill::kills).sum();
            if (near > bestKills) {
                best = candidate;
                bestKills = near;
            }
        }
        if (best == null) {
            for (WikiCatalog.Boss candidate : candidates) {
                long clan = kills.stream().filter(k -> k.boss().key().equals(candidate.key())).mapToLong(Kill::kills).sum();
                if (clan > bestKills) {
                    best = candidate;
                    bestKills = clan;
                }
            }
        }
        return ref(best != null ? best : candidates.get(0));
    }

    private static BossRef ref(WikiCatalog.Boss boss) {
        return new BossRef(boss.key(), boss.name(), boss.image());
    }

    // ---------- JSON helpers ----------

    private static DataObject periodJson(RecapPeriod p) {
        return DataObject.empty().put("token", p.token()).put("label", p.label()).put("from", p.from().toString()).put("to", p.to().toString()).put("toDate", p.toDate());
    }

    private String iconFor(String itemKey) {
        var item = catalog.item(itemKey);
        if (item.isPresent() && item.get().icon() != null) return item.get().icon();
        return catalog.tracked(itemKey) ? "/items/" + itemKey + ".png" : null;
    }

    private DataObject bossJson(BossRef boss) {
        return boss == null ? null : DataObject.empty().put("key", boss.key()).put("name", boss.name()).put("image", boss.image());
    }

    private DataObject eventJson(DropEvent e) {
        return DataObject.empty().put("rsn", e.rsn()).put("item", e.itemName()).put("key", e.itemKey()).put("icon", iconFor(e.itemKey()))
                .put("boss", bossJson(e.boss())).put("date", e.date()).put("occurredAt", SiteApi.occurredAt(e.date())).put("recordedAt", e.at().toString());
    }

    private DataArray eventsJson(List<DropEvent> events) {
        DataArray out = DataArray.empty();
        events.stream().limit(LIST_LIMIT).forEach(e -> out.add(eventJson(e)));
        return out;
    }

    /** One grid square: the item, how often the clan has received it in the window, who has, and who got it first (the rare drops wall). */
    private DataObject gridCell(String itemKey, String name, String quantity, String rarity, List<DropEvent> events) {
        Map<String, Integer> byPlayer = new HashMap<>();
        OffsetDateTime last = null;
        DropEvent first = null;
        for (DropEvent e : events) {
            byPlayer.merge(e.rsn(), 1, Integer::sum);
            if (last == null || e.at().isAfter(last)) last = e.at();
            if (first == null || e.at().isBefore(first.at())) first = e;
        }
        DataArray top = DataArray.empty();
        byPlayer.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(5)
                .forEach(en -> top.add(DataObject.empty().put("rsn", en.getKey()).put("count", en.getValue())));
        return DataObject.empty().put("item", name).put("key", itemKey).put("icon", iconFor(itemKey)).put("quantity", quantity).put("rarity", rarity)
                .put("tracked", catalog.tracked(itemKey)).put("count", events.size()).put("receivers", byPlayer.size()).put("last", last == null ? null : last.toString()).put("top", top)
                .put("first", first == null ? null : first.at().toString()).put("firstRsn", first == null ? null : first.rsn());
    }

    private static Map<String, List<DropEvent>> byItem(List<DropEvent> events) {
        Map<String, List<DropEvent>> out = new HashMap<>();
        for (DropEvent e : events) out.computeIfAbsent(e.itemKey(), k -> new ArrayList<>()).add(e);
        return out;
    }

    private static int cmpCell(DataObject a, DataObject b) {
        int byCount = Integer.compare(b.getInt("count"), a.getInt("count"));
        return byCount != 0 ? byCount : a.getString("item").compareToIgnoreCase(b.getString("item"));
    }

    // ---------- routes ----------

    /** Every boss with its kills, players and drops over the window — the page that lists them all. */
    public DataObject bosses(long guildId, String periodToken) {
        Gathered g = gather(guildId, periodToken);
        if (g == null) return null;

        Map<String, BossRef> refs = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> killsByBoss = new HashMap<>();
        for (Kill k : g.kills()) {
            refs.putIfAbsent(k.boss().key(), k.boss());
            killsByBoss.computeIfAbsent(k.boss().key(), x -> new HashMap<>()).merge(k.rsn(), k.kills(), Integer::sum);
        }
        Map<String, Integer> dropsByBoss = new HashMap<>();
        for (DropEvent e : g.drops()) if (e.boss() != null) dropsByBoss.merge(e.boss().key(), 1, Integer::sum);
        for (WikiCatalog.Boss b : catalog.bosses()) refs.putIfAbsent(b.key(), ref(b));

        List<DataObject> rows = new ArrayList<>();
        for (BossRef ref : refs.values()) {
            Map<String, Integer> players = killsByBoss.getOrDefault(ref.key(), Map.of());
            int kills = players.values().stream().mapToInt(Integer::intValue).sum();
            var catalogued = catalog.byKey(ref.key());
            var top = players.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.<String, Integer>comparingByKey().reversed()));
            rows.add(DataObject.empty().put("key", ref.key()).put("name", ref.name()).put("image", ref.image()).put("catalogued", catalogued.isPresent())
                    .put("kills", kills).put("players", players.size()).put("drops", dropsByBoss.getOrDefault(ref.key(), 0))
                    .put("tableSize", catalogued.map(b -> (int) b.drops().stream().filter(d -> !d.guaranteed()).count()).orElse(0))
                    .put("topKiller", top.isEmpty() ? null : DataObject.empty().put("rsn", top.get().getKey()).put("kills", top.get().getValue())));
        }
        rows.sort(Comparator.comparingInt((DataObject r) -> r.getInt("kills")).reversed().thenComparing(r -> r.getString("name"), String.CASE_INSENSITIVE_ORDER));

        DataArray bosses = DataArray.empty();
        rows.forEach(bosses::add);
        long totalKills = g.kills().stream().mapToLong(Kill::kills).sum();
        Set<String> killers = new HashSet<>();
        g.kills().forEach(k -> killers.add(k.rsn().toLowerCase(Locale.ROOT)));
        return DataObject.empty().put("period", periodJson(g.period())).put("bosses", bosses)
                .put("totals", DataObject.empty().put("kills", totalKills).put("drops", g.drops().size()).put("players", killers.size()).put("bosses", killsByBoss.size()));
    }

    /** One boss in full: its picture, everyone's kills and drops, the drop-table grid, and the drop list. */
    public DataObject boss(long guildId, String bossKey, String periodToken) {
        Gathered g = gather(guildId, periodToken);
        if (g == null || bossKey == null) return null;

        var catalogued = catalog.byKey(bossKey);
        BossRef ref = catalogued.map(BossStatsService::ref).orElse(null);
        Map<String, Integer> killsByPlayer = new HashMap<>();
        TreeMap<String, Integer> killsByDay = new TreeMap<>();
        for (Kill k : g.kills()) {
            if (!k.boss().key().equals(bossKey)) continue;
            if (ref == null) ref = k.boss();
            killsByPlayer.merge(k.rsn(), k.kills(), Integer::sum);
            killsByDay.merge(k.at().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().toString(), k.kills(), Integer::sum);
        }
        if (ref == null) return null;

        List<DropEvent> events = g.drops().stream().filter(e -> e.boss() != null && e.boss().key().equals(bossKey)).toList();
        Map<String, Integer> dropsByPlayer = new HashMap<>();
        events.forEach(e -> dropsByPlayer.merge(e.rsn(), 1, Integer::sum));

        Set<String> names = new HashSet<>(killsByPlayer.keySet());
        names.addAll(dropsByPlayer.keySet());
        DataArray players = DataArray.empty();
        names.stream().sorted(Comparator.comparingInt((String n) -> killsByPlayer.getOrDefault(n, 0)).reversed().thenComparing(Comparator.comparingInt((String n) -> dropsByPlayer.getOrDefault(n, 0)).reversed()).thenComparing(String.CASE_INSENSITIVE_ORDER))
                .forEach(n -> players.add(DataObject.empty().put("rsn", n).put("kills", killsByPlayer.getOrDefault(n, 0)).put("drops", dropsByPlayer.getOrDefault(n, 0))));

        Map<String, List<DropEvent>> grouped = byItem(events);
        List<DataObject> cells = new ArrayList<>();
        Set<String> inTable = new HashSet<>();
        catalogued.ifPresent(b -> b.drops().stream().filter(d -> !d.guaranteed()).forEach(d -> {
            inTable.add(d.key());
            cells.add(gridCell(d.key(), d.item(), d.quantity(), d.rarity(), grouped.getOrDefault(d.key(), List.of())));
        }));
        grouped.forEach((key, list) -> {
            if (!inTable.contains(key)) cells.add(gridCell(key, list.get(0).itemName(), "", "", list));
        });
        cells.sort(BossStatsService::cmpCell);
        DataArray grid = DataArray.empty();
        cells.forEach(grid::add);

        DataArray byDay = DataArray.empty();
        killsByDay.forEach((d, n) -> byDay.add(DataObject.empty().put("date", d).put("kills", n)));
        return DataObject.empty().put("period", periodJson(g.period()))
                .put("boss", DataObject.empty().put("key", ref.key()).put("name", ref.name()).put("image", ref.image()).put("catalogued", catalogued.isPresent())
                        .put("wikiPage", catalogued.map(WikiCatalog.Boss::wikiPage).orElse(null)))
                .put("kills", killsByPlayer.values().stream().mapToInt(Integer::intValue).sum()).put("drops", events.size())
                .put("players", players).put("killsByDay", byDay).put("grid", grid).put("list", eventsJson(events));
    }

    /** One item in full: how often, who got it, when, and which bosses it comes from. */
    public DataObject item(long guildId, String itemKey, String periodToken, String bossKey) {
        Gathered g = gather(guildId, periodToken);
        if (g == null || itemKey == null) return null;

        List<DropEvent> events = g.drops().stream().filter(e -> e.itemKey().equals(itemKey) && (bossKey == null || bossKey.isBlank() || (e.boss() != null && e.boss().key().equals(bossKey)))).toList();
        var catalogued = catalog.item(itemKey);
        String name = catalogued.map(WikiCatalog.Drop::item).orElseGet(() -> DropItemCatalog.all().stream().filter(i -> WikiCatalog.slug(i.name()).equals(itemKey)).map(DropItemCatalog.DropItem::name).findFirst().orElse(null));
        if (name == null) return null;

        Map<String, Integer> byPlayer = new HashMap<>();
        Map<String, OffsetDateTime> firstAt = new HashMap<>();
        Map<String, OffsetDateTime> lastAt = new HashMap<>();
        for (DropEvent e : events) {
            byPlayer.merge(e.rsn(), 1, Integer::sum);
            firstAt.merge(e.rsn(), e.at(), (a, b) -> a.isBefore(b) ? a : b);
            lastAt.merge(e.rsn(), e.at(), (a, b) -> a.isAfter(b) ? a : b);
        }
        DataArray receivers = DataArray.empty();
        byPlayer.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)))
                .forEach(en -> receivers.add(DataObject.empty().put("rsn", en.getKey()).put("count", en.getValue()).put("first", firstAt.get(en.getKey()).toString()).put("last", lastAt.get(en.getKey()).toString())));

        Map<String, Integer> byBoss = new HashMap<>();
        for (DropEvent e : g.drops()) if (e.itemKey().equals(itemKey) && e.boss() != null) byBoss.merge(e.boss().key(), 1, Integer::sum);
        DataArray bosses = DataArray.empty();
        for (WikiCatalog.Boss b : catalog.bossesWithItem(itemKey)) {
            String rarity = b.drops().stream().filter(d -> d.key().equals(itemKey)).map(WikiCatalog.Drop::rarity).findFirst().orElse("");
            bosses.add(DataObject.empty().put("key", b.key()).put("name", b.name()).put("image", b.image()).put("rarity", rarity).put("count", byBoss.getOrDefault(b.key(), 0)));
        }
        DataArray byMonth = DataArray.empty();
        TreeMap<String, Integer> months = new TreeMap<>();
        for (DropEvent e : events) months.merge(e.at().withOffsetSameInstant(ZoneOffset.UTC).toString().substring(0, 7), 1, Integer::sum);
        months.forEach((m, n) -> byMonth.add(DataObject.empty().put("month", m).put("count", n)));

        return DataObject.empty().put("period", periodJson(g.period()))
                .put("item", DataObject.empty().put("key", itemKey).put("name", name).put("icon", iconFor(itemKey)).put("tracked", catalog.tracked(itemKey))
                        .put("rarity", catalogued.map(WikiCatalog.Drop::rarity).orElse("")).put("quantity", catalogued.map(WikiCatalog.Drop::quantity).orElse("")))
                .put("total", events.size()).put("receivers", receivers).put("bosses", bosses).put("byMonth", byMonth).put("list", eventsJson(events));
    }

    /** The drop log: a grid of every item (greyed at zero) over a list of the actual drops, for chosen bosses and a window. */
    public DataObject drops(long guildId, String periodToken, String bossesCsv) {
        Gathered g = gather(guildId, periodToken);
        if (g == null) return null;

        Set<String> selected = new HashSet<>();
        if (bossesCsv != null) for (String s : bossesCsv.split(",")) if (!s.isBlank()) selected.add(s.trim());
        List<DropEvent> events = selected.isEmpty() ? g.drops() : g.drops().stream().filter(e -> e.boss() != null && selected.contains(e.boss().key())).toList();
        Map<String, List<DropEvent>> grouped = byItem(events);

        Map<String, WikiCatalog.Drop> table = new LinkedHashMap<>();
        for (WikiCatalog.Boss b : catalog.bosses()) {
            if (!selected.isEmpty() && !selected.contains(b.key())) continue;
            for (WikiCatalog.Drop d : b.drops()) if (!d.guaranteed()) table.putIfAbsent(d.key(), d);
        }
        List<DataObject> cells = new ArrayList<>();
        table.forEach((key, d) -> cells.add(gridCell(key, d.item(), d.quantity(), d.rarity(), grouped.getOrDefault(key, List.of()))));
        grouped.forEach((key, list) -> {
            if (!table.containsKey(key)) cells.add(gridCell(key, list.get(0).itemName(), "", "", list));
        });
        cells.sort(BossStatsService::cmpCell);
        DataArray grid = DataArray.empty();
        cells.forEach(grid::add);

        DataArray options = DataArray.empty();
        Set<String> seen = new HashSet<>();
        for (WikiCatalog.Boss b : catalog.bosses()) {
            seen.add(b.key());
            options.add(DataObject.empty().put("key", b.key()).put("name", b.name()));
        }
        Set<String> receivers = new HashSet<>();
        events.forEach(e -> receivers.add(e.rsn().toLowerCase(Locale.ROOT)));
        return DataObject.empty().put("period", periodJson(g.period())).put("bosses", options)
                .put("total", events.size()).put("uniqueItems", grouped.size()).put("receivers", receivers.size())
                .put("grid", grid).put("list", eventsJson(events));
    }
}
