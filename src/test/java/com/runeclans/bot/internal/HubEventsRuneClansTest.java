package com.runeclans.bot.internal;

import com.runeclans.bot.alliance.AllianceRepository;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.JoinLinks;
import com.runeclans.bot.events.CustomTemplate;
import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.events.EventCohostRepository;
import com.runeclans.bot.events.EventKinds;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventTemplates;
import com.runeclans.bot.events.HubModerationRepository;
import com.runeclans.bot.events.TeamRules;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import com.runeclans.bot.runescape.PlayerLinkService;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What RuneClans reads from the events hub (T-335..T-337): a server's "Only this server" events for its own members only, a player's own place in an
 * event, "My events" across servers, and the dashboard link only for someone who may manage the event. Runs over the developer's local Postgres
 * (skipped without one); Discord is mocked.
 */
class HubEventsRuneClansTest {
    private static final long HOST = 999_000_000_000_000_335L;
    private static final long OTHER = 999_000_000_000_000_336L;
    private static final long PLAYER = 999_000_000_000_000_501L;
    private static final long STRANGER = 999_000_000_000_000_502L;
    private static ConnectionSupplier supplier;
    private EventService service;
    private HubEventsApi api;
    private DashboardAccess access;
    private MemberEventsApi memberEvents;
    private JDA jda;
    private EventCohostRepository cohosts;
    private Guild host;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        EventRepository repository = new EventRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_CLAN;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds,
                new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        ClanProfileService profiles = Mockito.mock(ClanProfileService.class);
        Mockito.when(profiles.publicView(Mockito.anyLong(), Mockito.anyBoolean())).thenReturn(Optional.empty());
        HubModerationRepository moderation = Mockito.mock(HubModerationRepository.class);
        Mockito.when(moderation.barredIds()).thenReturn(java.util.Set.of());
        cohosts = Mockito.mock(EventCohostRepository.class);
        Mockito.when(cohosts.forEvent(Mockito.anyLong())).thenReturn(List.of());
        access = Mockito.mock(DashboardAccess.class);
        Mockito.when(access.tierOf(Mockito.any(), Mockito.any())).thenReturn(DashboardAccess.Tier.NONE);
        api = new HubEventsApi(repository, service, new EventTemplates(List.of(new CustomTemplate())), Mockito.mock(JoinLinks.class), profiles,
                Mockito.mock(AllianceRepository.class), Mockito.mock(PlayerLinkService.class), moderation, cohosts, access, memberEvents = Mockito.mock(MemberEventsApi.class));

