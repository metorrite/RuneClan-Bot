package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.permission.AdminRoleFilter;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Organisers in the event's settings, and the Approve / Deny buttons only staff and an event's organisers may press. */
class EventOrganisersTest {
    private static final long GUILD = 5L;
    private static final long EVENT = 40L;
    private static final long CLAIM = 7L;
    private static final long ORGANISER = 111L;
    private static final long STRANGER = 222L;

    // ---------- the settings ----------

    @Test
    void organisersAreReadFromTheSettingsWhetherTextOrNumbers() {
        DataObject config = DataObject.fromJson("{\"organisers\":[\"123456789012345678\", 42, \" 7 \"]}");
        assertEquals(Set.of(123456789012345678L, 42L, 7L), EventSettings.parse(config).organisers());
        assertTrue(EventSettings.parse(config).isOrganiser(42));
        assertFalse(EventSettings.parse(config).isOrganiser(43));
        assertTrue(EventSettings.parse(DataObject.empty()).organisers().isEmpty());
    }

    @Test
    void badOrganisersAreReportedInWords() {
        assertTrue(EventSettings.problems(DataObject.fromJson("{\"organisers\":[\"abc\"]}")).stream().anyMatch(p -> p.contains("Discord user")));
        assertTrue(EventSettings.problems(DataObject.fromJson("{\"organisers\":[\"5\",\"5\"]}")).stream().anyMatch(p -> p.contains("listed once")));
        assertTrue(EventSettings.problems(DataObject.fromJson("{\"organisers\":\"nope\"}")).stream().anyMatch(p -> p.contains("list of Discord users")));
        StringBuilder many = new StringBuilder("{\"organisers\":[");
        for (int i = 1; i <= EventSettings.MAX_ORGANISERS + 1; i++) many.append(i == 1 ? "" : ",").append("\"").append(i).append("\"");
        assertTrue(EventSettings.problems(DataObject.fromJson(many + "]}")).stream().anyMatch(p -> p.contains("up to")));
        assertTrue(EventSettings.problems(DataObject.fromJson("{\"organisers\":[\"1\",\"2\"]}")).isEmpty());
    }

    // ---------- the buttons ----------

    private HubEvents service;
    private AdminRoleFilter adminFilter;
    private EventClaimListener listener;
    private Guild guild;
    private Event event;

    @BeforeEach
    void setUp() {
        service = mock(HubEvents.class);
        adminFilter = mock(AdminRoleFilter.class);
        listener = new EventClaimListener(service, adminFilter);
        listener.useExecutor(Runnable::run);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        event = mock(Event.class);
        when(service.event(GUILD, EVENT)).thenReturn(event);
        when(service.mayOrganise(any(), anyLong(), anyBoolean())).thenAnswer(i -> (boolean) i.getArgument(2) || (long) i.getArgument(1) == ORGANISER);
    }

    private static Member member(long id, boolean manageServer) {
        Member member = mock(Member.class);
        when(member.getIdLong()).thenReturn(id);
        when(member.getAsMention()).thenReturn("<@" + id + ">");
        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(manageServer);
        return member;
    }

    /** A message edit that answers every builder call with itself, as the real one does. */
    private static WebhookMessageEditAction edit() {
        return mock(WebhookMessageEditAction.class, Mockito.RETURNS_SELF);
    }

    private ButtonInteractionEvent click(String id, Member member) {
        ButtonInteractionEvent click = mock(ButtonInteractionEvent.class, Mockito.RETURNS_DEEP_STUBS);
        when(click.getComponentId()).thenReturn(id);
        when(click.getGuild()).thenReturn(guild);
        when(click.getMember()).thenReturn(member);
        when(click.getMessage().getContentRaw()).thenReturn("**Bingo · submission #7**");
        when(click.getHook().editOriginal(anyString())).thenReturn(edit());
        return click;
    }

    private ModalInteractionEvent modal(String reason, Member member) {
        ModalInteractionEvent submitted = mock(ModalInteractionEvent.class, Mockito.RETURNS_DEEP_STUBS);
        when(submitted.getModalId()).thenReturn(EventClaimListener.DENY_MODAL + EVENT + ":" + CLAIM);
        when(submitted.getGuild()).thenReturn(guild);
        when(submitted.getMember()).thenReturn(member);
        when(submitted.getMessage().getContentRaw()).thenReturn("**Bingo · submission #7**");
        when(submitted.getHook().editOriginal(anyString())).thenReturn(edit());
        var value = reason == null ? null : mock(net.dv8tion.jda.api.interactions.modals.ModalMapping.class);
        if (value != null) when(value.getAsString()).thenReturn(reason);
        when(submitted.getValue("reason")).thenReturn(value);
        return submitted;
    }

