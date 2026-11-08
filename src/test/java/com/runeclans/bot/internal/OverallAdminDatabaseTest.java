package com.runeclans.bot.internal;

import com.runeclans.bot.access.AccessArea;
import com.runeclans.bot.access.AccessGrantRepository;
import com.runeclans.bot.access.AccessGrantService;
import com.runeclans.bot.access.GateSource;
import com.runeclans.bot.access.OverallAdmins;
import com.runeclans.bot.beta.BetaDatabaseInitializer;
import com.runeclans.bot.beta.BetaGuildRepository;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clansite.BotlessSiteRepository;
import com.runeclans.bot.clansite.ClanSiteLayoutRepository;
import com.runeclans.bot.clansite.ClanSiteRepository;
import com.runeclans.bot.clansite.ClanSiteService;
import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.clansite.SiteRoleRepository;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.configure.GuildSettingsDatabaseInitializer;
import com.runeclans.bot.configure.GuildSettingsRepository;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.premium.Premium;
import com.runeclans.bot.premium.PremiumAccessRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The overall RuneClans admins (N5), end to end over real HTTP into {@link InternalApiServer} and real SQL: the built-in admin (the bot's owner,
 * as {@link BotOwners} decides; no Discord id in the code), adding and removing admins, the premium access list, and an overall admin opening
 * another clan's site admin. Also the site's own admin access: people by user id and the staff-server rule from signed {@link ServerFacts}.
 * Made-up ids throughout, built at runtime.
 */
class OverallAdminDatabaseTest {
    private static final long BASE = Long.parseLong("9".repeat(17));
    private static final long HOME = BASE + 901;
    private static final long SITE = BASE + 902;
    private static final long STAFF_SERVER = BASE + 903;
    private static final long BUILT_IN = BASE + 911;
    private static final long ADDED = BASE + 912;
    private static final long SITE_OWNER = BASE + 913;
    private static final long SITE_ADMIN = BASE + 914;
    private static final long STAFFER = BASE + 915;
    private static final long STRANGER = BASE + 916;
    private static final String SECRET = "n5-" + "s".repeat(24);

