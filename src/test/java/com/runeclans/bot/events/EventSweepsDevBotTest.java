package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.alerts.LiveSwitch;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A bot that is not live leaves events alone on its own (T-187): it neither keeps event roles in step (giving and taking roles) nor runs the wrap-up
 * (locking and deleting channels, taking roles back, posting the archived transcripts). The live bot does both. Mocks only, no Discord.
 */
class EventSweepsDevBotTest {
    private final EventSpaceRepository space = mock(EventSpaceRepository.class);
    private final EventSpaces spaces = mock(EventSpaces.class);
    private final EventSpaceOps ops = new EventSpaceOps(mock(com.runeclans.bot.hubevents.HubEvents.class), space, spaces, mock(EventChanges.class), null, com.runeclans.bot.jobs.JobRepository.inMemory(java.time.Clock.systemUTC()));
    private final EventWrapupOps wrapup = new EventWrapupOps(mock(com.runeclans.bot.hubevents.HubEvents.class), space, ops, spaces, mock(EventChanges.class));

    @AfterEach
    void reset() {
        AutoPostRouter.reset();
    }

    private static JDA noGuilds() {
        JDA jda = mock(JDA.class);
        when(jda.getGuilds()).thenReturn(List.of());
        return jda;
    }

    @Test
    void aBotThatIsNotLiveSkipsTheEventRoleSweep() {
        LiveSwitch.set(false);
        JDA jda = noGuilds();
        ops.sweep(jda);
        verify(jda, never()).getGuilds();
    }

    @Test
    void theLiveBotKeepsEventRolesInStepAsBefore() {
        LiveSwitch.set(true);
        JDA jda = noGuilds();
        ops.sweep(jda);
        verify(jda).getGuilds();
    }

    @Test
    void aBotThatIsNotLiveGivesANewcomerNoEventRoles() {
        LiveSwitch.set(false);
        ops.onMemberJoin(join());
        verify(space, never()).eventsWithSpace(anyLong());
        verify(spaces).forget(anyLong(), anyLong()); // its own cache is still kept right
    }

    @Test
    void theLiveBotLooksForANewcomersEventRolesAsBefore() {
        LiveSwitch.set(true);
        when(space.eventsWithSpace(anyLong())).thenReturn(List.of());
        ops.onMemberJoin(join());
        verify(space).eventsWithSpace(anyLong());
    }

    @Test
    void aBotThatIsNotLiveSkipsTheWrapUpSweep() {
        LiveSwitch.set(false);
        JDA jda = noGuilds();
        wrapup.sweep(jda);
        verify(jda, never()).getGuilds();
    }

    @Test
    void theLiveBotWrapsUpAsBefore() {
        LiveSwitch.set(true);
        JDA jda = noGuilds();
        wrapup.sweep(jda);
        verify(jda).getGuilds();
    }

    private static GuildMemberJoinEvent join() {
        GuildMemberJoinEvent event = mock(GuildMemberJoinEvent.class);
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(1L);
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(2L);
        when(event.getGuild()).thenReturn(guild);
        when(event.getUser()).thenReturn(user);
        return event;
    }
}
