package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.runescape.RuneScapeSkillCatalog;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Competition: who gains the most XP in one skill (or overall) while the event runs. The bot reads each player's linked account from the
 * normal polling and adds their gain to the event as it grows ({@link EventTracker}); an organiser can still adjust anyone by hand.
 * Settings: {@code skillId} (a skill from {@link RuneScapeSkillCatalog}, or {@code -1} for total XP, the default) and {@code xpPerPoint}
 * (how much XP earns one point, 10,000 by default). Points are the XP gained divided by that, so a larger number keeps points small.
 * <p>
 * {@code xpSource} says where the gains come from: {@value #SOURCE_RUNEMETRICS} (the default, the polling above), {@value #SOURCE_PLUGIN}, the
 * RuneClans hub's XP ledger fed by RuneSync ({@link PluginXpTracker}, T-537), or {@value #SOURCE_AUTO} (T-557): RuneSync for each player the hub
 * can count, RuneMetrics for the rest, and RuneMetrics checks the RuneSync players' numbers. A player stays with whichever counted them first
 * ({@link CompetitionTracking#owners}). The plugin and auto choices are offered only while the bot is connected to the hub.
 * <p>
 * {@code wholeSpan} (set by skill of the week, T-541, never offered on the form): a RuneSync competition counts everyone's gains from the planned
 * start to the planned end, whenever they joined, so its standings are exactly the ledger's gains for that span.
 */
@BService
public class CompetitionTemplate implements EventTemplate {
    public static final String KEY = "competition";
    public static final String OBJECTIVE_KEY = "xp-gained";
    public static final int OVERALL = -1;
    public static final long DEFAULT_XP_PER_POINT = 10_000;
    public static final String SOURCE_RUNEMETRICS = "runemetrics";
    public static final String SOURCE_PLUGIN = "plugin";
    public static final String SOURCE_AUTO = "auto";

    /** Whether the plugin source can be offered: set once at startup by {@link PluginXpTracker} when the bot talks to the hub. */
    private static volatile boolean pluginSourceOffered;

    static void offerPluginSource(boolean offered) {
        pluginSourceOffered = offered;
    }

    public static boolean pluginSourceOffered() {
        return pluginSourceOffered;
    }

    @Override
    public String key() {
        return KEY;
    }

    /** Whether "Count confirmed links only" is offered (T-552). Off while linking is code-only on trust (T-815). */
    static final boolean CONFIRMED_LINKS_OFFERED = false;

    @Override
    public java.util.Set<String> fixedSettings() {
        return java.util.Set.of("skillId", "xpPerPoint", "xpSource", "confirmedLinksOnly", "wholeSpan");
    }

    @Override
    public String label() {
        return "Competition";
    }

    /** XP gained is read from the player's RuneScape name. */
    @Override
    public boolean needsRsn() {
        return true;
    }

    @Override
    public List<EventOptions.Option> options() {
        List<EventOptions.Choice> skills = new ArrayList<>();
        skills.add(new EventOptions.Choice(String.valueOf(OVERALL), "Overall (total XP)"));
        for (int id = 0; id < RuneScapeSkillCatalog.skillCount(); id++) skills.add(new EventOptions.Choice(String.valueOf(id), RuneScapeSkillCatalog.nameFor(id)));
        List<EventOptions.Option> list = new ArrayList<>(List.of(
                EventOptions.Option.choice("config.skillId", "Skill", "Whose XP counts: one skill, or total XP.", EventOptions.Step.RULES, (long) OVERALL, skills).numericChoices().widget("skill").inQuick(),
                EventOptions.Option.number("config.xpPerPoint", "XP per point", "How much XP earns one point. A larger number keeps points small.", EventOptions.Step.RULES,
                        DEFAULT_XP_PER_POINT, 1, EventRules.MAX_TARGET)));
        if (pluginSourceOffered) {
            list.add(EventOptions.Option.choice("config.xpSource", "Where XP comes from", "RuneMetrics reads public profiles every few minutes. RuneSync counts within a minute or two, private profiles too, but only "
                            + "players who run the RuneSync plugin and share XP with this bot; with RuneSync only, the others score nothing. Auto counts RuneSync players from RuneSync and everyone "
                            + "else from RuneMetrics. It can't be changed once the event exists.", EventOptions.Step.RULES, SOURCE_RUNEMETRICS,
                    List.of(new EventOptions.Choice(SOURCE_RUNEMETRICS, "RuneMetrics (public profiles)"), new EventOptions.Choice(SOURCE_PLUGIN, "RuneSync plugin"),
                            new EventOptions.Choice(SOURCE_AUTO, "Auto (RuneSync where players run it, else RuneMetrics)"))));
            // PARKED (T-815, Drake 2026-10-30): RuneLink links are taken on trust, so none is ever confirmed and this option would leave
            // everyone out. Events that already have it keep it (PluginXpTracker still honours it).
            if (CONFIRMED_LINKS_OFFERED) list.add(EventOptions.Option.of("config.confirmedLinksOnly", "Count confirmed links only", "Only players whose RuneLink the RuneClans hub has confirmed from "
                            + "public data score. The others are listed as not confirmed yet. It can't be changed once the event exists.", EventOptions.Step.RULES,
                    EventOptions.Type.BOOL, false).showWhen(EventOptions.When.is("config.xpSource", SOURCE_PLUGIN)));
        }
        return list;
    }

    @Override
    public String description() {
        return "Who gains the most XP in a skill (or overall) while the event runs. Progress comes from players' linked RuneScape accounts, so nobody has to send screenshots.";
    }

    private static long number(DataObject config, String key, long fallback) {
        try {
            return config.hasKey(key) && !config.isNull(key) ? config.getLong(key) : fallback;
        } catch (RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    /** The skill a competition is about: {@link #OVERALL} or a skill id. */
    public static int skillOf(DataObject config) {
        long id = number(config, "skillId", OVERALL);
        return id >= Integer.MIN_VALUE && id <= Integer.MAX_VALUE ? (int) id : Integer.MIN_VALUE;
    }

    /** Where a competition's gains come from: {@link #SOURCE_PLUGIN} or {@link #SOURCE_AUTO} when it says so, else {@link #SOURCE_RUNEMETRICS} (every event made before T-537). */
    public static String xpSourceOf(DataObject config) {
        String s = config.hasKey("xpSource") && !config.isNull("xpSource") ? String.valueOf(config.get("xpSource")) : null;
        return SOURCE_PLUGIN.equals(s) || SOURCE_AUTO.equals(s) ? s : SOURCE_RUNEMETRICS;
    }

    /** Counted from RuneSync only: every player, and nobody from RuneMetrics. */
    public static boolean countsFromPlugin(DataObject config) {
        return SOURCE_PLUGIN.equals(xpSourceOf(config));
    }

    /** A RuneSync competition that counts only players whose RuneLink the hub confirmed (T-552). Off unless it says so. */
    public static boolean confirmedLinksOnly(DataObject config) {
        return countsFromPlugin(config) && config.hasKey("confirmedLinksOnly") && !config.isNull("confirmedLinksOnly")
                && "true".equalsIgnoreCase(String.valueOf(config.get("confirmedLinksOnly")));
    }

    /** A RuneSync competition counted over its whole planned span (T-541). Off unless it says so. */
    public static boolean countsWholeSpan(DataObject config) {
        return countsFromPlugin(config) && config.hasKey("wholeSpan") && !config.isNull("wholeSpan") && "true".equalsIgnoreCase(String.valueOf(config.get("wholeSpan")));
    }

    /** Each player's XP counted from the tracker on the XP objective ({@link EventRules.Source#IMPORT} only, so an organiser's correction never changes it). */
    public static Map<Long, Long> importedXp(List<EventRepository.Progress> progress) {
        Map<Long, Long> xp = new HashMap<>();
        for (EventRepository.Progress p : progress) {
            if (p.source() == EventRules.Source.IMPORT && p.objectiveKey().equals(OBJECTIVE_KEY)) xp.merge(p.userId(), p.amount(), Long::sum);
        }
        return xp;
    }

    /**
     * A {@code wholeSpan} competition (skill of the week, T-541) is ranked by the XP itself: its points are each player's XP gained, most first and
     * the earlier id first on equal XP, so its standings are in the same order as {@link SkillWeekRules#winners}. Others keep the usual standings.
     */
    @Override
    public List<EventScoring.Standing> standings(EventRepository.Event event, List<ObjectiveSpec> objectives, List<EventRepository.Progress> progress,
                                                 List<EventScoring.Standing> usual) {
        if (event.scoringMode() != EventRules.ScoringMode.INDIVIDUAL || !countsWholeSpan(config(event))) return null;
        return byXp(usual, importedXp(progress));
    }

    static List<EventScoring.Standing> byXp(List<EventScoring.Standing> usual, Map<Long, Long> xp) {
        List<EventScoring.Standing> out = new ArrayList<>();
        for (EventScoring.Standing s : usual) {
            long gained = Math.max(0, xp.getOrDefault(userOf(s), 0L));
            out.add(new EventScoring.Standing(s.subject(), gained, s.objectivesCompleted(), s.completions(), s.lastCompletedAt()));
        }
        out.sort(Comparator.comparingLong(EventScoring.Standing::points).reversed().thenComparingLong(CompetitionTemplate::userOf));
        return out;
    }

    private static long userOf(EventScoring.Standing s) {
        try {
            return Long.parseLong(s.subject().substring(2));
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    private static DataObject config(EventRepository.Event event) {
        try {
            return DataObject.fromJson(event.config());
        } catch (RuntimeException e) {
            return DataObject.empty();
        }
    }

    /** Auto (T-557): RuneSync for the players the hub counts, RuneMetrics for the others. */
    public static boolean countsAuto(DataObject config) {
        return SOURCE_AUTO.equals(xpSourceOf(config));
    }

    /** Whether {@link PluginXpTracker} reads this competition from the hub (RuneSync only, or auto). */
    public static boolean readsPlugin(DataObject config) {
        return !SOURCE_RUNEMETRICS.equals(xpSourceOf(config));
    }

    public static boolean validSource(String source) {
        return SOURCE_RUNEMETRICS.equals(source) || SOURCE_PLUGIN.equals(source) || SOURCE_AUTO.equals(source);
    }

    /** The sources that need the bot connected to the hub. */
    public static boolean needsHub(String source) {
        return SOURCE_PLUGIN.equals(source) || SOURCE_AUTO.equals(source);
    }

    public static long xpPerPoint(DataObject config) {
        return number(config, "xpPerPoint", DEFAULT_XP_PER_POINT);
    }

    public static String skillName(int skillId) {
        return skillId == OVERALL ? "Overall" : RuneScapeSkillCatalog.nameFor(skillId);
    }

    @Override
    public List<String> configProblems(DataObject config) {
        List<String> problems = new ArrayList<>();
        int skill = skillOf(config);
        if (skill != OVERALL && (skill < 0 || skill >= RuneScapeSkillCatalog.skillCount())) problems.add("Choose a skill from the list, or overall XP.");
        long per = xpPerPoint(config);
        if (per < 1 || per > EventRules.MAX_TARGET) problems.add("XP per point has to be between 1 and " + EventRules.MAX_TARGET + ".");
        if (config.hasKey("xpSource") && !config.isNull("xpSource") && !validSource(String.valueOf(config.get("xpSource")))) {
            problems.add("XP comes from RuneMetrics, the RuneSync plugin, or auto.");
        }
        return problems;
    }

    @Override
    public List<ObjectiveSpec> starterObjectives(DataObject config) {
        int skill = skillOf(config);
        long per = xpPerPoint(config);
        String name = skillName(skill);
        String from = countsFromPlugin(config) ? " Counted from RuneSync." : countsAuto(config) ? " Counted from RuneSync where players run it, RuneMetrics for the rest." : "";
        String rule = countsWholeSpan(config) ? "Ranked by the XP gained from the start to the end, whenever you joined." // standings(): a point is an XP
                : "One point for every " + String.format("%,d", per) + " XP gained while the event runs.";
        return List.of(new ObjectiveSpec(OBJECTIVE_KEY, name + " XP gained", rule + from,
                1, Evidence.AUTO, per, EventRules.MAX_REPEAT, List.of()));
    }
}
