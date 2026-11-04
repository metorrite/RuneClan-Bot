package com.runeclans.bot.internal;

import com.runeclans.bot.runeclans.HubFeedListener;
import com.runeclans.bot.runescape.stats.StatsBackends;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.Locale;

/**
 * When the hub's poll of a player lands ({@code stats.updated} on the feed, T-851), the bot forgets what it cached about them: the hub
 * backend's batch answer that still holds the old snapshot, and the website's cached profile page ({@link DirectoryPlayerRoutes#CACHE_PREFIX}),
 * so a Refresh or "Update now" shows the new numbers on the next read instead of after the caches' own time. The per-player GET answers are
 * dropped by their tag in the hub client's cache.
 */
@BService
public class HubStatsChanges implements HubFeedListener {
    static final String KIND = "stats.updated";

    private final StatsBackends backends;
    private final SiteCache cache;

    public HubStatsChanges(StatsBackends backends, SiteCache cache) {
        this.backends = backends;
        this.cache = cache;
    }

    @Override
    public void onHubFeed(String kind, DataObject data) {
        if (!KIND.equals(kind) || data == null || !data.hasKey("rsn") || data.isNull("rsn")) return;
        String rsn = data.getString("rsn");
        if (rsn.isBlank()) return;
        backends.evictPlayer(rsn);
        cache.invalidate(DirectoryPlayerRoutes.CACHE_PREFIX + rsn.strip().toLowerCase(Locale.ROOT));
    }
}
