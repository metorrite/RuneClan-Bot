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
}
