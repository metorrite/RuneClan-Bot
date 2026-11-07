package com.runeclans.bot.internal;

import com.runeclans.bot.access.AccessArea;
import com.runeclans.bot.access.AccessGrantRepository;
import com.runeclans.bot.access.AccessGrantService;
import com.runeclans.bot.access.GateSource;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.ClanRecruitingRepository;
import com.runeclans.bot.clansite.BotlessSiteRepository;
import com.runeclans.bot.clansite.ClanSiteLayoutRepository;
import com.runeclans.bot.clansite.ClanSiteRepository;
import com.runeclans.bot.clansite.ClanSiteService;
import com.runeclans.bot.clansite.SiteConnectionStore;
import com.runeclans.bot.clansite.SiteDiscordSettings;
import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.clansite.SiteRoleRepository;
import com.runeclans.bot.beta.BetaDatabaseInitializer;
import com.runeclans.bot.beta.BetaGuildRepository;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.configure.GuildSettingsDatabaseInitializer;
import com.runeclans.bot.configure.GuildSettingsRepository;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.premium.Premium;
import com.runeclans.bot.runescape.ClanSyncScheduler;
import com.runeclans.bot.runescape.ClanSyncService;
import com.runeclans.bot.runescape.PlayerLinkService;
import com.runeclans.bot.testsupport.LocalDatabase;
import com.runeclans.bot.upload.ImageUploadService;
import com.runeclans.bot.upload.UploadedImageDatabaseInitializer;
import com.runeclans.bot.upload.UploadedImageRepository;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A clan website for a server the bot is NOT in, end to end: real HTTP into {@link InternalApiServer}, a JDA that knows no such server, and the
 * real site builder over real SQL. Only signed facts from someone on the beta list get in; owners and admins come from the facts, editors by
 * user id; Discord-only routes say they need the bot; and a server the bot is in never looks at the facts. Made-up ids throughout.
 */
class BotlessSiteEndToEndDatabaseTest {
    private static final long BASE = Long.parseLong("9".repeat(17));
    private static final long HOME = BASE + 701;
    private static final long BOTLESS = BASE + 702;
    private static final long ADMIN = BASE + 711;
    private static final long EDITOR = BASE + 712;
    private static final long STRANGER = BASE + 713;
    private static final String SECRET = "e2e-" + "s".repeat(24);

