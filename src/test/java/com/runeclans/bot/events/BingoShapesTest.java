package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoardView.TileView;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Bingo shapes in sizes of their own: the rules, the geometry and the drawing. */
class BingoShapesTest {
    private static List<int[]> allSizes() {
        List<int[]> out = new ArrayList<>();
        for (var r : BingoShapes.RANGES) for (int n = r.min(); n <= r.max(); n++) out.add(new int[]{BingoShapes.RANGES.indexOf(r), n});
        return out;
    }

    @Test
    void everySizeBuildsAConsistentShape() {
        for (int[] at : allSizes()) {
            var range = BingoShapes.RANGES.get(at[0]);
            var b = BingoShapes.build(range.id(), at[1]).orElseThrow(() -> new AssertionError(range.id() + " " + at[1]));
            String what = range.id() + " " + at[1];
            int tiles = b.places().size();
            // every number 1..n appears exactly once in the rows and once in the places
            Set<Integer> inRows = new HashSet<>();
            b.rows().forEach(row -> row.forEach(n -> assertTrue(inRows.add(n), what + ": " + n + " twice in the rows")));
            assertEquals(tiles, inRows.size(), what);
            for (int n = 1; n <= tiles; n++) assertTrue(inRows.contains(n), what + ": missing " + n);
            for (int i = 0; i < tiles; i++) assertEquals(i + 1, b.places().get(i).number(), what);
            assertEquals(b.rows().size(), b.rowNames().size(), what);
            assertEquals(tiles, b.depth().length, what);
            // what a tile needs exists, is one level shallower or more, and never itself
            b.requires().forEach((n, needs) -> {
                assertFalse(needs.isEmpty(), what);
                for (int need : needs) {
                    assertTrue(need >= 1 && need <= tiles && need != n, what + ": " + n + " needs " + need);
                    assertTrue(b.depth()[need - 1] < b.depth()[n - 1], what + ": " + n + " needs " + need + " which is not shallower");
                }
            });
            // every tile that needs nothing is a place to start; there is at least one
            assertTrue(b.requires().size() < tiles, what);
            // the tiles fit on the picture and never overlap
            for (var p : b.places()) {
                assertTrue(p.x() - BingoShapes.TILE / 2.0 >= 0 && p.x() + BingoShapes.TILE / 2.0 <= b.width(), what + ": tile " + p.number() + " off the side");
                assertTrue(p.y() - BingoShapes.TILE / 2.0 >= BingoShapes.HEADER - 1 && p.y() + BingoShapes.TILE / 2.0 <= b.height() - BingoShapes.FOOTER + 1, what + ": tile " + p.number() + " off the top or bottom");
            }
            for (int i = 0; i < tiles; i++) {
                for (int j = i + 1; j < tiles; j++) {
                    var a = b.places().get(i);
                    var c = b.places().get(j);
                    boolean apart = Math.abs(a.x() - c.x()) >= BingoShapes.TILE - 1 || Math.abs(a.y() - c.y()) >= BingoShapes.TILE - 1;
                    assertTrue(apart, what + ": tiles " + a.number() + " and " + c.number() + " overlap");
                }
            }
        }
    }

    @Test
    void tileCountsFollowTheirFormulas() {
        assertEquals(10, BingoShapes.build("pyramid", 4).orElseThrow().places().size());
        assertEquals(36, BingoShapes.build("pyramid", 8).orElseThrow().places().size());
        assertEquals(31 - 16, BingoShapes.build("tree", 4).orElseThrow().places().size());
        assertEquals(7, BingoShapes.build("tree", 3).orElseThrow().places().size());
        assertEquals(1 + 4 + 12 + 20, BingoShapes.build("convergence", 3).orElseThrow().places().size());
        assertEquals(17, BingoShapes.build("convergence", 2).orElseThrow().places().size());
        assertEquals(40, BingoShapes.build("path", 40).orElseThrow().places().size());
    }

    @Test
    void theConvergenceBossNeedsTheWholeInnerRing() {
        var b = BingoShapes.build("convergence", 3).orElseThrow();
        int boss = b.places().size();
        assertTrue(b.places().get(boss - 1).round());
        assertEquals(List.of(boss - 4, boss - 3, boss - 2, boss - 1), b.requires().get(boss));
        // each inner tile needs the outer tiles in front of it, and the outermost ring needs nothing
        for (int n = 1; n <= 20; n++) assertFalse(b.requires().containsKey(n), "outer ring tile " + n);
    }

    @Test
    void sizesOutsideTheRangeAreRefused() {
        assertNull(BingoShapes.problem("pyramid", 0));
        assertNull(BingoShapes.problem("pyramid", 8));
        assertNotNull(BingoShapes.problem("pyramid", 2));
        assertNotNull(BingoShapes.problem("pyramid", 9));
        assertNotNull(BingoShapes.problem("path", 41));
        assertNotNull(BingoShapes.problem("convergence", 4));
        assertNotNull(BingoShapes.problem("tree", 5));
        assertNotNull(BingoShapes.problem("grid", 5));
        assertTrue(BingoShapes.build("pyramid", 9).isEmpty());
        assertTrue(BingoShapes.build("pyramid", 0).isEmpty());
    }

