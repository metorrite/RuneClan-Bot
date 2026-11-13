package com.runeclans.bot.internal;

import com.runeclans.bot.tracking.DropItemCatalog;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The boss and drop-table reference the website's boss pages are built on, loaded from
 * {@code catalog/bosses.json} — a file {@code tools/wiki_sync.py} generates from the official RuneScape Wiki
 * (every boss's full drop table, its picture, and every item's icon, all downloaded into our own repositories).
 * <p>
 * Two jobs: tell the many ways the adventure log spells a boss ("Vindicta", "Gate of Elidinis", "Croesus'")
 * apart from the boss they mean, and say which bosses can drop a given item. Both are needed because a drop line
 * ("I found a Torva platelegs") never says where it came from.
 * <p>
 * The catalogue is deliberately ahead of the data: it lists whole drop tables, while the adventure log only
 * ever reports RuneScape's short "notable drops" list. Anything the log doesn't report simply stays at zero
 * until it does — the site's structure doesn't need to change if richer data turns up.
 */
@BService
public class WikiCatalog {
    private static final Logger log = LoggerFactory.getLogger(WikiCatalog.class);

    public record Drop(String item, String key, String quantity, String rarity, String icon) {
        /** Guaranteed drops (bones, seals) aren't "drops" in the drop-log sense, so the grid leaves them out. */
        public boolean guaranteed() {
            return rarity != null && rarity.equalsIgnoreCase("always");
        }
    }

    public record Boss(String key, String name, String wikiPage, List<String> aliases, String image, List<Drop> drops) {}

    private final List<Boss> bosses;
    private final Map<String, Boss> byKey = new LinkedHashMap<>();
    private final Map<String, Boss> byAlias = new HashMap<>();
    private final Map<String, List<Boss>> bossesByItem = new HashMap<>();
    private final Map<String, Drop> itemsByKey = new LinkedHashMap<>();
    private final Set<String> trackedItemKeys = new HashSet<>();

    public WikiCatalog() {
        this.bosses = load();
        for (Boss boss : bosses) {
            byKey.put(boss.key(), boss);
            for (String alias : boss.aliases()) byAlias.put(normalise(alias), boss);
            byAlias.put(normalise(boss.name()), boss);
            for (Drop drop : boss.drops()) {
                itemsByKey.putIfAbsent(drop.key(), drop);
                if (!drop.guaranteed()) bossesByItem.computeIfAbsent(drop.key(), k -> new ArrayList<>()).add(boss);
            }
        }
        // The items the adventure log actually reports: the only ones that can ever be non-zero today.
        for (String name : trackedNames()) trackedItemKeys.add(slug(name));
        log.info("Boss catalogue loaded: {} bosses, {} distinct items, {} tracked by the Adventurer's Log", bosses.size(), itemsByKey.size(), trackedItemKeys.size());
    }

    private static List<String> trackedNames() {
        List<String> names = new ArrayList<>();
        // DropItemCatalog has no public list accessor beyond lookup, so ask it about every catalogued item name.
        for (DropItemCatalog.DropItem item : DropItemCatalog.all()) names.add(item.name());
        return names;
    }

    private static List<Boss> load() {
        try (InputStream in = WikiCatalog.class.getResourceAsStream("/catalog/bosses.json")) {
            if (in == null) {
                log.warn("catalog/bosses.json is missing; boss pages will have no drop tables or pictures (run tools/wiki_sync.py)");
                return List.of();
            }
            DataArray array = DataObject.fromJson(in).getArray("bosses");
            List<Boss> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                DataObject b = array.getObject(i);
                List<String> aliases = new ArrayList<>();
                b.getArray("aliases").forEach(a -> aliases.add(String.valueOf(a)));
                List<Drop> drops = new ArrayList<>();
                DataArray d = b.getArray("drops");
                for (int j = 0; j < d.length(); j++) {
                    DataObject x = d.getObject(j);
                    drops.add(new Drop(x.getString("item"), x.getString("key"), x.getString("quantity", ""), x.getString("rarity", ""), x.isNull("icon") ? null : x.getString("icon")));
                }
                out.add(new Boss(b.getString("key"), b.getString("name"), b.isNull("wikiPage") ? null : b.getString("wikiPage"), List.copyOf(aliases),
                        b.isNull("image") ? null : b.getString("image"), List.copyOf(drops)));
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            log.error("Could not read catalog/bosses.json", e);
            return List.of();
        }
    }

    public List<Boss> bosses() {
        return bosses;
    }

    public Optional<Boss> byKey(String key) {
        return Optional.ofNullable(key == null ? null : byKey.get(key));
    }

    /** The catalogued boss an adventure-log name means, however the log spelled it. */
    public Optional<Boss> canonical(String rawName) {
        return Optional.ofNullable(rawName == null ? null : byAlias.get(normalise(rawName)));
    }

    /** Bosses whose (non-guaranteed) drop tables include this item. */
    public List<Boss> bossesWithItem(String itemKey) {
        return bossesByItem.getOrDefault(itemKey, List.of());
    }

    public Optional<Drop> item(String itemKey) {
        return Optional.ofNullable(itemsByKey.get(itemKey));
    }

    /** True when the adventure log is known to report this item (so a zero means "not yet", not "can't be seen"). */
    public boolean tracked(String itemKey) {
        return trackedItemKeys.contains(itemKey);
    }

    /** "K'ril Tsutsaroth" → "krIl_tsutsaroth"-style stable key: lower case, apostrophes dropped, runs of anything else become one underscore. */
    public static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "").replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** The comparison form of a boss name: no case, no apostrophes or leading "the", single spaces. */
    static String normalise(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "").replaceAll("\\s+", " ").trim();
        return n.startsWith("the ") ? n.substring(4) : n;
    }
}
