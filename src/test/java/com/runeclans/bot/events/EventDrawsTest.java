package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.PrizeRules.AwardStatus;
import com.runeclans.bot.events.PrizeRules.BuyInStatus;
import com.runeclans.bot.events.PrizeRules.PrizeKind;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prize draws and the other ways to win (T-649) over the local Postgres: the bot draws at the end time, posts the result through the outbox with the seed,
 * records the prize as a pending award; staff's Draw now and Reroll; first to a line; and a points event that behaves exactly as before. Skipped where
 * there is no local database. Everything is made in a made-up server and removed afterwards.
 */
class EventDrawsTest {
    private static final long G = 649_649L;
    private static final long CHANNEL = Long.parseLong("3".repeat(17));
    private static ConnectionSupplier supplier;

    private EventRepository repository;
    private EventService service;
    private EventDrawRepository drawRepository;
    private EventDraws draws;
    private Guild guild;
    private final List<DiscordJob> queued = new ArrayList<>();
    private final AtomicLong seeds = new AtomicLong(1);

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        AutoPostRouter.reset();
        repository = new EventRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_CLAN;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds,
                new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        drawRepository = new EventDrawRepository(supplier, repository);
        EventUpdateRepository posts = new EventUpdateRepository(supplier);
        EventBoardUpdates updates = Mockito.mock(EventBoardUpdates.class);
        Mockito.when(updates.channelFor(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(CHANNEL);
        DiscordOutbox outbox = Mockito.mock(DiscordOutbox.class);
        Mockito.when(outbox.enqueue(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.any(DataObject.class)))
                .thenAnswer(inv -> {
                    DiscordJob job = new DiscordJob(queued.size() + 1, inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4),
                            DiscordJob.State.QUEUED, 0, 0, null, null, null, Instant.now(), Instant.now());
                    queued.add(job);
                    return job;
                });
        draws = new EventDraws(repository, service, drawRepository, posts, updates, outbox);
        draws.seeds = seeds::getAndIncrement;

        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Member member = Mockito.mock(Member.class);
        Mockito.when(member.getEffectiveName()).thenReturn("Seren");
        Mockito.when(guild.getMemberById(ArgumentMatchers.anyLong())).thenReturn(member);
        JDA jda = Mockito.mock(JDA.class);
        Mockito.when(jda.getGuildById(G)).thenReturn(guild);
        Field field = EventDraws.class.getDeclaredField("jda");
        field.setAccessible(true);
        field.set(draws, jda);
    }

