package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * The hub didn't do what a poll, signup, group or form call asked (ticket F3). The message is written for the member who clicked, so a
 * button or command shows it as it is:
 * <ul>
 *   <li>{@link Kind#CHANGED}: the hub answered 409 to an {@code If-Match} edit: someone changed it elsewhere (the website) first.</li>
 *   <li>{@link Kind#QUEUED}: the hub couldn't be reached; the write waits in the hub write queue (up to 5 minutes) and will sync.</li>
 *   <li>{@link Kind#DOWN}: the hub couldn't be reached and nothing was saved: try again shortly.</li>
 *   <li>{@link Kind#REFUSED}: the hub said no (closed, full, not found, ...), with the hub's own words.</li>
 * </ul>
 */
public final class HubCommunityProblem extends RuntimeException {
    public enum Kind { CHANGED, QUEUED, DOWN, REFUSED }

    static final String CHANGED_TEXT = "This was changed somewhere else (on the website?) just now. Have a look and try again.";
    static final String QUEUED_TEXT = "Saved. RuneClans is busy right now, so it will show in a few minutes.";
    static final String DOWN_TEXT = "RuneClans can't be reached right now, so nothing was changed. Try again in a minute.";

    private final Kind kind;
    private final int status;
    private final String code;

    HubCommunityProblem(Kind kind, int status, String code, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    /** The hub's HTTP status (0 when it couldn't be reached). */
    public int status() {
        return status;
    }

    /** The hub's error code ({@code full}, {@code closed}, ...), or null. */
    public String code() {
        return code;
    }

    /** A refusal in the bot's own words ({@code status} as the website should see it). */
    public static HubCommunityProblem refused(int status, String message) {
        return new HubCommunityProblem(Kind.REFUSED, status, null, message);
    }

    /** The HTTP status the website should see for this problem: the hub's, or 503 when it couldn't be reached (202 when the write is queued). */
    public int httpStatus() {
        return switch (kind) {
            case QUEUED -> 202;
            case DOWN -> 503;
            case CHANGED -> 409;
            case REFUSED -> status == 0 ? 503 : status;
        };
    }

    static HubCommunityProblem down() {
        return new HubCommunityProblem(Kind.DOWN, 0, null, DOWN_TEXT);
    }

    static HubCommunityProblem queued() {
        return new HubCommunityProblem(Kind.QUEUED, 0, null, QUEUED_TEXT);
    }

    /** The problem behind a hub answer that wasn't a success (none, 429 or 5xx: down; 409 {@code changed_elsewhere} or 428: changed; else the hub's words). */
    static HubCommunityProblem of(RuneClansHubClient.Answer a) {
        if (a == null || a.status() >= 500 || a.status() == 429) return down();
        String code = null;
        String message = null;
        DataObject json = a.json();
        if (json != null) {
            // the hub answers {"error": code, "message": words}
            code = json.getString("error", null);
            message = json.getString("message", null);
        }
        if (a.status() == 409 && "changed_elsewhere".equals(code)) {
            return new HubCommunityProblem(Kind.CHANGED, 409, code, CHANGED_TEXT);
        }
        if (a.status() == 428) return new HubCommunityProblem(Kind.CHANGED, a.status(), code, CHANGED_TEXT);
        if (message == null || message.isBlank()) message = a.status() == 404 ? "That isn't there any more." : "RuneClans said no to that (" + a.status() + ").";
        return new HubCommunityProblem(Kind.REFUSED, a.status(), code, message);
    }
}
