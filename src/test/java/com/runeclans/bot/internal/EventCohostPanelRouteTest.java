package com.runeclans.bot.internal;

import com.runeclans.bot.alliance.AllianceRepository;
import com.runeclans.bot.alliance.AllianceRules;
import com.runeclans.bot.events.CohostPanels;
import com.runeclans.bot.events.EventCohostRepository;
import com.runeclans.bot.events.EventCohostRepository.Cohost;
import com.runeclans.bot.events.EventCohostRepository.Status;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The co-host signup panel's dashboard side (T-221): the channel picked on accepting or later, who may change it, the host's start-announcement switch. */
class EventCohostPanelRouteTest {
    private static final long HOST = 100, ALLY = 200, EVENT = 42, CHANNEL = 555;
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private EventCohostRepository cohosts;
    private EventRepository events;
    private EventService service;
    private CohostPanels panels;
    private EventCohostApi api;
    private Member actor;
    private Event event;

    @BeforeEach
    void setUp() {
        cohosts = mock(EventCohostRepository.class);
        events = mock(EventRepository.class);
        service = mock(EventService.class);
        panels = mock(CohostPanels.class);
        AllianceRepository alliances = mock(AllianceRepository.class);
        when(alliances.alliancesOf(anyLong())).thenReturn(List.of(new AllianceRepository.Alliance(1, "Friends", HOST, NOW)));
        when(alliances.membersOf(1)).thenReturn(List.of(new AllianceRepository.Membership(1, HOST, AllianceRules.Status.ACTIVE, null, NOW),
                new AllianceRepository.Membership(1, ALLY, AllianceRules.Status.ACTIVE, null, NOW)));
        api = new EventCohostApi(cohosts, events, service, alliances, panels);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(actor.getIdLong()).thenReturn(7L);
        event = new Event(EVENT, HOST, "bingo", "Joint Bingo", "", com.runeclans.bot.events.EventRules.Status.SIGNUPS, Scope.ALLIANCE, ScoringMode.INDIVIDUAL, NOW, null, 0, "{}", 1, NOW, null, false);
        when(service.event(HOST, EVENT)).thenReturn(event);
        when(events.eventById(EVENT)).thenReturn(Optional.of(event));
        EventRepository.Setup open = mock(EventRepository.Setup.class);
        when(events.setup(EVENT)).thenReturn(open);
        when(events.participants(EVENT)).thenReturn(List.of());
        when(events.cohostQuotas(EVENT)).thenReturn(Map.of());
        when(events.setConfig(anyLong(), anyLong(), anyString())).thenReturn(true);
        when(cohosts.forGuild(anyLong())).thenReturn(List.of());
        when(cohosts.forEvent(EVENT)).thenReturn(List.of());
        when(panels.panel(anyLong(), anyLong())).thenReturn(Optional.empty());
    }

    /** The ally's server, where JonnyBot may post in {@link #CHANNEL} if {@code canPost}. */
    private Guild ally(boolean canPost) {
        Guild guild = mock(Guild.class, Mockito.RETURNS_DEEP_STUBS);
        when(guild.getIdLong()).thenReturn(ALLY);
        TextChannel channel = mock(TextChannel.class, Mockito.RETURNS_DEEP_STUBS);
        when(channel.getName()).thenReturn("events");
        when(channel.getGuild().getSelfMember().hasPermission(eq(channel), Mockito.<net.dv8tion.jda.api.Permission>any(), Mockito.<net.dv8tion.jda.api.Permission>any())).thenReturn(canPost);
        when(guild.getTextChannelById(CHANNEL)).thenReturn(channel);
        return guild;
    }

    private DataObject call(Guild guild, String method, String path, String body) {
        return api.route(guild, actor, method, path.split("/"), () -> DataObject.fromJson(body));
    }

    @Test
    void acceptingWithAPanelChannelSavesItAndPutsThePanelUp() {
        when(cohosts.get(EVENT, ALLY)).thenReturn(Optional.of(new Cohost(EVENT, ALLY, Status.INVITED, 7L, null, NOW, null)));
        when(cohosts.decide(EVENT, ALLY, Status.INVITED, Status.ACCEPTED, 7L)).thenReturn(true);
        call(ally(true), "POST", "cohost-events/42/accept", "{\"panelChannelId\":\"" + CHANNEL + "\"}");
        verify(panels).setChannel(eq(event), eq(ALLY), eq(CHANNEL), Mockito.any());
    }

    @Test
    void aChannelJonnyBotCantPostInIsRefusedBeforeAccepting() {
        when(cohosts.get(EVENT, ALLY)).thenReturn(Optional.of(new Cohost(EVENT, ALLY, Status.INVITED, 7L, null, NOW, null)));
        ApiError e = assertThrows(ApiError.class, () -> call(ally(false), "POST", "cohost-events/42/accept", "{\"panelChannelId\":\"" + CHANNEL + "\"}"));
        assertTrue(e.getMessage().contains("#events"), e.getMessage());
        verify(cohosts, never()).decide(anyLong(), anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void onlyAnAcceptedCoHostCanChangeItsPanelChannel() {
        when(cohosts.get(EVENT, ALLY)).thenReturn(Optional.of(new Cohost(EVENT, ALLY, Status.INVITED, 7L, null, NOW, null)));
        assertThrows(ApiError.class, () -> call(ally(true), "PUT", "cohost-events/42/panel", "{\"panelChannelId\":\"" + CHANNEL + "\"}"));
        when(cohosts.get(EVENT, ALLY)).thenReturn(Optional.of(new Cohost(EVENT, ALLY, Status.ACCEPTED, 7L, 8L, NOW, NOW)));
        call(ally(true), "PUT", "cohost-events/42/panel", "{\"panelChannelId\":\"" + CHANNEL + "\"}");
        verify(panels).setChannel(eq(event), eq(ALLY), eq(CHANNEL), Mockito.any());
    }

    @Test
    void removingACoHostTakesItsPanelAndPendingJoinsAway() {
        when(cohosts.get(EVENT, ALLY)).thenReturn(Optional.of(new Cohost(EVENT, ALLY, Status.ACCEPTED, 7L, 8L, NOW, NOW)));
        when(cohosts.decide(EVENT, ALLY, Status.ACCEPTED, Status.REMOVED, 7L)).thenReturn(true);
        Guild host = mock(Guild.class, Mockito.RETURNS_DEEP_STUBS);
        when(host.getIdLong()).thenReturn(HOST);
        call(host, "DELETE", "events/42/cohosts/200", "{}");
        verify(panels).remove(event, ALLY);
        verify(cohosts).deletePendingJoins(EVENT, ALLY);
    }

    @Test
    void theHostSwitchesTheStartAnnouncementOnInTheEventsSettings() {
        Guild host = mock(Guild.class, Mockito.RETURNS_DEEP_STUBS);
        when(host.getIdLong()).thenReturn(HOST);
        call(host, "PUT", "events/42/cohosts/settings", "{\"announceStart\":true}");
        ArgumentCaptor<String> config = ArgumentCaptor.forClass(String.class);
        verify(events).setConfig(eq(HOST), eq(EVENT), config.capture());
        assertEquals(true, DataObject.fromJson(config.getValue()).getBoolean(CohostPanels.ANNOUNCE_START));
        assertThrows(ApiError.class, () -> call(host, "PUT", "events/42/cohosts/settings", "{\"announceStart\":\"yes\"}"));
    }
}
