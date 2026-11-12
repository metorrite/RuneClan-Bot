package com.runeclans.bot.notice;

import com.runeclans.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Who owns JonnyBot itself, as opposed to who runs one server it is in: the Discord application's owner (or the members of
 * its team), plus anyone listed in {@code OWNER_IDS}. Owners may post the notices every dashboard shows. Looked up from
 * Discord rather than configured, so it works without extra setup, and remembered briefly because it almost never changes.
 * <p>
 * A failed lookup (Discord unreachable, or the bot just starting or stopping) is not remembered as "nobody owns it": whoever was
 * known before stays known, and the lookup is tried again shortly instead of after the full refresh time.
 * <p>
 * The lookup never holds up a caller for long (B-40, CLAUDE.md "never wait on Discord without a time limit inside a web request"): it runs on
 * its own lane, one at a time however many requests ask. Once a lookup has worked, a caller is answered at once from who is known while a
 * stale list is refreshed behind it; before that (just after start-up) a caller waits at most {@link #FIRST_WAIT} for the lookup in flight.
 * The bot asks once when it connects, so that first wait is rare.
 */
@BService
public class BotOwners {
    private static final Logger log = LoggerFactory.getLogger(BotOwners.class);
    private static final long REFRESH_MS = 10 * 60_000L;
    private static final long RETRY_MS = 30_000L;
    /** How long a caller waits for the first lookup still in flight: Discord's own budget for a web request. */
    static final java.time.Duration FIRST_WAIT = com.runeclans.bot.discord.DiscordLookups.BUDGET;

    private final BotConfig config;
    /** Replaceable so tests can move time without waiting. */
    LongSupplier clock = System::currentTimeMillis;
    /** Where the lookup runs; replaceable in tests. */
    java.util.concurrent.Executor lookups = com.runeclans.bot.util.BotScheduler.lane("bot-owners");
    private volatile Set<Long> owners = Set.of();
    private volatile long nextLookupAt;
    /** Whether a lookup has ever worked (until then a caller waits a little for one). */
    private volatile boolean found;
    /** The lookup in flight, if any: one at a time. */
    private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>> inFlight = new java.util.concurrent.atomic.AtomicReference<>();

    public BotOwners(BotConfig config) {
        this.config = config;
    }

    public boolean isOwner(JDA jda, long userId) {
        return owners(jda).contains(userId) || config.getOwnerIds().contains(userId);
    }

    /**
     * {@link #isOwner} from what is already known, never asking Discord: for hot paths inside a web request (the dashboard's {@code whoami},
     * every dashboard call's owner override check). Anyone {@link #isOwner} found before, or listed in {@code OWNER_IDS}.
     */
    public boolean isKnownOwner(long userId) {
        return owners.contains(userId) || config.getOwnerIds().contains(userId);
    }

    /** Everyone known to own the bot right now (the application's owner or team, and {@code OWNER_IDS}), for showing the built-in admins. */
    public Set<Long> known(JDA jda) {
        Set<Long> all = new HashSet<>(owners(jda));
        all.addAll(config.getOwnerIds());
        return Set.copyOf(all);
    }

    /** Asks Discord who owns the bot as soon as it connects, so no request has to wait for the first lookup. */
    @io.github.freya022.botcommands.api.core.annotations.BEventListener
    public void onJdaReady(io.github.freya022.botcommands.api.core.events.InjectedJDAEvent event) {
        lookUp(event.getJda());
    }

    private Set<Long> owners(JDA jda) {
        if (clock.getAsLong() < nextLookupAt) return owners;
        java.util.concurrent.CompletableFuture<Void> lookup = lookUp(jda);
        if (found || lookup == null) return owners;
        try {
            lookup.get(FIRST_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // Discord is slow: answer from who is known (OWNER_IDS); the lookup keeps going and fills the list
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            // lookUp never fails its future; nothing to do
        }
        return owners;
    }

    /** Starts a lookup on {@link #lookups} unless one is in flight; the one in flight (or null when it couldn't be started). */
    private java.util.concurrent.CompletableFuture<Void> lookUp(JDA jda) {
        java.util.concurrent.CompletableFuture<Void> mine = new java.util.concurrent.CompletableFuture<>();
        if (!inFlight.compareAndSet(null, mine)) {
            java.util.concurrent.CompletableFuture<Void> running = inFlight.get();
            return running != null ? running : java.util.concurrent.CompletableFuture.completedFuture(null); // finished just now: its answer is in
        }
        try {
            lookups.execute(() -> {
                try {
                    lookUpNow(jda);
                } finally {
                    inFlight.set(null);
                    mine.complete(null);
                }
            });
        } catch (RuntimeException e) {
            inFlight.set(null);
            nextLookupAt = clock.getAsLong() + RETRY_MS;
            return null;
        }
        return mine;
    }

    private void lookUpNow(JDA jda) {
        try {
            Set<Long> got = new HashSet<>(config.getOwnerIds());
            var info = jda.retrieveApplicationInfo().complete();
            if (info.getOwner() != null) got.add(info.getOwner().getIdLong());
            if (info.getTeam() != null) info.getTeam().getMembers().forEach(member -> got.add(member.getUser().getIdLong()));
            owners = Set.copyOf(got);
            found = true;
            nextLookupAt = clock.getAsLong() + REFRESH_MS;
        } catch (Exception e) {
            // Keep who we knew, and ask again soon. This is routine while the bot is starting or stopping.
            log.warn("Couldn't ask Discord who owns the bot ({}); keeping the owners already known and retrying shortly.", e.getMessage());
            nextLookupAt = clock.getAsLong() + RETRY_MS;
        }
    }
}
