package com.runeclans.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.coffer.CofferDatabaseInitializer;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.poll.PollDatabaseInitializer;
import com.runeclans.bot.runescape.ClanPointsRepository;
import com.runeclans.bot.runescape.PlayerActivity;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import com.runeclans.bot.runescape.RuneScapeProfile;
import com.runeclans.bot.runescape.SkillValue;
import com.runeclans.bot.runescape.WeeklyDigestRepository;
import com.runeclans.bot.signup.SignupDatabaseInitializer;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Clan-site answers ({@link SiteApi#tenant}, T-364) over the developer's local Postgres (skipped without one): two made-up servers, each with
 * its own clan, and in the first a member who hid themselves from the rankings and hid their adventure log. Every route is called for both
 * servers: a server only ever sees its own clan, the hidden member is in no list of names (only on the roster, which the game itself makes
 * public), and no Discord user id ever leaves.
 */
class SiteApiTenantDatabaseTest {
    /** What the hub holds: the bot keeps no polled stats or adventure-log lines of its own (T-912). */
    private static final com.runeclans.bot.testsupport.MemoryStats HUB = new com.runeclans.bot.testsupport.MemoryStats();

    // Made-up ids, built at run time (a long literal of this shape trips the secret scanner).
    private static final long GUILD_A = Long.parseLong("7".repeat(17) + "1");
    private static final long GUILD_B = Long.parseLong("7".repeat(17) + "2");
    private static final long USER_SHOWN = Long.parseLong("6".repeat(17) + "1");
    private static final long USER_HIDDEN = Long.parseLong("6".repeat(17) + "2");
    private static final long USER_OTHER = Long.parseLong("6".repeat(17) + "3");
    private static final String SHOWN = "T364Shown";
    private static final String HIDDEN = "T364Hidden";
    private static final String OTHER = "T364Other";
    private static final List<String> NAMES = List.of(SHOWN, HIDDEN, OTHER);
    private static final DateTimeFormatter RUNEMETRICS = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH);

    /** Each tenant route with the query a site would send; the answers that must name the visible member (a check that the data is there at all). */
    private static final Map<String, String> QUERIES = Map.ofEntries(
            Map.entry("members", ""), Map.entry("overview", ""), Map.entry("leaderboard", "period=last-30d"), Map.entry("recap", "period=last-30d"),
            Map.entry("feed", "limit=50"), Map.entry("records", ""), Map.entry("citadel-grid", "weeks=4"), Map.entry("history", ""),
            Map.entry("coffer", ""), Map.entry("polls", ""), Map.entry("signups", ""), Map.entry("events", ""), Map.entry("pvm", ""),
            Map.entry("drops", ""), Map.entry("bosses", "period=last-30d"), Map.entry("boss", "boss=general_graardor&period=last-30d"),
            Map.entry("item", "item=bandos_hilt&period=last-30d"), Map.entry("drop-log", "period=last-30d"),
            Map.entry("member/skill", "rsn=" + SHOWN + "&skill=0"), Map.entry("notifications", ""),
            Map.entry("member", "rsn=" + SHOWN), Map.entry("member/recap", "rsn=" + SHOWN + "&period=last-30d"));
    private static final Set<String> NAMES_THE_SHOWN_MEMBER = Set.of("members", "overview", "leaderboard", "feed", "records", "citadel-grid", "history",
            "coffer", "signups", "pvm", "drops", "drop-log", "notifications");

    private static ConnectionSupplier db;
    // Built once: each initializer runs its whole schema pass, which is slow.
    private static SiteStatsRepository stats;
    private static PlayerLinkRepository links;
    private static WeeklyDigestRepository digest;
    private static ClanPointsRepository points;
    private static MemberProfileRepository profiles;
    private static CommunitySettings community;
    private static WikiCatalog catalog;
    private SiteApi api;
    private com.runeclans.bot.clansite.ClanSitePluginFeed feed;
    private SiteCache cache;
    private Guild guildA;
    private Guild guildB;

    @BeforeAll
    static void connect() throws Exception {
        db = LocalDatabase.supplier();
        var initializer = new RuneScapeDatabaseInitializer(db);
        new SignupDatabaseInitializer(db);
        new PollDatabaseInitializer(db);
        new CofferDatabaseInitializer(db);
        stats = new SiteStatsRepository(db, initializer).withStats(HUB.backends());
        links = new PlayerLinkRepository(db, initializer).withStats(HUB.backends());
        digest = new WeeklyDigestRepository(db, initializer).withStats(HUB.backends());
        points = new ClanPointsRepository(mock(com.runeclans.bot.runeclans.RuneClansHubClient.class));
        profiles = new MemberProfileRepository(db);
        community = new CommunitySettings(db, com.runeclans.bot.testsupport.SiteMappings.off(db));
        catalog = new WikiCatalog();
    }

    static Stream<String> routes() {
        return SiteApi.TENANT_ROUTES.stream().sorted();
    }

    @BeforeEach
    void setUp() throws Exception {
        GuildSettingsService settings = mock(GuildSettingsService.class);
        when(settings.getEffective(anyLong())).thenReturn(mock(GuildSettings.class));
        cache = new SiteCache();
        api = new SiteApi(stats, links, points, settings, digest, profiles, mock(SiteNewsService.class), mock(SignupService.class),
                cache, new RecapService(stats, profiles, digest, settings), community, new BossStatsService(stats, profiles, catalog),
                discordShowingEvents(), feed = mock(com.runeclans.bot.clansite.ClanSitePluginFeed.class));
        guildA = guild(GUILD_A);
        guildB = guild(GUILD_B);

        cleanUp();
        HUB.clear();
        member(GUILD_A, USER_SHOWN, SHOWN, links);
        member(GUILD_A, USER_HIDDEN, HIDDEN, links);
        member(GUILD_B, USER_OTHER, OTHER, links);
        profiles.saveProfile(new MemberProfileRepository.Profile(GUILD_A, USER_HIDDEN, "", null, null, true, true, false, false, false));
        sheetAndPoll(GUILD_A, List.of(SHOWN, HIDDEN), List.of(USER_SHOWN, USER_HIDDEN));
        sheetAndPoll(GUILD_B, List.of(OTHER), List.of(USER_OTHER));
    }

    private static Guild guild(long id) {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(id);
        when(guild.getId()).thenReturn(Long.toString(id));
        when(guild.getScheduledEvents()).thenReturn(List.of());
        return guild;
    }

    /** A clan member with a link, two snapshots ten days apart, adventure-log lines, a roster join and a coffer donation. */
    private static void member(long guildId, long userId, String rsn, PlayerLinkRepository links) throws Exception {
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank) VALUES (?, ?, 'Recruit')", guildId, rsn);
        links.createLink(guildId, userId, rsn, "TEST");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HUB.member(guildId, rsn)
                .snapshot(rsn, now.minusDays(10), 2000, 199_000_000, 120, List.of(new SkillValue(0, 99, 199_000_000, 5)))
                .snapshot(rsn, now.minusMinutes(5), 2000, 200_000_000, 120, List.of(new SkillValue(0, 99, 200_000_000, 4)));
        String date = RUNEMETRICS.format(now.minusHours(2).atZoneSameInstant(ZoneId.of("Europe/London")));
        HUB.activities(rsn, List.of(new PlayerActivity(date, "Capped at my Clan Citadel.", "x", "rs_plog_special_event"), new PlayerActivity(date, "I killed 5 General Graardors.", "x", "rs_plog_npc_kill_count"),
                new PlayerActivity(date, "I found a Bandos hilt.", "x", "rs_plog_item_drop"), new PlayerActivity(date, "Levelled up Attack.", "x", "rs_plog_levelup_count"),
                new PlayerActivity(date, "Quest complete: Desperate Measures", "x", "rs_plog_quest"), new PlayerActivity(date, "I found Shrimpy, the Fishing pet.", "x", "rs_plog_item_found")));
        sql("INSERT INTO younglings.clan_roster_event (guild_id, rsn, event_type, event_at) VALUES (?, ?, 'JOIN', NOW() - INTERVAL '3 days')", guildId, rsn);
        sql("INSERT INTO younglings.coffer_donation (guild_id, donor_name, amount, submitted_by_discord_id) VALUES (?, ?, 1000000, ?)", guildId, rsn, userId);
    }

    private static RuneScapeProfile profile(String rsn, long xp) {
        return new RuneScapeProfile(rsn, 2000, xp, 120, 10, 0, 0, List.of(), List.of());
    }

    /** One open signup sheet with everyone on it (typed names, as a queue sheet stores them) and one open poll. */
    private static void sheetAndPoll(long guildId, List<String> names, List<Long> users) throws Exception {
        long sheet = insertReturningId("INSERT INTO younglings.signup (guild_id, title, created_by_user_id) VALUES (?, 'T364 sheet', ?) RETURNING signup_id", guildId, users.getFirst());
        for (int i = 0; i < names.size(); i++) {
            sql("INSERT INTO younglings.signup_entry (signup_id, discord_user_id, rsn, queue_position, added_by_user_id) VALUES (?, ?, ?, ?, ?)", sheet, users.get(i), names.get(i), i + 1, users.get(i));
        }
        long poll = insertReturningId("INSERT INTO younglings.poll (guild_id, channel_id, title, created_by_user_id) VALUES (?, 1, 'T364 poll', ?) RETURNING poll_id", guildId, users.getFirst());
        sql("INSERT INTO younglings.poll_option (poll_id, option_number, label) VALUES (?, 1, 'Yes')", poll);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (db == null) return;
        stats.withPoints(null);
        for (String table : List.of("clan_member", "player_link", "member_profile", "coffer_donation", "clan_roster_event", "signup", "poll", "member_privacy_default",
                "member_recap_seen", "clan_points_award")) {
            sql("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)", GUILD_A, GUILD_B);
        }
        for (String rsn : NAMES) {
            for (String table : List.of("player_account", "player_stats_snapshot", "player_activity")) {
                sql("DELETE FROM younglings." + table + " WHERE LOWER(rsn) = LOWER(?)", rsn);
            }
        }
    }

    private DataObject call(Guild guild, String route) {
        HttpExchange exchange = mock(HttpExchange.class);
        String query = QUERIES.get(route);
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/x" + (query.isEmpty() ? "" : "?" + query)));
        return api.tenant(guild, route, exchange);
    }

    private static String text(DataObject answer) {
        return answer == null ? "" : answer.toString().toLowerCase(Locale.ROOT);
    }

    /** The same route for a bot-less site: by server id alone, with no Discord guild. */
    private DataObject callWithoutBot(long guildId, String route) {
        HttpExchange exchange = mock(HttpExchange.class);
        String query = QUERIES.get(route);
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/y" + (query.isEmpty() ? "" : "?" + query)));
        return api.tenant(guildId, null, route, exchange);
    }

    /** The answer as text without the period's own from/to (worked out from the clock at each call). */
    private static String timeless(DataObject answer) {
        return text(answer).replaceAll("\"(from|to)\":\"[^\"]*\"", "");
    }

    @Test
    void aBotlessSiteGetsTheSameClanDataByServerIdAndNeedsBotForDiscordOnlyRoutes() {
        // a first read seeds the server's rank list on the way (ClanPointsRepository), so warm every route up before comparing
        for (String route : SiteApi.TENANT_ROUTES) call(guildA, route);
        for (String route : SiteApi.TENANT_ROUTES) {
            cache.clear();
            DataObject withoutBot = callWithoutBot(GUILD_A, route);
            if (SiteApi.DISCORD_ROUTES.contains(route)) {
                assertEquals(SiteApi.needsBot().toString(), withoutBot.toString(), route + " needs Discord");
            } else if (!route.equals("notifications")) {
                cache.clear();
                assertEquals(timeless(call(guildA, route)), timeless(withoutBot), route + ": the stats are stored by server id, so the answer is the same");
            }
        }
        DataObject bell = callWithoutBot(GUILD_A, "notifications");
        assertTrue(bell.getBoolean("needsBot"));
        assertEquals(0, bell.getArray("events").length());
        assertEquals(0, bell.getArray("polls").length(), "the bot's polls aren't shown without it");
        assertTrue(bell.getArray("feed").length() > 0, "the activity lines are");
    }

    @Test
    void everyTenantRouteHasAQueryHere() {
        assertEquals(SiteApi.TENANT_ROUTES, QUERIES.keySet(), "a new tenant route needs its row here, so its privacy is tested");
    }

    @ParameterizedTest
    @MethodSource("routes")
    void aServerSeesOnlyItsOwnClanAndNeverAHiddenMemberOrADiscordId(String route) {
        String a = text(call(guildA, route));
        String b = text(call(guildB, route));

        assertFalse(a.contains(OTHER.toLowerCase()), route + ": the other server's member is not in this server's answer");
        assertFalse(b.contains(SHOWN.toLowerCase()) || b.contains(HIDDEN.toLowerCase()), route + ": nor the other way round");
        if (route.equals("members")) {
            assertTrue(a.contains(HIDDEN.toLowerCase()), "the roster lists everyone on the in-game clan list, as the home site does");
        } else {
            assertFalse(a.contains(HIDDEN.toLowerCase()), route + ": a member hidden from the rankings (and with a hidden log) never appears");
        }
        for (long user : List.of(USER_SHOWN, USER_HIDDEN, USER_OTHER)) {
            assertFalse(a.contains(Long.toString(user)) || b.contains(Long.toString(user)), route + ": no Discord user id leaves the bot");
        }
        if (NAMES_THE_SHOWN_MEMBER.contains(route)) {
            assertTrue(a.contains(SHOWN.toLowerCase()), route + ": the visible member is there (so the check above means something): " + a);
            assertTrue(b.contains(OTHER.toLowerCase()), route + ": and the second server gets its own clan's data: " + b);
        }
    }

    @Test
    void theDropLogGridSaysWhoGotEachItemFirst() {
        DataObject log = call(guildA, "drop-log");
        DataObject hilt = null;
        DataArray grid = log.getArray("grid");
        for (int i = 0; i < grid.length(); i++) if (grid.getObject(i).getString("key").equals("bandos_hilt")) hilt = grid.getObject(i);
        assertNotNull(hilt, "the hilt is in the grid");
        assertEquals(SHOWN, hilt.getString("firstRsn"), "the visible member got it first (the hidden member's drop isn't counted)");
        assertFalse(hilt.isNull("first"));
    }

    @Test
    void oneSkillSeriesOnlyForAVisibleMemberOfThatClan() {
        HttpExchange shown = exchange("rsn=" + SHOWN + "&skill=0");
        assertNotNull(api.tenant(guildA, "member/skill", shown));
        assertNull(api.tenant(guildA, "member/skill", exchange("rsn=" + HIDDEN + "&skill=0")), "hidden from the rankings");
        assertNull(api.tenant(guildA, "member/skill", exchange("rsn=" + OTHER + "&skill=0")), "not in this clan");
        assertNull(api.tenant(guildB, "member/skill", exchange("rsn=" + SHOWN + "&skill=0")), "the same name asked of the other server");
    }

    @Test
    void theRecapCarriesTheStoryTreesDataStreamsWithoutHiddenMembers() {
        DataObject recap = call(guildA, "recap");
        DataObject activity = recap.getObject("activity");
        assertEquals("Attack", activity.getArray("levelUpSkills").getObject(0).getString("skill"), "T-653: level-ups by skill");
        assertTrue(activity.getArray("questNames").toList().contains("Desperate Measures"));
        assertTrue(activity.getLong("pets") >= 1);
        assertEquals(1, recap.getObject("xp").getLong("skillsTrained"), "one skill gained XP");
        assertTrue(recap.hasKey("previous"), "the window before, for growth");
        DataArray levelers = recap.getArray("topLevelers");
        for (int i = 0; i < levelers.length(); i++) assertNotEquals(HIDDEN, levelers.getObject(i).getString("rsn"), "a hidden member is never named");
        DataObject mine = api.tenant(guildA, "member/recap", exchange("rsn=" + SHOWN + "&period=last-30d", USER_SHOWN));
        assertEquals("Attack", mine.getObject("activity").getArray("levelUpSkills").getObject(0).getString("skill"));
        assertTrue(mine.hasKey("previous"));
    }

    @Test
    void aMemberPageAndMemberRecapOnlyForAVisibleMemberOfThatClan() {
        for (String route : List.of("member", "member/recap")) {
            String period = route.equals("member") ? "" : "&period=last-30d";
            // a member recap is asked for by its own member here (others wait until they've opened it: theMemberRecapIsPrivateUntil...)
            DataObject page = api.tenant(guildA, route, exchange("rsn=" + SHOWN + period, USER_SHOWN));
            assertNotNull(page, route);
            assertTrue(text(page).contains(SHOWN.toLowerCase()), route);
            assertNull(api.tenant(guildA, route, exchange("rsn=" + HIDDEN + period, USER_HIDDEN)), route + ": a member hidden from the rankings has no page (404, like an unknown name), even for themselves");
            assertNull(api.tenant(guildA, route, exchange("rsn=" + OTHER + period, USER_OTHER)), route + ": not in this clan");
            assertNull(api.tenant(guildB, route, exchange("rsn=" + SHOWN + period, USER_SHOWN)), route + ": the same name asked of the other server");
            assertNotNull(api.tenant(guildB, route, exchange("rsn=" + OTHER + period, USER_OTHER)), route + ": the other server's own member");
        }
        assertNotNull(api.member(guildA, HIDDEN), "the home site's member page is unchanged");
    }

    private static java.util.Map<String, Boolean> pagePublic(DataObject members) {
        java.util.Map<String, Boolean> out = new java.util.HashMap<>();
        DataArray rows = members.getArray("members");
        for (int i = 0; i < rows.length(); i++) out.put(rows.getObject(i).getString("rsn"), rows.getObject(i).getBoolean("pagePublic"));
        return out;
    }

    @Test
    void aMemberPageSaysWhereTheMemberStandsAmongTheLeadersButNeverForAHiddenOne() {
        DataObject shown = api.member(guildA, SHOWN);
        DataObject standing = shown.getObject("standing");
        assertEquals(1, standing.getInt("weekGain"), "the only visible gainer this week is first");
        assertTrue(standing.isNull("points"), "no clan points, so not on the points list");
        assertTrue(shown.hasKey("maxRankOrder"));

        DataObject hidden = api.member(guildA, HIDDEN).getObject("standing");
        assertTrue(hidden.isNull("weekGain") && hidden.isNull("points"), "a member hidden from boards has no place on them");
    }

    @Test
    void theLeaderboardRaceShowsOnlyActiveMembersWhoMayBeRanked() throws Exception {
        String former = "T710Former";
        String alt = "T710AltName";
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank, active) VALUES (?, ?, 'Recruit', FALSE)", GUILD_A, former);
        // clan points are the hub's (T-978): 25 each yesterday
        ClanPointsRepository history = mock(ClanPointsRepository.class);
        java.time.LocalDate yesterday = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        when(history.earned(org.mockito.ArgumentMatchers.eq(GUILD_A), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(inv -> {
            boolean byDay = inv.getArgument(4);
            return List.of(SHOWN, HIDDEN, former, alt).stream().map(rsn -> new ClanPointsRepository.Earned(rsn, byDay ? yesterday : null, 25)).toList();
        });
        stats.withPoints(history);
        var racing = org.mockito.ArgumentCaptor.<java.util.function.Predicate<String>>captor();
        when(feed.xpRace(org.mockito.ArgumentMatchers.eq(GUILD_A), racing.capture())).thenReturn(null);

        DataObject overview = call(guildA, "overview");
        String month = overview.getArray("topPointsMonth").toString().toLowerCase(Locale.ROOT);
        String points = text(overview.getObject("race"));
        assertTrue(month.contains(SHOWN.toLowerCase(Locale.ROOT)), month);
        for (String out : List.of(HIDDEN, former, alt)) {
            assertFalse(month.contains(out.toLowerCase(Locale.ROOT)), out + " in " + month);
            assertFalse(points.contains(out.toLowerCase(Locale.ROOT)), out + " in " + points);
        }
        assertTrue(racing.getValue().test(SHOWN) && racing.getValue().test(SHOWN.toUpperCase(Locale.ROOT)), "a shown member races, however the hub cased the name");
        for (String out : List.of(HIDDEN, former, alt)) assertFalse(racing.getValue().test(out), out + " must not race in the XP race");
    }

    @Test
    void theRosterSaysWhoseMemberPageIsPrivate() {
        java.util.Map<String, Boolean> shown = pagePublic(call(guildA, "members"));
        assertEquals(Boolean.TRUE, shown.get(SHOWN), "T-416: a visible member's page can be opened");
        assertEquals(Boolean.FALSE, shown.get(HIDDEN), "T-416: a member hidden from the rankings keeps their page private");
    }

    @Test
    void privacyDefaultsHideMembersWhoHaveNotChosenAndNeverShowAHiddenOne() throws Exception {
        String unlinked = "T395Unlinked";
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank) VALUES (?, ?, 'Recruit')", GUILD_A, unlinked);
        assertTrue(pagePublic(api.members(GUILD_A)).get(unlinked), "all defaults off: nothing changes");

        profiles.savePrivacyDefaults(GUILD_A, new MemberProfileRepository.PrivacyDefaults(true, true, false), USER_SHOWN);
        java.util.Map<String, Boolean> withDefaults = pagePublic(api.members(GUILD_A));
        assertFalse(withDefaults.get(unlinked), "a member nobody linked follows the default");
        assertFalse(withDefaults.get(SHOWN), "a member who never chose follows the default");
        assertFalse(withDefaults.get(HIDDEN), "a member who hid themselves stays hidden");
        assertNull(api.tenant(guildA, "member", exchange("rsn=" + SHOWN)), "and their page answers 404");
        assertTrue(profiles.adventureLogHidden(GUILD_A, SHOWN), "the log default applies too");
        assertTrue(profiles.adventureLogHidden(GUILD_A, unlinked));
        assertFalse(profiles.hiddenDiscordUsers(GUILD_A).contains(USER_SHOWN), "the Discord link default is still off");

        // the member chooses: their own toggles win over the defaults
        profiles.saveProfile(new MemberProfileRepository.Profile(GUILD_A, USER_SHOWN, "", null, null, false, false, true, false, false, true));
        java.util.Map<String, Boolean> chosen = pagePublic(api.members(GUILD_A));
        assertTrue(chosen.get(SHOWN), "a member who chose to show stays shown");
        assertFalse(chosen.get(unlinked));
        assertFalse(profiles.adventureLogHidden(GUILD_A, SHOWN));

        // a later save can't un-choose
        profiles.saveProfile(new MemberProfileRepository.Profile(GUILD_A, USER_SHOWN, "bio", null, null, false, false, true, false, false));
        assertTrue(profiles.getProfile(GUILD_A, USER_SHOWN).privacyChosen());

        // the Discord link default hides everyone with a link who hasn't chosen
        profiles.savePrivacyDefaults(GUILD_A, new MemberProfileRepository.PrivacyDefaults(false, false, true), USER_SHOWN);
        assertEquals(java.util.Set.of(USER_HIDDEN), profiles.hiddenDiscordUsers(GUILD_A));
        assertEquals(new MemberProfileRepository.PrivacyDefaults(false, false, true), profiles.privacyDefaults(GUILD_A));
        profiles.deletePrivacyDefaults(GUILD_A);
        assertEquals(MemberProfileRepository.PrivacyDefaults.NONE, profiles.privacyDefaults(GUILD_A));
    }

    @Test
    void aMembersRecapChoiceAndTheRecapsTheyOpenedAreStoredForTheirLinkedName() throws Exception {
        assertFalse(profiles.getProfile(GUILD_A, USER_SHOWN).hideRecap(), "never saved: recaps are not hidden");
        assertEquals(new MemberProfileRepository.RecapOwner(USER_SHOWN, false, false), profiles.recapOwner(GUILD_A, SHOWN.toUpperCase(), "month:2026-09:final"),
                "found by name in any case; no profile row means not hidden");
        assertNull(profiles.recapOwner(GUILD_A, "T364Nobody", "all"), "nobody linked that name");
        assertNull(profiles.recapOwner(GUILD_B, SHOWN, "all"), "linked on another server only");

        profiles.markRecapSeen(GUILD_A, SHOWN, "month:2026-09:final", USER_SHOWN);
        profiles.markRecapSeen(GUILD_A, SHOWN.toLowerCase(), "month:2026-09:final", USER_SHOWN);
        assertTrue(profiles.recapOwner(GUILD_A, SHOWN, "month:2026-09:final").seen());
        assertEquals(1, count("SELECT COUNT(*) FROM younglings.member_recap_seen WHERE guild_id = ? AND rsn = ?", GUILD_A, SHOWN.toLowerCase()),
                "one mark per name and period, kept lower-cased");
        assertFalse(profiles.recapOwner(GUILD_A, SHOWN, "month:2026-09:running").seen(), "each period has its own mark");
        assertFalse(profiles.recapOwner(GUILD_A, SHOWN, null).seen(), "a period with no key is never seen");
        assertFalse(profiles.recapOwner(GUILD_B, OTHER, "month:2026-09:final").seen(), "marks are per server");

        profiles.markRecapSeen(GUILD_A, HIDDEN, "all", USER_SHOWN);
        assertFalse(profiles.recapOwner(GUILD_A, HIDDEN, "all").seen(), "a mark someone else left on the name isn't its member's");
        profiles.markRecapSeen(GUILD_A, HIDDEN, "all", USER_HIDDEN);
        assertTrue(profiles.recapOwner(GUILD_A, HIDDEN, "all").seen(), "the member's own opening replaces it");

        MemberProfileRepository.Profile p = profiles.getProfile(GUILD_A, USER_SHOWN);
        profiles.saveProfile(new MemberProfileRepository.Profile(GUILD_A, USER_SHOWN, p.bio(), p.accentColor(), p.pinnedSkill(), p.hideAdventureLog(),
                p.hideFromLeaderboards(), p.dmGoals(), p.dmEvents(), p.hideDiscordLink(), false, true));
        assertTrue(profiles.getProfile(GUILD_A, USER_SHOWN).hideRecap());
        assertFalse(profiles.getProfile(GUILD_A, USER_SHOWN).privacyChosen(), "turning recaps off is not choosing the privacy toggles");
        assertTrue(profiles.getProfileForRsn(GUILD_A, SHOWN).hideRecap());
        assertTrue(profiles.recapOwner(GUILD_A, SHOWN, "all").hideRecap());
        assertFalse(profiles.hiddenRsns(GUILD_A, true).contains(SHOWN.toLowerCase()), "and it hides nothing else");
    }

    private static long count(String query, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private DataObject memberRecap(Guild guild, String rsn, String period, Long viewer) {
        String query = "rsn=" + rsn + "&period=" + period;
        return api.tenant(guild, "member/recap", viewer == null ? exchange(query) : exchange(query, viewer));
    }

    private static boolean isPrivate(DataObject answer) {
        return answer != null && answer.toString().equals(RecapGate.privateAnswer().toString());
    }

    @Test
    void theMemberRecapIsPrivateUntilItsMemberOpenedThatVeryPeriod() throws Exception {
        assertTrue(isPrivate(memberRecap(guildA, SHOWN, "last-month", null)), "a signed-out visitor, before the member opened it: {\"private\": true} and nothing else");
        assertTrue(isPrivate(memberRecap(guildA, SHOWN, "last-month", USER_HIDDEN)), "another member, likewise");

        DataObject mine = memberRecap(guildA, SHOWN, "last-month", USER_SHOWN);
        assertTrue(mine.getBoolean("you"), "the member always sees their own");
        assertTrue(mine.getBoolean("sharedWithClan"), "and is told the clan can see it now");
        assertEquals(SHOWN, mine.getObject("subject").getString("name"));

        DataObject theirs = memberRecap(guildA, SHOWN, "last-month", null);
        assertFalse(isPrivate(theirs), "opened: everyone sees it on the next read, though the answer was cached");
        assertFalse(theirs.hasKey("you") || theirs.hasKey("sharedWithClan"), "the viewer's own fields never reach anyone else");
        assertEquals(SHOWN, theirs.getObject("subject").getString("name"));
        assertFalse(isPrivate(memberRecap(guildA, SHOWN, previousMonthToken(), null)), "the same month by another token is the same recap");
        assertTrue(isPrivate(memberRecap(guildA, SHOWN, "last-week", null)), "every period needs its own first view");
        assertTrue(isPrivate(memberRecap(guildA, SHOWN, "month", null)), "even the running month");
        for (long user : List.of(USER_SHOWN, USER_HIDDEN, USER_OTHER)) assertFalse(text(mine).contains(Long.toString(user)), "no Discord id in the member's answer");
    }

    /** The month before this one as {@code YYYY-MM}: the same window as {@code last-month}. */
    private static String previousMonthToken() {
        return java.time.YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString();
    }

    @Test
    void aMemberWhoTurnedRecapsOffKeepsThemAndRollingAndCustomWindowsFollowTheRule() throws Exception {
        memberRecap(guildA, SHOWN, "last-30d", USER_SHOWN);
        assertFalse(isPrivate(memberRecap(guildA, SHOWN, "last-30d", null)), "a rolling window has a key too: opened, then shared");

        MemberProfileRepository.Profile p = profiles.getProfile(GUILD_A, USER_SHOWN);
        profiles.saveProfile(new MemberProfileRepository.Profile(GUILD_A, USER_SHOWN, p.bio(), p.accentColor(), p.pinnedSkill(), p.hideAdventureLog(),
                p.hideFromLeaderboards(), p.dmGoals(), p.dmEvents(), p.hideDiscordLink(), p.privacyChosen(), true));
        assertTrue(isPrivate(memberRecap(guildA, SHOWN, "last-30d", null)), "turned off: private again at once, cached answer or not");
        DataObject mine = memberRecap(guildA, SHOWN, "last-30d", USER_SHOWN);
        assertTrue(mine.getBoolean("you"));
        assertFalse(mine.getBoolean("sharedWithClan"), "only they can see it");
        profiles.saveProfile(p);

        // a custom range that is not one whole week, month or year has no key: only ever the member's
        String custom = java.time.LocalDate.now(ZoneOffset.UTC).minusDays(12) + "_" + java.time.LocalDate.now(ZoneOffset.UTC).minusDays(3);
        DataObject range = memberRecap(guildA, SHOWN, custom, USER_SHOWN);
        assertTrue(range.getBoolean("you"));
        assertFalse(range.getBoolean("sharedWithClan"));
        assertTrue(isPrivate(memberRecap(guildA, SHOWN, custom, null)));
        assertEquals(1, count("SELECT COUNT(*) FROM younglings.member_recap_seen WHERE guild_id = ?", GUILD_A), "only the rolling window was marked");
    }

    @Test
    void aBotlessSitesMemberRecapIsPrivateForEveryoneAndAnUnlinkedNameNeverOpens() throws Exception {
        HttpExchange asMember = exchange("rsn=" + SHOWN + "&period=last-month", USER_SHOWN);
        assertTrue(isPrivate(api.tenant(GUILD_A, null, "member/recap", asMember)), "no bot, no links: nobody can be told to be the member");
        assertEquals(0, count("SELECT COUNT(*) FROM younglings.member_recap_seen WHERE guild_id = ?", GUILD_A), "and nothing is recorded");

        String unlinked = "T364Unlinked";
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank) VALUES (?, ?, 'Recruit')", GUILD_A, unlinked);
        for (Long viewer : java.util.Arrays.asList(USER_SHOWN, USER_HIDDEN, null)) {
            assertTrue(isPrivate(memberRecap(guildA, unlinked, "last-month", viewer)), "an unlinked name's recap is nobody's to open: " + viewer);
        }
        assertNull(memberRecap(guildA, HIDDEN, "last-month", USER_HIDDEN), "hidden from the rankings is still a 404 first");
    }

    private record Reply(int status, DataObject body) {}

    /** The home site's own {@code /internal/site/<route>} ({@link SiteApi#handle}), as the website calls it. */
    private Reply home(String route, String query, Long viewer) throws Exception {
        HttpExchange exchange = viewer == null ? exchange(query) : exchange(query, viewer);
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/internal/site/" + route + "?" + query));
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(out);
        int[] status = {-1};
        org.mockito.Mockito.doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(org.mockito.ArgumentMatchers.anyInt(), anyLong());
        api.handle(exchange, guildA);
        return new Reply(status[0], DataObject.fromJson(out.toString(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void theHomeSitesMemberRecapFollowsTheSameRulesAndHidesAHiddenMember() throws Exception {
        assertEquals(404, home("recap", "scope=member&rsn=" + HIDDEN + "&period=last-month", USER_HIDDEN).status(), "hidden from the rankings: no recap, as on clan sites");
        Reply anonymous = home("recap", "scope=member&rsn=" + SHOWN + "&period=last-month", null);
        assertEquals(200, anonymous.status());
        assertTrue(isPrivate(anonymous.body()));
        Reply mine = home("recap", "scope=member&rsn=" + SHOWN + "&period=last-month", USER_SHOWN);
        assertTrue(mine.body().getBoolean("you"));
        assertTrue(mine.body().getBoolean("sharedWithClan"));
        Reply after = home("recap", "scope=member&rsn=" + SHOWN + "&period=last-month", null);
        assertEquals(SHOWN, after.body().getObject("subject").getString("name"));
        assertFalse(after.body().hasKey("you"));
        Reply clan = home("recap", "scope=clan&period=last-month", null);
        assertEquals("clan", clan.body().getString("scope"), "the clan's recap is unchanged");
    }

    @Test
    void theHomeSitesOwnAnswersAreUnchanged() {
        // the home site's records still list everyone in "longest serving", and its signups show typed names as before
        assertTrue(text(api.records(guildA)).contains(HIDDEN.toLowerCase()));
        assertTrue(text(api.signups(guildA)).contains(HIDDEN.toLowerCase()));
        assertFalse(text(api.tenant(guildA, "records", exchange(""))).contains(HIDDEN.toLowerCase()));
    }

    @Test
    void aPollOrSignupWriteClearsEveryServersCachedAnswer() throws Exception {
        assertEquals(1, call(guildA, "polls").getArray("polls").length());
        long poll = insertReturningId("INSERT INTO younglings.poll (guild_id, channel_id, title, created_by_user_id) VALUES (?, 1, 'T364 second', ?) RETURNING poll_id", GUILD_A, USER_SHOWN);
        sql("INSERT INTO younglings.poll_option (poll_id, option_number, label) VALUES (?, 1, 'No')", poll);
        assertEquals(1, call(guildA, "polls").getArray("polls").length(), "cached for a few seconds");
        cache.invalidate("polls"); // what the poll writes call (MemberApi, CommunityAdminApi, PollCloser)
        assertEquals(2, call(guildA, "polls").getArray("polls").length(), "the clan site's key (#g<id>) is cleared with the home server's");

        assertEquals(2, call(guildA, "signups").getArray("signups").getObject(0).getArray("entries").length());
        sql("DELETE FROM younglings.signup_entry WHERE discord_user_id = ?", USER_HIDDEN);
        cache.invalidate("signups");
        assertEquals(1, call(guildA, "signups").getArray("signups").getObject(0).getArray("entries").length());
    }

    private static HttpExchange exchange(String query) {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/x" + (query.isEmpty() ? "" : "?" + query)));
        return exchange;
    }

    /** A request from a signed-in viewer: the website sends their Discord id in {@link RecapGate#VIEWER_HEADER}. */
    private static HttpExchange exchange(String query, long viewer) {
        HttpExchange exchange = exchange(query);
        Headers headers = new Headers();
        headers.add(RecapGate.VIEWER_HEADER, Long.toString(viewer));
        when(exchange.getRequestHeaders()).thenReturn(headers);
        return exchange;
    }

    private static void sql(String statement, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            s.executeUpdate();
        }
    }

    private static long insertReturningId(String statement, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The site's connections leave Discord events on, as for a site that never changed them. */
    private static SiteDiscordService discordShowingEvents() {
        SiteDiscordService discord = mock(SiteDiscordService.class);
        when(discord.eventsShown(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        return discord;
    }
}
