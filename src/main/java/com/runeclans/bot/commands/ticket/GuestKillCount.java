package com.runeclans.bot.commands.ticket;

import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkService;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import com.runeclans.bot.runescape.stats.StatsBackends;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.OptionalInt;

/**
 * Kills of a boss the bot has seen in the adventure log of a member's linked RuneScape names (see {@link HelpPriority#killsIn}). Only what the log reports counts, so the
 * number is a floor, and it is empty when the member has no linked name or none of their names has been tracked yet; the ticket rules then fall back to asking for a
 * description of earlier attempts. A failure reading it is also "no data", never a refusal.
 */
@BConfiguration
public class GuestKillCount {
    private static final Logger log = LoggerFactory.getLogger(GuestKillCount.class);
    private static final int MAX_LINES = 5000;

    private final ConnectionSupplier connectionSupplier;
    private final PlayerLinkService links;

    /** Stats reads answer from the hub while it polls, else from this class's own SQL (T-851, {@link StatsBackends}). */
    private volatile StatsBackends stats = StatsBackends.none();

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static GuestKillCount guestKillCount(ConnectionSupplier connectionSupplier, PlayerLinkService links, StatsBackends backends) {
        GuestKillCount store = new GuestKillCount(connectionSupplier, links);
        store.stats = backends;
        return store;
    }

    /** Reads from {@code backends} while the hub polls (tests). */
    public GuestKillCount withStats(StatsBackends backends) {
        this.stats = backends;
        return this;
    }

    public GuestKillCount(ConnectionSupplier connectionSupplier, PlayerLinkService links) {
        this.connectionSupplier = connectionSupplier;
        this.links = links;
    }

    public OptionalInt count(long guildId, long userId, String boss) {
        if (boss == null || boss.isBlank()) return OptionalInt.empty();
        try {
            List<String> rsns = links.getLinksForUser(guildId, userId).stream().map(PlayerLink::rsn).map(r -> r.toLowerCase(java.util.Locale.ROOT)).toList();
            if (rsns.isEmpty()) return OptionalInt.empty();
            int total = 0;
            boolean any = false;
            for (String text : stats.backend().recentActivityTexts(rsns, MAX_LINES)) {
                any = true;
                total += HelpPriority.killsIn(text, boss);
            }
            return any ? OptionalInt.of(total) : OptionalInt.empty();
        } catch (RuntimeException e) {
            log.warn("Couldn't read tracked kills of {} for user {}", boss, userId, e);
            return OptionalInt.empty();
        }
    }
}
