package com.runeclans.bot.hubevents;

import com.runeclans.bot.events.BingoLooks;
import com.runeclans.bot.events.EventQuickCreate;
import com.runeclans.bot.events.EventService.EventInput;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /events create}'s body to the hub: the standard setup (so the clan's own defaults apply) with only what was really chosen laid over it. */
class HubEventsCreateBodyTest {
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    @Test
    void aQuickEventAsksForTheStandardSetupAndSendsOnlyItsChoices() {
        EventInput in = EventQuickCreate.plan(EventQuickCreate.Kind.BINGO, "Winter bingo", 3, 2, "4", NOW, null).input();
        DataObject body = HubEvents.createBody(in, NOW);
        assertTrue(body.getBoolean("standard", false), "the hub lays the clan's defaults over the standard answers");
        assertFalse(body.hasKey("config"), "the whole settings object would replace the clan's defaults");
        assertEquals("Winter bingo", body.getString("name"));
        assertEquals("bingo", body.getString("template"));
        assertEquals(in.startsAt().toString(), body.getString("startsAt"));
        assertEquals(4, body.getInt("config.board.size"));
        assertEquals("OFF", body.getString("config.setup.mode"), "made from one command: no setup steps");
        assertFalse(body.hasKey("config.board.look"), "the look nobody chose is left to the clan's default");
        for (String key : body.keys()) assertTrue(key.matches("[A-Za-z]+(\\.[A-Za-z]+)*"), "the hub takes letter-only option paths: " + key);
    }

    @Test
    void aChosenLookIsSent() {
        EventInput in = EventQuickCreate.plan(EventQuickCreate.Kind.BINGO, "Winter bingo", 3, 2, null, NOW, BingoLooks.CLASSIC).input();
        assertEquals(BingoLooks.CLASSIC, HubEvents.createBody(in, NOW).getString("config.board.look"));
    }

    @Test
    void anotherTemplateSendsEveryAnswer() {
        EventInput in = new EventInput("Raid", "", "custom", com.runeclans.bot.events.EventRules.Scope.PRIVATE, com.runeclans.bot.events.EventRules.ScoringMode.INDIVIDUAL,
                NOW, NOW.plusSeconds(3600), 10, DataObject.empty().put("a", 1));
        DataObject body = HubEvents.createBody(in, NOW);
        assertFalse(body.hasKey("standard"));
        assertEquals(1, body.getObject("config").getInt("a"));
        assertEquals(10, body.getInt("maxParticipants"));
    }
}
