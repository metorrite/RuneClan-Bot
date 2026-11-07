package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.events.ClanVsClan;
import com.runeclans.bot.events.EventListing;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.hubevents.HubEvents;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The "Clan vs clan" history on a clan's hub page (T-223, design {@code docs/EVENTS-CLAN-VS-CLAN.md} section 5): the finished clan-vs-clan events this
 * clan took part in, newest first, with its place. Only events that were open to everyone and weren't taken off the hub, and only on a clan page that is
 * listed. Clan names only, never a player. It has no routes of its own.
 */
@BService
public class DirectoryClanVsClanSection implements DirectoryRouteModule {
    private static final Logger log = LoggerFactory.getLogger(DirectoryClanVsClanSection.class);
    static final int SHOWN = 10;
    private static final long TTL_MILLIS = 5 * 60_000L;

    private final HubEvents events;
    private final ClanProfileRepository profiles;
    private final TtlCache cache = new TtlCache();

    public DirectoryClanVsClanSection(HubEvents events, ClanProfileRepository profiles) {
        this.events = events;
        this.profiles = profiles;
    }

    @Override
    public void addToClan(ClanProfile profile, DataObject json) {
        if (profile.visibility() != ClanProfile.Visibility.PUBLIC) return; // an unlisted clan's page says nothing about its events
        try {
            json.put("clanVsClan", cache.get("cvc:" + profile.guildId(), TTL_MILLIS, () -> history(profile.guildId())));
        } catch (RuntimeException e) {
            log.warn("Couldn't read the clan-vs-clan history of server {}", profile.guildId(), e);
        }
    }

    /** The clan's finished open clan-vs-clan events, with its place among the clans (null when the host keeps the standings to staff). */
    DataArray history(long guildId) {
        DataArray out = DataArray.empty();
        for (Event e : finishedClanVsClan(guildId)) {
            if (out.length() >= SHOWN) break;
            DataObject config = DataObject.fromJson(e.config());
            if (EventListing.takenDown(config)) continue;
            Map<Long, Long> teams = ClanVsClan.teams(config);
            Long team = teams.get(guildId);
            if (team == null) continue;
            Integer place = null;
            int clans = teams.size();
            if (EventListing.clanStandingsShown(e)) {
                List<Standing> standings = events.standings(e.guildId(), e.id());
                for (int i = 0; i < standings.size(); i++) if (standings.get(i).subject().equals("t:" + team)) place = i + 1;
                clans = Math.max(clans, standings.size());
            }
            String host = profiles.get(e.guildId()).filter(p -> p.visibility() == ClanProfile.Visibility.PUBLIC).map(ClanProfile::displayName).orElse(null);
            out.add(DataObject.empty().put("eventId", Long.toString(e.id())).put("name", e.name()).put("game", ClanVsClan.game(e))
                    .put("endedAt", e.endsAt() == null ? null : e.endsAt().toString()).put("place", place).put("clans", clans).put("host", host)
                    .put("hosted", e.guildId() == guildId));
        }
        return out;
    }

    /** The clan's finished, open clan-vs-clan events on the hub (the ones its own server hosted; T-941), newest first. A server with no hub clan has none. */
    private List<Event> finishedClanVsClan(long guildId) {
        try {
            // the hub's list carries no settings, so the clan-vs-clan teams come from each event read in full
            return events.events(guildId, List.of(EventRules.Status.DONE), SHOWN * 3).stream()
                    .filter(e -> ClanVsClan.is(e.template()) && e.scope() == EventRules.Scope.PUBLIC)
                    .map(e -> events.full(e).orElse(null)).filter(java.util.Objects::nonNull).toList();
        } catch (EventProblem e) {
            return List.of();
        }
    }
}
