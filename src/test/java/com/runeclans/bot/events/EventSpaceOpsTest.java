package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRepository.Setup;
import com.runeclans.bot.events.EventRepository.Team;
import com.runeclans.bot.hubevents.HubEventMap;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.requests.restaction.RoleAction;
import net.dv8tion.jda.api.utils.concurrent.Task;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Setting up, keeping right and removing an event's roles and channels, over a real database and a pretend Discord server that records what is made, given
 * and deleted. Skipped where there is no local database. Everything is made in a made-up server and removed afterwards.
 */
class EventSpaceOpsTest {
    private static final long G = 999_000_000_000_000_051L;
    private static final long ACTOR = 9L;
    private static final long BOT = 77L;
    private static final long EVERYONE = 1L;
    private static final long STAFF_ROLE = 501L;

    private static final UUID CLAN = UUID.randomUUID();

    private static ConnectionSupplier supplier;
    private static HubEventMap map;
    /** The hub as the ops see it: a {@link HubEvents} answering from the state below (the hub's own rules are the hub's tests' business). */
    private HubEvents events;
    private final Map<Long, Event> hubEvents = new LinkedHashMap<>();
    private final Map<Long, List<Participant>> roster = new HashMap<>();
    private final Map<Long, List<Team>> teams = new HashMap<>();
    private final Map<Long, Setup> setups = new HashMap<>();
    private long nextTeam = 1;
    private EventSpaceRepository space;
    private EventSpaceOps ops;
    private EventSpaces spaces;
    private PretendServer server;
    private Guild guild;

    /** A pretend Discord server: what exists on it, who holds which role, and a log of what was asked of it. */
    private static final class PretendServer {
        long nextId = 10_000;
        final Map<Long, Role> roles = new LinkedHashMap<>();
        final Map<Long, GuildChannel> channels = new LinkedHashMap<>();
        final Map<Long, Set<Long>> memberRoles = new HashMap<>();
        final Set<Long> notInServer = new HashSet<>();
        final List<String> madeRoles = new ArrayList<>();
        final List<String> madeCategories = new ArrayList<>();
        final List<String> madeChannels = new ArrayList<>();
        final Map<String, List<long[]>> overwrites = new HashMap<>();
        final List<String> given = new ArrayList<>();
        final List<String> taken = new ArrayList<>();
        final List<Long> deleted = new ArrayList<>();
        /** A channel with this name can't be made (Discord is having a moment). */
        volatile String failChannel;
    }

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        map = new HubEventMap(supplier);
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        space = new EventSpaceRepository(supplier, map);
        events = Mockito.mock(HubEvents.class);
        Mockito.when(events.event(ArgumentMatchers.eq(G), ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            Event e = hubEvents.get((Long) inv.getArgument(1));
            if (e == null) throw new EventProblem(404, "That event doesn't exist (any more).");
            return e;
        });
        Mockito.when(events.settings(ArgumentMatchers.any(Event.class))).thenAnswer(inv -> EventSettings.parse(DataObject.fromJson(((Event) inv.getArgument(0)).config())));
        Mockito.when(events.participants(ArgumentMatchers.anyLong())).thenAnswer(inv -> List.copyOf(roster.getOrDefault((Long) inv.getArgument(0), List.of())));
        Mockito.when(events.countedPlayers(ArgumentMatchers.any(Event.class))).thenAnswer(inv -> roster.getOrDefault(((Event) inv.getArgument(0)).id(), List.of()).stream().filter(p -> !p.waiting()).toList());
        Mockito.when(events.teams(ArgumentMatchers.anyLong())).thenAnswer(inv -> List.copyOf(teams.getOrDefault((Long) inv.getArgument(0), List.of())));
        Mockito.when(events.setup(ArgumentMatchers.anyLong())).thenAnswer(inv -> setups.getOrDefault((Long) inv.getArgument(0), Setup.NONE));
        Mockito.doAnswer(inv -> {
            long id = inv.getArgument(1);
            Setup before = setups.getOrDefault(id, Setup.NONE);
            setups.put(id, new Setup(before.signupsClosedAt(), null, before.rosterConfirmedAt(), null, before.teamsFinalisedAt(), null,
                    before.spaceAppliedAt() == null ? java.time.Instant.now() : before.spaceAppliedAt(), null, inv.getArgument(2), inv.getArgument(3), null, null));
            return null;
        }).when(events).markSpace(ArgumentMatchers.eq(G), ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean(), ArgumentMatchers.any());
        Mockito.doAnswer(inv -> {
            long id = inv.getArgument(1);
            Setup before = setups.getOrDefault(id, Setup.NONE);
            setups.put(id, new Setup(before.signupsClosedAt(), null, before.rosterConfirmedAt(), null, before.teamsFinalisedAt(), null, null, null, false, "", null, null));
            return null;
        }).when(events).clearSpace(ArgumentMatchers.eq(G), ArgumentMatchers.anyLong());
        server = new PretendServer();
        guild = Mockito.mock(Guild.class);
        PretendServer s = server;
        Mockito.when(guild.getIdLong()).thenReturn(G);

