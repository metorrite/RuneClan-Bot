package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.commands.poll.PollSession;
import com.runeclans.bot.commands.signup.SignupMaintenanceSchedulerAccess;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.commands.signup.SignupSession;
import com.runeclans.bot.hubcommunity.HubCommunityProblem;
import com.runeclans.bot.poll.PollStore;
import com.runeclans.bot.signup.SignupStore;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.notice.NoticeRepository;
import com.runeclans.bot.ticket.TicketRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** With {@code hub-community} on, the bot's own timed work stands down, and a hub that can't answer is never shown as "nothing there". */
class HubCommunityStandDownTest {
    private static final long GUILD = Long.parseLong("2".repeat(18));
    private static final HubCommunityProblem DOWN = HubCommunityProblem.refused(0, "RuneClans can't be reached right now.");

    private static PollStore hubPolls() {
        PollStore store = mock(PollStore.class);
        when(store.hub()).thenReturn(true);
        when(store.getAllActivePolls()).thenReturn(List.of());
        return store;
    }

    @Test
    void aHubOutageIsAnErrorNotAClosedPollOrNoPolls() {
        PollStore store = hubPolls();
        when(store.getPollById(anyLong())).thenThrow(DOWN);
        when(store.getActivePolls(GUILD)).thenThrow(DOWN);
        PollService polls = new PollService(store);

        assertThrows(HubCommunityProblem.class, () -> polls.getSessionById(5));
        assertThrows(HubCommunityProblem.class, () -> polls.toggleVote(5, 1, 9), "never POLL_CLOSED while the hub is down");
        assertThrows(HubCommunityProblem.class, () -> polls.activePolls(GUILD), "never an empty list while the hub is down");

        // and the failed read isn't remembered as "gone": once the hub answers, the poll is there
        PollStore back = hubPolls();
        PollSession open = new PollSession(5, GUILD, 1, 2L, "t", false, false, "ACTIVE", 9);
        when(back.getPollById(5)).thenReturn(open);
        assertEquals(open, new PollService(back).getSessionById(5));
    }

    @Test
    void aHubOutageIsAnErrorNotAClosedSignup() {
        SignupStore store = mock(SignupStore.class);
        when(store.hub()).thenReturn(true);
        when(store.getOpenSignups()).thenReturn(List.of());
        when(store.getOpenSignupById(anyLong())).thenThrow(DOWN);
        SignupService signups = new SignupService(store);
        assertThrows(HubCommunityProblem.class, () -> signups.getSessionById(5));
    }

    @Test
    void thePollCloserStandsDownOnTheHub() {
        CommunitySettings settings = mock(CommunitySettings.class);
        PollService polls = mock(PollService.class);
        when(polls.onHub()).thenReturn(true);
        new PollCloser(settings, polls, mock(SiteCache.class)).closeDue(mock(JDA.class));
        verify(settings, never()).due(any(OffsetDateTime.class));
        verify(polls, never()).closePoll(any(), anyLong());
    }

    @Test
    void signupCleanupStandsDownOnTheHub() {
        SignupService signups = mock(SignupService.class);
        when(signups.onHub()).thenReturn(true);
        SignupMaintenanceSchedulerAccess.sweep(signups, mock(JDA.class));
        verify(signups, never()).purgeOldDeletedSignups(any(Instant.class));
        verify(signups, never()).autoCloseInactiveSignups(any(), any(Instant.class));
    }

    @Test
    void theOverviewSaysUnknownInsteadOfFailingWhenTheHubIsDown() {
        PollService polls = mock(PollService.class);
        SignupService signups = mock(SignupService.class);
        when(signups.onHub()).thenReturn(true);
        when(signups.getVisibleSignups(GUILD)).thenThrow(DOWN);
        OverviewAdminApi api = new OverviewAdminApi(mock(AdminOpsApi.class), mock(TicketRepository.class), mock(SiteStatsRepository.class), polls, signups,
                mock(NoticeRepository.class), mock(BotOwners.class));
        DataObject tile = api.community(GUILD);
        assertTrue(tile.getBoolean("unknown"));
        assertEquals(0, tile.getInt("openSignups"));

        // the hub's sheets are what it counts while the switch is on
        SignupService up = mock(SignupService.class);
        when(up.onHub()).thenReturn(true);
        when(up.getVisibleSignups(GUILD)).thenReturn(List.of(mock(SignupSession.class), mock(SignupSession.class)));
        when(polls.activePolls(GUILD)).thenReturn(List.of(mock(PollSession.class)));
        SiteStatsRepository stats = mock(SiteStatsRepository.class);
        DataObject counted = new OverviewAdminApi(mock(AdminOpsApi.class), mock(TicketRepository.class), stats, polls, up, mock(NoticeRepository.class), mock(BotOwners.class))
                .community(GUILD);
        assertEquals(2, counted.getInt("openSignups"));
        assertEquals(1, counted.getInt("openPolls"));
        assertFalse(counted.hasKey("unknown"));
        verify(stats, never()).countActiveSignups(anyLong());
    }
}
