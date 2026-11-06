package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoard.Board;
import com.runeclans.bot.events.BingoBoardView.TileView;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The extra bingo board options (title, fog, names, numbers, accent, points curve, free centre). */
class BingoOptionsTest {
    private static final List<BingoBoard.TileSpec> POOL = BingoBoard.dropsPool();

    private static BingoBoardOptions opts(String json) {
        return BingoBoardOptions.of(DataObject.fromJson("{\"board\":" + json + "}"));
    }

    @Test
    void aBoardThatNamesNoOptionsHasTheOriginalOnes() {
        assertEquals(BingoBoardOptions.NONE, BingoBoardOptions.of(DataObject.fromJson("{}")));
        assertEquals(BingoBoardOptions.NONE, opts("{\"size\":5}"));
        assertTrue(BingoBoardOptions.NONE.numbers());
        assertFalse(BingoBoardOptions.NONE.fog());
    }

    @Test
    void unknownValuesFallBackAndAreReported() {
        var o = opts("{\"accent\":\"pink\",\"curve\":\"zigzag\"}");
        assertEquals("gold", o.accent());
        assertEquals("pool", o.curve());
        assertEquals(2, BingoBoardOptions.problems(DataObject.fromJson("{\"accent\":\"pink\",\"curve\":\"zigzag\"}")).size());
        assertFalse(BingoBoardOptions.problems(DataObject.fromJson("{\"title\":\"" + "x".repeat(61) + "\"}")).isEmpty());
        assertTrue(BingoBoardOptions.problems(DataObject.fromJson("{\"title\":\"Winter Bingo\",\"fog\":true,\"accent\":\"azure\"}")).isEmpty());
    }

    @Test
    void fogHidesAClosedTileButKeepsItsNumber() {
        var tiles = List.of(new TileView(1, "Nex: Torva helm", 100, false, 0, 0, "icon", null, true),
                new TileView(2, "Nex: Virtus wand", 100, false, 0, 0, "icon", null, false),
                new TileView(3, "Kree'arra: Armadyl hilt", 100, true, 0, 0, "icon", null, true));
        var fogged = BingoBoardOptions.fogged(tiles);
        assertEquals(1, fogged.get(0).number());
        assertEquals("?", fogged.get(0).label());
        assertEquals(-1, fogged.get(0).points());
        assertNull(fogged.get(0).iconKey());
        assertEquals(tiles.get(1), fogged.get(1));
        assertEquals(tiles.get(2), fogged.get(2), "a finished tile is never hidden");
    }

    @Test
    void aFreeCentreIsDoneForEveryLineThroughIt() {
        Board b = BingoBoard.generate(5, 11, POOL, null, Set.of(), 50, 250, opts("{\"freeCentre\":true}"));
        assertEquals(BingoBoardOptions.FREE_KEY, b.tiles().get(12).key());
        assertEquals(1, b.tiles().stream().filter(t -> t.key().equals(BingoBoardOptions.FREE_KEY)).count());
        for (var bonus : b.bonuses()) {
            assertFalse(bonus.keys().contains(BingoBoardOptions.FREE_KEY), bonus.name());
            switch (bonus.name()) {
                case "Row 3", "Column 3", "Diagonal down", "Diagonal up" -> assertEquals(4, bonus.keys().size(), bonus.name());
                case "Blackout" -> assertEquals(24, bonus.keys().size());
                default -> assertEquals(5, bonus.keys().size(), bonus.name());
            }
        }
    }

    @Test
    void aFreeCentreNeedsAnOddGridAndIsOtherwiseIgnored() {
        Board even = BingoBoard.generate(4, 3, POOL, null, Set.of(), 50, 250, opts("{\"freeCentre\":true}"));
        assertTrue(even.tiles().stream().noneMatch(t -> t.key().equals(BingoBoardOptions.FREE_KEY)));
        assertEquals(BingoBoard.generate(4, 3, POOL, null, Set.of(), 50, 250).tiles(), even.tiles());
    }

