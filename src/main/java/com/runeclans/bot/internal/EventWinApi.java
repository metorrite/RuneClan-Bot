package com.runeclans.bot.internal;

import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.WinModes;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * How an event is won, for the dashboard's event manager (T-649): {@code GET events/{id}/win} (the rules in words, who would qualify for a draw right
 * now or the finishing order so far, and the draw with its seed and entries), {@code PUT events/{id}/win} (save the rules; only until the winners are
 * drawn or the awards worked out), {@code POST events/{id}/win/draw} ("Draw now") and {@code POST events/{id}/win/reroll} ({@code {place}}: draw again
 * for a winner who doesn't claim). Since T-941 all of it is the hub's ({@code /v1/events/{id}/win...}): the bot passes the asker on as the actor and
 * hands the hub's answer back, which has the shape this page always had (names are the hub's, players by their RuneScape name). Reached through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server (staff) and audit-logs every change.
 */
@BService
public class EventWinApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EventWinApi.class);

    private final HubEvents events;

    public EventWinApi(HubEvents events) {
        this.events = events;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 3 || parts.length > 4 || !parts[0].equals("events") || !parts[2].equals("win")) return null;
        if (parts.length == 4 && !parts[3].equals("draw") && !parts[3].equals("reroll")) return null;
        try {
            if (!parts[1].matches("\\d{1,18}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_event"));
            long id = Long.parseLong(parts[1]);
            long guildId = guild.getIdLong();
            Actor who = Actor.of(actor.getIdLong(), guildId, true);
            if (parts.length == 3) {
                if (method.equals("GET")) return events.win(guildId, id, who);
                AdminRouteExtension.requireMethod(method, "PUT");
                DataObject out = events.saveWin(guildId, id, body.get(), who);
                log.info("Dashboard: {} changed how event {} is won", actor.getId(), id);
                return out;
            }
            AdminRouteExtension.requireMethod(method, "POST");
            if (parts[3].equals("draw")) {
                DataObject out = events.drawNow(guildId, id, who);
                log.info("Dashboard: {} drew the winners of event {}", actor.getId(), id);
                return out;
            }
            return events.reroll(guildId, id, placeOf(body.get()), who);
        } catch (EventProblem e) {
            throw new ApiError(e.status() == 500 ? 500 : e.status(), e.getMessage(), e.problems());
        }
    }

    static int placeOf(DataObject body) {
        try {
            Object v = body.get("place");
            int place = v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v).strip());
            if (place < 1 || place > WinModes.MAX_WINNERS) throw new NumberFormatException();
            return place;
        } catch (RuntimeException e) {
            throw new ApiError(400, "Say which place to draw again for.");
        }
    }
}