    private static final String APPROVE = EventClaimListener.APPROVE + EVENT + ":" + CLAIM;
    private static final String DENY = EventClaimListener.DENY + EVENT + ":" + CLAIM;

    @Test
    void theButtonsCarryTheEventAndTheClaim() {
        var row = EventClaimListener.buttons(1L, EVENT, CLAIM);
        assertEquals(2, row.getComponents().size());
        assertNotNull(row.getComponents().get(0));
    }

    @Test
    void aStrangerCannotDecideProof() {
        ButtonInteractionEvent click = click(APPROVE, member(STRANGER, false));
        listener.onButtonInteraction(click);
        verify(service, never()).decideClaim(anyLong(), anyLong(), anyLong(), anyBoolean(), anyString(), any());
        verify(click, never()).deferEdit();
    }

    @Test
    void anOrganiserApprovesAndTheButtonsGoAway() {
        ButtonInteractionEvent click = click(APPROVE, member(ORGANISER, false));
        listener.onButtonInteraction(click);
        verify(service).decideClaim(eq(GUILD), eq(EVENT), eq(CLAIM), eq(true), eq(""), argThat(a -> a.discordId() == ORGANISER && a.guildId() == GUILD && !a.staff()));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(click.getHook()).editOriginal(text.capture());
        assertTrue(text.getValue().contains("Approved by <@111>"), text.getValue());
        assertTrue(text.getValue().startsWith("**Bingo · submission #7**"), "the submission text stays");
    }

    @Test
    void staffMayDecideAnyEvent() {
        listener.onButtonInteraction(click(APPROVE, member(STRANGER, true)));
        verify(service).decideClaim(eq(GUILD), eq(EVENT), eq(CLAIM), eq(true), eq(""), argThat(a -> a.discordId() == STRANGER && a.staff()));
    }

    @Test
    void someoneElseGettingThereFirstIsSaidNotRepeated() {
        when(service.decideClaim(eq(GUILD), eq(EVENT), eq(CLAIM), eq(true), eq(""), any())).thenThrow(new EventProblem(409, "Someone has already decided that claim."));
        ButtonInteractionEvent click = click(APPROVE, member(ORGANISER, false));
        listener.onButtonInteraction(click);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(click.getHook()).editOriginal(text.capture());
        assertTrue(text.getValue().contains("already decided"), text.getValue());
    }

    @Test
    void denyAsksForAReasonFirst() {
        ButtonInteractionEvent click = click(DENY, member(ORGANISER, false));
        listener.onButtonInteraction(click);
        ArgumentCaptor<Modal> modal = ArgumentCaptor.forClass(Modal.class);
        verify(click).replyModal(modal.capture());
        assertEquals(EventClaimListener.DENY_MODAL + EVENT + ":" + CLAIM, modal.getValue().getId());
        verify(service, never()).decideClaim(anyLong(), anyLong(), anyLong(), anyBoolean(), anyString(), any());
    }

    @Test
    void aStrangerGetsNoDenyForm() {
        ButtonInteractionEvent click = click(DENY, member(STRANGER, false));
        listener.onButtonInteraction(click);
        verify(click, never()).replyModal(any(Modal.class));
    }

    @Test
    void theReasonIsSentWithTheDenial() {
        ModalInteractionEvent submitted = modal("  Wrong tile  ", member(ORGANISER, false));
        listener.onModalInteraction(submitted);
        verify(service).decideClaim(eq(GUILD), eq(EVENT), eq(CLAIM), eq(false), eq("Wrong tile"), argThat(a -> a.discordId() == ORGANISER));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(submitted.getHook()).editOriginal(text.capture());
        assertTrue(text.getValue().contains("Denied by <@111>: Wrong tile"), text.getValue());
    }

    @Test
    void aDenialWithoutAReasonOrFromAStrangerDoesNothing() {
        listener.onModalInteraction(modal("   ", member(ORGANISER, false)));
        listener.onModalInteraction(modal("Nope", member(STRANGER, false)));
        verify(service, never()).decideClaim(anyLong(), anyLong(), anyLong(), anyBoolean(), anyString(), any());
    }

    @Test
    void aButtonThatIsNotOursIsIgnored() {
        ButtonInteractionEvent click = click("something_else:1", member(ORGANISER, true));
        listener.onButtonInteraction(click);
        verify(service, never()).event(eq(GUILD), anyLong());
    }
}
