package com.runeclans.bot.internal;

import com.runeclans.bot.events.EventProblem;
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
 * The dashboard's Bingo tile pool: the clan's own list of bingo tiles, which lives on the RuneClans hub ({@code /v1/clans/{clanId}/bingo-pool},
 * T-941). The routes here hand the request on as the asking staff member and give the hub's answer back as it is:
 * <ul>
 *     <li>{@code GET bingo-pool}: the tiles, the starter packs, the difficulties and the limits</li>
 *     <li>{@code POST bingo-pool/tiles}: add a tile {@code {label, points?, target?, difficulty?, tags?, enabled?}}</li>
 *     <li>{@code PUT bingo-pool/tiles/{id}}: change a tile</li>
 *     <li>{@code POST bingo-pool/tiles/{id}/delete}: remove a tile</li>
 *     <li>{@code POST bingo-pool/seed} {@code {pack, difficulties?}}: add a starter pack's tiles that are not already there</li>
 *     <li>{@code POST bingo-pool/clear} {@code {pack?}}: remove every tile, or only the ones that came from a pack</li>
 * </ul>
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard here and audit-logs every write.
 */
@BService
public class BingoPoolAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(BingoPoolAdminApi.class);

    private final HubEvents events;

    public BingoPoolAdminApi(HubEvents events) {
        this.events = events;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 0 || !parts[0].equals("bingo-pool")) return null;
        Actor staff = Actor.of(actor.getIdLong(), guild.getIdLong(), true);
        try {
            if (parts.length == 1) {
                AdminRouteExtension.requireMethod(method, "GET");
                return events.bingoPool(guild.getIdLong(), staff);
            }
            if (parts.length == 2 && parts[1].equals("tiles")) {
                AdminRouteExtension.requireMethod(method, "POST");
                DataObject out = events.bingoPool("POST", guild.getIdLong(), "/tiles", body.get(), staff);
                log.info("Dashboard: {} added a bingo tile", actor.getId());
                return out;
            }
            if (parts.length == 3 && parts[1].equals("tiles")) {
                AdminRouteExtension.requireMethod(method, "PUT");
                DataObject out = events.bingoPool("PUT", guild.getIdLong(), "/tiles/" + id(parts[2]), body.get(), staff);
                log.info("Dashboard: {} changed bingo tile {}", actor.getId(), parts[2]);
                return out;
            }
            if (parts.length == 4 && parts[1].equals("tiles") && parts[3].equals("delete")) {
                AdminRouteExtension.requireMethod(method, "POST");
                DataObject out = events.bingoPool("DELETE", guild.getIdLong(), "/tiles/" + id(parts[2]), null, staff);
                log.info("Dashboard: {} removed bingo tile {}", actor.getId(), parts[2]);
                return out;
            }
            if (parts.length == 2 && (parts[1].equals("seed") || parts[1].equals("clear"))) {
                AdminRouteExtension.requireMethod(method, "POST");
                DataObject out = events.bingoPool("POST", guild.getIdLong(), "/" + parts[1], body.get(), staff);
                log.info("Dashboard: {} {} the bingo pool ({})", actor.getId(), parts[1].equals("seed") ? "seeded" : "cleared", body.get().getString("pack", "all"));
                return out;
            }
            return null;
        } catch (EventProblem e) {
            throw new ApiError(e.status(), e.getMessage(), e.problems());
        }
    }

    /** A tile id as the hub gives it (a UUID); anything else is "no such tile". */
    private static String id(String raw) {
        if (raw == null || !raw.matches("[0-9a-fA-F-]{36}")) throw new ApiError(404, Text.en("dashboard.that_tile_doesn_t_exist"));
        return raw;
    }
}
