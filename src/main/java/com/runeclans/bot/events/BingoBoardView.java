package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventScoring.Standing;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Turns an event's board, objectives and standings into the tiles to draw. Pure. */
public final class BingoBoardView {
    /** One tile as a board shows it: its number, label and points, whether it is done, how many subjects have it (shared boards), its icon, who had it first, and whether it is still locked. */
    public record TileView(int number, String label, long points, boolean done, int count, int of, String iconKey, String by, boolean locked) {
        public TileView(int number, String label, long points, boolean done, int count, int of) {
            this(number, label, points, done, count, of, null, null, false);
        }

        public TileView(int number, String label, long points, boolean done, int count, int of, String iconKey) {
            this(number, label, points, done, count, of, iconKey, null, false);
        }
    }

    private BingoBoardView() {}

    /** The board's objective keys in reading order, or an empty list when the event has no board. */
    public static List<String> boardKeys(DataObject config) {
        List<String> keys = new ArrayList<>();
        try {
            if (config.hasKey("board") && !config.isNull("board") && config.getObject("board").hasKey("keys")) {
                DataArray array = config.getObject("board").getArray("keys");
                for (int i = 0; i < array.length(); i++) keys.add(array.getString(i));
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return keys;
    }

    public static int boardSize(DataObject config) {
        try {
            return config.getObject("board").getInt("size", 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * What a tile number means: the nth tile of the board when the event has one, otherwise the nth objective. Null when there is no such tile.
     * Numbers start at 1, as drawn on the board.
     */
    public static String keyForNumber(DataObject config, List<ObjectiveSpec> objectives, int number) {
        List<String> board = boardKeys(config);
        List<String> keys = board.isEmpty() ? objectives.stream().map(ObjectiveSpec::key).toList() : board;
        return number >= 1 && number <= keys.size() ? keys.get(number - 1) : null;
    }

    /** The shared board: each tile counts how many of the event's {@code subjects} have completed it. */
    public static List<TileView> shared(List<String> keys, List<ObjectiveSpec> objectives, List<Standing> standings, int subjects) {
        return shared(keys, objectives, standings, subjects, label -> null);
    }

    /** As {@link #shared(List, List, List, int)}, with a picture for tiles whose label {@code iconOf} knows (it may return null). */
    public static List<TileView> shared(List<String> keys, List<ObjectiveSpec> objectives, List<Standing> standings, int subjects, Function<String, String> iconOf) {
        return shared(keys, objectives, standings, subjects, iconOf, Map.of());
    }

    /** As above, and {@code firstBy} names, per objective key, who completed it first (shown under a finished tile on the picture shapes). */
    public static List<TileView> shared(List<String> keys, List<ObjectiveSpec> objectives, List<Standing> standings, int subjects, Function<String, String> iconOf,
                                        Map<String, String> firstBy) {
        Map<String, ObjectiveSpec> byKey = byKey(objectives);
        List<TileView> tiles = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (BingoBoardOptions.FREE_KEY.equals(key)) {
                tiles.add(freeSpace(i + 1));
                continue;
            }
            ObjectiveSpec o = byKey.get(key);
            int count = (int) standings.stream().filter(s -> s.completions().getOrDefault(key, 0) > 0).count();
            String label = o == null ? key : o.label();
            tiles.add(new TileView(i + 1, label, o == null ? 0 : o.points(), count > 0, count, Math.max(subjects, count), iconOf.apply(label), firstBy.get(key), false));
        }
        return tiles;
    }

    /**
     * Who completed each objective first: walks the progress log in time order, adding up each subject's progress per objective, and the first to
     * reach the objective's target wins the key. Subjects are as in {@link EventScoring.Entry}; the caller turns them into names. Pure.
     */
    public static Map<String, String> firstCompleters(List<ObjectiveSpec> objectives, List<EventScoring.Entry> log) {
        Map<String, ObjectiveSpec> byKey = byKey(objectives);
        Map<String, Map<String, Long>> totals = new HashMap<>();
        Map<String, String> first = new HashMap<>();
        for (EventScoring.Entry e : log.stream().sorted(java.util.Comparator.comparing(EventScoring.Entry::at)).toList()) {
            ObjectiveSpec o = byKey.get(e.objectiveKey());
            if (o == null) continue;
            long total = Math.max(0, totals.computeIfAbsent(e.subject(), k -> new HashMap<>()).merge(o.key(), e.amount(), Long::sum));
            totals.get(e.subject()).put(o.key(), total);
            if (!first.containsKey(o.key()) && EventScoring.completions(o, total) >= 1) first.put(o.key(), e.subject());
        }
        return first;
    }

    /** One player's or team's board: a tile is done when they have completed it. {@code mine} may be null for someone with no progress. */
    public static List<TileView> personal(List<String> keys, List<ObjectiveSpec> objectives, Standing mine) {
        return personal(keys, objectives, mine, label -> null);
    }

    public static List<TileView> personal(List<String> keys, List<ObjectiveSpec> objectives, Standing mine, Function<String, String> iconOf) {
        Map<String, ObjectiveSpec> byKey = byKey(objectives);
        Map<String, Integer> done = mine == null ? Map.of() : mine.completions();
        List<TileView> tiles = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            if (BingoBoardOptions.FREE_KEY.equals(keys.get(i))) {
                tiles.add(freeSpace(i + 1));
                continue;
            }
            ObjectiveSpec o = byKey.get(keys.get(i));
            String label = o == null ? keys.get(i) : o.label();
            // a tile that needs others first is dimmed until they are done
            boolean locked = o != null && o.prerequisites().stream().anyMatch(p -> done.getOrDefault(p, 0) < 1);
            tiles.add(new TileView(i + 1, label, o == null ? 0 : o.points(), done.getOrDefault(keys.get(i), 0) > 0, 0, 0, iconOf.apply(label), null, locked));
        }
        return tiles;
    }

    /** The free space of a grid with a free centre: done for everybody, worth nothing, nothing to send proof for. */
    private static TileView freeSpace(int number) {
        return new TileView(number, "FREE", 0, true, 0, 0, null, null, false);
    }

    private static Map<String, ObjectiveSpec> byKey(List<ObjectiveSpec> objectives) {
        Map<String, ObjectiveSpec> map = new HashMap<>();
        for (ObjectiveSpec o : objectives) map.put(o.key(), o);
        return map;
    }
}
