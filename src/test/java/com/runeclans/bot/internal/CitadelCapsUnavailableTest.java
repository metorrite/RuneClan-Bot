package com.runeclans.bot.internal;

import com.runeclans.bot.internal.CockpitRules.CapWeek;
import com.runeclans.bot.internal.CockpitRules.Caps;
import com.runeclans.bot.internal.CockpitRules.Member;
import com.runeclans.bot.internal.RankAdvisorRules.Check;
import com.runeclans.bot.internal.RankAdvisorRules.Facts;
import com.runeclans.bot.internal.RankAdvisorRules.Ready;
import com.runeclans.bot.internal.RankAdvisorRules.Rule;
import com.runeclans.bot.runescape.RuneMetricsSwitch;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-725: Citadel caps say "unavailable" once the bot stops asking RuneMetrics, and behave as before while it still does. */
class CitadelCapsUnavailableTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 27, 12, 0, 0, 0, ZoneOffset.UTC);
    /** A bot-less caller: the JSON never asks Discord for a name. */
    private static final SiteCaller BOTLESS = new SiteCaller(Long.parseLong("1".repeat(18)), Long.parseLong("2".repeat(18)), null, null, null, null);

    private static Facts member(String rsn, String rank, Long xp, int daysIn, Integer caps, long points) {
        return new Facts(rsn, rank, xp, NOW.minusDays(daysIn), NOW.minusDays(daysIn), caps, points);
    }

    private static Map<String, Rule> rules(Rule... rules) {
        Map<String, Rule> out = new LinkedHashMap<>();
        for (Rule r : rules) out.put(r.rank(), r);
        return out;
    }

    private static List<String> names(List<Ready> ready) {
        return ready.stream().map(r -> r.member().rsn()).toList();
    }

    // ---------- the switch ----------

    @Test
    void capsFollowTheRuneMetricsSwitch() {
        Clock before = Clock.fixed(java.time.Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC);
        Clock after = Clock.fixed(java.time.Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);
        assertTrue(RuneMetricsSwitch.fromEnv(k -> null, before).getAsBoolean(), "auto: counted");
        assertTrue(RuneMetricsSwitch.fromEnv(k -> null, after).getAsBoolean(), "auto: still counted, no stop date (plan D-1)");
        assertTrue(RuneMetricsSwitch.fromEnv(k -> "on", after).getAsBoolean());
        assertFalse(RuneMetricsSwitch.fromEnv(k -> "off", before).getAsBoolean());
    }

    // ---------- rank advisor rules ----------

    @Test
    void withRuneMetricsOnACapsPartCountsAsBefore() {
        Map<String, Rule> r = rules(new Rule("Corporal", 1_000_000L, null, 2, null));
        List<Ready> ready = RankAdvisorRules.ready(List.of(
                member("Capper", "Recruit", 2_000_000L, 30, 3, 0),
                member("No Caps", "Recruit", 2_000_000L, 30, 0, 0),
                member("No Log", "Recruit", 2_000_000L, 30, null, 0)), r, List.of(), NOW, true);
        assertEquals(List.of("Capper"), names(ready), "a private profile stays unknown and not met while RuneMetrics is on");
        Check caps = ready.getFirst().checks().get(1);
        assertEquals("caps", caps.what());
        assertTrue(caps.met());
        assertFalse(caps.unavailable());
        assertEquals(names(ready), names(RankAdvisorRules.ready(List.of(
                member("Capper", "Recruit", 2_000_000L, 30, 3, 0),
                member("No Caps", "Recruit", 2_000_000L, 30, 0, 0),
                member("No Log", "Recruit", 2_000_000L, 30, null, 0)), r, List.of(), NOW)), "the old overload counts caps");
    }

    @Test
    void withRuneMetricsOffTheCapsPartIsUnavailableAndNotCounted() {
        Map<String, Rule> r = rules(new Rule("Corporal", 1_000_000L, 14, 2, null));
        List<Ready> ready = RankAdvisorRules.ready(List.of(
                member("Xp And Days", "Recruit", 2_000_000L, 30, null, 0),
                member("Short Xp", "Recruit", 10L, 30, null, 0),
                member("Too New", "Recruit", 2_000_000L, 3, null, 0)), r, List.of(), NOW, false);
        assertEquals(List.of("Xp And Days"), names(ready), "ranks still work on XP and days; caps don't block anyone");
        Check caps = ready.getFirst().checks().stream().filter(k -> k.what().equals("caps")).findFirst().orElseThrow();
        assertTrue(caps.unavailable());
        assertFalse(caps.counts());
        assertNull(caps.have());
        assertEquals(2, caps.need(), "the owner's saved number is kept for when caps return");
    }

    @Test
    void aRuleThatIsOnlyCapsListsNobodyWhileCapsAreUnavailable() {
        Map<String, Rule> r = rules(new Rule("Corporal", null, null, 3, null));
        List<Facts> members = List.of(member("Anyone", "Recruit", 1L, 900, 12, 0));
        assertTrue(RankAdvisorRules.ready(members, r, List.of(), NOW, false).isEmpty(), "nothing left to check is no rule, not everyone");
        assertEquals(List.of("Anyone"), names(RankAdvisorRules.ready(members, r, List.of(), NOW, true)));
    }

    // ---------- route JSON ----------

    @Test
    void theCockpitCapsSectionSaysUnavailableWithAReasonAndNoRows() {
        DataObject off = SiteCockpitAdmin.capsUnavailableJson();
        assertFalse(off.getBoolean("available"));
        assertEquals(CitadelCaps.UNAVAILABLE_REASON, off.getString("reason"));
        assertTrue(off.getString("reason").startsWith("Citadel caps are unavailable"));
        assertEquals(0, off.getArray("rows").length());
        assertEquals(0, off.getArray("weeks").length());
        assertEquals(0, off.getInt("noLog"));
    }

    @Test
    void theCockpitCapsSectionWithRuneMetricsOnIsTodaysListMarkedAvailable() {
        LocalDate week = RecapPeriod.citadelWeekStart(NOW.toLocalDate());
        List<LocalDate> counted = CockpitRules.countedWeeks(week);
        Member missed = new Member("Missed", "Recruit", NOW.minusDays(300), NOW, null, null, null);
        Member capper = new Member("Capper", "Recruit", NOW.minusDays(300), NOW, null, null, null);
        List<CapWeek> grid = counted.stream().map(w -> new CapWeek("Capper", w, true)).toList();
        Map<String, OffsetDateTime> logs = Map.of("missed", NOW.minusDays(1), "capper", NOW.minusDays(1));
        Caps caps = CockpitRules.caps(List.of(missed, capper), grid, logs, week);
        DataObject on = SiteCockpitAdmin.capsJson(BOTLESS, caps, Map.of(), NOW);
        assertTrue(on.getBoolean("available"));
        assertTrue(on.isNull("reason"));
        assertEquals(CockpitRules.CAP_WEEKS, on.getArray("weeks").length());
        DataArray rows = on.getArray("rows");
        assertEquals(1, rows.length());
        assertEquals("Missed", rows.getObject(0).getString("rsn"));
        assertEquals(CockpitRules.CAP_WEEKS, rows.getObject(0).getInt("missed"));
    }

    @Test
    void theRankAdvisorMarksTheCapsPartUnavailableInItsJson() {
        DataObject off = SiteRankAdvisor.capsJson(false);
        assertFalse(off.getBoolean("available"));
        assertEquals(CitadelCaps.RULES_REASON, off.getString("reason"));
        DataObject on = SiteRankAdvisor.capsJson(true);
        assertTrue(on.getBoolean("available"));
        assertTrue(on.isNull("reason"));

        DataObject part = SiteRankAdvisor.checkJson(new Check("caps", 2, null, false, true));
        assertTrue(part.getBoolean("unavailable"));
        assertTrue(part.isNull("have"));
        DataObject counted = SiteRankAdvisor.checkJson(new Check("caps", 2, 3L, true));
        assertFalse(counted.getBoolean("unavailable"));
        assertEquals(3, counted.getLong("have"));
    }
}
