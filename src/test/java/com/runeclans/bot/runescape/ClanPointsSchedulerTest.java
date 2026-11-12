package com.runeclans.bot.runescape;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.tracking.ClanPointsService;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B-10: the daily Clan Report tries again the servers the hub didn't answer for, and only those. */
class ClanPointsSchedulerTest {
    private static Guild guild(String digit) {
        Guild g = mock(Guild.class);
        when(g.getIdLong()).thenReturn(Long.parseLong(digit.repeat(18)));
        return g;
    }

    @Test
    void onlyTheServersTheHubDidntAnswerForAreTriedAgain() {
        ClanPointsService service = mock(ClanPointsService.class);
        Guild silent = guild("1"), nobody = guild("2"), sent = guild("3"), broken = guild("4");
        when(service.runDailyPointsAndPromotionCheck(silent)).thenReturn(ClanPointsService.HUB_DIDNT_ANSWER);
        when(service.runDailyPointsAndPromotionCheck(nobody)).thenReturn(0);
        when(service.runDailyPointsAndPromotionCheck(sent)).thenReturn(3);
        when(service.runDailyPointsAndPromotionCheck(broken)).thenThrow(new IllegalStateException("boom"));
        ClanPointsScheduler scheduler = new ClanPointsScheduler(service, mock(ClanSyncService.class), mock(BotConfig.class));

        assertEquals(List.of(silent.getIdLong()), scheduler.run(List.of(silent, nobody, sent, broken)));
        assertTrue(ClanPointsScheduler.RETRY_AFTER.compareTo(Duration.ofMinutes(10)) >= 0, "past the hub's 30 s quiet time and a blip");
        assertTrue(ClanPointsScheduler.RETRY_AFTER.multipliedBy(ClanPointsScheduler.TRIES - 1).compareTo(Duration.ofHours(2)) <= 0, "still the same evening");
    }
}