        jda = Mockito.mock(JDA.class);
        host = Mockito.mock(Guild.class);
        Mockito.when(host.getIdLong()).thenReturn(HOST);
        Mockito.when(host.getId()).thenReturn(Long.toString(HOST));
        Mockito.when(host.getName()).thenReturn("Host server");
        Mockito.when(jda.getGuildById(HOST)).thenReturn(host);
        Member member = Mockito.mock(Member.class);
        Mockito.when(host.getMemberById(PLAYER)).thenReturn(member);
        User player = Mockito.mock(User.class);
        Mockito.when(player.getMutualGuilds()).thenReturn(List.of(host));
        Mockito.when(jda.getUserById(PLAYER)).thenReturn(player);
        User stranger = Mockito.mock(User.class);
        Mockito.when(stranger.getMutualGuilds()).thenReturn(List.of());
        Mockito.when(jda.getUserById(STRANGER)).thenReturn(stranger);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id IN (?, ?)")) {
            s.setLong(1, HOST);
            s.setLong(2, OTHER);
            s.executeUpdate();
        }
    }

    private long event(long guild, String name, EventRules.Scope scope, EventRules.Status to) {
        long id = service.createEvent(guild, 9L, new EventService.EventInput(name, "Rules here", "custom", scope, EventRules.ScoringMode.INDIVIDUAL, null, null, 0,
                DataObject.empty()));
        if (to != EventRules.Status.DRAFT) service.moveStage(guild, id, EventRules.Status.SIGNUPS);
        if (to == EventRules.Status.RUNNING) service.moveStage(guild, id, EventRules.Status.RUNNING);
        return id;
    }

    private static List<String> names(DataObject answer) {
        DataArray events = answer.getArray("events");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < events.length(); i++) out.add(events.getObject(i).getString("name"));
        return out;
    }

    @Test
    void aServersOwnEventsAreOnlyForItsMembers() {
        long own = event(HOST, "Members only", EventRules.Scope.PRIVATE, EventRules.Status.SIGNUPS);
        event(HOST, "Still a draft", EventRules.Scope.PRIVATE, EventRules.Status.DRAFT);

        assertTrue(names(api.list(jda, Long.toString(PLAYER))).contains("Members only"));
        assertFalse(names(api.list(jda, Long.toString(PLAYER))).contains("Still a draft"));
        assertFalse(names(api.list(jda, Long.toString(STRANGER))).contains("Members only"));
        assertFalse(names(api.list(jda, null)).contains("Members only"));

        DataObject seen = api.one(jda, Long.toString(own), Long.toString(PLAYER));
        assertNotNull(seen);
        assertEquals("MINE", seen.getString("access"));
        assertNull(api.one(jda, Long.toString(own), Long.toString(STRANGER)), "a stranger is told it doesn't exist");
        assertFalse(seen.toString().contains(Long.toString(HOST)), "no server id for someone who can't manage it");
    }

    @Test
    void aCardNamesTheHubEventItIsSoTheWebsiteListsItOnce() {
        long onHub = event(HOST, "On the hub", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        long local = event(HOST, "Bot only", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        String hubId = java.util.UUID.randomUUID().toString();
        Mockito.when(memberEvents.hubIdOf(Mockito.argThat(e -> e != null && e.id() == onHub))).thenReturn(hubId);
        DataArray cards = api.list(jda, null).getArray("events");
        int checked = 0;
        for (int i = 0; i < cards.length(); i++) {
            DataObject card = cards.getObject(i);
            if (card.getString("id").equals(Long.toString(onHub)) && ++checked > 0) assertEquals(hubId, card.getString("hubId"));
            if (card.getString("id").equals(Long.toString(local)) && ++checked > 0) assertFalse(card.hasKey("hubId"), "an event only the bot has carries no hub id");
        }
        assertEquals(2, checked, "both are listed");
        assertEquals(hubId, api.one(jda, Long.toString(onHub), null).getString("hubId"));
    }

    @Test
    void aPlayerSeesTheirOwnPlaceAndMyEventsSpansServers() {
        long here = event(HOST, "Here", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        long there = event(OTHER, "There", EventRules.Scope.PRIVATE, EventRules.Status.SIGNUPS);
        event(OTHER, "Not joined", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        service.join(HOST, here, HOST, PLAYER, null, true, null);
        service.join(OTHER, there, OTHER, PLAYER, null, true, null);

        DataObject one = api.one(jda, Long.toString(here), Long.toString(PLAYER));
        DataObject me = one.getObject("me");
        assertFalse(me.getBoolean("waiting"));
        assertEquals("NOT_REQUIRED", me.getString("verification"));
        assertTrue(api.one(jda, Long.toString(here), Long.toString(STRANGER)).isNull("me"), "nothing about anyone else");

        List<String> mine = names(api.mine(jda, Long.toString(PLAYER)));
        assertEquals(2, mine.size(), mine.toString());
        assertTrue(mine.containsAll(List.of("Here", "There")));
        assertEquals(List.of(), names(api.mine(jda, Long.toString(STRANGER))));
        assertEquals(List.of(), names(api.mine(jda, "not-an-id")));
    }

    @Test
    void theManageLinkIsOnlyForSomeoneWhoMayUseTheEventManager() {
        long id = event(HOST, "Managed", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        assertTrue(api.one(jda, Long.toString(id), Long.toString(PLAYER)).isNull("manage"));

        Mockito.when(access.tierOf(Mockito.any(), Mockito.any())).thenReturn(DashboardAccess.Tier.ADMIN);
        DataObject manage = api.one(jda, Long.toString(id), Long.toString(PLAYER)).getObject("manage");
        assertEquals(Long.toString(HOST), manage.getString("guildId"));
        assertEquals(Long.toString(id), manage.getString("eventId"));
        assertTrue(api.one(jda, Long.toString(id), Long.toString(STRANGER)).isNull("manage"));
    }

    @Test
    void aPlayerCanLeaveFromRuneClansOnlyWhileSignupsAreOpen() {
        long open = event(HOST, "Leave me", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        long running = event(HOST, "Too late", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        service.join(HOST, open, HOST, PLAYER, null, true, null);
        service.join(HOST, running, HOST, PLAYER, null, true, null);
        // straight to running in the table: the start checks (objectives etc.) aren't what this test is about
        new EventRepository(supplier).moveStage(HOST, running, EventRules.Status.SIGNUPS, EventRules.Status.RUNNING);

        assertEquals(200, api.leave(jda, Long.toString(open), Long.toString(PLAYER)).status());
        assertEquals(404, api.leave(jda, Long.toString(open), Long.toString(PLAYER)).status(), "not in it any more");
        assertEquals(409, api.leave(jda, Long.toString(running), Long.toString(PLAYER)).status(), "it has started");
        assertEquals(400, api.leave(jda, Long.toString(open), "nobody").status());
    }

    @Test
    void aPlayerCanJoinThroughACoHostTheyAreIn() {
        long id = event(HOST, "Co-hosted", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        Guild other = Mockito.mock(Guild.class);
        Mockito.when(other.getIdLong()).thenReturn(OTHER);
        Mockito.when(other.getName()).thenReturn("Other server");
        Mockito.when(other.getMemberById(PLAYER)).thenReturn(Mockito.mock(Member.class));
        Mockito.when(jda.getGuildById(OTHER)).thenReturn(other);
        Mockito.when(jda.getUserById(PLAYER).getMutualGuilds()).thenReturn(List.of(host, other));
        Mockito.when(cohosts.forEvent(id)).thenReturn(List.of(new EventCohostRepository.Cohost(id, OTHER, EventCohostRepository.Status.ACCEPTED, 9L, 9L, null, null)));

        DataArray joinWith = api.one(jda, Long.toString(id), Long.toString(PLAYER)).getArray("joinWith");
        assertEquals(1, joinWith.length());
        assertEquals("cohost-1", joinWith.getObject(0).getString("key"));
        assertEquals("Other server", joinWith.getObject(0).getString("name"));
        assertFalse(joinWith.toString().contains(Long.toString(OTHER)), "no server id");

        assertEquals(400, api.join(jda, Long.toString(id), Long.toString(PLAYER), "cohost-2").status(), "no such co-host");
        assertEquals(200, api.join(jda, Long.toString(id), Long.toString(PLAYER), "cohost-1").status());
        assertEquals(OTHER, new EventRepository(supplier).participant(id, PLAYER).orElseThrow().guildId(), "joined through the co-host");
    }
}
