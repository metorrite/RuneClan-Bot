package com.runeclans.bot.events;

import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.events.EventCohostRepository.PendingJoin;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.hubevents.HubEvents;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Joining from a co-host's signup panel (T-221, on hub events since T-934): someone in the host's Discord joins at once with the co-host's clan as their
 * origin; someone who isn't gets the invite and a pending join; a pending join completes when they join the host's Discord while signups are open, and is
 * gone either way after. Whether a server co-hosts is the hub's word.
 */
class CohostPanelsTest {
    private static final long HOST = 100, ALLY = 200, EVENT = 42, USER = 7;
    private static final UUID ALLY_CLAN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final Actor BOT = Actor.of(77L, HOST, true);

    private HubEvents events;
    private EventCohostRepository cohosts;
    private GuildSettingsService guildSettings;
    private CohostPanels panels;
    private Event event;
    private ClanProfileRepository profiles;

    @BeforeEach
    void setUp() {
        events = mock(HubEvents.class);
        cohosts = mock(EventCohostRepository.class);
        guildSettings = mock(GuildSettingsService.class, Mockito.RETURNS_DEEP_STUBS);
        profiles = mock(ClanProfileRepository.class);
        when(profiles.get(anyLong())).thenReturn(Optional.empty());
        when(guildSettings.getEffective(HOST).inviteUrl()).thenReturn("https://discord.gg/example");
        panels = new CohostPanels(events, cohosts,
                new com.runeclans.bot.clanprofile.JoinLinks(guildSettings, mock(com.runeclans.bot.clanprofile.ClanRecruitingRepository.class, Mockito.RETURNS_DEEP_STUBS)),
                profiles, new EventChanges());
        event = new Event(EVENT, HOST, "bingo", "Joint Bingo", "", Status.SIGNUPS, Scope.ALLIANCE, ScoringMode.INDIVIDUAL, NOW.plusSeconds(86_400), null, 0, "{}", 1, NOW, null, false);
        when(events.eventById(EVENT)).thenReturn(Optional.of(event));
        when(events.setup(EVENT)).thenReturn(EventRepository.Setup.NONE);
        when(events.settingsOf(event)).thenReturn(EventSettings.defaults());
        when(events.self(HOST)).thenReturn(BOT);
        when(events.clanOf(ALLY)).thenReturn(Optional.of(ALLY_CLAN));
        when(events.cohosts(EVENT)).thenReturn(List.of(new HubEvents.Cohost(ALLY_CLAN, "Allies", "ACCEPTED", null, null)));
    }

    @Test
    void aPendingJoinLastsUntilTheDeadlineElseTheStartElseAMonth() {
        Instant deadline = NOW.plusSeconds(3_600), start = NOW.plusSeconds(7_200);
        assertEquals(deadline, CohostPanels.pendingExpiry(deadline, start, NOW));
        assertEquals(start, CohostPanels.pendingExpiry(null, start, NOW));
        assertEquals(start, CohostPanels.pendingExpiry(NOW.minusSeconds(1), start, NOW), "a deadline already past doesn't count");
        assertEquals(NOW.plus(CohostPanels.PENDING_FALLBACK), CohostPanels.pendingExpiry(null, null, NOW));
    }

    @Test
    void whetherAServerCoHostsIsTheHubsWord() {
        assertEquals(ALLY_CLAN, panels.acceptedCohost(EVENT, ALLY));
        when(events.cohosts(EVENT)).thenReturn(List.of(new HubEvents.Cohost(ALLY_CLAN, "Allies", "INVITED", null, null)));
        assertNull(panels.acceptedCohost(EVENT, ALLY), "invited but not accepted");
        when(events.clanOf(ALLY)).thenReturn(Optional.empty());
        assertNull(panels.acceptedCohost(EVENT, ALLY), "a server with no clan on the hub co-hosts nothing");
    }

    @Test
    void someoneInTheHostsDiscordJoinsAtOnceThroughTheCoHost() {
        when(events.join(HOST, EVENT, USER, ALLY_CLAN, BOT)).thenReturn(new EventService.Joined(false, 0));
        String answer = panels.joinNow(event, ALLY_CLAN, USER);
        verify(events).join(HOST, EVENT, USER, ALLY_CLAN, BOT);
        assertTrue(answer.startsWith("You're in **Joint Bingo**"), answer);
    }

    @Test
    void theEventsOwnRulesStillDecide() {
        when(events.join(HOST, EVENT, USER, ALLY_CLAN, BOT)).thenThrow(new EventProblem(409, "This event isn't open to players from other servers."));
        assertEquals("This event isn't open to players from other servers.", panels.joinNow(event, ALLY_CLAN, USER));
    }

    @Test
    void someoneNotInTheHostsDiscordGetsTheInviteAndAPendingJoin() {
        String answer = panels.joinLater(event, ALLY, USER, NOW);
        verify(cohosts).savePendingJoin(EVENT, USER, ALLY, NOW.plusSeconds(86_400));
        verify(events, never()).join(anyLong(), anyLong(), anyLong(), any(UUID.class), any());
        assertTrue(answer.contains("https://discord.gg/example") && answer.contains("automatically"), answer);
    }