        Role everyone = role(EVERYONE, "@everyone");
        Role staff = role(STAFF_ROLE, "Admin");
        Mockito.when(staff.getPosition()).thenReturn(10);
        Mockito.when(guild.getPublicRole()).thenReturn(everyone);
        Mockito.when(guild.getRoles()).thenAnswer(inv -> new ArrayList<>(s.roles.values()));
        Mockito.when(guild.getRoleById(ArgumentMatchers.anyLong())).thenAnswer(inv -> s.roles.get((Long) inv.getArgument(0)));
        Mockito.when(guild.getChannels()).thenAnswer(inv -> new ArrayList<>(s.channels.values()));
        Mockito.when(guild.getGuildChannelById(ArgumentMatchers.anyLong())).thenAnswer(inv -> s.channels.get((Long) inv.getArgument(0)));
        Mockito.when(guild.getCategoryById(ArgumentMatchers.anyLong())).thenAnswer(inv -> s.channels.get((Long) inv.getArgument(0)) instanceof Category c ? c : null);
        SelfMember self = Mockito.mock(SelfMember.class, invocation -> invocation.getMethod().getName().equals("hasPermission") || invocation.getMethod().getName().equals("canInteract") ? Boolean.TRUE : Mockito.RETURNS_DEFAULTS.answer(invocation));
        Mockito.when(self.getIdLong()).thenReturn(BOT);
        Mockito.when(guild.getSelfMember()).thenReturn(self);

        // members: everyone is in the server unless said otherwise; asking Discord about the rest finds nobody
        Mockito.when(guild.getMemberById(ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            if (s.notInServer.contains(id)) return null;
            Member member = Mockito.mock(Member.class);
            Mockito.when(member.getIdLong()).thenReturn(id);
            Mockito.when(member.getEffectiveName()).thenReturn("Player " + id);
            Mockito.when(member.getRoles()).thenAnswer(x -> s.memberRoles.getOrDefault(id, Set.of()).stream().map(s.roles::get).toList());
            return member;
        });
        Task<List<Member>> nobody = Mockito.mock(Task.class);
        Mockito.when(nobody.onSuccess(ArgumentMatchers.any())).thenAnswer(inv -> {
            ((Consumer<List<Member>>) inv.getArgument(0)).accept(List.of());
            return nobody;
        });
        Mockito.when(nobody.onError(ArgumentMatchers.any())).thenReturn(nobody);
        Mockito.when(guild.retrieveMembersByIds(ArgumentMatchers.<Collection<Long>>any())).thenReturn(nobody);