    private static ConnectionSupplier db;
    private static int port;
    private static TicketAdminApi ticketAdmin;
    private static AccessGrantRepository grants;
    private static AccessGrantService grantService;
    private static BotlessSiteRepository botless;
    private static GuildSettingsService settings;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        db = LocalDatabase.supplier();
        clean();
        ClanSiteRepository sites = new ClanSiteRepository(db);
        ClanSiteLayoutRepository layouts = new ClanSiteLayoutRepository(db, sites);
        BetaGuildRepository beta = new BetaGuildRepository(db, new BetaDatabaseInitializer(db));
        botless = new BotlessSiteRepository(db);
        BotConfig config = mock(BotConfig.class);
        when(config.getGuildId()).thenReturn(HOME);
        when(config.getInternalApiSecret()).thenReturn(SECRET);
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        when(config.getInternalApiPort()).thenReturn(port);
        settings = new GuildSettingsService(new GuildSettingsRepository(db, new GuildSettingsDatabaseInitializer(db)), config);
        ClanSiteService siteService = new ClanSiteService(sites, GateSource.ownTables(beta::all, null, null), config, botless);
        ClanSyncService clanSync = mock(ClanSyncService.class);
        when(clanSync.getClanName(anyLong())).thenAnswer(i -> settings.getEffective(i.getArgument(0)).clanName());
        ClanProfileService profiles = new ClanProfileService(new ClanProfileRepository(db), clanSync, mock(PlayerLinkService.class), GateSource.ownTables(null, null, null));
        BotOwners owners = mock(BotOwners.class);
        SiteRoleGuard roles = new SiteRoleGuard(new SiteRoleRepository(db), mock(DashboardAccess.class), settings, mock(PlayerLinkService.class),
                com.runeclans.bot.access.OverallAdmins.of(() -> com.runeclans.bot.access.OverallAdmins.HubList.EMPTY, owners));
        ClanSiteAdminApi clanSite = new ClanSiteAdminApi(sites, layouts, siteService, profiles, owners, mock(Premium.class), roles,
                new SiteDiscordService(new SiteConnectionStore(db, new SiteDiscordSettings(db), new SiteNewsService(db, com.runeclans.bot.testsupport.SiteMappings.off(db)), com.runeclans.bot.testsupport.SiteMappings.off(db)), new SiteDiscordSettings(db),
                        new SiteNewsService(db, com.runeclans.bot.testsupport.SiteMappings.off(db)), mock(MemberProfileRepository.class), new SiteCache(), com.runeclans.bot.hubsites.HubSites.off()), mock(SiteAddressRoutes.class),
                mock(SiteMemberOptionsAdminApi.class), mock(SiteMembersAdmin.class), new SiteInviteRoutes(settings, new ClanRecruitingRepository(db), profiles));
        ImageUploadService images = new ImageUploadService(new UploadedImageRepository(db, new UploadedImageDatabaseInitializer(db)));
        grants = new AccessGrantRepository(db);
        grantService = new AccessGrantService(GateSource.ownTables(null, grants::all, null));
        AdminToolsStore audit = new AdminToolsStore(db);
        BotlessAdminApi botlessAdmin = new BotlessAdminApi(clanSite, new ClanProfileAdminApi(new ClanProfileRepository(db), profiles),
                new SitePictureAdminApi(images, mock(Premium.class), roles), new ImageAdminApi(images), mock(AdminOpsApi.class), audit, roles, botless, settings,
                grantService, owners, mock(ClanSyncScheduler.class));

