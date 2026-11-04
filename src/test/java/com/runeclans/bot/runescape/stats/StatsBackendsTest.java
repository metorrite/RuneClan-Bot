package com.runeclans.bot.runescape.stats;

import com.runeclans.bot.runeclans.RuneClansHubClient.Answer;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** T-851: which source answers stats, and how the hub's Refresh answers are read. */
class StatsBackendsTest {
    @Test
    void railwayWordsHoldTheSwitchLikeTheWebsitesStatsBackend() {
        assertEquals("on", StatsBackends.railwayWord("hub"));
        assertEquals("on", StatsBackends.railwayWord(" HUB "));
        assertEquals("off", StatsBackends.railwayWord("bot"));
        assertEquals("on", StatsBackends.railwayWord("true"));
        assertNull(StatsBackends.railwayWord(null));
        assertNull(StatsBackends.railwayWord("  "));
        assertEquals("maybe", StatsBackends.railwayWord("maybe"), "an unknown word is left for PanelSwitches to treat as a hold-off");
    }

    @Test
    void theHubBackendIsCurrentOnlyWhileTheSwitchSaysSoAndAHubExists() {
        HubStatsBackend hub = new HubStatsBackend(new HubStatsBackend.Hub() {
            @Override
            public com.runeclans.bot.runeclans.RuneClansHubClient.Read read(String path, java.time.Duration keepFresh, String... tags) {
                return null;
            }

            @Override
            public Answer call(String method, String path, String body) {
                return null;
            }
        }, (g, a) -> java.util.List.of(), java.time.Clock.systemUTC(), null);
        AtomicBoolean on = new AtomicBoolean(false);
        StatsBackends backends = new StatsBackends(on::get, hub);
        assertFalse(backends.hubPolls());
        assertNull(backends.hub());
        on.set(true);
        assertTrue(backends.hubPolls(), "a flip of the panel switch takes effect on the next read, no restart");
        assertSame(hub, backends.hub());
        on.set(false);
        assertNull(backends.hub());

        StatsBackends noHub = new StatsBackends(() -> true, null);
        assertFalse(noHub.hubPolls(), "the switch counts only with a hub client");
        assertNull(noHub.hub());
        assertFalse(StatsBackends.none().hubPolls());
        assertTrue(StatsBackends.hubAlways(hub).hubPolls());
    }

    @Test
    void refreshAnswersAreReadIntoOutcomes() {
        assertEquals(HubRefresh.Outcome.QUEUED, HubRefresh.of(new Answer(202, DataObject.empty().put("queued", true))).outcome());
        HubRefresh.Result wait = HubRefresh.of(new Answer(429, DataObject.empty().put("error", "refresh_wait").put("retryAfterSeconds", 1234)));
        assertEquals(HubRefresh.Outcome.WAIT, wait.outcome());
        assertEquals(1234, wait.retryAfterSeconds());
        assertEquals("refresh_wait", wait.code());
        HubRefresh.Result actor = HubRefresh.of(new Answer(429, DataObject.empty().put("error", "refresh_actor_wait"), 90));
        assertEquals(90, actor.retryAfterSeconds(), "the header when the body has no seconds");
        assertEquals(HubRefresh.Outcome.UNKNOWN, HubRefresh.of(new Answer(404, DataObject.empty().put("error", "not_found"))).outcome());
        assertEquals(HubRefresh.Outcome.UNAVAILABLE, HubRefresh.of(new Answer(409, DataObject.empty().put("error", "hub_polling_off"))).outcome());
        assertEquals("hub_polling_off", HubRefresh.of(new Answer(409, DataObject.empty().put("error", "hub_polling_off"))).code());
        assertEquals(HubRefresh.Outcome.UNAVAILABLE, HubRefresh.of(null).outcome());
        assertEquals(HubRefresh.Outcome.UNAVAILABLE, HubRefresh.ask((com.runeclans.bot.runeclans.RuneClansHubClient) null, "Zezima", 1L).outcome(), "no hub client");
    }
}
