package com.runeclans.bot.events;

import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import com.runeclans.bot.runescape.ClanMemberRepository.ClanMemberRow;
import com.runeclans.bot.runescape.ClanSyncService;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkService;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * A clan-vs-clan event over real SQL (T-222): the host's clan gets a team when the event is made, an accepting co-host's clan gets one, a player joins their
 * clan's team by its in-game list, someone on no list is refused (staff can still add them, with no team), a clan's empty team goes when it is removed as a
 * co-host, and the event is always scored by team. Runs over the developer's local Postgres (skipped without one); rosters and links are mocks.
 */
class ClanVsClanEventTest {
    private static final long HOST = 999_000_000_000_000_071L, ALLY = 999_000_000_000_000_072L;
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventCohostRepository cohosts;
    private EventService service;
    private PlayerLinkService links;
    private ClanSyncService clanSync;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    private static PlayerLink link(long guild, long user, String rsn) {
        return new PlayerLink(1, guild, user, rsn, "TEST", OffsetDateTime.now(), null);
    }

    private static ClanMemberRow row(long guild, String rsn) {
        return new ClanMemberRow(1, guild, rsn, "Recruit", OffsetDateTime.now(), OffsetDateTime.now(), true, 0, 0, null);
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        cohosts = new EventCohostRepository(supplier, new com.runeclans.bot.hubevents.HubEventMap(supplier));
        links = Mockito.mock(PlayerLinkService.class);
        clanSync = Mockito.mock(ClanSyncService.class);
        ClanProfileRepository profiles = Mockito.mock(ClanProfileRepository.class);
        Mockito.when(profiles.get(anyLong())).thenReturn(Optional.empty());
        Mockito.when(clanSync.getClanName(HOST)).thenReturn("Younglings");
        Mockito.when(clanSync.getClanName(ALLY)).thenReturn("Iron Legion");
        Mockito.when(clanSync.getRoster(HOST, true)).thenReturn(List.of(row(HOST, "Host Guy")));
        Mockito.when(clanSync.getRoster(ALLY, true)).thenReturn(List.of(row(ALLY, "Ally Gal")));
        Mockito.when(links.getLinksForUser(anyLong(), Mockito.eq(1L))).thenReturn(List.of(link(HOST, 1, "host guy")));
        Mockito.when(links.getLinksForUser(anyLong(), Mockito.eq(2L))).thenReturn(List.of(link(ALLY, 2, "Ally Gal")));
        Mockito.when(links.getLinksForUser(anyLong(), Mockito.eq(3L))).thenReturn(List.of(link(HOST, 3, "Nobody Here")));
        ClanVsClanTemplate template = new ClanVsClanTemplate(new BingoTemplate(), new CompetitionTemplate(), new BossNightTemplate(), new ScavengerHuntTemplate(), repository, cohosts,
                links, clanSync, profiles);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return userGuildId == hostGuildId ? TeamRules.Kind.HOME_CLAN : TeamRules.Kind.PARTNER;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(template, new CustomTemplate())), kinds,
                new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, HOST);
            s.executeUpdate();
        }
    }

    private Map<String, Long> teams(long id) {
        Map<String, Long> byName = new java.util.TreeMap<>();
        repository.teams(id).forEach(t -> byName.put(t.name(), t.id()));
        return byName;
    }

    @Test
    void eachClanIsATeamAndPlayersJoinTheirClansTeamByItsList() {
        // asked for solo scoring and a boss night: the template scores by team whatever was asked
        long id = service.createEvent(HOST, 9L, new EventService.EventInput("Clan clash", "", ClanVsClan.KEY, EventRules.Scope.ALLIANCE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0,
                DataObject.fromJson("{\"cvcGame\":\"boss-night\",\"boss\":\"Telos\"}")));
        EventRepository.Event event = repository.event(HOST, id).orElseThrow();
        assertEquals(EventRules.ScoringMode.TEAM, event.scoringMode());
        assertEquals("boss-night", ClanVsClan.game(event));
        assertEquals(List.of("Younglings"), List.copyOf(teams(id).keySet()), "the host's clan has its team from the start");

        service.moveStage(HOST, id, EventRules.Status.SIGNUPS);
        cohosts.invite(id, ALLY, 9L);
        cohosts.decide(id, ALLY, EventCohostRepository.Status.INVITED, EventCohostRepository.Status.ACCEPTED, 8L);
        service.syncTemplateTeams(repository.event(HOST, id).orElseThrow(), false);
        Map<String, Long> teams = teams(id);
        assertEquals(List.of("Iron Legion", "Younglings"), List.copyOf(teams.keySet()));

        service.join(HOST, id, HOST, 1, null, false, null);
        service.join(HOST, id, HOST, 2, null, false, null);
        assertEquals(teams.get("Younglings"), repository.participant(id, 1).orElseThrow().teamId(), "names are matched whatever their case");
        assertEquals(teams.get("Iron Legion"), repository.participant(id, 2).orElseThrow().teamId());

        EventProblem refused = assertThrows(EventProblem.class, () -> service.join(HOST, id, HOST, 3, null, false, null));
        assertTrue(refused.getMessage().contains("Iron Legion") && refused.getMessage().contains("Younglings"), refused.getMessage());
        service.join(HOST, id, HOST, 3, null, true, null);
        assertNull(repository.participant(id, 3).orElseThrow().teamId(), "staff can add them, and choose their team");
    }

    @Test
    void aClanRemovedAsCoHostLosesItsEmptyTeam() {
        long id = service.createEvent(HOST, 9L, new EventService.EventInput("Clan clash", "", ClanVsClan.KEY, EventRules.Scope.ALLIANCE, EventRules.ScoringMode.TEAM, null, null, 0,
                DataObject.fromJson("{\"cvcGame\":\"boss-night\",\"boss\":\"Telos\"}")));
        service.moveStage(HOST, id, EventRules.Status.SIGNUPS);
        cohosts.invite(id, ALLY, 9L);
        cohosts.decide(id, ALLY, EventCohostRepository.Status.INVITED, EventCohostRepository.Status.ACCEPTED, 8L);
        service.syncTemplateTeams(repository.event(HOST, id).orElseThrow(), false);
        assertEquals(2, repository.teams(id).size());

        cohosts.decide(id, ALLY, EventCohostRepository.Status.ACCEPTED, EventCohostRepository.Status.REMOVED, 9L);
        service.syncTemplateTeams(repository.event(HOST, id).orElseThrow(), false);
        assertEquals(List.of("Younglings"), List.copyOf(teams(id).keySet()));
        assertEquals(1, ClanVsClan.teams(DataObject.fromJson(repository.event(HOST, id).orElseThrow().config())).size());
    }

    @Test
    void theChecklistAsksForAnotherClanAndPointsOutSmallClans() {
        long id = service.createEvent(HOST, 9L, new EventService.EventInput("Clan clash", "", ClanVsClan.KEY, EventRules.Scope.ALLIANCE, EventRules.ScoringMode.TEAM, null, null, 0,
                DataObject.fromJson("{\"cvcGame\":\"boss-night\",\"boss\":\"Telos\",\"cvc\":{\"minPlayers\":2}}")));
        EventPrep.Prep prep = service.prepOf(repository.event(HOST, id).orElseThrow());
        assertEquals("clans", prep.steps().get(0).key());
        assertEquals(EventPrep.State.ACTIVE, prep.steps().get(0).state());
        assertTrue(prep.warnings().stream().anyMatch(w -> w.contains("Younglings has 0 players")), prep.warnings().toString());
    }
}
