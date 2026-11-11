package com.runeclans.bot.hub;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The slash commands that stand on their own rather than belonging to one feature, each shown as a card on the dashboard's
 * Hub. {@code slashName} is the command's top-level name in Discord. {@code defaultAccess} is plain-English for who may use
 * it when the server hasn't changed anything, and {@code adminByDefault} is whether that default is "Admins only" (which is
 * enforced here, so a server can widen it) as opposed to a rule the command checks itself.
 * <p>
 * To add a command to the Hub, add it here with at least one {@link CommandTag} and give it a card on the website; the generic settings (on/off, who may use it,
 * where it may be used) then apply to it with no further code.
 */
public enum HubCommand {
    TAG("tag", "Custom tags", "tag", "The server's saved replies: members bring one up with /tag use, staff make and delete them. The full editor is on the Custom tags page, which also switches tags on.",
            "Everyone (making and deleting needs staff)", false, CommandTag.FUNCTIONAL, CommandTag.MEMBER_USE),
    SIGNUP("signup", "Signups", "signup", "Create and manage signups: queues, groups and submission forms, with a public panel members use.",
            "Admins only", true, CommandTag.FUNCTIONAL, CommandTag.CLAN_MANAGEMENT),
    POLL("poll", "Polls", "poll", "Create a poll, or end one you started.",
            "Linked clan members", false, CommandTag.FUNCTIONAL, CommandTag.UTILITY),
    WRAPPED("wrapped", "Recap card", "wrapped", "A member's recap card for a week, month, year or all time: XP, Citadel, boss kills and more.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.MEMBER_USE),
    COMBAT_ACHIEVEMENTS("ca", "Combat achievements", "ca", "Look up a Combat Mastery achievement: its tier, scores and the wiki's tips.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.MEMBER_USE),
    REMINDERS("remindme", "Reminders", "remindme", "Personal reminders: be pinged by DM after a length of time you choose, with a list and cancel.",
            "Everyone", false, CommandTag.UTILITY, CommandTag.MEMBER_USE),
    AFK("afk", "Away status", "afk", "Mark yourself away: people who mention you are told, and it clears when you next speak.",
            "Everyone", false, CommandTag.UTILITY, CommandTag.MEMBER_USE),
    SPLIT("split", "Loot split", "split", "Divide a drop between a group, with an optional fee, and show each person's share.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.UTILITY, CommandTag.MEMBER_USE),
    SUGGEST("suggest", "Suggestions", "suggest", "Members post ideas in the suggestions channel for everyone to vote on; staff reply from the dashboard.",
            "Everyone", false, CommandTag.FUNCTIONAL, CommandTag.MEMBER_USE),
    WARN("warn", "Warnings", "warn", "Warn members and keep a record of it: add, list and remove warnings. Members are told by DM.",
            "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    POINTS("points", "Points", "points", "A server's points ledger: check your balance, see the top members, and staff award points with a reason on record.",
            "Everyone (awarding needs Manage Server)", false, CommandTag.CLAN_MANAGEMENT, CommandTag.MEMBER_USE),
    ANNOUNCE("announce", "Announce", "announce", "Staff post an announcement in a channel, optionally pinging one role, @everyone or @here. Pings follow the sender's own mention permissions. The dashboard has the same form on its Post a message page.",
            "Admins only", true, CommandTag.ADMIN, CommandTag.CLAN_MANAGEMENT),
    RANK("rank", "Self roles", "rank", "List, join and leave the roles this server lets members pick for themselves (the list is edited on the website).",
            "Everyone", false, CommandTag.FUNCTIONAL, CommandTag.MEMBER_USE),
    LOCK("lock", "Lock a channel", "lock", "Stop everyone but chosen roles from talking in a channel. /unlock puts its permissions back exactly as they were.",
            "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    UNLOCK("unlock", "Unlock a channel", "unlock", "Put a channel locked with /lock back exactly as it was.",
            "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    LOCKDOWN("lockdown", "Lockdown", "lockdown", "Lock or unlock the server's saved set of channels at once (the set is chosen on the Lockdown page).",
            "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    SERVER_REMINDER("serverreminder", "Server reminders", "serverreminder", "Staff reminders posted in a channel, once or repeating, with an optional role ping (managed on the Server reminders page too).",
            "Admins only", true, CommandTag.UTILITY, CommandTag.ADMIN),
    HIGHLIGHT("highlight", "Highlights", "highlight", "Members pick words and get a direct message when someone says one in a channel they can see. Off until the Highlights page switches it on.",
            "Everyone", false, CommandTag.UTILITY, CommandTag.MEMBER_USE),
    ROLE("role", "Role toolkit", "role", "Give or take a role for a member. Admins can use any role the bot can hand out (never one that gives bot staff rights); others only the roles their permission group was allowed on the Role toolkit page.",
            "Admins, and groups an admin allowed (set on the Role toolkit page)", false, CommandTag.ADMIN, CommandTag.CLAN_MANAGEMENT),
    ROLEPERSIST("rolepersist", "Kept roles", "rolepersist", "Mark a role on a member to be given back whenever they rejoin, and see or remove what is kept. The server-wide sticky list is on the Kept roles page.",
            "Admins only", true, CommandTag.ADMIN, CommandTag.CLAN_MANAGEMENT),
    TEMPROLE("temprole", "Temporary roles", "temprole", "Give a member a role for a set time (like 2h, 7d or 1w); it is taken off by itself, even across restarts and rejoins.",
            "Admins only", true, CommandTag.ADMIN, CommandTag.CLAN_MANAGEMENT),
    EVENTS("events", "Events", "events", "See the events you are in (place, points, buy-in, prizes), browse the ones open for signup, and join or leave them. Events are made and run from the dashboard's Event manager.",
            "Everyone", false, CommandTag.EVENTS, CommandTag.MEMBER_USE),
    LEVEL("level", "Chat level", "level", "See your chat level, XP and rank, or another member's. Chat levels are off until the Chat levels page switches them on.",
            "Everyone", false, CommandTag.FUNCTIONAL, CommandTag.MEMBER_USE),
    LEVELS("levels", "Chat leaderboard", "levels", "The most active chatters in the server.",
            "Everyone", false, CommandTag.FUNCTIONAL, CommandTag.MEMBER_USE),
    PRICE("price", "Grand Exchange prices", "price", "Look up an item's Grand Exchange price with its 7 and 30 day change, and a link to its wiki page.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.MEMBER_USE),
    WORLD_EVENTS("worldevents", "World events", "worldevents", "The Voice of Seren right now and the events that come round by themselves, with how often. Hourly reminders are set on the RuneScape timers page.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.MEMBER_USE),
    RESET("reset", "Reset timers", "reset", "Countdowns to the daily, weekly and monthly RuneScape resets. Reminders at each reset are set on the RuneScape timers page.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.MEMBER_USE),
    DND("dnd", "Distractions and Diversions", "dnd", "The RuneScape Distractions and Diversions by how often they can be played (daily, weekly, monthly, when available), from the wiki.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.MEMBER_USE),
    BOSS("boss", "Boss info", "boss", "A short wiki summary of a RuneScape 3 boss with its key facts and a link to its strategy page.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.MEMBER_USE),
    QUEST("quest", "Quest info", "quest", "A short wiki summary of a RuneScape 3 quest with its difficulty, area and a link to its quick guide.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.MEMBER_USE),
    // The fun commands: off by default in every server, and registered with Discord only in a server that switches them on, so a
    // switched-off one is not in its command list at all (see FunCommandFilter). Tagged FUN, which is what marks them.
    FLIP("flip", "Coin flip", "flip", "Flip a coin: heads or tails.", "Everyone", false, CommandTag.FUN, CommandTag.MEMBER_USE),
    ROLL("roll", "Dice roll", "roll", "Roll dice in dice notation, like 2d6 or d20+3.", "Everyone", false, CommandTag.FUN, CommandTag.MEMBER_USE),
    RPS("rps", "Rock paper scissors", "rps", "Play rock paper scissors against the bot.", "Everyone", false, CommandTag.FUN, CommandTag.MEMBER_USE),
    EIGHT_BALL("8ball", "Magic 8-ball", "8ball", "Ask the magic 8-ball a yes or no question.", "Everyone", false, CommandTag.FUN, CommandTag.MEMBER_USE),
    CHOOSE("choose", "Choose", "choose", "Pick one from a list of options a member types in.", "Everyone", false, CommandTag.FUN, CommandTag.UTILITY, CommandTag.MEMBER_USE),
    DAD_JOKE("dadjoke", "Dad joke", "dadjoke", "Tell a dad joke (from a free public joke service).", "Everyone", false, CommandTag.FUN, CommandTag.MEMBER_USE),
    CAT("cat", "Cat picture", "cat", "A random cat picture (from a free public picture service).", "Everyone", false, CommandTag.FUN, CommandTag.MEMBER_USE),
    DOG("dog", "Dog picture", "dog", "A random dog picture (from a free public picture service).", "Everyone", false, CommandTag.FUN, CommandTag.MEMBER_USE),
    // Moderation: the commands that ban, kick or time someone out are OFF until a server switches them on, and admin-only until it widens that
    // to a moderator group on the Commands page. The two case lookups are read-only and start on (still admin-only).
    BAN("ban", "Ban", "ban", "Ban a member, or someone who has left, with a reason and a numbered case.", "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    UNBAN("unban", "Unban", "unban", "Lift a ban, with a reason and a numbered case.", "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    KICK("kick", "Kick", "kick", "Kick a member, with a reason and a numbered case.", "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    TIMEOUT("timeout", "Timeout", "timeout", "Time a member out (mute) for up to 28 days, with a reason and a numbered case.", "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    UNTIMEOUT("untimeout", "Remove timeout", "untimeout", "Remove a member's timeout early.", "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    SOFTBAN("softban", "Softban", "softban", "Ban and at once unban, to clear a member's last day of messages.", "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    CASE("case", "Case lookup", "case", "Show or edit one moderation case by its number.", "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    MODLOGS("modlogs", "Moderation history", "modlogs", "A member's moderation cases, newest first.", "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN);

    private final String key;
    private final String title;
    private final String slashName;
    private final String description;
    private final String defaultAccess;
    private final boolean adminByDefault;
    private final List<CommandTag> tags;

    HubCommand(String key, String title, String slashName, String description, String defaultAccess, boolean adminByDefault, CommandTag... tags) {
        this.key = key;
        this.title = title;
        this.slashName = slashName;
        this.description = description;
        this.defaultAccess = defaultAccess;
        this.adminByDefault = adminByDefault;
        this.tags = List.of(tags);
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String slashName() {
        return slashName;
    }

    public String description() {
        return description;
    }

    public String defaultAccess() {
        return defaultAccess;
    }

    public boolean adminByDefault() {
        return adminByDefault;
    }

    /** Whether a server has it switched on before anyone has touched the settings: everything except the fun commands and the ones that ban, kick or time out. */
    public boolean defaultEnabled() {
        return !tags.contains(CommandTag.FUN) && !punishes();
    }

    /** True for the commands that do something irreversible or disruptive to a member: ban, unban, kick, timeout, untimeout, softban. */
    public boolean punishes() {
        return this == BAN || this == UNBAN || this == KICK || this == TIMEOUT || this == UNTIMEOUT || this == SOFTBAN;
    }

    /** True for the commands registered with Discord per server (only where switched on), instead of globally: the fun ones. */
    public boolean registeredPerServer() {
        return tags.contains(CommandTag.FUN);
    }

    /** What the command is for; never empty (the dashboard's Commands page filters by these). */
    public List<CommandTag> tags() {
        return tags;
    }

    public static Optional<HubCommand> ofKey(String key) {
        return Arrays.stream(values()).filter(c -> c.key.equals(key)).findFirst();
    }

    public static Optional<HubCommand> ofSlashName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.slashName.equals(lower)).findFirst();
    }
}