    @Test
    void thePictureSizeKeepsThePaintedPicture() {
        assertFalse(BingoLayout.of("pyramid", 5).orElseThrow().generated());
        assertFalse(BingoLayout.of("pyramid", 0).orElseThrow().generated());
        assertEquals(15, BingoLayout.of("pyramid", 5).orElseThrow().tiles());
        assertFalse(BingoLayout.of("path", 24).orElseThrow().generated());
        assertFalse(BingoLayout.of("convergence", 2).orElseThrow().generated());
        assertTrue(BingoLayout.of("pyramid", 6).orElseThrow().generated());
        assertTrue(BingoLayout.of("tree", 3).orElseThrow().generated());
        assertEquals(21, BingoLayout.of("pyramid", 6).orElseThrow().tiles());
        assertTrue(BingoLayout.of("zigzag", 5).isEmpty());
        assertTrue(BingoLayout.of("pyramid", 12).isEmpty());
    }

    @Test
    void aSizedShapeEarnsBonusesForEveryGroupAndTheBlackout() {
        var layout = BingoLayout.of("pyramid", 6).orElseThrow();
        // tiers of one tile (the apex) are tiles of their own, the other five tiers are groups
        assertEquals(5, layout.groups().size());
        List<BingoBoard.Tile> tiles = new ArrayList<>();
        for (int i = 1; i <= layout.tiles(); i++) tiles.add(new BingoBoard.Tile("t" + i, "Tile " + i, 10, 1));
        var bonuses = BingoBoard.layoutBonuses(layout, tiles, 50, 250);
        assertEquals(6, bonuses.size());
    }

    @Test
    void aBoardOfASizedShapeIsMadeAndRerolledAndKeepsItsSize() {
        BingoTemplate template = new BingoTemplate();
        DataObject prepared = template.prepare(DataObject.fromJson("{\"board\":{\"layout\":\"pyramid\",\"shapeSize\":4,\"seed\":3,\"unlock\":\"locked\"}}"));
        assertEquals(10, prepared.getObject("board").getArray("keys").length());
        assertEquals(4, prepared.getObject("board").getInt("shapeSize"));
        List<ObjectiveSpec> objectives = template.starterObjectives(prepared);
        assertEquals(10, objectives.size());
        // in order: the apex needs the two tiles beneath it
        assertEquals(2, objectives.get(0).prerequisites().size());
        assertTrue(template.configProblems(prepared).isEmpty(), template.configProblems(prepared).toString());
        // a re-roll keeps the size
        var again = template.reroll(prepared, objectives, null, 9L, Set.of());
        assertEquals(10, again.config().getObject("board").getArray("keys").length());
        assertEquals(4, again.config().getObject("board").getInt("shapeSize"));
        // a new size makes a fresh board of that size
        var bigger = template.reroll(prepared, objectives, null, null, Set.of(), null, 6);
        assertEquals(21, bigger.config().getObject("board").getArray("keys").length());
        assertEquals(21, bigger.objectives().size());
        assertEquals(6, bigger.config().getObject("board").getInt("shapeSize"));
        // back to the picture's own size, then to another shape (which drops the size) and to a grid
        var picture = template.reroll(bigger.config(), bigger.objectives(), null, null, Set.of(), null, 0);
        assertEquals(15, picture.config().getObject("board").getArray("keys").length());
        assertFalse(picture.config().getObject("board").hasKey("shapeSize"));
        var other = template.reroll(bigger.config(), bigger.objectives(), null, null, Set.of(), "path", null);
        assertEquals("path", other.config().getObject("board").getString("layout"));
        assertFalse(other.config().getObject("board").hasKey("shapeSize"));
        assertEquals(24, other.config().getObject("board").getArray("keys").length());
        var grid = template.reroll(bigger.config(), bigger.objectives(), 4, null, Set.of(), "grid", null);
        assertFalse(grid.config().getObject("board").hasKey("shapeSize"));
        assertFalse(grid.config().getObject("board").hasKey("layout"));
        assertThrows(IllegalArgumentException.class, () -> template.reroll(prepared, objectives, null, null, Set.of(), null, 12));
    }

    @Test
    void aSizeOutsideTheRangeIsAProblemInTheSettings() {
        BingoTemplate template = new BingoTemplate();
        assertFalse(template.configProblems(DataObject.fromJson("{\"board\":{\"layout\":\"pyramid\",\"shapeSize\":12}}")).isEmpty());
        assertTrue(template.configProblems(DataObject.fromJson("{\"board\":{\"layout\":\"pyramid\",\"shapeSize\":8}}")).isEmpty());
    }

    @Test
    void theSizeIsInTheSetupRegistry() {
        var option = new BingoTemplate().options().stream().filter(o -> o.path().equals("config.board.shapeSize")).findFirst().orElseThrow();
        assertEquals(0L, option.standard());
    }
}