        // making things
        Mockito.when(guild.createRole()).thenAnswer(inv -> {
            RoleAction action = Mockito.mock(RoleAction.class, Mockito.RETURNS_SELF);
            AtomicReference<String> name = new AtomicReference<>("");
            Mockito.when(action.setName(ArgumentMatchers.anyString())).thenAnswer(x -> {
                name.set(x.getArgument(0));
                return action;
            });
            Mockito.when(action.complete()).thenAnswer(x -> {
                s.madeRoles.add(name.get());
                return role(s.nextId++, name.get());
            });
            return action;
        });
        Mockito.when(guild.createCategory(ArgumentMatchers.anyString())).thenAnswer(inv -> channelAction(inv.getArgument(0), true));
        Mockito.when(guild.createTextChannel(ArgumentMatchers.anyString(), ArgumentMatchers.any(Category.class))).thenAnswer(inv -> channelAction(inv.getArgument(0), false));
        Mockito.when(guild.addRoleToMember(ArgumentMatchers.any(UserSnowflake.class), ArgumentMatchers.any(Role.class))).thenAnswer(inv -> {
            long user = ((UserSnowflake) inv.getArgument(0)).getIdLong();
            Role role = inv.getArgument(1);
            AuditableRestAction<Void> action = Mockito.mock(AuditableRestAction.class, Mockito.RETURNS_SELF);
            Mockito.when(action.complete()).thenAnswer(x -> {
                if (s.notInServer.contains(user)) {
                    net.dv8tion.jda.api.exceptions.ErrorResponseException gone = Mockito.mock(net.dv8tion.jda.api.exceptions.ErrorResponseException.class);
                    Mockito.when(gone.getErrorResponse()).thenReturn(net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MEMBER);
                    throw gone;
                }
                s.given.add(user + ":" + role.getName());
                s.memberRoles.computeIfAbsent(user, k -> new HashSet<>()).add(role.getIdLong());
                return null;
            });
            return action;
        });
        Mockito.when(guild.removeRoleFromMember(ArgumentMatchers.any(UserSnowflake.class), ArgumentMatchers.any(Role.class))).thenAnswer(inv -> {
            long user = ((UserSnowflake) inv.getArgument(0)).getIdLong();
            Role role = inv.getArgument(1);
            AuditableRestAction<Void> action = Mockito.mock(AuditableRestAction.class, Mockito.RETURNS_SELF);
            Mockito.when(action.complete()).thenAnswer(x -> {
                s.taken.add(user + ":" + role.getName());
                s.memberRoles.computeIfAbsent(user, k -> new HashSet<>()).remove(role.getIdLong());
                return null;
            });
            return action;
        });

        PermissionGroupService groups = Mockito.mock(PermissionGroupService.class);
        Mockito.when(groups.group(ArgumentMatchers.eq(G), ArgumentMatchers.eq(PermissionGroup.ADMIN)))
                .thenReturn(Optional.of(new PermissionGroup(1, G, PermissionGroup.ADMIN, "Admin", true, false, List.of(STAFF_ROLE))));
        spaces = new EventSpaces(events, space, groups);
        ops = new EventSpaceOps(events, space, spaces, new EventChanges(), null, com.runeclans.bot.jobs.JobRepository.inMemory(java.time.Clock.systemUTC()));
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (long id : hubEvents.keySet()) space.clear(id);
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_map WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    // ---------- the pretend server ----------

    private Role role(long id, String name) {
        Role role = Mockito.mock(Role.class);
        Mockito.when(role.getIdLong()).thenReturn(id);
        Mockito.when(role.getId()).thenReturn(Long.toString(id));
        Mockito.when(role.getName()).thenReturn(name);
        // a bot-made role: ordinary, so MemberApi.safeToAssign lets the bot hand it out
        Mockito.when(role.getPermissions()).thenReturn(java.util.EnumSet.noneOf(net.dv8tion.jda.api.Permission.class));
        AuditableRestAction<Void> delete = deletion(id);
        Mockito.when(role.delete()).thenReturn(delete);
        server.roles.put(id, role);
        return role;
    }

