package com.runeclans.bot.runeclans;

import com.runeclans.bot.hubsites.HubSites;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot's reader of RuneClan Admin > Switches (T-817): the bot's own Railway variable wins while set, else the panel's value; a hub that
 * stops answering keeps its last answer standing until it answers again (the three switches guard data a hub outage must not move back).
 */
class PanelSwitchesTest {
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-31T09:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static DataObject switches(String id, String value) {
        return DataObject.empty().put(id, value);
    }

    @Test
    void railwayWinsWhileSetElseThePanelDecides() {
        PanelSwitches panel = PanelSwitches.of(Map.of("hub-access", "on", "member-privacy", "off"));
        assertTrue(panel.toggle("hub-access", null).getAsBoolean(), "unset on Railway: the panel's on");
        assertFalse(panel.toggle("hub-access", "off").getAsBoolean(), "Railway's off is the kill switch");
        assertFalse(panel.toggle("hub-access", "0").getAsBoolean());
        assertTrue(panel.toggle("member-privacy", "true").getAsBoolean(), "Railway's true holds it on with the panel off");
        assertTrue(panel.toggle("member-privacy", " ON ").getAsBoolean());
        assertTrue(panel.toggle("member-privacy", "yes").getAsBoolean());
        for (String typo : new String[] {"maybe", "disabled", "none", "onn", "no"})
            assertFalse(panel.toggle("hub-access", typo).getAsBoolean(), typo + ": any Railway value but an on word holds it off (the panel is on)");
        assertTrue(panel.toggle("hub-access", " ").getAsBoolean(), "blank on Railway lets the panel decide");
        assertFalse(panel.toggle("not-listed", null).getAsBoolean(), "a switch the hub doesn't list is off");
        assertFalse(PanelSwitches.none().toggle("hub-access", null).getAsBoolean(), "no panel: the default (off)");
    }

    @Test
    void aChoiceTakesOnlyItsValues() {
        List<String> values = List.of("bot", "hub");
        PanelSwitches panel = PanelSwitches.of(Map.of("sites-backend", "hub"));
        assertEquals("hub", panel.choice("sites-backend", null, values, "bot").get());
        assertEquals("bot", panel.choice("sites-backend", "BOT", values, "bot").get(), "Railway's bot holds it there");
        assertEquals("bot", panel.choice("sites-backend", "on", values, "bot").get(), "a Railway value that isn't a choice holds the default");
        assertEquals("hub", panel.choice("sites-backend", "", values, "bot").get(), "blank lets the panel decide");
        assertEquals("bot", PanelSwitches.of(Map.of("sites-backend", "elsewhere")).choice("sites-backend", null, values, "bot").get(),
                "a panel value that isn't a choice is the default");
        assertEquals("bot", PanelSwitches.none().choice("sites-backend", null, values, "bot").get());
    }

    @Test
    void theLastAnswerStandsWhileTheHubIsDownAndNothingIsGuessedBeforeTheFirst() {
        TestClock clock = new TestClock();
        AtomicReference<DataObject> hub = new AtomicReference<>(switches("hub-access", "on"));
        PanelSwitches panel = new PanelSwitches(hub::get, clock);
        BooleanSupplier access = panel.toggle("hub-access", null);
        assertFalse(access.getAsBoolean(), "before the first answer");

        panel.refresh();
        assertTrue(access.getAsBoolean());
        hub.set(switches("hub-access", "off"));
        clock.advance(Duration.ofSeconds(30));
        panel.refresh();
        assertFalse(access.getAsBoolean(), "a change on the panel shows on the next read");

        hub.set(switches("hub-access", "on"));
        panel.refresh();
        hub.set(null); // the hub stops answering
        clock.advance(Duration.ofMinutes(11));
        panel.refresh();
        assertTrue(access.getAsBoolean(), "the last answer stands past 10 minutes: the hub's beta lists aren't swapped for the bot's stale ones");
        clock.advance(Duration.ofHours(6));
        panel.refresh();
        assertEquals("on", panel.value("hub-access"));

        hub.set(switches("hub-access", "off"));
        panel.refresh();
        assertFalse(access.getAsBoolean(), "a fresh answer changes it as soon as the hub answers again");
    }

    @Test
    void aBrokenAnswerIsNoAnswer() {
        assertNull(PanelSwitches.answerOf(null));
        assertNull(PanelSwitches.answerOf(new RuneClansHubClient.Answer(503, null)));
        assertNull(PanelSwitches.answerOf(new RuneClansHubClient.Answer(500, DataObject.empty().put("switches", switches("x", "on")))),
                "an error status is no answer even with a switches body");
        assertNull(PanelSwitches.answerOf(new RuneClansHubClient.Answer(200, DataObject.empty().put("other", 1))));
        assertNull(PanelSwitches.answerOf(new RuneClansHubClient.Answer(200, DataObject.empty().put("switches", DataObject.empty()))),
                "an empty switches list is no answer: the last one stands");
        assertEquals("on", PanelSwitches.answerOf(new RuneClansHubClient.Answer(200, DataObject.empty().put("switches", switches("x", "on"))))
                .getString("x"));
        PanelSwitches throwing = new PanelSwitches(() -> {
            throw new IllegalStateException("down");
        }, Clock.systemUTC());
        throwing.refresh();
        assertNull(throwing.value("x"));
    }

    @Test
    void clanSitesFollowThePanelsChoice() {
        com.runeclans.bot.config.BotConfig config = org.mockito.Mockito.mock(com.runeclans.bot.config.BotConfig.class);
        RuneClansHubClient client = org.mockito.Mockito.mock(RuneClansHubClient.class);
        org.mockito.Mockito.when(client.enabled()).thenReturn(true);
        TestClock clock = new TestClock();
        AtomicReference<DataObject> hub = new AtomicReference<>(switches(HubSites.SWITCH_ID, "bot"));
        PanelSwitches panel = new PanelSwitches(hub::get, clock);
        panel.refresh();

        HubSites sites = HubSites.hubSites(config, client, panel);
        assertFalse(sites.movedToHub());
        hub.set(switches(HubSites.SWITCH_ID, "hub"));
        panel.refresh();
        assertTrue(sites.movedToHub(), "the panel moved clan sites to the API while the bot runs");
        assertTrue(sites.readsHub());

        hub.set(null); // the hub stops answering
        clock.advance(Duration.ofHours(1));
        panel.refresh();
        assertTrue(sites.movedToHub(), "a hub outage never moves clan sites back to the bot: writes keep answering moved");

        org.mockito.Mockito.when(config.getSitesBackend()).thenReturn("bot");
        assertFalse(HubSites.hubSites(config, client, panel).movedToHub(), "SITES_BACKEND=bot on Railway still wins");
    }
}
