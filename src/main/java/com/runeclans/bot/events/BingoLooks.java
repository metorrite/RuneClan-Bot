package com.runeclans.bot.events;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;
import java.util.Optional;

/**
 * The looks a bingo board can have: what its tiles are drawn on. A look is chosen per event ({@code board.look} in the event's settings). The
 * hub draws the picture (T-941, {@code GET /v1/events/{id}/board.png}); the bot only knows the ids so an event's settings can be checked.
 * <p>
 * A board whose settings name no look keeps how it was always drawn: the shapes in their {@link #CLASSIC} pictures, a square grid as the plain dark board.
 */
public final class BingoLooks {
    /** Your own template pictures: a dark teal board with a gold frame. */
    public static final String CLASSIC = "classic";
    /** A scene painted in the game's style: a desert night, a treasure map, an ember-lit ritual circle, an old grove, or a stone hall for the grid. */
    public static final String RUNESCAPE = "runescape";
    /** What a new event gets when its creator doesn't choose. */
    public static final String DEFAULT_FOR_NEW = RUNESCAPE;

    /** A look as the dashboard lists it. */
    public record Look(String id, String label, String blurb) {}

    private static final List<Look> ALL = List.of(
            new Look(CLASSIC, "Template", "The original template pictures: a dark teal board with a gold frame."),
            new Look(RUNESCAPE, "RuneScape", "A scene painted for each shape: a desert night, a treasure map, a ritual circle, an old grove. The square grid gets a stone hall."));

    private BingoLooks() {}

    public static List<Look> all() {
        return ALL;
    }

    public static List<String> ids() {
        return ALL.stream().map(Look::id).toList();
    }

    public static boolean valid(String id) {
        return id != null && ids().contains(id);
    }

    public static String label(String id) {
        return ALL.stream().filter(l -> l.id().equals(id)).map(Look::label).findFirst().orElse("Default");
    }

    /** The look a bingo's settings name ({@code board.look}), or empty when they name none or an unknown one. */
    public static Optional<String> of(DataObject config) {
        try {
            if (config == null || !config.hasKey("board") || config.isNull("board")) return Optional.empty();
            DataObject board = config.getObject("board");
            if (!board.hasKey("look") || board.isNull("look")) return Optional.empty();
            String id = board.getString("look");
            return valid(id) ? Optional.of(id) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
