package com.runeclans.bot.hubsites;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T-817: RuneClan Bot declares its capabilities and role holders to the hub on every shard's ready while clan sites come from the hub (so
 * every shard's servers are covered), and once when the Switches panel moves clan sites to the hub while the bot runs.
 */
class HubSiteDeclarerTest {
    private final AtomicBoolean readsHub = new AtomicBoolean(false);
    private final AtomicBoolean discord = new AtomicBoolean(false);
    private final AtomicInteger declared = new AtomicInteger();
    private final HubSiteReporter.Declarer declarer = new HubSiteReporter.Declarer(readsHub::get, discord::get, declared::incrementAndGet);

    @Test
    void notMovedNeverDeclares() {
        discord.set(true);
        declarer.ready();
        declarer.tick();
        assertEquals(0, declared.get());
    }

    @Test
    void movedBeforeDiscordIsReadyWaitsForIt() {
        readsHub.set(true);
        declarer.tick();
        assertEquals(0, declared.get(), "no Discord yet: nothing to declare");
        discord.set(true);
        declarer.tick();
        declarer.tick();
        declarer.tick();
        assertEquals(1, declared.get(), "declared once across several ticks");
    }

    @Test
    void everyShardsReadyDeclaresAgainSoLaterShardsAreCovered() {
        readsHub.set(true);
        discord.set(true);
        declarer.ready(); // shard 0
        declarer.ready(); // shard 1
        declarer.tick();
        assertEquals(2, declared.get(), "each ready declares; the tick adds nothing after a ready");
    }

    @Test
    void movedBackAndForthDeclaresAgain() {
        discord.set(true);
        readsHub.set(true);
        declarer.tick();
        readsHub.set(false);
        declarer.tick();
        readsHub.set(true);
        declarer.tick();
        assertEquals(2, declared.get());
    }
}
