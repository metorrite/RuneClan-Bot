package com.runeclans.bot.events;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A bingo board that isn't a square grid: a picture with a fixed number of tile places (the four shapes in {@code resources/bingo}), and the
 * rules that come with the shape. Each shape says which tiles must be done before another opens (its prerequisites), which groups of tiles earn a
 * bonus when finished (a tier, a row, a ring), how the tiles are laid out in rows for the dashboard, and how hard each place is meant to be (so the
 * strongest tiles go to the summit, the chest, the centre, the crown). Tiles are numbered as drawn on the picture; the number is what players type to
 * submit proof. Pure apart from reading the picture and its coordinates, so every rule can be tested.
 */
public final class BingoLayout {
    public static final String PATH = "path";
    public static final String PYRAMID = "pyramid";
    public static final String CONVERGENCE = "convergence";
    public static final String TREE = "tree";

    /** A board's look: the picture the tiles are drawn on (see {@link BingoLooks}). */
    public static final String LOOK_CLASSIC = BingoLooks.CLASSIC;
    public static final String LOOK_RUNESCAPE = BingoLooks.RUNESCAPE;

    public static List<String> looks() {
        return BingoLooks.ids();
    }

    /** One place on the picture. {@code bounds}, {@code icon} and {@code name} are {@code [x, y, width, height]} in picture pixels; {@code round} marks the centre circle. */
    public record Slot(int number, int[] bounds, int[] icon, int[] name, boolean round) {}

    /** A group of tiles that earns a bonus when all are done (a tier, a row, a ring). */
    public record Group(String name, List<Integer> numbers) {}

    private final String id;
    private final String name;
    private final String blurb;
    private final String rule;
    private final String image;
    private final List<Slot> slots;
    private final List<List<Integer>> rows;
    private final List<String> rowNames;
    private final Map<Integer, List<Integer>> requires;
    private final int[] depth;
    /** For a shape in a size of its own: the geometry it is drawn from. Null for the four painted pictures. */
    private final BingoShapes.Built built;

    private BingoLayout(String id, String name, String blurb, String rule, String image, List<Slot> slots, List<List<Integer>> rows, List<String> rowNames,
                        Map<Integer, List<Integer>> requires, int[] depth) {
        this(id, name, blurb, rule, image, slots, rows, rowNames, requires, depth, null);
    }

