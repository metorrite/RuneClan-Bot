package com.runeclans.bot.hubsites;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which hub feed entries become outbox jobs (T-734), and the server mappings that change with request handler roles. */
class RequestCardsTest {
    // fake ids, made at runtime
    private static final String GUILD = "2".repeat(18);
    private static final String CHANNEL = "6".repeat(18);
    private static final String ROLE = "7".repeat(18);
    private static final String MEMBER = "8".repeat(18);
    private static final String REQUEST = UUID.randomUUID().toString();

    private static DataObject entry(DataObject inner) {
        return DataObject.empty().put("item", "x").put("data", inner);
    }

    private static DataObject cardEntry(String channel) {
        DataObject inner = DataObject.empty().put("siteId", UUID.randomUUID().toString()).put("guildId", GUILD)
                .put("request", DataObject.empty().put("id", REQUEST).put("rev", 2).put("history", DataArray.empty()));
        if (channel != null) inner.put("channelId", channel);
        return entry(inner);
    }

    @Test
    void requestChangesBecomeOneCardJobPerRequest() {
        RequestCards.Job job = RequestCards.job("request.changed", cardEntry(CHANNEL));
        assertEquals(RequestCardJobs.CARD, job.kind());
        assertEquals(Long.parseLong(GUILD), job.guildId());
        assertEquals("request-card:" + REQUEST, job.dedupe(), "a newer change replaces a waiting one");
        assertEquals(CHANNEL, job.payload().getString("channelId"));
        assertEquals(REQUEST, job.payload().getObject("request").getString("id"));
        assertEquals(job.dedupe(), RequestCards.job("request.created", cardEntry(CHANNEL)).dedupe());
        assertFalse(RequestCards.job("request.changed", cardEntry(null)).payload().hasKey("channelId"), "no channel yet: the job says so");
    }

    @Test
    void memberRoleEntriesBecomeRoleJobs() {
        DataObject inner = DataObject.empty().put("guildId", GUILD).put("communityId", "k").put("roleId", ROLE).put("discordId", MEMBER).put("give", true);
        RequestCards.Job job = RequestCards.job("community.member.role", entry(inner));
        assertEquals(RequestCardJobs.ROLE, job.kind());
        assertTrue(job.payload().getBoolean("give"));
        assertEquals("community-role:k:" + MEMBER, job.dedupe(), "leaving soon after joining wins");
        assertNull(RequestCards.job("community.member.role", entry(inner.put("roleId", "@everyone"))));
    }

    @Test
    void anythingElseIsIgnored() {
        assertNull(RequestCards.job("site.roles_changed", cardEntry(CHANNEL)));
        assertNull(RequestCards.job("request.changed", null));
        assertNull(RequestCards.job("request.changed", DataObject.empty()));
        assertNull(RequestCards.job("request.changed", entry(DataObject.empty().put("guildId", "nope").put("request", DataObject.empty().put("id", REQUEST)))));
        assertNull(RequestCards.job("request.changed", entry(DataObject.empty().put("guildId", GUILD).put("request", DataObject.empty().put("id", "x")))));
    }

    @Test
    void handlerRoleChangesRemapTheServer() {
        DataObject feature = entry(DataObject.empty().put("guildId", GUILD).put("feature", DataObject.empty().put("key", "requests.discord")));
        assertEquals(Long.parseLong(GUILD), HubSiteReporter.remappedGuild("feature.changed", feature));
        DataObject other = entry(DataObject.empty().put("guildId", GUILD).put("feature", DataObject.empty().put("key", "discord.feed")));
        assertNull(HubSiteReporter.remappedGuild("feature.changed", other));
        assertEquals(Long.parseLong(GUILD), HubSiteReporter.remappedGuild("site.roles_changed", entry(DataObject.empty().put("guildId", GUILD))));
        // plan C-13: a site changing kind changes which connections apply, so its roles are reported again
        assertEquals(Long.parseLong(GUILD), HubSiteReporter.remappedGuild("site.kind.changed", entry(DataObject.empty().put("guildId", GUILD).put("kind", "clan"))));
        assertNull(HubSiteReporter.remappedGuild("request.changed", feature));
    }

