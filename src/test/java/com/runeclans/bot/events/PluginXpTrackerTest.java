package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRepository.Progress;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Competitions counted from RuneSync (T-537): one hub call per event per pass, progress = the hub's gain minus what is already counted. */
class PluginXpTrackerTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ALICE = Long.parseLong("1".repeat(18));
    private static final long BOB = Long.parseLong("2".repeat(18));
    private static final long CAROL = Long.parseLong("3".repeat(18));
    private static final Instant STARTED = Instant.parse("2026-10-20T18:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-20T19:00:30Z");

    private final EventRepository repository = Mockito.mock(EventRepository.class);
    private final EventService service = Mockito.mock(EventService.class);
    private final RuneClansHubClient hub = Mockito.mock(RuneClansHubClient.class);
    private final PluginXpTracker tracker = new PluginXpTracker(repository, service, hub).withClock(Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void offAgain() {
        CompetitionTemplate.offerPluginSource(false);
    }

    private static Event event(long id, String config) {
        return new Event(id, GUILD, CompetitionTemplate.KEY, "Comp", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, STARTED.minusSeconds(3600), NOW.plusSeconds(86_400), 0, config, 1,
                STARTED.minusSeconds(7200), null, false);
    }

    private static Participant player(long userId, Instant joined) {
        return new Participant(1, userId, GUILD, null, null, false, null, joined, null, null, null, null, null, null);
    }

    private static Progress imported(long userId, long amount) {
        return new Progress(1, 1, CompetitionTemplate.OBJECTIVE_KEY, userId, null, amount, Source.IMPORT, "pxp:x", null, null, STARTED);
    }

    private static RuneClansHubClient.Answer gains(String... aliasAndXp) {
        DataArray players = DataArray.empty();
        for (int i = 0; i < aliasAndXp.length; i += 2) players.add(DataObject.empty().put("alias", aliasAndXp[i]).put("xp", Long.parseLong(aliasAndXp[i + 1])));
        return new RuneClansHubClient.Answer(200, DataObject.empty().put("players", players).put("notCounted", DataArray.empty()));
    }

    @Test
    void addsTheHubsGainMinusWhatIsCountedAndListsWhoTheHubCantCount() {
        Event e = event(1, "{\"skillId\":9,\"xpSource\":\"plugin\"}");
        when(repository.startedAt(1)).thenReturn(Optional.of(STARTED));
        when(repository.participants(1)).thenReturn(List.of(player(ALICE, STARTED.minusSeconds(60)), player(BOB, STARTED.minusSeconds(60)), player(CAROL, STARTED.minusSeconds(60))));
        when(repository.progress(1)).thenReturn(List.of(imported(ALICE, 1_000)));
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasOf(BOB)).thenReturn(Optional.of("a1_bob"));
        when(hub.aliasOf(CAROL)).thenReturn(Optional.empty()); // no RuneSync identity grant
        when(hub.xpGains(eq(GUILD), any(), eq(9), eq(STARTED), eq(NOW))).thenReturn(gains("a1_alice", "4500")); // bob shares no XP

        assertEquals(1, tracker.feed(e));
        verify(service).recordProgress(eq(GUILD), eq(1L), eq(CompetitionTemplate.OBJECTIVE_KEY), eq(ALICE), eq(3_500L), eq(Source.IMPORT), eq(CompetitionTracking.pluginSourceKey(ALICE, NOW)),
                isNull(), anyString());
        verify(hub, times(1)).xpGains(anyLong(), any(), any(), any(), any());
        assertEquals(Set.of(BOB, CAROL), tracker.notCounted(1).orElseThrow().userIds());

        // the next pass asks the hub for aliases again only after they have been kept a while
        tracker.feed(e);
        verify(hub, times(1)).aliasOf(CAROL);
    }

    @Test
    void aGainThisMinutesKeyAlreadyCountedWaitsForTheNextMinuteAndThePassIsntComplete() {
        Event e = event(1, "{\"skillId\":9,\"xpSource\":\"plugin\"}");
        when(repository.startedAt(1)).thenReturn(Optional.of(STARTED));
        when(repository.participants(1)).thenReturn(List.of(player(ALICE, STARTED.minusSeconds(60))));
        // the minute sweep already counted 1,000 this minute; a new ledger row has come in since
        when(repository.progress(1)).thenReturn(List.of(new Progress(1, 1, CompetitionTemplate.OBJECTIVE_KEY, ALICE, null, 1_000, Source.IMPORT,
                CompetitionTracking.pluginSourceKey(ALICE, NOW), null, null, NOW)));
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.xpGains(eq(GUILD), any(), eq(9), eq(STARTED), eq(NOW))).thenReturn(gains("a1_alice", "1500"));

        PluginXpTracker.Pass pass = tracker.pass(e);
        assertFalse(pass.complete(), "a closing pass must try again rather than close without the gain");
        verify(service, never()).recordProgress(anyLong(), anyLong(), any(), anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void aLateJoinerCountsFromWhenTheyJoinedInTheirOwnCall() {
        Event e = event(2, "{\"xpSource\":\"plugin\"}");
        Instant joined = STARTED.plusSeconds(1800);
        when(repository.startedAt(2)).thenReturn(Optional.of(STARTED));
        when(repository.participants(2)).thenReturn(List.of(player(ALICE, STARTED.minusSeconds(60)), player(BOB, joined)));
        when(repository.progress(2)).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasOf(BOB)).thenReturn(Optional.of("a1_bob"));
        when(hub.xpGains(eq(GUILD), any(), isNull(), eq(STARTED), eq(NOW))).thenReturn(gains("a1_alice", "10"));
        when(hub.xpGains(eq(GUILD), any(), isNull(), eq(joined), eq(NOW))).thenReturn(gains("a1_bob", "20"));

        assertEquals(2, tracker.feed(e));
        verify(service).recordProgress(eq(GUILD), eq(2L), anyString(), eq(BOB), eq(20L), eq(Source.IMPORT), anyString(), isNull(), anyString());
    }

    @Test
    void theHubBeingDownOrOffAddsNothingAndSaysNothingAboutWhoCounts() {
        Event e = event(3, "{\"xpSource\":\"plugin\"}");
        when(repository.startedAt(3)).thenReturn(Optional.empty()); // started before T-537: from the planned start
        when(repository.participants(3)).thenReturn(List.of(player(ALICE, STARTED.minusSeconds(7200))));
        when(repository.progress(3)).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.xpGains(anyLong(), any(), any(), any(), any())).thenReturn(new RuneClansHubClient.Answer(404, null));

        assertEquals(0, tracker.feed(e));
        verify(service, never()).recordProgress(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), any(), anyString(), any(), anyString());
        assertTrue(tracker.notCounted(3).isEmpty());
        verify(hub).xpGains(eq(GUILD), any(), isNull(), eq(STARTED.minusSeconds(3600)), eq(NOW));
    }

    @Test
    void theSweepFeedsOnlyCompetitionsCountedFromThePlugin() {
        when(repository.runningEvents(CompetitionTemplate.KEY)).thenReturn(List.of(event(4, "{}"), event(5, "{\"xpSource\":\"runemetrics\"}")));
        when(repository.runningEvents(ClanVsClan.KEY)).thenReturn(List.of());
        tracker.sweep();
        verify(repository, never()).participants(anyLong());
        verify(hub, never()).xpGains(anyLong(), any(Collection.class), any(), any(), any());
    }

    @Test
    void withConfirmedLinksOnlyAnUnconfirmedPlayerScoresNothingAndIsListedApart() {
        Event e = event(6, "{\"xpSource\":\"plugin\",\"confirmedLinksOnly\":true}");
        when(repository.startedAt(6)).thenReturn(Optional.of(STARTED));
        when(repository.participants(6)).thenReturn(List.of(player(ALICE, STARTED.minusSeconds(60)), player(BOB, STARTED.minusSeconds(60))));
        when(repository.progress(6)).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasOf(BOB)).thenReturn(Optional.of("a1_bob"));
        DataArray players = DataArray.empty();
        players.add(DataObject.empty().put("alias", "a1_alice").put("xp", 500L).put("verification", "public_data_corroborated"));
        players.add(DataObject.empty().put("alias", "a1_bob").put("xp", 900L).put("verification", "implicit"));
        when(hub.xpGains(eq(GUILD), any(), isNull(), eq(STARTED), eq(NOW))).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("players", players)));

        assertEquals(1, tracker.feed(e));
        verify(service).recordProgress(eq(GUILD), eq(6L), anyString(), eq(ALICE), eq(500L), eq(Source.IMPORT), anyString(), isNull(), anyString());
        verify(service, never()).recordProgress(anyLong(), anyLong(), anyString(), eq(BOB), anyLong(), any(), anyString(), any(), anyString());
        assertEquals(Set.of(BOB), tracker.notCounted(6).orElseThrow().unconfirmed());
        assertEquals(Set.of(), tracker.notCounted(6).orElseThrow().userIds());

        // off (the default) and on a RuneMetrics competition it changes nothing
        assertFalse(CompetitionTemplate.confirmedLinksOnly(DataObject.fromJson("{\"xpSource\":\"plugin\"}")));
        assertFalse(CompetitionTemplate.confirmedLinksOnly(DataObject.fromJson("{\"confirmedLinksOnly\":true}")));
        CompetitionTemplate.offerPluginSource(true);
        CompetitionTemplate template = new CompetitionTemplate();
        assertFalse(template.options().stream().anyMatch(o -> o.path().equals("config.confirmedLinksOnly")),
                "T-815: not offered while links are taken on trust (nobody would score)");
        assertTrue(template.fixedSettings().contains("confirmedLinksOnly"), "an event that already has it keeps it fixed");
    }

    @Test
    void theOptionIsOfferedOnlyWithTheHubAndTheSourceIsFixed() {
        CompetitionTemplate template = new CompetitionTemplate();
        assertFalse(template.options().stream().anyMatch(o -> o.path().equals("config.xpSource")), "hidden with the hub off");
        CompetitionTemplate.offerPluginSource(true);
        var option = template.options().stream().filter(o -> o.path().equals("config.xpSource")).findFirst().orElseThrow();
        assertEquals(CompetitionTemplate.SOURCE_RUNEMETRICS, option.standard());
        assertTrue(template.fixedSettings().contains("xpSource"));
        assertFalse(template.configProblems(DataObject.fromJson("{\"xpSource\":\"wom\"}")).isEmpty());
        assertTrue(template.configProblems(DataObject.fromJson("{\"xpSource\":\"plugin\"}")).isEmpty());
        assertEquals(CompetitionTemplate.SOURCE_RUNEMETRICS, CompetitionTemplate.xpSourceOf(DataObject.empty()), "every older event stays on RuneMetrics");
    }

    @Test
    void theServersDefaultIsLaidOverNewCompetitionsOnlyWhileOffered() {
        EventDefaults plugin = EventDefaults.NONE.withXpSource(CompetitionTemplate.SOURCE_PLUGIN);
        CompetitionTemplate template = new CompetitionTemplate();
        assertEquals(CompetitionTemplate.SOURCE_RUNEMETRICS, CompetitionTemplate.xpSourceOf(EventOptions.standard(template, NOW, plugin).getObject("config")));
        CompetitionTemplate.offerPluginSource(true);
        assertEquals(CompetitionTemplate.SOURCE_PLUGIN, CompetitionTemplate.xpSourceOf(EventOptions.standard(template, NOW, plugin).getObject("config")));
        assertEquals(CompetitionTemplate.SOURCE_RUNEMETRICS, CompetitionTemplate.xpSourceOf(EventOptions.standard(template, NOW, EventDefaults.NONE).getObject("config")));
        assertFalse(EventOptions.standard(new BingoTemplate(), NOW, plugin).getObject("config").hasKey("xpSource"));
        assertEquals(null, EventDefaults.NONE.withXpSource("nonsense").xpSource());
    }

    @Test
    void countingStartsAtTheLaterOfTheStartAndTheJoin() {
        assertEquals(STARTED, CompetitionTracking.countsFrom(STARTED, STARTED.minusSeconds(5)));
        assertEquals(STARTED.plusSeconds(5), CompetitionTracking.countsFrom(STARTED, STARTED.plusSeconds(5)));
        assertEquals(STARTED, CompetitionTracking.countsFrom(STARTED, null));
    }
}