        ticketAdmin = mock(TicketAdminApi.class);
        org.mockito.Mockito.doAnswer(i -> {
            InternalApiServer.sendJson(i.getArgument(0), 200, DataObject.empty().put("dashboard", true));
            return null;
        }).when(ticketAdmin).handle(any(), any());
        InternalApiServer server = new InternalApiServer(config, ticketAdmin, mock(SiteApi.class), mock(DirectoryApi.class), mock(PluginApi.class),
                mock(MemberApi.class), images, mock(com.runeclans.bot.tracking.CitadelWinners.class), mock(FormsPublicApi.class), List.of(), botlessAdmin,
                mock(com.runeclans.bot.discord.jobs.DiscordOutbox.class), mock(OverallAdminApi.class), com.runeclans.bot.hubsites.HubSites.off());
        JDA jda = mock(JDA.class);
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        Guild home = mock(Guild.class);
        when(home.getIdLong()).thenReturn(HOME);
        when(jda.getGuildById(HOME)).thenReturn(home); // the only server the bot is in; every other id is null
        InjectedJDAEvent event = mock(InjectedJDAEvent.class);
        when(event.getJda()).thenReturn(jda);
        server.onJdaReady(event);
    }

    @BeforeEach
    void before() throws Exception {
        reset();
    }

    @AfterEach
    void after() throws Exception {
        reset();
    }

    private void reset() throws Exception {
        if (db == null) return;
        clean();
        grantService.listChanged();
        // the bot-less list is remembered in memory: forget it through the repository itself
        botless.remove(BOTLESS);
    }

    private static void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            for (String table : new String[]{"clan_site_layout_version", "clan_site_layout", "clan_site_block", "clan_site", "clan_profile", "clan_slug_hold",
                    "clan_site_roles", "botless_site", "guild_settings", "admin_audit", "site_connection", "site_connection_channel", "clan_recruiting"}) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)")) {
                    s.setLong(1, BOTLESS);
                    s.setLong(2, HOME);
                    s.executeUpdate();
                } catch (java.sql.SQLException e) {
                    // a table another test run never created
                }
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.access_grant WHERE user_id IN (?, ?, ?)")) {
                s.setLong(1, ADMIN);
                s.setLong(2, EDITOR);
                s.setLong(3, STRANGER);
                s.executeUpdate();
            } catch (java.sql.SQLException e) {
                // not created yet
            }
        }
    }

    private void onBeta(long userId) {
        grants.add(AccessArea.SITE_BUILDER_BETA, userId, null, null, "e2e", ADMIN, null);
        grantService.listChanged();
    }

    private record Answer(int status, DataObject body) {}

    /** {@code facts}: null for none; otherwise the signed headers for that person in {@code factsGuild}. */
    private Answer call(String method, String path, String body, long guildId, Long userId, boolean admin, boolean owner, long factsGuild, String signatureOverride) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/admin/" + path)).timeout(java.time.Duration.ofSeconds(20))
                .header("X-Internal-Secret", SECRET).header("X-Guild-Id", Long.toString(guildId))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (userId != null) {
            long now = Instant.now().getEpochSecond();
            String actor = ActorFacts.encode(ActorFactsTest.facts(userId, factsGuild, admin, owner, now - 5, now + 240));
            request.header("X-Actor-Id", Long.toString(userId)).header(ActorFacts.HEADER, actor)
                    .header(ActorFacts.SIG_HEADER, signatureOverride != null ? signatureOverride : ActorFacts.sign(actor, SECRET));
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        return new Answer(response.statusCode(), text == null || text.isBlank() ? DataObject.empty() : DataObject.fromJson(text));
    }

    private Answer as(long userId, boolean admin, String method, String path, String body) throws Exception {
        return call(method, path, body, BOTLESS, userId, admin, false, BOTLESS, null);
    }

    @Test
    void missingTamperedOrWrongServerFactsAreRefused() throws Exception {
        onBeta(ADMIN);
        Answer none = call("GET", "clan-site", null, BOTLESS, null, false, false, BOTLESS, null);
        assertEquals(403, none.status());
        assertTrue(none.body().getBoolean("botless"));
        assertEquals(403, call("GET", "clan-site", null, BOTLESS, ADMIN, true, false, BOTLESS, "0".repeat(64)).status(), "a bad signature");
        Answer wrong = call("GET", "clan-site", null, BOTLESS, ADMIN, true, false, BOTLESS + 1, null);
        assertEquals(403, wrong.status());
        assertTrue(wrong.body().getString("error").contains("different server"));
        assertFalse(botless.contains(BOTLESS), "nothing is set up for a refused request");
    }

    @Test
    void someoneNotOnTheBetaListCannotManageIt() throws Exception {
        Answer who = as(STRANGER, true, "GET", "whoami", null);
        assertEquals(200, who.status());
        assertFalse(who.body().getBoolean("allowed"));
        assertTrue(who.body().getBoolean("notOnBeta"));
        Answer site = as(STRANGER, true, "GET", "clan-site", null);
        assertEquals(403, site.status());
        assertTrue(site.body().getBoolean("notOnBeta"));
    }

    @Test
    void aBetaAdminCreatesEditsAndPublishesTheSite() throws Exception {
        onBeta(ADMIN);
        Answer who = as(ADMIN, true, "GET", "whoami", null);
        assertTrue(who.body().getBoolean("allowed"));
        assertEquals("ADMIN", who.body().getString("tier"));
        assertTrue(who.body().getBoolean("botless"));

        Answer site = as(ADMIN, true, "GET", "clan-site", null);
        assertEquals(200, site.status(), site.body().toString());
        assertTrue(site.body().getBoolean("botless"));
        assertTrue(site.body().getBoolean("inBeta"), "a bot-less site set up by someone on the beta list is in the builder beta");
        assertTrue(botless.contains(BOTLESS));
        assertEquals("admin", site.body().getObject("you").getString("role"));

        assertEquals(200, as(ADMIN, true, "PUT", "clan-site/clan-name", "{\"clanName\":\"  Botless   Clan \"}").status());
        assertEquals("Botless Clan", settings.getEffective(BOTLESS).clanName(), "stored as the server's clan name, which the stats and clan sync read");
        assertEquals(400, as(ADMIN, true, "PUT", "clan-site/clan-name", "{\"clanName\":\"<script>\"}").status());

        Answer profile = as(ADMIN, true, "PUT", "clan-profile", "{\"displayName\":\"Botless Clan\",\"slug\":\"botless-e2e-702\",\"visibility\":\"UNLISTED\"}");
        assertEquals(200, profile.status(), profile.body().toString());
        assertEquals("botless-e2e-702", profile.body().getString("slug"));

        assertEquals(200, as(ADMIN, true, "PUT", "clan-site", "{\"enabled\":true}").status());
        Answer published = as(ADMIN, true, "POST", "clan-site/publish", "{}");
        assertEquals(200, published.status(), published.body().toString());
        assertTrue(published.body().getObject("site").getBoolean("published"));
        assertTrue(audited("clan-site/publish"), "every write is in the server's audit log");
    }

    @Test
    void editorsAreNamedByUserIdAndDiscordRolesAreUnavailable() throws Exception {
        onBeta(ADMIN);
        onBeta(EDITOR);
        Answer roles = as(ADMIN, true, "GET", "clan-site/roles", null);
        assertEquals(200, roles.status());
        assertFalse(roles.body().getObject("discordRoles").getBoolean("available"));
        assertTrue(roles.body().getObject("discordRoles").getString("reason").contains("unavailable without the bot"));

        Answer withRoles = as(ADMIN, true, "PUT", "clan-site/roles", "{\"editorRoleIds\":[\"" + BASE + "\"]}");
        assertEquals(400, withRoles.status(), "no Discord roles can be picked without the bot");

        assertEquals(403, as(EDITOR, false, "GET", "clan-site/layout", null).status(), "not an editor yet");
        Answer saved = as(ADMIN, true, "PUT", "clan-site/roles", "{\"editorUserIds\":[\"" + EDITOR + "\"]}");
        assertEquals(200, saved.status(), saved.body().toString());
        assertEquals(DataArray.empty().add(Long.toString(EDITOR)).toString(), saved.body().getArray("editorUserIds").toString());

        Answer layout = as(EDITOR, false, "GET", "clan-site/layout", null);
        assertEquals(200, layout.status(), layout.body().toString());
        assertEquals("editor", layout.body().getObject("you").getString("role"));
        assertEquals(403, as(EDITOR, false, "PUT", "clan-site", "{\"enabled\":true}").status(), "the site's settings are for admins");
        assertEquals(403, as(EDITOR, false, "GET", "clan-site/roles", null).status());
    }

    @Test
    void connectionsWorkWithoutTheBotButOnlyAServerAdminConnects() throws Exception {
        onBeta(ADMIN);
        onBeta(EDITOR);
        Answer state = as(ADMIN, true, "GET", "clan-site/connections", null);
        assertEquals(200, state.status(), state.body().toString());
        assertTrue(state.body().getObject("server").getBoolean("connected"));
        assertFalse(state.body().getObject("server").getBoolean("botPresent"));
        assertFalse(state.body().getObject("provider").getBoolean("ready"), "nothing can be supplied without the bot");
        assertEquals(0, state.body().getObject("feed").getArray("channels").length());

        Answer saved = as(ADMIN, true, "PUT", "clan-site/connections", "{\"online\":{\"mode\":\"names+avatars\",\"limit\":12},\"events\":{\"enabled\":false}}");
        assertEquals(200, saved.status(), saved.body().toString());
        assertEquals("names+avatars", saved.body().getObject("online").getString("mode"));
        assertEquals(12, saved.body().getObject("online").getInt("limit"));
        assertFalse(saved.body().getObject("events").getBoolean("enabled"));
        assertEquals(409, as(ADMIN, true, "PUT", "clan-site/connections", "{\"feed\":{\"channels\":[{\"channelId\":\"" + BASE + "\"}]}}").status(),
                "channels need the bot");

        assertEquals(200, as(ADMIN, true, "POST", "clan-site/connections/disconnect", "{}").status());
        assertFalse(as(ADMIN, true, "GET", "clan-site/connections", null).body().getObject("server").getBoolean("connected"));
        // the editor isn't a site admin; and only someone Discord says runs the server may connect it
        assertEquals(403, as(EDITOR, false, "POST", "clan-site/connections/connect", "{\"guildId\":\"" + BOTLESS + "\"}").status());
        assertEquals(400, as(ADMIN, true, "POST", "clan-site/connections/connect", "{\"guildId\":\"" + HOME + "\"}").status(), "only the site's own server");
        Answer back = as(ADMIN, true, "POST", "clan-site/connections/connect", "{\"guildId\":\"" + BOTLESS + "\"}");
        assertEquals(200, back.status(), back.body().toString());
        assertTrue(back.body().getObject("server").getBoolean("connected"));
        assertEquals(Long.toString(ADMIN), back.body().getObject("server").getString("connectedBy"));
        assertEquals("names+avatars", back.body().getObject("online").getString("mode"), "the features were kept");
    }

    @Test
    void aSiteAdminSetsTheDiscordInviteTheSiteShows() throws Exception {
        onBeta(ADMIN);
        onBeta(EDITOR);
        Answer none = as(ADMIN, true, "GET", "clan-site/connections/invite", null);
        assertEquals(200, none.status(), none.body().toString());
        assertTrue(none.body().isNull("inviteUrl"));
        assertTrue(none.body().isNull("joinLink"));

        assertEquals(400, as(ADMIN, true, "PUT", "clan-site/connections/invite", "{\"inviteUrl\":\"https://example.com/join\"}").status(), "only a Discord invite");
        Answer saved = as(ADMIN, true, "PUT", "clan-site/connections/invite", "{\"inviteUrl\":\"discord.com/invite/abc123\"}");
        assertEquals(200, saved.status(), saved.body().toString());
        assertEquals("https://discord.gg/abc123", saved.body().getString("inviteUrl"), "stored in its short form");
        assertEquals("https://discord.gg/abc123", saved.body().getString("joinLink"), "the typed invite is the one the site shows");
        assertEquals("https://discord.gg/abc123", settings.getEffective(BOTLESS).inviteUrl());

        assertEquals(403, as(EDITOR, false, "PUT", "clan-site/connections/invite", "{\"inviteUrl\":null}").status(), "admins only");
        Answer cleared = as(ADMIN, true, "PUT", "clan-site/connections/invite", "{\"inviteUrl\":\"\"}");
        assertEquals(200, cleared.status());
        assertTrue(cleared.body().isNull("inviteUrl"));
    }

    @Test
    void discordOnlyRoutesSayTheyNeedTheBot() throws Exception {
        onBeta(ADMIN);
        Answer structure = as(ADMIN, true, "GET", "structure", null);
        assertEquals(200, structure.status());
        assertTrue(structure.body().getBoolean("botless"));
        assertEquals(0, structure.body().getArray("roles").length());
        for (String path : new String[]{"polls", "ticket/panels", "welcome", "clan-profile/verify"}) {
            Answer answer = as(ADMIN, true, "GET", path, null);
            assertEquals(409, answer.status(), path);
            assertTrue(answer.body().getBoolean("needsBot"), path);
        }
    }

    @Test
    void aServerTheBotIsInKeepsTodaysPathAndIgnoresFacts() throws Exception {
        onBeta(ADMIN);
        Answer answer = call("GET", "clan-site", null, HOME, ADMIN, true, true, HOME, null);
        assertTrue(answer.body().getBoolean("dashboard"), "the dashboard answered, not the bot-less routes");
        verify(ticketAdmin).handle(any(), org.mockito.ArgumentMatchers.argThat(g -> g != null && g.getIdLong() == HOME));
        assertFalse(botless.contains(HOME));
        verify(ticketAdmin, never()).handleGuildList(any(), any());
    }

    /** A request that names no server (no {@code X-Guild-Id}, no facts), as the website's {@code betaTester} call sends it. */
    private Answer noServer(String method, String path, Long actorId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/admin/" + path)).timeout(java.time.Duration.ofSeconds(20))
                .header("X-Internal-Secret", SECRET).method(method, HttpRequest.BodyPublishers.noBody());
        if (actorId != null) request.header("X-Actor-Id", Long.toString(actorId));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.body().isBlank() ? DataObject.empty() : DataObject.fromJson(response.body()));
    }

    @Test
    void betaTestersMeAnswersAnyoneSignedInWithoutAServer() throws Exception {
        org.mockito.Mockito.clearInvocations(ticketAdmin);
        Answer stranger = noServer("GET", "beta-testers/me", STRANGER);
        assertEquals(200, stranger.status(), stranger.body().toString());
        assertFalse(stranger.body().getBoolean("betaTester"));
        onBeta(ADMIN);
        assertTrue(noServer("GET", "beta-testers/me", ADMIN).body().getBoolean("betaTester"));
        assertFalse(noServer("GET", "beta-testers/me", null).body().getBoolean("betaTester"), "nobody signed in is no");
        assertEquals(405, noServer("POST", "beta-testers/me", ADMIN).status());
        verify(ticketAdmin, never()).handle(any(), any());
    }

    @Test
    void layoutAndRolesSayBotPresentFalseAndTakeEditorUserIds() throws Exception {
        onBeta(ADMIN);
        Answer layout = as(ADMIN, true, "GET", "clan-site/layout", null);
        assertEquals(200, layout.status(), layout.body().toString());
        assertFalse(layout.body().getBoolean("botPresent", true));
        assertTrue(layout.body().getBoolean("botless"));
        Answer roles = as(ADMIN, true, "GET", "clan-site/roles", null);
        assertFalse(roles.body().getBoolean("botPresent", true));
        assertTrue(roles.body().getBoolean("botless"));
        assertEquals(0, roles.body().getArray("editorUserIds").length());

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < ClanSiteAdminApi.MAX_ROLES_PER_LIST + 1; i++) many.append(i == 0 ? "" : ",").append('"').append(BASE + 800 + i).append('"');
        assertEquals(400, as(ADMIN, true, "PUT", "clan-site/roles", "{\"editorUserIds\":[" + many + "]}").status(), "at most 20 editors by user id");
        Answer saved = as(ADMIN, true, "PUT", "clan-site/roles", "{\"editorUserIds\":[\"" + EDITOR + "\"]}");
        assertEquals(200, saved.status(), saved.body().toString());
        assertFalse(saved.body().getBoolean("botPresent", true));
        assertEquals(1, saved.body().getArray("editorUserIds").length());
    }

    @Test
    void putSetupWithOnlyTheClanNameWorksWithoutTheBot() throws Exception {
        onBeta(ADMIN);
        onBeta(EDITOR);
        Answer saved = as(ADMIN, true, "PUT", "setup", "{\"clanName\":\"Lantern  Hollow\"}");
        assertEquals(200, saved.status(), saved.body().toString());
        assertEquals("Lantern Hollow", saved.body().getString("clanName"));
        assertEquals("Lantern Hollow", settings.getEffective(BOTLESS).clanName(), "the same name clan-site/clan-name stores");
        assertEquals("Lantern Hollow", as(ADMIN, true, "GET", "clan-site/clan-name", null).body().getString("clanName"));
        assertEquals("Lantern Hollow", as(ADMIN, true, "GET", "setup", null).body().getString("clanName"));
        assertTrue(audited("setup"));

        assertEquals(400, as(ADMIN, true, "PUT", "setup", "{\"clanName\":\"<script>\"}").status());
        Answer more = as(ADMIN, true, "PUT", "setup", "{\"clanName\":\"Other\",\"clanEnabled\":true}");
        assertEquals(409, more.status(), "any other setting needs the bot");
        assertTrue(more.body().getBoolean("needsBot"));
        assertEquals(403, as(EDITOR, false, "PUT", "setup", "{\"clanName\":\"Other\"}").status(), "the clan name is for the site's admins");
        assertEquals("Lantern Hollow", settings.getEffective(BOTLESS).clanName());
    }

    private static boolean audited(String path) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.admin_audit WHERE guild_id = ? AND path = ?")) {
            s.setLong(1, BOTLESS);
            s.setString(2, path);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}
