package com.runeclans.bot.tracking;

import com.runeclans.bot.runescape.ClanPointsRepository;
import com.runeclans.bot.runescape.ClanPointsRepository.RankConfigRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClanPointsServiceTest {
    private static List<RankConfigRow> ladder(long... thresholds) {
        List<RankConfigRow> ranks = new ArrayList<>();
        for (int order = 0; order < thresholds.length; order++) {
            ranks.add(new RankConfigRow(order, 1L, "Rank" + order, order, thresholds[order]));
        }
        return ranks;
    }

    @Test
    void unconfiguredTiersAboveTheConfiguredOnesAreNeverEarned() {
        // Live's real shape: Recruit..Captain set, General..Owner still at their default of 0. Counting a
        // 0 threshold as "met" made every member eligible for the top tier — the whole clan listed for Owner.
        List<RankConfigRow> ranks = ladder(30, 60, 90, 120, 150, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(0, ClanPointsService.earnedRankOrder(ranks, 0));
        assertEquals(0, ClanPointsService.earnedRankOrder(ranks, 6));
        assertEquals(1, ClanPointsService.earnedRankOrder(ranks, 60));
        assertEquals(4, ClanPointsService.earnedRankOrder(ranks, 150));
        assertEquals(4, ClanPointsService.earnedRankOrder(ranks, 1_000_000));
    }

    @Test
    void lowestRankIsAlwaysEarnedEvenWithAThresholdSetAboveZeroPoints() {
        assertEquals(0, ClanPointsService.earnedRankOrder(ladder(30, 60), 0));
    }

    @Test
    void anUnconfiguredTierInTheMiddleIsSkippedNotBlocking() {
        // Corporal set, Sergeant left at 0, Lieutenant set: 100 points earns Lieutenant.
        assertEquals(3, ClanPointsService.earnedRankOrder(ladder(0, 50, 0, 100), 100));
        assertEquals(1, ClanPointsService.earnedRankOrder(ladder(0, 50, 0, 100), 99));
    }

    @Test
    void paginateKeepsEveryEntryInOrderAndEveryPageUnderTheLimit() {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 61; i++) entries.add("entry-" + i + " " + "x".repeat(120)); // ~61 members' worth, like the live report

        List<String> pages = ClanPointsService.paginate(entries);

        assertTrue(pages.size() > 1, "61 entries of this size don't fit in one message");
        for (String page : pages) assertTrue(page.length() <= ClanPointsService.MAX_PAGE_CHARS, "page was " + page.length());

        String rejoined = String.join("\n\n", pages);
        assertEquals(String.join("\n\n", entries), rejoined, "no entry lost, reordered, or split across pages");
    }

    @Test
    void paginateReturnsOnePageWhenEverythingFits() {
        assertEquals(List.of("a\n\nb"), ClanPointsService.paginate(List.of("a", "b")));
    }

    @Test
    void paginateOfNothingIsNothing() {
        assertEquals(List.of(), ClanPointsService.paginate(List.of()));
    }

    @Test
    void clanReportSortsAnUnmodifiableFlaggedList() {
        // The repository returns Stream.toList(), which is unmodifiable: sorting it in place crashed the report.
        var later = new ClanPointsRepository.MemberPointsRow("Later", 10, true, java.time.LocalDate.of(2026, 1, 5));
        var earlier = new ClanPointsRepository.MemberPointsRow("Earlier", 10, true, java.time.LocalDate.of(2026, 1, 1));
        var unknown = new ClanPointsRepository.MemberPointsRow("Unknown", 10, true, null);
        List<ClanPointsRepository.MemberPointsRow> flagged = List.of(unknown, later, earlier);
        assertEquals(List.of(earlier, later, unknown), ClanPointsService.longestWaitingFirst(flagged));
    }
}
