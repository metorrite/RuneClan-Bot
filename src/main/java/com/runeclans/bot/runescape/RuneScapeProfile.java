package com.runeclans.bot.runescape;

import java.util.List;

/** A snapshot of a player's public profile, as the hub polled it from Jagex. */
public record RuneScapeProfile(String name, int totalLevel, long totalXp, int combatLevel,
                                int questsComplete, int questsStarted, int questsNotStarted,
                                List<SkillValue> skills, List<PlayerActivity> activities) {
}