    @Test
    void joiningTheHostsDiscordCompletesThePendingJoinOnce() {
        when(cohosts.pendingJoins(USER, HOST, NOW)).thenReturn(List.of(new PendingJoin(EVENT, USER, ALLY, NOW.plusSeconds(3_600))));
        when(events.join(HOST, EVENT, USER, ALLY_CLAN, BOT)).thenReturn(new EventService.Joined(false, 0));
        assertEquals(1, panels.completePendingJoins(HOST, USER, NOW));
        verify(events).join(HOST, EVENT, USER, ALLY_CLAN, BOT);
        verify(cohosts).deletePendingJoin(EVENT, USER);
    }

    @Test
    void aPendingJoinForAnEventThatClosedIsDroppedWithoutJoining() {
        when(events.setup(EVENT)).thenReturn(new EventRepository.Setup(NOW, null, null, null, null, null, null, null, false, "", null, null));
        when(cohosts.pendingJoins(USER, HOST, NOW)).thenReturn(List.of(new PendingJoin(EVENT, USER, ALLY, NOW.plusSeconds(3_600))));
        assertEquals(0, panels.completePendingJoins(HOST, USER, NOW));
        verify(events, never()).join(anyLong(), anyLong(), anyLong(), any(UUID.class), any());
        verify(cohosts).deletePendingJoin(EVENT, USER);
    }

    @Test
    void theStartAnnouncementIsOffUnlessSwitchedOn() {
        assertTrue(!CohostPanels.announceStart(event));
        Event on = new Event(EVENT, HOST, "bingo", "Joint Bingo", "", Status.RUNNING, Scope.ALLIANCE, ScoringMode.INDIVIDUAL, null, null, 0, "{\"cohostAnnounceStart\":true}", 1, NOW, null, false);
        assertTrue(CohostPanels.announceStart(on));
        verify(cohosts, never()).savePendingJoin(eq(EVENT), anyLong(), anyLong(), Mockito.any());
    }

    @Test
    void anEventTheHubDeletedLosesItsPanelsAndPendingJoins() {
        com.runeclans.bot.hubevents.HubEventMap map = mock(com.runeclans.bot.hubevents.HubEventMap.class);
        when(events.map()).thenReturn(map);
        when(map.cohostPanels(EVENT)).thenReturn(List.of(new com.runeclans.bot.hubevents.HubEventMap.CohostPanel(ALLY, 555L, 777L)));
        panels.gone(EVENT);
        verify(map).clearCohostPanel(EVENT, ALLY);
        verify(cohosts).deletePendingJoins(EVENT, null);
    }

    @Test
    void aCoHostsPanelChannelIsTheHubsCoHostRow() {
        com.runeclans.bot.hubevents.HubEventMap map = mock(com.runeclans.bot.hubevents.HubEventMap.class);
        when(events.map()).thenReturn(map);
        when(map.cohostPanel(EVENT, ALLY)).thenReturn(Optional.of(new com.runeclans.bot.hubevents.HubEventMap.CohostPanel(ALLY, 555L, 777L)));
        when(events.cohosts(EVENT)).thenReturn(List.of(new HubEvents.Cohost(ALLY_CLAN, "Allies", "ACCEPTED", null, 555L)));
        when(events.guildOf(eq(ALLY_CLAN), any())).thenReturn(Optional.of(ALLY));
        CohostPanels.Panel panel = panels.panel(EVENT, ALLY).orElseThrow();
        assertEquals(555L, panel.channelId());
        assertEquals(777L, panel.messageId());
        assertEquals(1, panels.panels(EVENT).size());
        assertTrue(panels.panel(EVENT, HOST).isEmpty(), "the host's server has no clan on the hub here");

        panels.setChannel(event, ALLY, 556L, BOT);
        verify(events).setCohostChannel(EVENT, ALLY_CLAN, 556L, BOT);
    }

    // ---------- B-101: Join always answers ----------

    /** A press on the ally's panel whose acknowledgement goes through, so the Join work runs on its lane; the answers land in {@code sent}. */
    @SuppressWarnings("unchecked")
    private ButtonInteractionEvent press(List<String> sent) {
        ButtonInteractionEvent press = mock(ButtonInteractionEvent.class, Mockito.RETURNS_DEEP_STUBS);
        when(press.getComponentId()).thenReturn(CohostPanels.JOIN + EVENT);
        Guild ally = mock(Guild.class);
        when(ally.getIdLong()).thenReturn(ALLY);
        when(press.getGuild()).thenReturn(ally);
        when(press.getUser().getIdLong()).thenReturn(USER);
        InteractionHook hook = mock(InteractionHook.class);
        when(press.getHook()).thenReturn(hook);
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        when(press.deferReply(true)).thenReturn(defer);
        doAnswer(i -> {
            ((Consumer<InteractionHook>) i.getArgument(0)).accept(hook);
            return null;
        }).when(defer).queue(any(), any());
        WebhookMessageCreateAction<Message> send = mock(WebhookMessageCreateAction.class, Mockito.RETURNS_SELF);
        when(hook.sendMessageComponents(Mockito.<Collection<? extends MessageTopLevelComponent>>any())).thenAnswer(i -> {
            Container card = (Container) ((Collection<?>) i.getArgument(0)).iterator().next();
            synchronized (sent) {
                sent.add(card.getComponents().stream().map(c -> ((TextDisplay) c).getContent()).collect(Collectors.joining("\n")));
            }
            return send;
        });
        return press;
    }

