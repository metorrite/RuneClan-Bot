package com.runeclans.bot.events;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompetitionTest {
    private final CompetitionTemplate template = new CompetitionTemplate();

    @Test
    void theDefaultCompetitionIsOverallXpAtOnePointPerTenThousand() {
        assertTrue(template.configProblems(DataObject.empty()).isEmpty());
        var objectives = template.starterObjectives(DataObject.empty());
        assertEquals(1, objectives.size());
        var o = objectives.get(0);
        assertEquals("xp-gained", o.key());
        assertEquals("Overall XP gained", o.label());
        assertEquals(10_000, o.target());
        assertEquals(EventRules.Evidence.AUTO, o.evidence());
        assertTrue(EventService.checkObjectives(objectives).isEmpty());
    }

    @Test
    void aSkillCompetitionNamesTheSkill() {
        var o = template.starterObjectives(DataObject.fromJson("{\"skillId\":18,\"xpPerPoint\":500}")).get(0);
        assertEquals("Slayer XP gained", o.label());
        assertEquals(500, o.target());
    }

    @Test
    void badSettingsAreRejected() {
        assertFalse(template.configProblems(DataObject.fromJson("{\"skillId\":99}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"skillId\":-5}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"xpPerPoint\":0}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"xpPerPoint\":2000000}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"skillId\":\"slayer\"}")).isEmpty());
        assertTrue(template.configProblems(DataObject.fromJson("{\"skillId\":28}")).isEmpty());
    }

    @Test
    void xpGainBecomesPointsAtTheConfiguredRate() {
        var objectives = template.starterObjectives(DataObject.fromJson("{\"xpPerPoint\":1000}"));
        var log = List.of(new EventScoring.Entry("u:1", "xp-gained", 4_500, java.time.Instant.parse("2026-10-20T12:00:00Z")));
        assertEquals(4, EventScoring.standings(objectives, log).get(0).points());
    }
}
