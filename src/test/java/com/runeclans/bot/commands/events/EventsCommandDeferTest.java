package com.runeclans.bot.commands.events;

import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventSignups;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import net.dv8tion.jda.api.entities.Message;
import io.github.freya022.botcommands.api.localization.interaction.LocalizableInteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-90: {@code /events mine} and {@code /events board} acknowledge before their hub reads, then answer every path (nothing found, a hub refusal, an
 * unexpected failure) by replacing the "thinking..." message, never with a second reply.
 */
class EventsCommandDeferTest {
    private HubEvents service;
    private EventsCommand command;
    private GuildSlashEvent event;
    private ReplyCallbackAction defer;
    private LocalizableInteractionHook hook;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(HubEvents.class);
        command = new EventsCommand(service, mock(AdminRoleFilter.class), mock(EventSignups.class));
        event = mock(GuildSlashEvent.class, RETURNS_DEEP_STUBS);
        when(event.getGuild().getIdLong()).thenReturn(1L);
        when(event.getUser().getIdLong()).thenReturn(2L);
        AtomicBoolean acknowledged = new AtomicBoolean();
        defer = mock(ReplyCallbackAction.class);
        when(event.deferReply(true)).thenAnswer(i -> {
            acknowledged.set(true);
            return defer;
        });
        when(event.isAcknowledged()).thenAnswer(i -> acknowledged.get());
        hook = mock(LocalizableInteractionHook.class);
        edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginalComponents(anyList())).thenReturn(edit);
        doReturn(hook).when(event).getHook();
    }

    private void assertDeferredThenAnswered() {
        InOrder order = inOrder(event, defer, hook, edit);
        order.verify(event).deferReply(true);
        order.verify(defer).queue();
        order.verify(hook).editOriginalComponents(anyList());
        order.verify(edit).useComponentsV2(true);
        order.verify(edit).queue(any(), any());
        verify(event, never()).replyComponents(anyList());
    }

    @Test
    void mineWithNoEventsAnswersThroughTheHook() {
        when(service.mine(anyLong(), anyLong(), anyInt())).thenReturn(List.of());
        command.onMine(event);
        assertDeferredThenAnswered();
    }

    @Test
    void mineWhenTheHubRefusesAnswersThroughTheHook() {
        when(service.mine(anyLong(), anyLong(), anyInt())).thenThrow(new EventProblem(503, "RuneClans is busy."));
        command.onMine(event);
        assertDeferredThenAnswered();
    }

    @Test
    void mineWhenSomethingUnexpectedFailsStillAnswers() {
        when(service.mine(anyLong(), anyLong(), anyInt())).thenThrow(new IllegalStateException("boom"));
        command.onMine(event);
        assertDeferredThenAnswered();
    }

    @Test
    void boardWithNoRunningBingoAnswersThroughTheHook() {
        when(service.mine(anyLong(), anyLong(), anyInt())).thenReturn(List.of());
        command.onBoard(event, null);
        assertDeferredThenAnswered();
    }

    @Test
    void boardWhenTheHubRefusesAnswersThroughTheHook() {
        when(service.event(anyLong(), anyLong())).thenThrow(new EventProblem(404, "There's no event #5."));
        command.onBoard(event, 5L);
        assertDeferredThenAnswered();
    }

    @Test
    void boardWhenSomethingUnexpectedFailsStillAnswers() {
        when(service.mine(anyLong(), anyLong(), anyInt())).thenThrow(new IllegalStateException("boom"));
        command.onBoard(event, null);
        assertDeferredThenAnswered();
    }
}
