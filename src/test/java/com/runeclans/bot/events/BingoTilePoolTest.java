package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoTilePool.Difficulty;
import com.runeclans.bot.events.BingoTilePool.Entry;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A server's own bingo tile pool: the tile rules, starter packs from wiki content, and how a board is made from it. */
class BingoTilePoolTest {
    private static Entry tile(String label, Difficulty d, boolean enabled, String... tags) {
        return new Entry(label, d.defaultPoints(), 1, d, List.of(tags), enabled);
    }

    @Test
    void aTileNeedsANameAndSaneNumbersAndADifficulty() {
        assertNull(BingoTilePool.problem("Kill Nex", 25, 1, Difficulty.HARD));
        assertNotNull(BingoTilePool.problem("   ", 25, 1, Difficulty.HARD));
        assertNotNull(BingoTilePool.problem("x".repeat(BingoTilePool.MAX_LABEL + 1), 25, 1, Difficulty.HARD));
        assertNotNull(BingoTilePool.problem("Kill Nex", -1, 1, Difficulty.HARD));
        assertNotNull(BingoTilePool.problem("Kill Nex", BingoTilePool.MAX_POINTS + 1, 1, Difficulty.HARD));
        assertNotNull(BingoTilePool.problem("Kill Nex", 25, 0, Difficulty.HARD));
        assertNotNull(BingoTilePool.problem("Kill Nex", 25, 1, null));
        assertEquals("Kill Nex", BingoTilePool.cleanLabel("  Kill   Nex "));
    }

    @Test
    void tagsAreTidiedAndBounded() {
        assertEquals(List.of("pvm", "elite dungeon"), BingoTilePool.cleanTags(List.of(" PvM ", "Elite  Dungeon", "pvm", "")));
        assertNull(BingoTilePool.cleanTags(List.of("bad!tag")));
        assertNull(BingoTilePool.cleanTags(List.of("a", "b", "c", "d", "e", "f")));
        assertNull(BingoTilePool.cleanTags(List.of("x".repeat(BingoTilePool.MAX_TAG + 1))));
    }

    @Test
    void difficultiesAreParsedAnyCaseAndStartFromRisingPoints() {
        assertEquals(Difficulty.GRANDMASTER, Difficulty.parse(" Grandmaster "));
        assertNull(Difficulty.parse("impossible"));
        long last = -1;
        for (Difficulty d : Difficulty.values()) {
            assertTrue(d.defaultPoints() > last);
            last = d.defaultPoints();
        }
    }

    @Test
    void onlyEnabledTilesMatchingTheFiltersAreCopied() {
        List<Entry> pool = List.of(tile("A", Difficulty.EASY, true, "pvm"), tile("B", Difficulty.HARD, true, "pvm", "boss"), tile("C", Difficulty.HARD, false, "pvm", "boss"), tile("D", Difficulty.HARD, true, "skilling"));

        assertEquals(List.of("A", "B", "D"), BingoTilePool.select(pool, Set.of(), Set.of()).stream().map(Entry::label).toList());
        assertEquals(List.of("B", "D"), BingoTilePool.select(pool, EnumSet.of(Difficulty.HARD), Set.of()).stream().map(Entry::label).toList());
        assertEquals(List.of("B"), BingoTilePool.select(pool, EnumSet.of(Difficulty.HARD), Set.of("pvm", "boss")).stream().map(Entry::label).toList());
    }

    @Test
    void anEventKeepsACopyOfTheTilesAndRejectsDuplicatesAndBadTiles() {
        DataArray tiles = BingoTilePool.toConfig(List.of(tile("A", Difficulty.EASY, true), tile("B", Difficulty.HARD, true)));

        assertTrue(BingoTilePool.configProblems(tiles).isEmpty());
        assertEquals(2, BingoTilePool.fromConfig(tiles).size());
        assertEquals("A", BingoTilePool.fromConfig(tiles).get(0).label());

        DataArray twice = DataArray.empty().add(DataObject.empty().put("label", "A").put("points", 1).put("target", 1)).add(DataObject.empty().put("label", "a").put("points", 1).put("target", 1));
        assertFalse(BingoTilePool.configProblems(twice).isEmpty());
        DataArray bad = DataArray.empty().add(DataObject.empty().put("label", "").put("points", 1).put("target", 1)).add("not an object");
        assertEquals(2, BingoTilePool.configProblems(bad).size());
    }

    @Test
    void anEventNeverHoldsMoreThanTheLimit() {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < BingoTilePool.MAX_TILES_IN_EVENT + 50; i++) many.add(tile("Tile " + i, Difficulty.EASY, true));

        assertEquals(BingoTilePool.MAX_TILES_IN_EVENT, BingoTilePool.toConfig(many).length());
    }

}