    @Test
    void aCardIsEditedWhereItIsAndOlderChangesNeverPaintOverNewerOnes() {
        DataObject request = DataObject.empty().put("id", REQUEST).put("rev", 3);
        assertEquals(RequestCardJobs.Plan.POST, RequestCardJobs.plan(request, CHANNEL, null, null), "no card yet: post");
        assertEquals(RequestCardJobs.Plan.SKIP, RequestCardJobs.plan(request, CHANNEL, 4L, null), "older than the card");
        assertEquals("post", RequestCardJobs.plan(request, CHANNEL, 3L, null).action(), "the same rev again (re-send): post when there is no card");
        String message = "5".repeat(18);
        DataObject known = DataObject.fromJson(request.toString()).put("external", DataObject.empty().put("channelId", CHANNEL).put("messageId", message));
        assertEquals(new RequestCardJobs.Plan("edit", message), RequestCardJobs.plan(known, CHANNEL, 2L, null), "the hub knows where it is");
        assertEquals(RequestCardJobs.Plan.POST, RequestCardJobs.plan(known, "4".repeat(18), null, null), "another channel now: a new card");
        assertEquals(new RequestCardJobs.Plan("edit", message), RequestCardJobs.plan(request, CHANNEL, null, new String[] {CHANNEL, message}),
                "posted here before the hub heard where");
    }

    @Test
    void theRoleIsOnlyChangedWhenTheMemberIsNotAsAsked() {
        assertEquals("add", RequestCardJobs.roleChange(true, false));
        assertEquals("remove", RequestCardJobs.roleChange(false, true));
        assertNull(RequestCardJobs.roleChange(true, true));
        assertNull(RequestCardJobs.roleChange(false, false));
    }

    @Test
    void aCardTheOutboxCouldNotTakeWaitsAndIsTriedAgain() {
        DiscordOutbox outbox = org.mockito.Mockito.mock(DiscordOutbox.class);
        org.mockito.Mockito.when(outbox.enqueue(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("database down")).thenReturn(null);
        RuneClansHubClient hub = org.mockito.Mockito.mock(RuneClansHubClient.class);
        RequestCards cards = new RequestCards(outbox, hub);
        cards.onHubFeed("request.changed", cardEntry(CHANNEL));
        cards.enqueueWaiting();
        cards.enqueueWaiting();
        org.mockito.Mockito.verify(outbox, org.mockito.Mockito.atLeast(2)).enqueue(org.mockito.ArgumentMatchers.eq(RequestCardJobs.CARD),
                org.mockito.ArgumentMatchers.eq(Long.parseLong(GUILD)), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq("request-card:" + REQUEST), org.mockito.ArgumentMatchers.any());
        int calls = org.mockito.Mockito.mockingDetails(outbox).getInvocations().size();
        cards.enqueueWaiting();
        assertEquals(calls, org.mockito.Mockito.mockingDetails(outbox).getInvocations().size(), "once queued it is not queued again");
    }

    @Test
    void oneJobTheOutboxRefusesHoldsUpNoOther() {
        DiscordOutbox outbox = org.mockito.Mockito.mock(DiscordOutbox.class);
        org.mockito.Mockito.when(outbox.enqueue(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("bad"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("refused"));
        java.util.Map<String, RequestCards.Job> waiting = new java.util.LinkedHashMap<>();
        waiting.put("bad", new RequestCards.Job(RequestCardJobs.CARD, 1L, "bad", DataObject.empty()));
        waiting.put("good", new RequestCards.Job(RequestCardJobs.CARD, 1L, "good", DataObject.empty()));
        assertEquals(1, RequestCards.drain(waiting, outbox));
        assertEquals(java.util.Set.of("bad"), waiting.keySet(), "the refused one waits; the one after it was queued");
        org.mockito.Mockito.verify(outbox).enqueue(RequestCardJobs.CARD, 1L, 0L, "good", DataObject.empty());
    }

    @Test
    void aPressSaysWhatDiscordSaysAboutThePresser() {
        RequestCardLogic.Press accept = RequestCardLogic.parse(RequestCardLogic.buttonId("accepted", REQUEST, 2));
        for (boolean[] flags : new boolean[][] {{false, false}, {true, false}, {false, true}}) {
            net.dv8tion.jda.api.entities.Member member = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Member.class);
            org.mockito.Mockito.when(member.getId()).thenReturn(MEMBER);
            org.mockito.Mockito.when(member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)).thenReturn(flags[0]);
            org.mockito.Mockito.when(member.isOwner()).thenReturn(flags[1]);
            java.util.List<DataObject> sent = new java.util.ArrayList<>();
            RequestCards.press(accept, GUILD, member, (m, p, b, h) -> {
                sent.add(DataObject.fromJson(b));
                return new RuneClansHubClient.Answer(200, DataObject.empty());
            });
            DataObject by = sent.get(0).getObject("by");
            assertEquals(MEMBER, by.getString("discordId"));
            assertEquals(flags[0], by.getBoolean("administrator"), "administrator");
            assertEquals(flags[1], by.getBoolean("owner"), "owner");
            assertEquals(GUILD, sent.get(0).getString("guildId"));
        }
    }
}
