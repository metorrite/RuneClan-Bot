package com.runeclans.bot.linkedroles;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linked Roles end to end over real SQL and real HTTP, against a mock of Discord's endpoints on a local port (never the real Discord): verifying
 * sends the metadata, the hourly pass sends only changes (or after a day), tokens refresh and rotate, a revoked grant is forgotten, a wrong
 * client secret is not mistaken for one, unlinking clears and revokes, rate limits are waited out, and the schema registers. Skipped without a
 * local database.
 */
class LinkedRolesFlowDatabaseTest {
    /** What the hub holds: the bot keeps no polled stats of its own (T-912). */
    private static final com.runeclans.bot.testsupport.MemoryStats HUB = new com.runeclans.bot.testsupport.MemoryStats();

    private static final long USER = 990_000_000_000_000_701L;
    private static final long OTHER = 990_000_000_000_000_702L;
    private static final long GUILD = 990_000_000_000_000_791L;
    private static final String RSN = "LrTestZez";
    private static ConnectionSupplier db;

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<DataObject> connections = new CopyOnWriteArrayList<>();
    private final List<String> bearers = new CopyOnWriteArrayList<>();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final AtomicInteger rateLimitOnce = new AtomicInteger();
    private volatile String refreshAnswer = "ok";
    private volatile String revokedBearer = "";
    private DataArray schema;
    private MutableClock clock;
    private LinkedRolesStore store;
    private LinkedRoles roles;
    private LinkedRolesSettings settings;

    @BeforeAll
    static void connect() throws Exception {
        db = LocalDatabase.supplier();
        new RuneScapeDatabaseInitializer(db);
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v10/", this::discord);
        server.start();
        Map<String, String> env = new HashMap<>(LinkedRolesUnitTest.fullEnv());
        env.put("LINKED_ROLES_DISCORD_API", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v10");
        settings = LinkedRolesSettings.from(env::get);
        assertTrue(settings.enabled(), settings.problem());
        clock = new MutableClock(Instant.parse("2026-10-23T12:00:00Z"));
        store = new LinkedRolesStore(db).withStats(HUB.backends());
        roles = LinkedRoles.forTest(settings, store, DiscordRoleApi.http(settings), guild -> guild == GUILD, clock);
        cleanUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        cleanUp();
    }

    private void cleanUp() {
        sql("DELETE FROM younglings.linked_role_connection WHERE discord_user_id IN (" + USER + "," + OTHER + ")");
        sql("DELETE FROM younglings.player_account WHERE discord_user_id IN (" + USER + "," + OTHER + ")");
        HUB.clear();
        sql("DELETE FROM younglings.clan_member WHERE guild_id = " + GUILD);
    }

    // ---------- the mock Discord ----------

    private void discord(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/api/v10".length());
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(ex.getRequestMethod() + " " + path);
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (rateLimitOnce.get() > 0 && rateLimitOnce.decrementAndGet() >= 0) {
            send(ex, 429, "{\"message\":\"You are being rate limited.\",\"retry_after\":0.2,\"global\":false}");
            return;
        }
        if (path.equals("/oauth2/token")) {
            Map<String, String> form = form(body);
            if (!form.get("grant_type").equals("refresh_token") || !auth.startsWith("Basic ")) {
                send(ex, 400, "{\"error\":\"unsupported_grant_type\"}");
                return;
            }
            switch (refreshAnswer) {
                case "invalid_grant" -> send(ex, 400, "{\"error\":\"invalid_grant\"}");
                case "invalid_client" -> send(ex, 401, "{\"error\":\"invalid_client\"}");
                default -> {
                    int n = refreshes.incrementAndGet();
                    send(ex, 200, "{\"access_token\":\"access-" + n + "\",\"refresh_token\":\"refresh-" + n + "\",\"expires_in\":604800,\"token_type\":\"Bearer\"}");
                }
            }
        } else if (path.equals("/oauth2/token/revoke")) {
            send(ex, 200, "{}");
        } else if (path.matches("/users/@me/applications/\\d+/role-connection") && ex.getRequestMethod().equals("PUT")) {
            String bearer = auth.substring("Bearer ".length());
            if (bearer.equals(revokedBearer)) {
                send(ex, 401, "{\"message\":\"401: Unauthorized\",\"code\":0}");
                return;
            }
            bearers.add(bearer);
            connections.add(DataObject.fromJson(body));
            send(ex, 200, body);
        } else if (path.matches("/applications/\\d+/role-connections/metadata") && ex.getRequestMethod().equals("PUT")) {
            assertTrue(auth.startsWith("Bot "));
            schema = DataArray.fromJson(body);
            send(ex, 200, body);
        } else {
            send(ex, 404, "{\"message\":\"404: Not Found\"}");
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    // ---------- fixtures ----------

    private void linkAndStats(int total, int combat, boolean member) {
        sql("INSERT INTO younglings.player_account (rsn, discord_user_id, verification_method, verified_at) VALUES ('" + RSN + "', " + USER
                + ", 'TEST', '2026-01-01T00:00:00Z')");
        snapshot(total, combat, "2026-10-22T00:00:00Z");
        if (member) sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank) VALUES (" + GUILD + ", '" + RSN + "', 'Recruit')");
    }

    private void snapshot(int total, int combat, String at) {
        HUB.snapshot(RSN.toLowerCase(), java.time.OffsetDateTime.parse(at.replace("Z", "+00:00")), total, 1, combat, java.util.List.of());
    }

    private LinkedRoles.Shared verify() throws Exception {
        return roles.connect(USER, "access-0", "refresh-0", 604800, null, null);
    }

    // ---------- tests ----------

    @Test
    void verifyingSendsTheMetadataAndKeepsTheTokensSealed() throws Exception {
        linkAndStats(2400, 130, true);
        LinkedRoles.Shared shared = verify();
        assertEquals(RSN, shared.profile().rsn());
        DataObject sent = connections.get(0);
        assertEquals("RuneScape", sent.getString("platform_name"));
        assertEquals(RSN, sent.getString("platform_username"));
        DataObject m = sent.getObject("metadata");
        assertEquals("1", m.getString("rsn_linked"));
        assertEquals("2400", m.getString("total_level"));
        assertEquals("130", m.getString("combat_level"));
        assertEquals("1", m.getString("clan_member"));
        assertEquals("2026-01-01T00:00:00Z", m.getString("linked_since"));
        assertEquals("access-0", bearers.get(0));

        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT access_enc, refresh_enc FROM younglings.linked_role_connection WHERE discord_user_id = ?")) {
            s.setLong(1, USER);
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                assertFalse(new String(rs.getBytes(1), StandardCharsets.ISO_8859_1).contains("access-0"));
                assertFalse(new String(rs.getBytes(2), StandardCharsets.ISO_8859_1).contains("refresh-0"));
            }
        }
        assertTrue(roles.status(USER).orElseThrow().values().containsKey("total_level"));
        assertTrue(roles.status(OTHER).isEmpty(), "someone else is not connected");
    }