    @SuppressWarnings("unchecked")
    private AuditableRestAction<Void> deletion(long id) {
        AuditableRestAction<Void> delete = Mockito.mock(AuditableRestAction.class, Mockito.RETURNS_SELF);
        Mockito.when(delete.complete()).thenAnswer(x -> {
            server.deleted.add(id);
            server.roles.remove(id);
            server.channels.remove(id);
            return null;
        });
        return delete;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ChannelAction channelAction(String name, boolean category) {
        ChannelAction action = Mockito.mock(ChannelAction.class, Mockito.RETURNS_SELF);
        List<long[]> mine = new ArrayList<>();
        Mockito.when(action.addRolePermissionOverride(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            mine.add(new long[]{1, inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)});
            return action;
        });
        Mockito.when(action.addMemberPermissionOverride(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            mine.add(new long[]{0, inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)});
            return action;
        });
        Mockito.when(action.complete()).thenAnswer(inv -> {
            if (name.equals(server.failChannel)) throw new IllegalStateException("Discord is having a moment");
            long id = server.nextId++;
            GuildChannel channel = category ? Mockito.mock(Category.class) : Mockito.mock(TextChannel.class);
            Mockito.when(channel.getIdLong()).thenReturn(id);
            Mockito.when(channel.getId()).thenReturn(Long.toString(id));
            Mockito.when(channel.getName()).thenReturn(name);
            Mockito.when(channel.getType()).thenReturn(category ? ChannelType.CATEGORY : ChannelType.TEXT);
            // like a real channel, it has the overwrites it was made with (T-210 reads them back to spot changes made on Discord)
            Mockito.when(((net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer) channel).getPermissionOverrides()).thenAnswer(x -> overridesOf(mine));
            AuditableRestAction<Void> delete = deletion(id);
            Mockito.when(channel.delete()).thenReturn(delete);
            server.channels.put(id, channel);
            (category ? server.madeCategories : server.madeChannels).add(name);
            server.overwrites.put(name, mine);
            return channel;
        });
        return action;
    }

    /** The pretend channel's overwrites as JDA hands them out: {isRole, id, allow, deny} each. */
    private static List<net.dv8tion.jda.api.entities.PermissionOverride> overridesOf(List<long[]> made) {
        List<net.dv8tion.jda.api.entities.PermissionOverride> list = new ArrayList<>();
        for (long[] o : made) {
            net.dv8tion.jda.api.entities.PermissionOverride override = Mockito.mock(net.dv8tion.jda.api.entities.PermissionOverride.class);
            Mockito.when(override.isRoleOverride()).thenReturn(o[0] == 1);
            Mockito.when(override.isMemberOverride()).thenReturn(o[0] == 0);
            Mockito.when(override.getIdLong()).thenReturn(o[1]);
            Mockito.when(override.getAllowedRaw()).thenReturn(o[2]);
            Mockito.when(override.getDeniedRaw()).thenReturn(o[3]);
            list.add(override);
        }
        return list;
    }

    // ---------- helpers ----------

    // ---------- the pretend hub's state ----------

    private long event(String template, ScoringMode mode, String config) {
        long id = map.localId(UUID.randomUUID(), CLAN, G);
        hubEvents.put(id, new Event(id, G, template, "Boss Night", "", Status.DRAFT, Scope.PRIVATE, mode, null, null, 0, config, ACTOR, java.time.Instant.now(), null, false));
        return id;
    }

    private void open(long eventId) {
        Event e = hubEvents.get(eventId);
        hubEvents.put(eventId, new Event(e.id(), e.guildId(), e.template(), e.name(), e.description(), Status.SIGNUPS, e.scope(), e.scoringMode(), e.startsAt(), e.endsAt(), e.maxParticipants(),
                e.config(), e.createdBy(), e.createdAt(), e.signupId(), e.archived()));
    }

    private void join(long eventId, long userId, Long teamId) {
        roster.computeIfAbsent(eventId, k -> new ArrayList<>()).add(new Participant(eventId, userId, G, teamId, TeamRules.Kind.HOME_CLAN, false, PrizeRules.BuyInStatus.NONE,
                java.time.Instant.now(), EventRoster.Verification.NOT_REQUIRED, null, null, "", null, EventRepository.RosterSource.STAFF));
    }

    private void leave(long eventId, long userId) {
        roster.getOrDefault(eventId, new ArrayList<>()).removeIf(p -> p.userId() == userId);
    }

    private void setTeam(long eventId, long userId, Long teamId) {
        List<Participant> players = roster.get(eventId);
        for (int i = 0; i < players.size(); i++) {
            Participant p = players.get(i);
            if (p.userId() == userId) players.set(i, new Participant(p.eventId(), p.userId(), p.guildId(), teamId, p.kind(), p.waiting(), p.buyIn(), p.joinedAt(), p.verification(), p.verifiedBy(),
                    p.verifiedAt(), p.verificationNote(), p.rating(), p.source()));
        }
    }

    private long addTeam(long eventId, String name) {
        long id = nextTeam++;
        teams.computeIfAbsent(eventId, k -> new ArrayList<>()).add(new Team(id, eventId, name, null));
        return id;
    }

    private void organisers(long eventId, long... userIds) {
        Event e = hubEvents.get(eventId);
        DataObject config = DataObject.fromJson(e.config());
        net.dv8tion.jda.api.utils.data.DataArray ids = net.dv8tion.jda.api.utils.data.DataArray.empty();
        for (long id : userIds) ids.add(Long.toString(id));
        config.put("organisers", ids);
        hubEvents.put(eventId, new Event(e.id(), e.guildId(), e.template(), e.name(), e.description(), e.status(), e.scope(), e.scoringMode(), e.startsAt(), e.endsAt(), e.maxParticipants(),
                config.toString(), e.createdBy(), e.createdAt(), e.signupId(), e.archived()));
    }

    private void closeAndConfirm(long eventId) {
        Setup before = setups.getOrDefault(eventId, Setup.NONE);
        java.time.Instant now = java.time.Instant.now();
        setups.put(eventId, new Setup(now, null, now, null, before.teamsFinalisedAt(), null, before.spaceAppliedAt(), null, before.spaceComplete(), before.spaceNote(), null, null));
    }

    private void finaliseTeams(long eventId) {
        Setup before = setups.getOrDefault(eventId, Setup.NONE);
        setups.put(eventId, new Setup(before.signupsClosedAt(), null, before.rosterConfirmedAt(), null, java.time.Instant.now(), null, before.spaceAppliedAt(), null, before.spaceComplete(),
                before.spaceNote(), null, null));
    }

    private EventSpaceOps.Job finish(EventSpaceOps.Job job) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (job.running() && System.currentTimeMillis() < end) Thread.sleep(25);
        assertFalse(job.running(), "the job didn't finish: " + job.message());
        return job;
    }

    private Event reload(long eventId) {
        return hubEvents.get(eventId);
    }

    private static boolean allows(long[] overwrite, Permission p) {
        return (overwrite[2] & p.getRawValue()) != 0;
    }

    private static boolean denies(long[] overwrite, Permission p) {
        return (overwrite[3] & p.getRawValue()) != 0;
    }

    private long[] overwriteFor(String channel, long id) {
        return server.overwrites.get(channel).stream().filter(o -> o[1] == id).findFirst().orElseThrow(() -> new AssertionError("no overwrite for " + id + " in " + channel));
    }

    private long roleId(long eventId, String key) {
        return space.roles(eventId).stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow().roleId();
    }

    // ---------- setting up ----------

    @Test
    void settingUpMakesTheRolesTheCategoryAndTheChannelsAndRecordsEachOne() throws Exception {
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"channels\": {\"enabled\": true}}");
        open(id);
        for (long user = 701; user <= 703; user++) join(id, user, null);
        organisers(id, 42L);
        closeAndConfirm(id);

        EventSpaceOps.Job job = finish(ops.startSetup(guild, reload(id), ACTOR, false));
        assertEquals("done", job.state(), job.message());

        assertEquals(List.of("Boss Night Participant", "Boss Night Organiser"), server.madeRoles);
        assertEquals(List.of("Boss Night"), server.madeCategories);
        assertEquals(List.of("boss-night-staff", "boss-night-announcements", "boss-night-general"), server.madeChannels);
        assertEquals(2, space.roles(id).size());
        assertEquals(4, space.channels(id).size());
        assertTrue(space.channels(id).stream().filter(c -> c.key().equals("general")).findFirst().orElseThrow().owned());
        assertEquals("category", space.channels(id).stream().filter(c -> c.key().equals("general")).findFirst().orElseThrow().parent());
        Setup setup = events.setup(id);
        assertTrue(setup.spaceApplied());
        assertTrue(setup.spaceComplete(), setup.spaceNote());

        // who can see what: everyone is shut out, players chat in general and only read announcements, the staff channel is the organisers' and staff's
        long participants = roleId(id, "participant");
        long organisers = roleId(id, "organiser");
        for (String channel : server.madeChannels) {
            assertTrue(denies(overwriteFor(channel, EVERYONE), Permission.VIEW_CHANNEL), channel);
            assertTrue(allows(overwriteFor(channel, STAFF_ROLE), Permission.MESSAGE_MANAGE), channel);
            assertTrue(allows(overwriteFor(channel, organisers), Permission.VIEW_CHANNEL), channel);
            assertTrue(allows(overwriteFor(channel, BOT), Permission.MANAGE_CHANNEL), channel);
        }
        assertTrue(allows(overwriteFor("boss-night-general", participants), Permission.MESSAGE_SEND));
        assertTrue(denies(overwriteFor("boss-night-announcements", participants), Permission.MESSAGE_SEND));
        assertTrue(allows(overwriteFor("boss-night-announcements", participants), Permission.VIEW_CHANNEL));
        assertTrue(server.overwrites.get("boss-night-staff").stream().noneMatch(o -> o[1] == participants), "players don't see the staff channel");
        assertEquals(0, overwriteFor("boss-night-general", BOT)[0], "the bot is let in as a member, not a role");

        // who holds the roles
        assertEquals(Set.of(701L, 702L, 703L), space.members(id).get("participant"));
        assertEquals(Set.of(42L), space.members(id).get("organiser"));
        assertTrue(server.given.contains("701:Boss Night Participant"));
    }

    @Test
    void settingUpAgainMakesNothingNewAndAnEventNotReadyIsRefusedBeforeAnythingIsMade() throws Exception {
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"channels\": {\"enabled\": true}}");
        open(id);
        join(id, 701, null);
        EventProblem early = assertThrows(EventProblem.class, () -> ops.startSetup(guild, reload(id), ACTOR, false));
        assertTrue(early.problems().contains("Close signups first."), early.problems().toString());
        assertTrue(server.madeRoles.isEmpty() && server.madeChannels.isEmpty(), "nothing is made for an event that isn't ready");

        closeAndConfirm(id);
        assertEquals("done", finish(ops.startSetup(guild, reload(id), ACTOR, false)).state());
        int roles = server.madeRoles.size();
        int channels = server.madeChannels.size();
        assertEquals("done", finish(ops.startSetup(guild, reload(id), ACTOR, false)).state());
        assertEquals(roles, server.madeRoles.size(), "the roles were made once");
        assertEquals(channels, server.madeChannels.size(), "the channels were made once");
    }

    @Test
    void aSetupThatStopsPartWayKeepsWhatWasMadeSaysWhatIsMissingAndCarriesOnNextTime() throws Exception {
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"channels\": {\"enabled\": true}}");
        open(id);
        join(id, 701, null);
        closeAndConfirm(id);
        server.failChannel = "boss-night-general";

        EventSpaceOps.Job job = finish(ops.startSetup(guild, reload(id), ACTOR, false));
        assertEquals("failed", job.state());
        assertEquals("Discord is having a moment", job.message());
        assertEquals(List.of("boss-night-staff", "boss-night-announcements"), server.madeChannels, "what came before the failure was made");
        assertEquals(3, space.channels(id).size(), "and is recorded: the category and two channels");
        Setup stopped = events.setup(id);
        assertTrue(stopped.spaceApplied());
        assertFalse(stopped.spaceComplete());
        assertTrue(stopped.spaceNote().contains("boss-night-general"), stopped.spaceNote());
        assertFalse(events.setup(id).spaceNote().contains("boss-night-staff"), "what was made isn't called missing");

        server.failChannel = null;
        assertEquals("done", finish(ops.startSetup(guild, reload(id), ACTOR, false)).state());
        assertEquals(List.of("boss-night-staff", "boss-night-announcements", "boss-night-general"), server.madeChannels, "only the missing channel was made the second time");
        assertEquals(List.of("Boss Night Participant", "Boss Night Organiser"), server.madeRoles, "the roles were not made again");
        assertTrue(events.setup(id).spaceComplete());
    }

    @Test
    void aTeamEventGetsARoleAndAPrivateChannelPerTeamAndOnlyTheTeamsRoleIsLetIn() throws Exception {
        long id = event(BossNightTemplate.KEY, ScoringMode.TEAM, "{\"boss\":\"Araxxor\",\"channels\": {\"enabled\": true, \"slots\": {\"staff\": {\"use\": \"none\"}, \"announcements\": {\"use\": \"none\"}, \"general\": {\"use\": \"none\"}, \"review\": {\"use\": \"none\"}}}}");
        open(id);
        long red = addTeam(id, "Red");
        long blue = addTeam(id, "Blue");
        join(id, 801, red);
        join(id, 802, blue);
        closeAndConfirm(id);
        finaliseTeams(id);

        assertEquals("done", finish(ops.startSetup(guild, reload(id), ACTOR, false)).state());
        assertEquals(List.of("Boss Night Participant", "Boss Night Organiser", "Boss Night Red", "Boss Night Blue"), server.madeRoles);
        assertEquals(List.of("boss-night-red", "boss-night-blue"), server.madeChannels);
        long redRole = roleId(id, EventSpace.teamKey(red));
        long blueRole = roleId(id, EventSpace.teamKey(blue));
        assertTrue(allows(overwriteFor("boss-night-red", redRole), Permission.MESSAGE_SEND));
        assertTrue(server.overwrites.get("boss-night-red").stream().noneMatch(o -> o[1] == blueRole), "the other team can't see it");
        assertTrue(server.overwrites.get("boss-night-red").stream().noneMatch(o -> o[1] == roleId(id, "participant")), "players in general can't see it");
        assertEquals(Set.of(801L), space.members(id).get(EventSpace.teamKey(red)));
        assertEquals(Set.of(802L), space.members(id).get(EventSpace.teamKey(blue)));
        assertEquals(Set.of(801L, 802L), space.members(id).get("participant"));

        // a team added later is the only thing the next setup makes
        long green = addTeam(id, "Green");
        server.madeRoles.clear();
        server.madeChannels.clear();
        assertEquals("done", finish(ops.startSetup(guild, reload(id), ACTOR, false)).state());
        assertEquals(List.of("Boss Night Green"), server.madeRoles);
        assertEquals(List.of("boss-night-green"), server.madeChannels);
        assertTrue(space.roles(id).stream().anyMatch(r -> r.key().equals(EventSpace.teamKey(green))));
    }

    @Test
    void aChosenExistingChannelIsUsedAsItIsAndBecomesTheBoardOrProofChannel() throws Exception {
        GuildChannel proof = Mockito.mock(TextChannel.class);
        Mockito.when(proof.getIdLong()).thenReturn(5555L);
        Mockito.when(proof.getName()).thenReturn("our-proof");
        Mockito.when(proof.getType()).thenReturn(ChannelType.TEXT);
        server.channels.put(5555L, proof);
        long id = event(BossNightTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"boss\":\"Araxxor\",\"channels\": {\"enabled\": true, \"slots\": {\"review\": {\"use\": \"existing\", \"channelId\": \"5555\"}}}}");
        open(id);
        join(id, 701, null);
        closeAndConfirm(id);

        assertEquals("done", finish(ops.startSetup(guild, reload(id), ACTOR, false)).state());
        assertFalse(server.madeChannels.contains("our-proof"), "an existing channel isn't made again");
        assertFalse(server.overwrites.containsKey("our-proof"), "and its permissions are left alone");
        EventSpaceRepository.ChannelRow row = space.channels(id).stream().filter(c -> c.key().equals("review")).findFirst().orElseThrow();
        assertFalse(row.owned());
        assertEquals(5555L, row.channelId());
        assertEquals(5555L, EventSpaces.reviewChannelOf(reload(id), space), "the engine posts proof there (the hub keeps no channel ids: the bot's record says)");
    }

    // ---------- who holds the roles ----------

    @Test
    void aPlayerWhoLeavesLosesTheRoleAndAPlayerNotInTheServerGetsItWhenTheyJoin() throws Exception {
        server.notInServer.add(704L);
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"channels\": {\"enabled\": true}}");
        open(id);
        for (long user = 701; user <= 704; user++) join(id, user, null);
        closeAndConfirm(id);
        EventSpaceOps.Job job = finish(ops.startSetup(guild, reload(id), ACTOR, false));
        assertEquals("done", job.state(), job.message());
        assertEquals(Set.of(701L, 702L, 703L), space.members(id).get("participant"), "the player who isn't in the server isn't given a role");
        assertTrue(job.message().startsWith("The roles and channels are set up."), job.message());

        leave(id, 702);
        assertEquals(0, ops.syncMembers(guild, reload(id)));
        assertEquals(List.of("702:Boss Night Participant"), server.taken);
        assertEquals(Set.of(701L, 703L), space.members(id).get("participant"));

        server.notInServer.remove(704L);
        assertEquals(0, ops.syncMembers(guild, reload(id)));
        assertEquals(Set.of(701L, 703L, 704L), space.members(id).get("participant"));
        assertTrue(server.given.contains("704:Boss Night Participant"));
        server.given.clear();
        assertEquals(0, ops.syncMembers(guild, reload(id)));
        assertTrue(server.given.isEmpty() && server.taken.size() == 1, "nothing changes when nothing changed");
    }

    @Test
    void whoIsInTheServerIsAskedAboutOnlyOnceAMinuteUntilSomeoneJoins() {
        server.notInServer.add(704L);
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"channels\": {\"enabled\": true}}");
        open(id);
        join(id, 701, null);
        join(id, 704, null);
        closeAndConfirm(id);

        var first = spaces.preview(guild, reload(id)).plan();
        var second = spaces.preview(guild, reload(id)).plan();
        assertTrue(first.warnings().stream().anyMatch(w -> w.contains("1 player hasn't joined this Discord server yet")), first.warnings().toString());
        assertEquals(first.warnings(), second.warnings());
        Mockito.verify(guild, Mockito.times(1)).retrieveMembersByIds(ArgumentMatchers.<Collection<Long>>any());

        server.notInServer.remove(704L);
        spaces.forget(G, 704);
        assertTrue(spaces.preview(guild, reload(id)).plan().warnings().stream().noneMatch(w -> w.contains("hasn't joined")), "after they join, they are no longer reported missing");
    }

    @Test
    void aTeamMemberMovedToAnotherTeamSwapsTeamRoles() throws Exception {
        long id = event(BossNightTemplate.KEY, ScoringMode.TEAM, "{\"boss\":\"Araxxor\",\"channels\": {\"enabled\": true}}");
        open(id);
        long red = addTeam(id, "Red");
        long blue = addTeam(id, "Blue");
        join(id, 801, red);
        join(id, 802, blue);
        closeAndConfirm(id);
        finaliseTeams(id);
        assertEquals("done", finish(ops.startSetup(guild, reload(id), ACTOR, false)).state());

        setTeam(id, 801, blue);
        assertEquals(0, ops.syncMembers(guild, reload(id)));
        assertTrue(server.taken.contains("801:Boss Night Red"), server.taken.toString());
        assertTrue(server.given.contains("801:Boss Night Blue"), server.given.toString());
        assertEquals(Set.of(801L, 802L), space.members(id).get(EventSpace.teamKey(blue)));
    }

    // ---------- taking it down ----------

    @Test
    void removingDeletesOnlyWhatWasMadeAndTellsTheHub() throws Exception {
        GuildChannel proof = Mockito.mock(TextChannel.class);
        Mockito.when(proof.getIdLong()).thenReturn(5555L);
        Mockito.when(proof.getName()).thenReturn("our-proof");
        Mockito.when(proof.getType()).thenReturn(ChannelType.TEXT);
        server.channels.put(5555L, proof);
        long id = event(BossNightTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"boss\":\"Araxxor\",\"channels\": {\"enabled\": true, \"slots\": {\"review\": {\"use\": \"existing\", \"channelId\": \"5555\"}}}}");
        open(id);
        join(id, 701, null);
        closeAndConfirm(id);
        assertEquals("done", finish(ops.startSetup(guild, reload(id), ACTOR, false)).state());

        assertTrue(events.setup(id).spaceApplied(), "the hub was told, so it refuses to delete the event meanwhile");

        EventSpaceOps.Job job = finish(ops.startRemoval(guild, reload(id)));
        assertEquals("done", job.state(), job.message());
        assertFalse(server.deleted.contains(5555L), "a channel that was only chosen is never deleted");
        assertEquals(Set.of(5555L), server.channels.keySet(), "the category and the channels made are gone, the chosen one is left");
        assertEquals(6, server.deleted.size(), "the category, three channels and two roles");
        assertTrue(space.roles(id).isEmpty() && space.channels(id).isEmpty() && space.members(id).isEmpty());
        assertFalse(events.setup(id).spaceApplied(), "and told again once everything is gone");
        Mockito.verify(events).clearSpace(G, id);
        assertNotNull(job.finishedAt());
    }

    @Test
    void removingWhenNothingWasMadeIsRefused() {
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"channels\": {\"enabled\": true}}");
        EventProblem none = assertThrows(EventProblem.class, () -> ops.startRemoval(guild, reload(id)));
        assertEquals(409, none.status());
    }
}
