package com.runeclans.bot.internal;

import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.plugin.RuneSyncService;
import com.runeclans.bot.rsnlink.bridge.RsnLinkAdminApi;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * The bot's own health for the overall admin panel ({@code overall/health}) the plans per server ({@code overall/premium-guilds...}, the bot's owner only, as before) the RS Link abuse view ({@code overall/rsnlink/...}, the bot's owner only, as before) and the RuneSync
 * diagnostics viewer ({@code overall/runesync/diagnostics/{id}}, the bot's owner only as before). Read only: the
 * same answers the bot owner's dashboard gives, here for every overall admin, who need not be in any server. {@link OverallAdminApi} has already
 * checked the asker is an overall admin; the answers are about the whole bot, with the home server only supplying the roster and the words.
 */
@BService
public class OverallOpsRoutes {
    static final String HEALTH = "health";
    static final String RUNESYNC = "runesync";
    static final String RSNLINK = "rsnlink";
    static final String PREMIUM_GUILDS = "premium-guilds";

    private final AdminOpsApi ops;
    private final RuneSyncService runeSync;
    private final BotOwners owners;
    private final PremiumAdminApi premium;
    private final RsnLinkAdminApi rsLink;

    public OverallOpsRoutes(AdminOpsApi ops, RuneSyncService runeSync, BotOwners owners, PremiumAdminApi premium, RsnLinkAdminApi rsLink) {
        this.ops = ops;
        this.runeSync = runeSync;
        this.owners = owners;
        this.premium = premium;
        this.rsLink = rsLink;
    }

    /** {@code null} when the path is not one of these. */
    DataObject route(JDA jda, long actor, Guild home, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length >= 1 && parts[0].equals(RSNLINK)) return rsLink.routeFor(jda, actor, method, parts, body);
        if (parts.length >= 1 && parts[0].equals(PREMIUM_GUILDS)) return premium.routeFor(home, actor, method, parts, body);
        if (parts.length == 3 && parts[0].equals(RUNESYNC) && parts[1].equals("diagnostics")) return diagnostic(jda, actor, method, parts[2]);
        if (parts.length != 1) return null;
        if (parts[0].equals(HEALTH)) {
            AdminRouteExtension.requireMethod(method, "GET");
            return ops.health(home);
        }
        return null;
    }

    /** One snapshot a member sent from RuneSync's panel, redacted when stored. The bot's owner only, whether or not the id exists. */
    private DataObject diagnostic(JDA jda, long actor, String method, String id) {
        if (!owners.isOwner(jda, actor)) throw AdminRouteExtension.refuse(403, "Only the bot's owner can open RuneSync diagnostics.");
        AdminRouteExtension.requireMethod(method, "GET");
        try {
            DataObject found = runeSync.diagnostic(id, Instant.now());
            if (found == null) throw AdminRouteExtension.refuse(404, "No snapshot has that id (they are kept " + RuneSyncService.SNAPSHOT_KEPT.toDays() + " days).");
            return found;
        } catch (RuneSyncService.RuneSyncException e) {
            throw AdminRouteExtension.refuse(e.status(), e.getMessage());
        }
    }
}
