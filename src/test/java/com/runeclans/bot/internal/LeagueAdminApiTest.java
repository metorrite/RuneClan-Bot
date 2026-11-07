package com.runeclans.bot.internal;

import com.runeclans.bot.events.ClanVsClan;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.leagues.LeagueDiscordStore;
import com.runeclans.bot.leagues.LeagueHub;
import com.runeclans.bot.leagues.LeagueReporter;
import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The dashboard's league routes (T-759): the list with the server's clan-vs-clan events, attaching one of them, and the refusals. */
class LeagueAdminApiTest {
    private static final long GUILD = Long.parseLong("2".repeat(18));
    private static final String LEAGUE = "0dc0dc00-0000-4000-8000-00000000000b";

    private final Map<String, RuneClansHubClient.Read> answers = new HashMap<>();
    private final List<String> written = new ArrayList<>();
    private HubEvents events;
    private LeagueReporter reporter;
    private LeagueDiscordStore discord;
    private LeagueAdminApi api;
    private Guild guild;
    private Member actor;

    private static Event event(long id, String template, Status status) {
        return new Event(id, GUILD, template, "Boss night", "", status, Scope.PUBLIC, ScoringMode.TEAM, Instant.EPOCH, Instant.EPOCH, 0, "{}", 1, Instant.EPOCH, null, false);
    }

    @BeforeEach
    void setUp() {
        Function<String, RuneClansHubClient.Read> reader = answers::get;
        LeagueHub hub = LeagueHub.on(reader, (method, path, body) -> {
            written.add(method + " " + path + " " + body);
            return new HubWriteQueue.Result(HubWriteQueue.Outcome.SENT, new RuneClansHubClient.Answer(200, DataObject.empty().put("number", 2)));
        });
        events = mock(HubEvents.class);
        reporter = mock(LeagueReporter.class);
        discord = mock(LeagueDiscordStore.class);
        when(discord.channel(GUILD)).thenReturn(Optional.empty());
        api = new LeagueAdminApi(hub, reporter, events, discord);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(events.events(eq(GUILD), any(), anyInt())).thenReturn(List.of(event(77, ClanVsClan.KEY, Status.RUNNING), event(78, "bingo", Status.RUNNING)));
        when(events.eventById(77)).thenReturn(Optional.of(event(77, ClanVsClan.KEY, Status.RUNNING)));
        when(events.eventById(78)).thenReturn(Optional.of(event(78, "bingo", Status.RUNNING)));
        when(events.eventById(79)).thenReturn(Optional.empty());
        when(events.eventById(80)).thenReturn(Optional.of(event(80, ClanVsClan.KEY, Status.CANCELLED)));
    }

    private void hubAnswers(int status, DataObject json) {
        answers.put("/v1/guilds/" + GUILD + "/leagues", new RuneClansHubClient.Read(new RuneClansHubClient.Answer(status, json), false, false));
    }

    @Test
    void theListCarriesTheHubsLeaguesAndOnlyClanVsClanEvents() {
        hubAnswers(200, DataObject.empty().put("clanId", "c1").put("clanName", "Alpha").put("leagues", DataArray.empty().add(DataObject.empty().put("id", LEAGUE))));
        DataObject out = api.route(guild, actor, "GET", new String[]{"leagues"}, DataObject::empty);
        assertTrue(out.getBoolean("on"));
        assertTrue(out.getBoolean("clan"));
        assertEquals("Alpha", out.getString("clanName"));
        assertEquals(1, out.getArray("leagues").length());
        assertEquals(1, out.getArray("events").length(), "the bingo event isn't offered");
        assertEquals("77", out.getArray("events").getObject(0).getString("id"));
    }

