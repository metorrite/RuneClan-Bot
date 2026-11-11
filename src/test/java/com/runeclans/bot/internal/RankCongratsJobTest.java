package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The weekly congrats post (T-594): a long message goes out in Discord-sized parts, and no part can ping anyone. */
class RankCongratsJobTest {
    private static final long GUILD = 1L;

    @Test
    void everyPartOfALongMessageAllowsNoMentions() throws Exception {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getIdLong()).thenReturn(GUILD);
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(guild.getChannelById(GuildMessageChannel.class, 20L)).thenReturn(channel);
        when(channel.canTalk()).thenReturn(true);
        when(channel.getId()).thenReturn("20");
        MessageCreateAction action = mock(MessageCreateAction.class);
        when(channel.sendMessage(anyString())).thenReturn(action);
        when(action.setAllowedMentions(any())).thenReturn(action);
        Message sent = mock(Message.class);
        when(sent.getId()).thenReturn("40");
        when(action.complete(false)).thenReturn(sent);

        String line = "• @everyone (Someone): Corporal\n";
        String text = line.repeat(RankAdvisorRules.DISCORD_MAX / line.length() * 2 + 5);
        int parts = RankAdvisorRules.chunks(text, RankAdvisorRules.DISCORD_MAX).size();
        DataObject result = new RankCongratsJob(mock(SiteCockpitStore.class)).run(jda, new DiscordJob(5L, RankCongratsJob.KIND, GUILD, 0L, null,
                RankCongratsJob.payload(20L, text), DiscordJob.State.RUNNING, 1, 0, null, null, null, null, null));

