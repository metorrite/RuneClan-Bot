package com.runeclans.bot.access;

import com.runeclans.bot.beta.BetaGuildRepository.BetaGuild;
import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the access gates come from (B-1.4): the hub with RUNECLANS_HUB_ACCESS, its cached answer while it is down, else the bot's own tables. */
class GateSourceTest {
    // fake ids, made at runtime
    private static final long USER = Long.parseLong("1".repeat(18));
    private static final long GUILD = Long.parseLong("2".repeat(18));
    private static final long OWN_GUILD = Long.parseLong("3".repeat(18));

    private static final List<BetaGuild> OWN_BETA = List.of(new BetaGuild(OWN_GUILD, "own", AccessLevel.OWNER));
    private static final List<AccessGrant> OWN_GRANTS = List.of(new AccessGrant(9, AccessArea.HUB, USER, null, null, null, 0, Instant.EPOCH, null));
    private static final Map<String, Boolean> OWN_SWITCHES = Map.of("hub", false);

    /** A stub hub: answers by path, or null (unreachable) when the path isn't set. */
    private static final class Stub implements Function<String, RuneClansHubClient.Read> {
        final Map<String, RuneClansHubClient.Read> answers = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        Stub answer(String path, int status, DataObject json, boolean fromCache) {
            answers.put(path, new RuneClansHubClient.Read(new RuneClansHubClient.Answer(status, json), fromCache, fromCache));
            return this;
        }

        @Override
        public RuneClansHubClient.Read apply(String path) {
            asked.add(path);
            return answers.get(path);
        }
    }

    private static DataObject servers() {
        return DataObject.empty()
                .put("betaServers", DataArray.empty().add(DataObject.empty().put("guildId", Long.toString(GUILD)).put("minLevel", "administrator"))
                        .add(DataObject.empty().put("guildId", "not-a-guild").put("minLevel", "administrator")))
                .put("guildGrants", DataArray.empty()
                        .add(DataObject.empty().put("area", "dashboard").put("guildId", Long.toString(GUILD)).put("minLevel", "manage_server").put("expiresAt", null))
                        .add(DataObject.empty().put("area", "somewhere-new").put("guildId", Long.toString(GUILD)).put("minLevel", "member")))
                .put("switches", DataObject.empty().put("hub", true).put("dashboard", false));
    }

    private static DataObject user() {
        return DataObject.empty().put("userId", Long.toString(USER)).put("betaTester", true)
                .put("grants", DataArray.empty().add(DataObject.empty().put("area", "site-builder-beta").put("expiresAt", "2999-01-01T00:00:00Z")));
    }

    private static GateSource source(boolean on, Stub hub) {
        return new GateSource(() -> on, hub, null, () -> OWN_BETA, () -> OWN_GRANTS, () -> OWN_SWITCHES);
    }

    @Test
    void theHubsAnswersAreUsed() {
        Stub hub = new Stub().answer("/v1/access/servers", 200, servers(), false).answer("/v1/access/users/" + USER, 200, user(), false);
        GateSource gates = source(true, hub);
        assertTrue(gates.fromHub());

        List<BetaGuild> beta = gates.betaGuilds();
        assertEquals(1, beta.size(), "a malformed server id is skipped");
        assertEquals(GUILD, beta.get(0).guildId());
        assertEquals(AccessLevel.ADMINISTRATOR, beta.get(0).level());

        List<AccessGrant> grants = gates.grantsFor(USER);
        assertEquals(2, grants.size(), "the server grant in a known area, and the person's own; an unknown area is dropped: " + grants);
        AccessGrant server = grants.get(0);
        assertEquals(AccessArea.DASHBOARD, server.area());
        assertEquals(GUILD, server.guildId());
        assertEquals(AccessLevel.MANAGE_SERVER, server.level());
        assertNull(server.expiresAt());
        AccessGrant mine = grants.get(1);
        assertTrue(mine.isUser());
        assertEquals(USER, mine.userId());
        assertEquals(AccessArea.SITE_BUILDER_BETA, mine.area());
        assertFalse(mine.expired(Instant.now()));

        assertEquals(Map.of("hub", true, "dashboard", false), gates.switches());
    }

    @Test
    void theDecisionUsesTheHubsGrants() {
        Stub hub = new Stub().answer("/v1/access/servers", 200, servers(), false).answer("/v1/access/users/" + USER, 200, user(), false);
        List<AccessGrant> grants = source(true, hub).grantsFor(USER);
        // a user grant matches without asking Discord (no JDA needed for that branch)
        assertTrue(AccessGrantService.matches(grants, null, AccessArea.SITE_BUILDER_BETA, USER, Instant.now()));
        assertFalse(AccessGrantService.matches(grants.subList(1, 2), null, AccessArea.HUB, USER, Instant.now()), "a grant in another area");
    }

    @Test
    void aCachedAnswerIsUsedWhileTheHubIsDown() {
        // RuneClansHubClient.read hands back its cached copy (fromCache, stale) when the hub can't be reached
        Stub hub = new Stub().answer("/v1/access/servers", 200, servers(), true).answer("/v1/access/users/" + USER, 200, user(), true);
        GateSource gates = source(true, hub);
        assertEquals(GUILD, gates.betaGuilds().get(0).guildId());
        assertEquals(2, gates.grantsFor(USER).size());
    }

