package com.runeclans.bot.discord;

import com.runeclans.bot.eventannounce.EventAnnounceListener;
import com.runeclans.bot.eventannounce.EventAnnounceService;
import com.runeclans.bot.giveaway.GiveawayListener;
import com.runeclans.bot.giveaway.GiveawayRepository;
import com.runeclans.bot.giveaway.GiveawayService;
import com.runeclans.bot.rolepanel.RolePanelListener;
import com.runeclans.bot.rolepanel.RolePanelRepository;
import com.runeclans.bot.suggestion.SuggestionListener;
import com.runeclans.bot.suggestion.SuggestionRepository;
import com.runeclans.bot.suggestion.SuggestionService;
import com.runeclans.bot.tempvoice.TempVoiceListener;
import com.runeclans.bot.tempvoice.TempVoiceRepository;
import com.runeclans.bot.tempvoice.TempVoiceService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-104: a button, menu or box whose handler fails (a database hiccup) still answers the member, instead of leaving Discord's "This interaction
 * failed". And {@link Containers#respond} answers both a fresh and a deferred interaction (B-90, B-101).
 */
class InteractionErrorAnswerTest {
    private static final RuntimeException DB_DOWN = new RuntimeException("pool exhausted");

    private static <T extends IReplyCallback> T pressed(Class<T> type) {
        T event = mock(type, RETURNS_DEEP_STUBS);
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(1L);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(mock(Member.class));
        when(event.isAcknowledged()).thenReturn(false);
        return event;
    }

    private static ButtonInteractionEvent button(String id) {
        ButtonInteractionEvent event = pressed(ButtonInteractionEvent.class);
        when(event.getComponentId()).thenReturn(id);
        return event;
    }

    @Test
    void giveawayEnterAnswersWhenTheDatabaseFails() {
        GiveawayRepository repository = mock(GiveawayRepository.class);
        when(repository.byId(anyLong())).thenThrow(DB_DOWN);
        ButtonInteractionEvent event = button("gw:5");
        new GiveawayListener(repository, mock(GiveawayService.class)).onButtonInteraction(event);
        verify(event).replyComponents(anyList());
    }

    @Test
    void suggestionVoteAnswersWhenTheDatabaseFails() {
        SuggestionRepository repository = mock(SuggestionRepository.class);
        when(repository.byId(anyLong())).thenThrow(DB_DOWN);
        ButtonInteractionEvent event = button("sg:up:7");
        new SuggestionListener(repository, mock(SuggestionService.class)).onButtonInteraction(event);
        verify(event).replyComponents(anyList());
    }

    @Test
    void rolePanelButtonAndMenuAnswerWhenTheDatabaseFails() {
        RolePanelRepository repository = mock(RolePanelRepository.class);
        when(repository.get(anyLong(), anyLong())).thenThrow(DB_DOWN);
        ButtonInteractionEvent press = button("rr:3:4");
        new RolePanelListener(repository).onButtonInteraction(press);
        verify(press).replyComponents(anyList());

        StringSelectInteractionEvent pick = pressed(StringSelectInteractionEvent.class);
        when(pick.getComponentId()).thenReturn("rr:3" + com.runeclans.bot.rolepanel.RolePanelMessage.MENU_SUFFIX);
        new RolePanelListener(repository).onStringSelectInteraction(pick);
        verify(pick).replyComponents(anyList());
    }

    @Test
    void tempVoiceControlsAnswerWhenTheDatabaseFails() {
        TempVoiceRepository repository = mock(TempVoiceRepository.class);
        when(repository.channel(anyLong())).thenThrow(DB_DOWN);
        TempVoiceListener listener = new TempVoiceListener(repository, mock(TempVoiceService.class));

        ButtonInteractionEvent press = button("tv:lock:9");
        listener.onButtonInteraction(press);
        verify(press).replyComponents(anyList());

        EntitySelectInteractionEvent pick = pressed(EntitySelectInteractionEvent.class);
        when(pick.getComponentId()).thenReturn("tv:kicksel:9");
        listener.onEntitySelectInteraction(pick);
        verify(pick).replyComponents(anyList());

        ModalInteractionEvent box = pressed(ModalInteractionEvent.class);
        when(box.getModalId()).thenReturn("tv:renamemodal:9");
        listener.onModalInteraction(box);
        verify(box).replyComponents(anyList());
    }

    @Test
    void eventAnnouncerInterestButtonAnswersWhenItFails() {
        EventAnnounceService service = mock(EventAnnounceService.class);
        when(service.toggleInterest(any(), any(), anyLong())).thenThrow(DB_DOWN);
        ButtonInteractionEvent event = button(EventAnnounceService.BUTTON_PREFIX + "11");
        new EventAnnounceListener(service).onButtonInteraction(event);
        verify(event).replyComponents(anyList());
    }

    @Test
    void otherComponentsAreLeftToTheirOwnListener() {
        GiveawayRepository repository = mock(GiveawayRepository.class);
        ButtonInteractionEvent event = button("poll:1");
        new GiveawayListener(repository, mock(GiveawayService.class)).onButtonInteraction(event);
        verify(event, never()).replyComponents(anyList());
    }

    @Test
    void respondRepliesToAFreshInteraction() {
        ButtonInteractionEvent event = button("x");
        Containers.respond(event, Containers.INFO, "hi");
        verify(event).replyComponents(anyList());
        verify(event.getHook(), never()).editOriginalComponents(anyList());
    }

    @Test
    void respondReplacesTheThinkingMessageOfADeferredOne() {
        ButtonInteractionEvent event = button("x");
        when(event.isAcknowledged()).thenReturn(true);
        InteractionHook hook = mock(InteractionHook.class);
        @SuppressWarnings("unchecked") WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginalComponents(anyList())).thenReturn(edit);
        when(event.getHook()).thenReturn(hook);
        Containers.respond(event, Containers.INFO, "hi");
        verify(hook).editOriginalComponents(anyList());
        verify(edit).queue(any(), any());
        verify(event, never()).replyComponents(anyList());
    }
}
