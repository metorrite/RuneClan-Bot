package com.runeclans.bot.leagues;

import com.runeclans.bot.events.ClanVsClan;
import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRoster;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.events.PrizeRules.BuyInStatus;
import com.runeclans.bot.events.TeamRules.Kind;
import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The reporter's gates (T-759 review): a result goes to the hub only for a clan-vs-clan event attached to a round this server's clan hosts
 * that is not over, only when the event is DONE; a cancelled event is detached; nothing else writes. Every write empties the cached league
 * answers.
 */
class LeagueReporterTest {
    // fake ids, made at runtime
    private static final long HOST = Long.parseLong("2".repeat(18));
    private static final long GUEST = Long.parseLong("3".repeat(18));
    private static final String LEAGUE = "0dc0dc00-0000-4000-8000-00000000000b";

    private final Map<String, RuneClansHubClient.Read> answers = new HashMap<>();
    private final List<String> written = new ArrayList<>();
    private int forgotten;
    private HubEvents events;
    private LeagueReporter reporter;
    private LeagueHub hub;

    private static Event event(long id, String template, Status status) {
        String config = DataObject.empty().put(ClanVsClan.TEAMS, DataObject.empty().put(Long.toString(HOST), 10L).put(Long.toString(GUEST), 20L)).toString();
        return new Event(id, HOST, template, "Boss night", "", status, Scope.PUBLIC, ScoringMode.TEAM, Instant.EPOCH, Instant.EPOCH, 0, config, 1, Instant.EPOCH, null, false);
    }

    private static Participant player(long userId, Long teamId) {
        return new Participant(1, userId, HOST, teamId, Kind.HOME_CLAN, false, BuyInStatus.NONE, Instant.EPOCH, EventRoster.Verification.NOT_REQUIRED, null, null, null, null, null);
    }

    private static DataObject round(int number, Long eventId, String status, boolean host) {
        DataObject r = DataObject.empty().put("number", number).put("kind", "event").put("status", status).put("isHost", host).put("needsBot", host)
                .put("config", DataObject.empty().put("metric", "placing")).put("resultSource", null);
        if (eventId != null) r.put("eventRef", DataObject.empty().put("eventId", Long.toString(eventId)).put("guildId", Long.toString(HOST)));
        return r;
    }

    private void hubSays(DataObject... rounds) {
        DataArray arr = DataArray.empty();
        for (DataObject r : rounds) arr.add(r);
        DataObject json = DataObject.empty().put("guildId", Long.toString(HOST)).put("clanId", "c1").put("leagues",
                DataArray.empty().add(DataObject.empty().put("id", LEAGUE).put("name", "Winter cup").put("rounds", arr)));
        answers.put("/v1/guilds/" + HOST + "/leagues", new RuneClansHubClient.Read(new RuneClansHubClient.Answer(200, json), false, false));
    }

    @BeforeEach
    void setUp() {
        Function<String, RuneClansHubClient.Read> reader = answers::get;
        hub = LeagueHub.on(reader, (method, path, body) -> {
            written.add(method + " " + path + " " + body);
            return new HubWriteQueue.Result(HubWriteQueue.Outcome.SENT, new RuneClansHubClient.Answer(200, DataObject.empty().put("number", 2)));
        }, () -> forgotten++);
        for (long g : new long[] {HOST, GUEST}) {
            answers.put("/v1/guilds/" + g + "/site", new RuneClansHubClient.Read(new RuneClansHubClient.Answer(200, DataObject.empty().put("clanId", g == HOST ? "c1" : "c2")), false, false));
        }
        events = mock(HubEvents.class);
        when(events.participants(77)).thenReturn(List.of(player(1, 10L), player(2, 10L), player(4, 20L)));
        when(events.standings(HOST, 77)).thenReturn(List.of(new Standing("t:10", 340, 3, Map.of(), null), new Standing("t:20", 120, 1, Map.of(), null)));
        reporter = new LeagueReporter(events, hub, new EventChanges());
    }

