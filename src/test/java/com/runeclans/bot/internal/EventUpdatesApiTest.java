package com.runeclans.bot.internal;

import com.runeclans.bot.events.BingoTemplate;
import com.runeclans.bot.events.BoardUpdates;
import com.runeclans.bot.events.EventBoardPanels;
import com.runeclans.bot.events.EventBoardUpdates;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventUpdateRepository;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Updates section's routes (T-650) over the developer's local Postgres: reading the rules with their previews, saving them, refusing what can't be
 * saved, and each post noted once. Skipped where there is no local database. Everything is made in a made-up server and removed afterwards.
 */
class EventUpdatesApiTest {
    private static final long G = 650_650L;
    private static final long CHANNEL = Long.parseLong("2".repeat(18));

    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventUpdateRepository posts;
    private EventUpdatesApi api;
    private Guild guild;
    private Member actor;
    private long bingo;
    private long custom;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        posts = new EventUpdateRepository(supplier);
        EventService service = Mockito.mock(EventService.class);
        Mockito.when(service.event(ArgumentMatchers.eq(G), ArgumentMatchers.anyLong())).thenAnswer(inv -> repository.event(G, inv.getArgument(1)).orElseThrow());
        Mockito.when(service.objectives(ArgumentMatchers.anyLong())).thenReturn(List.of());
        EventBoardUpdates updates = Mockito.mock(EventBoardUpdates.class);
        api = new EventUpdatesApi(service, repository, posts, Mockito.mock(EventBoardPanels.class), updates);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Mockito.when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(Mockito.mock(GuildMessageChannel.class));
        actor = Mockito.mock(Member.class);
        Mockito.when(actor.getId()).thenReturn("9");
        bingo = repository.createEvent(G, BingoTemplate.KEY, "Bingo night", "", Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, "{\"board\":{\"size\":5}}", 1L);
        custom = repository.createEvent(G, "custom", "Boss night", "", Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, "{}", 1L);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private DataObject call(String method, long id, String body) {
        return api.route(guild, actor, method, new String[] {"events", String.valueOf(id), "updates"}, () -> DataObject.fromJson(body));
    }

    @Test
    void aNewBingoShowsTodaysRulesAndAPreviewOfEveryPost() {
        DataObject view = call("GET", bingo, "{}");
        assertTrue(view.getBoolean("isDefault"));
        assertEquals("LIVE", view.getObject("settings").getString("mode"));
        assertEquals(1, view.getArray("rules").length());
        for (String post : List.of("board", "recap", "firstLine", "firstBlackout", "firstTile")) assertTrue(view.getObject("previews").hasKey(post), post);
        assertEquals(0, view.getArray("posts").length());
        assertFalse(view.getBoolean("standingsHidden"));
    }

    @Test
    void hiddenStandingsAreSaidAndKeptOutOfTheRecapPreview() {
        DataObject config = DataObject.fromJson(repository.event(G, bingo).orElseThrow().config()).put("standings", DataObject.empty().put("visibility", "STAFF"));
        repository.setConfig(G, bingo, config.toString());
        DataObject view = call("PUT", bingo, "{\"recap\":true,\"firstTile\":true}");
        assertTrue(view.getBoolean("standingsHidden"));
        assertTrue(view.getArray("rules").toList().stream().anyMatch(l -> String.valueOf(l).contains("no milestones are announced")));
        assertTrue(view.getObject("previews").getString("recap").contains("only shown to staff"));
    }

    @Test
    void savedRulesAreKeptInTheEventAndDefaultsTakeThemOutAgain() {
        DataObject view = call("PUT", bingo, "{\"mode\":\"DAILY\",\"recap\":true,\"firstBlackout\":true,\"channelId\":\"" + CHANNEL + "\"}");
        assertFalse(view.getBoolean("isDefault"));
        assertEquals(3, view.getArray("rules").length());
        BoardUpdates.Settings saved = BoardUpdates.of(DataObject.fromJson(repository.event(G, bingo).orElseThrow().config()));
        assertEquals(BoardUpdates.Mode.DAILY, saved.mode());
        assertEquals(CHANNEL, saved.channelId());
        assertEquals(5, DataObject.fromJson(repository.event(G, bingo).orElseThrow().config()).getObject("board").getInt("size"), "the rest of the settings are kept");

        call("PUT", bingo, "{\"mode\":\"LIVE\"}");
        assertFalse(DataObject.fromJson(repository.event(G, bingo).orElseThrow().config()).hasKey("updates"));
    }

    @Test
    void whatCannotBeSavedIsRefusedWithReasons() {
        ApiError bad = assertThrows(ApiError.class, () -> call("PUT", bingo, "{\"mode\":\"BATCHED\",\"everyMinutes\":1}"));
        assertEquals(400, bad.status);
        ApiError noChannel = assertThrows(ApiError.class, () -> call("PUT", bingo, "{\"channelId\":\"" + "3".repeat(18) + "\"}"));
        assertEquals(400, noChannel.status);
        ApiError tooBig = assertThrows(ApiError.class, () -> call("PUT", bingo, "{\"channelId\":\"" + "9".repeat(20) + "\"}"));
        assertEquals(400, tooBig.status, "an id that doesn't fit a long is a problem, not a crash");
        ApiError notBingo = assertThrows(ApiError.class, () -> call("PUT", custom, "{\"recap\":true}"));
        assertEquals(409, notBingo.status);
        repository.moveStage(G, bingo, Status.DRAFT, Status.DONE);
        ApiError over = assertThrows(ApiError.class, () -> call("PUT", bingo, "{\"recap\":true}"));
        assertEquals(409, over.status);
    }

    @Test
    void otherPathsAreLeftToOtherRoutes() {
        assertNull(api.route(guild, actor, "GET", new String[] {"events", String.valueOf(bingo), "space"}, DataObject::empty));
        assertNull(api.route(guild, actor, "GET", new String[] {"events"}, DataObject::empty));
    }

    @Test
    void eachPostIsNotedOnceAndShownNewestFirst() {
        assertTrue(posts.claim(bingo, "tile:a", "FIRST_TILE", "first"));
        assertFalse(posts.claim(bingo, "tile:a", "FIRST_TILE", "again"), "the same post is never made twice");
        assertTrue(posts.claim(bingo, "recap:2026-10-26", "RECAP", "recap"));
        assertEquals(2, posts.recent(bingo, 10).size());
        assertEquals(2, call("GET", bingo, "{}").getArray("posts").length());
    }
}
