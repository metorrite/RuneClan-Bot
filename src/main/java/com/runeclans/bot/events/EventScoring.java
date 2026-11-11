package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.ObjectiveSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the progress log into standings. Nothing here is stored: the log is the truth, and anyone can recompute the same answer from it.
 * An objective completes once its amounts add up to the target, and counts again for every further multiple of the target up to its repeat
 * limit; points are earned per completion. Pure, so it can be tested.
 */
public final class EventScoring {
    /** One entry of the progress log, for one subject (a player, or a team when the event scores by team). Negative amounts reverse earlier ones. */
    public record Entry(String subject, String objectiveKey, long amount, Instant at) {}

    /**
     * One subject's result. {@code completions} counts per objective key; {@code lastCompletedAt} breaks ties (earlier wins): the moment the
     * subject's points last rose to their final total, so progress later taken back, and 0-point objectives, don't move it (B-76).
     */
    public record Standing(String subject, long points, int objectivesCompleted, Map<String, Integer> completions, Instant lastCompletedAt) {}

    private EventScoring() {}

    /** How many times an objective has counted for something that has made {@code total} progress on it. */
    public static int completions(ObjectiveSpec objective, long total) {
        if (total <= 0) return 0;
        long times = total / objective.target();
        return (int) Math.min(times, objective.repeatLimit());
    }

    /** Standings for every subject that has any progress, best first (most points, then who finished earlier, then the subject key for a stable order). */
    public static List<Standing> standings(List<ObjectiveSpec> objectives, List<Entry> log) {
        return standings(objectives, log, List.of());
    }

    /** The same, with a bonus added for every group of objectives a subject has completed in full. */
    public static List<Standing> standings(List<ObjectiveSpec> objectives, List<Entry> log, List<EventBonuses.Bonus> bonuses) {
        Map<String, ObjectiveSpec> byKey = new HashMap<>();
        for (ObjectiveSpec o : objectives) byKey.put(o.key(), o);

        List<Entry> ordered = new ArrayList<>(log);
        ordered.sort(Comparator.comparing(Entry::at));

        // every subject's points after each entry, in time order, so the tie time can be read once the final total is known
        Map<String, Map<String, Long>> totals = new LinkedHashMap<>();
        Map<String, List<Step>> steps = new HashMap<>();
        for (Entry e : ordered) {
            ObjectiveSpec o = byKey.get(e.objectiveKey());
            if (o == null) continue; // an objective removed after progress was logged simply stops counting
            Map<String, Long> mine = totals.computeIfAbsent(e.subject(), s -> new HashMap<>());
            long before = mine.getOrDefault(o.key(), 0L);
            long after = Math.max(0, before + e.amount());
            mine.put(o.key(), after);
            if (completions(o, after) != completions(o, before)) steps.computeIfAbsent(e.subject(), s -> new ArrayList<>()).add(new Step(e.at(), score(objectives, mine, bonuses).points()));
        }

        List<Standing> standings = new ArrayList<>();
        for (var entry : totals.entrySet()) {
            Score score = score(objectives, entry.getValue(), bonuses);
            Instant reached = reachedAt(steps.getOrDefault(entry.getKey(), List.of()), score.points());
            standings.add(new Standing(entry.getKey(), score.points(), score.completions().size(), score.completions(), reached));
        }
        standings.sort(Comparator.comparingLong(Standing::points).reversed()
                .thenComparing(s -> s.lastCompletedAt() == null ? Instant.MAX : s.lastCompletedAt())
                .thenComparing(Standing::subject));
        return standings;
    }

    private record Step(Instant at, long points) {}

    private record Score(long points, Map<String, Integer> completions) {}

    /** A subject's points (completions times points, plus every bonus earned) and completions per objective, from their totals. */
    private static Score score(List<ObjectiveSpec> objectives, Map<String, Long> totals, List<EventBonuses.Bonus> bonuses) {
        Map<String, Integer> completions = new LinkedHashMap<>();
        long points = 0;
        for (ObjectiveSpec o : objectives) {
            int times = completions(o, totals.getOrDefault(o.key(), 0L));
            if (times == 0) continue;
            completions.put(o.key(), times);
            points += o.points() * times;
        }
        for (EventBonuses.Bonus bonus : EventBonuses.earned(bonuses, completions)) points += bonus.points();
        return new Score(points, completions);
    }

    /**
     * When the subject last went from below their final points to at or above them: they have held that total (or more) ever since. Null when they
     * never had to climb to it (a total of 0 or less), so they sort after anyone who did.
     */
    private static Instant reachedAt(List<Step> steps, long finalPoints) {
        long points = 0;
        Instant reached = null;
        for (Step step : steps) {
            if (points < finalPoints && step.points() >= finalPoints) reached = step.at();
            points = step.points();
        }
        return reached;
    }

    /** Whether an objective is open to a subject: every prerequisite has completed at least once for them. */
    public static boolean available(ObjectiveSpec objective, Map<String, Long> subjectTotals, Map<String, ObjectiveSpec> byKey) {
        for (String prerequisite : objective.prerequisites()) {
            ObjectiveSpec p = byKey.get(prerequisite);
            if (p == null) return false;
            if (completions(p, subjectTotals.getOrDefault(prerequisite, 0L)) < 1) return false;
        }
        return true;
    }
}