    @Test
    void someoneWithNoLinkedNameIsToldSoAndAnRsLinkNameCounts() throws Exception {
        LinkedRoles.Shared none = verify();
        assertEquals(null, none.profile().rsn());
        assertEquals("0", connections.get(0).getObject("metadata").getString("rsn_linked"));

        LinkedRoles.Shared viaRsLink = roles.connect(USER, "access-0", "refresh-0", 604800, RSN, Instant.parse("2026-05-01T00:00:00Z"));
        snapshot(1500, 90, "2026-10-22T00:00:00Z");
        assertEquals(RSN, viaRsLink.profile().rsn());
        assertEquals("2026-05-01T00:00:00Z", connections.get(1).getObject("metadata").getString("linked_since"));
        assertEquals("1500", roles.status(USER).orElseThrow().values().get("total_level"));
    }

    @Test
    void theHourlyPassFollowsRsLinkWithoutVerifyingAgain() throws Exception {
        AtomicReference<Optional<Map<Long, RsLinkSource.Current>>> hub = new AtomicReference<>(Optional.empty());
        List<List<Long>> asked = new CopyOnWriteArrayList<>();
        roles = LinkedRoles.forTest(settings, store, DiscordRoleApi.http(settings), guild -> guild == GUILD, ids -> {
            asked.add(List.copyOf(ids));
            return hub.get();
        }, clock);
        roles.connect(USER, "access-0", "refresh-0", 604800, RSN, Instant.parse("2026-05-01T00:00:00Z"));
        assertEquals("1", connections.get(0).getObject("metadata").getString("rsn_linked"));

        clock.advance(Duration.ofHours(1));
        assertEquals(0, roles.tick(), "the hub can't be asked: the name from sign-in stays");
        assertEquals(List.of(List.of(USER)), asked);

        hub.set(Optional.of(Map.of(USER, new RsLinkSource.Current(RSN, Instant.parse("2026-05-01T00:00:00Z")))));
        clock.advance(Duration.ofHours(1));
        assertEquals(0, roles.tick(), "the same link changes nothing");

        hub.set(Optional.of(Map.of()));
        clock.advance(Duration.ofHours(1));
        assertEquals(1, roles.tick(), "an RS Link unlink is sent");
        DataObject unlinked = connections.get(1);
        assertEquals("0", unlinked.getObject("metadata").getString("rsn_linked"));
        assertFalse(unlinked.hasKey("platform_username"));
        assertEquals(null, roles.status(USER).orElseThrow().profile().rsn());

        hub.set(Optional.of(Map.of(USER, new RsLinkSource.Current("LrTestNew", null))));
        clock.advance(Duration.ofHours(1));
        assertEquals(1, roles.tick(), "a new link is sent");
        DataObject relinked = connections.get(2);
        assertEquals("1", relinked.getObject("metadata").getString("rsn_linked"));
        assertEquals("LrTestNew", relinked.getString("platform_username"));
        assertEquals(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), relinked.getObject("metadata").getString("linked_since"),
                "with no link date from the hub, days linked count from now");

