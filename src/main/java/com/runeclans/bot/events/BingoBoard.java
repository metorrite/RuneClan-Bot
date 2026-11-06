package com.runeclans.bot.events;

import com.runeclans.bot.events.EventBonuses.Bonus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Builds a bingo board: a square grid of tiles picked from a pool, with a bonus for every row, column and diagonal and for the whole board.
 * The same size, seed and pool always give the same board, so a board can be re-rolled by changing the seed, and tiles an organiser likes
 * can be locked in place while the rest are re-rolled. Pure, so it can be tested.
 */
public final class BingoBoard {
    public static final int MIN_SIZE = 3;
    public static final int MAX_SIZE = 7;

    /** A tile in the pool: what it asks for, how many points it is worth, and how many times the thing must happen. */
    public record TileSpec(String label, long points, long target) {}

    /** A tile on a board. */
    public record Tile(String key, String label, long points, long target) {}

    /**
     * {@code tiles} are in reading order (row by row) on a square board, and in the picture's own numbering on a shaped one ({@code layout} names it,
     * and {@code size} is then 0).
     */
    public record Board(int size, long seed, List<Tile> tiles, List<Bonus> bonuses, String layout) {
        public Board(int size, long seed, List<Tile> tiles, List<Bonus> bonuses) {
            this(size, seed, tiles, bonuses, null);
        }
    }

    private BingoBoard() {}

    public static List<String> problems(int size, int poolSize, long lineBonus, long blackoutBonus) {
        List<String> problems = new ArrayList<>();
        if (size < MIN_SIZE || size > MAX_SIZE) problems.add("A bingo board is between " + MIN_SIZE + "x" + MIN_SIZE + " and " + MAX_SIZE + "x" + MAX_SIZE + ".");
        else if (poolSize < size * size) problems.add("A " + size + "x" + size + " board needs at least " + size * size + " different tiles to pick from, and there are " + poolSize + ".");
        if (lineBonus < 0 || lineBonus > EventBonuses.MAX_POINTS) problems.add("A line bonus has to be between 0 and " + EventBonuses.MAX_POINTS + ".");
        if (blackoutBonus < 0 || blackoutBonus > EventBonuses.MAX_POINTS) problems.add("The blackout bonus has to be between 0 and " + EventBonuses.MAX_POINTS + ".");
        return problems;
    }

    /**
     * A board. {@code previous} (the current tiles in reading order, or null) and {@code lockedKeys} keep chosen tiles exactly where they are;
     * every other place gets a fresh tile from the pool, never one already on the board. A bonus of 0 is left out.
     */
    public static Board generate(int size, long seed, List<TileSpec> pool, List<Tile> previous, Set<String> lockedKeys, long lineBonus, long blackoutBonus) {
        return generate(size, seed, pool, previous, lockedKeys, lineBonus, blackoutBonus, BingoBoardOptions.NONE);
    }

