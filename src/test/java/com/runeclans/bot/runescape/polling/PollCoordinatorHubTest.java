package com.runeclans.bot.runescape.polling;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.ProfileResult;
import com.runeclans.bot.runescape.RuneScapeApiClient;
import com.runeclans.bot.runescape.RuneScapeStatsService;
import com.runeclans.bot.runescape.stats.HubStatsBackend;
import com.runeclans.bot.runescape.stats.StatsBackends;
import com.runeclans.bot.tracking.TrackingEventClassifier;
import com.runeclans.bot.tracking.TrackingEventRouter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** T-851: while the hub polls, the bot's queue takes no work, asks RuneMetrics for nobody and writes no snapshot. */
class PollCoordinatorHubTest {
    @Test
    void withTheHubSwitchOnThePollCounterStaysAtZeroAndNoSnapshotIsWritten() {
        RuneScapeApiClient api = mock(RuneScapeApiClient.class);
        PlayerLinkRepository repository = mock(PlayerLinkRepository.class);
        AtomicBoolean hubPolls = new AtomicBoolean(true);
        HubStatsBackend hub = mock(HubStatsBackend.class);
        StatsBackends backends = StatsBackends.of(hubPolls::get, hub);
        RuneScapeStatsService stats = new RuneScapeStatsService(api, repository, mock(TrackingEventClassifier.class), mock(TrackingEventRouter.class), null, backends, null);
        BotConfig config = mock(BotConfig.class);
        when(config.getRunescapePollRequestsPerMinute()).thenReturn(10);
        when(config.getRunescapePollBurst()).thenReturn(6);
        PollCoordinator coordinator = new PollCoordinator(stats, repository, config, backends);

        assertTrue(coordinator.hubPolls());
        PollOutcome outcome = coordinator.pollAndWait(PollRequest.interactive("Zezima", "test"), Duration.ofSeconds(2));
        assertEquals(PollOutcome.Status.CANCELLED, outcome.status());
        assertInstanceOf(ProfileResult.Unavailable.class, outcome.resultOrUnavailable());
        assertEquals(0, coordinator.status().engine().polled());
        assertEquals(0, coordinator.status().engine().queued());
        verify(api, never()).fetchProfileResult(anyString());
        verify(repository, never()).saveSnapshot(anyString(), any(), anyString());

        // a job's run submits nobody either
        AtomicBoolean asked = new AtomicBoolean();
        coordinator.schedule(new PollJob("test job", PollPriority.CLAN, Duration.ofHours(1), Duration.ofMillis(0), Duration.ofSeconds(1), Duration.ofMinutes(1), () -> {
            asked.set(true);
            return List.of("Zezima");
        }));
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(asked.get(), "the job's targets aren't even worked out while the hub polls");
        coordinator.cancel("test job");

        // the service's own raw poll is gated too (belt and braces for callers that keep a reference to it)
        assertInstanceOf(ProfileResult.Unavailable.class, stats.fetchAndStore("Zezima"));
        verify(api, never()).fetchProfileResult(anyString());

        hubPolls.set(false);
        assertFalse(coordinator.hubPolls());
    }
}