        roles = LinkedRoles.forTest(settings, store, DiscordRoleApi.http(settings), guild -> guild == GUILD, ids -> {
            throw new IllegalStateException("hub down");
        }, clock);
        clock.advance(Duration.ofHours(1));
        assertEquals(0, roles.tick(), "a failing hub keeps what was there");
        assertEquals("LrTestNew", roles.status(USER).orElseThrow().profile().rsn());
    }

    @Test
    void withRequireConfirmedAnRsLinkNameCountsOnceTheHubConfirmsIt() throws Exception {
        Map<String, String> env = new HashMap<>(LinkedRolesUnitTest.fullEnv());
        env.put("LINKED_ROLES_DISCORD_API", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v10");
        env.put("LINKED_ROLES_REQUIRE_CONFIRMED", "true");
        LinkedRolesSettings strict = LinkedRolesSettings.from(env::get);
        AtomicReference<Optional<Map<Long, RsLinkSource.Current>>> hub = new AtomicReference<>(
                Optional.of(Map.of(USER, new RsLinkSource.Current(RSN, Instant.parse("2026-05-01T00:00:00Z"), false))));
        roles = LinkedRoles.forTest(strict, store, DiscordRoleApi.http(strict), guild -> guild == GUILD, ids -> hub.get(), clock);

        LinkedRoles.Shared shared = roles.connect(USER, "access-0", "refresh-0", 604800, RSN, Instant.parse("2026-05-01T00:00:00Z"));
        assertEquals("0", connections.get(0).getObject("metadata").getString("rsn_linked"), "not confirmed yet: the name doesn't count");
        assertEquals(RSN, shared.unconfirmedRsLink());
        assertEquals(null, shared.profile().rsn());

        hub.set(Optional.of(Map.of(USER, new RsLinkSource.Current(RSN, Instant.parse("2026-05-01T00:00:00Z"), true))));
        clock.advance(Duration.ofHours(1));
        assertEquals(1, roles.tick(), "the hub confirmed the link: it is sent");
        assertEquals("1", connections.get(1).getObject("metadata").getString("rsn_linked"));
        assertEquals(null, roles.status(USER).orElseThrow().unconfirmedRsLink());

        // the same person with the switch off: the name counted all along
        roles = LinkedRoles.forTest(settings, store, DiscordRoleApi.http(settings), guild -> guild == GUILD, ids -> hub.get(), clock);
        assertEquals(RSN, roles.status(USER).orElseThrow().profile().rsn());
    }

    @Test
    void aNameTheBotKeepsItselfWinsOverRsLink() throws Exception {
        linkAndStats(2000, 120, false);
        roles = LinkedRoles.forTest(settings, store, DiscordRoleApi.http(settings), guild -> guild == GUILD, ids -> Optional.of(Map.of()), clock);
        verify();
        clock.advance(Duration.ofHours(1));
        assertEquals(0, roles.tick(), "no RS Link link, but the bot's own verified name still counts");
        assertEquals("1", connections.get(0).getObject("metadata").getString("rsn_linked"));
    }

    @Test
    void theHourlyPassSendsOnlyChangesOrAfterADay() throws Exception {
        linkAndStats(2000, 120, false);
        verify();
        assertEquals(1, connections.size());
        clock.advance(Duration.ofHours(1));
        assertEquals(0, roles.tick(), "nothing changed");
        snapshot(2001, 120, "2026-10-23T12:30:00Z");
        assertEquals(1, roles.tick(), "a level-up is sent");
        assertEquals("2001", connections.get(1).getObject("metadata").getString("total_level"));
        clock.advance(Duration.ofHours(1));
        assertEquals(0, roles.tick());
        clock.advance(Duration.ofHours(25));
        assertEquals(1, roles.tick(), "re-sent after a day");
    }

    @Test
    void anExpiredAccessTokenIsRefreshedAndBothTokensRotate() throws Exception {
        linkAndStats(2000, 120, false);
        roles.connect(USER, "access-0", "refresh-0", 600, null, null);
        clock.advance(Duration.ofMinutes(11));
        snapshot(2002, 120, "2026-10-23T12:05:00Z");
        assertEquals(1, roles.tick());
        assertEquals(1, refreshes.get());
        assertEquals("access-1", bearers.get(1));
        clock.advance(Duration.ofDays(8));
        assertEquals(1, roles.tick());
        assertEquals(2, refreshes.get(), "the rotated refresh token was kept and used");
        assertEquals("access-2", bearers.get(2));
    }

    @Test
    void aRevokedGrantIsForgottenButAWrongClientSecretIsNot() throws Exception {
        linkAndStats(2000, 120, false);
        roles.connect(USER, "access-0", "refresh-0", 600, null, null);
        clock.advance(Duration.ofDays(2));

        refreshAnswer = "invalid_client";
        assertEquals(0, roles.tick());
        assertTrue(store.find(USER).isPresent(), "our misconfiguration must not wipe anyone's tokens");

        refreshAnswer = "invalid_grant";
        assertEquals(0, roles.tick());
        assertTrue(store.find(USER).isEmpty(), "the person took the app's access away: forgotten");
    }

    @Test
    void aRevokedAccessTokenIsRefreshedOnceBeforeGivingUp() throws Exception {
        linkAndStats(2000, 120, false);
        verify();
        revokedBearer = "access-0";
        clock.advance(Duration.ofDays(2));
        assertEquals(1, roles.tick());
        assertEquals("access-1", bearers.get(bearers.size() - 1));
    }

    @Test
    void unlinkingClearsTheValuesRevokesAndForgets() throws Exception {
        linkAndStats(2000, 120, true);
        verify();
        assertTrue(roles.unlink(USER));
        DataObject last = connections.get(connections.size() - 1);
        assertTrue(last.getObject("metadata").keys().isEmpty(), "every condition now fails, so the roles drop");
        assertTrue(calls.contains("POST /oauth2/token/revoke"));
        assertTrue(store.find(USER).isEmpty());
        assertFalse(roles.unlink(USER), "nothing left to forget");
    }

    @Test
    void aRateLimitIsWaitedOut() throws Exception {
        linkAndStats(2000, 120, false);
        rateLimitOnce.set(1);
        verify();
        assertEquals(1, connections.size());
        assertEquals(2, calls.size(), "one 429, then the retry");
    }

    @Test
    void theSchemaRegistersAndRepeatingItChangesNothing() {
        assertTrue(roles.registerSchema());
        assertEquals(5, schema.length());
        DataArray first = schema;
        assertTrue(roles.registerSchema());
        assertEquals(first.toString(), schema.toString());
        assertEquals(List.of("rsn_linked", "total_level", "combat_level", "clan_member", "linked_since"),
                new ArrayList<>(schema.stream(DataArray::getObject).map(o -> o.getString("key")).toList()));
    }

    @Test
    void connectWithoutTokensIsRefused() {
        LinkedRoles.Problem p = assertThrows(LinkedRoles.Problem.class, () -> roles.connect(USER, "", "x", 60, null, null));
        assertEquals(400, p.status());
    }

    @Test
    void offMeansOff() {
        LinkedRoles off = LinkedRoles.forTest(LinkedRolesSettings.from(name -> null), store, null, g -> false, clock);
        assertFalse(off.enabled());
        assertEquals(404, assertThrows(LinkedRoles.Problem.class, () -> off.status(USER)).status());
        assertEquals(0, off.tick());
        assertFalse(off.registerSchema());
    }

    private static void sql(String statement) {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
