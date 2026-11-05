package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.List;
import java.util.Locale;

/**
 * Whether the bot tracks a name (T-846, the Refresh button): a {@code /rsn} account or a name in the hub's last polling list. The hub does all
 * the polling; this only answers "is this name one of ours" for the Refresh route. Never asks the hub from here, so a web request doesn't wait
 * on it.
 */
@BService
public class TrackedNames {
    private final HubPollingList hubList;
    private final PlayerLinkService linkService;

    public TrackedNames(RuneClansHubClient hub, PlayerLinkService linkService) {
        this.hubList = HubPollingList.from(hub);
        this.linkService = linkService;
    }

    public boolean tracked(String rsn) {
        if (rsn == null || rsn.isBlank()) return false;
        String key = rsn.strip().toLowerCase(Locale.ROOT);
        HubPollingList.Groups hub = hubList.lastKnown();
        for (List<String> list : List.of(hub.clan(), hub.community(), hub.linked(), linkService.getAllAccountRsns())) {
            for (String name : list) if (name.strip().toLowerCase(Locale.ROOT).equals(key)) return true;
        }
        return false;
    }
}
