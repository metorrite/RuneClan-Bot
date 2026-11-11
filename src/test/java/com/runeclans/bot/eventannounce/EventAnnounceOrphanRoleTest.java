package com.runeclans.bot.eventannounce;

import com.runeclans.bot.eventannounce.EventAnnounceRules.Settings;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-102: when the announcement can't be posted, the event's role is saved first and then deleted, and the claim is given back. */
class EventAnnounceOrphanRoleTest {
    private static final long EVENT = 11, GUILD = 1, CHANNEL = 5, ROLE = 77;

    private final EventAnnounceRepository repository = mock(EventAnnounceRepository.class);
    private final Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
    private final GuildMessageChannel channel = mock(GuildMessageChannel.class, RETURNS_DEEP_STUBS);
    private final ScheduledEvent event = mock(ScheduledEvent.class);
    private final Role role = mock(Role.class);

    private void setUp(boolean useRole) {
        when(repository.settings(GUILD)).thenReturn(new Settings(GUILD, true, CHANNEL, useRole, true));
        when(repository.claim(EVENT, GUILD)).thenReturn(true);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn(Long.toString(GUILD));
        doReturn(channel).when(guild).getChannelById(GuildMessageChannel.class, CHANNEL);
        when(channel.getGuild()).thenReturn(guild);
        SelfMember self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(any(GuildChannel.class), any(Permission[].class))).thenReturn(true);
        when(self.hasPermission(Permission.MANAGE_ROLES)).thenReturn(true);
        when(guild.getRoles()).thenReturn(List.of());
        when(guild.createRole().setName(anyString()).setPermissions(any(java.util.Collection.class)).setMentionable(true).reason(anyString()).complete()).thenReturn(role);
        when(role.getIdLong()).thenReturn(ROLE);
        when(event.getGuild()).thenReturn(guild);
        when(event.getId()).thenReturn(Long.toString(EVENT));
        when(event.getIdLong()).thenReturn(EVENT);
        when(event.getName()).thenReturn("Raid night");
        when(event.getDescription()).thenReturn("");
        when(event.getStartTime()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(event.getStatus()).thenReturn(ScheduledEvent.Status.SCHEDULED);
        when(event.getLocation()).thenReturn("World 1");
        when(channel.sendMessage(any(MessageCreateData.class)).complete()).thenThrow(new RuntimeException("Discord 503"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedPostSavesThenDeletesTheRoleAndReleasesTheClaim() {
        setUp(true);
        AuditableRestAction<Void> delete = mock(AuditableRestAction.class);
        when(role.delete()).thenReturn(delete);
        when(delete.reason(anyString())).thenReturn(delete);
        EventAnnounceService service = new EventAnnounceService(repository);

        assertThrows(RuntimeException.class, () -> service.announce(event));

        verify(repository).setRole(EVENT, ROLE); // saved before posting: even a crash here leaves it tracked for the sweep
        verify(repository, never()).setMessage(anyLong(), anyLong(), anyLong(), any());
        ArgumentCaptor<Consumer<Void>> deleted = ArgumentCaptor.forClass(Consumer.class);
        verify(delete).queue(deleted.capture(), any());
        verify(repository, never()).release(EVENT); // only once Discord confirms the role is gone
        deleted.getValue().accept(null);
        verify(repository).release(EVENT);
    }

    @Test
    void aFailedPostWithoutARoleReleasesTheClaim() {
        setUp(false);
        EventAnnounceService service = new EventAnnounceService(repository);

        assertThrows(RuntimeException.class, () -> service.announce(event));

        verify(repository, never()).setRole(anyLong(), anyLong());
        verify(repository).release(EVENT);
    }

    @Test
    void aRoleThatCantBeDeletedStaysSavedForTheSweep() {
        setUp(true);
        @SuppressWarnings("unchecked") AuditableRestAction<Void> delete = mock(AuditableRestAction.class);
        when(role.delete()).thenReturn(delete);
        when(delete.reason(anyString())).thenReturn(delete);
        EventAnnounceService service = new EventAnnounceService(repository);

        assertThrows(RuntimeException.class, () -> service.announce(event));

        @SuppressWarnings("unchecked") ArgumentCaptor<Consumer<Throwable>> failed = ArgumentCaptor.forClass(Consumer.class);
        verify(delete).queue(any(), failed.capture());
        failed.getValue().accept(new RuntimeException("Missing permission"));
        verify(repository, never()).release(eq(EVENT));
        verify(repository, never()).clearRole(anyLong());
    }
}