    @Test
    void aServerWithoutAClanOnTheHubHasNoLeaguesAndTheHubBeingDownIs503() {
        hubAnswers(404, DataObject.empty().put("error", "not_found"));
        DataObject out = api.route(guild, actor, "GET", new String[]{"leagues"}, DataObject::empty);
        assertFalse(out.getBoolean("clan"));
        assertEquals(0, out.getArray("leagues").length());
        answers.clear();
        assertEquals(503, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "GET", new String[]{"leagues"}, DataObject::empty)).status);
        assertNull(api.route(guild, actor, "GET", new String[]{"alliances"}, DataObject::empty), "not this extension's");
        assertEquals(405, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"leagues"}, DataObject::empty)).status);
    }

    @Test
    void theLeagueChannelIsSavedOnlyWhenTheBotCanPostThere() {
        String channelId = "3".repeat(18);
        String otherId = "4".repeat(18);
        long channelNo = Long.parseLong(channelId);
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.getIdLong()).thenReturn(channelNo);
        when(channel.canTalk()).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, channelId)).thenReturn(channel);
        when(actor.getIdLong()).thenReturn(7L);
        DataObject out = api.route(guild, actor, "PUT", new String[]{"leagues", "settings"}, () -> DataObject.empty().put("channelId", channelId));
        assertEquals(channelId, out.getString("channelId"));
        verify(discord).setChannel(GUILD, channelNo, 7L);
        out = api.route(guild, actor, "PUT", new String[]{"leagues", "settings"}, () -> DataObject.empty().put("channelId", null));
        assertTrue(out.isNull("channelId"));
        verify(discord).setChannel(GUILD, null, 7L);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class,
                () -> api.route(guild, actor, "PUT", new String[]{"leagues", "settings"}, () -> DataObject.empty().put("channelId", "100"))).status, "not a channel id");
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class,
                () -> api.route(guild, actor, "PUT", new String[]{"leagues", "settings"}, () -> DataObject.empty().put("channelId", otherId))).status, "not a channel of this server");
        when(channel.canTalk()).thenReturn(false);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class,
                () -> api.route(guild, actor, "PUT", new String[]{"leagues", "settings"}, () -> DataObject.empty().put("channelId", channelId))).status, "the bot can't post there");
        assertEquals(405, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "GET", new String[]{"leagues", "settings"}, DataObject::empty)).status);
        // the list says which channel is picked
        when(discord.channel(GUILD)).thenReturn(Optional.of(channelNo));
        hubAnswers(404, DataObject.empty());
        assertEquals(channelId, api.route(guild, actor, "GET", new String[]{"leagues"}, DataObject::empty).getString("channelId"));
    }

    @Test
    void offSaysSoWithoutAskingTheHub() {
        LeagueAdminApi off = new LeagueAdminApi(LeagueHub.off(), reporter, events, discord);
        DataObject out = off.route(guild, actor, "GET", new String[]{"leagues"}, DataObject::empty);
        assertFalse(out.getBoolean("on"));
        assertFalse(out.getBoolean("clan"));
    }

    @Test
    void attachingSendsTheServersClanVsClanEventAndRefusesAnythingElse() {
        String[] parts = {"leagues", LEAGUE, "rounds", "2", "event"};
        DataObject out = api.route(guild, actor, "POST", parts, () -> DataObject.empty().put("eventId", "77"));
        assertTrue(out.getBoolean("ok"));
        assertFalse(out.getBoolean("queued"));
        assertEquals(2, out.getObject("round").getInt("number"));
        assertEquals(1, written.size());
        assertTrue(written.get(0).startsWith("POST /v1/leagues/" + LEAGUE + "/rounds/2/event "), written.get(0));
        assertTrue(written.get(0).contains("\"eventId\":\"77\""));
        assertTrue(written.get(0).contains("\"name\":\"Boss night\""));
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", parts, () -> DataObject.empty().put("eventId", "78"))).status, "not clan-vs-clan");
        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", parts, () -> DataObject.empty().put("eventId", "79"))).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", parts, () -> DataObject.empty().put("eventId", "80"))).status, "cancelled");
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", parts, () -> DataObject.empty().put("eventId", "x"))).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"leagues", "nope", "rounds", "2", "event"}, DataObject::empty)).status);
        assertEquals(405, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "GET", parts, DataObject::empty)).status);
        assertEquals(1, written.size(), "nothing else reached the hub");
    }

    @Test
    void detachingSendsANullEventAndReportingNeedsAnAttachedFinishedEvent() {
        api.route(guild, actor, "POST", new String[]{"leagues", LEAGUE, "rounds", "2", "detach"}, DataObject::empty);
        assertTrue(written.get(0).contains("\"eventId\":null"), written.get(0));

        String[] report = {"leagues", LEAGUE, "rounds", "2", "report"};
        hubAnswers(200, DataObject.empty().put("leagues", DataArray.empty().add(DataObject.empty().put("id", LEAGUE).put("rounds", DataArray.empty()
                .add(DataObject.empty().put("number", 1))
                .add(DataObject.empty().put("number", 2).put("eventRef", DataObject.empty().put("eventId", "77")))))));
        assertEquals(409, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", report, DataObject::empty)).status, "not finished yet");
        assertEquals(409, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"leagues", LEAGUE, "rounds", "1", "report"}, DataObject::empty)).status, "no event attached");

        when(events.eventById(77)).thenReturn(Optional.of(event(77, ClanVsClan.KEY, Status.DONE)));
        when(reporter.report(any())).thenReturn(new LeagueReporter.Outcome(false, 409, "A participating clan has no clan on RuneClans."));
        assertEquals(409, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", report, DataObject::empty)).status);
        when(reporter.report(any())).thenReturn(new LeagueReporter.Outcome(true, 202, "Saved; it reaches RuneClans shortly."));
        DataObject out = api.route(guild, actor, "POST", report, DataObject::empty);
        assertTrue(out.getBoolean("ok"));
        assertTrue(out.getBoolean("queued"));
    }
}
