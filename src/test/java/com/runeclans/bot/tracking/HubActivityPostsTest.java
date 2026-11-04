package com.runeclans.bot.tracking;

import com.runeclans.bot.runescape.PlayerActivity;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** T-851: tracking posts from the hub's {@code player.activity} feed entries. */
class HubActivityPostsTest {
    record Announced(String rsn, List<PlayerActivity> lines) {}

    private static DataObject entry(long id, String rsn, String text) {
        return DataObject.empty().put("id", id).put("rsn", rsn).put("playerId", "p").put("date", "04-Nov-2026 10:00").put("text", text).put("details", "")
                .put("occurredAt", "2026-11-04T10:00:00Z").put("recordedAt", "2026-11-04T10:05:00Z");
    }

    @Test
    void onePostPerEntryGatheredPerPlayerPerPageAndNoneOnAReplay() {
        List<Announced> out = new ArrayList<>();
        HubActivityPosts.Buffer posts = new HubActivityPosts.Buffer(() -> true, (rsn, lines) -> out.add(new Announced(rsn, lines)));

        HubActivityPosts.Buffer.page(posts, List.of(entry(1, "Alice", "I killed a dragon."), entry(2, "Alice", "I killed a dragon."), entry(3, "Bob", "Levelled up Attack.")));
        assertEquals(2, out.size(), "one announcement per player per page");
        assertEquals("Alice", out.get(0).rsn());
        assertEquals(2, out.get(0).lines().size(), "both lines go to the classifier together, so repeats collapse as from one poll");
        assertEquals("Bob", out.get(1).rsn());

        HubActivityPosts.Buffer.page(posts, List.of(entry(1, "Alice", "I killed a dragon."), entry(3, "Bob", "Levelled up Attack.")));
        assertEquals(2, out.size(), "a page the hub sends twice posts nothing twice");

        HubActivityPosts.Buffer.page(posts, List.of(entry(4, "Alice", "Levelled up Magic.")));
        assertEquals(3, out.size());
        assertEquals(0, posts.pendingCount());
    }

    @Test
    void nothingIsPostedWhileTheBotPollsItselfOrForOtherKinds() {
        List<Announced> out = new ArrayList<>();
        AtomicBoolean hubPolls = new AtomicBoolean(false);
        HubActivityPosts.Buffer posts = new HubActivityPosts.Buffer(hubPolls::get, (rsn, lines) -> out.add(new Announced(rsn, lines)));
        HubActivityPosts.Buffer.page(posts, List.of(entry(1, "Alice", "I killed a dragon.")));
        assertTrue(out.isEmpty(), "the bot's own polls announce while it polls");
        hubPolls.set(true);
        posts.onHubFeed("stats.updated", entry(2, "Alice", "x"));
        posts.onHubFeed("player.activity", null);
        posts.onHubFeed("player.activity", DataObject.empty().put("id", 3).put("rsn", "Alice")); // no text
        posts.onHubFeedPage();
        assertTrue(out.isEmpty());
        HubActivityPosts.Buffer.page(posts, List.of(entry(1, "Alice", "I killed a dragon.")));
        assertEquals(1, out.size(), "an entry seen while the bot polled wasn't remembered as posted");
    }
}
