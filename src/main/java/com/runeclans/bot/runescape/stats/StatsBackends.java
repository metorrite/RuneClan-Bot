package com.runeclans.bot.runescape.stats;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.runeclans.PanelSwitches;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.ClanMemberRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Which source answers the bot's stats reads (T-851, plan D): the hub's routes while the hub polls, else the bot's own tables. "The hub polls"
 * is the Switches panel's <b>Hub polling</b> ({@value #SWITCH_ID}, RuneClan Admin > Switches), read like every other panel switch through
 * {@link PanelSwitches} every 30 seconds, so the handover (T-852) is one flip with no restart: within a minute the bot reads from the hub,
 * its poll jobs submit nothing and its poll queue refuses new work, and the flip back resumes everything. {@value #SETTING_ENV} on the bot's
 * Railway service holds it ({@code hub} or {@code bot}; the website's {@code STATS_BACKEND} has the same two values) while set.
 * <p>
 * The switch counts only while the hub client is on ({@code RUNECLANS_HUB_ENABLED}): a bot that can't reach the hub keeps polling and reading
 * its own tables whatever the panel says, and says so in the log once, because a bot with no source at all would blank every stats feature.
 */
@BConfiguration
public class StatsBackends {
    private static final Logger log = LoggerFactory.getLogger(StatsBackends.class);
    public static final String SWITCH_ID = "hub-polling";
    public static final String SETTING_ENV = "RUNECLANS_STATS_BACKEND";

    private final BooleanSupplier hubPolls;
    private final HubStatsBackend hub;
    private volatile boolean lastLogged;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static StatsBackends statsBackends(BotConfig config, PanelSwitches panel, RuneClansHubClient hubClient, ClanMemberRepository members) {
        String railway = railwayWord(config.getRuneClansSetting(SETTING_ENV));
        BooleanSupplier switched = panel.toggle(SWITCH_ID, railway);
        if (!hubClient.enabled()) {
            if ("on".equals(railway)) log.warn("{} says hub but the hub client is off (RUNECLANS_HUB_ENABLED): stats stay with the bot's own tables and polling.", SETTING_ENV);
            return new StatsBackends(() -> false, null);
        }
        return new StatsBackends(switched, HubStatsBackend.of(hubClient, members));
    }

    StatsBackends(BooleanSupplier hubPolls, HubStatsBackend hub) {
        this.hubPolls = hubPolls;
        this.hub = hub;
    }

    /** No hub: the bot's own tables and polling, whatever the panel says (the hub client off, and tests). */
    public static StatsBackends none() {
        return new StatsBackends(() -> false, null);
    }

    /** The hub's backend, always current, for tests. */
    public static StatsBackends hubAlways(HubStatsBackend hub) {
        return new StatsBackends(() -> true, hub);
    }

    /** The hub told us {@code rsn} has new stats: the hub backend forgets its batch answer for them, whichever way the switch points. */
    public void evictPlayer(String rsn) {
        if (hub != null) hub.evictPlayer(rsn);
    }

    /** {@code hub} while {@code hubPolls} says so, for tests that flip the switch. */
    public static StatsBackends of(BooleanSupplier hubPolls, HubStatsBackend hub) {
        return new StatsBackends(hubPolls, hub);
    }

    /** {@code hub}/{@code on} words hold the switch on, {@code bot}/{@code off} words off, for {@link PanelSwitches#toggle}; null lets the panel decide. */
    static String railwayWord(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "hub", "on", "true", "1", "yes" -> "on";
            case "bot", "off", "false", "0", "no" -> "off";
            default -> raw;
        };
    }

    /** True while the hub polls RuneMetrics: the bot reads stats from the hub and polls nothing itself. */
    public boolean hubPolls() {
        if (hub == null) return false;
        boolean on = hubPolls.getAsBoolean();
        if (on != lastLogged) {
            lastLogged = on;
            if (on) log.info("Hub polling is on: stats are read from the hub and the bot's own RuneMetrics polling stands down.");
            else log.info("Hub polling is off: the bot polls RuneMetrics and reads its own stats tables.");
        }
        return on;
    }

    /** The hub's backend while the hub polls, else null (then the repositories run their own SQL). */
    public HubStatsBackend hub() {
        return hubPolls() ? hub : null;
    }
}
