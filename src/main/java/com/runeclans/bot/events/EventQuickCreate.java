package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventService.EventInput;
import com.runeclans.bot.runescape.RuneScapeSkillCatalog;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Turns the few things asked for by {@code /events create} (the kind of event, a name, how long it runs, and one optional detail) into a full
 * event with the template's usual settings, so a leader can start something from Discord and fine-tune it on the dashboard afterwards.
 * Pure, so it can be tested. {@link IllegalArgumentException}s carry a message that is safe to show.
 */
public final class EventQuickCreate {
    public static final int MAX_DAYS = 90;
    public static final int MAX_START_HOURS = 24 * 30;

    /** The kinds of event that can be made this way, with what the optional {@code detail} means for each. */
    public enum Kind {
        BINGO(BingoTemplate.KEY, "Bingo", "Board size, 3 to 7 (default 5), or a shape: path, pyramid, convergence or tree"),
        COMPETITION(CompetitionTemplate.KEY, "XP competition", "Skill name, or overall (default overall)"),
        BOSS_NIGHT(BossNightTemplate.KEY, "Boss night", "The boss (required)"),
        SCAVENGER_HUNT(ScavengerHuntTemplate.KEY, "Scavenger hunt", "How many items to find, 3 to 30 (default 8)");

        public final String template;
        public final String label;
        public final String detailHelp;

        Kind(String template, String label, String detailHelp) {
            this.template = template;
            this.label = label;
            this.detailHelp = detailHelp;
        }
    }

    /** What a quick bingo's board is drawn on: the choices {@code /events create} offers (see {@link BingoLooks}). */
    public enum Look {
        RUNESCAPE(BingoLooks.RUNESCAPE),
        TEMPLATE(BingoLooks.CLASSIC);

        public final String id;

        Look(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return BingoLooks.label(id);
        }
    }

    public record Plan(EventInput input, String summary) {}

    private EventQuickCreate() {}

    /** The skill a name stands for ({@code overall} included), or {@code null}. Case and spaces don't matter. */
    static Integer skillByName(String text) {
        String wanted = text.strip().toLowerCase(Locale.ROOT);
        if (wanted.equals("overall") || wanted.equals("total") || wanted.isEmpty()) return CompetitionTemplate.OVERALL;
        for (int id = 0; id < RuneScapeSkillCatalog.skillCount(); id++) {
            if (RuneScapeSkillCatalog.nameFor(id).toLowerCase(Locale.ROOT).equals(wanted)) return id;
        }
        return null;
    }

