package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.stats.HubRefresh;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T-851: the website's Refresh through the bot goes to the hub's Refresh , with the presser's Discord id. */
class DirectoryPlayerRefreshHubTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private static final class HubDeps implements DirectoryPlayerRefreshRoutes.Deps {
        final List<String> asked = new ArrayList<>();
        HubRefresh.Result answer = new HubRefresh.Result(HubRefresh.Outcome.QUEUED, 0, null);

        @Override
        public Instant lastPoll(String rsn) {
            return rsn.equals("Hidden") ? NOW.minus(Duration.ofHours(1)) : null;
        }

        @Override
        public boolean tracked(String rsn) {
            return !rsn.equals("Nobody");
        }

        @Override
        public boolean shown(String rsn) {
            return !rsn.equals("Nobody") && !rsn.equals("Hidden");
        }

        @Override
        public HubRefresh.Result hubRefresh(String rsn, String discordId) {
            asked.add(rsn + "|" + discordId);
            return answer;
        }
    }

    @Test
    void theHubsAnswersBecomeTheRoutesStatuses() {
        HubDeps deps = new HubDeps();
        DirectoryPlayerRefreshRoutes.Refresher refresher = new DirectoryPlayerRefreshRoutes.Refresher(deps, Clock.fixed(NOW, ZoneOffset.UTC));
        String presser = "1".repeat(18);

        DirectoryPlayerRefreshRoutes.Reply queued = refresher.refresh(DataObject.empty().put("rsn", "Zezima").put("waitMinutes", "30").put("discordId", presser));
        assertEquals(200, queued.status());
        assertEquals("QUEUED", queued.body().getString("status"));
        assertFalse(queued.body().getBoolean("polled"));
        assertEquals(NOW.plus(Duration.ofMinutes(30)).toString(), queued.body().getString("nextAt"));
        assertEquals(List.of("Zezima|" + presser), deps.asked);

        deps.answer = new HubRefresh.Result(HubRefresh.Outcome.WAIT, 600, "refresh_wait");
        DirectoryPlayerRefreshRoutes.Reply wait = refresher.refresh(DataObject.empty().put("rsn", "Zezima"));
        assertEquals(429, wait.status());
        assertEquals(NOW.plusSeconds(600).toString(), wait.body().getString("nextAt"));
        assertEquals("refresh_wait", wait.body().getString("code"));
        assertTrue(wait.body().getString("error").startsWith("Zezima was updated recently"), wait.body().getString("error"));
        deps.answer = new HubRefresh.Result(HubRefresh.Outcome.WAIT, 60, "refresh_actor_wait");
        DirectoryPlayerRefreshRoutes.Reply you = refresher.refresh(DataObject.empty().put("rsn", "Zezima").put("discordId", presser));
        assertEquals("refresh_actor_wait", you.body().getString("code"));
        assertTrue(you.body().getString("error").startsWith("You refreshed recently"), "the presser's own wait is told apart: " + you.body().getString("error"));
        deps.asked.remove(deps.asked.size() - 1);
        assertEquals("Zezima|null", deps.asked.get(1), "no presser named: only the hub's site-wide limit applies");

        deps.answer = new HubRefresh.Result(HubRefresh.Outcome.UNKNOWN, 0, "not_found");
        assertEquals(404, refresher.refresh(DataObject.empty().put("rsn", "Zezima")).status());
        deps.answer = new HubRefresh.Result(HubRefresh.Outcome.UNAVAILABLE, 0, "hub_polling_off");
        assertEquals(503, refresher.refresh(DataObject.empty().put("rsn", "Zezima")).status());
        assertEquals(404, refresher.refresh(DataObject.empty().put("rsn", "Nobody")).status(), "a name nobody tracks stays unknown");
        assertEquals(404, refresher.refresh(DataObject.empty().put("rsn", "Hidden")).status(), "a tracked name the profile page hides stays hidden: no hub poll for it");
        assertEquals(4, deps.asked.size(), "neither refusal reached the hub");
    }

    @Test
    void theBotsOwnSiteWideCapStillHoldsWhileTheHubPolls() {
        HubDeps deps = new HubDeps();
        DirectoryPlayerRefreshRoutes.Refresher refresher = new DirectoryPlayerRefreshRoutes.Refresher(deps, Clock.fixed(NOW, ZoneOffset.UTC));
        for (int i = 0; i < DirectoryPlayerRefreshRoutes.GLOBAL_PER_MINUTE; i++) {
            assertEquals(200, refresher.refresh(DataObject.empty().put("rsn", "Player" + i)).status());
        }
        DirectoryPlayerRefreshRoutes.Reply sixth = refresher.refresh(DataObject.empty().put("rsn", "PlayerX"));
        assertEquals(429, sixth.status(), "a flood from the website never reaches the hub");
        assertEquals(DirectoryPlayerRefreshRoutes.GLOBAL_PER_MINUTE, deps.asked.size());
    }
}
