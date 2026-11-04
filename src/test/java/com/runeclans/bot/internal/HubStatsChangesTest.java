package com.runeclans.bot.internal;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.stats.HubStatsBackend;
import com.runeclans.bot.runescape.stats.StatsBackends;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T-851: a landed hub poll drops the bot's cached batch answer and the website's cached profile for that player. */
class HubStatsChangesTest {
    @Test
    void aStatsUpdatedEntryDropsTheBatchAndTheProfilePage() {
        List<String> calls = new ArrayList<>();
        HubStatsBackend.Hub hub = new HubStatsBackend.Hub() {
            @Override
            public RuneClansHubClient.Read read(String path, java.time.Duration keepFresh, String... tags) {
                return null;
            }

            @Override
            public RuneClansHubClient.Answer call(String method, String path, String body) {
                calls.add(method + " " + path);
                return new RuneClansHubClient.Answer(200, DataObject.empty().put("players", DataArray.empty().add(DataObject.empty().put("rsn", "Zezima")
                        .put("snapshotId", 7).put("at", "2026-11-04T12:00:00Z").put("totalLevel", 2898).put("skills", DataArray.empty()))).put("missing", DataArray.empty()), 0);
            }
        };
        HubStatsBackend backend = new HubStatsBackend(hub, (g, a) -> List.of(), Clock.systemUTC(), null);
        StatsBackends backends = StatsBackends.hubAlways(backend);
        SiteCache cache = new SiteCache();
        cache.get(DirectoryPlayerRoutes.CACHE_PREFIX + "zezima", 60_000, () -> DataObject.empty().put("old", true));
        HubStatsChanges changes = new HubStatsChanges(backends, cache);

        assertEquals(7, backend.latest("Zezima").snapshotId());
        backend.latest("Zezima");
        assertEquals(1, calls.size(), "the second look is the cached batch");

        changes.onHubFeed("player.activity", DataObject.empty().put("rsn", "Zezima"));
        backend.latest("Zezima");
        assertEquals(1, calls.size(), "only stats.updated says the snapshot changed");

        changes.onHubFeed("stats.updated", DataObject.empty().put("rsn", "Zezima"));
        backend.latest("Zezima");
        assertEquals(2, calls.size(), "after the hub's poll landed the next read fetches again");
        assertNull(cache.get(DirectoryPlayerRoutes.CACHE_PREFIX + "zezima", 60_000, () -> null), "the website's cached profile page went too");

        changes.onHubFeed("stats.updated", DataObject.empty().put("rsn", "Other"));
        changes.onHubFeed("stats.updated", DataObject.empty());
        changes.onHubFeed("stats.updated", null);
        backend.latest("Zezima");
        assertEquals(2, calls.size(), "another player's change leaves this batch alone");
    }
}