    private static int whole(String detail, int fallback, int min, int max, String what) {
        if (detail == null || detail.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(detail.strip());
            if (value < min || value > max) throw new IllegalArgumentException(what + " has to be between " + min + " and " + max + ".");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " has to be a number between " + min + " and " + max + ".");
        }
    }

    /** The board shape a bingo's detail names ("pyramid", "path", "convergence", "tree" or "world tree"), or null when it names none. */
    static BingoLayout shapeNamed(String detail) {
        if (detail == null) return null;
        String word = detail.strip().toLowerCase(java.util.Locale.ROOT);
        return BingoLayout.all().stream().filter(l -> l.id().equals(word) || l.name().toLowerCase(java.util.Locale.ROOT).contains(word) && word.length() >= 4).findFirst().orElse(null);
    }

    /**
     * @param days         how long the event runs once it starts
     * @param startInHours when it is planned to start, counted from {@code now}
     * @param detail       the kind's one optional setting; see {@link Kind#detailHelp}
     */
    public static Plan plan(Kind kind, String name, int days, int startInHours, String detail, Instant now) {
        return plan(kind, name, days, startInHours, detail, now, null);
    }

    /**
     * As above, and a bingo's board is drawn in {@code look} ({@link BingoLooks#ids}; the default for new events when null). Other kinds have no board and ignore it.
     */
    public static Plan plan(Kind kind, String name, int days, int startInHours, String detail, Instant now, String look) {
        return plan(kind, name, days, startInHours, detail, now, look, EventDefaults.NONE);
    }

    /**
     * As above, starting from the server's own defaults ({@link EventDefaults}): its name patterns, verification gate and bingo look (when no look was asked
     * for). The setup steps stay off whatever the server's default is: an event made from one command is started by hand.
     */
    public static Plan plan(Kind kind, String name, int days, int startInHours, String detail, Instant now, String look, EventDefaults defaults) {
        if (look != null && !BingoLooks.valid(look)) throw new IllegalArgumentException("Choose a board look from the list.");
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("An event can run for 1 to " + MAX_DAYS + " days.");
        if (startInHours < 0 || startInHours > MAX_START_HOURS) throw new IllegalArgumentException("It can start from now up to " + MAX_START_HOURS + " hours away.");
        // the standard answers for the kind (the same ones the dashboard's standard setup uses), with what was asked for laid over them
        EventDefaults own = defaults == null ? EventDefaults.NONE : defaults;
        DataObject answers = EventOptions.standard(templateFor(kind), now, own);
        // made from one Discord command, so it doesn't take the dashboard's setup steps: an organiser just starts it
        EventOptions.set(answers, "config.setup.mode", EventPrep.Mode.OFF.name());
        String about;
        switch (kind) {
            case BINGO -> {
                BingoLayout shape = shapeNamed(detail);
                String boardLook = look != null ? look : own.look() != null ? own.look() : BingoLooks.DEFAULT_FOR_NEW;
                EventOptions.set(answers, "config.board.look", boardLook);
                if (shape != null) {
                    EventOptions.set(answers, "config.board.layout", shape.id());
                    about = shape.name() + " board (" + shape.tiles() + " tiles)";
                } else {
                    int size = whole(detail, BingoTemplate.DEFAULT_SIZE, 3, 7, "The board size");
                    EventOptions.set(answers, "config.board.size", size);
                    about = size + "x" + size + " board";
                }
                about += " · " + BingoLooks.label(boardLook) + " look";
            }
            case COMPETITION -> {
                Integer skill = skillByName(detail == null ? "" : detail);
                if (skill == null) throw new IllegalArgumentException("I don't know the skill \"" + detail.strip() + "\". Use a skill name like Slayer, or overall.");
                EventOptions.set(answers, "config.skillId", skill);
                about = CompetitionTemplate.skillName(skill) + " XP";
            }
            case BOSS_NIGHT -> {
                String boss = detail == null ? "" : detail.strip();
                if (boss.isEmpty()) throw new IllegalArgumentException("Say which boss the night is for.");
                EventOptions.set(answers, "config.boss", boss);
                about = boss;
            }
            case SCAVENGER_HUNT -> {
                int items = whole(detail, ScavengerHuntTemplate.DEFAULT_STEPS, ScavengerHuntTemplate.MIN_STEPS, ScavengerHuntTemplate.MAX_STEPS, "The number of items");
                EventOptions.set(answers, "config.steps", items);
                about = items + " items to find";
            }
            default -> throw new IllegalArgumentException("That kind of event can't be made here.");
        }
        Instant starts = now.plus(Duration.ofHours(startInHours));
        Instant ends = starts.plus(Duration.ofDays(days));
        answers.put("startsAt", starts.toString()).put("endsAt", ends.toString()).put("name", name == null ? "" : name.strip());
        EventInput input = EventOptions.toInput(EventOptions.normalize(answers));
        return new Plan(input, kind.label + " · " + about + " · " + days + (days == 1 ? " day" : " days"));
    }

    /**
     * The plain standard answers of a quick-create kind's template (no server defaults), as an {@link EventInput}: what {@code HubEvents.createEvent} compares a
     * plan with so it sends the hub only what was really chosen. Empty for a template this command doesn't make.
     */
    public static java.util.Optional<EventInput> standardInput(String templateKey, Instant now) {
        for (Kind kind : Kind.values()) {
            if (kind.template.equals(templateKey)) return java.util.Optional.of(EventOptions.toInput(EventOptions.normalize(EventOptions.standard(templateFor(kind), now))));
        }
        return java.util.Optional.empty();
    }

    /** The template of a kind, for its options. The templates keep no state, so one made here is as good as the one the bot holds. */
    private static EventTemplate templateFor(Kind kind) {
        return switch (kind) {
            case BINGO -> new BingoTemplate();
            case COMPETITION -> new CompetitionTemplate();
            case BOSS_NIGHT -> new BossNightTemplate();
            case SCAVENGER_HUNT -> new ScavengerHuntTemplate();
        };
    }
}
