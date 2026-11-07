package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether the bot tracks a name (T-846, the Refresh button): a {@code /rsn} account or a name in the hub's last polling list. The hub does all
 * the polling; this only answers "is this name one of ours" for the Refresh route. A web request never waits on the hub: it answers from the last
 * list, and when that is older than {@link HubPollingList#KEEP} (or was never read) a fresh read is started in the background for the next one.
 */
@BService
public class TrackedNames {
    private HubPollingList hubList;
    private PlayerLinkService linkService;
    private Executor refresher = BotScheduler.lane("tracked-names");
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public TrackedNames(RuneClansHubClient hub, PlayerLinkService linkService) {
        this.hubList = HubPollingList.from(hub);
        this.linkService = linkService;
    }

    /** For tests: a scripted polling list and a runner for the background read (a service class has exactly one constructor). */
    static TrackedNames forTest(HubPollingList hubList, PlayerLinkService linkService, Executor refresher) {
        TrackedNames names = new TrackedNames(null, linkService);
        names.hubList = hubList;
        names.refresher = refresher;
        return names;
    }

    public boolean tracked(String rsn) {
        if (rsn == null || rsn.isBlank()) return false;
        refreshIfStale();
        String key = rsn.strip().toLowerCase(Locale.ROOT);
        HubPollingList.Groups hub = hubList.lastKnown();
        for (List<String> list : List.of(hub.clan(), hub.community(), hub.linked(), linkService.getAllAccountRsns())) {
            for (String name : list) if (name.strip().toLowerCase(Locale.ROOT).equals(key)) return true;
        }
        return false;
    }

    /** Starts one background read of the hub's list when the last one is stale; never more than one at a time. */
    private void refreshIfStale() {
        if (hubList.fresh() || !refreshing.compareAndSet(false, true)) return;
        try {
            refresher.execute(() -> {
                try {
                    hubList.groups();
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (RuntimeException e) {
            refreshing.set(false);
        }
    }
}
