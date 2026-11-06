package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.internal.SeasonRules.Row;
import com.runeclans.bot.internal.SeasonRules.Season;
import com.runeclans.bot.internal.SeasonRules.Weights;
import com.runeclans.bot.internal.SiteSeasonStore.Settings;
import com.runeclans.bot.internal.SiteStatsRepository.Gain;
import com.runeclans.bot.internal.SiteStatsRepository.MemberRow;
import com.runeclans.bot.internal.SiteStatsRepository.PointsEarned;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import com.runeclans.bot.testsupport.LocalDatabase;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Clan seasons' tables and the season-end close over a real database (skipped without one), T-596. */
class SiteSeasonsDatabaseTest {
    private static final long G = Long.parseLong("9".repeat(17) + "6");
    private static final long OWNER = Long.parseLong("5".repeat(18));
    private static final long WINNER = Long.parseLong("4".repeat(18));
    private static final long TEAMMATE = Long.parseLong("3".repeat(18));
    private static ConnectionSupplier supplier;
    private static SiteSeasonStore store;

    private SiteStatsRepository stats;
    private MemberProfileRepository profiles;
    private EventService events;
    private EventRepository eventRows;
    private SiteSeasons seasons;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        RuneScapeDatabaseInitializer schema = new RuneScapeDatabaseInitializer(supplier);
        EventRepository repo = new EventRepository(supplier);
        store = new SiteSeasonStore(supplier, schema, repo);
    }

    @BeforeEach
    void wire() {
        stats = mock(SiteStatsRepository.class);
        profiles = mock(MemberProfileRepository.class);
        events = mock(EventService.class);
        eventRows = mock(EventRepository.class);
        when(profiles.hiddenRsns(anyLong(), anyBoolean())).thenReturn(Set.of());
        TrackingPostingToggle posting = mock(TrackingPostingToggle.class);
        when(posting.isEnabled()).thenReturn(true);
        seasons = new SiteSeasons(store, stats, profiles, events, eventRows, mock(SiteRoleGuard.class), mock(DiscordOutbox.class), posting);
        seasons.switchedOn = true;
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : List.of("site_season_settings", "site_season_final", "site_season_standing", "player_link", "clan_member", "event")) {
            sql("DELETE FROM younglings." + table + " WHERE guild_id = " + G);
        }
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            s.executeUpdate();
        }
    }

    private static MemberRow member(String rsn) {
        return new MemberRow(rsn, "Recruit", 0, 0, 0, null, OffsetDateTime.parse("2026-01-01T00:00:00Z"), 0, false, false, null, null, null);
    }

    @Test
    void settingsStartOffAndTurningOnIsDatedOnce() throws Exception {
        assertEquals(Settings.OFF, store.settings(G));
        store.save(G, true, new Weights(2, 1, 0), false, null, OWNER);
        Settings on = store.settings(G);
        assertTrue(on.enabled());
        assertEquals(new Weights(2, 1, 0), on.weights());
        assertNotNull(on.enabledAt());

        sql("UPDATE younglings.site_season_settings SET enabled_at = '2026-01-05T00:00:00Z' WHERE guild_id = " + G);
        store.save(G, true, Weights.EQUAL, false, null, OWNER);
        assertEquals(OffsetDateTime.parse("2026-01-05T00:00:00Z").toInstant(), store.settings(G).enabledAt().toInstant(), "a save while on keeps the date");

        store.save(G, false, Weights.EQUAL, false, null, OWNER);
        assertNull(store.settings(G).enabledAt());
        assertFalse(store.enabled().containsKey(G));
    }

    @Test
    void aSeasonIsKeptOnceAndItsTopThreeGetBadges() {
        List<Row> rows = SeasonRules.standings(List.of(new SeasonRules.Input("Alpha", 30, 0, 0), new SeasonRules.Input("Beta", 20, 0, 0),
                new SeasonRules.Input("Gamma", 10, 0, 0), new SeasonRules.Input("Delta", 5, 0, 0)), new Weights(1, 0, 0));
        assertTrue(store.finish(G, "2026-q3", new Weights(1, 0, 0), rows));
        assertFalse(store.finish(G, "2026-q3", Weights.EQUAL, List.of()), "a second close changes nothing");
        assertEquals(4, store.standings(G, "2026-q3", 10).size());
        assertEquals(new Weights(1, 0, 0), store.finalOf(G, "2026-q3").orElseThrow().weights());
        assertEquals(1, store.badges(G, "alpha").size());
        assertEquals(0, store.badges(G, "Delta").size(), "fourth place gets no badge");
    }

    @Test
    void closingCountsXpPointsAndEventWinsAndRunsOnlyOnce() throws Exception {
        Season q3 = new Season(2026, 3);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-01T00:30:00Z");
        store.save(G, true, Weights.EQUAL, false, null, OWNER);
        sql("UPDATE younglings.site_season_settings SET enabled_at = '2026-08-01T00:00:00Z' WHERE guild_id = " + G);

        when(stats.members(G)).thenReturn(List.of(member("Skiller"), member("Pointy"), member("Victor"), member("Quiet")));
        when(stats.xpGainsBetween(eq(G), any(), any())).thenReturn(List.of(new Gain("Skiller", 50_000_000), new Gain("Quiet", 0)));
        when(stats.pointsBetween(eq(G), eq(null), any(), any())).thenReturn(List.of(new PointsEarned("Pointy", 400)));

        // a team event that finished inside the season, won by Victor's team; and one that finished after it
        sql("INSERT INTO younglings.event (guild_id, template, name, status, created_by, ends_at) VALUES (" + G + ", 'custom', 'Bingo', 'DONE', 1, '2026-09-20T00:00:00Z')");
        sql("INSERT INTO younglings.event (guild_id, template, name, status, created_by, ends_at) VALUES (" + G + ", 'custom', 'Later', 'DONE', 1, '2026-10-02T00:00:00Z')");
        List<Long> finished = store.eventsFinishedBetween(G, q3.from(), q3.to());
        assertEquals(1, finished.size());
        long eventId = finished.getFirst();
        when(events.standings(G, eventId)).thenReturn(List.of(new Standing("t:7", 90, 3, Map.of(), null), new Standing("t:8", 40, 1, Map.of(), null)));
        when(eventRows.teams(eventId)).thenReturn(List.of(new EventRepository.Team(7, eventId, "Red", WINNER)));
        when(eventRows.participants(eventId)).thenReturn(List.of());
        sql("INSERT INTO younglings.player_link (guild_id, discord_user_id, rsn, verification_method) VALUES (" + G + ", " + WINNER + ", 'Victor', 'TEST')");
        sql("INSERT INTO younglings.player_link (guild_id, discord_user_id, rsn, verification_method) VALUES (" + G + ", " + TEAMMATE + ", 'NotInClan', 'TEST')");
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank, active) VALUES (" + G + ", 'Victor', 'Recruit', TRUE)");

        assertEquals(1, seasons.closeFinished(null, now));
        assertEquals(0, seasons.closeFinished(null, now.plusMinutes(30)), "the next tick closes nothing");

        List<Row> kept = store.standings(G, "2026-q3", 10);
        assertEquals(3, kept.size(), "the member with nothing isn't listed");
        assertTrue(kept.stream().allMatch(r -> r.score() == 33.3 && r.place() == 1), "equal weights: each category's best scores the same");
        assertEquals(1, kept.stream().filter(r -> r.rsn().equals("Victor")).findFirst().orElseThrow().wins());

        DataObject answer = seasons.publicAnswer(G, "Victor", now);
        assertTrue(answer.getBoolean("enabled"));
        assertEquals("2026-q4", answer.getObject("season").getString("token"));
        assertEquals("2026-q3", answer.getObject("last").getString("token"));
        DataArray badges = answer.getArray("badges");
        assertEquals(1, badges.length());
        assertEquals("Season champion", badges.getObject(0).getString("title"));
    }

    @Test
    void aSiteThatTurnedSeasonsOnAfterTheSeasonEndedIsLeftAlone() throws Exception {
        store.save(G, true, Weights.EQUAL, false, null, OWNER);
        sql("UPDATE younglings.site_season_settings SET enabled_at = '2026-10-01T00:10:00Z' WHERE guild_id = " + G);
        assertEquals(0, seasons.closeFinished(null, OffsetDateTime.parse("2026-10-01T00:30:00Z")));
        assertTrue(store.finals(G, 5).isEmpty());
    }

    @Test
    void offAnswersOnlyThatItIsOff() {
        assertFalse(seasons.publicAnswer(G, null, OffsetDateTime.parse("2026-10-27T00:00:00Z")).getBoolean("enabled"));
        store.save(G, true, Weights.EQUAL, false, null, OWNER);
        seasons.switchedOn = false;
        assertFalse(seasons.publicAnswer(G, null, OffsetDateTime.parse("2026-10-27T00:00:00Z")).getBoolean("enabled"), "the bot's switch wins");
        assertEquals(0, seasons.closeFinished(null, OffsetDateTime.parse("2027-01-01T01:00:00Z")));
    }
}
