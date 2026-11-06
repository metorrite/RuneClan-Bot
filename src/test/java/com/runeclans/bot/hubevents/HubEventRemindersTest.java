package com.runeclans.bot.hubevents;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.events.EventSpaceRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The hub's {@code clan_event.reminder_due} becomes a post in the event's channel (pinging its participant role), or DMs when the event has no channel. */
class HubEventRemindersTest {
    static final long G = 999_000_000_000_000_937L;
    static final long ANNOUNCEMENTS = 4_001L, ROLE = 5_001L;
    static final UUID CLAN = UUID.randomUUID();
    static final UUID EVENT = UUID.randomUUID();
    private static ConnectionSupplier supplier;
    private static HubEventMap map;
    private FakeEventHub hub;
    private HubEvents events;
    private EventSpaceRepository space;
    private HubEventReminders reminders;
    private JDA jda;
    private Guild guild;
    private GuildMessageChannel channel;
    private final List<String> sent = new ArrayList<>();
    private final List<Collection<Message.MentionType>> mentionsAllowed = new ArrayList<>();
    private final List<Long> rolesPinged = new ArrayList<>();

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        map = new HubEventMap(supplier);
    }

    @BeforeEach
    void setUp() {
        hub = new FakeEventHub();
        events = new HubEvents(new HubEventClient(hub, true), map, new EventChanges());
        space = mock(EventSpaceRepository.class);
        reminders = new HubEventReminders(events, space);
        jda = mock(JDA.class, Mockito.RETURNS_DEEP_STUBS);
        when(jda.getShardManager()).thenReturn(null);
        guild = mock(Guild.class);
        when(jda.getGuildById(G)).thenReturn(guild);
        channel = mock(GuildMessageChannel.class);
        when(channel.getName()).thenReturn("announcements");
        when(channel.canTalk()).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, ANNOUNCEMENTS)).thenReturn(channel);
        when(channel.sendMessage(any(CharSequence.class))).thenAnswer(inv -> {
            sent.add(inv.getArgument(0).toString());
            return mock(MessageCreateAction.class, a -> {
                if (a.getMethod().getName().equals("setAllowedMentions")) mentionsAllowed.add(a.getArgument(0));
                if (a.getMethod().getName().equals("mentionRoles")) {
                    for (Object arg : a.getArguments()) {
                        if (arg instanceof long[] ids) for (long id : ids) rolesPinged.add(id);
                        else if (arg instanceof Long id) rolesPinged.add(id);
                    }
                }
                return a.getMethod().getReturnType().isInstance(a.getMock()) ? a.getMock() : null;
            });
        });
        reminders.useJda(jda);
    }

    @AfterEach
    void cleanUp() throws Exception {
        AutoPostRouter.reset();
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_event_map WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private static DataObject due(String startsAt) {
        DataObject event = DataObject.empty().put("id", EVENT.toString()).put("clanId", CLAN.toString()).put("name", "Boss night").put("status", "SIGNUPS").put("startsAt", startsAt).put("rev", 4);
        return DataObject.empty().put("item", "event").put("id", EVENT.toString()).put("rev", 4)
                .put("data", DataObject.empty().put("event", event).put("minutesBefore", 60).put("remindAt", "2026-11-10T17:00:00Z").put("clanId", CLAN.toString()).put("guildId", Long.toString(G)));
    }

    @Test
    void theReminderGoesInTheAnnouncementsChannelAndPingsOnlyTheParticipantRole() {
        long local = map.localId(EVENT, CLAN, G);
        when(space.channels(local)).thenReturn(List.of(new EventSpaceRepository.ChannelRow(local, "announcements", ANNOUNCEMENTS, "boss-night-announcements", true, "category")));
        when(space.roles(local)).thenReturn(List.of(new EventSpaceRepository.RoleRow(local, "participant", ROLE, "Boss night Participant")));

        String result = reminders.remind(due("2026-11-10T18:00:00Z"));

        assertTrue(result.startsWith("posted in #announcements"), result);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).startsWith("<@&" + ROLE + "> **Boss night** starts <t:" + java.time.Instant.parse("2026-11-10T18:00:00Z").getEpochSecond() + ":R>"), sent.get(0));
        assertEquals(List.of(ROLE), rolesPinged);
        assertTrue(mentionsAllowed.get(0).isEmpty(), "nothing else may ping");
    }

    @Test
    void anEventTheBotNeverMetIsMappedFromTheEntryItself() {
        when(space.channels(anyLong())).thenReturn(List.of());
        hub.ok("GET /v1/events/" + EVENT + "/participants", DataObject.empty().put("participants", DataArray.empty()));
        String result = reminders.remind(due("2026-11-10T18:00:00Z"));
        assertTrue(map.eventByHubId(EVENT).isPresent(), "the entry names the clan and its server");
        assertTrue(result.startsWith("no channel to post in: 0 players DMed"), result);
    }

    @Test
    void withoutAChannelEachPlayerInAPlaceIsDMed() {
        long local = map.localId(EVENT, CLAN, G);
        when(space.channels(local)).thenReturn(List.of());
        hub.ok("GET /v1/events/" + EVENT + "/participants", DataObject.empty().put("participants", DataArray.empty()
                .add(DataObject.empty().put("personId", UUID.randomUUID().toString()).put("discordId", "501").put("waiting", false))
                .add(DataObject.empty().put("personId", UUID.randomUUID().toString()).put("discordId", "502").put("waiting", true))
                .add(DataObject.empty().put("personId", UUID.randomUUID().toString()).put("waiting", false))));

        String result = reminders.remind(due("2026-11-10T18:00:00Z"));

        assertEquals("no channel to post in: 1 players DMed about Boss night", result);
        verify(jda).retrieveUserById(501L);
        verify(jda, never()).retrieveUserById(502L);
        verify(channel, never()).sendMessage(any(CharSequence.class));
    }

    @Test
    void aBotThatIsNotLiveAndAnotherKindPostNothing() {
        long local = map.localId(EVENT, CLAN, G);
        when(space.channels(local)).thenReturn(List.of(new EventSpaceRepository.ChannelRow(local, "announcements", ANNOUNCEMENTS, "a", true, "category")));
        TrackingPostingToggle posting = mock(TrackingPostingToggle.class);
        when(posting.isEnabled()).thenReturn(false);
        new AutoPostRouter(mock(BotConfig.class), posting);
        assertEquals("skipped: this bot is not live", reminders.remind(due("2026-11-10T18:00:00Z")));
        AutoPostRouter.reset();

        reminders.onHubFeed("clan_event.updated", due("2026-11-10T18:00:00Z"));
        assertEquals("skipped: Boss night has no start time", reminders.remind(due(null)));
        verify(channel, never()).sendMessage(any(CharSequence.class));
    }
}
