package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventScoring.Standing;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The rules of a clan-vs-clan event (T-222, design {@code docs/EVENTS-CLAN-VS-CLAN.md}): every team is a clan, a player's team is the participating clan
 * whose in-game roster has their linked name, and the clans compete on an inner game (bingo, competition, boss night or scavenger hunt) that the existing
 * templates run. Pure, so it can be tested.
 * <p>
 * Settings: {@code cvcGame} (the inner game, chosen when the event is made and kept), {@code cvc: {scoring, topN, minPlayers, reviewByOtherClan}}, and
 * {@code cvcTeams: {"<serverId>": teamId}} (the team of each participating clan, kept by JonnyBot).
 */
public final class ClanVsClan {
    public static final String KEY = "clan-vs-clan";
    public static final String GAME = "cvcGame";
    public static final String TEAMS = "cvcTeams";
    /** Bot-side only (never sent to the hub): each clan's local team id by its hub clan id, filled from the hub's {@code cvcTeams} by {@code HubEvents}. */
    public static final String TEAMS_HUB = "cvcTeamsHub";
    public static final List<String> GAMES = List.of(BingoTemplate.KEY, CompetitionTemplate.KEY, BossNightTemplate.KEY, ScavengerHuntTemplate.KEY);
    public static final int DEFAULT_TOP_N = 10;
    public static final int MAX_TOP_N = 100;
    public static final int DEFAULT_MIN_PLAYERS = 3;

    /** How a clan's score is made from its players' points, so a big clan doesn't win just by size. */
    public enum Scoring { TOP_N, AVERAGE, TOTAL }

    public record Settings(String game, Scoring scoring, int topN, int minPlayers, boolean reviewByOtherClan) {
        public static final Settings DEFAULT = new Settings(BingoTemplate.KEY, Scoring.TOP_N, DEFAULT_TOP_N, DEFAULT_MIN_PLAYERS, false);
    }

    private ClanVsClan() {}

    public static boolean is(String template) {
        return KEY.equals(template);
    }

    /** The game an event actually plays: its own template, or for a clan-vs-clan event the inner game. */
    public static String game(String template, String config) {
        if (!is(template)) return template;
        try {
            return settings(DataObject.fromJson(config == null || config.isBlank() ? "{}" : config)).game();
        } catch (RuntimeException e) {
            return BingoTemplate.KEY;
        }
    }

    /** {@link #game(String, String)} for an event. */
    public static String game(EventRepository.Event event) {
        return game(event.template(), event.config());
    }

    /** Whether an event plays {@code gameKey}, directly or as a clan-vs-clan inner game. */
    public static boolean plays(EventRepository.Event event, String gameKey) {
        return gameKey.equals(game(event));
    }

    public static Settings settings(DataObject config) {
        if (config == null) return Settings.DEFAULT;
        String game = config.hasKey(GAME) && !config.isNull(GAME) ? config.getString(GAME, BingoTemplate.KEY).strip().toLowerCase(Locale.ROOT) : BingoTemplate.KEY;
        if (!GAMES.contains(game)) game = BingoTemplate.KEY;
        if (!config.hasKey("cvc") || config.isNull("cvc")) return new Settings(game, Scoring.TOP_N, DEFAULT_TOP_N, DEFAULT_MIN_PLAYERS, false);
        try {
            DataObject c = config.getObject("cvc");
            Scoring scoring = scoringOf(c.getString("scoring", Scoring.TOP_N.name()));
            int topN = c.hasKey("topN") && !c.isNull("topN") ? c.getInt("topN") : DEFAULT_TOP_N;
            int min = c.hasKey("minPlayers") && !c.isNull("minPlayers") ? c.getInt("minPlayers") : DEFAULT_MIN_PLAYERS;
            boolean review = c.hasKey("reviewByOtherClan") && !c.isNull("reviewByOtherClan") && c.getBoolean("reviewByOtherClan");
            return new Settings(game, scoring == null ? Scoring.TOP_N : scoring, Math.min(Math.max(topN, 1), MAX_TOP_N), Math.max(min, 0), review);
        } catch (RuntimeException e) {
            return new Settings(game, Scoring.TOP_N, DEFAULT_TOP_N, DEFAULT_MIN_PLAYERS, false);
        }
    }

