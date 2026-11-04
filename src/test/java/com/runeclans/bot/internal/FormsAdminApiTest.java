package com.runeclans.bot.internal;

import com.runeclans.bot.forms.WebFormRepository;
import com.runeclans.bot.forms.WebForms.Form;
import com.runeclans.bot.forms.WebForms.Kind;
import com.runeclans.bot.forms.WebForms.Question;
import com.runeclans.bot.ticket.TicketModels.Panel;
import com.runeclans.bot.ticket.TicketRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FormsAdminApiTest {
    private static final long GUILD = 10;
    private static final long OTHER_GUILD = 11;

    private WebFormRepository repository;
    private TicketRepository tickets;
    private FormsAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(WebFormRepository.class);
        tickets = mock(TicketRepository.class);
        api = new FormsAdminApi(repository, tickets, com.runeclans.bot.hubcommunity.HubCommunity.off(), null, null);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        actor = mock(Member.class);
        when(repository.list(GUILD)).thenReturn(List.of());
        when(tickets.getPanels(GUILD)).thenReturn(List.of());
    }

    private static Form form(long id, long guildId) {
        return new Form(id, guildId, "Apply", "Apply", "", false, null, null, null, false, 0, List.of(new Question("a", "Name", Kind.SHORT, true, List.of(), 50)));
    }

    private static DataObject enabledBody(String panelId) {
        return DataObject.empty().put("enabled", true).put("panelId", panelId)
                .put("questions", DataArray.fromCollection(List.of(DataObject.empty().put("id", "a").put("label", "Name").put("kind", "SHORT").put("required", true).put("maxLength", 50))));
    }

    @Test
    void aNewFormFromTheClanApplicationTemplateStartsOffInThisServer() {
        when(repository.create(any())).thenAnswer(call -> call.getArgument(0));

        api.route(guild, actor, "POST", new String[]{"forms"}, () -> DataObject.empty().put("template", "clan-application"));

        verify(repository).create(org.mockito.ArgumentMatchers.argThat(f -> f.guildId() == GUILD && !f.enabled() && f.name().equals("Clan application")));
    }

    @Test
    void aServerCannotHaveMoreThanTheLimit() {
        when(repository.list(GUILD)).thenReturn(java.util.Collections.nCopies(10, form(1, GUILD)));

        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"forms"}, DataObject::empty)).status);
        verify(repository, never()).create(any());
    }

    @Test
    void aFormOfAnotherServerCannotBeReadSavedOrDeleted() {
        when(repository.get(5)).thenReturn(Optional.of(form(5, OTHER_GUILD)));

        for (String method : new String[]{"PUT", "DELETE"}) {
            assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, method, new String[]{"forms", "5"}, DataObject::empty)).status);
        }
        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "GET", new String[]{"forms", "5", "submissions"}, DataObject::empty)).status);
        verify(repository, never()).save(any());
        verify(repository, never()).delete(anyLong(), anyLong());
    }

    @Test
    void aPanelFromAnotherServerIsRefused() {
        when(repository.get(5)).thenReturn(Optional.of(form(5, GUILD)));
        Panel foreign = mock(Panel.class);
        when(foreign.guildId()).thenReturn(OTHER_GUILD);
        when(tickets.getPanel(77)).thenReturn(foreign);

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", new String[]{"forms", "5"}, () -> enabledBody("77")));

        assertEquals(400, thrown.status);
        assertTrue(thrown.getMessage().contains("ticket panel"));
        verify(repository, never()).save(any());
    }

    @Test
    void aValidFormIsSavedWithItsPanel() {
        when(repository.get(5)).thenReturn(Optional.of(form(5, GUILD)));
        Panel own = mock(Panel.class);
        when(own.guildId()).thenReturn(GUILD);
        when(tickets.getPanel(77)).thenReturn(own);
        when(repository.save(any())).thenReturn(true);

        api.route(guild, actor, "PUT", new String[]{"forms", "5"}, () -> enabledBody("77"));

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(f -> f.id() == 5 && f.guildId() == GUILD && f.enabled() && Long.valueOf(77).equals(f.panelId())));
    }

    @Test
    void aChannelThatIsNotInThisServerIsRefused() {
        when(repository.get(5)).thenReturn(Optional.of(form(5, GUILD)));
        TextChannel nothing = null;
        when(guild.getTextChannelById(123L)).thenReturn(nothing);
        DataObject body = enabledBody("").put("channelId", "123");

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", new String[]{"forms", "5"}, () -> body));

        assertTrue(thrown.getMessage().contains("channel"));
        verify(repository, never()).save(any());
    }

    @Test
    void otherRoutesAreNotThisExtensions() {
        assertNull(api.route(guild, actor, "GET", new String[]{"welcome"}, DataObject::empty));
    }
}
