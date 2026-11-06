package com.runeclans.bot.events;

import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.hubevents.HubEvents;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;
import java.util.UUID;

/**
 * Skill of the week (T-541, T-711), kept by the RuneClans hub since T-884 and reached from here since T-941: the hub keeps each clan's setting
 * (switch, rota, rota or vote, announcement channel, auto-enrol), opens the week's competition each Monday, counts it from RuneSync's XP, closes
 * it and keeps the winners, and takes the votes. The bot only shows what the hub has (the dashboard page, a member's vote and auto-enrol choice,
 * the clan website's widget) and posts the hub's announcements in the chosen channel ({@code skill_week.changed}, {@link com.runeclans.bot.hubevents.HubEventPosts}).
 * Every call names the clan of the server ({@link HubEvents#clanOf}); a server with no clan on RuneClans has no skill of the week.
 */
@BService
public class SkillWeekService {
    /** How many places the website's standings show at most. */
    public static final int MAX_SHOWN = 25;

    private final HubEvents events;

    public SkillWeekService(HubEvents events) {
        this.events = events;
    }

    /** Whether weeks can be opened at all: the bot talks to the hub. */
    public boolean available() {
        return events.enabled();
    }

    private String path(long guildId) {
        UUID clan = events.clanOf(guildId).orElseThrow(() -> new EventProblem(409, HubEvents.NO_CLAN_TEXT));
        return "/v1/clans/" + clan + "/skill-week";
    }

    /**
     * What the dashboard shows: the switch and rota, the skill next in line, this week (skill, span, competition and its standings by XP, at
     * most {@code shown} places), the last closed week's winners, the announcement channel and the skills to choose from. Read as the bot
     * itself, a staff member of the server, so every player is named.
     */
    public DataObject view(long guildId, int shown) {
        return events.client().get(path(guildId) + "?shown=" + Math.max(1, Math.min(MAX_SHOWN, shown)), events.self(guildId)).put("available", available());
    }

    /**
     * {@link #view} for anyone (the clan website's widget): the same, with nobody named and no XP shown, which is what the hub shows a
     * member who isn't staff. Who may be named on a clan site is the hub's rule, applied on the website's own skill-week reads.
     */
    public DataObject publicView(long guildId, int shown) {
        DataObject out = view(guildId, shown);
        out.remove("announceChannelId");
        for (String week : List.of("current", "last")) {
            DataObject w = out.hasKey(week) && !out.isNull(week) ? out.getObject(week) : null;
            if (w == null) continue;
            for (String rows : List.of("standings", "winners")) {
                if (!w.hasKey(rows) || w.isNull(rows)) continue;
                DataArray list = w.getArray(rows);
                for (int i = 0; i < list.length(); i++) list.getObject(i).put("name", "A member").put("xp", null).put("mine", false);
            }
        }
        return out;
    }

    /** Saves the clan's setting (the dashboard's form), as the staff member who pressed save. The hub's view after. */
    public DataObject save(long guildId, long actorId, DataObject body) {
        return events.client().send("POST", path(guildId), body, Actor.of(actorId, guildId, true)).put("available", available());
    }

    /** The member's own auto-enrol choice for the clan website: whether the clan enters members by themselves, and whether they opted out. */
    public DataObject autoView(long guildId, long userId) {
        DataObject view = events.client().get(path(guildId) + "?shown=1", Actor.member(userId, guildId));
        return DataObject.empty().put("autoEnrol", view.getBoolean("enabled", false) && view.getBoolean("autoEnrol", false)).put("optedOut", view.getBoolean("optedOut", false));
    }

    /** Keeps the member out of (or lets them back into) auto-enrol in this clan, answering {@link #autoView}. */
    public DataObject setOptOut(long guildId, long userId, boolean optedOut) {
        DataObject out = events.client().send("POST", path(guildId) + "/auto", DataObject.empty().put("optedOut", optedOut), Actor.member(userId, guildId));
        return DataObject.empty().put("autoEnrol", out.getBoolean("autoEnrol", false)).put("optedOut", out.getBoolean("optedOut", optedOut));
    }

    /** The vote's state for the clan website: the week voted for, the rota's skills in order with their votes, and {@code mine} when given. */
    public DataObject voteView(long guildId, Long userId) {
        DataObject view = events.client().get(path(guildId) + "?shown=1", userId == null ? Actor.APP : Actor.member(userId, guildId));
        return view.hasKey("vote") && !view.isNull("vote") ? view.getObject("vote") : DataObject.empty().put("open", false);
    }

    /** A member's vote for next week's skill; the hub refuses it (409) unless the clan votes, and (400) for a skill not on its ballot. */
    public DataObject castVote(long guildId, long userId, int skillId) {
        return events.client().send("POST", path(guildId) + "/vote", DataObject.empty().put("skillId", skillId), Actor.member(userId, guildId));
    }
}
