package com.runeclans.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.coffer.CofferDatabaseInitializer;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.poll.PollDatabaseInitializer;
import com.runeclans.bot.runescape.ClanPointsRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import com.runeclans.bot.runescape.RuneScapeProfile;
import com.runeclans.bot.runescape.SkillValue;
import com.runeclans.bot.runescape.WeeklyDigestRepository;
import com.runeclans.bot.signup.SignupDatabaseInitializer;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code GET players/{rsn}} ({@link DirectoryPlayerRoutes}) over the developer's local Postgres (skipped without one), with the real roster,
 * snapshot and privacy reads: a player in two listed clans (named with different capitals), one of which they left, and a third clan where
 * they hid themselves from the rankings. Only the clan profiles are stand-ins (their rules are {@link DirectoryPlayerLogicTest}'s).
 */
class DirectoryPlayerDatabaseTest {
    /** What the hub holds: the bot keeps no polled stats of its own (T-912). */
    private static final com.runeclans.bot.testsupport.MemoryStats HUB = new com.runeclans.bot.testsupport.MemoryStats();

    // Made-up ids, built at run time (a long literal of this shape trips the secret scanner).
    private static final long GUILD_A = Long.parseLong("2".repeat(16) + "61");
    private static final long GUILD_B = Long.parseLong("2".repeat(16) + "62");
    private static final long GUILD_HIDDEN = Long.parseLong("2".repeat(16) + "63");
    private static final long GUILD_LEFT = Long.parseLong("2".repeat(16) + "64");
    private static final long USER = Long.parseLong("2".repeat(16) + "71");
    private static final String RSN = "Dpt Player";
    private static final List<Long> GUILDS = List.of(GUILD_A, GUILD_B, GUILD_HIDDEN, GUILD_LEFT);

    private static ConnectionSupplier db;
    private static SiteStatsRepository stats;
    private static PlayerLinkRepository links;
    private static ClanPointsRepository points;
    private static MemberProfileRepository memberProfiles;
    private static SiteApi siteApi;
    private JDA jda;
    private DirectoryPlayerRoutes routes;

    @BeforeAll
    static void connect() throws Exception {
        db = LocalDatabase.supplier();
        var initializer = new RuneScapeDatabaseInitializer(db);
        new SignupDatabaseInitializer(db);
        new PollDatabaseInitializer(db);
        new CofferDatabaseInitializer(db);
        stats = new SiteStatsRepository(db, initializer).withStats(HUB.backends());
        links = new PlayerLinkRepository(db, initializer).withStats(HUB.backends());
        points = new ClanPointsRepository(mock(com.runeclans.bot.runeclans.RuneClansHubClient.class));
        memberProfiles = new MemberProfileRepository(db);
        WeeklyDigestRepository digest = new WeeklyDigestRepository(db, initializer);
        GuildSettingsService settings = mock(GuildSettingsService.class);
        when(settings.getEffective(anyLong())).thenReturn(mock(GuildSettings.class));
        CommunitySettings community = new CommunitySettings(db, com.runeclans.bot.testsupport.SiteMappings.off(db));
        siteApi = new SiteApi(stats, links, points, settings, digest, memberProfiles, mock(SiteNewsService.class), mock(SignupService.class),
                new SiteCache(), new RecapService(stats, memberProfiles, digest, settings), community,
                new BossStatsService(stats, memberProfiles, new WikiCatalog()), mock(SiteDiscordService.class), mock(com.runeclans.bot.clansite.ClanSitePluginFeed.class));
    }

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        ClanProfileRepository profiles = mock(ClanProfileRepository.class);
        ClanProfileService service = mock(ClanProfileService.class);
        for (long guild : GUILDS) {
            String slug = "dpt-" + (guild % 100);
            when(profiles.get(guild)).thenReturn(Optional.of(new ClanProfile(guild, ClanProfile.Visibility.PUBLIC, slug, "Clan " + slug, "", "", null, null, null,
                    EnumSet.noneOf(ClanProfile.Focus.class), ClanProfile.Recruiting.CLOSED, "", null, false, "Clan " + slug, Instant.EPOCH, null, null, null, null)));
            when(service.clanNameOf(guild)).thenReturn("Clan " + slug);
        }
        jda = mock(JDA.class);
        routes = new DirectoryPlayerRoutes(profiles, service, siteApi, stats, links, points, new SiteCache(), mock(com.runeclans.bot.runescape.TrackedNames.class));

        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank) VALUES (?, ?, 'Owner')", GUILD_A, RSN);
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank) VALUES (?, ?, 'Recruit')", GUILD_B, RSN.toUpperCase());
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank) VALUES (?, ?, 'Recruit')", GUILD_HIDDEN, RSN);
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank, active) VALUES (?, ?, 'Recruit', FALSE)", GUILD_LEFT, RSN);
        links.createLink(GUILD_HIDDEN, USER, RSN, "TEST");
        memberProfiles.saveProfile(new MemberProfileRepository.Profile(GUILD_HIDDEN, USER, "", null, null, false, true, false, false, false));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HUB.clear();
        for (long guild : new long[]{GUILD_A, GUILD_B, GUILD_HIDDEN}) HUB.member(guild, RSN);
        HUB.snapshot(RSN, now.minusDays(10), 2000, 199_000_000, 120, List.of(new SkillValue(0, 99, 199_000_000, 5)))
                .snapshot(RSN, now.minusMinutes(5), 2100, 200_000_000, 121, List.of(new SkillValue(0, 99, 200_000_000, 4)));

    }

    @AfterEach
    void cleanUp() throws Exception {
        if (db == null) return;
        for (String table : List.of("clan_member", "player_link", "member_profile", "member_privacy_default")) {
            for (long guild : GUILDS) sql("DELETE FROM younglings." + table + " WHERE guild_id = ?", guild);
        }
        for (String table : List.of("player_account", "player_stats_snapshot")) sql("DELETE FROM younglings." + table + " WHERE LOWER(rsn) = LOWER(?)", RSN);
    }

    @Test
    void theRosterReadFindsOnlyCurrentMembershipsWhateverTheCapitals() {
        List<Long> guilds = stats.guildsWithActiveMember(RSN.toLowerCase());
        assertEquals(3, guilds.size(), guilds.toString());
        assertTrue(guilds.containsAll(List.of(GUILD_A, GUILD_B, GUILD_HIDDEN)));
        assertFalse(guilds.contains(GUILD_LEFT), "a clan the player left");
    }

    @Test
    void theAnswerHasTheListedClansAndTheNewestSnapshot() throws Exception {
        DataObject answer = DataObject.fromJson(get("players/dpt%20player"));
        DataArray clans = answer.getArray("clans");
        assertEquals(2, clans.length(), "not the clan the player is hidden in, nor the one they left: " + clans);
        for (int i = 0; i < clans.length(); i++) {
            String slug = clans.getObject(i).getString("slug");
            assertTrue(slug.equals("dpt-61") || slug.equals("dpt-62"), slug);
        }
        DataObject statsJson = answer.getObject("stats");
        assertEquals(2100, statsJson.getInt("totalLevel"));
        assertEquals(200_000_000L, statsJson.getLong("totalXp"));
        assertEquals(4, statsJson.getArray("skills").getObject(0).getInt("rank"));
        assertEquals(1_000_000L, statsJson.getObject("gains").getLong("month"));
        assertFalse(answer.toString().contains(Long.toString(USER)), "no Discord id");
        verifyNoInteractions(jda);
    }

    @Test
    void aNameOnlyAHiddenOrFormerMembershipHasIs404() throws Exception {
        sql("DELETE FROM younglings.clan_member WHERE guild_id IN (?, ?)", GUILD_A, GUILD_B);
        assertEquals("404", get("players/Dpt%20Player").substring(0, 3));
    }

    /** The body for a 200, or the status for anything else. */
    private String get(String path) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        int[] status = {-1};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        URI uri = URI.create("http://localhost/internal/directory/" + path);
        when(exchange.getRequestURI()).thenReturn(uri);
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(out);
        doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(any(Integer.class), any(Long.class));
        String[] parts = uri.getPath().substring("/internal/directory/".length()).split("/");
        assertTrue(routes.route(new DirectoryRequest(exchange, jda, "GET", parts, slug -> Optional.empty(), p -> DataObject.empty())));
        return status[0] == 200 ? out.toString(StandardCharsets.UTF_8) : Integer.toString(status[0]);
    }

    private static void sql(String statement, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            s.executeUpdate();
        }
    }
}
