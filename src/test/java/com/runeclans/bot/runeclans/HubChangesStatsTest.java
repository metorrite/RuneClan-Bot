package com.runeclans.bot.runeclans;

import com.runeclans.bot.runescape.stats.HubStatsBackend;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** T-851: a poll the hub reports in its feed empties the player's cached stats answers. */
class HubChangesStatsTest {
    @Test
    void statsUpdatedAndPlayerActivityDropThePlayersAnswers() {
        HubCache cache = new HubCache(10, Clock.systemUTC());
        RuneClansHubClient.Answer a = new RuneClansHubClient.Answer(200, DataObject.empty());
        cache.put("/v1/players/Zezima/stats/history?limit=5", a, Set.of(HubStatsBackend.playerTag("Zezima")));
        cache.put("/v1/players/Zezima/activity?limit=5", a, Set.of(HubStatsBackend.playerTag("Zezima")));
        cache.put("/v1/players/Other/activity?limit=5", a, Set.of(HubStatsBackend.playerTag("Other")));
        assertEquals(2, HubChanges.evict(cache, "stats.updated", DataObject.empty().put("rsn", "zezima").put("snapshotId", 1)));
        assertEquals(1, cache.size());
        cache.put("/v1/players/Zezima/activity?limit=5", a, Set.of(HubStatsBackend.playerTag("Zezima")));
        assertEquals(1, HubChanges.evict(cache, "player.activity", DataObject.empty().put("rsn", "Zezima").put("id", 9)));
        assertEquals(0, HubChanges.evict(cache, "player.activity", null));
        assertEquals(0, HubChanges.evict(cache, "plugin.event", DataObject.empty().put("rsn", "Other")));
        assertNotNull(cache.lookup("/v1/players/Other/activity?limit=5"));
    }
}