    @Test
    void theBotsOwnTablesWhenTheHubHasNothing() {
        // unreachable with nothing cached
        GateSource down = source(true, new Stub());
        assertEquals(OWN_BETA, down.betaGuilds());
        assertEquals(OWN_GRANTS, down.grantsFor(USER));
        assertEquals(OWN_SWITCHES, down.switches());

        // the hub's access module is off (404), or only the person's answer is missing: the bot's whole list, never half of each
        GateSource off = source(true, new Stub().answer("/v1/access/servers", 404, DataObject.empty().put("error", "not_found"), false));
        assertEquals(OWN_BETA, off.betaGuilds());
        GateSource half = source(true, new Stub().answer("/v1/access/servers", 200, servers(), false));
        assertEquals(OWN_GRANTS, half.grantsFor(USER));
        assertEquals(GUILD, half.betaGuilds().get(0).guildId(), "the server answer itself is still used");
    }

    @Test
    void switchOffMeansTheOldPathAndNoHubCalls() {
        Stub hub = new Stub().answer("/v1/access/servers", 200, servers(), false);
        GateSource gates = source(false, hub);
        assertFalse(gates.fromHub());
        assertEquals(OWN_BETA, gates.betaGuilds());
        assertEquals(OWN_GRANTS, gates.grantsFor(USER));
        assertEquals(OWN_SWITCHES, gates.switches());
        assertTrue(hub.asked.isEmpty());
        assertFalse(gates.clanVerified("Iron Legion", USER));

        GateSource own = GateSource.ownTables(null, null, null);
        assertFalse(own.fromHub());
        assertTrue(own.betaGuilds().isEmpty());
        assertTrue(own.switches().isEmpty());
    }

    @Test
    void dashboardVerificationIsSentToTheHub() {
        List<String> sent = new ArrayList<>();
        Function<String, HubWriteQueue.Result> writer = body -> {
            sent.add(body);
            return new HubWriteQueue.Result(HubWriteQueue.Outcome.QUEUED, null);
        };
        GateSource gates = new GateSource(() -> true, new Stub(), writer, () -> OWN_BETA, () -> OWN_GRANTS, () -> OWN_SWITCHES);
        assertTrue(gates.clanVerified("Iron Legion", USER));
        DataObject body = DataObject.fromJson(sent.get(0));
        assertEquals("Iron Legion", body.getString("name"));
        assertEquals(Long.toString(USER), body.getString("verifiedBy"));

        GateSource refused = new GateSource(() -> true, new Stub(), b -> new HubWriteQueue.Result(HubWriteQueue.Outcome.REFUSED, null), () -> OWN_BETA, () -> OWN_GRANTS,
                () -> OWN_SWITCHES);
        assertFalse(refused.clanVerified("Iron Legion", USER), "refused: the bot's own copy still has it");
        assertFalse(gates.clanVerified(null, USER));
    }

    @Test
    void theSettingIsOffUnlessOn() {
        assertFalse(GateSource.on(null));
        assertFalse(GateSource.on("off"));
        assertTrue(GateSource.on(" on "));
        assertTrue(GateSource.on("TRUE"));
    }

    @Test
    void theBotsGatesFollowThePanelsBetaAccessSwitchUnlessRailwayHoldsIt() {
        com.runeclans.bot.config.BotConfig config = org.mockito.Mockito.mock(com.runeclans.bot.config.BotConfig.class);
        RuneClansHubClient hub = org.mockito.Mockito.mock(RuneClansHubClient.class);
        org.mockito.Mockito.when(hub.enabled()).thenReturn(true);
        com.runeclans.bot.beta.BetaGuildRepository beta = org.mockito.Mockito.mock(com.runeclans.bot.beta.BetaGuildRepository.class);
        com.runeclans.bot.access.AccessGrantRepository grants = org.mockito.Mockito.mock(com.runeclans.bot.access.AccessGrantRepository.class);
        com.runeclans.bot.siteaccess.SiteSwitchRepository switches = org.mockito.Mockito.mock(com.runeclans.bot.siteaccess.SiteSwitchRepository.class);
        com.runeclans.bot.runeclans.PanelSwitches panelOn = com.runeclans.bot.runeclans.PanelSwitches.of(Map.of(GateSource.SWITCH_ID, "on"));
        com.runeclans.bot.runeclans.PanelSwitches panelOff = com.runeclans.bot.runeclans.PanelSwitches.of(Map.of(GateSource.SWITCH_ID, "off"));

        assertTrue(GateSource.gateSource(config, hub, panelOn, beta, grants, switches).fromHub(), "Railway unset: the panel's on");
        assertFalse(GateSource.gateSource(config, hub, panelOff, beta, grants, switches).fromHub(), "Railway unset: the panel's off");
        org.mockito.Mockito.when(config.getRuneClansSetting(GateSource.SETTING_ENV)).thenReturn("no");
        assertFalse(GateSource.gateSource(config, hub, panelOn, beta, grants, switches).fromHub(), "any Railway value but an on word holds it off");
        org.mockito.Mockito.when(config.getRuneClansSetting(GateSource.SETTING_ENV)).thenReturn("on");
        assertTrue(GateSource.gateSource(config, hub, panelOff, beta, grants, switches).fromHub(), "Railway's on holds it on");
    }
}
