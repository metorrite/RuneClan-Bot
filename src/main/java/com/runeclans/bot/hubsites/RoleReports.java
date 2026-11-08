package com.runeclans.bot.hubsites;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Who holds the roles a site's rules follow, reported to the hub (T-505, D-13): only roles the hub's {@code role-mappings} name, one
 * {@code PUT /v1/guilds/{guildId}/role-members/{roleId}} per role with every holder. Member role changes are gathered for a few seconds
 * so a burst (a role panel, a bulk edit) still sends one report per role. Discord ids go only to the hub, never to the log.
 * <p>Discord answers only one member-list request per server every 30 seconds and silently drops the rest, so the holders of every role
 * due in a server come from ONE member load: asking once per role left all but the first role of each server unreported, and the
 * hub then counted the clan's chosen editor roles as unreported (T-963 fallback) for everyone.
 */
final class RoleReports {
    /** How long role changes are gathered before the report goes out. */
    static final Duration DEBOUNCE = Duration.ofSeconds(5);

    /** Runs {@code task} once after {@code delay}. */
    interface Timer {
        void after(Duration delay, Runnable task);
    }

    /** Sends one role's holders to the hub. */
    interface Sender {
        void send(long guildId, long roleId, List<Long> holders);
    }

    record Key(long guildId, long roleId) {}

    private final Function<Long, Optional<Set<Long>>> mappedRoles;
    /**
     * Every holder of each of the roles in a server, from one member load: role id to holders (a role deleted in Discord maps to no
     * holders), or null when Discord couldn't say (nothing is sent then: never a wrong "nobody").
     */
    private final BiFunction<Long, Set<Long>, Map<Long, List<Long>>> holders;
    private final Sender sender;
    private final Timer timer;
    private final Set<Key> pending = new LinkedHashSet<>();
    private boolean scheduled;

    RoleReports(Function<Long, Optional<Set<Long>>> mappedRoles, BiFunction<Long, Set<Long>, Map<Long, List<Long>>> holders, Sender sender, Timer timer) {
        this.mappedRoles = mappedRoles;
        this.holders = holders;
        this.sender = sender;
        this.timer = timer;
    }

    /** A member's roles changed in {@code guildId}: the mapped ones among {@code roleIds} are reported after {@link #DEBOUNCE}. */
    void changed(long guildId, Collection<Long> roleIds) {
        Set<Long> mapped = mappedRoles.apply(guildId).orElse(Set.of());
        boolean schedule = false;
        synchronized (this) {
            for (long role : roleIds) if (mapped.contains(role)) pending.add(new Key(guildId, role));
            if (!pending.isEmpty() && !scheduled) {
                scheduled = true;
                schedule = true;
            }
        }
        if (schedule) timer.after(DEBOUNCE, this::flush);
    }

    /** Reports every mapped role of {@code guildId} now (on start). Returns how many roles were reported. */
    int all(long guildId) {
        return report(guildId, mappedRoles.apply(guildId).orElse(Set.of()));
    }

    /** Sends the gathered roles, one report each. Returns how many were sent. */
    int flush() {
        List<Key> due;
        synchronized (this) {
            due = List.copyOf(pending);
            pending.clear();
            scheduled = false;
        }
        Map<Long, Set<Long>> byGuild = new LinkedHashMap<>();
        for (Key key : due) byGuild.computeIfAbsent(key.guildId(), g -> new LinkedHashSet<>()).add(key.roleId());
        int sent = 0;
        for (Map.Entry<Long, Set<Long>> e : byGuild.entrySet()) sent += report(e.getKey(), e.getValue());
        return sent;
    }

    /** One member load for the server, then one report per role. Returns how many roles were reported. */
    private int report(long guildId, Set<Long> roleIds) {
        if (roleIds.isEmpty()) return 0;
        Map<Long, List<Long>> loaded = holders.apply(guildId, Set.copyOf(roleIds));
        if (loaded == null) return 0;
        int sent = 0;
        for (long role : roleIds) {
            List<Long> who = loaded.get(role);
            if (who == null) continue;
            sender.send(guildId, role, who);
            sent++;
        }
        return sent;
    }
}
