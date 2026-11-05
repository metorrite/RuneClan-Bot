package com.runeclans.bot.events;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRepository.Progress;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.RuneScapeStatsService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Auto competitions (T-557): RuneSync for the players the hub counts, RuneMetrics for the rest, each player owned by whoever counted them first. */
class AutoXpSourceTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ALICE = Long.parseLong("1".repeat(18));
    private static final long BOB = Long.parseLong("2".repeat(18));
    private static final long CAROL = Long.parseLong("3".repeat(18));
    private static final Instant STARTED = Instant.parse("2026-10-20T18:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-20T19:00:30Z");
    private static final String AUTO = "{\"skillId\":9,\"xpSource\":\"auto\"}";

    private final EventRepository repository = Mockito.mock(EventRepository.class);
    private final EventService service = Mockito.mock(EventService.class);
    private final RuneClansHubClient hub = Mockito.mock(RuneClansHubClient.class);
    private final PluginXpTracker plugin = new PluginXpTracker(repository, service, hub).withClock(Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void offAgain() {
        CompetitionTemplate.offerPluginSource(false);
    }

    private static Event event(long id, String config) {
        return new Event(id, GUILD, CompetitionTemplate.KEY, "Comp", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, STARTED, NOW.plusSeconds(86_400), 0, config, 1,
                STARTED.minusSeconds(7200), null, false);
    }

    private static Participant player(long userId) {
        return new Participant(1, userId, GUILD, null, null, false, null, STARTED.minusSeconds(60), null, null, null, null, null, null);
    }

    private static Progress imported(long id, long userId, long amount, String key, Instant at) {
        return new Progress(id, 1, CompetitionTemplate.OBJECTIVE_KEY, userId, null, amount, Source.IMPORT, key, null, null, at);
    }

    @Test
    void theSourceIsReadAndOfferedWithTheOthers() {
        DataObject auto = DataObject.fromJson(AUTO);
        assertEquals(CompetitionTemplate.SOURCE_AUTO, CompetitionTemplate.xpSourceOf(auto));
        assertTrue(CompetitionTemplate.countsAuto(auto));
        assertFalse(CompetitionTemplate.countsFromPlugin(auto), "auto is not RuneSync-only");
        assertTrue(CompetitionTemplate.readsPlugin(auto));
        assertFalse(CompetitionTemplate.readsPlugin(DataObject.empty()));
        assertTrue(CompetitionTemplate.needsHub(CompetitionTemplate.SOURCE_AUTO));
        assertFalse(CompetitionTemplate.needsHub(CompetitionTemplate.SOURCE_RUNEMETRICS));
        assertTrue(new CompetitionTemplate().configProblems(auto).isEmpty());
        CompetitionTemplate.offerPluginSource(true);
        var option = new CompetitionTemplate().options().stream().filter(o -> o.path().equals("config.xpSource")).findFirst().orElseThrow();
        assertEquals(CompetitionTemplate.SOURCE_RUNEMETRICS, option.standard(), "RuneMetrics stays the default");
        assertTrue(option.choices().stream().anyMatch(c -> c.value().equals(CompetitionTemplate.SOURCE_AUTO)));
        assertTrue(new CompetitionTemplate().starterObjectives(auto).get(0).description().contains("RuneMetrics for the rest"));
    }

    @Test
    void whoeverCountedAPlayerFirstOwnsThem() {
        Map<Long, CompetitionTracking.Counter> owners = CompetitionTracking.owners(List.of(
                imported(3, ALICE, 10, "xp:" + ALICE + ":9", STARTED.plusSeconds(600)),
                imported(2, ALICE, 10, "pxp:" + ALICE + ":1", STARTED.plusSeconds(60)),
                imported(4, BOB, 10, "xp:" + BOB + ":9", STARTED.plusSeconds(60)),
                new Progress(5, 1, CompetitionTemplate.OBJECTIVE_KEY, CAROL, null, 10, Source.MANUAL, "pxp:x", null, null, STARTED)));
        assertEquals(Map.of(ALICE, CompetitionTracking.Counter.RUNESYNC, BOB, CompetitionTracking.Counter.RUNEMETRICS), owners, "a hand-entered row owns nobody");
    }

    @Test
    void disagreementNeedsBothAFloorAndAShare() {
        assertFalse(CompetitionTracking.disagrees(0, 100_000), "small gains differ by RuneMetrics' lag");
        assertTrue(CompetitionTracking.disagrees(0, 100_001));
        assertFalse(CompetitionTracking.disagrees(5_000_000, 4_100_000), "under a fifth of a big gain");
        assertTrue(CompetitionTracking.disagrees(5_000_000, 3_900_000));
    }

    @Test
    void thePluginTrackerCountsTheHubsPlayersAndLeavesTheRestToRuneMetrics() {
        Event e = event(1, AUTO);
        when(repository.startedAt(1)).thenReturn(Optional.of(STARTED));
        when(repository.participants(1)).thenReturn(List.of(player(ALICE), player(BOB), player(CAROL)));
        // carol was counted from RuneMetrics first, so she stays RuneMetrics' even if she starts RuneSync
        when(repository.progress(1)).thenReturn(List.of(imported(1, CAROL, 500, "xp:" + CAROL + ":7", STARTED.plusSeconds(300))));
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasOf(BOB)).thenReturn(Optional.empty());
        DataArray players = DataArray.empty().add(DataObject.empty().put("alias", "a1_alice").put("xp", 4_000L));
        when(hub.xpGains(eq(GUILD), any(), eq(9), eq(STARTED), eq(NOW))).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("players", players)));

        assertEquals(1, plugin.feed(e));
        verify(service).recordProgress(eq(GUILD), eq(1L), eq(CompetitionTemplate.OBJECTIVE_KEY), eq(ALICE), eq(4_000L), eq(Source.IMPORT), anyString(), isNull(), anyString());
        verify(hub, never()).aliasOf(CAROL);
        assertEquals(Set.of(ALICE), plugin.runeSyncPlayers(1));
        assertTrue(plugin.notCounted(1).isEmpty(), "in auto nobody is 'not counted': the rest are RuneMetrics'");
    }

    @Test
    void aDisagreementIsKeptUntilTheNumbersAgree() {
        plugin.check(1, ALICE, 1_000_000, 400_000);
        assertEquals(1, plugin.disagreements(1).size());
        assertEquals(400_000, plugin.disagreements(1).get(0).runeMetricsXp());
        plugin.check(1, ALICE, 1_000_000, 950_000);
        assertTrue(plugin.disagreements(1).isEmpty());
    }

    @Test
    void runeMetricsCountsTheOthersAndOnlyChecksTheRuneSyncPlayers() {
        PlayerLinkRepository links = Mockito.mock(PlayerLinkRepository.class);
        RuneScapeStatsService stats = Mockito.mock(RuneScapeStatsService.class);
        EventTracker tracker = new EventTracker(repository, service, links, stats, Mockito.mock(BotConfig.class), plugin);
        Event e = event(2, "{\"xpSource\":\"auto\"}");
        when(repository.participants(2)).thenReturn(List.of(player(ALICE), player(BOB)));
        when(links.getLinksForUser(GUILD, ALICE)).thenReturn(List.of(new PlayerLink(1, GUILD, ALICE, "Alice", null, null, null)));
        when(links.getLinksForUser(GUILD, BOB)).thenReturn(List.of(new PlayerLink(2, GUILD, BOB, "Bob", null, null, null)));
        when(stats.getLatestSnapshot(anyString())).thenReturn(new PlayerLinkRepository.StatsSnapshotRow(9, null, 0, 2_000_000, 0, 0, 0, 0));
        when(repository.baseline(eq(2L), anyLong())).thenReturn(OptionalLong.of(1_000_000));
        // alice's first progress came from RuneSync: 300k counted there, while RuneMetrics sees 1M
        when(repository.progress(2)).thenReturn(List.of(imported(1, ALICE, 300_000, "pxp:" + ALICE + ":1", STARTED.plusSeconds(60))));

        assertEquals(1, tracker.feed(e), "only bob is added from RuneMetrics");
        verify(service).recordProgress(eq(GUILD), eq(2L), anyString(), eq(BOB), eq(1_000_000L), eq(Source.IMPORT), anyString(), isNull(), anyString());
        verify(service, never()).recordProgress(anyLong(), anyLong(), anyString(), eq(ALICE), anyLong(), any(), anyString(), any(), anyString());
        assertEquals(List.of(new PluginXpTracker.Disagreement(ALICE, 300_000, 1_000_000, NOW)), plugin.disagreements(2));
    }
}