    private static ConnectionSupplier db;
    private static int port;
    private static AccessGrantRepository grants;
    private static AccessGrantService grantService;
    private static BotlessSiteRepository botless;
    private static OverallAdmins overall;
    /** The hub's added admins, as the fake hub answers them. */
    private static final Set<Long> HUB_ADDED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        db = LocalDatabase.supplier();
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
        GuildSettingsService settings = new GuildSettingsService(new GuildSettingsRepository(db, new GuildSettingsDatabaseInitializer(db)), config);
        ClanSiteService siteService = new ClanSiteService(sites, GateSource.ownTables(beta::all, null, null), config, botless);
        ClanSyncService clanSync = mock(ClanSyncService.class);
        ClanProfileService profiles = new ClanProfileService(new ClanProfileRepository(db), clanSync, mock(PlayerLinkService.class), GateSource.ownTables(null, null, null));
        // the built-in admin is the bot's owner as BotOwners decides
        BotOwners owners = mock(BotOwners.class);
        when(owners.isOwner(any(), anyLong())).thenReturn(false);
        when(owners.isOwner(any(), eq(BUILT_IN))).thenReturn(true);
        when(owners.known(any())).thenReturn(Set.of(BUILT_IN));
        // the hub keeps the added admins (T-981): a fake hub list the tests change
        overall = OverallAdmins.of(() -> new OverallAdmins.HubList(Set.of(), Set.copyOf(HUB_ADDED)), owners);
        SiteRoleGuard roles = new SiteRoleGuard(new SiteRoleRepository(db), mock(DashboardAccess.class), settings, mock(PlayerLinkService.class), overall);
        ClanSiteAdminApi clanSite = new ClanSiteAdminApi(sites, layouts, siteService, profiles, owners, mock(Premium.class), roles,
                mock(SiteDiscordService.class), mock(SiteAddressRoutes.class), mock(SiteMemberOptionsAdminApi.class), mock(SiteMembersAdmin.class), mock(SiteInviteRoutes.class));
        ImageUploadService images = new ImageUploadService(new UploadedImageRepository(db, new UploadedImageDatabaseInitializer(db)));
        grants = new AccessGrantRepository(db);
        grantService = new AccessGrantService(GateSource.ownTables(null, grants::all, null));
        AdminToolsStore audit = new AdminToolsStore(db);
        BotlessAdminApi botlessAdmin = new BotlessAdminApi(clanSite, new ClanProfileAdminApi(new ClanProfileRepository(db), profiles),
                new SitePictureAdminApi(images, mock(Premium.class), roles), new ImageAdminApi(images), mock(AdminOpsApi.class), audit, roles, botless, settings,
                grantService, owners, mock(ClanSyncScheduler.class));
        OverallAdminApi overallApi = new OverallAdminApi(overall, new PremiumAccessRepository(db), mock(SiteOwnerAdminApi.class),
                new SiteBuilderBetaAdminApi(grants, grantService, owners), mock(OverallOpsRoutes.class), audit, config);
        InternalApiServer server = new InternalApiServer(config, mock(TicketAdminApi.class), mock(SiteApi.class), mock(DirectoryApi.class), mock(PluginApi.class),
                mock(MemberApi.class), images, mock(com.runeclans.bot.tracking.CitadelWinners.class), mock(FormsPublicApi.class), List.of(), botlessAdmin,
                mock(com.runeclans.bot.discord.jobs.DiscordOutbox.class), overallApi, com.runeclans.bot.hubsites.HubSites.off());
        JDA jda = mock(JDA.class);
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        Guild home = mock(Guild.class);
        when(home.getIdLong()).thenReturn(HOME);
        when(jda.getGuildById(HOME)).thenReturn(home);
        InjectedJDAEvent event = mock(InjectedJDAEvent.class);
        when(event.getJda()).thenReturn(jda);
        server.onJdaReady(event);
    }

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        if (db == null) return;
        try (Connection c = db.getConnection()) {
            for (String table : new String[]{"clan_site_layout", "clan_site", "clan_profile", "clan_site_roles", "botless_site", "guild_settings", "admin_audit"}) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)")) {
                    s.setLong(1, SITE);
                    s.setLong(2, HOME);
                    s.executeUpdate();
                } catch (java.sql.SQLException e) {
                    // a table another test run never created
                }
            }
            long[] people = {BUILT_IN, ADDED, SITE_OWNER, SITE_ADMIN, STAFFER, STRANGER};
            for (long id : people) {
                for (String sql : new String[]{"DELETE FROM younglings.access_grant WHERE user_id = ?",
                        "DELETE FROM younglings.premium_access_grant WHERE target_id = ?"}) {
                    try (PreparedStatement s = c.prepareStatement(sql)) {
                        s.setLong(1, id);
                        s.executeUpdate();
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.premium_access_grant WHERE target_id = ?")) {
                s.setLong(1, SITE);
                s.executeUpdate();
            }
        }
        HUB_ADDED.clear();
        if (overall != null) overall.forget();
        if (grantService != null) grantService.listChanged();
        if (botless != null) botless.remove(SITE);
    }

    private record Answer(int status, DataObject body) {}

    /** A no-server call to the overall panel, as {@code actor}. */
    private Answer overall(String method, String path, String body, long actor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/admin/overall/" + path))
                .timeout(java.time.Duration.ofSeconds(20)).header("X-Internal-Secret", SECRET).header("X-Actor-Id", Long.toString(actor))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return send(request);
    }

    /** A call about the bot-less site, with signed actor facts (and, when given, signed staff servers). */
    private Answer site(String method, String path, String body, long actor, boolean owner, Map<Long, Long> staff) throws Exception {
        long now = Instant.now().getEpochSecond();
        String facts = ActorFacts.encode(ActorFactsTest.facts(actor, SITE, owner, owner, now - 5, now + 240));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/admin/" + path))
                .timeout(java.time.Duration.ofSeconds(20)).header("X-Internal-Secret", SECRET).header("X-Guild-Id", Long.toString(SITE))
                .header("X-Actor-Id", Long.toString(actor)).header(ActorFacts.HEADER, facts).header(ActorFacts.SIG_HEADER, ActorFacts.sign(facts, SECRET))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (staff != null) {
            DataObject servers = DataObject.empty();
            staff.forEach((id, bits) -> servers.put(Long.toString(id), Long.toString(bits)));
            String signed = ServerFacts.encode(DataObject.empty().put("v", 1).put("userId", Long.toString(actor)).put("servers", servers)
                    .put("iat", now - 5).put("exp", now + 240));
            request.header(ServerFacts.HEADER, signed).header(ServerFacts.SIG_HEADER, ActorFacts.sign(signed, SECRET));
        }
        return send(request.build());
    }

    private Answer send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        return new Answer(response.statusCode(), text == null || text.isBlank() ? DataObject.empty() : DataObject.fromJson(text));
    }

    /** A built-in admin adds {@code id} on the website: the hub's list changes, and the bot reads it again. */
    private static void hubAdds(long id) {
        HUB_ADDED.add(id);
        overall.forget();
    }

    private static void hubRemoves(long id) {
        HUB_ADDED.remove(id);
        overall.forget();
    }

    @Test
    void theBuiltInAdminIsAlwaysAnAdmin() throws Exception {
        Answer who = overall("GET", "whoami", null, BUILT_IN);
        assertEquals(200, who.status());
        assertTrue(who.body().getBoolean("overallAdmin"));
        assertTrue(who.body().getBoolean("builtIn"));

        Answer stranger = overall("GET", "whoami", null, STRANGER);
        assertEquals(200, stranger.status(), "whoami answers everyone");
        assertFalse(stranger.body().getBoolean("overallAdmin"));
        assertEquals(403, overall("GET", "premium", null, STRANGER).status());
        assertEquals(403, overall("GET", "beta-testers", null, STRANGER).status());
        assertEquals(404, overall("GET", "admins", null, BUILT_IN).status(), "the list itself lives on the hub (T-981)");
    }

    @Test
    void anAdminAddedOnTheHubIsAnAdminHereUntilRemoved() throws Exception {
        hubAdds(ADDED);
        Answer who = overall("GET", "whoami", null, ADDED);
        assertTrue(who.body().getBoolean("overallAdmin"));
        assertFalse(who.body().getBoolean("builtIn"));
        assertEquals(200, overall("GET", "beta-testers", null, ADDED).status(), "the beta list is the panel's, for every overall admin");

        hubRemoves(ADDED);
        assertFalse(overall("GET", "whoami", null, ADDED).body().getBoolean("overallAdmin"), "a removal takes effect once the list is read again");
    }

    @Test
    void premiumGrantsAreKeptWithDatesButNotEnforced() throws Exception {
        hubAdds(ADDED);
        Answer granted = overall("POST", "premium", "{\"kind\":\"site\",\"targetId\":\"" + SITE + "\",\"startsAt\":\"2026-01-01\",\"endsAt\":\"2099-01-01\",\"note\":\"n5\"}", ADDED);
        assertEquals(200, granted.status(), granted.body().toString());
        assertFalse(granted.body().getBoolean("enforced"));
        DataObject grant = granted.body().getArray("grants").getObject(0);
        assertEquals("site", grant.getString("kind"));
        assertEquals("2026-01-01T00:00:00Z", grant.getString("startsAt"));
        assertTrue(grant.getBoolean("active"));
        assertEquals(400, overall("POST", "premium", "{\"kind\":\"user\",\"targetId\":\"" + STRANGER + "\",\"startsAt\":\"2026-02-01\",\"endsAt\":\"2026-01-01\"}", ADDED).status(),
                "the end must come after the start");
        assertEquals(400, overall("POST", "premium", "{\"kind\":\"team\",\"targetId\":\"" + STRANGER + "\"}", ADDED).status());
        assertEquals(200, overall("DELETE", "premium/" + grant.getString("id"), null, ADDED).status());
        assertEquals(404, overall("DELETE", "premium/" + grant.getString("id"), null, ADDED).status());
    }

    @Test
    void anOverallAdminOpensAnotherClansSiteAdminAndChangesItsPermissions() throws Exception {
        // a stranger with the same facts (not the server's owner or admin, not on the beta list) is refused
        assertEquals(403, site("GET", "clan-site/roles", null, STRANGER, false, null).status());
        hubAdds(ADDED);
        Answer roles = site("GET", "clan-site/roles", null, ADDED, false, null);
        assertEquals(200, roles.status(), roles.body().toString());
        assertEquals("owner", roles.body().getObject("you").getString("role"));
        assertTrue(roles.body().getObject("you").getBoolean("overallAdmin"));
        Answer access = site("PUT", "clan-site/roles/admin-access", "{\"userIds\":[\"" + SITE_ADMIN + "\"]}", ADDED, false, null);
        assertEquals(200, access.status(), access.body().toString());
        assertEquals(Long.toString(SITE_ADMIN), access.body().getObject("adminAccess").getArray("userIds").getString(0));

        hubRemoves(ADDED);
        assertEquals(403, site("GET", "clan-site/roles", null, ADDED, false, null).status(), "removed: back to a stranger");
    }

    @Test
    void theSiteOwnerLetsPeopleInByUserIdAndByTheStaffServerRule() throws Exception {
        grants.add(AccessArea.SITE_BUILDER_BETA, SITE_OWNER, null, null, "n5", SITE_OWNER, null);
        grantService.listChanged();
        assertEquals(200, site("GET", "clan-site", null, SITE_OWNER, true, null).status());

        assertEquals(403, site("GET", "clan-site/roles", null, SITE_ADMIN, false, null).status());
        Answer saved = site("PUT", "clan-site/roles/admin-access", "{\"userIds\":[\"" + SITE_ADMIN + "\"],\"staffServerId\":\"" + STAFF_SERVER
                + "\",\"staffPermission\":\"manage_server\"}", SITE_OWNER, true, null);
        assertEquals(200, saved.status(), saved.body().toString());
        assertEquals("manage_server", saved.body().getObject("adminAccess").getString("staffPermission"));

        Answer named = site("GET", "clan-site/roles", null, SITE_ADMIN, false, null);
        assertEquals(200, named.status(), named.body().toString());
        assertEquals("admin", named.body().getObject("you").getString("role"));
        assertEquals(403, site("PUT", "clan-site/roles/admin-access", "{\"userIds\":[]}", SITE_ADMIN, false, null).status(),
                "only the site's owner changes who opens the site admin");

        Answer staff = site("GET", "clan-site/roles", null, STAFFER, false, Map.of(STAFF_SERVER, 0x20L));
        assertEquals(200, staff.status(), staff.body().toString());
        assertEquals("admin", staff.body().getObject("you").getString("role"));
        assertEquals(200, site("GET", "clan-site/roles", null, STAFFER, false, Map.of(STAFF_SERVER, 0x8L)).status(), "Administrator always counts");
        assertEquals(403, site("GET", "clan-site/roles", null, STAFFER, false, Map.of(STAFF_SERVER, 0x2L)).status(), "Kick Members isn't Manage Server");
        assertEquals(403, site("GET", "clan-site/roles", null, STAFFER, false, Map.of(STAFF_SERVER + 1, 0x20L)).status(), "another server");
        assertEquals(403, site("GET", "clan-site/roles", null, STAFFER, false, null).status(), "no signed facts, no staff");

        assertEquals(400, site("PUT", "clan-site/roles/admin-access", "{\"staffServerId\":\"" + STAFF_SERVER + "\",\"staffPermission\":\"sing\"}", SITE_OWNER, true, null).status());
        assertEquals(400, site("PUT", "clan-site/roles/admin-access", "{\"roleIds\":[\"" + BASE + "\"]}", SITE_OWNER, true, null).status(),
                "no Discord roles without the bot");
    }
}
