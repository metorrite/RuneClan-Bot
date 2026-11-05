package com.runeclans.bot.runeclans;

import com.runeclans.bot.plugin.PluginEventSink;
import com.runeclans.bot.plugin.PluginPayload;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The RuneClans hub client: off by default, settings checked, and the feed relay passing hub events to the bot's own consumers. */
class RuneClansHubClientTest {
    /** A key-shaped value made at runtime (secret scanners flag literal ones). */
    private static final String KEY = "rsh_" + "k".repeat(43);

    private static RuneClansSettings on() {
        Map<String, String> env = Map.of("RUNECLANS_HUB_ENABLED", "true", "HUB_API_URL", "http://localhost:8090/", "RUNECLANS_HUB_API_KEY", KEY);
        return RuneClansSettings.from(env::get);
    }

    @Test
    void plainHttpOnlyForPrivateHosts() {
        for (String ok : new String[]{"https://api.runeclans.com", "http://hub.railway.internal:8090", "http://localhost:8090",
                "http://127.0.0.1:8090", "http://[::1]:8090", "http://10.0.0.5:8090", "http://172.16.0.1", "http://172.31.255.1", "http://192.168.1.20"}) {
            assertTrue(RuneClansSettings.allowedBaseUrl(ok), ok);
        }
        for (String bad : new String[]{"http://api.runeclans.com", "http://172.32.0.1", "http://172.15.0.1", "http://11.0.0.1", "http://8.8.8.8",
                "http://railway.internal.example.org", "http://evil.com/.railway.internal", "ftp://localhost", "https://user:pw@api.runeclans.com", "not a url"}) {
            assertFalse(RuneClansSettings.allowedBaseUrl(bad), bad);
        }
        Map<String, String> env = Map.of("RUNECLANS_HUB_ENABLED", "true", "HUB_API_URL", "http://hub.railway.internal:8090", "RUNECLANS_HUB_API_KEY", KEY);
        assertTrue(RuneClansSettings.from(env::get).enabled());
    }

    @Test
    void offUnlessSwitchedOnAndComplete() {
        assertFalse(RuneClansSettings.from(k -> null).enabled());
        assertFalse(RuneClansSettings.from(Map.of("HUB_API_URL", "http://localhost:8090", "RUNECLANS_HUB_API_KEY", KEY)::get).enabled());
        RuneClansSettings bad = RuneClansSettings.from(Map.of("RUNECLANS_HUB_ENABLED", "true", "HUB_API_URL", "http://example.org")::get);
        assertFalse(bad.enabled());
        assertTrue(bad.problem().contains("HUB_API_URL") && bad.problem().contains("RUNECLANS_HUB_API_KEY"));
        RuneClansSettings good = on();
        assertTrue(good.enabled());
        assertEquals("http://localhost:8090", good.baseUrl());
        assertFalse(good.toString().contains(KEY));
    }

    @Test
    void offClientNeverCalls() {
        RuneClansHubClient off = new RuneClansHubClient(RuneClansSettings.OFF, (m, p, b) -> {
            throw new AssertionError("called while off");
        });
        assertNull(off.call("GET", "/v1/me", null));
        assertFalse(off.putGuild(1L, "x"));
    }

    private static DataObject event(String id, String alias, String type) {
        DataObject data = DataObject.empty().put("id", id).put("alias", alias).put("rsn", "Zezima").put("type", type).put("category", "DROPS")
                .put("occurredAt", "2026-10-22T01:00:00Z").put("source", DataObject.empty().put("kind", "NPC").put("name", "Telos"))
                .put("data", DataObject.empty().put("itemName", "Dragon bones"));
        return DataObject.empty().put("id", id).put("kind", "plugin.event").put("alias", alias).put("data", data);
    }

