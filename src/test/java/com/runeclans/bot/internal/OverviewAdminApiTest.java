package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.notice.Notice;
import com.runeclans.bot.notice.NoticeRepository;
import com.runeclans.bot.ticket.TicketRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverviewAdminApiTest {
    private static final long OWNER = 7;

    private NoticeRepository notices;
    private BotOwners owners;
    private OverviewAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        notices = mock(NoticeRepository.class);
        owners = mock(BotOwners.class);
        api = new OverviewAdminApi(mock(AdminOpsApi.class), mock(TicketRepository.class), mock(SiteStatsRepository.class), mock(PollService.class), mock(com.runeclans.bot.commands.signup.SignupService.class), notices, owners);

        guild = mock(Guild.class);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(OWNER);
        when(owners.isOwner(any(), anyLong())).thenReturn(true);
        when(notices.all()).thenReturn(List.of());
    }

    private static DataObject body(String severity, String text) {
        return DataObject.empty().put("severity", severity).put("body", text);
    }

    @Test
    void theBotsOwnerCanPostANotice() {
        api.addNotice(guild, actor, body("issue", "  Tickets are slow right now.  "));

        verify(notices).add("issue", "Tickets are slow right now.", OWNER);
    }

    @Test
    void aServerAdminWhoDoesNotOwnTheBotCannot() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.addNotice(guild, actor, body("info", "hi")));

        assertEquals(403, error.status);
        verify(notices, never()).add(anyString(), anyString(), anyLong());
    }

    @Test
    void aNoticeNeedsTextAndAKnownSeverityAndAReasonableLength() {
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.addNotice(guild, actor, body("info", "   "))).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.addNotice(guild, actor, body("loud", "hi"))).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.addNotice(guild, actor, body("info", "x".repeat(Notice.MAX_BODY + 1)))).status);
    }

    @Test
    void thereIsALimitToHowManyNoticesStayUp() {
        List<Notice> full = Collections.nCopies(Notice.MAX_ACTIVE, new Notice(1, "info", "x", OffsetDateTime.now()));
        when(notices.all()).thenReturn(full);

        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.addNotice(guild, actor, body("info", "one more"))).status);
    }

    @Test
    void removingAGoneNoticeIsANotFoundAndANonNumberIsABadRequest() {
        when(notices.delete(5)).thenReturn(false);

        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.removeNotice(guild, actor, "5")).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.removeNotice(guild, actor, "abc")).status);
    }

    @Test
    void aNonOwnerCannotRemoveOne() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);

        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.removeNotice(guild, actor, "5")).status);
        verify(notices, never()).delete(anyLong());
    }
}
