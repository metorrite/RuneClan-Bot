package com.runeclans.bot.events;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rules for where a bingo's boards are shown, free of Discord so they can be tested. A bingo can show ONE board for everybody (how many teams or
 * players have each tile: the live board that has always been there), a board of their own for every team or player (what most events want: each side works
 * through its own card), or both. An own board is posted in the team's or player's own channel when the event made one; where there is none it goes in the
 * live board channel; where there is neither it isn't posted (the player still sees it with {@code /events board} and on the website). A board is only
 * drawn and sent again when its tiles changed.
 */
public final class EventBoards {
    private EventBoards() {}

    /** {@code SHARED}: one board for everyone. {@code OWN}: a board per team or player. {@code BOTH}: the two. */
    public enum Display {
        SHARED, OWN, BOTH;

        public boolean shared() {
            return this != OWN;
        }

        public boolean own() {
            return this != SHARED;
        }
    }

    /** The display in the event's board settings ({@code board.display}); {@code SHARED} when there is none, which is how older events behaved. */
    public static Display displayOf(DataObject config) {
        try {
            if (config == null || !config.hasKey("board") || config.isNull("board")) return Display.SHARED;
            DataObject board = config.getObject("board");
            if (!board.hasKey("display") || board.isNull("display")) return Display.SHARED;
            return Display.valueOf(board.getString("display").strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return Display.SHARED;
        }
    }

    /** What is wrong with the display in {@code config}, in words, or null. */
    public static String problem(DataObject config) {
        try {
            if (config == null || !config.hasKey("board") || config.isNull("board")) return null;
            DataObject board = config.getObject("board");
            if (!board.hasKey("display") || board.isNull("display")) return null;
            Display.valueOf(board.getString("display").strip().toUpperCase(Locale.ROOT));
            return null;
        } catch (RuntimeException e) {
            return "Choose how the boards are shown: one for everyone, one for each team or player, or both.";
        }
    }

    /** A team ({@code t:<id>}) or player ({@code u:<id>}) with a board; {@code channelId} is their own channel, or null when they have none. */
    public record Subject(String key, String name, Long channelId) {}

    /** The message a subject's board is already in, and the fingerprint of the tiles it was drawn from. */
    public record Posted(long channelId, long messageId, String hash) {}

    public enum What {
        /** Draw it and send a new message. */
        POST,
        /** Draw it and edit the message that is there (it moved channel or changed). */
        EDIT,
        /** Nothing changed since it was drawn. */
        UNCHANGED,
        /** There is nowhere to put it. */
        NO_PLACE
    }

    public record Step(Subject subject, What what, Long channelId, String hash) {}

    /**
     * What to do for each subject: their own channel, else {@code fallbackChannelId} (the live board channel), else nowhere. A board already posted in the
     * right place whose tiles didn't change is left alone. {@code hashes} maps each subject's key to the fingerprint of their tiles now.
     */
    public static List<Step> plan(List<Subject> subjects, Long fallbackChannelId, Map<String, Posted> posted, Map<String, String> hashes) {
        List<Step> steps = new ArrayList<>();
        for (Subject s : subjects) {
            Long channel = s.channelId() != null ? s.channelId() : fallbackChannelId;
            String hash = hashes.getOrDefault(s.key(), "");
            if (channel == null) {
                steps.add(new Step(s, What.NO_PLACE, null, hash));
                continue;
            }
            Posted existing = posted.get(s.key());
            if (existing == null) steps.add(new Step(s, What.POST, channel, hash));
            else if (existing.channelId() != channel) steps.add(new Step(s, What.POST, channel, hash));
            else if (!existing.hash().equals(hash)) steps.add(new Step(s, What.EDIT, channel, hash));
            else steps.add(new Step(s, What.UNCHANGED, channel, hash));
        }
        return steps;
    }

}
