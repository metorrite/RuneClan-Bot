package com.runeclans.bot.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bingo shapes in sizes of the organiser's choosing, drawn in code rather than on a fixed picture: a pyramid of any base from 3 to 8, a path of 6 to 40
 * tiles, a convergence of 1 to 3 rings around the boss, and a world tree of 3 or 4 levels. The rules are the ones the picture shapes have (see
 * {@link BingoLayout}): a tile opens when the tiles it rests on are done, and every tier, stretch, ring or level earns a bonus. Each shape also has a
 * <i>picture size</i> (pyramid base 5, path 24, convergence 2 rings): at that size the board keeps drawing on the painted picture, so a board that doesn't
 * ask for a size looks as it always did. Everything here is pure geometry and rules, so it can be tested without drawing anything.
 * <p>
 * Tiles are numbered as the picture shapes number them: top to bottom, left to right for the pyramid and the tree (the apex or crown is 1), from the start for the path,
 * and from the outermost ring in to the boss in the middle for the convergence.
 */
public final class BingoShapes {
    private BingoShapes() {}

    /** The side of one tile, and the space between tiles, in picture pixels. */
    static final int TILE = 110;
    static final int GAP = 34;
    /** Room left around the shape inside the picture (the title is above, in the backdrop's header). */
    private static final int MARGIN = 80;
    /** Where the title block ends and the board starts, and the room under the board, in picture pixels (the hub draws the picture, T-941; the geometry stays). */
    static final int HEADER = 190;
    static final int FOOTER = 116;

    /** What a shape can be sized to. {@code picture} is the size that keeps the painted picture, or 0 when the shape has none to keep (the tree). */
    public record Range(String id, String what, int min, int max, int picture) {}

    public static final List<Range> RANGES = List.of(
            new Range(BingoLayout.PYRAMID, "tiles along the base", 3, 8, 5),
            new Range(BingoLayout.PATH, "tiles on the trail", 6, 40, 24),
            new Range(BingoLayout.CONVERGENCE, "rings around the boss", 1, 3, 2),
            new Range(BingoLayout.TREE, "levels", 3, 4, 0));