    @AfterEach
    void cleanUp() throws Exception {
        AutoPostRouter.reset();
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private static ObjectiveSpec tile(String key) {
        return new ObjectiveSpec(key, "Tile " + key, "", 10, Evidence.EITHER, 1, 1, List.of());
    }

    /** A running event with tiles a-d (two rows), players 501-503 and a prize for 1st. {@code win} is the win rules, or null for none. */
    private long event(String win, Instant endsAt) {
        String config = "{\"bonuses\":[{\"name\":\"Row 1\",\"keys\":[\"a\",\"b\"],\"points\":50},{\"name\":\"Row 2\",\"keys\":[\"c\",\"d\"],\"points\":50}]"
                + (win == null ? "" : ",\"win\":" + win) + "}";
        long id = repository.createEvent(G, "custom", "Advent bingo", "", Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, endsAt, 0, config, 1L);
        repository.replaceObjectives(id, List.of(tile("a"), tile("b"), tile("c"), tile("d")));
        for (long user = 501; user <= 503; user++) repository.join(id, user, G, null, TeamRules.Kind.HOME_CLAN, BuyInStatus.NONE, 0);
        repository.replacePrizes(id, List.of(new PrizePlan.Prize(0, 1, 1, PrizeKind.IN_GAME, "Bonds", 1, 0, PrizePlan.Split.EQUAL)));
        assertTrue(repository.moveStage(G, id, Status.DRAFT, Status.RUNNING));
        return id;
    }

    private void done(long id, long user, String tile) {
        assertTrue(repository.addProgress(id, tile, user, null, 1, Source.MANUAL, user + ":" + tile + ":" + System.nanoTime(), 1L, "").isPresent());
    }

    @Test
    void aDrawEventIsDrawnAtItsEndTimePostedWithTheSeedAndThePrizeRecordedAsPending() {
        Instant end = Instant.now().plus(Duration.ofHours(1));
        long id = event("{\"mode\":\"DRAW\"}", end);
        done(id, 501, "a");
        done(id, 502, "a");
        done(id, 502, "b");

        assertEquals(0, draws.sweep(end.minusSeconds(60)), "not before the end time");
        assertEquals(1, draws.sweep(end.plusSeconds(60)));
        assertEquals(0, draws.sweep(end.plusSeconds(120)), "only once");

        List<WinModes.Round> rounds = drawRepository.rounds(id);
        assertEquals(1, rounds.size());
        WinModes.Round r = rounds.get(0);
        assertEquals(3, r.tickets(), "one entry per finished tile; 503 finished none");
        assertEquals(1, r.picked().size());
        assertEquals(r.picked(), WinModes.draw(r.entrants(), 1, java.util.Set.of(), r.seed()), "anyone can re-run it from the seed and entries");

        var awards = repository.awards(id);
        assertEquals(1, awards.size());
        assertEquals(AwardStatus.PENDING, awards.get(0).status(), "recorded, not paid");
        assertEquals("u:" + awards.get(0).userId(), r.picked().get(0));

        assertEquals(1, queued.size());
        String text = queued.get(0).payload().getString("text");
        assertTrue(text.contains("Prize draw") && text.contains(Long.toUnsignedString(r.seed())), text);
        assertFalse(text.contains("<@"), "names, never mentions");
        assertEquals(EventUpdatePostJob.KIND, queued.get(0).kind());
    }

    @Test
    void aBotThatIsNotLiveDrawsNothingByItself() {
        Instant end = Instant.now().plus(Duration.ofHours(1));
        long id = event("{\"mode\":\"DRAW\"}", end);
        done(id, 501, "a");
        TrackingPostingToggle posting = Mockito.mock(TrackingPostingToggle.class);
        Mockito.when(posting.isEnabled()).thenReturn(false);
        new AutoPostRouter(Mockito.mock(BotConfig.class), posting);
        assertEquals(0, draws.sweep(end.plusSeconds(60)));
        assertTrue(drawRepository.rounds(id).isEmpty());
    }

    @Test
    void staffDrawOnceAndRerollAWinnerWhoDoesNotClaim() {
        long id = event("{\"mode\":\"DRAW\",\"autoDraw\":false}", null);
        done(id, 501, "a");
        done(id, 502, "a");
        assertEquals(0, draws.sweep(Instant.now().plus(Duration.ofDays(1))), "staff draw by hand");

        WinModes.Round first = draws.drawNow(guild, id, 9L);
        EventProblem twice = assertThrows(EventProblem.class, () -> draws.drawNow(guild, id, 9L));
        assertEquals(409, twice.status());

        WinModes.Round again = draws.reroll(guild, id, 1, 9L);
        assertNotEquals(first.picked().get(0), again.picked().get(0), "the winner who didn't claim is never drawn again");
        var awards = repository.awards(id);
        assertEquals(2, awards.size());
        assertEquals(AwardStatus.REVOKED, awards.stream().filter(a -> ("u:" + a.userId()).equals(first.picked().get(0))).findFirst().orElseThrow().status());
        var current = awards.stream().filter(a -> ("u:" + a.userId()).equals(again.picked().get(0))).findFirst().orElseThrow();
        assertEquals(AwardStatus.PENDING, current.status());
        assertEquals(2, queued.size(), "the draw and the reroll are both announced");

        service.moveAward(G, id, current.id(), AwardStatus.PAID, 9L, "");
        assertEquals(409, assertThrows(EventProblem.class, () -> draws.reroll(guild, id, 1, 9L)).status(), "a paid prize is never drawn again");
        assertEquals(400, assertThrows(EventProblem.class, () -> draws.reroll(guild, id, 2, 9L)).status(), "no winner in that place");
    }

    @Test
    void aRerollOnlyRecordsItsOwnPlaceEvenWhenAnotherWinningTeamGainedAMember() {
        long id = repository.createEvent(G, "custom", "Team draw", "", Scope.PRIVATE, ScoringMode.TEAM, null, null, 0,
                "{\"win\":{\"mode\":\"DRAW\",\"winners\":2,\"autoDraw\":false}}", 1L);
        repository.replaceObjectives(id, List.of(tile("a"), tile("b"), tile("c"), tile("d")));
        java.util.Map<Long, Long> teamOf = new java.util.HashMap<>();
        for (long user = 501; user <= 503; user++) {
            long team = repository.addTeam(id, "Team " + user, user, 10).orElseThrow();
            teamOf.put(user, team);
            repository.join(id, user, G, team, TeamRules.Kind.HOME_CLAN, BuyInStatus.NONE, 0);
        }
        repository.replacePrizes(id, List.of(new PrizePlan.Prize(0, 1, 2, PrizeKind.IN_GAME, "Bonds", 1, 0, PrizePlan.Split.EACH)));
        assertTrue(repository.moveStage(G, id, Status.DRAFT, Status.RUNNING));
        for (long user = 501; user <= 503; user++) {
            assertTrue(repository.addProgress(id, "a", user, teamOf.get(user), 1, Source.MANUAL, user + ":a:" + System.nanoTime(), 1L, "").isPresent());
        }

        WinModes.Round first = draws.drawNow(guild, id, 9L);
        assertEquals(2, first.picked().size());
        long firstPlaceTeam = Long.parseLong(first.picked().get(0).substring(2));
        assertEquals(2, repository.awards(id).size(), "one member on each winning team");

        // someone joins the 1st-place team after the draw (Draw now works while the event runs), then 2nd place is drawn again
        repository.join(id, 504, G, firstPlaceTeam, TeamRules.Kind.HOME_CLAN, BuyInStatus.NONE, 0);
        WinModes.Round again = draws.reroll(guild, id, 2, 9L);
        assertEquals(1, again.picked().size());

        var awards = repository.awards(id);
        var firstPlace = awards.stream().filter(a -> a.place() == 1).toList();
        assertEquals(1, firstPlace.size(), "1st place keeps exactly the award it was given at the draw: " + firstPlace);
        assertTrue(firstPlace.stream().noneMatch(a -> a.userId() == 504), "a member who joined later gets nothing for a place that was not drawn again");
        assertEquals(1, awards.stream().filter(a -> a.place() == 2 && a.status() == AwardStatus.REVOKED).count());
        assertEquals(1, awards.stream().filter(a -> a.place() == 2 && a.status() == AwardStatus.PENDING).count());
    }

    @Test
    void onlyPlaceLeavesEveryOtherPlaceEmpty() {
        assertEquals(java.util.Arrays.asList(null, null, "t:7"), EventDraws.onlyPlace(3, "t:7"));
        assertEquals(List.of("u:1"), EventDraws.onlyPlace(1, "u:1"));
    }

    @Test
    void aDrawThatFoundNobodyCanBeDrawnAgain() {
        long id = event("{\"mode\":\"DRAW\",\"minTiles\":2}", null);
        done(id, 501, "a");
        WinModes.Round empty = draws.drawNow(guild, id, 9L);
        assertTrue(empty.picked().isEmpty());
        assertTrue(queued.get(0).payload().getString("text").contains("Nobody qualified"));
        done(id, 501, "b");
        assertEquals(List.of("u:501"), draws.drawNow(guild, id, 9L).picked());
    }

    @Test
    void aPointsEventIsNeverDrawnAndItsAwardsFollowTheStandingsAsToday() {
        Instant end = Instant.now().plus(Duration.ofHours(1));
        long id = event(null, end);
        done(id, 501, "a");
        done(id, 502, "a");
        done(id, 502, "b");
        assertEquals(0, draws.sweep(end.plusSeconds(60)));
        assertEquals(409, assertThrows(EventProblem.class, () -> draws.drawNow(guild, id, 9L)).status());
        assertTrue(repository.moveStage(G, id, Status.RUNNING, Status.SCORING));
        var awards = service.generateAwards(G, id);
        assertEquals(1, awards.size());
        assertEquals(502L, awards.get(0).userId(), "most points wins");
        assertTrue(queued.isEmpty());
    }

    @Test
    void firstToALineGivesThePrizeToWhoeverFinishedALineFirst() {
        long id = event("{\"mode\":\"FIRST_LINE\"}", null);
        done(id, 502, "a");
        done(id, 502, "b"); // 502 finishes row 1 first
        done(id, 501, "c");
        done(id, 501, "d");
        done(id, 501, "a"); // 501 ends with more points
        assertTrue(repository.moveStage(G, id, Status.RUNNING, Status.SCORING));
        assertEquals("u:501", service.standings(G, id).get(0).subject(), "on points 501 would win");
        var awards = service.generateAwards(G, id);
        assertEquals(1, awards.size());
        assertEquals(502L, awards.get(0).userId());
    }

    @Test
    void aDrawEventsAwardsComeFromTheDrawNotTheStandings() {
        long id = event("{\"mode\":\"DRAW\"}", null);
        assertTrue(repository.moveStage(G, id, Status.RUNNING, Status.SCORING));
        assertEquals(409, assertThrows(EventProblem.class, () -> service.generateAwards(G, id)).status());
    }
}
