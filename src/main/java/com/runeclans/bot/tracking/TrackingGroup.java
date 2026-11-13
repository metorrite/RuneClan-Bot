package com.runeclans.bot.tracking;

/**
 * The fixed set of announcement groups an admin enables and picks destinations for from
 * {@code /configure}'s Tracking panel — approved as a taxonomy before this was built (RuneMetrics'
 * own Adventurer's Log categories, Clan Citadel, the clan roster diff, Discord's own admin log
 * (grouped from JDA's {@code ActionType} list), and the clan points/promotion system's daily report).
 * <p>
 * Deliberately code, not a database table an admin defines from scratch: which entry types belong to
 * which group is a judgment call made once, not something worth 150+ individual toggles for. What IS
 * per-guild configurable — whether a group is enabled, and which channel(s)/thread(s) it posts to —
 * lives in {@link TrackingRepository}.
 */
public enum TrackingGroup {
    SKILL_MILESTONES("Adventurer's Log", "Skill Milestones"),
    QUESTS("Adventurer's Log", "Quests & Quest Points"),
    NOTABLE_DROPS("Adventurer's Log", "Notable Drops"),
    CLUE_SCROLLS("Adventurer's Log", "Clue Scrolls & Treasure Trails"),
    PETS("Adventurer's Log", "Pets"),
    BOSS_KILLS("Adventurer's Log", "Boss & Monster Milestones"),
    MINIGAME_MISC("Adventurer's Log", "Minigame & Activity Milestones"),
    ARCHAEOLOGY("Adventurer's Log", "Archaeology"),
    CITADEL_ACTIVITY("Clan Citadel", "Citadel Activity"),
    WEEKLY_CITADEL_REPORT("Clan Citadel", "Weekly Citadel Report"),
    CLAN_JOINS_LEAVES("Clan Roster", "Clan Joins & Leaves"),
    WEEKLY_JOINS_LEAVES("Clan Roster", "Weekly Joins & Leaves"),
    CLAN_REPORT("Points & Promotions", "Clan Report"),
    SERVER_SETTINGS("Discord Admin Log", "Server Settings"),
    CHANNELS_THREADS("Discord Admin Log", "Channels & Threads"),
    ROLES_PERMISSIONS("Discord Admin Log", "Roles & Command Permissions"),
    MEMBERS_MODERATION("Discord Admin Log", "Members & Moderation"),
    MESSAGES("Discord Admin Log", "Messages"),
    SERVER_EXTRAS("Discord Admin Log", "Server Extras");

    private final String source;
    private final String displayName;

    TrackingGroup(String source, String displayName) {
        this.source = source;
        this.displayName = displayName;
    }

    /** Which of the sources (Adventurer's Log, Clan Citadel, Clan Roster, Discord Admin Log) feeds this group — for grouping the Tracking panel's display only. */
    public String source() {
        return source;
    }

    public String displayName() {
        return displayName;
    }
}
