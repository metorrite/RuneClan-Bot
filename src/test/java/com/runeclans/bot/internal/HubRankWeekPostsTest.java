package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The hub's weekly congrats entry (T-976 part 2) becomes one outbox post per server and week, and nothing while posting is off. */
class HubRankWeekPostsTest {
    private static final String GUILD = "4".repeat(18);
    private static final String CHANNEL = "5".repeat(18);
    private static final java.time.Clock DAY_AFTER = java.time.Clock.fixed(java.time.Instant.parse("2026-11-12T12:00:00Z"), java.time.ZoneOffset.UTC);

    private static HubRankWeekPosts posts(DiscordOutbox outbox, TrackingPostingToggle posting) {
        HubRankWeekPosts posts = new HubRankWeekPosts(outbox, posting);
        posts.clock = DAY_AFTER;
        return posts;
    }

    private static DataObject entry(String guild, String channel, String text) {
        DataObject data = DataObject.empty().put("siteId", "s").put("guildId", guild).put("channelId", channel).put("weekStart", "2026-11-11")
                .put("template", "Well done!\n{list}").put("text", text)
                .put("promoted", net.dv8tion.jda.api.utils.data.DataArray.empty().add(DataObject.empty().put("rsn", "Zezima").put("to", "Corporal")));
        return DataObject.empty().put("item", "site_rank_week").put("data", data);
    }

    @Test
    void anEntryIsQueuedOncePerServerAndWeek() {
        HubRankWeekPosts.Plan plan = HubRankWeekPosts.plan(entry(GUILD, CHANNEL, "Well done!\n• Zezima: Corporal"));
        assertEquals(Long.parseLong(GUILD), plan.guildId());
        assertEquals("rankweek:" + GUILD + ":2026-11-11", plan.dedupe());
        assertEquals(Long.parseLong(CHANNEL), plan.payload().getLong("channelId"));
        assertEquals("Well done!\n{list}", plan.payload().getString("template"));
        assertEquals(1, plan.payload().getArray("promoted").length());
        assertNull(HubRankWeekPosts.plan(entry("x", CHANNEL, "t")), "no server");
        assertNull(HubRankWeekPosts.plan(entry(GUILD, CHANNEL, " ")), "no text");
        assertNull(HubRankWeekPosts.plan(DataObject.empty()));
        java.time.Instant weekStart = java.time.Instant.parse("2026-11-11T00:00:00Z");
        assertFalse(HubRankWeekPosts.stale(plan, weekStart.plus(java.time.Duration.ofDays(8))));
        assertTrue(HubRankWeekPosts.stale(plan, weekStart.plus(java.time.Duration.ofDays(10))), "a replay long after the week posts nothing");
    }

    @Test
    void aReplayedEntryPostsOnceEvenAfterTheFirstJobFinished() {
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        TrackingPostingToggle posting = mock(TrackingPostingToggle.class);
        when(posting.isEnabled()).thenReturn(true);
        String key = "rankweek:" + GUILD + ":2026-11-11";
        when(outbox.known(key)).thenReturn(true);
        posts(outbox, posting).onHubFeed(HubRankWeekPosts.KIND, entry(GUILD, CHANNEL, "t"));
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void nothingIsQueuedWhilePostingIsOffOrForOtherKinds() {
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        TrackingPostingToggle posting = mock(TrackingPostingToggle.class);
        HubRankWeekPosts posts = posts(outbox, posting);
        posts.onHubFeed("site.updated", entry(GUILD, CHANNEL, "t"));
        when(posting.isEnabled()).thenReturn(false);
        posts.onHubFeed(HubRankWeekPosts.KIND, entry(GUILD, CHANNEL, "t"));
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), anyString(), any());
        when(posting.isEnabled()).thenReturn(true);
        posts.onHubFeed(HubRankWeekPosts.KIND, entry(GUILD, CHANNEL, "t"));
        verify(outbox).enqueue(eq(RankCongratsJob.KIND), eq(Long.parseLong(GUILD)), eq(0L), eq("rankweek:" + GUILD + ":2026-11-11"), any());
    }
}
