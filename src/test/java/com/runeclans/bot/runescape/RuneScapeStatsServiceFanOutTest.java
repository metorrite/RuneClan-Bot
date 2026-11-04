package com.runeclans.bot.runescape;

import com.runeclans.bot.tracking.ClassifiedEntry;
import com.runeclans.bot.tracking.TrackingEventClassifier;
import com.runeclans.bot.tracking.TrackingEventRouter;
import com.runeclans.bot.tracking.TrackingGroup;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A player is polled once for every server; what the poll finds is announced in each server whose clan they are in. */
class RuneScapeStatsServiceFanOutTest {
    private static final String RSN = "Jonny Young";

    private RuneScapeApiClient apiClient;
    private PlayerLinkRepository repository;
    private TrackingEventClassifier classifier;
    private TrackingEventRouter router;
    private ClanMemberRepository clanMembers;
    private RuneScapeStatsService service;
    private Guild first;
    private Guild second;

    @BeforeEach
    void setUp() throws Exception {
        apiClient = mock(RuneScapeApiClient.class);
        repository = mock(PlayerLinkRepository.class);
        classifier = mock(TrackingEventClassifier.class);
        router = mock(TrackingEventRouter.class);
        clanMembers = mock(ClanMemberRepository.class);
        service = new RuneScapeStatsService(apiClient, repository, classifier, router, clanMembers, com.runeclans.bot.runescape.stats.StatsBackends.none(), null);

        first = mock(Guild.class);
        second = mock(Guild.class);
        JDA jda = mock(JDA.class);
        when(jda.getGuildById(10L)).thenReturn(first);
        when(jda.getGuildById(20L)).thenReturn(second);
        var field = RuneScapeStatsService.class.getDeclaredField("jda");
        field.setAccessible(true);
        field.set(service, jda);
    }

    private static RuneScapeProfile profileWith(PlayerActivity... activities) {
        return new RuneScapeProfile(RSN, 2000, 1_000_000L, 138, 300, 0, 0, List.of(), List.of(activities));
    }

    private void polledAndFound(PlayerActivity activity) {
        RuneScapeProfile profile = profileWith(activity);
        when(apiClient.fetchProfileResult(RSN)).thenReturn(new ProfileResult.Found(profile));
        when(repository.saveSnapshot(eq(RSN), any(), any())).thenReturn(1L);
        when(repository.saveActivities(eq(RSN), anyList())).thenReturn(List.of(activity));
        when(classifier.classify(eq(RSN), eq(activity), anyInt()))
                .thenReturn(Optional.of(new ClassifiedEntry(TrackingGroup.CLAN_JOINS_LEAVES, "found something")));
    }

    @Test
    void oneRequestIsAnnouncedInEveryServerThePlayerIsInTheClanOf() {
        PlayerActivity activity = new PlayerActivity("06-Oct-2026 20:06", "I killed 3 Amascuts.", "I killed 3 goddess' of destruction.");
        polledAndFound(activity);
        when(clanMembers.activeGuildIds(RSN)).thenReturn(List.of(10L, 20L));

        service.fetchAndStore(RSN);

        verify(apiClient).fetchProfileResult(RSN);
        verify(repository).saveSnapshot(eq(RSN), any(), any());
        verify(router).dispatchAll(eq(first), anyList());
        verify(router).dispatchAll(eq(second), anyList());
    }

    @Test
    void aServerWhoseClanThePlayerIsNotInHearsNothing() {
        PlayerActivity activity = new PlayerActivity("06-Oct-2026 20:06", "I killed 3 Amascuts.", "I killed 3 goddess' of destruction.");
        polledAndFound(activity);
        when(clanMembers.activeGuildIds(RSN)).thenReturn(List.of(20L));

        service.fetchAndStore(RSN);

        verify(router, never()).dispatchAll(eq(first), anyList());
        verify(router).dispatchAll(eq(second), anyList());
    }

    @Test
    void aLinkedPlayerInNoClanIsStoredButNeverAnnounced() {
        PlayerActivity activity = new PlayerActivity("06-Oct-2026 20:06", "I killed 3 Amascuts.", "I killed 3 goddess' of destruction.");
        polledAndFound(activity);
        when(clanMembers.activeGuildIds(RSN)).thenReturn(List.of());

        service.fetchAndStore(RSN);

        verify(repository).saveActivities(eq(RSN), anyList());
        verify(router, never()).dispatchAll(any(), anyList());
    }

    @Test
    void aRateLimitedPollIsReportedAndNothingIsSaved() {
        when(apiClient.fetchProfileResult(RSN)).thenReturn(new ProfileResult.RateLimited(Duration.ofSeconds(30)));

        assertInstanceOf(ProfileResult.RateLimited.class, service.fetchAndStore(RSN));

        verify(repository, never()).saveSnapshot(any(), any(), any());
    }
}
