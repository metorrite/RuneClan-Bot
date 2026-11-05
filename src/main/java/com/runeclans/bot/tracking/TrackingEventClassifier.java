package com.runeclans.bot.tracking;

import com.runeclans.bot.runescape.XpText;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.runescape.PlayerActivity;
import com.runeclans.bot.runescape.RuneScapeSkillCatalog;
import com.runeclans.bot.runescape.SkillEmojiCatalog;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogChange;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.audit.AuditLogOption;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one {@link PlayerActivity} (RuneMetrics' adventure log feed) or one Discord
 * {@link AuditLogEntry} into a {@link ClassifiedEntry} — a group plus an already-rendered plain-text
 * line — or {@code empty} if it's not one of the entry types the approved taxonomy covers yet.
 * <p>
 * The RuneMetrics rules are ordered most-specific-first and each one returns as soon as it matches;
 * two of them ({@link #CHAMPION_WIN}, the "Capped at my Clan Citadel" exact match) are lifted
 * directly from real activity text already confirmed live elsewhere in this codebase
 * ({@link com.runeclans.bot.runescape.MonthlyRecapService}) — the rest are compiled from
 * <a href="https://runescape.wiki/w/RuneMetrics/Adventurer%27s_Log">the wiki's own documented
 * templates</a> and haven't all been individually confirmed against real text yet. Expect to add a
 * pattern here occasionally when something shows up that doesn't match any rule — same "we'll add it
 * when we see it" approach already agreed for the drop list.
 * <p>
 * {@link PlayerActivity#date()} is shown as-is on every RuneMetrics-sourced line (see {@link #classify})
 * — this is a display-only use, distinct from {@code WeeklyDigestService}'s more careful treatment of
 * the same field for filtering/ordering. RuneScape's own player-facing "Recent Activity" list shows
 * this exact string as the activity's timestamp with no further conversion, so showing it here the same
 * way is exactly as trustworthy as the game's own UI, even without knowing its precise timezone.
 */
@BService
public class TrackingEventClassifier {
    private static final Pattern CHAMPION_WIN = Pattern.compile(
            "Won a challenge against the (.+?) Champion", Pattern.CASE_INSENSITIVE);
    private static final Pattern XP_MILESTONE = Pattern.compile("^([\\d,]+)XP in (.+)$");
    private static final Pattern SKILL_LEVEL_UP = Pattern.compile("^Levelled up (.+?)\\.?$");
    private static final Pattern QUEST_COMPLETE = Pattern.compile("^Quest complete: ?(.+)$");
    private static final Pattern SKILLING_PET = Pattern.compile("^I found (.+?), the (.+?) pet\\.?$", Pattern.CASE_INSENSITIVE);

    private static final List<String> MINIGAME_KEYWORDS = List.of(
            "Castle Wars", "Dominion Tower", "duellist's cap", "wildstalker helmet", "Daemonheim",
            "dungeoneering tokens", "charm sprites", "Livid Farm", "court summons", "Golden Cannon",
            "Royale Cannon", "Master Student", "Fight Kiln", "chimp ices", "Dagannoth Kings' Rex",
            "Duel Arena");

    /** The hub's adventure-log event codes the rules above are keyed on. */
    private static final Set<String> LEVEL_UP_CODES = Set.of("rs_plog_levelup_count", "rs_plog_levelup");
    private static final Set<String> KILL_CODES = Set.of("rs_plog_npc_defeat", "rs_plog_npc_kill_count", "rs_plog_npc_kill");
    private static final Set<String> FOUND_CODES = Set.of("rs_plog_item_drop", "rs_plog_item_found", "rs_plog_item_found_nowhere");

    private final SkillEmojiCatalog skillEmojiCatalog;
    private final TrackingIconCatalog trackingIconCatalog;

    public TrackingEventClassifier(SkillEmojiCatalog skillEmojiCatalog, TrackingIconCatalog trackingIconCatalog) {
        this.skillEmojiCatalog = skillEmojiCatalog;
        this.trackingIconCatalog = trackingIconCatalog;
    }

    /**
     * {@code count} is how many times this exact activity (same {@link PlayerActivity#text()}) showed
     * up back-to-back in this poll's batch — see {@link com.runeclans.bot.runescape.RuneScapeStatsService}'s
     * run-length collapsing, which is what actually groups them before calling this once per run rather
     * than once per repeat. RuneMetrics reports every individual boss kill (and can report the same
     * drop landing more than once at once), so without that collapsing a grind session would flood the
     * feed with one identical line per kill/drop; {@code count} lets the two branches that actually see
     * repeats in practice (drops, boss kills) fold a whole run into one line instead of just picking a
     * lower posting rate.
     * <p>
     * Every resulting line ends with RuneMetrics' own timestamp for {@code activity} (its
     * {@link PlayerActivity#date()}, exactly as the game reports it — see the class doc for why that's
     * trusted for display here even though it's deliberately never parsed into an absolute instant
     * elsewhere in this codebase). For a collapsed run of repeats, that's the most recent one's time.
     */
    public Optional<ClassifiedEntry> classify(String rsn, PlayerActivity activity, int count) {
        return classifyActivity(rsn, activity, count)
                .map(e -> new ClassifiedEntry(e.group(), e.line() + " (" + when(activity) + ")"));
    }

    /**
     * When it happened, as a Discord timestamp so everyone sees it in their own time zone. RuneMetrics writes the time as UK local time (see
     * {@link com.runeclans.bot.runescape.RuneMetricsDates}); if it can't be read the raw text is shown as it came.
     */
    static String when(PlayerActivity activity) {
        java.time.Instant at = com.runeclans.bot.runescape.RuneMetricsDates.toInstant(activity.date());
        return at == null ? activity.date() : "<t:" + at.getEpochSecond() + ":f>";
    }

    private Optional<ClassifiedEntry> classifyActivity(String rsn, PlayerActivity activity, int count) {
        String text = activity.text();
        String type = activity.type();
        // The hub's event code sorts the line; the text only decides inside one code. No code, no entry.
        if (type == null || text == null) return Optional.empty();
        String bold = "**" + rsn + "**";

        if ("rs_plog_special_event".equals(type)) {
            if (text.startsWith("Capped at my Clan Citadel")) {
                return entry(TrackingGroup.CITADEL_ACTIVITY, null, bold + " capped at the Clan Citadel.");
            }
            if (text.startsWith("Visited my Clan Citadel")) {
                return entry(TrackingGroup.CITADEL_ACTIVITY, null, bold + " visited the Clan Citadel.");
            }
            if (text.startsWith("Maintained Clan Fealty")) {
                return entry(TrackingGroup.CITADEL_ACTIVITY, null, bold + " " + lowerFirst(text));
            }
        }

        Matcher quest = QUEST_COMPLETE.matcher(text);
        if ("rs_plog_quest".equals(type) && quest.matches()) {
            return entry(TrackingGroup.QUESTS, trackingIconCatalog.mentionForCategory("quest"),
                    bold + " completed the quest **" + quest.group(1).trim() + "**.");
        }
        if ("rs_plog_qp_milestone".equals(type)) {
            return entry(TrackingGroup.QUESTS, trackingIconCatalog.mentionForCategory("quest"), bold + " " + lowerFirst(text));
        }

        Optional<DropItemCatalog.DropItem> drop = FOUND_CODES.contains(type) ? DropItemCatalog.findIn(text) : Optional.empty();
        if (drop.isPresent()) {
            DropItemCatalog.DropItem item = drop.get();
            String message = count == 1
                    ? bold + " found **" + item.name() + "**."
                    : bold + " found **" + count + " " + pluralize(item.name()) + "**.";
            return entry(TrackingGroup.NOTABLE_DROPS, trackingIconCatalog.mentionForDrop(item.key()), message);
        }

        Matcher levelUp = SKILL_LEVEL_UP.matcher(text);
        if (LEVEL_UP_CODES.contains(type) && levelUp.matches()) {
            String skillName = levelUp.group(1).trim();
            return entry(TrackingGroup.SKILL_MILESTONES, skillEmojiMention(skillName),
                    bold + " levelled up **" + skillName + "**.");
        }
        Matcher xpMilestone = XP_MILESTONE.matcher(text);
        if ("rs_plog_xp_milestone".equals(type) && xpMilestone.matches()) {
            long xp = Long.parseLong(xpMilestone.group(1).replace(",", ""));
            String skillName = xpMilestone.group(2).trim();
            return entry(TrackingGroup.SKILL_MILESTONES, skillEmojiMention(skillName),
                    bold + " reached " + XpText.amount(xp) + " XP in " + skillName + ".");
        }
        if ("rs_plog_levelup_total_milestone".equals(type) || "rs_plog_levelup_all_milestone".equals(type)) {
            return entry(TrackingGroup.SKILL_MILESTONES, null, bold + " " + lowerFirst(text));
        }

        Matcher championWin = CHAMPION_WIN.matcher(text);
        if ("rs_plog_champions_challenge".equals(type) && championWin.find()) {
            return entry(TrackingGroup.BOSS_KILLS, null,
                    bold + " won a challenge against the **" + championWin.group(1).trim() + " Champion**.");
        }
        if (KILL_CODES.contains(type) && (text.startsWith("I killed") || text.startsWith("I defeated"))) {
            for (BossCatalog.Boss boss : BossCatalog.all()) {
                if (text.contains(boss.name())) {
                    String message = count == 1
                            ? bold + " defeated **" + boss.name() + "**."
                            : bold + " has defeated **" + boss.name() + "** " + count + " times!";
                    return entry(TrackingGroup.BOSS_KILLS, trackingIconCatalog.mentionForBoss(boss.key()), message);
                }
            }
        }

        // No generic "pet" icon — a skilling pet's own name is chosen by the player, not a fixed
        // species, so there's nothing stable to catalog an icon against. The skill it came from IS
        // stable, and we already have that icon (reusing SkillEmojiCatalog), so that's shown instead.
        Matcher pet = SKILLING_PET.matcher(text);
        if (pet.matches()) {
            String skillName = pet.group(2).trim();
            return entry(TrackingGroup.PETS, skillEmojiMention(skillName),
                    bold + " found **" + pet.group(1).trim() + "**, the " + skillName + " pet.");
        }
        // TzRek-Jad is a fixed identity (the boss it's themed after), so it gets that specific boss's
        // own icon rather than the generic boss default; an effigy pet isn't tied to one specific boss,
        // so it gets the default.
        if (text.contains("I adopted TzRek-Jad")) {
            return entry(TrackingGroup.PETS, trackingIconCatalog.mentionForBoss("tztok_jad"), bold + " " + lowerFirst(text));
        }
        if (text.toLowerCase(Locale.ROOT).contains("effigy pet")) {
            return entry(TrackingGroup.PETS, trackingIconCatalog.mentionForDefaultBoss(), bold + " " + lowerFirst(text));
        }

        if ("rs_plog_finished_trail".equals(type)) {
            return entry(TrackingGroup.CLUE_SCROLLS, trackingIconCatalog.mentionForCategory("clue"), bold + " " + lowerFirst(text));
        }

        // Reuses the Archaeology skill's own icon (SkillEmojiCatalog) rather than a separately
        // downloaded one — a dig site is specific to which mystery was solved (too granular to
        // catalog one icon per site), but the skill itself is exactly what every one of these lines is
        // actually about, and already has a real icon with no new wiki source needed.
        if (text.contains("archaeological mystery") || text.contains("tetracompass")
                || (text.startsWith("Earnt my") && text.contains("qualification"))) {
            return entry(TrackingGroup.ARCHAEOLOGY, skillEmojiMention("Archaeology"), bold + " " + lowerFirst(text));
        }

        for (String keyword : MINIGAME_KEYWORDS) {
            if (text.contains(keyword)) {
                return entry(TrackingGroup.MINIGAME_MISC, null, bold + " " + lowerFirst(text));
            }
        }

        return Optional.empty();
    }

    /** Naive "+s" pluralization — good enough for the vast majority of (mostly regular) RS item names; an irregular one just reads slightly odd, never wrong data. */
    private static String pluralize(String name) {
        return name.endsWith("s") ? name : name + "s";
    }

    /**
     * {@code actorMention} is a raw {@code <@id>} mention, already resolved by the caller —
     * {@link TrackingEventRouter} sends every tracking message with push/desktop notifications
     * suppressed, so this (and the target mention below) never actually pings anyone. Rendered as its
     * own bordered {@link Container} (see {@link ClassifiedContainer}) rather than a plain-text line —
     * Discord's own client visually runs consecutive same-bot messages together with no gap, and a
     * structured action like this reads far worse that way than a card with a clear border does.
     */
    public Optional<ClassifiedContainer> classify(AuditLogEntry logEntry, String actorMention) {
        TrackingGroup group = groupFor(logEntry.getType());
        if (group == null) return Optional.empty();

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + prettyLabel(logEntry.getType())));

        StringBuilder details = new StringBuilder("**Performed By:** ").append(actorMention);

        String target = describeTarget(logEntry);
        if (target != null) details.append("\n**Target:** ").append(target);

        String channel = describeChannel(logEntry);
        if (channel != null) details.append("\n**Channel:** ").append(channel);

        String reason = logEntry.getReason();
        if (reason != null && !reason.isBlank()) details.append("\n**Reason:** ").append(reason);

        long epochSeconds = logEntry.getTimeCreated().toEpochSecond();
        details.append("\n**Time:** <t:").append(epochSeconds).append(":f> (<t:").append(epochSeconds).append(":R>)");

        children.add(TextDisplay.of(details.toString()));

        return Optional.of(new ClassifiedContainer(group, Containers.card(Containers.PRIMARY, children)));
    }

    /**
     * A handful of action types (message deletes especially — the target is the affected *user*, not
     * a channel) carry the channel separately in the audit log's own "options" rather than as the
     * entry's target — {@code null} if this action type doesn't have one.
     */
    private static String describeChannel(AuditLogEntry logEntry) {
        Object channelId = logEntry.getOption(AuditLogOption.CHANNEL);
        if (channelId == null) return null;
        try {
            return "<#" + Long.parseLong(channelId.toString()) + ">";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Best-effort description of what the action targeted. A role or member mention already renders
     * its own live name in Discord, so those are left as a bare mention; a role additionally gets its
     * name appended from the audit log's own "name" change (not a live lookup), since that's the one
     * target type most likely to already be gone by the time this posts (a temp role created then
     * deleted, for instance) — the audit log's copy of the name survives that. Channels/threads render
     * their name via the mention itself either way.
     */
    private static String describeTarget(AuditLogEntry logEntry) {
        long targetId = logEntry.getTargetIdLong();
        if (targetId == 0) return null;

        return switch (logEntry.getTargetType()) {
            case ROLE -> "<@&" + targetId + ">" + nameSuffix(logEntry);
            case CHANNEL, THREAD -> "<#" + targetId + ">";
            case MEMBER -> "<@" + targetId + ">";
            default -> {
                String name = nameFromChanges(logEntry);
                yield name != null ? "**" + name + "** (`" + targetId + "`)" : "`" + targetId + "`";
            }
        };
    }

    private static String nameSuffix(AuditLogEntry logEntry) {
        String name = nameFromChanges(logEntry);
        return name != null ? " (**" + name + "**)" : "";
    }

    private static String nameFromChanges(AuditLogEntry logEntry) {
        AuditLogChange change = logEntry.getChangeByKey("name");
        if (change == null) return null;
        Object value = change.getNewValue() != null ? change.getNewValue() : change.getOldValue();
        return value != null ? value.toString() : null;
    }

    private static TrackingGroup groupFor(ActionType type) {
        return switch (type) {
            case GUILD_UPDATE, GUILD_PROFILE_UPDATE -> TrackingGroup.SERVER_SETTINGS;
            case CHANNEL_CREATE, CHANNEL_UPDATE, CHANNEL_DELETE, CHANNEL_OVERRIDE_CREATE,
                 CHANNEL_OVERRIDE_UPDATE, CHANNEL_OVERRIDE_DELETE, THREAD_CREATE, THREAD_UPDATE,
                 THREAD_DELETE, VOICE_CHANNEL_STATUS_UPDATE, VOICE_CHANNEL_STATUS_DELETE,
                 STAGE_INSTANCE_CREATE, STAGE_INSTANCE_UPDATE, STAGE_INSTANCE_DELETE -> TrackingGroup.CHANNELS_THREADS;
            case ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE, APPLICATION_COMMAND_PRIVILEGES_UPDATE -> TrackingGroup.ROLES_PERMISSIONS;
            case KICK, PRUNE, BAN, UNBAN, MEMBER_UPDATE, MEMBER_ROLE_UPDATE, MEMBER_VOICE_MOVE,
                 MEMBER_VOICE_KICK, BOT_ADD, AUTO_MODERATION_RULE_CREATE, AUTO_MODERATION_RULE_UPDATE,
                 AUTO_MODERATION_RULE_DELETE, AUTO_MODERATION_RULE_BLOCK_MESSAGE,
                 AUTO_MODERATION_FLAG_TO_CHANNEL, AUTO_MODERATION_MEMBER_TIMEOUT,
                 AUTO_MODERATION_QUARANTINE_USER -> TrackingGroup.MEMBERS_MODERATION;
            case MESSAGE_DELETE, MESSAGE_BULK_DELETE, MESSAGE_PIN, MESSAGE_UNPIN -> TrackingGroup.MESSAGES;
            case INVITE_CREATE, INVITE_UPDATE, INVITE_DELETE, WEBHOOK_CREATE, WEBHOOK_UPDATE,
                 WEBHOOK_REMOVE, EMOJI_CREATE, EMOJI_UPDATE, EMOJI_DELETE, STICKER_CREATE,
                 STICKER_UPDATE, STICKER_DELETE, SOUNDBOARD_SOUND_CREATE, SOUNDBOARD_SOUND_UPDATE,
                 SOUNDBOARD_SOUND_DELETE, SCHEDULED_EVENT_CREATE, SCHEDULED_EVENT_UPDATE,
                 SCHEDULED_EVENT_DELETE, ONBOARDING_PROMPT_CREATE, ONBOARDING_PROMPT_UPDATE,
                 ONBOARDING_PROMPT_DELETE, ONBOARDING_CREATE, ONBOARDING_UPDATE, INTEGRATION_CREATE,
                 INTEGRATION_UPDATE, INTEGRATION_DELETE, CREATOR_MONETIZATION_REQUEST_CREATED,
                 CREATOR_MONETIZATION_TERMS_ACCEPTED, HOME_SETTINGS_CREATE, HOME_SETTINGS_UPDATE -> TrackingGroup.SERVER_EXTRAS;
            default -> null; // MESSAGE_CREATE/UPDATE and anything JDA adds later that we haven't triaged
        };
    }

    /** {@code "ROLE_CREATE"} -> {@code "Role Create"} — good enough for a log line; not worth 50 hand-written labels for a first pass. */
    private static String prettyLabel(ActionType type) {
        String[] words = type.name().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private String skillEmojiMention(String skillName) {
        for (int skillId = 0; skillId < RuneScapeSkillCatalog.skillCount(); skillId++) {
            if (RuneScapeSkillCatalog.nameFor(skillId).equalsIgnoreCase(skillName)) {
                return skillEmojiCatalog.mentionFor(skillId);
            }
        }
        return null;
    }

    private static String lowerFirst(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * Every RuneMetrics-sourced line gets an icon, one way or another — {@code iconMention} if the
     * caller found a specific one, otherwise a default appropriate to the group: {@link BossCatalog}'s
     * generic icon for {@link TrackingGroup#BOSS_KILLS}, the Clan Citadel's own icon for
     * {@link TrackingGroup#CITADEL_ACTIVITY}, or the generic RuneScore icon for anything else with no
     * icon system of its own (e.g. {@link TrackingGroup#MINIGAME_MISC}) — a loot beam only makes sense
     * for an actual drop, not a general achievement. Never blank.
     */
    private Optional<ClassifiedEntry> entry(TrackingGroup group, String iconMention, String message) {
        String icon = iconMention != null ? iconMention : defaultIconFor(group);
        String line = icon != null ? icon + " " + message : message;
        return Optional.of(new ClassifiedEntry(group, line));
    }

    private String defaultIconFor(TrackingGroup group) {
        return switch (group) {
            case BOSS_KILLS -> trackingIconCatalog.mentionForDefaultBoss();
            case CITADEL_ACTIVITY -> trackingIconCatalog.mentionForCategory("citadel");
            default -> trackingIconCatalog.mentionForCategory("runescore");
        };
    }
}
