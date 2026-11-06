package com.runeclans.bot.events;

import com.runeclans.bot.events.EventBoards.Display;
import com.runeclans.bot.events.EventBoards.Posted;
import com.runeclans.bot.events.EventBoards.Subject;
import com.runeclans.bot.events.EventBoards.What;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a bingo's boards are shown: one for everyone, one each, or both, and what is sent again when something changes. */
class EventBoardsTest {
    private static Subject team(long id, String name, Long channel) {
        return new Subject("t:" + id, name, channel);
    }

    // ---------- the setting ----------

    @Test
    void anEventWithNoDisplayShowsTheOneSharedBoardLikeBefore() {
        assertEquals(Display.SHARED, EventBoards.displayOf(DataObject.fromJson("{}")));
        assertEquals(Display.SHARED, EventBoards.displayOf(DataObject.fromJson("{\"board\":{\"size\":5}}")));
        assertEquals(Display.OWN, EventBoards.displayOf(DataObject.fromJson("{\"board\":{\"display\":\"own\"}}")));
        assertEquals(Display.BOTH, EventBoards.displayOf(DataObject.fromJson("{\"board\":{\"display\":\"BOTH\"}}")));
        assertEquals(Display.SHARED, EventBoards.displayOf(null));
    }

    @Test
    void whichBoardsEachDisplayShows() {
        assertTrue(Display.SHARED.shared());
        assertFalse(Display.SHARED.own());
        assertFalse(Display.OWN.shared());
        assertTrue(Display.OWN.own());
        assertTrue(Display.BOTH.shared() && Display.BOTH.own());
    }

    @Test
    void aDisplayThatIsNotOneOfTheThreeIsReported() {
        assertNull(EventBoards.problem(DataObject.fromJson("{}")));
        assertNull(EventBoards.problem(DataObject.fromJson("{\"board\":{\"display\":\"own\"}}")));
        assertNotNull(EventBoards.problem(DataObject.fromJson("{\"board\":{\"display\":\"everywhere\"}}")));
        assertEquals(Display.SHARED, EventBoards.displayOf(DataObject.fromJson("{\"board\":{\"display\":\"everywhere\"}}")), "and it reads as the old behaviour");
    }

    // ---------- where each board goes and what is sent ----------

    @Test
    void aBoardGoesInItsOwnChannelElseTheLiveBoardChannelElseNowhere() {
        var steps = EventBoards.plan(List.of(team(1, "Red", 100L), team(2, "Blue", null), team(3, "Green", null)), 500L, Map.of(), Map.of());
        assertEquals(100L, steps.get(0).channelId());
        assertEquals(500L, steps.get(1).channelId(), "no channel of its own: the live board channel");
        assertEquals(List.of(What.POST, What.POST, What.POST), steps.stream().map(EventBoards.Step::what).toList());

        var nowhere = EventBoards.plan(List.of(team(2, "Blue", null)), null, Map.of(), Map.of());
        assertEquals(What.NO_PLACE, nowhere.get(0).what());
        assertNull(nowhere.get(0).channelId());
    }

    @Test
    void aBoardWhoseTilesDidNotChangeIsLeftAloneAndOneThatDidIsEdited() {
        var subjects = List.of(team(1, "Red", 100L), team(2, "Blue", 200L));
        Map<String, Posted> posted = Map.of("t:1", new Posted(100L, 11L, "aaa"), "t:2", new Posted(200L, 22L, "bbb"));
        var steps = EventBoards.plan(subjects, null, posted, Map.of("t:1", "aaa", "t:2", "ccc"));
        assertEquals(What.UNCHANGED, steps.get(0).what());
        assertEquals(What.EDIT, steps.get(1).what());
        assertEquals("ccc", steps.get(1).hash(), "the new fingerprint is what gets remembered");
    }

    @Test
    void aBoardThatHasToMoveToAnotherChannelIsPostedAgainThere() {
        var steps = EventBoards.plan(List.of(team(1, "Red", 300L)), null, Map.of("t:1", new Posted(100L, 11L, "aaa")), Map.of("t:1", "aaa"));
        assertEquals(What.POST, steps.get(0).what(), "their channel was made after the board was posted in the live board channel");
        assertEquals(300L, steps.get(0).channelId());
    }
}
