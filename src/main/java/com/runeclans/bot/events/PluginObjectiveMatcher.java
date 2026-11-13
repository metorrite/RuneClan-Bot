package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.plugin.PluginEventType;
import com.runeclans.bot.runescape.RuneScapeSkillCatalog;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which objectives of an event one thing the plugin reported counts towards. Pure, so every rule can be tested.
 * <ul>
 *   <li>A {@code DROP} counts towards an objective for that item: a bingo tile or scavenger objective whose label is the item, or
 *       {@code "Boss: item"} (when the plugin says which monster dropped it, that has to be the tile's boss too).</li>
 *   <li>A {@code KILL} counts one kill towards a boss night for that boss.</li>
 *   <li>In a bingo or scavenger hunt (T-061), the other achievement types count towards objectives labelled for them:
 *     <ul>
 *       <li>{@code CLUE}: a clue tile such as "Elite clue", "Clue scroll (hard)" or "Any clue" (a label with no tier takes any tier);</li>
 *       <li>{@code LEVEL_UP}: "99 Fletching", "Level 90 Herblore" or "Herblore 90", when the new level reaches it;</li>
 *       <li>{@code COLLECTION_LOG}: "Collection log: item" or "Log: item" (a plain item label is left to {@code DROP}, so one item never
 *           counts twice);</li>
 *       <li>{@code QUEST_COMPLETE}: "Quest: name";</li>
 *       <li>{@code ACHIEVEMENT}: "Achievement: name".</li>
 *     </ul>
 *   Each counts 1.</li>
 *   <li>XP tiles ("Gain 1M Fletching XP", "500k XP in Mining", T-543) don't take plugin reports: the hub's tracking jobs read them from
 *       RuneClans hub's XP ledger ({@link #xpGoal}, {@link #xpProgress}), and only while {@code EVENT_XP_TILES_ENABLED} is on.</li>
 *   <li>{@code XP} and {@code PRESENCE} are not matched. Objectives that don't take automatic progress ({@code PROOF} or {@code ORGANISER}
 *       evidence) never match.</li>
 * </ul>
 */
public final class PluginObjectiveMatcher {
    /** Progress to record: {@code amount} towards the objective. */
    public record Hit(String objectiveKey, long amount) {}

    /** What an objective label means, as {@link BingoTiles#resolve} works it out. */
    public interface Labels extends Function<String, BingoTiles.Ref> {}

    private static final Set<String> CLUE_TIERS = Set.of("easy", "medium", "hard", "elite", "master");
    /** The words a clue tile may have besides its tier: "Clue scroll (elite)", "Complete a hard clue", "Any treasure trail". */
    private static final Pattern CLUE_FILLER = Pattern.compile("\\b(clue|clues|scroll|scrolls|treasure|trail|trails|complete|completed|an?|any|tier|casket|caskets)\\b");
    private static final Pattern LEVEL_FIRST = Pattern.compile("^(?:level\\s+)?(\\d{1,3})\\s+([a-z][a-z ]*)$");
    private static final Pattern SKILL_FIRST = Pattern.compile("^([a-z][a-z ]*?)\\s+(?:level\\s+)?(\\d{1,3})$");