    /** As above, with the board options that apply when a board is made: the free centre and how points are spread (see {@link BingoBoardOptions}). */
    public static Board generate(int size, long seed, List<TileSpec> pool, List<Tile> previous, Set<String> lockedKeys, long lineBonus, long blackoutBonus, BingoBoardOptions options) {
        List<String> problems = problems(size, pool.size(), lineBonus, blackoutBonus);
        if (!problems.isEmpty()) throw new IllegalArgumentException(problems.get(0));
        int need = size * size;
        Tile[] grid = new Tile[need];
        Set<String> used = new HashSet<>();
        int free = options.freeCentre() && size % 2 == 1 ? need / 2 : -1;
        if (previous != null) {
            for (int i = 0; i < Math.min(need, previous.size()); i++) {
                if (i != free && lockedKeys.contains(previous.get(i).key())) {
                    grid[i] = previous.get(i);
                    used.add(previous.get(i).key());
                }
            }
        }
        if (free >= 0) grid[free] = new Tile(BingoBoardOptions.FREE_KEY, "Free space", 0, 1);
        // the places that get a fresh tile now: only their points are spread by the curve, a tile kept in place keeps its points
        List<Integer> fresh = new ArrayList<>();
        for (int i = 0; i < need; i++) if (grid[i] == null) fresh.add(i);
        List<TileSpec> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, new Random(seed));
        int next = 0;
        for (int i = 0; i < need; i++) {
            if (grid[i] != null) continue;
            Tile tile = null;
            while (tile == null && next < shuffled.size()) {
                TileSpec spec = shuffled.get(next++);
                String key = EventRules.keyFrom(spec.label());
                if (!key.isEmpty() && used.add(key)) tile = new Tile(key, spec.label(), spec.points(), spec.target());
            }
            if (tile == null) throw new IllegalArgumentException("There aren't enough different tiles left to fill the board. Unlock some tiles or add more to the pool.");
            grid[i] = tile;
        }
        List<Tile> tiles = BingoBoardOptions.spread(List.of(grid), options.curve(), fresh);
        return new Board(size, seed, tiles, bonuses(size, tiles, lineBonus, blackoutBonus));
    }

    public static List<String> layoutProblems(BingoLayout layout, int poolSize, long groupBonus, long blackoutBonus) {
        List<String> problems = new ArrayList<>();
        if (poolSize < layout.tiles()) problems.add("The " + layout.name() + " board needs at least " + layout.tiles() + " different tiles to pick from, and there are " + poolSize + ".");
        if (groupBonus < 0 || groupBonus > EventBonuses.MAX_POINTS) problems.add("A tier, row or ring bonus has to be between 0 and " + EventBonuses.MAX_POINTS + ".");
        if (blackoutBonus < 0 || blackoutBonus > EventBonuses.MAX_POINTS) problems.add("The blackout bonus has to be between 0 and " + EventBonuses.MAX_POINTS + ".");
        return problems;
    }

    /**
     * A board in one of the picture shapes. Works like {@link #generate} (same seed, same board; locked tiles stay where they are), with one addition:
     * the tiles that were picked are placed easiest to hardest along the shape's journey, so the weakest tiles are where players start (the base,
     * the outer ring, the roots, the first stretch of the path) and the strongest at the end (the apex, the centre, the crown, the chest). Ties keep
     * their shuffled order. {@code previous} is in the picture's numbering.
     */
    public static Board generateLayout(BingoLayout layout, long seed, List<TileSpec> pool, List<Tile> previous, Set<String> lockedKeys, long groupBonus, long blackoutBonus) {
        return generateLayout(layout, seed, pool, previous, lockedKeys, groupBonus, blackoutBonus, BingoBoardOptions.NONE);
    }

    /** As above, with the points curve of the board options (a shape's places are already filled easiest to hardest, so {@code ramp} is what a shape always did). */
    public static Board generateLayout(BingoLayout layout, long seed, List<TileSpec> pool, List<Tile> previous, Set<String> lockedKeys, long groupBonus, long blackoutBonus,
                                       BingoBoardOptions options) {
        List<String> problems = layoutProblems(layout, pool.size(), groupBonus, blackoutBonus);
        if (!problems.isEmpty()) throw new IllegalArgumentException(problems.get(0));
        int need = layout.tiles();
        Tile[] places = new Tile[need];
        Set<String> used = new HashSet<>();
        if (previous != null) {
            for (int i = 0; i < Math.min(need, previous.size()); i++) {
                if (lockedKeys.contains(previous.get(i).key())) {
                    places[i] = previous.get(i);
                    used.add(previous.get(i).key());
                }
            }
        }
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < need; i++) if (places[i] == null) open.add(i);
        List<TileSpec> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, new Random(seed));
        List<Tile> picked = new ArrayList<>();
        int next = 0;
        while (picked.size() < open.size()) {
            Tile tile = null;
            while (tile == null && next < shuffled.size()) {
                TileSpec spec = shuffled.get(next++);
                String key = EventRules.keyFrom(spec.label());
                if (!key.isEmpty() && used.add(key)) tile = new Tile(key, spec.label(), spec.points(), spec.target());
            }
            if (tile == null) throw new IllegalArgumentException("There aren't enough different tiles left to fill the board. Unlock some tiles or add more to the pool.");
            picked.add(tile);
        }
        // easiest tiles to where the journey starts: sort the picks by points, the open places by how far along they are (both sorts are stable)
        picked.sort(java.util.Comparator.comparingLong(Tile::points));
        open.sort(java.util.Comparator.comparingInt(i -> layout.depth(i + 1)));
        for (int i = 0; i < open.size(); i++) places[open.get(i)] = picked.get(i);
        List<Tile> tiles = BingoBoardOptions.spread(List.of(places), options.curve(), open);
        return new Board(0, seed, tiles, layoutBonuses(layout, tiles, groupBonus, blackoutBonus), layout.id());
    }

    /** A bonus for every tier, row or ring of the shape and for the whole board. */
    public static List<Bonus> layoutBonuses(BingoLayout layout, List<Tile> tiles, long groupBonus, long blackoutBonus) {
        List<Bonus> bonuses = new ArrayList<>();
        if (groupBonus > 0) {
            for (BingoLayout.Group group : layout.groups()) {
                bonuses.add(new Bonus(group.name(), group.numbers().stream().map(n -> tiles.get(n - 1).key()).toList(), groupBonus));
            }
        }
        if (blackoutBonus > 0) bonuses.add(new Bonus("Blackout", tiles.stream().map(Tile::key).toList(), blackoutBonus));
        return bonuses;
    }

    /** A bonus for each row, each column, both diagonals and the whole board. */
    static List<Bonus> bonuses(int size, List<Tile> tiles, long lineBonus, long blackoutBonus) {
        List<Bonus> bonuses = new ArrayList<>();
        // the free space is done for everybody and is no objective, so it is left out of every group that would otherwise need it
        if (lineBonus > 0) {
            for (int r = 0; r < size; r++) {
                List<String> keys = new ArrayList<>();
                for (int c = 0; c < size; c++) keys.add(tiles.get(r * size + c).key());
                bonuses.add(new Bonus("Row " + (r + 1), withoutFree(keys), lineBonus));
            }
            for (int c = 0; c < size; c++) {
                List<String> keys = new ArrayList<>();
                for (int r = 0; r < size; r++) keys.add(tiles.get(r * size + c).key());
                bonuses.add(new Bonus("Column " + (c + 1), withoutFree(keys), lineBonus));
            }
            List<String> down = new ArrayList<>();
            List<String> up = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                down.add(tiles.get(i * size + i).key());
                up.add(tiles.get(i * size + (size - 1 - i)).key());
            }
            bonuses.add(new Bonus("Diagonal down", withoutFree(down), lineBonus));
            bonuses.add(new Bonus("Diagonal up", withoutFree(up), lineBonus));
        }
        if (blackoutBonus > 0) bonuses.add(new Bonus("Blackout", withoutFree(tiles.stream().map(Tile::key).toList()), blackoutBonus));
        return bonuses;
    }

    private static List<String> withoutFree(List<String> keys) {
        return keys.stream().filter(k -> !BingoBoardOptions.FREE_KEY.equals(k)).toList();
    }

    public static final String POOL_DROPS = "drops";
    public static final String POOL_CLASSIC = "classic";
    /** The server's own tiles, kept in the event itself ({@code board.tiles}); the hub keeps the pool. */
    public static final String POOL_CUSTOM = "custom";

    /** The tile pools an organiser can start a board from. */
    public static List<String> poolIds() {
        return List.of(POOL_DROPS, POOL_CLASSIC, POOL_CUSTOM);
    }

    public static List<TileSpec> pool(String id) {
        return POOL_CLASSIC.equals(id) ? defaultPool() : dropsPool();
    }

    /**
     * Boss drops: "Boss: item" tiles, the kind a drop bingo uses. The items are ones RuneMetrics announces (see {@code DropItemCatalog}); the
     * point values are a rough guess at rarity in three tiers, for organisers to edit.
     */
    public static List<TileSpec> dropsPool() {
        List<TileSpec> pool = new ArrayList<>();
        String[][] drops = {
                {"Kree'arra", "Armadyl helmet", "50"}, {"Kree'arra", "Armadyl chestplate", "50"}, {"Kree'arra", "Armadyl chainskirt", "50"}, {"Kree'arra", "Armadyl hilt", "100"},
                {"General Graardor", "Bandos chestplate", "50"}, {"General Graardor", "Bandos tassets", "50"}, {"General Graardor", "Bandos helmet", "50"},
                {"General Graardor", "Bandos boots", "25"}, {"General Graardor", "Bandos gloves", "25"}, {"General Graardor", "Bandos warshield", "50"}, {"General Graardor", "Bandos hilt", "100"},
                {"Commander Zilyana", "Saradomin sword", "100"}, {"Commander Zilyana", "Saradomin hilt", "100"},
                {"K'ril Tsutsaroth", "Zamorak hilt", "100"},
                {"Araxxi", "Araxxi's web", "25"}, {"Araxxi", "Araxxi's fang", "50"}, {"Araxxi", "Araxxi's eye", "50"},
                {"Kalphite King", "Drygore longsword", "100"}, {"Kalphite King", "Drygore mace", "100"}, {"Kalphite King", "Drygore rapier", "100"},
                {"Kalphite King", "Off-hand drygore longsword", "100"}, {"Kalphite King", "Off-hand drygore mace", "100"}, {"Kalphite King", "Off-hand drygore rapier", "100"},
                {"Nex", "Torva full helm", "100"}, {"Nex", "Torva platebody", "100"}, {"Nex", "Torva platelegs", "100"}, {"Nex", "Torva boots", "50"}, {"Nex", "Torva gloves", "50"},
                {"Nex", "Virtus mask", "100"}, {"Nex", "Virtus robe top", "100"}, {"Nex", "Virtus robe legs", "100"}, {"Nex", "Virtus boots", "50"}, {"Nex", "Virtus gloves", "50"},
                {"Nex", "Virtus wand", "100"}, {"Nex", "Virtus book", "100"},
                {"Nex", "Pernix cowl", "100"}, {"Nex", "Pernix body", "100"}, {"Nex", "Pernix chaps", "100"}, {"Nex", "Pernix boots", "50"}, {"Nex", "Pernix gloves", "50"},
                {"Vorago", "Ascension grips", "100"},
                {"Telos", "Dormant anima core helm", "100"}, {"Telos", "Dormant anima core body", "100"}, {"Telos", "Dormant anima core legs", "100"},
                {"Godwars Dungeon", "Godsword shard 1", "25"}, {"Godwars Dungeon", "Godsword shard 2", "25"}, {"Godwars Dungeon", "Godsword shard 3", "25"},
                {"Abyssal demon", "Abyssal whip", "25"}, {"Dark beast", "Dark bow", "50"},
        };
        for (String[] d : drops) pool.add(new TileSpec(d[0] + ": " + d[1], Long.parseLong(d[2]), 1));
        return pool;
    }

    /** Placeholder tiles to start from: RuneScape 3 bosses and goals in three difficulty tiers. Organisers edit, lock and swap them; real clans' own tile lists replace this. */
    public static List<TileSpec> defaultPool() {
        List<TileSpec> pool = new ArrayList<>();
        for (String boss : new String[]{"Giant Mole", "Barrows chest", "King Black Dragon", "Queen Black Dragon", "Corporeal Beast", "Chaos Elemental", "Dagannoth Kings", "General Graardor", "Commander Zilyana", "Kree'arra", "K'ril Tsutsaroth", "Kalphite Queen"}) {
            pool.add(new TileSpec("Kill " + boss, 10, 1));
        }
        for (String boss : new String[]{"Vorago", "Araxxor", "Telos", "Nex", "Kalphite King", "Legiones", "Rasial", "Gregorovic", "Helwyr", "Hermod", "Vindicta", "Solak", "Zamorak, Lord of Chaos", "Barrows: Rise of the Six"}) {
            pool.add(new TileSpec("Kill " + boss, 25, 1));
        }
        for (String boss : new String[]{"Nex: Angel of Death", "Vorago (hard mode)", "Telos (enrage 500%)", "Raksha", "Solak (enrage 500%)", "The Magister", "Croesus"}) {
            pool.add(new TileSpec("Kill " + boss, 50, 1));
        }
        for (String goal : new String[]{"Complete a Slayer task", "Get a clue scroll reward", "Complete 5 Daily Challenges", "Make 100 potions", "Mine 200 ore", "Burn 200 logs", "Catch 200 fish", "Fletch 500 arrows", "Craft 100 items", "Pickpocket 100 times"}) {
            pool.add(new TileSpec(goal, 10, 1));
        }
        for (String goal : new String[]{"Get a pet drop", "Receive a unique from any boss", "Reach a new 99", "Complete an elite clue scroll", "Finish a Treasure Hunter prize", "Get a rare drop worth 10M gp or more"}) {
            pool.add(new TileSpec(goal, 25, 1));
        }
        return pool;
    }
}
