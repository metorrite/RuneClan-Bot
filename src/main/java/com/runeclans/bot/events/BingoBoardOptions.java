package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoardView.TileView;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The extra options of a bingo board, all stored under the event's {@code board} settings and all optional: a board that names none of them is drawn and
 * generated exactly as it was before they existed.
 * <p>
 * Drawn options (they only change the picture, never the tiles or the rules, so they can change at any time before the event is over):
 * {@code title} and {@code subtitle} replace the picture's heading; {@code fog} hides the name, picture and points of a tile that is still locked;
 * {@code names} writes who completed a tile under it (grids); {@code numbers} (on by default) is the tile number in the corner of a grid tile;
 * {@code accent} is the colour of the ticks, badges and finished-tile outlines on a grid.
 * <p>
 * Made options (they apply when the board is made or re-rolled): {@code curve} says how the points of the picked tiles are spread over the board, and
 * {@code freeCentre} turns the middle place of an odd square grid into a free space that counts as done for every line through it and for the blackout.
 */
public record BingoBoardOptions(String title, String subtitle, boolean fog, boolean names, boolean numbers, String accent, String curve, boolean freeCentre) {
    public static final int MAX_TITLE = 60;
    public static final int MAX_SUBTITLE = 90;
    /** The key of the free space: it is on the board but is not an objective, nobody submits proof for it. */
    public static final String FREE_KEY = "free-space";

    public static final String CURVE_POOL = "pool";
    public static final String CURVE_FLAT = "flat";
    public static final String CURVE_RAMP = "ramp";
    public static final List<String> CURVES = List.of(CURVE_POOL, CURVE_FLAT, CURVE_RAMP);

    public static final String ACCENT_GOLD = "gold";
    /** The accent colours of a grid: id to colour. Gold is what boards always had. */
    public static final Map<String, Color> ACCENTS = accents();

    public static final BingoBoardOptions NONE = new BingoBoardOptions("", "", false, false, true, ACCENT_GOLD, CURVE_POOL, false);

    private static Map<String, Color> accents() {
        Map<String, Color> map = new LinkedHashMap<>();
        map.put(ACCENT_GOLD, new Color(0xd4af37));
        map.put("emerald", new Color(0x3fbf7f));
        map.put("azure", new Color(0x4da3ff));
        map.put("crimson", new Color(0xe5566d));
        map.put("violet", new Color(0xa77bf0));
        return map;
    }

    public static BingoBoardOptions of(DataObject config) {
        try {
            if (config == null || !config.hasKey("board") || config.isNull("board")) return NONE;
            DataObject b = config.getObject("board");
            String accent = b.getString("accent", ACCENT_GOLD);
            String curve = b.getString("curve", CURVE_POOL);
            return new BingoBoardOptions(b.getString("title", "").strip(), b.getString("subtitle", "").strip(), b.getBoolean("fog", false), b.getBoolean("names", false),
                    b.getBoolean("numbers", true), ACCENTS.containsKey(accent) ? accent : ACCENT_GOLD, CURVES.contains(curve) ? curve : CURVE_POOL, b.getBoolean("freeCentre", false));
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    /** The keys of a board's settings that are options (kept when the board is re-rolled or a tile swapped). */
    public static final List<String> KEYS = List.of("title", "subtitle", "fog", "names", "numbers", "accent", "curve", "freeCentre");

    public Color accentColour() {
        return ACCENTS.get(accent);
    }

    /** What is wrong with the option values in a board's settings. */
    public static List<String> problems(DataObject board) {
        List<String> problems = new ArrayList<>();
        try {
            if (board.hasKey("title") && !board.isNull("title") && board.getString("title").strip().length() > MAX_TITLE) problems.add("The board title can be at most " + MAX_TITLE + " characters.");
            if (board.hasKey("subtitle") && !board.isNull("subtitle") && board.getString("subtitle").strip().length() > MAX_SUBTITLE) problems.add("The board subtitle can be at most " + MAX_SUBTITLE + " characters.");
            if (board.hasKey("accent") && !board.isNull("accent") && !ACCENTS.containsKey(board.getString("accent"))) problems.add("Choose a board accent colour from the list.");
            if (board.hasKey("curve") && !board.isNull("curve") && !CURVES.contains(board.getString("curve"))) problems.add("Choose how tile points are spread from the list.");
            for (String flag : List.of("fog", "names", "numbers", "freeCentre")) {
                if (board.hasKey(flag) && !board.isNull(flag)) board.getBoolean(flag);
            }
        } catch (RuntimeException e) {
            problems.add("The board options couldn't be read.");
        }
        return problems;
    }

    /** Copies the options of {@code from} into {@code to} (both are a board's settings). */
    public static void copy(DataObject from, DataObject to) {
        for (String key : KEYS) if (from.hasKey(key) && !from.isNull(key)) to.put(key, from.get(key));
    }

    /**
     * Fog of war: a tile that is still locked shows no name, picture or points, only its number (what a player would type for it, once it opens). Done in
     * one place, on the tiles about to be drawn, so nothing about a hidden tile can reach the picture.
     */
    public static List<TileView> fogged(List<TileView> tiles) {
        List<TileView> out = new ArrayList<>(tiles.size());
        for (TileView t : tiles) {
            out.add(t.locked() && !t.done() ? new TileView(t.number(), "?", -1, false, 0, 0, null, null, true) : t);
        }
        return out;
    }

    /**
     * Spreads the points over the tiles at the places in {@code order} (the places that are not kept as they were, listed from easiest to hardest).
     * {@code flat}: each of those tiles is worth the average of them, rounded to a multiple of 5 (at least 5). {@code ramp}: the same point values, sorted,
     * placed along {@code order}, so the board gets harder along it. {@code pool}: unchanged.
     */
    public static List<BingoBoard.Tile> spread(List<BingoBoard.Tile> tiles, String curve, List<Integer> order) {
        if (CURVE_POOL.equals(curve) || order.isEmpty()) return tiles;
        List<BingoBoard.Tile> out = new ArrayList<>(tiles);
        if (CURVE_FLAT.equals(curve)) {
            long total = order.stream().mapToLong(i -> tiles.get(i).points()).sum();
            long average = Math.max(5, Math.round(total / (double) order.size() / 5.0) * 5);
            for (int i : order) out.set(i, withPoints(tiles.get(i), average));
            return out;
        }
        List<Long> values = order.stream().map(i -> tiles.get(i).points()).sorted().toList();
        for (int k = 0; k < order.size(); k++) out.set(order.get(k), withPoints(tiles.get(order.get(k)), values.get(k)));
        return out;
    }

    private static BingoBoard.Tile withPoints(BingoBoard.Tile t, long points) {
        return new BingoBoard.Tile(t.key(), t.label(), points, t.target());
    }
}
