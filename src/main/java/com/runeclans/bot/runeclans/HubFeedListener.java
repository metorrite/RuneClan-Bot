package com.runeclans.bot.runeclans;

import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;
import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * Told about every entry of JonnyBot's hub change feed ({@link RuneClansFeedPoller}, with {@code RUNECLANS_HUB_ENABLED} on): its
 * {@code kind} and its {@code data} ({@code {item, id, rev, origin, actor, data}} for changes made through the hub's write path). Called
 * on the feed's lane, in feed order; an implementation hands slow work to its own lane and never throws.
 */
@InterfacedService(acceptMultiple = true)
public interface HubFeedListener {
    void onHubFeed(String kind, DataObject data);

    /** Every entry of one feed page has been handed over (T-851): a listener that gathers entries acts on them now. */
    default void onHubFeedPage() {
    }
}