    public static Optional<Range> range(String id) {
        return RANGES.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** What is wrong with asking for {@code size} of the shape {@code id}, or null when it is fine (0 means the picture's own size). */
    public static String problem(String id, int size) {
        if (size == 0) return null;
        Range r = range(id).orElse(null);
        if (r == null) return "That shape has no size to change.";
        if (size < r.min() || size > r.max()) return "A " + id + " can have " + r.min() + " to " + r.max() + " " + r.what() + ".";
        return null;
    }

    /** A place on the picture: its centre, and whether it is the round boss place in the middle of a convergence. */
    public record Place(int number, double x, double y, boolean round) {}

    /** A shape in a size. {@code rows} are tile numbers from the top of the picture down; {@code requires} maps a tile to the tiles that must be done first. */
    public record Built(String id, int size, int width, int height, List<Place> places, List<List<Integer>> rows, List<String> rowNames, Map<Integer, List<Integer>> requires,
                        int[] depth, String name, String blurb, String rule) {}

    /** The shape at that size, or empty when the size is outside what the shape can be. */
    public static Optional<Built> build(String id, int size) {
        if (problem(id, size) != null || size == 0) return Optional.empty();
        return Optional.of(switch (id) {
            case BingoLayout.PYRAMID -> pyramid(size);
            case BingoLayout.PATH -> path(size);
            case BingoLayout.CONVERGENCE -> convergence(size);
            default -> tree(size);
        });
    }

    private static List<Integer> range(int from, int to) {
        List<Integer> list = new ArrayList<>();
        for (int i = from; i <= to; i++) list.add(i);
        return list;
    }

    // ---------- pyramid ----------

    private static Built pyramid(int base) {
        List<List<Integer>> rows = new ArrayList<>();
        int next = 1;
        for (int tier = 0; tier < base; tier++) {
            rows.add(range(next, next + tier));
            next += tier + 1;
        }
        Map<Integer, List<Integer>> requires = new LinkedHashMap<>();
        for (int tier = 0; tier < base - 1; tier++) {
            List<Integer> above = rows.get(tier);
            List<Integer> below = rows.get(tier + 1);
            for (int p = 0; p < above.size(); p++) requires.put(above.get(p), List.of(below.get(p), below.get(p + 1)));
        }
        int total = next - 1;
        int[] depth = new int[total];
        List<String> names = new ArrayList<>();
        for (int tier = 0; tier < base; tier++) {
            for (int n : rows.get(tier)) depth[n - 1] = base - 1 - tier;
            names.add(tier == 0 ? "Apex" : tier == base - 1 ? "Base" : "Tier " + (base - tier));
        }
        int width = Math.max(760, base * (TILE + GAP) - GAP + 2 * MARGIN);
        int height = HEADER + base * (TILE + GAP) - GAP + FOOTER;
        List<Place> places = new ArrayList<>();
        for (int tier = 0; tier < base; tier++) {
            List<Integer> row = rows.get(tier);
            double rowWidth = row.size() * (TILE + GAP) - GAP;
            for (int p = 0; p < row.size(); p++) {
                places.add(new Place(row.get(p), (width - rowWidth) / 2 + p * (TILE + GAP) + TILE / 2.0, HEADER + tier * (TILE + GAP) + TILE / 2.0, false));
            }
        }
        places.sort((a, b) -> Integer.compare(a.number(), b.number()));
        return new Built(BingoLayout.PYRAMID, base, width, height, places, rows, names, requires, depth, "Pyramid of " + base + " tiers",
                total + " tiles in " + base + " tiers: " + base + " at the base, one fewer each step up, one at the apex.",
                "Build upwards: a tile opens once the two tiles beneath it are done. Finishing a whole tier earns a bonus.");
    }

    // ---------- path ----------

    /** Tiles per row of the winding trail. */
    private static final int PER_ROW = 6;
    private static final int STRETCH = 8;

    private static Built path(int tiles) {
        Map<Integer, List<Integer>> requires = new LinkedHashMap<>();
        for (int n = 2; n <= tiles; n++) requires.put(n, List.of(n - 1));
        int[] depth = new int[tiles];
        for (int n = 1; n <= tiles; n++) depth[n - 1] = n - 1;
        List<List<Integer>> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int stretches = (tiles + STRETCH - 1) / STRETCH;
        for (int s = 0; s < stretches; s++) {
            int from = s * STRETCH + 1;
            int to = Math.min(tiles, from + STRETCH - 1);
            rows.add(range(from, to));
            names.add(stretches == 1 ? "The trail" : "Stretch " + (s + 1) + " (" + from + "-" + to + ")");
        }
        int lines = (tiles + PER_ROW - 1) / PER_ROW;
        int width = Math.max(760, PER_ROW * (TILE + GAP) - GAP + 2 * MARGIN);
        int height = HEADER + lines * (TILE + GAP) - GAP + FOOTER;
        List<Place> places = new ArrayList<>();
        for (int n = 1; n <= tiles; n++) {
            int line = (n - 1) / PER_ROW;
            int col = (n - 1) % PER_ROW;
            // the trail winds: every other row runs back the other way
            int slot = line % 2 == 0 ? col : PER_ROW - 1 - col;
            double rowWidth = PER_ROW * (TILE + GAP) - GAP;
            places.add(new Place(n, (width - rowWidth) / 2 + slot * (TILE + GAP) + TILE / 2.0, HEADER + line * (TILE + GAP) + TILE / 2.0, false));
        }
        // rows for the dashboard run from the top of the picture, which is where the trail starts, so they are the stretches in order
        return new Built(BingoLayout.PATH, tiles, width, height, places, rows, names, requires, depth, "A path of " + tiles + " tiles",
                tiles + " tiles along a winding trail, from the start to the end.",
                "Follow the trail in order: a tile opens once the tile before it is done. Finishing a stretch of eight earns a bonus.");
    }

    // ---------- convergence ----------

    private static final int RING_STEP = 170;

    /** Tiles on ring {@code m} (1 is the innermost): 4, 12, 20 ... */
    static int ringTiles(int m) {
        return 4 * (2 * m - 1);
    }

    private static Built convergence(int rings) {
        // numbering: the outermost ring first, then inwards, then the boss at the centre
        int[] first = new int[rings + 2];
        int next = 1;
        for (int m = rings; m >= 1; m--) {
            first[m] = next;
            next += ringTiles(m);
        }
        int centre = next;
        List<List<Integer>> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        rows.add(List.of(centre));
        names.add("Final boss");
        for (int m = 1; m <= rings; m++) {
            rows.add(range(first[m], first[m] + ringTiles(m) - 1));
            names.add(rings == 1 ? "The ring" : m == 1 ? "Inner ring" : m == rings ? "Outer ring" : "Ring " + (rings - m + 1));
        }
        Map<Integer, List<Integer>> requires = new LinkedHashMap<>();
        for (int m = 1; m <= rings; m++) {
            int c = ringTiles(m);
            for (int i = 0; i < c; i++) {
                int tile = first[m] + i;
                if (m == rings) continue; // the outer ring needs nothing
                // a tile needs the tiles of the next ring out that lie in its arc
                int outer = ringTiles(m + 1);
                List<Integer> needs = new ArrayList<>();
                for (int j = 0; j < outer; j++) {
                    double lo = (double) j / outer;
                    double hi = (double) (j + 1) / outer;
                    double from = (double) i / c;
                    double to = (double) (i + 1) / c;
                    if (Math.min(hi, to) - Math.max(lo, from) > 1e-9) needs.add(first[m + 1] + j);
                }
                requires.put(tile, needs);
            }
        }
        requires.put(centre, range(first[1], first[1] + ringTiles(1) - 1));
        int[] depth = new int[centre];
        for (int m = 1; m <= rings; m++) for (int i = 0; i < ringTiles(m); i++) depth[first[m] + i - 1] = rings - m;
        depth[centre - 1] = rings;
        int radius = rings * RING_STEP;
        int side = 2 * (radius + TILE / 2) + 2 * MARGIN;
        int width = Math.max(760, side);
        int height = HEADER + side - 2 * MARGIN + FOOTER;
        double cx = width / 2.0;
        double cy = HEADER + radius + TILE / 2.0;
        List<Place> places = new ArrayList<>();
        for (int m = 1; m <= rings; m++) {
            int c = ringTiles(m);
            for (int i = 0; i < c; i++) {
                // clockwise from the top, the first tile just right of straight up
                double angle = -Math.PI / 2 + 2 * Math.PI * (i + 0.5) / c;
                places.add(new Place(first[m] + i, cx + m * RING_STEP * Math.cos(angle), cy + m * RING_STEP * Math.sin(angle), false));
            }
        }
        places.add(new Place(centre, cx, cy, true));
        places.sort((a, b) -> Integer.compare(a.number(), b.number()));
        return new Built(BingoLayout.CONVERGENCE, rings, width, height, places, rows, names, requires, depth, "Convergence of " + rings + (rings == 1 ? " ring" : " rings"),
                centre + " tiles: " + rings + (rings == 1 ? " ring" : " rings") + " around the final boss at the centre.",
                "Work inwards: a tile opens once the tiles of the ring outside it that lie in front of it are done, and the final boss opens once the whole inner ring is done. "
                        + "Finishing a ring earns a bonus.");
    }

    // ---------- tree ----------

    private static Built tree(int levels) {
        // levels of 1, 2, 4, 8 tiles from the crown down to the roots
        List<List<Integer>> rows = new ArrayList<>();
        int next = 1;
        for (int level = 0; level < levels; level++) {
            int count = 1 << level;
            rows.add(range(next, next + count - 1));
            next += count;
        }
        Map<Integer, List<Integer>> requires = new LinkedHashMap<>();
        for (int level = 0; level < levels - 1; level++) {
            List<Integer> above = rows.get(level);
            List<Integer> below = rows.get(level + 1);
            for (int p = 0; p < above.size(); p++) requires.put(above.get(p), List.of(below.get(2 * p), below.get(2 * p + 1)));
        }
        int total = next - 1;
        int[] depth = new int[total];
        List<String> names = new ArrayList<>();
        for (int level = 0; level < levels; level++) {
            for (int n : rows.get(level)) depth[n - 1] = levels - 1 - level;
            names.add(level == 0 ? "Crown" : level == levels - 1 ? "Roots" : levels - level == 2 ? "Branches" : "Limbs");
        }
        int widest = 1 << (levels - 1);
        int width = Math.max(760, widest * (TILE + GAP) - GAP + 2 * MARGIN);
        int height = HEADER + levels * (TILE + GAP) - GAP + FOOTER;
        List<Place> places = new ArrayList<>();
        for (int level = 0; level < levels; level++) {
            List<Integer> row = rows.get(level);
            // each tile sits above its two children, so the roots set the spacing
            double slot = (double) widest / row.size();
            for (int p = 0; p < row.size(); p++) {
                double x = (width - (widest * (TILE + GAP) - GAP)) / 2 + (p * slot + slot / 2) * (TILE + GAP) - GAP / 2.0;
                places.add(new Place(row.get(p), x, HEADER + level * (TILE + GAP) + TILE / 2.0, false));
            }
        }
        places.sort((a, b) -> Integer.compare(a.number(), b.number()));
        return new Built(BingoLayout.TREE, levels, width, height, places, rows, names, requires, depth, "A world tree of " + levels + " levels",
                total + " tiles from " + widest + " roots up through " + (levels - 2) + (levels == 3 ? " level" : " levels") + " of branches to the crown.",
                "Grow upwards: a tile opens once the two tiles that feed it from below are done, from the roots to the crown. Finishing a level earns a bonus.");
    }
}
