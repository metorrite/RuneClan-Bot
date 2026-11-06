package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoTilePool.Difficulty;
import com.runeclans.bot.events.BingoTilePool.Entry;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A bingo event made from a server's own tile pool: only those tiles, a copy kept in the event, and the old pools untouched. */
class BingoCustomPoolTest {
    private final BingoTemplate template = new BingoTemplate();

    private static List<Entry> pool(int n) {
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new Entry("Custom tile " + i, 10 + i, 1, i % 2 == 0 ? Difficulty.EASY : Difficulty.HARD, List.of(i % 2 == 0 ? "a" : "b"), true));
        return list;
    }

    private static DataObject config(DataArray tiles) {
        DataObject board = DataObject.empty().put("size", 3).put("pool", "custom").put("seed", 7);
        if (tiles != null) board.put("tiles", tiles);
        return DataObject.empty().put("board", board);
    }

    @Test
    void aBoardIsMadeOnlyFromTheEventsOwnTiles() {
        DataObject config = config(BingoTilePool.toConfig(pool(12)));

        assertTrue(template.configProblems(config).isEmpty(), template.configProblems(config).toString());
        DataObject prepared = template.prepare(config);

        var objectives = template.starterObjectives(prepared);
        assertEquals(9, objectives.size());
        assertTrue(objectives.stream().allMatch(o -> o.label().startsWith("Custom tile ")));
        assertEquals(12, prepared.getObject("board").getArray("tiles").length(), "the event keeps its copy of the pool");
        assertEquals("custom", prepared.getObject("board").getString("pool"));
    }

    @Test
    void theSameEventRebuildsTheSameBoardFromItsOwnCopy() {
        DataObject prepared = template.prepare(config(BingoTilePool.toConfig(pool(20))));

        assertEquals(template.starterObjectives(prepared).stream().map(o -> o.label()).toList(), template.starterObjectives(prepared).stream().map(o -> o.label()).toList());
    }

    @Test
    void aPoolTooSmallForTheBoardIsReportedLikeAnyOther() {
        List<String> problems = template.configProblems(config(BingoTilePool.toConfig(pool(5))));

        assertFalse(problems.isEmpty());
        assertTrue(problems.get(0).contains("at least 9"));
    }

    @Test
    void aCustomPoolWithoutTilesAsksForTheDashboard() {
        List<String> problems = template.configProblems(config(null));

        assertTrue(problems.stream().anyMatch(p -> p.contains("dashboard")));
    }

    @Test
    void theBuiltInPoolsStillWorkAsBefore() {
        DataObject config = DataObject.empty().put("board", DataObject.empty().put("size", 5).put("pool", "classic").put("seed", 3));

        assertTrue(template.configProblems(config).isEmpty());
        assertEquals(25, template.starterObjectives(template.prepare(config)).size());
    }

}