    @Test
    void aReportNeedsAnAttachedRoundThisClanHostsThatIsNotOver() {
        Event done = event(77, ClanVsClan.KEY, Status.DONE);
        hubSays(round(2, 77L, "running", true));
        LeagueReporter.Outcome ok = reporter.report(done);
        assertTrue(ok.ok(), ok.message());
        assertEquals(1, written.size());
        assertTrue(written.get(0).startsWith("POST /v1/leagues/" + LEAGUE + "/rounds/2/results "), written.get(0));
        assertTrue(written.get(0).contains("\"clanId\":\"c1\"") && written.get(0).contains("\"raw\":340"), written.get(0));
        assertTrue(written.get(0).contains("\"clanId\":\"c2\"") && written.get(0).contains("\"raw\":120"), written.get(0));
        assertEquals(1, forgotten, "the cached league answers go after the write");

        hubSays(round(2, 77L, "running", false));
        assertEquals(403, reporter.report(done).status(), "not the host");
        hubSays(round(2, 77L, "final", true));
        assertEquals(409, reporter.report(done).status(), "the round is over");
        hubSays(round(2, 78L, "running", true));
        assertEquals(404, reporter.report(done).status(), "attached nowhere");
        assertEquals(400, reporter.report(event(77, "bingo", Status.DONE)).status(), "not clan-vs-clan");
        answers.remove("/v1/guilds/" + GUEST + "/site");
        hubSays(round(2, 77L, "running", true));
        assertEquals(409, reporter.report(done).status(), "a clan without a hub clan: nothing goes out");
        assertEquals(1, written.size(), "no refusal reached the hub");
        assertEquals(1, forgotten);
        assertEquals(503, new LeagueReporter(events, LeagueHub.off(), new EventChanges()).report(done).status());
    }

    @Test
    void onlyADoneEventIsReportedAndACancelledOneIsDetached() {
        hubSays(round(2, 77L, "running", true));
        when(events.eventById(77)).thenReturn(Optional.of(event(77, ClanVsClan.KEY, Status.RUNNING)));
        reporter.onStage(HOST, 77);
        assertTrue(written.isEmpty(), "a running event has no result to report");
        when(events.eventById(77)).thenReturn(Optional.of(event(77, ClanVsClan.KEY, Status.SCORING)));
        reporter.onStage(HOST, 77);
        assertTrue(written.isEmpty(), "nor a scoring one");
        when(events.eventById(77)).thenReturn(Optional.of(event(77, ClanVsClan.KEY, Status.DONE)));
        reporter.onStage(HOST, 77);
        assertEquals(1, written.size());
        assertTrue(written.get(0).contains("/rounds/2/results "), written.get(0));
        when(events.eventById(77)).thenReturn(Optional.of(event(77, ClanVsClan.KEY, Status.CANCELLED)));
        reporter.onStage(HOST, 77);
        assertEquals(2, written.size());
        assertTrue(written.get(1).contains("/rounds/2/event ") && written.get(1).contains("\"eventId\":null"), "a cancelled event is detached: " + written.get(1));
        assertEquals(2, forgotten);
        when(events.eventById(78)).thenReturn(Optional.of(event(78, "bingo", Status.DONE)));
        reporter.onStage(HOST, 78);
        when(events.eventById(79)).thenReturn(Optional.empty());
        reporter.onStage(HOST, 79);
        assertEquals(2, written.size(), "a bingo event and a missing one write nothing");
        hubSays(round(2, 77L, "final", true));
        when(events.eventById(77)).thenReturn(Optional.of(event(77, ClanVsClan.KEY, Status.CANCELLED)));
        reporter.onStage(HOST, 77);
        assertEquals(2, written.size(), "a final round keeps its event");
        assertFalse(reporter.detach(event(77, ClanVsClan.KEY, Status.CANCELLED)).ok());
    }
}
