package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoard.Board;
import com.runeclans.bot.events.BingoBoard.Tile;
import com.runeclans.bot.events.BingoBoardView.TileView;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BingoLayoutTest {
    private final BingoTemplate template = new BingoTemplate();

    private static BingoLayout layout(String id) {
        return BingoLayout.find(id).orElseThrow();
    }

    // ---------- the shapes ----------

    @Test
    void everyShapeHasItsPlacesOnThePictureInOrder() {
        Map<String, Integer> counts = Map.of(BingoLayout.PATH, 24, BingoLayout.PYRAMID, 15, BingoLayout.CONVERGENCE, 17, BingoLayout.TREE, 16);
        for (BingoLayout l : BingoLayout.all()) {
            assertEquals(counts.get(l.id()), l.tiles(), l.id());
            assertEquals(l.tiles(), l.slots().size());
            for (int i = 0; i < l.slots().size(); i++) {
                var s = l.slots().get(i);
                assertEquals(i + 1, s.number(), l.id() + " numbered in order");
                for (int[] box : List.of(s.bounds(), s.icon(), s.name())) {
                    assertTrue(box[0] >= 0 && box[1] >= 0 && box[0] + box[2] <= 1600 && box[1] + box[3] <= 1000, l.id() + " slot " + s.number() + " is on the picture");
                }
                assertTrue(s.icon()[0] >= s.bounds()[0] && s.icon()[0] + s.icon()[2] <= s.bounds()[0] + s.bounds()[2], "the icon sits inside its tile");
            }
        }
    }

    @Test
    void theRowsCoverEveryTileOnceAndEachTileOnlyNeedsEarlierSteps() {
        for (BingoLayout l : BingoLayout.all()) {
            List<Integer> inRows = l.rows().stream().flatMap(List::stream).sorted().toList();
            assertEquals(java.util.stream.IntStream.rangeClosed(1, l.tiles()).boxed().toList(), inRows, l.id() + " rows hold every tile once");
            assertEquals(l.rows().size(), l.rowNames().size());
            for (var needs : l.requires().entrySet()) {
                assertTrue(needs.getKey() >= 1 && needs.getKey() <= l.tiles());
                for (int need : needs.getValue()) {
                    assertTrue(need >= 1 && need <= l.tiles() && need != needs.getKey(), l.id() + " tile " + needs.getKey() + " needs a real, other tile");
                    assertTrue(l.depth(need) < l.depth(needs.getKey()) || l.id().equals(BingoLayout.PATH), l.id() + ": what a tile needs is earlier in the journey");
                }
            }
        }
    }

    @Test
    void thePyramidHasFiveTiersEachTileOpensWhenTheTwoBeneathItAreDone() {
        BingoLayout pyramid = layout(BingoLayout.PYRAMID);
        assertEquals(List.of(1, 2, 3, 4, 5), pyramid.rows().stream().map(List::size).toList(), "apex to base");
        assertEquals(List.of(2, 3), pyramid.requires().get(1));
        assertEquals(List.of(4, 5), pyramid.requires().get(2));
        assertEquals(List.of(5, 6), pyramid.requires().get(3));
        assertEquals(List.of(13, 14), pyramid.requires().get(9), "tile 9 sits on tiles 13 and 14");
        assertFalse(pyramid.requires().containsKey(11), "the base needs nothing");
        assertEquals(4, pyramid.depth(1));
        assertEquals(0, pyramid.depth(15));
        assertEquals(4, pyramid.groups().size(), "every tier but the single apex earns a bonus");
    }

    @Test
    void thePathIsAChainOfTwentyFourStartingAtOne() {
        BingoLayout path = layout(BingoLayout.PATH);
        assertFalse(path.requires().containsKey(1));
        for (int n = 2; n <= 24; n++) assertEquals(List.of(n - 1), path.requires().get(n));
        assertEquals(3, path.groups().size());
    }

    @Test
    void theConvergenceFeedsTheFinalBossFromTheInnerRingFromTheOuterRing() {
        BingoLayout c = layout(BingoLayout.CONVERGENCE);
        assertEquals(List.of(1, 2, 3), c.requires().get(13));
        assertEquals(List.of(10, 11, 12), c.requires().get(16));
        assertEquals(List.of(13, 14, 15, 16), c.requires().get(17));
        assertEquals(0, c.depth(5));
        assertEquals(1, c.depth(14));
        assertEquals(2, c.depth(17));
        assertEquals(2, c.groups().size(), "the outer and the inner ring");
    }

    @Test
    void theTreeGrowsFromSevenRootsThroughBranchesAndLimbsToTheCrown() {
        BingoLayout tree = layout(BingoLayout.TREE);
        assertEquals(List.of(2, 3), tree.requires().get(1));
        assertEquals(List.of(4, 5, 6), tree.requires().get(2));
        assertEquals(List.of(10, 11), tree.requires().get(4));
        assertEquals(List.of(11, 12), tree.requires().get(5));
        assertEquals(List.of(15, 16), tree.requires().get(9));
        for (int root = 10; root <= 16; root++) assertFalse(tree.requires().containsKey(root));
        assertEquals(0, tree.depth(10));
        assertEquals(3, tree.depth(1));
    }

    // ---------- boards ----------

    private static final List<BingoBoard.TileSpec> POOL = BingoBoard.pool(BingoBoard.POOL_CLASSIC);

    @Test
    void aBoardInAShapeHasThatManyDifferentTilesAndTheSameSeedGivesTheSameBoard() {
        for (BingoLayout l : BingoLayout.all()) {
            Board a = BingoBoard.generateLayout(l, 42, POOL, null, Set.of(), 50, 250);
            Board b = BingoBoard.generateLayout(l, 42, POOL, null, Set.of(), 50, 250);
            assertEquals(l.tiles(), a.tiles().size());
            assertEquals(l.tiles(), a.tiles().stream().map(Tile::key).collect(Collectors.toSet()).size(), "no tile twice");
            assertEquals(a.tiles(), b.tiles());
            assertEquals(l.id(), a.layout());
            assertNotEquals(a.tiles(), BingoBoard.generateLayout(l, 43, POOL, null, Set.of(), 50, 250).tiles());
        }
    }

    @Test
    void theEasiestTilesAreWherePlayersStartAndTheHardestAtTheEnd() {
        for (BingoLayout l : BingoLayout.all()) {
            Board board = BingoBoard.generateLayout(l, 7, POOL, null, Set.of(), 50, 250);
            for (int a = 1; a <= l.tiles(); a++) {
                for (int b = 1; b <= l.tiles(); b++) {
                    if (l.depth(a) < l.depth(b)) {
                        assertTrue(board.tiles().get(a - 1).points() <= board.tiles().get(b - 1).points(), l.id() + ": tile " + a + " is no harder than tile " + b);
                    }
                }
            }
        }
    }

    @Test
    void lockedTilesStayWhereTheyAreWhenTheRestIsRerolled() {
        BingoLayout pyramid = layout(BingoLayout.PYRAMID);
        Board first = BingoBoard.generateLayout(pyramid, 1, POOL, null, Set.of(), 50, 250);
        Tile kept = first.tiles().get(3);
        Board second = BingoBoard.generateLayout(pyramid, 2, POOL, first.tiles(), Set.of(kept.key()), 50, 250);
        assertEquals(kept, second.tiles().get(3));
        assertNotEquals(first.tiles().get(0), second.tiles().get(0));
    }

    @Test
    void everyTierRowOrRingEarnsABonusAndTheBlackoutCoversEverything() {
        BingoLayout pyramid = layout(BingoLayout.PYRAMID);
        Board board = BingoBoard.generateLayout(pyramid, 1, POOL, null, Set.of(), 50, 250);
        assertEquals(5, board.bonuses().size(), "four tiers and the blackout");
        var base = board.bonuses().stream().filter(b -> b.name().equals("Base")).findFirst().orElseThrow();
        assertEquals(5, base.keys().size());
        assertEquals(50, base.points());
        assertEquals(15, board.bonuses().get(board.bonuses().size() - 1).keys().size());
        assertEquals(1, BingoBoard.generateLayout(pyramid, 1, POOL, null, Set.of(), 0, 250).bonuses().size(), "no tier bonus when it is 0");
    }

    // ---------- the template ----------

    private static DataObject config(String layout, String pool, String unlock) {
        DataObject board = DataObject.empty().put("layout", layout).put("pool", pool);
        if (unlock != null) board.put("unlock", unlock);
        return DataObject.empty().put("board", board);
    }

    @Test
    void aShapedEventStartsWithItsObjectivesAndBonusesAndNoSquareSize() {
        DataObject prepared = template.prepare(config(BingoLayout.PYRAMID, BingoBoard.POOL_CLASSIC, null));
        assertEquals(BingoLayout.PYRAMID, prepared.getObject("board").getString("layout"));
        assertEquals(0, prepared.getObject("board").getInt("size"));
        assertEquals(15, BingoBoardView.boardKeys(prepared).size());
        assertEquals(5, prepared.getArray("bonuses").length());
        assertEquals(15, template.starterObjectives(prepared).size());
        assertEquals(List.of(), template.configProblems(prepared));
    }

    @Test
    void whetherTilesUnlockInOrderFollowsThePoolUnlessSaid() {
        assertTrue(BingoTemplate.unlockedInOrder(config(BingoLayout.TREE, BingoBoard.POOL_CLASSIC, null)), "classic tasks can be planned");
        assertFalse(BingoTemplate.unlockedInOrder(config(BingoLayout.TREE, BingoBoard.POOL_DROPS, null)), "rare drops can't be banked");
        assertFalse(BingoTemplate.unlockedInOrder(config(BingoLayout.TREE, BingoBoard.POOL_CLASSIC, "free")));
        assertTrue(BingoTemplate.unlockedInOrder(config(BingoLayout.TREE, BingoBoard.POOL_DROPS, "locked")));
        // what was decided at the start is written into the board, so it never changes with the pool later
        assertEquals("free", template.prepare(config(BingoLayout.TREE, BingoBoard.POOL_DROPS, null)).getObject("board").getString("unlock"));
        assertEquals("locked", template.prepare(config(BingoLayout.TREE, BingoBoard.POOL_CLASSIC, null)).getObject("board").getString("unlock"));
    }

    @Test
    void lockedTilesCarryTheShapesPrerequisitesAndFreeOnesCarryNone() {
        DataObject locked = template.prepare(config(BingoLayout.PYRAMID, BingoBoard.POOL_CLASSIC, "locked"));
        List<String> keys = BingoBoardView.boardKeys(locked);
        Map<String, ObjectiveSpec> byKey = template.starterObjectives(locked).stream().collect(Collectors.toMap(ObjectiveSpec::key, o -> o));
        assertEquals(List.of(keys.get(1), keys.get(2)), byKey.get(keys.get(0)).prerequisites(), "the apex needs the two tiles of the tier below");
        assertEquals(List.of(), byKey.get(keys.get(14)).prerequisites(), "the base needs nothing");

        DataObject free = template.prepare(config(BingoLayout.PYRAMID, BingoBoard.POOL_CLASSIC, "free"));
        assertTrue(template.starterObjectives(free).stream().allMatch(o -> o.prerequisites().isEmpty()));
    }

    @Test
    void anUnknownShapeOrUnlockModeIsRefused() {
        assertFalse(template.configProblems(config("spiral", BingoBoard.POOL_CLASSIC, null)).isEmpty());
        assertFalse(template.configProblems(config(BingoLayout.TREE, BingoBoard.POOL_CLASSIC, "sometimes")).isEmpty());
    }

    @Test
    void aRerollKeepsLockedTilesAndRebuildsThePrerequisitesAroundThem() {
        DataObject prepared = template.prepare(config(BingoLayout.TREE, BingoBoard.POOL_CLASSIC, "locked"));
        List<ObjectiveSpec> current = template.starterObjectives(prepared);
        String lockedKey = BingoBoardView.boardKeys(prepared).get(10);
        var rerolled = template.reroll(prepared, current, null, 99L, Set.of(lockedKey));
        List<String> keys = BingoBoardView.boardKeys(rerolled.config());
        assertEquals(lockedKey, keys.get(10), "the locked tile did not move");
        Map<String, ObjectiveSpec> byKey = rerolled.objectives().stream().collect(Collectors.toMap(ObjectiveSpec::key, o -> o));
        // tile 4 of the tree needs tiles 10 and 11, whichever tiles those are now
        assertEquals(List.of(keys.get(9), keys.get(10)), byKey.get(keys.get(3)).prerequisites());
        assertEquals(16, rerolled.objectives().size());
    }

    @Test
    void switchingShapeBuildsADifferentBoardAndBackToTheGrid() {
        DataObject prepared = template.prepare(config(BingoLayout.PYRAMID, BingoBoard.POOL_CLASSIC, "locked"));
        var tree = template.reroll(prepared, template.starterObjectives(prepared), null, 5L, Set.of(), BingoLayout.TREE);
        assertEquals(BingoLayout.TREE, tree.config().getObject("board").getString("layout"));
        assertEquals(16, BingoBoardView.boardKeys(tree.config()).size());
        assertEquals(16, tree.objectives().size());

        var grid = template.reroll(tree.config(), tree.objectives(), 4, 5L, Set.of(), "grid");
        assertFalse(BingoTemplate.layoutOf(grid.config()).isPresent());
        assertEquals(16, BingoBoardView.boardKeys(grid.config()).size());
        assertEquals(4, grid.config().getObject("board").getInt("size"));
        assertTrue(grid.objectives().stream().allMatch(o -> o.prerequisites().isEmpty()), "a square board has no prerequisites");

        var backToGrid = template.reroll(tree.config(), tree.objectives(), null, 5L, Set.of(), "grid");
        assertEquals(BingoTemplate.DEFAULT_SIZE, backToGrid.config().getObject("board").getInt("size"), "no size given: the usual one");
        assertEquals(25, backToGrid.objectives().size());

        assertThrows(IllegalArgumentException.class, () -> template.reroll(prepared, template.starterObjectives(prepared), null, 5L, Set.of(), "spiral"));
    }

    @Test
    void replacingATileKeepsTheShapeAndRenamesWhatNeedsIt() {
        DataObject prepared = template.prepare(config(BingoLayout.PYRAMID, BingoBoard.POOL_CLASSIC, "locked"));
        List<ObjectiveSpec> current = template.starterObjectives(prepared);
        String oldKey = BingoBoardView.boardKeys(prepared).get(1);
        var replaced = template.replaceTile(prepared, current, 2, "Kill Telos five times", 80);
        assertTrue(BingoTemplate.layoutOf(replaced.config()).isPresent());
        assertEquals(5, replaced.config().getArray("bonuses").length());
        String newKey = BingoBoardView.boardKeys(replaced.config()).get(1);
        assertNotEquals(oldKey, newKey);
        ObjectiveSpec apex = replaced.objectives().stream().filter(o -> o.key().equals(BingoBoardView.boardKeys(replaced.config()).get(0))).findFirst().orElseThrow();
        assertTrue(apex.prerequisites().contains(newKey), "the apex needs the renamed tile");
        assertFalse(apex.prerequisites().contains(oldKey));
    }

    // ---------- the view and the picture ----------

    @Test
    void aTileIsLockedOnAPersonalBoardUntilWhatItNeedsIsDone() {
        DataObject prepared = template.prepare(config(BingoLayout.PYRAMID, BingoBoard.POOL_CLASSIC, "locked"));
        List<String> keys = BingoBoardView.boardKeys(prepared);
        List<ObjectiveSpec> objectives = template.starterObjectives(prepared);
        var none = BingoBoardView.personal(keys, objectives, null);
        assertTrue(none.get(0).locked(), "the apex is locked");
        assertFalse(none.get(14).locked(), "the base is open");
        var someone = new EventScoring.Standing("u:1", 0, 2, Map.of(keys.get(1), 1, keys.get(2), 1), Instant.now());
        assertFalse(BingoBoardView.personal(keys, objectives, someone).get(0).locked(), "both tiles below the apex are done");
    }

    @Test
    void whoCompletedATileFirstIsTheFirstToReachItsTarget() {
        var o = new ObjectiveSpec("a", "A", "", 1, EventRules.Evidence.EITHER, 3, 1, List.of());
        Instant t0 = Instant.parse("2026-10-16T10:00:00Z");
        List<EventScoring.Entry> log = List.of(
                new EventScoring.Entry("u:1", "a", 2, t0),
                new EventScoring.Entry("u:2", "a", 3, t0.plusSeconds(10)),
                new EventScoring.Entry("u:1", "a", 1, t0.plusSeconds(20)));
        assertEquals("u:2", BingoBoardView.firstCompleters(List.of(o), log).get("a"), "u:2 reached 3 first");
        assertTrue(BingoBoardView.firstCompleters(List.of(o), List.of(new EventScoring.Entry("u:1", "a", 2, t0))).isEmpty(), "nobody has finished it");
    }
}
