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

    /** Review 2: each pass with a silent server schedules one retry 15 minutes later, for those servers only, and at most 3 retries in all. */
    @Test
    @SuppressWarnings("unchecked")
    void aSilentServerIsRetriedEveryFifteenMinutesAtMostThreeTimes() {
        ClanPointsService service = mock(ClanPointsService.class);
        Guild silent = guild("1"), fine = guild("2");
        net.dv8tion.jda.api.JDA jda = mock(net.dv8tion.jda.api.JDA.class);
        when(jda.getGuildById(silent.getIdLong())).thenReturn(silent);
        when(service.runDailyPointsAndPromotionCheck(silent)).thenReturn(ClanPointsService.HUB_DIDNT_ANSWER);
        when(service.runDailyPointsAndPromotionCheck(fine)).thenReturn(0);
        ClanPointsScheduler scheduler = new ClanPointsScheduler(service, mock(ClanSyncService.class), mock(BotConfig.class));
        java.util.concurrent.ScheduledExecutorService executor = mock(java.util.concurrent.ScheduledExecutorService.class);
        List<Runnable> scheduled = new java.util.ArrayList<>();
        when(executor.schedule(org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(a -> {
                    assertEquals(ClanPointsScheduler.RETRY_AFTER.toSeconds(), (long) a.getArgument(1));
                    assertEquals(java.util.concurrent.TimeUnit.SECONDS, a.getArgument(2));
                    scheduled.add(a.getArgument(0));
                    return mock(java.util.concurrent.ScheduledFuture.class);
                });
        scheduler.executor = executor;

        scheduler.runAndRetry(jda, List.of(silent, fine), 1);
        int retries = 0;
        while (!scheduled.isEmpty() && retries < 10) {
            Runnable next = scheduled.remove(0);
            retries++;
            next.run();
        }
        assertEquals(ClanPointsScheduler.TRIES - 1, retries, "4 tries in all: the first and 3 retries");
        org.mockito.Mockito.verify(service, org.mockito.Mockito.times(ClanPointsScheduler.TRIES)).runDailyPointsAndPromotionCheck(silent);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.times(1)).runDailyPointsAndPromotionCheck(fine);

        // once the hub answers, no more retries
        org.mockito.Mockito.reset(executor);
        when(executor.schedule(org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(a -> {
                    scheduled.add(a.getArgument(0));
                    return mock(java.util.concurrent.ScheduledFuture.class);
                });
        scheduler.runAndRetry(jda, List.of(silent), 1);
        assertEquals(1, scheduled.size());
        when(service.runDailyPointsAndPromotionCheck(silent)).thenReturn(2);
        scheduled.remove(0).run();
        assertTrue(scheduled.isEmpty(), "answered on the retry: nothing more is scheduled");
    }
}
