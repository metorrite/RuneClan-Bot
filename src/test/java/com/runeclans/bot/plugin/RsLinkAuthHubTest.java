package com.runeclans.bot.plugin;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runeclans.RuneClansSettings;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** With the RuneClans hub on, RsLinkAuth's two calls go to the hub's /v1/link routes; anything else is refused. */
class RsLinkAuthHubTest {
    @Test
    void internalPathsMapToTheHub() throws Exception {
        Map<String, String> env = Map.of("RUNECLANS_HUB_ENABLED", "true", "HUB_API_URL", "http://localhost:8090", "RUNECLANS_HUB_API_KEY", "rsh_" + "k".repeat(43));
        List<String> calls = new ArrayList<>();
        RuneClansHubClient.Transport fake = (method, path, body) -> {
            calls.add(method + " " + path);
            return new RuneClansHubClient.Answer(200, DataObject.empty().put("ok", true));
        };
        Constructor<RuneClansHubClient> make = RuneClansHubClient.class.getDeclaredConstructor(RuneClansSettings.class, RuneClansHubClient.Transport.class);
        make.setAccessible(true);
        RuneClansHubClient hub = make.newInstance(RuneClansSettings.from(env::get), fake);

        RsLinkAuth.Transport t = RsLinkAuth.hubTransport(hub);
        assertEquals(200, t.call("GET", "/internal/rsnlink/v1/keys", null).status());
        assertEquals(200, t.call("POST", "/internal/rsnlink/resolve", "{}").status());
        assertNull(t.call("POST", "/internal/rsnlink/web/links", "{}"));
        assertEquals(List.of("GET /v1/link/keys", "POST /v1/link/resolve"), calls);
    }
}
