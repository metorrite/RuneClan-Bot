package com.runeclans.bot.internal;

import com.runeclans.bot.tracking.BossCatalog;
import com.runeclans.bot.tracking.DropItemCatalog;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sorts a RuneMetrics adventure-log line into a kind the website can icon and filter on, and picks the
 * boss/kill count or drop out of the lines that carry one. The hub's event code ({@code type}) picks the kind; the text only reads names and counts out of a line.
 * Anything it doesn't recognise is {@link Kind#OTHER}, which the site's feed simply leaves out.
 */
final class ActivityKinds {
    private ActivityKinds() {}

    enum Kind { LEVEL_UP, XP_MILESTONE, QUEST, BOSS, DROP, PET, CITADEL_CAP, CITADEL_VISIT, CLUE, CHALLENGE, OTHER }

    private static final Pattern LEVEL_UP = Pattern.compile("^Levelled up (.+?)\\.?$");
    private static final Pattern XP_MILESTONE = Pattern.compile("^([\\d,]+)XP in (.+)$");
    private static final Pattern QUEST = Pattern.compile("^Quest complete: ?(.+)$");
    private static final Pattern PET = Pattern.compile("^I found (.+?), the (.+?) pet\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHALLENGE = Pattern.compile("Won a challenge against the (.+?) Champion", Pattern.CASE_INSENSITIVE);
    private static final Pattern KILLED = Pattern.compile("^I (?:killed|defeated) (\\d[\\d,]*)? ?(.+?)\\.?$");

    /** Longest boss names first, so "Kalphite King" is never mistaken for a shorter name it contains. */
    private static final List<BossCatalog.Boss> BOSSES_BY_LENGTH = BossCatalog.all().stream()
            .sorted(Comparator.comparingInt((BossCatalog.Boss b) -> b.name().length()).reversed()).toList();

    /** The hub's event codes (the adventure log's own {@code type}); the code decides the kind, the text only picks within one code. */
    private static final Set<String> LEVEL_UP_CODES = Set.of("rs_plog_levelup_count", "rs_plog_levelup");
    private static final Set<String> KILL_CODES = Set.of("rs_plog_npc_defeat", "rs_plog_npc_kill_count", "rs_plog_npc_kill");
    private static final Set<String> FOUND_CODES = Set.of("rs_plog_item_drop", "rs_plog_item_found", "rs_plog_item_found_nowhere");
    private static final Set<String> PET_CODES = Set.of("rs_plog_item_found", "rs_plog_item_found_nowhere", "rs_plog_item_drop", "rs_plog_special_event");

    /** The kind of a line by its event code; a line with no code ({@code null}) or a code we don't sort is {@link Kind#OTHER}. */
    static Kind kindOf(String type, String text) {
        if (type == null || text == null) return Kind.OTHER;
        if ("rs_plog_quest".equals(type)) return QUEST.matcher(text).matches() ? Kind.QUEST : Kind.OTHER;
        if (LEVEL_UP_CODES.contains(type)) return Kind.LEVEL_UP;
        if ("rs_plog_xp_milestone".equals(type)) return XP_MILESTONE.matcher(text).matches() ? Kind.XP_MILESTONE : Kind.OTHER;
        if ("rs_plog_champions_challenge".equals(type)) return CHALLENGE.matcher(text).find() ? Kind.CHALLENGE : Kind.OTHER;
        if ("rs_plog_finished_trail".equals(type)) return Kind.CLUE;
        if ("rs_plog_special_event".equals(type)) {
            if (text.startsWith("Capped at my Clan Citadel")) return Kind.CITADEL_CAP;
            if (text.startsWith("Visited my Clan Citadel")) return Kind.CITADEL_VISIT;
        }
        if (KILL_CODES.contains(type)) return bossOf(text).isPresent() ? Kind.BOSS : Kind.OTHER;
        if (PET_CODES.contains(type) && isPet(text)) return Kind.PET;
        if (FOUND_CODES.contains(type) && dropOf(text).isPresent()) return Kind.DROP;
        return Kind.OTHER;
    }

    private static boolean isPet(String text) {
        return PET.matcher(text).matches() || text.contains("I adopted TzRek-Jad") || text.toLowerCase().contains("effigy pet");
    }

    /**
     * The boss a kill line is about, if it is one. The tracking catalogue's names win where they match; for
     * any other boss (the catalogue is short) the name is read straight off the line — article dropped, cut at
     * a comma ("Amascut, the Devourer" → "Amascut"), and un-pluralised when the line gives a count.
     */
    static Optional<String> bossOf(String text) {
        if (!(text.startsWith("I killed") || text.startsWith("I defeated"))) return Optional.empty();
        for (BossCatalog.Boss boss : BOSSES_BY_LENGTH) {
            if (text.contains(boss.name())) return Optional.of(boss.name());
        }

        Matcher m = KILLED.matcher(text);
        if (!m.matches()) return Optional.empty();
        String name = m.group(2).trim().replaceFirst("^(?:an?|the) ", "");
        int comma = name.indexOf(',');
        if (comma > 0) name = name.substring(0, comma).trim();
        if (m.group(1) != null && killCount(text) != 1) name = singular(name);
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    /** "Amascuts" → "Amascut", "Nexes" → "Nex"; names that end like a singular ("Elidinis", "Araxxi") are left alone. */
    static String singular(String name) {
        if (name.endsWith("ies")) return name.substring(0, name.length() - 3) + "y";
        if (name.endsWith("xes")) return name.substring(0, name.length() - 2);
        if (name.endsWith("s") && !name.endsWith("ss") && !name.endsWith("us") && !name.endsWith("is")) return name.substring(0, name.length() - 1);
        return name;
    }

    /** How many kills the line reports — a bare "I killed Vorago." is one. */
    static int killCount(String text) {
        Matcher m = KILLED.matcher(text);
        if (m.matches() && m.group(1) != null) {
            try {
                return Integer.parseInt(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                // fall through to 1
            }
        }
        return 1;
    }

    /** The skill a "Levelled up Attack." line names. */
    static Optional<String> levelUpSkill(String text) {
        Matcher m = LEVEL_UP.matcher(text);
        return m.matches() ? Optional.of(m.group(1).trim()).filter(s -> !s.isBlank()) : Optional.empty();
    }

    /** The quest a "Quest complete: …" line names. */
    static Optional<String> questName(String text) {
        Matcher m = QUEST.matcher(text);
        return m.matches() ? Optional.of(m.group(1).trim().replaceFirst("\\.$", "")).filter(s -> !s.isBlank()) : Optional.empty();
    }

    /** An XP milestone line ("50000000XP in Fishing"): the skill and the XP. */
    record Milestone(String skill, long xp) {}

    static Optional<Milestone> milestone(String text) {
        Matcher m = XP_MILESTONE.matcher(text);
        if (!m.matches()) return Optional.empty();
        try {
            return Optional.of(new Milestone(m.group(2).trim().replaceFirst("\\.$", ""), Long.parseLong(m.group(1).replace(",", ""))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The pet a pet line names ("I found Shrimpy, the Fishing pet." → "Shrimpy"). */
    static Optional<String> petName(String text) {
        Matcher m = PET.matcher(text);
        if (m.matches()) return Optional.of(m.group(1).trim());
        return text.contains("I adopted TzRek-Jad") ? Optional.of("TzRek-Jad") : Optional.empty();
    }

    /** The catalogued drop a "I found …" line names, if it is one. */
    static Optional<String> dropOf(String text) {
        if (!text.startsWith("I found")) return Optional.empty();
        if (PET.matcher(text).matches()) return Optional.empty();
        return DropItemCatalog.findIn(text).map(DropItemCatalog.DropItem::name);
    }
}