        assertEquals("40", result.getString("messageId"));
        assertTrue(parts > 1, "the message is split");
        verify(channel, times(parts)).sendMessage(anyString());
        verify(action, times(parts)).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
        verify(action, never()).mentionRoles(any(long[].class));
        verify(action, never()).mentionUsers(any(long[].class));
    }

    @Test
    void theHubsWeekNamesTheLinkedMembersInThisServer() {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SiteCockpitStore cockpit = mock(SiteCockpitStore.class);
        long linked = Long.parseLong("9".repeat(18));
        when(cockpit.links(GUILD)).thenReturn(Map.of("zezima", linked));
        net.dv8tion.jda.api.entities.Member member = mock(net.dv8tion.jda.api.entities.Member.class);
        when(member.getEffectiveName()).thenReturn("Zez");
        when(guild.getMemberById(linked)).thenReturn(member);
        DataObject payload = DataObject.fromJson("""
                {"channelId": 20, "text": "Well done!\\n• Zezima: Corporal\\n• Bob: Sergeant", "template": "Well done!\\n{list}",
                 "promoted": [{"rsnKey": "zezima", "rsn": "Zezima", "from": "Recruit", "to": "Corporal"}, {"rsn": "Bob", "from": "Corporal", "to": "Sergeant"},
                              {"rsn": "", "to": "Corporal"}]}""");
        RankCongratsJob job = new RankCongratsJob(cockpit);
        assertEquals("Well done!\n• @Zez (Zezima): Corporal\n• Bob: Sergeant", job.textFor(guild, payload));

        when(cockpit.links(GUILD)).thenThrow(new RuntimeException("database away"));
        assertEquals("Well done!\n• Zezima: Corporal\n• Bob: Sergeant", job.textFor(guild, payload), "no links: the hub's own text");
        assertEquals("plain", job.textFor(guild, RankCongratsJob.payload(20L, "plain")), "the bot's own payload is posted as it is");
    }

    @Test
    void anUnreadableHubListPostsTheHubsTextAndANameNeverPingsEveryone() {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SiteCockpitStore cockpit = mock(SiteCockpitStore.class);
        long linked = Long.parseLong("9".repeat(18));
        when(cockpit.links(GUILD)).thenReturn(Map.of("zezima", linked));
        net.dv8tion.jda.api.entities.Member member = mock(net.dv8tion.jda.api.entities.Member.class);
        when(member.getEffectiveName()).thenReturn("@everyone");
        when(guild.getMemberById(linked)).thenReturn(member);
        RankCongratsJob job = new RankCongratsJob(cockpit);
        DataObject bad = DataObject.fromJson("{\"text\": \"hub text\", \"template\": \"{list}\", \"promoted\": [1, \"x\"]}");
        assertEquals("hub text", job.textFor(guild, bad), "an unreadable list: the hub's text");
        DataObject named = DataObject.fromJson("{\"text\": \"hub text\", \"template\": \"{list}\", \"promoted\": [{\"rsnKey\": \"zezima\", \"rsn\": \"Zezima\", \"to\": \"Corporal\"}]}");
        assertEquals("• @@\u200beveryone (Zezima): Corporal", job.textFor(guild, named));
    }

    /** B-37: a link saved with underscores or odd spacing names the member the hub folded to spaces. */
    @Test
    void aLinkIsMatchedTheWayTheHubFoldsNames() {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SiteCockpitStore cockpit = mock(SiteCockpitStore.class);
        long linked = Long.parseLong("8".repeat(18));
        when(cockpit.links(GUILD)).thenReturn(Map.of("foo_bar", linked, "a  b", Long.parseLong("7".repeat(18))));
        net.dv8tion.jda.api.entities.Member member = mock(net.dv8tion.jda.api.entities.Member.class);
        when(member.getEffectiveName()).thenReturn("Foo");
        when(guild.getMemberById(linked)).thenReturn(member);
        DataObject payload = DataObject.fromJson("""
                {"channelId": 20, "text": "x", "template": "{list}",
                 "promoted": [{"rsnKey": "foo bar", "rsn": "Foo Bar", "from": "Recruit", "to": "Corporal"}]}""");
        assertEquals("• @Foo (Foo Bar): Corporal", new RankCongratsJob(cockpit).textFor(guild, payload));
    }

    /** B-37: a linked member the bot hasn't cached is asked from Discord; when Discord doesn't answer, the RuneScape name is used. */
    @Test
    @SuppressWarnings("unchecked")
    void anUncachedMemberIsAskedFromDiscord() {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SiteCockpitStore cockpit = mock(SiteCockpitStore.class);
        long linked = Long.parseLong("6".repeat(18));
        when(cockpit.links(GUILD)).thenReturn(Map.of("zezima", linked));
        net.dv8tion.jda.api.entities.Member member = mock(net.dv8tion.jda.api.entities.Member.class);
        when(member.getIdLong()).thenReturn(linked);
        when(member.getEffectiveName()).thenReturn("Zez");
        net.dv8tion.jda.api.utils.concurrent.Task<java.util.List<net.dv8tion.jda.api.entities.Member>> task =
                mock(net.dv8tion.jda.api.utils.concurrent.Task.class);
        when(guild.retrieveMembersByIds(org.mockito.ArgumentMatchers.eq(false), any(long[].class))).thenReturn(task);
        when(task.setTimeout(RankCongratsJob.MEMBER_WAIT)).thenReturn(task);
        when(task.get()).thenReturn(java.util.List.of(member));
        DataObject payload = DataObject.fromJson("""
                {"channelId": 20, "text": "x", "template": "{list}",
                 "promoted": [{"rsnKey": "zezima", "rsn": "Zezima", "from": "Recruit", "to": "Corporal"}]}""");
        RankCongratsJob job = new RankCongratsJob(cockpit);
        assertEquals("• @Zez (Zezima): Corporal", job.textFor(guild, payload));

        when(task.get()).thenThrow(new RuntimeException("timed out"));
        assertEquals("• Zezima: Corporal", job.textFor(guild, payload), "no answer: the RuneScape name only");
    }

    /** Discord takes at most 100 ids per member request, so more uncached members are asked in batches. */
    @Test
    @SuppressWarnings("unchecked")
    void manyUncachedMembersAreAskedInBatches() {
        Guild guild = mock(Guild.class);
        net.dv8tion.jda.api.utils.concurrent.Task<java.util.List<net.dv8tion.jda.api.entities.Member>> task =
                mock(net.dv8tion.jda.api.utils.concurrent.Task.class);
        when(task.setTimeout(RankCongratsJob.MEMBER_WAIT)).thenReturn(task);
        when(task.get()).thenReturn(java.util.List.of());
        java.util.Set<Long> ids = new java.util.HashSet<>();
        long base = Long.parseLong("1".repeat(18));
        for (int i = 0; i < 250; i++) ids.add(base + i);
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        when(guild.retrieveMembersByIds(org.mockito.ArgumentMatchers.eq(false), any(long[].class))).thenAnswer(inv -> {
            sizes.add(inv.getArguments().length - 1);
            return task;
        });
        RankCongratsJob.memberNames(guild, ids);
        assertEquals(java.util.List.of(100, 100, 50), sizes);
    }
}