    /** {@code press} also finds the host's Discord, and the player is a member there. */
    @SuppressWarnings("unchecked")
    private void inTheHostsDiscord(ButtonInteractionEvent press) {
        JDA jda = mock(JDA.class);
        when(press.getJDA()).thenReturn(jda);
        Guild host = mock(Guild.class);
        when(jda.getGuildById(HOST)).thenReturn(host);
        CacheRestAction<Member> retrieve = mock(CacheRestAction.class);
        when(host.retrieveMemberById(USER)).thenReturn(retrieve);
        doAnswer(i -> {
            ((Consumer<Member>) i.getArgument(0)).accept(mock(Member.class));
            return null;
        }).when(retrieve).queue(any(), any());
    }

    private static String answerOf(List<String> sent) {
        synchronized (sent) {
            assertEquals(1, sent.size(), "exactly one answer: " + sent);
            return sent.get(0);
        }
    }

    private static void awaitAnswer(List<String> sent) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            synchronized (sent) {
                if (!sent.isEmpty()) return;
            }
            Thread.sleep(10);
        }
    }

    @Test
    void aHubFailureReadingTheEventAnswersTryAgain() throws Exception {
        when(events.eventById(EVENT)).thenThrow(new EventProblem(503, "hub down"));
        List<String> sent = new java.util.ArrayList<>();
        panels.onButtonInteraction(press(sent));
        awaitAnswer(sent);
        assertEquals(CohostPanels.TRY_AGAIN, answerOf(sent));
    }

    @Test
    void aHubFailureCheckingTheCoHostAnswersTryAgainNotDoesntCoHost() throws Exception {
        when(events.cohosts(EVENT)).thenThrow(new EventProblem(503, "hub down"));
        List<String> sent = new java.util.ArrayList<>();
        panels.onButtonInteraction(press(sent));
        awaitAnswer(sent);
        assertEquals(CohostPanels.TRY_AGAIN, answerOf(sent));
    }

    @Test
    void anUnexpectedFailureJoiningAnswersTryAgain() throws Exception {
        when(events.join(HOST, EVENT, USER, ALLY_CLAN, BOT)).thenThrow(new IllegalStateException("pool exhausted"));
        List<String> sent = new java.util.ArrayList<>();
        ButtonInteractionEvent press = press(sent);
        inTheHostsDiscord(press);
        panels.onButtonInteraction(press);
        awaitAnswer(sent);
        assertEquals(CohostPanels.TRY_AGAIN, answerOf(sent));
    }

    @Test
    void aJoinThatWentThroughSaysSo() throws Exception {
        when(events.join(HOST, EVENT, USER, ALLY_CLAN, BOT)).thenReturn(new EventService.Joined(false, 0));
        List<String> sent = new java.util.ArrayList<>();
        ButtonInteractionEvent press = press(sent);
        inTheHostsDiscord(press);
        panels.onButtonInteraction(press);
        awaitAnswer(sent);
        assertTrue(answerOf(sent).startsWith("You're in **Joint Bingo**"), sent.toString());
    }

    @Test
    void tidyingUpAfterTheJoinCantTurnItIntoTryAgain() {
        when(events.join(HOST, EVENT, USER, ALLY_CLAN, BOT)).thenReturn(new EventService.Joined(true, 3));
        Mockito.doThrow(new IllegalStateException("db down")).when(cohosts).deletePendingJoin(EVENT, USER);
        when(profiles.get(anyLong())).thenThrow(new IllegalStateException("db down"));
        String answer = panels.joinNow(event, ALLY_CLAN, USER);
        assertTrue(answer.startsWith("**Joint Bingo** is full, so you're on the waitlist (number 3)"), answer);
        assertTrue(answer.contains("the host's server"), answer);
    }

    @Test
    void aPendingJoinIsDroppedQuietlyWhenTheHubCantSayWhoCoHosts() {
        when(cohosts.pendingJoins(USER, HOST, NOW)).thenReturn(List.of(new PendingJoin(EVENT, USER, ALLY, NOW.plusSeconds(3_600))));
        when(events.cohosts(EVENT)).thenThrow(new EventProblem(503, "hub down"));
        assertEquals(0, panels.completePendingJoins(HOST, USER, NOW));
        verify(cohosts).deletePendingJoin(EVENT, USER);
    }
}