    @Test
    void relayPassesEventsOfIdentityGrantsOnlyAndNeverReplaysOnFirstRun() {
        String known = "a1_" + "a".repeat(26);
        String anonymous = "a1_" + "b".repeat(26);
        List<String> paths = new ArrayList<>();
        AtomicReference<DataArray> nextPage = new AtomicReference<>(DataArray.empty().add(event("1", known, "DROP")));
        RuneClansHubClient hub = new RuneClansHubClient(on(), (method, path, body) -> {
            paths.add(method + " " + path);
            if (path.startsWith("/v1/feed")) {
                DataArray page = nextPage.getAndSet(DataArray.empty());
                return new RuneClansHubClient.Answer(200, DataObject.empty().put("data", page).put("nextCursor", "c" + paths.size()).put("more", false));
            }
            if (path.equals("/v1/players/" + known)) return new RuneClansHubClient.Answer(200, DataObject.empty().put("alias", known).put("discordId", "123456"));
            if (path.equals("/v1/players/" + anonymous)) return new RuneClansHubClient.Answer(200, DataObject.empty().put("alias", anonymous));
            return new RuneClansHubClient.Answer(404, DataObject.empty());
        });
        Map<String, String> store = new HashMap<>();
        List<String> received = new ArrayList<>();
        PluginEventSink sink = (userId, rsn, events) -> {
            for (PluginPayload.Event e : events) received.add(userId + " " + rsn + " " + e.type() + " " + e.key() + " " + e.sourceName());
        };
        List<Map.Entry<String, Long>> grants = new ArrayList<>();
        RuneClansFeedPoller.Relay relay = new RuneClansFeedPoller.Relay(hub, () -> Optional.ofNullable(store.get("c")), c -> store.put("c", c), List.of(sink), grants::add);

        // first run: skip to the end, nothing passed on
        assertEquals(0, relay.poll());
        assertTrue(received.isEmpty());
        assertNotNull(store.get("c"));

        nextPage.set(DataArray.empty().add(event("2", known, "DROP")).add(event("3", anonymous, "DROP"))
                .add(DataObject.empty().put("id", "4").put("kind", "player.grant_changed").put("alias", known)
                        .put("data", DataObject.empty().put("alias", known).put("revoked", false).put("identity", true))));
        assertEquals(1, relay.poll());
        assertEquals(List.of("123456 Zezima DROP hub:2 Telos"), received);
        assertEquals(List.of(Map.entry(known, 123456L)), grants);
        assertTrue(paths.stream().anyMatch(p -> p.contains("cursor=c")), "the stored cursor is sent");
    }

    @Test
    void feedListenersHearEveryEntryAndOneThatFailsStopsNobody() {
        AtomicReference<DataArray> nextPage = new AtomicReference<>(DataArray.empty());
        RuneClansHubClient hub = new RuneClansHubClient(on(), (method, path, body) -> {
            DataArray page = nextPage.getAndSet(DataArray.empty());
            return new RuneClansHubClient.Answer(200, DataObject.empty().put("data", page).put("nextCursor", "c").put("more", false));
        });
        Map<String, String> store = new HashMap<>();
        List<String> heard = new ArrayList<>();
        HubFeedListener broken = (kind, data) -> {
            throw new IllegalStateException("boom");
        };
        HubFeedListener listening = (kind, data) -> heard.add(kind + " " + (data == null ? "-" : data.getString("item", "-")));
        RuneClansFeedPoller.Relay relay = new RuneClansFeedPoller.Relay(hub, () -> Optional.ofNullable(store.get("c")), c -> store.put("c", c), List.of(),
                g -> { }, List.of(broken, listening));
        relay.poll(); // first run starts at the end
        nextPage.set(DataArray.empty().add(DataObject.empty().put("id", "5").put("kind", "request.changed").put("data", DataObject.empty().put("item", "site_application")))
                .add(DataObject.empty().put("id", "6").put("kind", "site.roles_changed")));
        relay.poll();
        assertEquals(List.of("request.changed site_application", "site.roles_changed -"), heard);
    }

    @Test
    void theFeedIsReadOftenEnoughForARequestCardWithinFiveSeconds() {
        // T-734: a site-side change reaches its Discord card within 5 s (this poll plus the outbox's 1 s drain)
        assertTrue(RuneClansFeedPoller.POLL_EVERY_SECONDS <= 3);
    }

    @Test
    void theCursorIsReadOnceAndSavedOnlyWhenTheFeedMoved() {
        AtomicReference<String> next = new AtomicReference<>("c1");
        RuneClansHubClient hub = new RuneClansHubClient(on(), (method, path, body) ->
                new RuneClansHubClient.Answer(200, DataObject.empty().put("data", DataArray.empty()).put("nextCursor", next.get()).put("more", false)));
        List<String> saves = new ArrayList<>();
        int[] loads = {0};
        RuneClansFeedPoller.Relay relay = new RuneClansFeedPoller.Relay(hub, () -> {
            loads[0]++;
            return Optional.of("c0");
        }, saves::add, List.of(), g -> { });
        relay.poll();
        relay.poll();
        relay.poll();
        assertEquals(1, loads[0], "loaded once, then kept");
        assertEquals(List.of("c1"), saves, "an unchanged cursor is not written again");
        next.set("c2");
        relay.poll();
        assertEquals(List.of("c1", "c2"), saves);
    }

    @Test
    void hubEventsConvertLikeTheBotsOwn() {
        PluginPayload.Event e = RuneClansFeedPoller.Relay.toEvent(event("9", "a1_x", "KILL").getObject("data"));
        assertNotNull(e);
        assertEquals("hub:9", e.key());
        assertEquals("NPC", e.sourceKind());
        assertNull(RuneClansFeedPoller.Relay.toEvent(DataObject.empty().put("type", "NOPE")));
    }
}
