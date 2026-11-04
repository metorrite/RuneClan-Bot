package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.runeclans.PanelSwitches;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Where polls, signups, the group finder and web forms live (runeclans-hub docs/PHASE-3-EVENTS-POLLS-SIGNUPS.md, ticket F3, D-10): the
 * Switches panel choice {@code hub-community} ({@code bot} or {@code hub}, default {@code bot}), with {@code COMMUNITY_BACKEND} on the bot's
 * Railway service winning while it is set. The same one switch is read by the hub and the website.
 * <ul>
 *   <li>{@code bot}: everything as before, on the bot's own tables.</li>
 *   <li>{@code hub}: the hub owns the data. Commands, buttons and the dashboard routes read and write it through the hub's {@code /v1}
 *       community routes ({@link HubPollStore}, {@link HubSignupStore}); the bot keeps only Discord mappings keyed by hub ids
 *       ({@link HubCommunityMaps}); changes made on the website reach the Discord panels through the hub feed ({@link HubCommunityFeed});
 *       the bot's own poll closer, signup sweep and group/form jobs stand down (the hub runs them).</li>
 * </ul>
 * Read on every call, so the panel's flip takes effect within the panel reader's 30 seconds. The hub side also needs the hub client
 * ({@code RUNECLANS_HUB_ENABLED}): with the switch at {@code hub} and the client off, the bot stays on its own tables and says so in the log.
 */
@BConfiguration
public class HubCommunity {
    private static final Logger log = LoggerFactory.getLogger(HubCommunity.class);
    public static final String SETTING_ENV = "COMMUNITY_BACKEND";
    /** The choice on RuneClan Admin > Switches (runeclans-hub {@code SwitchCatalogue}) and its two values. */
    public static final String SWITCH_ID = "hub-community";
    static final String BOT = "bot";
    static final String HUB = "hub";
    static final List<String> BACKENDS = List.of(BOT, HUB);

    private final BooleanSupplier switchOn;
    private final boolean client;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static HubCommunity hubCommunity(BotConfig config, RuneClansHubClient hub, PanelSwitches panel) {
        String railway = config.getCommunityBackend();
        Supplier<String> backend = panel.choice(SWITCH_ID, railway, BACKENDS, BOT);
        if (HUB.equals(backend.get()) && !hub.enabled()) {
            log.warn("{} says hub but the hub client is off (RUNECLANS_HUB_ENABLED): polls, signups, groups and forms stay on the bot's own tables.", SETTING_ENV);
        }
        return new HubCommunity(() -> HUB.equals(backend.get()), hub.enabled());
    }

    HubCommunity(BooleanSupplier switchOn, boolean client) {
        this.switchOn = switchOn;
        this.client = client;
    }

    /** Always on the bot's own tables (tests, and callers with no switch). */
    public static HubCommunity off() {
        return new HubCommunity(() -> false, false);
    }

    /** Always on the hub (tests). */
    public static HubCommunity on() {
        return new HubCommunity(() -> true, true);
    }

    /** A test switch that can be flipped. */
    public static HubCommunity of(BooleanSupplier switchOn) {
        return new HubCommunity(switchOn, true);
    }

    /** The hub owns polls, signups, groups and forms right now: the bot reads and writes them there and its own jobs stand down. */
    public boolean onHub() {
        return client && switchOn.getAsBoolean();
    }
}
