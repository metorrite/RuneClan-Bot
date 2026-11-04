package com.runeclans.bot.runescape.stats;

import com.runeclans.bot.internal.SiteStatsRepository;
import com.runeclans.bot.runeclans.RuneClansHubClient.Answer;
import com.runeclans.bot.runeclans.RuneClansHubClient.Read;
import com.runeclans.bot.runescape.ClanMemberRepository.ClanMemberRow;
import com.runeclans.bot.runescape.PlayerActivity;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** T-851: the bot's stats reads answered from the hub's routes, with a fake hub that counts its calls. */
class HubStatsBackendTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final long GUILD = 1_000_000_000_000_000_001L;

    /** A scripted hub: paths (without their query) to answers, every call counted. */
    static final class FakeHub implements HubStatsBackend.Hub {
        final List<String> calls = new ArrayList<>();
        final Map<String, Function<String, Answer>> routes = new java.util.HashMap<>();

        void on(String pathPrefix, Function<String, Answer> answer) {
            routes.put(pathPrefix, answer);
        }

        private Answer answer(String method, String path, String body) {
            calls.add(method + " " + path + (body == null ? "" : " " + body));
            for (var e : routes.entrySet()) if (path.startsWith(e.getKey())) return e.getValue().apply(body == null ? path : body);
            return new Answer(404, DataObject.empty().put("error", "not_found"));
        }

        @Override
        public Read read(String path, Duration keepFresh, String... tags) {
            Answer a = answer("GET", path, null);
            return new Read(a, false, false);
        }

        @Override
        public Answer call(String method, String path, String body) {
            return answer(method, path, body);
        }

        long count(String contains) {
            return calls.stream().filter(c -> c.contains(contains)).count();
        }
    }

    private static DataObject snapshot(long id, String rsn, Instant at, long totalXp, int totalLevel) {
        return DataObject.empty().put("rsn", rsn).put("snapshotId", id).put("at", at.toString()).put("totalLevel", totalLevel).put("totalXp", totalXp).put("combatLevel", 138)
                .put("questsComplete", 300).put("questsStarted", 1).put("questsNotStarted", 2).put("source", "runemetrics");
    }

    private static DataObject line(long id, String rsn, String text, Instant occurred, Instant recorded) {
        return DataObject.empty().put("id", id).put("rsn", rsn).put("date", "04-Nov-2026 10:00").put("text", text).put("details", "d").put("occurredAt", occurred.toString()).put("recordedAt", recorded.toString());
    }

    private static Answer ok(DataObject json) {
        return new Answer(200, json);
    }

    private static ClanMemberRow member(String rsn) {
        return new ClanMemberRow(1, GUILD, rsn, "Recruit", OffsetDateTime.now(), OffsetDateTime.now(), true, 0, 0, null);
    }

    private static HubStatsBackend backend(FakeHub hub, String... members) {
        return new HubStatsBackend(hub, (g, active) -> List.of(members).stream().map(HubStatsBackendTest::member).toList(), Clock.fixed(NOW, ZoneOffset.UTC), null);
    }

    @Test
    void aStatCommandNeedsAtMostTwoHubCalls() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/players/stats", body -> ok(DataObject.empty().put("players", DataArray.empty().add(snapshot(7, "Zezima", NOW, 5_000_000_000L, 2898)
                .put("skills", DataArray.empty().add(DataObject.empty().put("skillId", 0).put("level", 99).put("xp", 200_000_000L).put("rank", 1))
                        .add(DataObject.empty().put("skillId", 1).put("level", 99).put("xp", 13_034_431L).put("rank", 2))))).put("missing", DataArray.empty())));
        hub.on("/v1/players/Zezima/activity", path -> ok(DataObject.empty().put("rsn", "Zezima").put("activities", DataArray.empty()
                .add(line(1, "Zezima", "Levelled up Attack.", NOW.minusSeconds(60), NOW.minusSeconds(30)))
                .add(line(2, "Zezima", "I killed a dragon.", NOW.minusSeconds(600), NOW.minusSeconds(500)))).put("hidden", false)));
        HubStatsBackend b = backend(hub);

        StatsSnapshotRow latest = b.latest("zezima");
        assertNotNull(latest);
        assertEquals(7, latest.snapshotId());
        assertEquals(2898, latest.totalLevel());
        assertEquals(2, b.skillsForSnapshot(7).size(), "the skills came with the newest snapshot");
        assertEquals(200_000_000L, b.skillsForSnapshot(7).get(0).xp());
        List<PlayerActivity> recent = b.recentActivities("Zezima", 5);
        assertEquals(List.of("Levelled up Attack.", "I killed a dragon."), recent.stream().map(PlayerActivity::text).toList(), "newest first");
        assertEquals(2, hub.calls.size(), "one batch read and one activity read: " + hub.calls);

        assertEquals(latest.snapshotId(), b.latest("Zezima").snapshotId());
        assertEquals(2, hub.calls.size(), "a second look inside the fresh time is the cached batch, not a third call");
        assertTrue(hub.calls.get(0).startsWith("POST /v1/players/stats {\"rsns\":[\"zezima\"]}") || hub.calls.get(0).startsWith("POST /v1/players/stats"), hub.calls.get(0));
    }

    @Test
    void historyAnswersNewestFirstAndSinceOldestFirst() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/players/A/stats/history", path -> ok(DataObject.empty().put("rsn", "A").put("snapshots", DataArray.empty()
                .add(snapshot(3, "A", NOW, 300, 30)).add(snapshot(1, "A", NOW.minus(Duration.ofDays(2)), 100, 10)).add(snapshot(2, "A", NOW.minus(Duration.ofDays(1)), 200, 20)))));
        HubStatsBackend b = backend(hub);
        assertEquals(List.of(3L, 2L, 1L), b.history("A", 10).stream().map(StatsSnapshotRow::snapshotId).toList());
        assertEquals(List.of(1L, 2L, 3L), b.snapshotsSince("A", NOW.minus(Duration.ofDays(3)).atOffset(ZoneOffset.UTC)).stream().map(StatsSnapshotRow::snapshotId).toList());
        assertTrue(hub.calls.get(0).contains("limit=10"), hub.calls.get(0));
        assertTrue(hub.calls.get(1).contains("&since=2026-11-01T12:00:00Z"), hub.calls.get(1));
    }

    @Test
    void aSpaceInANameTravelsAsAnUnderscore() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/players/Lord_Of_Bones/activity", path -> ok(DataObject.empty().put("activities", DataArray.empty())));
        backend(hub).recentActivities("Lord  Of Bones", 3);
        assertEquals(1, hub.count("/v1/players/Lord_Of_Bones/activity?limit=3"), hub.calls.toString());
    }

    @Test
    void clanGainsUseTheClanRouteWhenTheServerHasARegisteredClan() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/guilds/" + GUILD + "/site", path -> ok(DataObject.empty().put("clanId", "11111111-1111-1111-1111-111111111111")));
        hub.on("/v1/clans/11111111-1111-1111-1111-111111111111/stats/gains", body -> ok(DataObject.empty().put("gains", DataArray.empty()
                .add(DataObject.empty().put("rsn", "Bob").put("gained", 500)).add(DataObject.empty().put("rsn", "alice").put("gained", 900))
                .add(DataObject.empty().put("rsn", "Stranger").put("gained", 1)))));
        HubStatsBackend b = backend(hub, "Alice", "Bob");
        List<SiteStatsRepository.Gain> gains = b.xpGainsBetween(GUILD, NOW.minus(Duration.ofDays(7)).atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
        assertEquals(List.of("Alice", "Bob"), gains.stream().map(SiteStatsRepository.Gain::rsn).toList(), "roster order and spelling; names off the bot's roster left out");
        assertEquals(900, gains.get(0).xp());
        assertEquals(1, hub.count("/stats/gains"));
        assertEquals(0, hub.count("/stats/history"), "no per-member reads when the clan route answers");
    }

    @Test
    void clanGainsFallBackToEachMembersHistoryWithoutARegisteredClan() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/guilds/" + GUILD + "/site", path -> new Answer(404, null));
        OffsetDateTime from = NOW.minus(Duration.ofDays(7)).atOffset(ZoneOffset.UTC), to = NOW.atOffset(ZoneOffset.UTC);
        hub.on("/v1/players/Alice/stats/history", path -> ok(DataObject.empty().put("snapshots", DataArray.empty()
                .add(snapshot(1, "Alice", from.toInstant().minusSeconds(3600), 1000, 10)).add(snapshot(2, "Alice", NOW.minusSeconds(60), 1600, 12)))));
        hub.on("/v1/players/Bob/stats/history", path -> ok(DataObject.empty().put("snapshots", DataArray.empty()
                .add(snapshot(3, "Bob", from.toInstant().plusSeconds(3600), 50, 10)).add(snapshot(4, "Bob", NOW.minusSeconds(60), 70, 12)))));
        hub.on("/v1/players/Carol/stats/history", path -> ok(DataObject.empty().put("snapshots", DataArray.empty())));
        HubStatsBackend b = backend(hub, "Alice", "Bob", "Carol");
        List<SiteStatsRepository.Gain> gains = b.xpGainsBetween(GUILD, from, to);
        assertEquals(2, gains.size(), "Carol has no snapshot");
        assertEquals(600, gains.get(0).xp(), "Alice: newest minus the snapshot at or before the start");
        assertEquals(20, gains.get(1).xp(), "Bob: newest minus the first snapshot after the start");
        assertTrue(hub.calls.stream().anyMatch(c -> c.contains("/v1/players/Alice/stats/history?limit=1000&since=" + from.toInstant().minus(HubStatsBackend.BEFORE_WINDOW))), hub.calls.toString());
        assertEquals(Optional.empty(), b.clanIdOf(GUILD));
    }

    @Test
    void citadelViewsComeFromTheMembersOwnLines() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/guilds/" + GUILD + "/site", path -> new Answer(404, null));
        Instant wed = Instant.parse("2026-10-28T09:00:00Z"); // a Wednesday after the 00:10 reset
        Instant tue = Instant.parse("2026-10-27T23:00:00Z"); // the week before
        hub.on("/v1/players/Alice/activity", path -> ok(DataObject.empty().put("activities", DataArray.empty()
                .add(line(1, "Alice", "Capped at my Clan Citadel.", wed, wed.plusSeconds(5)))
                .add(line(2, "Alice", "Capped at my Clan Citadel.", tue, tue.plusSeconds(5)))
                .add(line(3, "Alice", "Visited my Clan Citadel.", wed.plusSeconds(100), wed.plusSeconds(105))))));
        hub.on("/v1/players/Bob/activity", path -> ok(DataObject.empty().put("activities", DataArray.empty()
                .add(line(4, "Bob", "Visited my Clan Citadel.", wed, wed.plusSeconds(5))))));
        HubStatsBackend b = backend(hub, "Alice", "Bob");

        List<SiteStatsRepository.Capper> top = b.topCappers(GUILD, 10);
        assertEquals(1, top.size());
        assertEquals(new SiteStatsRepository.Capper("Alice", 2, 2), top.get(0));

        List<SiteStatsRepository.CapWeek> grid = b.citadelGrid(GUILD, 4);
        assertEquals(3, grid.size(), grid.toString());
        assertTrue(grid.contains(new SiteStatsRepository.CapWeek("Alice", LocalDate.of(2026, 10, 28), true)));
        assertTrue(grid.contains(new SiteStatsRepository.CapWeek("Alice", LocalDate.of(2026, 10, 21), true)));
        assertTrue(grid.contains(new SiteStatsRepository.CapWeek("Bob", LocalDate.of(2026, 10, 28), false)));

        List<SiteStatsRepository.WeekCount> byWeek = b.citadelByWeek(GUILD, 4);
        assertEquals(List.of(new SiteStatsRepository.WeekCount(LocalDate.of(2026, 10, 21), 1, 1), new SiteStatsRepository.WeekCount(LocalDate.of(2026, 10, 28), 1, 2)), byWeek);

        SiteStatsRepository.CitadelTotals totals = b.citadelFor(GUILD, "Alice");
        assertEquals(2, totals.caps());
        assertEquals(1, totals.visits());
        assertEquals(List.of(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 28)), totals.cappedWeeks());

        assertEquals(1, b.cappersBetween(GUILD, wed.minusSeconds(3600).atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), 5).size());
        assertEquals(new SiteStatsRepository.Capper("Alice", 1, 1), b.cappersBetween(GUILD, wed.minusSeconds(3600).atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), 5).get(0));
    }

    @Test
    void recordsAndLeadersComeFromHistoryAndTheNewestSkills() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/guilds/" + GUILD + "/site", path -> new Answer(404, null));
        hub.on("/v1/players/Alice/stats/history", path -> ok(DataObject.empty().put("snapshots", DataArray.empty()
                .add(snapshot(1, "Alice", NOW.minus(Duration.ofDays(2)), 1000, 10)).add(snapshot(2, "Alice", NOW.minus(Duration.ofDays(1)), 1900, 12)).add(snapshot(3, "Alice", NOW, 2000, 12)))));
        hub.on("/v1/players/stats", body -> ok(DataObject.empty().put("players", DataArray.empty().add(snapshot(3, "Alice", NOW, 2000, 12)
                .put("skills", DataArray.empty().add(DataObject.empty().put("skillId", 0).put("level", 99).put("xp", 200_000_000L).put("rank", 1))
                        .add(DataObject.empty().put("skillId", 1).put("level", 99).put("xp", 200_000_000L).put("rank", 2)))))));
        HubStatsBackend b = backend(hub, "Alice");
        List<SiteStatsRepository.BigDay> days = b.biggestDays(GUILD, 5);
        assertEquals(List.of(new SiteStatsRepository.BigDay("Alice", NOW.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1), 900),
                new SiteStatsRepository.BigDay("Alice", NOW.atOffset(ZoneOffset.UTC).toLocalDate(), 100)), days);
        assertEquals(List.of(new SiteStatsRepository.ClubMember("Alice", 2)), b.twoHundredMillionClub(GUILD, 5));
        assertEquals(2, b.skillLeaders(GUILD, 3).size());
        assertEquals(Map.of("alice", 2000L).get("alice"), b.membersLatest(GUILD).get("alice").totalXp());
        assertEquals(List.of(new SiteStatsRepository.DayXp(NOW.atOffset(ZoneOffset.UTC).toLocalDate(), 100)),
                b.dailyXpBetween(GUILD, null, NOW.minus(Duration.ofHours(6)).atOffset(ZoneOffset.UTC), NOW.plusSeconds(1).atOffset(ZoneOffset.UTC)));
    }

    @Test
    void aHubThatCannotAnswerGivesEmptyResultsNotErrors() {
        HubStatsBackend b = new HubStatsBackend(new HubStatsBackend.Hub() {
            @Override
            public Read read(String path, Duration keepFresh, String... tags) {
                return null;
            }

            @Override
            public Answer call(String method, String path, String body) {
                return null;
            }
        }, (g, active) -> List.of(member("Alice")), Clock.fixed(NOW, ZoneOffset.UTC), null);
        assertNull(b.latest("Alice"), "one player: nothing known, as an empty table would say");
        assertTrue(b.history("Alice", 5).isEmpty());
        assertTrue(b.recentActivities("Alice", 5).isEmpty());
        assertTrue(b.citadelFor(GUILD, "Alice").cappedWeeks().isEmpty());
        // a clan's view can't be answered: it says so instead of coming up empty or short (the internal API answers 503 "try again")
        OffsetDateTime from = NOW.minusSeconds(100).atOffset(ZoneOffset.UTC);
        OffsetDateTime to = NOW.atOffset(ZoneOffset.UTC);
        assertThrows(StatsUnavailableException.class, () -> b.xpGainsBetween(GUILD, from, to));
        assertThrows(StatsUnavailableException.class, () -> b.firstSnapshotAt(GUILD));
        assertThrows(StatsUnavailableException.class, () -> b.citadelGrid(GUILD, 2));
        assertThrows(StatsUnavailableException.class, () -> b.lastActivity(GUILD));
        assertThrows(StatsUnavailableException.class, () -> b.membersLatest(GUILD));
    }

    @Test
    void aPartialHubFailureIsUnavailableNotAShorterLeaderboard() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/guilds/" + GUILD + "/site", path -> new Answer(404, null));
        String[] names = new String[10];
        for (int i = 0; i < 10; i++) {
            names[i] = "M" + i;
            final int n = i;
            hub.on("/v1/players/M" + i + "/stats/history", path -> n % 2 == 0
                    ? ok(DataObject.empty().put("snapshots", DataArray.empty().add(snapshot(n, "M" + n, NOW.minusSeconds(50), 1_000 + n, 100)).add(snapshot(100 + n, "M" + n, NOW.minusSeconds(1000), 500, 90))))
                    : new Answer(429, DataObject.empty().put("error", "busy"), 5));
        }
        HubStatsBackend b = backend(hub, names);
        OffsetDateTime from = NOW.minusSeconds(100).atOffset(ZoneOffset.UTC);
        OffsetDateTime to = NOW.atOffset(ZoneOffset.UTC);
        assertThrows(StatsUnavailableException.class, () -> b.xpGainsBetween(GUILD, from, to), "half the members answered 429: no five-member leaderboard");

        FakeHub fine = new FakeHub();
        fine.on("/v1/guilds/" + GUILD + "/site", path -> new Answer(404, null));
        for (int i = 0; i < 10; i++) {
            final int n = i;
            fine.on("/v1/players/M" + i + "/stats/history", path -> n % 2 == 0
                    ? ok(DataObject.empty().put("snapshots", DataArray.empty().add(snapshot(n, "M" + n, NOW.minusSeconds(50), 1_000 + n, 100)).add(snapshot(100 + n, "M" + n, NOW.minusSeconds(1000), 500, 90))))
                    : new Answer(404, DataObject.empty().put("error", "not_found")));
        }
        assertEquals(5, backend(fine, names).xpGainsBetween(GUILD, from, to).size(), "a member the hub has never seen is simply not on the board");
    }

    @Test
    void aFanOutStopsAtItsBudgetAndCancelsWhatIsLeft() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();
            HubStatsBackend.Hub slow = new HubStatsBackend.Hub() {
                @Override
                public Read read(String path, Duration keepFresh, String... tags) {
                    if (path.contains("/site")) return new Read(new Answer(404, null), false, false);
                    started.incrementAndGet();
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    return new Read(ok(DataObject.empty().put("activities", DataArray.empty())), false, false);
                }

                @Override
                public Answer call(String method, String path, String body) {
                    return null;
                }
            };
            String[] names = new String[12];
            for (int i = 0; i < 12; i++) names[i] = "M" + i;
            HubStatsBackend b = new HubStatsBackend(slow, (g, a) -> List.of(names).stream().map(HubStatsBackendTest::member).toList(), Clock.fixed(NOW, ZoneOffset.UTC), pool)
                    .withBudget(Duration.ofMillis(300));
            long begin = System.nanoTime();
            assertThrows(StatsUnavailableException.class, () -> b.lastActivity(GUILD));
            long tookMs = (System.nanoTime() - begin) / 1_000_000;
            assertTrue(tookMs < 2_000, "12 reads of 400 ms on 2 threads would take 2.4 s; the budget cut it at " + tookMs + " ms");
            Thread.sleep(500);
            assertTrue(started.get() <= 4, "the queued reads were cancelled, not run after the view gave up: " + started.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cappersComeFromTheClanRouteInOneReadWhenThereIsARegisteredClan() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/guilds/" + GUILD + "/site", path -> ok(DataObject.empty().put("clanId", "c1")));
        hub.on("/v1/clans/c1/stats/cappers", path -> ok(DataObject.empty().put("cappers", DataArray.empty()
                .add(DataObject.empty().put("rsn", "alice").put("caps", 3).put("lastAt", NOW.toString()))
                .add(DataObject.empty().put("rsn", "Bob").put("caps", 1).put("lastAt", NOW.toString()))
                .add(DataObject.empty().put("rsn", "Stranger").put("caps", 9).put("lastAt", NOW.toString())))));
        HubStatsBackend b = backend(hub, "Alice", "Bob", "Carol");
        List<SiteStatsRepository.Capper> cappers = b.cappersBetween(GUILD, NOW.minusSeconds(7 * 86_400).atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), 10);
        assertEquals(List.of("Alice", "Bob"), cappers.stream().map(SiteStatsRepository.Capper::rsn).toList(), "the bot's roster and spelling; a hub-only name is left out");
        assertEquals(3, cappers.get(0).totalCaps());
        assertEquals(3, cappers.get(0).weeksCapped());
        assertEquals(2, b.topCappers(GUILD, 10).size());
        assertEquals(0, hub.count("/activity"), "no per-member activity reads: " + hub.calls);
        assertEquals(2, hub.count("/stats/cappers"), "one clan read per view");
    }

    @Test
    void skillGainsAreReadOncePerMemberAndWindow() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/guilds/" + GUILD + "/site", path -> ok(DataObject.empty().put("clanId", "c1")));
        hub.on("/v1/clans/c1/stats/skill-gains", body -> ok(DataObject.empty().put("skills", DataArray.empty().add(DataObject.empty().put("skillId", 3).put("gained", 1000)))));
        HubStatsBackend b = backend(hub, "Alice", "Bob");
        OffsetDateTime from = NOW.minusSeconds(86_400).atOffset(ZoneOffset.UTC);
        OffsetDateTime to = NOW.atOffset(ZoneOffset.UTC);
        assertEquals(2000, b.skillGainsBetween(GUILD, null, from, to).get(0).xp());
        assertEquals(2, hub.count("skill-gains"));
        assertEquals(2000, b.skillGainsBetween(GUILD, null, from, to).get(0).xp());
        assertEquals(1000, b.skillGainsBetween(GUILD, "Alice", from, to).get(0).xp());
        assertEquals(2, hub.count("skill-gains"), "a second view inside five minutes reads nothing: " + hub.calls);
        b.evictPlayer("Alice");
        b.skillGainsBetween(GUILD, "Alice", from, to);
        assertEquals(3, hub.count("skill-gains"), "the hub's stats.updated for Alice drops her cached gains");
    }

    @Test
    void aClanOfFiveHundredIsAnsweredWhole() {
        FakeHub hub = new FakeHub();
        hub.on("/v1/players/stats", body -> {
            DataArray rsns = DataObject.fromJson(body).getArray("rsns");
            DataArray players = DataArray.empty();
            for (int i = 0; i < rsns.length(); i++) players.add(snapshot(i, rsns.getString(i), NOW, 1_000, 100 + i));
            return ok(DataObject.empty().put("players", players).put("missing", DataArray.empty()));
        });
        String[] names = new String[500];
        for (int i = 0; i < 500; i++) names[i] = "Member" + i;
        Map<String, StatsSnapshotRow> latest = backend(hub, names).membersLatest(GUILD);
        assertEquals(500, latest.size(), "RS3 clans go to 500: nobody is cut from the hub's views");
        assertEquals(5, hub.count("/v1/players/stats"), "batches of 100");
    }
}
