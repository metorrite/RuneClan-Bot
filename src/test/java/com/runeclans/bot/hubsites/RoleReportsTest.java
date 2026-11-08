package com.runeclans.bot.hubsites;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Mapped role holders reach the hub once per role, however many changes come in a burst (T-505, D-13). */
class RoleReportsTest {
    // fake ids, made at runtime
    private static final long GUILD = Long.parseLong("2".repeat(18));
    private static final long MAPPED = Long.parseLong("4".repeat(18));
    private static final long OTHER_MAPPED = Long.parseLong("5".repeat(18));
    private static final long UNMAPPED = Long.parseLong("6".repeat(18));
    private static final long MEMBER = Long.parseLong("1".repeat(18));

    private final List<String> sent = new ArrayList<>();
    private final List<Runnable> timers = new ArrayList<>();
    private final List<Duration> delays = new ArrayList<>();
    /** How many member loads Discord was asked for. */
    private int loads;

    private RoleReports reports(List<Long> holders) {
        return new RoleReports(g -> Optional.of(Set.of(MAPPED, OTHER_MAPPED)), (g, roles) -> {
                    loads++;
                    if (holders == null) return null;
                    Map<Long, List<Long>> out = new HashMap<>();
                    for (long r : roles) out.put(r, holders);
                    return out;
                },
                (g, r, who) -> sent.add(g + "/" + r + "=" + who), (delay, task) -> {
                    delays.add(delay);
                    timers.add(task);
                });
    }

    @Test
    void aRoleChangeSendsOneReportForThatRole() {
        RoleReports reports = reports(List.of(MEMBER));
        reports.changed(GUILD, List.of(MAPPED));
        reports.changed(GUILD, List.of(MAPPED));
        reports.changed(GUILD, List.of(MAPPED, UNMAPPED));
        assertEquals(1, timers.size(), "one wait for the whole burst");
        assertEquals(RoleReports.DEBOUNCE, delays.get(0));
        assertEquals(List.of(), sent, "nothing before the wait is over");

        timers.get(0).run();
        assertEquals(List.of(GUILD + "/" + MAPPED + "=[" + MEMBER + "]"), sent);
    }

    @Test
    void onlyMappedRolesAreReported() {
        RoleReports reports = reports(List.of(MEMBER));
        reports.changed(GUILD, List.of(UNMAPPED));
        assertEquals(0, timers.size());
        assertEquals(0, reports.flush());
        assertEquals(List.of(), sent);
    }

    @Test
    void aLaterChangeWaitsAgain() {
        RoleReports reports = reports(List.of());
        reports.changed(GUILD, List.of(MAPPED));
        timers.get(0).run();
        reports.changed(GUILD, List.of(OTHER_MAPPED));
        assertEquals(2, timers.size());
        timers.get(1).run();
        assertEquals(List.of(GUILD + "/" + MAPPED + "=[]", GUILD + "/" + OTHER_MAPPED + "=[]"), sent);
    }

    @Test
    void onStartEveryMappedRoleIsReported() {
        assertEquals(2, reports(List.of(MEMBER)).all(GUILD));
        assertEquals(2, sent.size());
        assertEquals(1, loads, "one member load per server: Discord drops a second member-list request within 30 seconds");
    }

    @Test
    void aBurstOverSeveralRolesLoadsTheServerOnce() {
        RoleReports reports = reports(List.of(MEMBER));
        reports.changed(GUILD, List.of(MAPPED, OTHER_MAPPED));
        timers.get(0).run();
        assertEquals(2, sent.size());
        assertEquals(1, loads);
    }

    @Test
    void holdersDiscordCouldNotLoadAreNotReportedAsNobody() {
        RoleReports reports = reports(null);
        reports.changed(GUILD, List.of(MAPPED));
        timers.get(0).run();
        assertEquals(List.of(), sent);
        assertEquals(0, reports.all(GUILD));
    }
}
