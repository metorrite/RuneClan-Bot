package com.runeclans.bot.runescape.stats;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.ClanMemberRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Which source answers the bot's stats reads (T-851, plan D): the hub's routes, always, while the hub client is on
 * ({@code RUNECLANS_HUB_ENABLED}); a bot that can't reach the hub (a local run, tests) falls back to its own tables. The hub polls every
 * tracked player and the bot polls nothing itself, so there is no switch any more: the old {@code hub-polling} handover is done.
 */
@BConfiguration
public class StatsBackends {
    private static final Logger log = LoggerFactory.getLogger(StatsBackends.class);

    private final HubStatsBackend hub;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static StatsBackends statsBackends(RuneClansHubClient hubClient, ClanMemberRepository members) {
        if (!hubClient.enabled()) {
            log.warn("The hub client is off (RUNECLANS_HUB_ENABLED): stats are read from the bot's own tables, which nothing fills any more.");
            return new StatsBackends(null);
        }
        return new StatsBackends(HubStatsBackend.of(hubClient, members));
    }

    StatsBackends(HubStatsBackend hub) {
        this.hub = hub;
    }

    /** No hub: the bot's own tables, for a bot that can't reach the hub (and tests). */
    public static StatsBackends none() {
        return new StatsBackends(null);
    }

    /** The hub's backend, for tests. */
    public static StatsBackends hubAlways(HubStatsBackend hub) {
        return new StatsBackends(hub);
    }

    /** The hub told us {@code rsn} has new stats: the hub backend forgets its batch answer for them. */
    public void evictPlayer(String rsn) {
        if (hub != null) hub.evictPlayer(rsn);
    }

    /** True while the bot has a hub to read from (the hub client is on): it reads stats from the hub and polls nothing itself. */
    public boolean hubPolls() {
        return hub != null;
    }

    /** The hub's backend, or null when the hub client is off (then the repositories run their own SQL). */
    public HubStatsBackend hub() {
        return hub;
    }
}