    private static Scoring scoringOf(String raw) {
        try {
            return Scoring.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What is wrong with the clan-vs-clan settings themselves (the inner game's own settings are checked by its template). */
    public static List<String> problems(DataObject config) {
        List<String> problems = new ArrayList<>();
        if (config.hasKey(GAME) && !config.isNull(GAME) && !GAMES.contains(config.getString(GAME, "").strip().toLowerCase(Locale.ROOT))) {
            problems.add("Choose what the clans play: bingo, competition, boss night or scavenger hunt.");
        }
        if (config.hasKey("cvc") && !config.isNull("cvc")) {
            try {
                DataObject c = config.getObject("cvc");
                if (c.hasKey("scoring") && !c.isNull("scoring") && scoringOf(c.getString("scoring")) == null) problems.add("Choose how clans are scored: best players, average or total.");
                if (c.hasKey("topN") && !c.isNull("topN") && (c.getInt("topN") < 1 || c.getInt("topN") > MAX_TOP_N)) problems.add("Count between 1 and " + MAX_TOP_N + " best players per clan.");
                if (c.hasKey("minPlayers") && !c.isNull("minPlayers") && c.getInt("minPlayers") < 0) problems.add("The fewest players a clan needs can't be negative.");
            } catch (RuntimeException e) {
                problems.add("The clan-vs-clan settings couldn't be read.");
            }
        }
        return problems;
    }

    /** The team of each participating clan, by server, from the settings. */
    public static Map<Long, Long> teams(DataObject config) {
        Map<Long, Long> teams = new LinkedHashMap<>();
        if (config == null || !config.hasKey(TEAMS) || config.isNull(TEAMS)) return teams;
        try {
            DataObject t = config.getObject(TEAMS);
            for (String key : t.keys()) teams.put(Long.parseLong(key), t.getLong(key));
        } catch (RuntimeException e) {
            // unreadable: no teams known, they are made again
        }
        return teams;
    }

    /** Each participating clan's local team id by hub clan id ({@link #TEAMS_HUB}); empty for an event the hub didn't send. */
    public static Map<String, Long> hubTeams(DataObject config) {
        Map<String, Long> teams = new LinkedHashMap<>();
        if (config == null || !config.hasKey(TEAMS_HUB) || config.isNull(TEAMS_HUB)) return teams;
        try {
            DataObject t = config.getObject(TEAMS_HUB);
            for (String key : t.keys()) teams.put(key, t.getLong(key));
        } catch (RuntimeException e) {
            // unreadable: none known
        }
        return teams;
    }

    // ---------- which team a player is on ----------

    /** Which participating clan's team a player goes on: one, none (they can't join), or a choice staff make (on two rosters, came through neither). */
    public sealed interface TeamChoice {
        record One(long teamId) implements TeamChoice {}

        record NoClan() implements TeamChoice {}

        record StaffChoose() implements TeamChoice {}
    }

    /**
     * The team for a player whose linked names are on the rosters of {@code clansOfPlayer} (servers), who came through {@code origin}. {@code teams} are the
     * participating clans' teams by server. On two rosters, the clan of the server they came through wins; otherwise staff choose.
     */
    public static TeamChoice teamFor(Set<Long> clansOfPlayer, long origin, Map<Long, Long> teams) {
        List<Long> mine = new ArrayList<>();
        for (var t : teams.entrySet()) if (clansOfPlayer.contains(t.getKey())) mine.add(t.getKey());
        if (mine.isEmpty()) return new TeamChoice.NoClan();
        if (mine.size() == 1) return new TeamChoice.One(teams.get(mine.get(0)));
        if (mine.contains(origin)) return new TeamChoice.One(teams.get(origin));
        return new TeamChoice.StaffChoose();
    }

    // ---------- scoring ----------

    /** One progress entry with who logged it and for which team. */
    public record Report(long userId, long teamId, String objectiveKey, long amount, Instant at) {}

    /**
     * What each player earned for their clan: replaying the log in time order, the points of every completion the clan made go to the player whose report
     * completed it. Nothing is counted twice (a tile two players both log counts once, for the first), and in a competition each threshold of XP crossed is
     * credited to the player whose gain crossed it. By team, then player.
     */
    public static Map<Long, Map<Long, Long>> contributions(List<ObjectiveSpec> objectives, List<Report> log) {
        Map<String, ObjectiveSpec> byKey = new HashMap<>();
        for (ObjectiveSpec o : objectives) byKey.put(o.key(), o);
        List<Report> ordered = new ArrayList<>(log);
        ordered.sort(Comparator.comparing(Report::at));
        Map<Long, Map<String, Long>> totals = new HashMap<>();
        Map<Long, Map<Long, Long>> earned = new LinkedHashMap<>();
        for (Report r : ordered) {
            ObjectiveSpec o = byKey.get(r.objectiveKey());
            if (o == null) continue;
            Map<String, Long> team = totals.computeIfAbsent(r.teamId(), t -> new HashMap<>());
            long before = team.getOrDefault(o.key(), 0L);
            long after = Math.max(0, before + r.amount());
            team.put(o.key(), after);
            int gained = EventScoring.completions(o, after) - EventScoring.completions(o, before);
            if (gained != 0) earned.computeIfAbsent(r.teamId(), t -> new LinkedHashMap<>()).merge(r.userId(), gained * o.points(), Long::sum);
        }
        return earned;
    }

    /**
     * The clans' standings. {@code teamStandings} are the plain team standings (objective points plus the board's bonuses, earned once per clan);
     * {@code TOTAL} keeps them. {@code TOP_N} replaces the objective points with the sum of the clan's best {@code topN} players' contributions, and
     * {@code AVERAGE} with their average over the players who scored; the bonuses still count once for the clan. Ties: the clan that got there first.
     */
    public static List<Standing> standings(Settings settings, List<Standing> teamStandings, List<ObjectiveSpec> objectives, Map<Long, Map<Long, Long>> contributions) {
        if (settings.scoring() == Scoring.TOTAL) return teamStandings;
        Map<String, ObjectiveSpec> byKey = new HashMap<>();
        for (ObjectiveSpec o : objectives) byKey.put(o.key(), o);
        List<Standing> out = new ArrayList<>();
        for (Standing s : teamStandings) {
            long objectivePoints = 0;
            for (var c : s.completions().entrySet()) {
                ObjectiveSpec o = byKey.get(c.getKey());
                if (o != null) objectivePoints += o.points() * c.getValue();
            }
            long bonus = s.points() - objectivePoints;
            long teamId = s.subject().startsWith("t:") ? Long.parseLong(s.subject().substring(2)) : -1;
            List<Long> players = new ArrayList<>(contributions.getOrDefault(teamId, Map.of()).values());
            players.removeIf(p -> p <= 0);
            players.sort(Comparator.reverseOrder());
            long base = switch (settings.scoring()) {
                case TOP_N -> players.stream().limit(settings.topN()).mapToLong(Long::longValue).sum();
                case AVERAGE -> players.isEmpty() ? 0 : Math.round(players.stream().mapToLong(Long::longValue).sum() / (double) players.size());
                case TOTAL -> objectivePoints;
            };
            out.add(new Standing(s.subject(), base + bonus, s.objectivesCompleted(), s.completions(), s.lastCompletedAt()));
        }
        out.sort(Comparator.comparingLong(Standing::points).reversed()
                .thenComparing(s -> s.lastCompletedAt() == null ? Instant.MAX : s.lastCompletedAt())
                .thenComparing(Standing::subject));
        return out;
    }
}
