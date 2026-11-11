package com.runeclans.bot.discord;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared Components V2 building blocks, so every command/listener renders replies the same way
 * instead of each hand-rolling its own {@code Container}/color palette. Built while converting the
 * whole bot off classic embeds — see the individual command packages for how these get used.
 * <p>
 * Two kinds of helper live here: plain {@link Container} factories ({@link #toast}, {@link #card})
 * for building a component tree yourself, and {@code replyX}/{@code editX} convenience methods that
 * also send/edit it. The convenience methods are typed against {@link IReplyCallback} /
 * {@link IMessageEditCallback} rather than a concrete event class, since every interaction event in
 * this bot (slash command, button, modal) implements one or both — one helper works everywhere.
 */
public final class Containers {
    private Containers() {}

    // A small shared palette instead of every listener defining its own — pick by what the message
    // is communicating, not by which subsystem it's in.
    public static final Color PRIMARY = Color.CYAN;
    public static final Color INFO = Color.BLUE;
    public static final Color SUCCESS = new Color(0x57F287);
    public static final Color WARNING = new Color(0xFEE75C);
    public static final Color DANGER = new Color(0xED4245);

    /** A container of one {@link TextDisplay} per line — the everyday replacement for a bare {@code event.reply("some string")}. */
    public static Container toast(Color accent, String... lines) {
        List<ContainerChildComponent> children = new ArrayList<>(lines.length);
        for (String line : lines) children.add(TextDisplay.of(line));
        return Container.of(children).withAccentColor(accent);
    }

    /** A container built from arbitrary children (text, separators, action rows, sections, ...). */
    public static Container card(Color accent, List<? extends ContainerChildComponent> children) {
        return Container.of(children).withAccentColor(accent);
    }

    public static Container card(Color accent, ContainerChildComponent... children) {
        return Container.of(List.of(children)).withAccentColor(accent);
    }

    /**
     * The tiny footer on a panel saying when it removes itself — see {@link EphemeralLifecycle}. Discord's
     * relative timestamp counts down on its own, so it stays accurate without the message being edited;
     * every click rebuilds the panel (and restarts the real timer), which rebuilds this too.
     */
    public static TextDisplay autoCloseNote() {
        long closesAt = Instant.now().getEpochSecond() + EphemeralLifecycle.PANEL_IDLE_SECONDS;
        return TextDisplay.of("-# This panel closes <t:" + closesAt + ":R> if you stop using it.");
    }

    // --- The "Link by ID" fallback button, standard everywhere a channel/thread/role select menu is ---

    private static final String LINK_EMOJI = "🔗"; // 🔗

    /**
     * The blue chain-link button that belongs next to every channel/thread/role select menu in the
     * bot — a native select can't list an individual forum thread at all, and its options are
     * otherwise limited to whatever Discord's own client decides to surface, so this is the one path
     * that always works: paste a link (channel) or a raw ID (channel or role) instead of picking from
     * the list. Discord doesn't allow a button in the same row as a select menu, so this goes
     * immediately after the select's own row, never beside it.
     */
    public static Button linkButton(String customId, String label) {
        return Button.primary(customId, label).withEmoji(Emoji.fromUnicode(LINK_EMOJI));
    }

    /** {@link #linkButton(String, String)} with the standard "Link by ID" label. */
    public static Button linkButton(String customId) {
        return linkButton(customId, "Link by ID");
    }

    /** {@link #linkButton(String, String)} already wrapped in its own row, ready to append right after a select's row. */
    public static ActionRow linkButtonRow(String customId, String label) {
        return ActionRow.of(linkButton(customId, label));
    }

    public static ActionRow linkButtonRow(String customId) {
        return linkButtonRow(customId, "Link by ID");
    }

    // --- Reply (works from a slash command, button, or modal) ---

    public static void reply(IReplyCallback event, Color accent, boolean ephemeral, String... lines) {
        event.replyComponents(List.of(toast(accent, lines)))
                .useComponentsV2(true)
                .setEphemeral(ephemeral)
                .queue();
    }

    public static void replyEphemeral(IReplyCallback event, Color accent, String... lines) {
        reply(event, accent, true, lines);
    }

    /** Ephemeral reply that self-deletes after {@code after} — the "toast" pattern used for short-lived confirmations. */
    public static void replyThenDelete(IReplyCallback event, Color accent, Duration after, String... lines) {
        event.replyComponents(List.of(toast(accent, lines)))
                .useComponentsV2(true)
                .setEphemeral(true)
                .delay(after)
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }

    public static void replyThenDelete(IReplyCallback event, Color accent, String... lines) {
        replyThenDelete(event, accent, Duration.ofSeconds(5), lines);
    }

    /**
     * Ephemeral answer that works whether or not {@code event} was deferred: a fresh interaction gets a reply, a deferred one has its "thinking..."
     * message replaced. Handlers that defer first (because the work can pass Discord's 3 s limit) answer every path through this.
     */
    public static void respond(IReplyCallback event, Color accent, String... lines) {
        if (!event.isAcknowledged()) {
            replyEphemeral(event, accent, lines);
            return;
        }
        event.getHook().editOriginalComponents(List.of(toast(accent, lines))).useComponentsV2(true).queue(ok -> {}, failure -> {});
    }

    // --- Edit (button/modal only — nothing to edit from a fresh slash command) ---

    public static void edit(IMessageEditCallback event, Color accent, String... lines) {
        event.editComponents(List.of(toast(accent, lines)))
                .useComponentsV2(true)
                .queue();
    }

    /** Edits the originating message to a toast, then deletes it entirely after {@code after}. */
    public static void editThenDelete(IMessageEditCallback event, Color accent, Duration after, String... lines) {
        event.editComponents(List.of(toast(accent, lines)))
                .useComponentsV2(true)
                .delay(after)
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }

    public static void editThenDelete(IMessageEditCallback event, Color accent, String... lines) {
        editThenDelete(event, accent, Duration.ofSeconds(5), lines);
    }

    // --- Error fallback, shared by every listener's catch block ---

    private static final String ERROR_MESSAGE = "An unexpected error occurred. Please try again or contact an admin.";

    /**
     * {@code event} is usually still fresh (nothing sent yet), so a plain ephemeral reply works. But
     * every admin action that defers first (Sync Clan, Poll All, Clan Overview, Monthly Recap, ...)
     * calls {@code deferReply}/{@code deferEdit} <em>before</em> doing the actual work — if that work
     * throws, {@code event} is already acknowledged, and a plain {@code reply} silently does nothing
     * (Discord's own client is left showing "thinking..." forever, with no error ever visible to
     * whoever clicked it). Editing through the hook instead, exactly like every successful path in
     * these handlers already does after a defer, means an error is always visible one way or another.
     */
    public static void replyError(IReplyCallback event) {
        try {
            if (!event.isAcknowledged()) {
                reply(event, DANGER, true, ERROR_MESSAGE);
            } else {
                event.getHook().editOriginalComponents(List.of(toast(DANGER, ERROR_MESSAGE)))
                        .useComponentsV2(true)
                        .queue(success -> {}, failure -> {});
            }
        } catch (Exception ignored) {}
    }
}
