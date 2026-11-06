package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the team builder knows about an event's players beyond who is on which team: how strong each looks (their linked RuneScape account's latest stats, and
 * the rating an organiser gave), and a starting suggestion for the teams ({@link TeamBuilder}). Reads only, from the hub ({@link HubEvents}); changing
 * teams is the hub's.
 */
@BService
public class EventTeams {
    private final HubEvents service;
    private final PlayerLinkRepository links;

    public EventTeams(HubEvents service, PlayerLinkRepository links) {
        this.service = service;
        this.links = links;
    }

    /**
     * What is known of each of these players' accounts in {@code guildId}: the latest stats of their linked RuneScape name, and when a player has linked
     * several, of the one with the most XP (their main). A player with no link, or whose name has never been polled, has no entry.
     */
    public Map<Long, TeamBuilder.Strength> strengths(long guildId, Collection<Long> userIds) {
        Map<Long, TeamBuilder.Strength> result = new HashMap<>();
        if (userIds.isEmpty()) return result;
        Set<Long> wanted = new HashSet<>(userIds);
        Map<Long, List<String>> namesOf = new HashMap<>();
        for (PlayerLink link : links.getAllLinks(guildId)) {
            if (wanted.contains(link.discordUserId())) namesOf.computeIfAbsent(link.discordUserId(), k -> new ArrayList<>()).add(link.rsn());
        }
        List<String> all = namesOf.values().stream().flatMap(List::stream).toList();
        Map<String, StatsSnapshotRow> snapshots = links.getLatestSnapshots(all);
        namesOf.forEach((userId, names) -> {
            TeamBuilder.Strength best = null;
            for (String name : names) {
                StatsSnapshotRow snapshot = snapshots.get(name.toLowerCase(java.util.Locale.ROOT));
                if (snapshot == null) continue;
                if (best == null || snapshot.totalXp() > best.totalXp()) best = new TeamBuilder.Strength(name, snapshot.totalLevel(), snapshot.combatLevel(), snapshot.totalXp());
            }
            if (best != null) result.put(userId, best);
        });
        return result;
    }

    /** Everyone who holds a place in the event (not the waitlist), as the team builder sees them. */
    public List<TeamBuilder.Player> players(long guildId, Event event) {
        List<Participant> active = service.participants(event.id()).stream().filter(p -> !p.waiting() && p.userId() != 0).toList();
        Map<Long, TeamBuilder.Strength> strengths = strengths(guildId, active.stream().map(Participant::userId).toList());
        return active.stream().map(p -> new TeamBuilder.Player(p.userId(), p.kind(), p.teamId(), p.rating(), strengths.get(p.userId()))).toList();
    }

    /** The event's teams as the team builder sees them. */
    public List<TeamBuilder.Team> teams(long eventId) {
        return service.teams(eventId).stream().map(t -> new TeamBuilder.Team(t.id(), t.name(), t.captainId())).toList();
    }

    /**
     * A starting point for the teams: where each player who has none (and may be on one) would go so the teams come out level. Nothing is changed; an organiser
     * looks at it and applies it, or doesn't.
     */
    public TeamBuilder.Suggestion suggest(long guildId, long eventId) {
        Event event = service.event(guildId, eventId);
        if (event.scoringMode() != ScoringMode.TEAM) throw new EventProblem(409, Text.of(guildId, "events.problem.this_event_is_scored_by_player_2"));
        List<TeamBuilder.Player> players = players(guildId, event);
        Map<Long, Participant> byId = new HashMap<>();
        service.participants(eventId).forEach(p -> byId.put(p.userId(), p));
        Set<Long> eligible = new HashSet<>();
        for (TeamBuilder.Player p : players) {
            if (service.teamBlock(event, byId.get(p.userId())) == null) eligible.add(p.userId());
        }
        return TeamBuilder.suggest(teams(eventId), players, eligible, service.settings(event).teamPolicy());
    }
}
