package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Clan points read from and saved to the hub (T-978): the history routes, the "no clan" memory and thresholds-only saves. */
class ClanPointsRepositoryTest {
    // made-up ids, built at run time
    private static final long GUILD = Long.parseLong("5".repeat(17) + "8");
    private static final String BASE = "/v1/guilds/" + GUILD + "/points";

    private static RuneClansHubClient.Read answer(int status, DataObject json) {
        return new RuneClansHubClient.Read(new RuneClansHubClient.Answer(status, json), false, false);
    }

    @Test
    void historyComesFromTheHub() {
        RuneClansHubClient hub = mock(RuneClansHubClient.class);
        LocalDate from = LocalDate.of(2026, 10, 1);
        when(hub.read(eq(BASE + "/earned?from=2026-10-01&by=day"), any(Duration.class), any(String[].class))).thenReturn(answer(200, DataObject.empty().put("earned",
                DataArray.empty().add(DataObject.empty().put("rsn", "RaceA").put("day", "2026-10-01").put("points", 15))
                        .add(DataObject.empty().put("rsn", "RaceA").put("day", "2026-10-04").put("points", 7)))));
        when(hub.read(eq(BASE + "/awards?rsn=Ace%20Two&limit=2"), any(Duration.class), any(String[].class))).thenReturn(answer(200, DataObject.empty().put("awards",
                DataArray.empty().add(DataObject.empty().put("type", "CITADEL_CAP").put("points", 20).put("awardedFor", "2026-11-04")))));
        ClanPointsRepository points = new ClanPointsRepository(hub);

        assertEquals(List.of(new ClanPointsRepository.Earned("RaceA", from, 15), new ClanPointsRepository.Earned("RaceA", from.plusDays(3), 7)),
                points.earned(GUILD, from, null, null, true));
        assertEquals(List.of(new ClanPointsRepository.Award("CITADEL_CAP", 20, LocalDate.of(2026, 11, 4))), points.awards(GUILD, "Ace Two", 2));
        assertTrue(points.earned(GUILD, from, from.plusDays(1), "Nobody", false).isEmpty(), "no answer: nothing earned");
    }

    @Test
    void aServerWithNoClanIsRememberedForAMinute() {
        RuneClansHubClient hub = mock(RuneClansHubClient.class);
        when(hub.read(eq(BASE), any(Duration.class), any(String[].class))).thenReturn(answer(409, DataObject.empty().put("error", "no_clan")));
        ClanPointsRepository points = new ClanPointsRepository(hub);
        assertNull(points.known(GUILD));
        assertEquals(ClanPointsRepository.STANDARD_RANK_NAMES.size(), points.getRanksOrdered(GUILD).size());
        assertEquals(0, points.getSettings(GUILD).dailyMembershipPoints());
        verify(hub, times(1)).read(eq(BASE), any(Duration.class), any(String[].class));
    }

    @Test
    void aThresholdsOnlySaveSendsNoAmounts() {
        RuneClansHubClient hub = mock(RuneClansHubClient.class);
        when(hub.call(eq("PUT"), eq(BASE), anyString())).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("dailyMembershipPoints", 5)));
        ClanPointsRepository points = new ClanPointsRepository(hub);
        DataObject saved = points.save(GUILD, 7, null, null, null, Map.of(1L, 40L));
        assertEquals(5, ClanPointsRepository.settingsOf(saved).dailyMembershipPoints(), "the save's answer draws the panel");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(hub).call(eq("PUT"), eq(BASE), body.capture());
        DataObject sent = DataObject.fromJson(body.getValue());
        assertFalse(sent.hasKey("dailyMembershipPoints") || sent.hasKey("citadelVisitPoints") || sent.hasKey("citadelCapPoints"), sent.toString());
        assertEquals("40", String.valueOf(sent.getArray("ranks").getObject(0).get("threshold")));
    }

    @Test
    void ranksAndNamesFollowTheStandardLadder() {
        assertEquals(0, ClanPointsRepository.standardOrder("recruit"));
        assertEquals(10, ClanPointsRepository.standardOrder(" Deputy Owner "));
        assertEquals(-1, ClanPointsRepository.standardOrder("Friend"));
        assertEquals("ace two", ClanPointsRepository.key("Ace_Two"));
    }
}