    private BingoLayout(String id, String name, String blurb, String rule, String image, List<Slot> slots, List<List<Integer>> rows, List<String> rowNames,
                        Map<Integer, List<Integer>> requires, int[] depth, BingoShapes.Built built) {
        this.id = id;
        this.name = name;
        this.blurb = blurb;
        this.rule = rule;
        this.image = image;
        this.slots = slots;
        this.rows = rows;
        this.rowNames = rowNames;
        this.requires = requires;
        this.depth = depth;
        this.built = built;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** One line on what the shape is. */
    public String blurb() {
        return blurb;
    }

    /** One or two sentences on how tiles open up, for organisers and players. */
    public String rule() {
        return rule;
    }

    public int tiles() {
        return slots.size();
    }

    /** True for a shape drawn in code in a size of its own (see {@link BingoShapes}); false for the four painted pictures. */
    public boolean generated() {
        return built != null;
    }

    /** The size a generated shape was made at (pyramid base, path length, rings, levels), or 0 for a painted picture. */
    public int sizeParam() {
        return built == null ? 0 : built.size();
    }

    /** The width of the picture a generated shape is drawn on. */
    public int canvasWidth() {
        return built.width();
    }

    public int canvasHeight() {
        return built.height();
    }

    /** The places of a generated shape as points (centres), in tile order; used to draw the paths between tiles. */
    public List<BingoShapes.Place> places() {
        return built == null ? List.of() : built.places();
    }

    public List<Slot> slots() {
        return slots;
    }

    /** The tile numbers in rows from the top of the picture down (a pyramid's apex first), for the dashboard and the text board. */
    public List<List<Integer>> rows() {
        return rows;
    }

    public List<String> rowNames() {
        return rowNames;
    }

    /** Tile number to the numbers of the tiles that must be done first. Tiles that need nothing are absent. */
    public Map<Integer, List<Integer>> requires() {
        return requires;
    }

    /** How far along the shape's journey a tile is, 0 for where players start; the higher the harder. */
    public int depth(int number) {
        return depth[number - 1];
    }

    /** Groups of tiles that earn a bonus when finished: every row, tier or ring but the one-tile ones, which are tiles of their own. */
    public List<Group> groups() {
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).size() > 1) groups.add(new Group(rowNames.get(i), rows.get(i)));
        }
        return groups;
    }

    // ---------- the shapes ----------

    private static List<BingoLayout> all;

    public static synchronized List<BingoLayout> all() {
        if (all == null) all = List.of(path(), pyramid(), convergence(), tree());
        return all;
    }

    /**
     * The shape {@code id} in a size ({@link BingoShapes}). A size of 0, or the size the painted picture has, is the picture itself; any other size in the
     * shape's range is drawn in code. Empty for an unknown shape or a size outside the range.
     */
    public static Optional<BingoLayout> of(String id, int size) {
        Optional<BingoLayout> picture = find(id);
        if (picture.isEmpty() || size == 0) return picture;
        var range = BingoShapes.range(id);
        if (range.isPresent() && range.get().picture() == size) return picture;
        return BingoShapes.build(id, size).map(BingoLayout::fromBuilt);
    }

    private static BingoLayout fromBuilt(BingoShapes.Built b) {
        List<Slot> slots = new ArrayList<>();
        for (BingoShapes.Place pl : b.places()) {
            int x = (int) Math.round(pl.x());
            int y = (int) Math.round(pl.y());
            int h = BingoShapes.TILE / 2;
            slots.add(new Slot(pl.number(), new int[]{x - h, y - h, BingoShapes.TILE, BingoShapes.TILE}, new int[]{x - h + 14, y - h + 10, 82, 70}, new int[]{x - h + 7, y - h + 86, 96, 19}, pl.round()));
        }
        return new BingoLayout(b.id(), b.name(), b.blurb(), b.rule(), null, List.copyOf(slots), b.rows(), b.rowNames(), b.requires(), b.depth(), b);
    }

    public static Optional<BingoLayout> find(String id) {
        if (id == null) return Optional.empty();
        return all().stream().filter(l -> l.id.equals(id)).findFirst();
    }

    public static List<String> ids() {
        return all().stream().map(BingoLayout::id).toList();
    }

    private static List<Slot> slotsOf(String layoutId) {
        try (InputStream in = BingoLayout.class.getResourceAsStream("/bingo/layouts.json")) {
            if (in == null) throw new IllegalStateException("The board layouts are missing.");
            DataArray array = DataObject.fromJson(in).getObject("layouts").getObject(layoutId).getArray("slots");
            List<Slot> slots = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                DataObject s = array.getObject(i);
                slots.add(new Slot(s.getInt("n"), box(s.getArray("bounds")), box(s.getArray("icon")), box(s.getArray("name")), s.getBoolean("round", false)));
            }
            return List.copyOf(slots);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int[] box(DataArray array) {
        return new int[]{array.getInt(0), array.getInt(1), array.getInt(2), array.getInt(3)};
    }

    private static List<Integer> range(int from, int to) {
        List<Integer> list = new ArrayList<>();
        for (int i = from; i <= to; i++) list.add(i);
        return list;
    }

    /** The adventurer's path: 24 tiles along a trail in three stretches. Each tile opens when the one before it is done. */
    private static BingoLayout path() {
        List<List<Integer>> rows = List.of(range(1, 8), range(9, 16), range(17, 24));
        Map<Integer, List<Integer>> requires = new LinkedHashMap<>();
        for (int n = 2; n <= 24; n++) requires.put(n, List.of(n - 1));
        int[] depth = new int[24];
        for (int n = 1; n <= 24; n++) depth[n - 1] = n - 1;
        return new BingoLayout(PATH, "The Adventurer's Path", "24 tiles along a winding trail, from the start to the treasure chest.",
                "Follow the trail in order: a tile opens once the tile before it is done. Finishing a stretch of eight earns a bonus.",
                "path.png", slotsOf(PATH), rows, List.of("First stretch (1-8)", "Second stretch (9-16)", "Final stretch (17-24)"), requires, depth);
    }

    /** The pyramid: five tiers, five tiles at the base and one fewer each step up to the apex. A tile opens when the two tiles under it are done. */
    private static BingoLayout pyramid() {
        List<List<Integer>> rows = List.of(List.of(1), List.of(2, 3), List.of(4, 5, 6), range(7, 10), range(11, 15));
        Map<Integer, List<Integer>> requires = new LinkedHashMap<>();
        for (int tier = 0; tier < rows.size() - 1; tier++) {
            List<Integer> above = rows.get(tier);
            List<Integer> below = rows.get(tier + 1);
            for (int p = 0; p < above.size(); p++) requires.put(above.get(p), List.of(below.get(p), below.get(p + 1)));
        }
        int[] depth = new int[15];
        for (int tier = 0; tier < rows.size(); tier++) for (int n : rows.get(tier)) depth[n - 1] = rows.size() - 1 - tier;
        return new BingoLayout(PYRAMID, "Pyramid of the Pharaohs", "15 tiles in five tiers: five at the base, one fewer each step up, one at the apex.",
                "Build upwards: a tile opens once the two tiles beneath it are done. Finishing a whole tier earns a bonus.",
                "pyramid.png", slotsOf(PYRAMID), rows, List.of("Apex", "Tier 4", "Tier 3", "Tier 2", "Base"), requires, depth);
    }

    /** The convergence: twelve tiles on the outer ring, four on the inner ring, and the final boss in the centre. */
    private static BingoLayout convergence() {
        List<List<Integer>> rows = List.of(List.of(17), range(13, 16), range(1, 12));
        Map<Integer, List<Integer>> requires = new LinkedHashMap<>();
        // each inner tile needs the three outer tiles clockwise from the top in its quarter, and the final boss needs all four inner tiles
        for (int q = 0; q < 4; q++) requires.put(13 + q, List.of(3 * q + 1, 3 * q + 2, 3 * q + 3));
        requires.put(17, range(13, 16));
        int[] depth = new int[17];
        for (int n = 13; n <= 16; n++) depth[n - 1] = 1;
        depth[16] = 2;
        return new BingoLayout(CONVERGENCE, "The Boss Convergence", "12 tiles on the outer ring, 4 on the inner ring, and the final boss at the centre.",
                "Work inwards: each inner tile opens once the three outer tiles of its quarter are done, and the final boss opens once all four inner tiles are done. "
                        + "Finishing the outer or the inner ring earns a bonus.",
                "convergence.png", slotsOf(CONVERGENCE), rows, List.of("Final boss", "Inner ring", "Outer ring"), requires, depth);
    }

    /** The world tree: seven roots, six branches, two limbs and the crown. Each tile opens when every tile that feeds it is done. */
    private static BingoLayout tree() {
        List<List<Integer>> rows = List.of(List.of(1), List.of(2, 3), range(4, 9), range(10, 16));
        Map<Integer, List<Integer>> requires = new LinkedHashMap<>();
        requires.put(1, List.of(2, 3));
        requires.put(2, List.of(4, 5, 6));
        requires.put(3, List.of(7, 8, 9));
        // each branch grows from two neighbouring roots, so every root but the ends feeds two branches
        for (int branch = 4; branch <= 9; branch++) requires.put(branch, List.of(branch + 6, branch + 7));
        int[] depth = new int[16];
        for (int n = 4; n <= 9; n++) depth[n - 1] = 1;
        depth[1] = 2;
        depth[2] = 2;
        depth[0] = 3;
        return new BingoLayout(TREE, "The World Tree", "16 tiles from seven roots up through branches and limbs to the crown.",
                "Grow upwards: a tile opens once every tile that feeds it from below is done, from the roots to the crown. Finishing the roots, the branches or the limbs earns a bonus.",
                "tree.png", slotsOf(TREE), rows, List.of("Crown", "Limbs", "Branches", "Roots"), requires, depth);
    }
}
