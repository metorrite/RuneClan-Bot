package com.runeclans.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.internal.SiteStatsRepository.MemberRow;
import com.runeclans.bot.runescape.ClanPointsRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code GET players/{rsn}} ({@link DirectoryPlayerRoutes}): the request shape, 400/404/405, the cache, and that Discord is never asked. */
class DirectoryPlayerRoutesTest {
    private static final long GUILD = Long.parseLong("5".repeat(17) + "1");

    private ClanProfileRepository profiles;
    private ClanProfileService service;
    private SiteApi siteApi;
    private SiteStatsRepository stats;
    private PlayerLinkRepository links;
    private ClanPointsRepository points;
    private JDA jda;
    private SiteCache cache;
    private DirectoryPlayerRoutes routes;

    @BeforeEach
    void setUp() {
        profiles = mock(ClanProfileRepository.class);
        service = mock(ClanProfileService.class);
        siteApi = mock(SiteApi.class);
        stats = mock(SiteStatsRepository.class);
        links = mock(PlayerLinkRepository.class);
        points = mock(ClanPointsRepository.class);
        jda = mock(JDA.class);
        cache = new SiteCache();
        routes = new DirectoryPlayerRoutes(profiles, service, siteApi, stats, links, points, cache, mock(com.runeclans.bot.runescape.TrackedNames.class));
    }

    private void listedClanWith(String rsn) {
        ClanProfile profile = new ClanProfile(GUILD, ClanProfile.Visibility.PUBLIC, "alpha", "Alpha", "", "", null, null, null, EnumSet.noneOf(ClanProfile.Focus.class),
                ClanProfile.Recruiting.CLOSED, "", null, false, "Alpha", Instant.EPOCH, null, null, null, null);
        when(stats.guildsWithActiveMember(anyString())).thenReturn(List.of(GUILD));
        when(profiles.get(GUILD)).thenReturn(Optional.of(profile));
        when(service.clanNameOf(GUILD)).thenReturn("Alpha");
        when(siteApi.visibleMember(eq(GUILD), anyString())).thenReturn(new MemberRow(rsn, "Owner", 11, 1, 0, null, OffsetDateTime.now(), 0, false, false, null, null, null));
        when(links.getSnapshotHistory(anyString(), any(Integer.class))).thenReturn(List.of());
    }

    record Outcome(boolean handled, int status, String body) {}

    private Outcome call(String method, String path) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        int[] status = {-1};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        URI uri = URI.create("http://localhost/internal/directory/" + path);
        when(exchange.getRequestURI()).thenReturn(uri);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(out);
        doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(any(Integer.class), any(Long.class));
        // as DirectoryApi splits it: the decoded path after the prefix
        String[] parts = uri.getPath().substring("/internal/directory/".length()).split("/");
        DirectoryRequest request = new DirectoryRequest(exchange, jda, method, parts, slug -> Optional.empty(), p -> DataObject.empty());
        boolean handled = routes.route(request);
        return new Outcome(handled, status[0], out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aListedMemberGetsTheirClans() throws Exception {
        listedClanWith("Some Name");
        Outcome ok = call("GET", "players/%20some%20name%20");
        assertEquals(200, ok.status());
        DataObject body = DataObject.fromJson(ok.body());
        assertEquals("Some Name", body.getString("rsn"));
        assertEquals("alpha", body.getArray("clans").getObject(0).getString("slug"));
        assertTrue(body.isNull("stats"));
        verify(stats).guildsWithActiveMember("some name");
        verifyNoInteractions(jda);
    }

    @Test
    void aBadNameIs400AndNothingIsRead() throws Exception {
        assertEquals(400, call("GET", "players/way-too-long-a-name").status());
        assertEquals(400, call("GET", "players/semi;colon").status());
        assertEquals(400, call("GET", "players/%20%20").status());
        verify(stats, never()).guildsWithActiveMember(anyString());
    }

    @Test
    void aNameNoIncludedClanHasIs404() throws Exception {
        when(stats.guildsWithActiveMember(anyString())).thenReturn(List.of());
        assertEquals(404, call("GET", "players/Nobody").status());
    }

    @Test
    void onlyGet() throws Exception {
        Outcome post = call("POST", "players/Nobody");
        assertTrue(post.handled());
        assertEquals(405, post.status());
    }

    @Test
    void answersAreCachedByNameIgnoringCase() throws Exception {
        listedClanWith("Cached");
        assertEquals(200, call("GET", "players/Cached").status());
        assertEquals(200, call("GET", "players/CACHED").status());
        verify(stats, times(1)).guildsWithActiveMember(anyString());
        when(stats.guildsWithActiveMember("Ghost")).thenReturn(List.of());
        assertEquals(404, call("GET", "players/Ghost").status());
        assertEquals(404, call("GET", "players/ghost").status());
        verify(stats, times(1)).guildsWithActiveMember("Ghost");
    }

    @Test
    void hidingAMemberDropsTheCachedAnswerAtOnce() throws Exception {
        listedClanWith("Hider");
        assertEquals(200, call("GET", "players/Hider").status());
        // the player turns on "hide from rankings": SiteApi stops returning the row and MemberApi clears the shared cache
        when(siteApi.visibleMember(eq(GUILD), anyString())).thenReturn(null);
        assertEquals(200, call("GET", "players/Hider").status(), "still cached until something clears it");
        cache.clear();
        assertEquals(404, call("GET", "players/Hider").status());
    }

    @Test
    void otherShapesAreLeftToTheOtherModules() throws Exception {
        assertFalse(call("GET", "players/a/b").handled());
        assertFalse(call("GET", "clans/a").handled());
    }

    @Test
    void theBarePathAnswersTheTopPlayers() throws Exception {
        ClanProfile profile = new ClanProfile(GUILD, ClanProfile.Visibility.PUBLIC, "alpha", "Alpha", "", "", null, null, null, EnumSet.noneOf(ClanProfile.Focus.class),
                ClanProfile.Recruiting.CLOSED, "", null, true, "Alpha", Instant.EPOCH, null, null, null, null);
        when(profiles.shared()).thenReturn(List.of(profile));
        when(service.clanNameOf(GUILD)).thenReturn("Alpha");
        when(stats.xpGains(eq(GUILD), any(OffsetDateTime.class))).thenReturn(List.of(new SiteStatsRepository.Gain("Grinder", 1_000)));
        when(siteApi.visibleMember(eq(GUILD), anyString())).thenReturn(new MemberRow("Grinder", "Owner", 11, 1, 0, null, OffsetDateTime.now(), 0, false, false, null, null, null));

        Outcome ok = call("GET", "players");
        assertTrue(ok.handled());
        assertEquals(200, ok.status());
        DataObject body = DataObject.fromJson(ok.body());
        assertEquals("Grinder", body.getArray("entries").getObject(0).getString("rsn"));
        assertEquals(1_000, body.getArray("entries").getObject(0).getLong("xpGained"));
        assertEquals(405, call("POST", "players").status());
        verifyNoInteractions(jda);
    }
}
