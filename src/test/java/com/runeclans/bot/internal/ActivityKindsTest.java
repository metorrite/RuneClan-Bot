package com.runeclans.bot.internal;

import com.runeclans.bot.internal.ActivityKinds.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityKindsTest {
    @Test
    void theEventCodeSortsTheLine() {
        assertEquals(Kind.LEVEL_UP, ActivityKinds.kindOf("rs_plog_levelup_count", "Levelled up Cooking."));
        assertEquals(Kind.XP_MILESTONE, ActivityKinds.kindOf("rs_plog_xp_milestone", "200000000XP in Necromancy"));
        assertEquals(Kind.QUEST, ActivityKinds.kindOf("rs_plog_quest", "Quest complete: Heralds of Crimson"));
        assertEquals(Kind.CITADEL_CAP, ActivityKinds.kindOf("rs_plog_special_event", "Capped at my Clan Citadel."));
        assertEquals(Kind.CITADEL_VISIT, ActivityKinds.kindOf("rs_plog_special_event", "Visited my Clan Citadel."));
        assertEquals(Kind.BOSS, ActivityKinds.kindOf("rs_plog_npc_kill_count", "I killed 12 Amascuts."));
        assertEquals(Kind.PET, ActivityKinds.kindOf("rs_plog_item_found", "I found Shrimpy, the Fishing pet."));
        assertEquals(Kind.CLUE, ActivityKinds.kindOf("rs_plog_finished_trail", "Medium treasure trail completed."));
        assertEquals(Kind.OTHER, ActivityKinds.kindOf("rs_plog_special_event", "Maintained Clan Fealty 5"));
    }

    @Test
    void textAloneNeverSortsALine() {
        assertEquals(Kind.OTHER, ActivityKinds.kindOf(null, "Levelled up Cooking."));
        assertEquals(Kind.OTHER, ActivityKinds.kindOf(null, "Capped at my Clan Citadel."));
        // a capped line under another code is not a cap
        assertEquals(Kind.OTHER, ActivityKinds.kindOf("rs_plog_quest", "Capped at my Clan Citadel."));
        assertEquals(Kind.OTHER, ActivityKinds.kindOf("rs_plog_unknown", "Levelled up Cooking."));
    }

    @Test
    void theRecapsDetailsComeFromTheSameLines() {
        assertEquals("Cooking", ActivityKinds.levelUpSkill("Levelled up Cooking.").orElseThrow());
        assertEquals("Heralds of Crimson", ActivityKinds.questName("Quest complete: Heralds of Crimson").orElseThrow());
        assertEquals("Necromancy", ActivityKinds.milestone("200000000XP in Necromancy").orElseThrow().skill());
        assertEquals(50_000_000L, ActivityKinds.milestone("50,000,000XP in Fishing").orElseThrow().xp());
        assertEquals("Shrimpy", ActivityKinds.petName("I found Shrimpy, the Fishing pet.").orElseThrow());
        assertTrue(ActivityKinds.levelUpSkill("I killed 12 Amascuts.").isEmpty());
        assertTrue(ActivityKinds.questName("Levelled up Cooking.").isEmpty());
    }

    @Test
    void killLinesGiveTheBossAndCount() {
        assertEquals("Vorago", ActivityKinds.bossOf("I killed  Vorago.").orElseThrow());
        assertEquals(1, ActivityKinds.killCount("I killed  Vorago."));
        assertEquals(12, ActivityKinds.killCount("I killed 12 Amascuts."));
        assertEquals(1_200, ActivityKinds.killCount("I killed 1,200 Telos."));
        assertEquals("Telos", ActivityKinds.bossOf("I killed 21 Telos.").orElseThrow());
    }

    @Test
    void bossesOutsideTheCatalogueAreReadFromTheLine() {
        assertEquals("Amascut", ActivityKinds.bossOf("I killed 12 Amascuts.").orElseThrow());
        assertEquals("Nex", ActivityKinds.bossOf("I killed 7 Nexes.").orElseThrow());
        assertEquals("Gate of Elidinis", ActivityKinds.bossOf("I killed 18 Gate of Elidinis.").orElseThrow());
        assertEquals("Amascut", ActivityKinds.bossOf("I killed  Amascut, the Devourer.").orElseThrow());
        assertEquals("Arch-Glacor", ActivityKinds.bossOf("I killed  an Arch-Glacor.").orElseThrow());
        assertEquals("King Black Dragon", ActivityKinds.bossOf("I killed 15 King Black Dragons.").orElseThrow());
    }

    @Test
    void nonKillLinesHaveNoBoss() {
        assertTrue(ActivityKinds.bossOf("Levelled up Mining.").isEmpty());
        assertTrue(ActivityKinds.bossOf("I found a dragon helm").isEmpty());
    }
}