    /** "1M", "500k", "2.5m", "1,000,000": the number and its suffix. */
    private static final String AMOUNT = "(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s*([kmb])?";
    private static final String XP_WORD = "(?:xp|exp|experience)";
    /** "Gain 1M Fletching XP", "1m xp", "Gain 2M total XP". */
    private static final Pattern XP_SKILL_FIRST = Pattern.compile("^(?:gain\\s+)?" + AMOUNT + "\\s+(?:([a-z]+)\\s+)?" + XP_WORD + "$");
    /** "500k XP in Mining", "Gain 1M XP in Fletching". */
    private static final Pattern XP_IN_SKILL = Pattern.compile("^(?:gain\\s+)?" + AMOUNT + "\\s+" + XP_WORD + "\\s+in\\s+([a-z]+)$");
    /** No tile asks for more than every skill's 200M. */
    private static final long MAX_XP_GOAL = 10_000_000_000L;
    /** Short names players write on XP tiles ("Gain 1M WC XP", "500k Necro XP"), as the skill's full name. */
    private static final java.util.Map<String, String> SKILL_ALIASES = java.util.Map.ofEntries(
            java.util.Map.entry("att", "attack"), java.util.Map.entry("atk", "attack"), java.util.Map.entry("def", "defence"), java.util.Map.entry("defense", "defence"),
            java.util.Map.entry("str", "strength"), java.util.Map.entry("hp", "constitution"), java.util.Map.entry("hitpoints", "constitution"),
            java.util.Map.entry("range", "ranged"), java.util.Map.entry("ranging", "ranged"), java.util.Map.entry("pray", "prayer"), java.util.Map.entry("mage", "magic"),
            java.util.Map.entry("cook", "cooking"), java.util.Map.entry("wc", "woodcutting"), java.util.Map.entry("fletch", "fletching"), java.util.Map.entry("fish", "fishing"),
            java.util.Map.entry("fm", "firemaking"), java.util.Map.entry("craft", "crafting"), java.util.Map.entry("smith", "smithing"), java.util.Map.entry("mine", "mining"),
            java.util.Map.entry("herb", "herblore"), java.util.Map.entry("agi", "agility"), java.util.Map.entry("agil", "agility"), java.util.Map.entry("thiev", "thieving"),
            java.util.Map.entry("thieve", "thieving"), java.util.Map.entry("slay", "slayer"), java.util.Map.entry("farm", "farming"), java.util.Map.entry("rc", "runecrafting"),
            java.util.Map.entry("runecraft", "runecrafting"), java.util.Map.entry("rcing", "runecrafting"),
            java.util.Map.entry("hunt", "hunter"), java.util.Map.entry("con", "construction"), java.util.Map.entry("cons", "construction"), java.util.Map.entry("summ", "summoning"),
            java.util.Map.entry("summon", "summoning"), java.util.Map.entry("dung", "dungeoneering"), java.util.Map.entry("dg", "dungeoneering"), java.util.Map.entry("dungeoneer", "dungeoneering"),
            java.util.Map.entry("div", "divination"), java.util.Map.entry("inv", "invention"), java.util.Map.entry("invent", "invention"), java.util.Map.entry("arch", "archaeology"),
            java.util.Map.entry("arc", "archaeology"), java.util.Map.entry("necro", "necromancy"));
    /**
     * A remark in brackets ("(any method)"); it and trailing punctuation ("!") are dropped before an XP tile's label is read. A remark on a label
     * that names no skill ("Gain 1M XP (skilling only)") limits the XP in a way the ledger can't tell, so that label isn't an XP tile.
     */
    private static final Pattern REMARK = Pattern.compile("\\([^)]*\\)");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\p{Punct}\\s]+$");

    /** Whether XP tiles are counted: the hub counts them from RuneSync's XP (T-885), so this is on whenever the bot talks to the hub (set at startup by {@code HubEvents}). */
    private static volatile boolean xpTilesOffered;

    private PluginObjectiveMatcher() {}

    public static void offerXpTiles(boolean offered) {
        xpTilesOffered = offered;
    }

    public static boolean xpTilesOffered() {
        return xpTilesOffered;
    }

    /** An XP tile's goal (T-543): {@code xp} gained in {@code skillId}, or in total XP for {@link CompetitionTemplate#OVERALL}. */
    public record XpGoal(int skillId, long xp) {
        /** "Fletching XP", "total XP". */
        public String what() {
            return (skillId == CompetitionTemplate.OVERALL ? "total" : RuneScapeSkillCatalog.nameFor(skillId)) + " XP";
        }
    }

    /**
     * The XP goal a tile label asks for, or null when it isn't an XP tile. A label with no skill (or "total", "overall") is total XP, unless it
     * carries a remark ({@link #REMARK}).
     */
    public static XpGoal xpGoal(String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.toLowerCase(Locale.ROOT);
        String bare = REMARK.matcher(label).replaceAll(" ");
        boolean remark = !bare.equals(label);
        label = TRAILING_PUNCTUATION.matcher(bare).replaceAll("").strip().replaceAll("\\s+", " ");
        Matcher m = XP_SKILL_FIRST.matcher(label);
        if (!m.matches()) m = XP_IN_SKILL.matcher(label);
        if (!m.matches()) return null;
        String skill = m.group(3) == null ? "total" : m.group(3);
        int skillId = skillId(skill);
        if (skillId == Integer.MIN_VALUE || remark && skillId == CompetitionTemplate.OVERALL) return null;
        BigDecimal amount = new BigDecimal(m.group(1).replace(",", ""));
        if (m.group(2) != null) amount = amount.movePointRight(switch (m.group(2)) {
            case "k" -> 3;
            case "m" -> 6;
            default -> 9;
        });
        long xp = amount.setScale(0, RoundingMode.DOWN).min(BigDecimal.valueOf(MAX_XP_GOAL + 1)).longValue();
        return xp <= 0 || xp > MAX_XP_GOAL ? null : new XpGoal(skillId, xp);
    }

    /**
     * Progress an XP tile has earned for {@code gained} XP: the objective's target is reached when the gain reaches the goal, so a tile with target 1
     * takes 1 at the goal, and a tile whose target is the XP itself fills as the XP comes in. Never more than the tile can hold (target × repeats).
     */
    public static long xpProgress(XpGoal goal, ObjectiveSpec objective, long gained) {
        if (gained <= 0 || objective.target() <= 0) return 0;
        BigInteger earned = BigInteger.valueOf(gained).multiply(BigInteger.valueOf(objective.target())).divide(BigInteger.valueOf(goal.xp()));
        long capacity = objective.target() * Math.max(1, objective.repeatLimit());
        return earned.min(BigInteger.valueOf(capacity)).longValue();
    }

    /** Whether an event of this template (a clan-vs-clan event's game) counts XP tiles: only bingo and scavenger hunts do. */
    public static boolean countsXpTiles(String template) {
        return BingoTemplate.KEY.equals(template) || ScavengerHuntTemplate.KEY.equals(template);
    }

    /** The objectives of an event that are XP tiles taking automatic progress, with their goals; empty for anything but bingo and scavenger hunts. */
    public static java.util.Map<ObjectiveSpec, XpGoal> xpTiles(String template, List<ObjectiveSpec> objectives) {
        java.util.Map<ObjectiveSpec, XpGoal> out = new java.util.LinkedHashMap<>();
        if (!countsXpTiles(template)) return out;
        for (ObjectiveSpec o : objectives) {
            XpGoal goal = takesAutomatic(o) ? xpGoal(o.label()) : null;
            if (goal != null) out.put(o, goal);
        }
        return out;
    }

    /**
     * The XP tile labels a public event page may list (each once): a bingo's always (its board is public anyway); a scavenger hunt's only once it
     * runs, and a step that unlocks after another only once the hunt is over, so the page never gives a step away early.
     */
    public static List<String> shownXpTiles(String template, EventRules.Status status, List<ObjectiveSpec> objectives) {
        boolean hunt = ScavengerHuntTemplate.KEY.equals(template);
        boolean over = status == EventRules.Status.DONE;
        if (hunt && status != EventRules.Status.RUNNING && status != EventRules.Status.SCORING && !over) return List.of();
        java.util.Set<String> labels = new java.util.LinkedHashSet<>();
        for (ObjectiveSpec o : xpTiles(template, objectives).keySet()) if (!hunt || over || o.prerequisites().isEmpty()) labels.add(o.label());
        return List.copyOf(labels);
    }

    private static int skillId(String word) {
        if (word.equals("total") || word.equals("overall")) return CompetitionTemplate.OVERALL;
        String wanted = SKILL_ALIASES.getOrDefault(word, word);
        for (int id = 0; id < RuneScapeSkillCatalog.skillCount(); id++) {
            if (RuneScapeSkillCatalog.nameFor(id).toLowerCase(Locale.ROOT).equals(wanted)) return id;
        }
        return Integer.MIN_VALUE;
    }

    public static List<Hit> match(String template, String config, List<ObjectiveSpec> objectives, PluginEventType type, String sourceName, DataObject data, Labels labels) {
        List<Hit> hits = new ArrayList<>();
        if (type == PluginEventType.KILL && BossNightTemplate.KEY.equals(template)) {
            String wanted = bossOf(config);
            String killed = text(data, "bossName");
            if (!wanted.isEmpty() && wanted.equalsIgnoreCase(killed)) {
                for (ObjectiveSpec objective : objectives) {
                    if (objective.key().equals(BossNightTemplate.OBJECTIVE_KEY) && takesAutomatic(objective)) hits.add(new Hit(objective.key(), 1));
                }
            }
            return hits;
        }
        if (type == PluginEventType.DROP && !CompetitionTemplate.KEY.equals(template) && !BossNightTemplate.KEY.equals(template)) {
            String item = text(data, "itemName");
            if (item.isEmpty()) return hits;
            long quantity = Math.max(1, number(data, "quantity", 1));
            for (ObjectiveSpec objective : objectives) {
                if (!takesAutomatic(objective)) continue;
                BingoTiles.Ref ref = labels.apply(objective.label());
                boolean sameItem = item.equalsIgnoreCase(ref.item()) || item.equalsIgnoreCase(objective.label().strip());
                if (!sameItem) continue;
                // the plugin may name the monster that dropped it; a tile for another boss's drop doesn't count then
                if (ref.boss() != null && sourceName != null && !sourceName.isBlank() && !sourceName.strip().toLowerCase(Locale.ROOT).contains(ref.boss().toLowerCase(Locale.ROOT))
                        && !ref.boss().toLowerCase(Locale.ROOT).contains(sourceName.strip().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                hits.add(new Hit(objective.key(), quantity));
            }
            return hits;
        }
        if (CompetitionTemplate.KEY.equals(template) || BossNightTemplate.KEY.equals(template)) return hits;
        for (ObjectiveSpec objective : objectives) {
            if (!takesAutomatic(objective)) continue;
            String label = objective.label() == null ? "" : objective.label().strip().toLowerCase(Locale.ROOT);
            boolean counts = switch (type) {
                case CLUE -> clueTile(label, text(data, "tier"));
                case LEVEL_UP -> levelTile(label, text(data, "skill"), number(data, "level", 0));
                case COLLECTION_LOG -> named(label, text(data, "itemName"), "collection log:", "log:");
                case QUEST_COMPLETE -> named(label, text(data, "quest"), "quest:");
                case ACHIEVEMENT -> named(label, text(data, "name"), "achievement:");
                default -> false;
            };
            if (counts) hits.add(new Hit(objective.key(), 1));
        }
        return hits;
    }

    /**
     * What the bingo editor says under a tile while it is typed (T-141): whether RuneSync can tick it by itself, in words. {@code auto} is
     * true only when a report RuneSync sends today would match this label under {@link #match}'s rules; {@code text} says why or why not.
     * Null for event types that don't use tile labels (competitions and boss nights) and for an empty label.
     */
    public record Hint(boolean auto, String text) {}

    /** The event types RuneSync reports today (see the RuneSync plugin's README, "What RuneSync sends"). */
    private static final String NOT_YET = "RuneSync doesn't report %s yet, so this tile needs proof or an organiser for now.";

    public static Hint explain(String template, String rawLabel, Labels labels, Function<String, Boolean> knownItem) {
        if (CompetitionTemplate.KEY.equals(template) || BossNightTemplate.KEY.equals(template)) return null;
        String label = rawLabel == null ? "" : rawLabel.strip().toLowerCase(Locale.ROOT);
        if (label.isEmpty()) return null;
        XpGoal goal = xpGoal(label);
        boolean remarked = goal == null && xpGoal(REMARK.matcher(label).replaceAll(" ")) != null; // "Gain 1M XP (skilling only)"
        if (goal != null || remarked) {
            if (!xpTilesOffered) return new Hint(false, "XP tiles need proof or an organiser.");
            if (!countsXpTiles(template)) return new Hint(false, "XP tiles count by themselves only in bingo and scavenger hunts, so this one needs proof or an organiser.");
            if (remarked) return new Hint(false, "RuneSync can't tell which XP the remark allows, so this tile needs proof or an organiser. An XP tile that names its skill "
                    + "(\"Gain 1M Fletching XP (any method)\") counts by itself.");
            String step = ScavengerHuntTemplate.KEY.equals(template) ? " A step that unlocks after another counts XP from when it unlocks." : "";
            return new Hint(true, "Auto with RuneSync: " + String.format(Locale.ROOT, "%,d", goal.xp()) + " " + goal.what() + " gained while the event runs, read from the "
                    + "RuneClans hub. In a team event the team's XP adds up." + step + " XP gained while RuneSync wasn't watching doesn't count, and players without RuneSync "
                    + "(Jagex hiscores only) make no progress on this tile.");
        }
        if (label.contains("clue") || label.contains("treasure trail")) {
            String rest = CLUE_FILLER.matcher(label.replaceAll("[^a-z ]", " ")).replaceAll(" ").strip().replaceAll("\\s+", " ");
            if (rest.isEmpty()) return new Hint(true, "Auto with RuneSync: any clue scroll the member completes (read from their game messages).");
            if (CLUE_TIERS.contains(rest)) return new Hint(true, "Auto with RuneSync: a " + rest + " clue scroll the member completes.");
            return new Hint(false, "Looks like a clue tile, but only one tier (easy, medium, hard, elite, master) or none can be in the name.");
        }
        if (LEVEL_FIRST.matcher(label).matches() || SKILL_FIRST.matcher(label).matches()) return new Hint(false, String.format(NOT_YET, "level-ups"));
        if (label.startsWith("collection log:") || label.startsWith("log:")) return new Hint(false, String.format(NOT_YET, "collection log entries"));
        if (label.startsWith("quest:")) return new Hint(false, String.format(NOT_YET, "quests"));
        if (label.startsWith("achievement:")) return new Hint(false, String.format(NOT_YET, "achievements"));
        BingoTiles.Ref ref = labels.apply(rawLabel.strip());
        String item = ref.item() == null ? rawLabel.strip() : ref.item();
        if (Boolean.TRUE.equals(knownItem.apply(item))) {
            String from = ref.boss() == null ? "" : " from " + ref.boss();
            return new Hint(true, "Auto with RuneSync: a drop of " + item + from + " (loot from an NPC the member kills).");
        }
        return new Hint(false, "Not an item the bot knows. RuneSync ticks it only if a drop's name is exactly \"" + item
                + "\". Pick the boss and item from the lists, or write \"Boss: item\".");
    }

    /** A clue tile for this tier: only clue words and at most one tier are left in the label, and that tier (if any) is the one reported. */
    static boolean clueTile(String label, String tier) {
        if (!label.contains("clue") && !label.contains("treasure trail")) return false;
        String rest = CLUE_FILLER.matcher(label.replaceAll("[^a-z ]", " ")).replaceAll(" ").strip().replaceAll("\\s+", " ");
        if (rest.isEmpty()) return true; // "Any clue": every tier
        return CLUE_TIERS.contains(rest) && rest.equalsIgnoreCase(tier.strip());
    }

    /** A level tile ("99 fletching", "level 90 herblore", "herblore 90") the reported level of that skill reaches. */
    static boolean levelTile(String label, String skill, long level) {
        if (skill.isEmpty() || level <= 0) return false;
        Matcher first = LEVEL_FIRST.matcher(label);
        Matcher second = SKILL_FIRST.matcher(label);
        String wantedSkill;
        long wantedLevel;
        if (first.matches()) {
            wantedLevel = Long.parseLong(first.group(1));
            wantedSkill = first.group(2);
        } else if (second.matches()) {
            wantedSkill = second.group(1);
            wantedLevel = Long.parseLong(second.group(2));
        } else {
            return false;
        }
        return wantedSkill.strip().equalsIgnoreCase(skill.strip()) && level >= wantedLevel;
    }

    /** "prefix name" for one of the prefixes, where name is what was reported. */
    private static boolean named(String label, String reported, String... prefixes) {
        if (reported.isEmpty()) return false;
        for (String prefix : prefixes) {
            if (label.startsWith(prefix) && label.substring(prefix.length()).strip().equalsIgnoreCase(reported.strip())) return true;
        }
        return false;
    }

    private static boolean takesAutomatic(ObjectiveSpec objective) {
        Evidence evidence = objective.evidence();
        return EventRules.evidenceAllows(evidence, Source.AUTO) && evidence != Evidence.ORGANISER;
    }

    private static String bossOf(String config) {
        try {
            DataObject json = DataObject.fromJson(config == null || config.isBlank() ? "{}" : config);
            return json.hasKey("boss") && !json.isNull("boss") ? json.getString("boss").strip() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String text(DataObject data, String key) {
        return data.hasKey(key) && !data.isNull(key) && data.opt(key).orElse(null) instanceof String s ? s.strip() : "";
    }

    private static long number(DataObject data, String key, long fallback) {
        return data.hasKey(key) && !data.isNull(key) && data.opt(key).orElse(null) instanceof Number n ? n.longValue() : fallback;
    }
}
