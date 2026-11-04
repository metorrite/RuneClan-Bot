package com.runeclans.bot.runescape.stats;

import com.runeclans.bot.runeclans.RuneClansHubClient.Answer;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T-851: the hub's Refresh as the bot asks it, and the words each answer gets. */
class HubRefreshTest {
    @Test
    void theAskNamesThePlayerWithUnderscoresAndThePresserInTheBody() {
        List<String> sent = new ArrayList<>();
        HubRefresh.Poster poster = (path, body) -> {
            sent.add(path + " " + body);
            return new Answer(202, DataObject.empty().put("queued", true));
        };
        String presser = "1".repeat(18);
        HubRefresh.Result r = HubRefresh.ask(poster, "  Lord  of_Bones ", Long.parseLong(presser));
        assertTrue(r.queued());
        assertEquals(List.of("/v1/players/Lord_of_Bones/refresh {\"discordId\":\"" + presser + "\"}"), sent);

        sent.clear();
        HubRefresh.ask(poster, "Zezima", 0);
        assertEquals(List.of("/v1/players/Zezima/refresh {}"), sent, "nobody in particular pressed: no discordId, only the site-wide limit applies");
    }

    @Test
    void eachAnswerHasItsOwnWords() {
        assertEquals(HubRefresh.Outcome.WAIT, HubRefresh.of(new Answer(429, DataObject.empty().put("error", "refresh_actor_wait").put("retryAfterSeconds", 500))).outcome());
        String player = HubRefresh.describe(new HubRefresh.Result(HubRefresh.Outcome.WAIT, 600, "refresh_wait"), "Zezima");
        String presser = HubRefresh.describe(new HubRefresh.Result(HubRefresh.Outcome.WAIT, 500, "refresh_actor_wait"), "Zezima");
        String busy = HubRefresh.describe(new HubRefresh.Result(HubRefresh.Outcome.WAIT, 20, "refresh_busy"), "Zezima");
        assertTrue(player.startsWith("**Zezima** was updated recently"), player);
        assertTrue(player.contains("10 minute"), player);
        assertTrue(presser.startsWith("You refreshed recently"), presser);
        assertTrue(presser.contains("9 minute"), presser);
        assertTrue(busy.startsWith("Lots of players are refreshing"), busy);
        assertTrue(busy.contains("in about a minute"), busy);
        assertNotEquals(player, presser);
        assertNotEquals(presser, busy);

        String unknown = HubRefresh.describe(new HubRefresh.Result(HubRefresh.Outcome.UNKNOWN, 0, "not_found"), "Gone");
        assertTrue(unknown.contains("removed their data"), "a 404 can be permanent: " + unknown);
        assertFalse(unknown.contains("will be"), "no promise the hub can't keep: " + unknown);
        assertTrue(HubRefresh.describe(new HubRefresh.Result(HubRefresh.Outcome.QUEUED, 0, null), "Zezima").contains("within a minute"));
        assertTrue(HubRefresh.describe(new HubRefresh.Result(HubRefresh.Outcome.UNAVAILABLE, 0, "hub_polling_off"), "Zezima").contains("Try again"));
    }
}
