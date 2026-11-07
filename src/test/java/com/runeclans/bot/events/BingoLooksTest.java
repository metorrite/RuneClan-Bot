package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoardView.TileView;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BingoLooksTest {
    private final BingoTemplate template = new BingoTemplate();

    private static DataObject config(String layout, Integer size, String look) {
        DataObject board = DataObject.empty().put("pool", BingoBoard.POOL_CLASSIC).put("seed", 5);
        if (layout != null) board.put("layout", layout);
        if (size != null) board.put("size", size);
        if (look != null) board.put("look", look);
        return DataObject.empty().put("board", board);
    }

    private static List<TileView> tiles(int count) {
        List<TileView> tiles = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            boolean done = i % 3 == 1;
            tiles.add(new TileView(i, i % 2 == 0 ? "Nex: Torva boots" : "Complete a Slayer task", 25 + (i % 4) * 25, done, done ? 2 : 0, 6, null, done ? "Metorrite" : null, !done && i % 4 == 0));
        }
        return tiles;
    }

    // ---------- the registry and the setting ----------

    @Test
    void theTwoLooksAreListedWithNamesAndTheNewDefaultIsOneOfThem() {
        assertEquals(List.of("classic", "runescape"), BingoLooks.ids());
        assertEquals("Template", BingoLooks.label("classic"));
        assertEquals("RuneScape", BingoLooks.label("runescape"));
        assertTrue(BingoLooks.valid(BingoLooks.DEFAULT_FOR_NEW));
        assertTrue(BingoLooks.all().stream().allMatch(l -> !l.blurb().isBlank()));
        assertFalse(BingoLooks.valid("neon"));
        assertFalse(BingoLooks.valid(null));
    }

    @Test
    void aBoardNamesItsLookInItsSettingsOrHasNone() {
        assertEquals("runescape", BingoLooks.of(config(null, 3, "runescape")).orElseThrow());
        assertTrue(BingoLooks.of(config(null, 3, null)).isEmpty(), "no look named: the board keeps how it was drawn");
        assertTrue(BingoLooks.of(config(null, 3, "neon")).isEmpty(), "an unknown look is treated as none");
        assertTrue(BingoLooks.of(DataObject.empty()).isEmpty());
        assertTrue(BingoLooks.of(DataObject.fromJson("{\"board\":{\"look\":5}}")).isEmpty(), "a look that isn't text is treated as none");
    }

    @Test
    void anUnknownLookIsRefusedButAMissingOneIsFine() {
        assertEquals(List.of(), template.configProblems(config(null, 3, null)));
        assertEquals(List.of(), template.configProblems(config(null, 3, "runescape")));
        assertEquals(List.of(), template.configProblems(config(BingoLayout.PATH, null, "classic")));
        assertFalse(template.configProblems(config(null, 3, "neon")).isEmpty());
    }

    @Test
    void theLookSurvivesRerollingChangingShapeAndReplacingATile() {
        DataObject prepared = template.prepare(config(BingoLayout.PYRAMID, null, "runescape"));
        assertEquals("runescape", prepared.getObject("board").getString("look"), "a new board keeps the look it was made with");
        var rerolled = template.reroll(prepared, template.starterObjectives(prepared), null, 9L, Set.of());
        assertEquals("runescape", rerolled.config().getObject("board").getString("look"));
        var tree = template.reroll(rerolled.config(), rerolled.objectives(), null, 5L, Set.of(), BingoLayout.TREE);
        assertEquals("runescape", tree.config().getObject("board").getString("look"), "a new shape keeps the look");
        var grid = template.reroll(tree.config(), tree.objectives(), 4, 5L, Set.of(), "grid");
        assertEquals("runescape", grid.config().getObject("board").getString("look"), "back to a grid keeps it too");
        var replaced = template.replaceTile(grid.config(), grid.objectives(), 2, "Kill Telos five times", 80);
        assertEquals("runescape", replaced.config().getObject("board").getString("look"), "swapping a tile keeps it");
        // a board without a look stays without one
        DataObject plain = template.prepare(config(null, 3, null));
        assertFalse(plain.getObject("board").hasKey("look"));
        assertFalse(template.reroll(plain, template.starterObjectives(plain), null, 2L, Set.of()).config().getObject("board").hasKey("look"));
    }

    // ---------- the shapes ----------

    // ---------- the grid ----------
}
