package com.runeclans.bot.tracking;

import com.runeclans.bot.runescape.PlayerActivity;
import com.runeclans.bot.runescape.SkillEmojiCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TrackingEventClassifierTypeTest {
    private final TrackingEventClassifier classifier = new TrackingEventClassifier(mock(SkillEmojiCatalog.class), mock(TrackingIconCatalog.class));

    private TrackingGroup group(String type, String text) {
        return classifier.classify("Zezima", new PlayerActivity("2026-11-05T01:00:00Z", text, "", type), 1)
                .map(ClassifiedEntry::group).orElse(null);
    }

    @Test
    void theEventCodeDecidesTheGroup() {
        assertEquals(TrackingGroup.QUESTS, group("rs_plog_quest", "Quest complete: Heralds of Crimson"));
        assertEquals(TrackingGroup.SKILL_MILESTONES, group("rs_plog_levelup_count", "Levelled up Farming."));
        assertEquals(TrackingGroup.SKILL_MILESTONES, group("rs_plog_xp_milestone", "84000000XP in Smithing"));
        assertEquals(TrackingGroup.CLUE_SCROLLS, group("rs_plog_finished_trail", "Medium treasure trail completed."));
        assertEquals(TrackingGroup.CITADEL_ACTIVITY, group("rs_plog_special_event", "Capped at my Clan Citadel."));
        assertEquals(TrackingGroup.BOSS_KILLS, group("rs_plog_champions_challenge", "Won a challenge against the Imp Champion."));
    }

    @Test
    void theSameTextUnderTheWrongCodeOrNoCodeIsLeftOut() {
        assertTrue(classifier.classify("Zezima", new PlayerActivity("d", "Levelled up Farming.", "", null), 1).isEmpty());
        assertTrue(classifier.classify("Zezima", new PlayerActivity("d", "Levelled up Farming.", ""), 1).isEmpty());
        assertEquals(null, group("rs_plog_quest", "Capped at my Clan Citadel."));
        assertEquals(null, group("rs_plog_special_event", "Levelled up Farming."));
    }
}