    @Test
    void theFreeCentreIsOnTheBoardButIsNoObjective() {
        BingoTemplate template = new BingoTemplate();
        DataObject prepared = template.prepare(DataObject.fromJson("{\"board\":{\"size\":3,\"seed\":9,\"freeCentre\":true}}"));
        var keys = prepared.getObject("board").getArray("keys");
        assertEquals(9, keys.length());
        assertEquals(BingoBoardOptions.FREE_KEY, keys.getString(4));
        List<ObjectiveSpec> objectives = template.starterObjectives(prepared);
        assertEquals(8, objectives.size());
        assertTrue(objectives.stream().noneMatch(o -> o.key().equals(BingoBoardOptions.FREE_KEY)));
        assertTrue(EventBonuses.problems(prepared, objectives.stream().map(ObjectiveSpec::key).collect(Collectors.toSet())).isEmpty());
        assertTrue(template.configProblems(prepared).isEmpty());
        // the free space is drawn as done
        var views = BingoBoardView.personal(BingoBoardView.boardKeys(prepared), objectives, null);
        assertTrue(views.get(4).done());
        assertEquals("FREE", views.get(4).label());
        assertThrows(IllegalArgumentException.class, () -> template.replaceTile(prepared, objectives, 5, "Something else", 10));
    }

    @Test
    void rerollingKeepsTheBoardOptions() {
        BingoTemplate template = new BingoTemplate();
        DataObject prepared = template.prepare(DataObject.fromJson("{\"board\":{\"size\":3,\"seed\":9,\"title\":\"Winter\",\"fog\":true,\"accent\":\"azure\",\"freeCentre\":true,\"curve\":\"flat\"}}"));
        var again = template.reroll(prepared, template.starterObjectives(prepared), null, 77L, Set.of());
        DataObject board = again.config().getObject("board");
        assertEquals("Winter", board.getString("title"));
        assertTrue(board.getBoolean("fog"));
        assertEquals("azure", board.getString("accent"));
        assertTrue(board.getBoolean("freeCentre"));
        assertEquals("flat", board.getString("curve"));
        assertEquals(BingoBoardOptions.FREE_KEY, board.getArray("keys").getString(4));
    }

    @Test
    void theFlatCurveMakesEveryFreshTileWorthTheSame() {
        Board b = BingoBoard.generate(5, 21, POOL, null, Set.of(), 50, 250, opts("{\"curve\":\"flat\"}"));
        assertEquals(1, b.tiles().stream().map(BingoBoard.Tile::points).distinct().count());
        assertTrue(b.tiles().get(0).points() >= 5 && b.tiles().get(0).points() % 5 == 0);
    }

    @Test
    void theRampCurveRisesAlongTheBoardAndKeepsTheSamePoints() {
        Board pool = BingoBoard.generate(5, 21, POOL, null, Set.of(), 50, 250);
        Board ramp = BingoBoard.generate(5, 21, POOL, null, Set.of(), 50, 250, opts("{\"curve\":\"ramp\"}"));
        List<Long> points = ramp.tiles().stream().map(BingoBoard.Tile::points).toList();
        for (int i = 1; i < points.size(); i++) assertTrue(points.get(i) >= points.get(i - 1), "rises at " + i);
        assertEquals(pool.tiles().stream().map(BingoBoard.Tile::points).sorted().toList(), points, "the same set of points");
        assertEquals(pool.tiles().stream().map(BingoBoard.Tile::key).collect(Collectors.toSet()), ramp.tiles().stream().map(BingoBoard.Tile::key).collect(Collectors.toSet()));
    }

    @Test
    void aLockedTileKeepsItsPointsUnderACurve() {
        Board first = BingoBoard.generate(5, 4, POOL, null, Set.of(), 50, 250);
        var locked = first.tiles().get(7);
        Board again = BingoBoard.generate(5, 9, POOL, first.tiles(), Set.of(locked.key()), 50, 250, opts("{\"curve\":\"flat\"}"));
        assertEquals(locked, again.tiles().get(7));
    }

    @Test
    void theOptionsAreInTheSetupRegistry() {
        var paths = new BingoTemplate().options().stream().map(EventOptions.Option::path).toList();
        for (String key : BingoBoardOptions.KEYS) assertTrue(paths.contains("config.board." + key), key);
    }
}
