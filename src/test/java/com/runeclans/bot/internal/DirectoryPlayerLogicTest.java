package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfile.Visibility;
import com.runeclans.bot.internal.SiteStatsRepository.MemberRow;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.runeclans.bot.runescape.SkillValue;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which clans a player's public profile shows, and its stats ({@link DirectoryPlayerLogic}), over plain lists. Fake ids built at run time. */
class DirectoryPlayerLogicTest {
    private static final long GUILD_A = Long.parseLong("4".repeat(17) + "1");
    private static final long GUILD_B = Long.parseLong("4".repeat(17) + "2");
    private static final long GUILD_C = Long.parseLong("4".repeat(17) + "3");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 26, 12, 0, 0, 0, ZoneOffset.UTC);

    /** An in-memory source: rosters by server, profiles, clan names, hidden members and snapshots. */
    private static final class Fake implements DirectoryPlayerLogic.Source {
        final Map<Long, List<MemberRow>> rosters = new HashMap<>();
        final Map<Long, ClanProfile> profiles = new HashMap<>();
        final Map<Long, String> clanNames = new HashMap<>();
        final Set<String> hidden = new HashSet<>();
        final Set<String> hiddenAnywhere = new HashSet<>();
        final Set<String> linked = new HashSet<>();
        final List<StatsSnapshotRow> snapshots = new ArrayList<>();
        final Map<Long, List<SkillValue>> skills = new HashMap<>();

        @Override
        public List<Long> guildsWithActiveMember(String rsn) {
            return rosters.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(m -> m.rsn().equalsIgnoreCase(rsn))).map(Map.Entry::getKey).sorted().toList();
        }

        @Override
        public Optional<ClanProfile> profile(long guildId) {
            return Optional.ofNullable(profiles.get(guildId));
        }

        @Override
        public String clanName(long guildId) {
            return clanNames.get(guildId);
        }

        @Override
        public MemberRow visibleMember(long guildId, String rsn) {
            if (hidden.contains(guildId + ":" + rsn.toLowerCase())) return null;
            return rosters.getOrDefault(guildId, List.of()).stream().filter(m -> m.rsn().equalsIgnoreCase(rsn.trim())).findFirst().orElse(null);
        }

        @Override
        public boolean hiddenAnywhere(String rsn) {
            return hiddenAnywhere.contains(rsn.toLowerCase());
        }

        @Override
        public boolean linked(String rsn) {
            return linked.contains(rsn.toLowerCase());
        }

        @Override
        public int maxRankOrder(long guildId) {
            return 11;
        }

        @Override
        public List<StatsSnapshotRow> snapshots(String rsn) {
            return snapshots;
        }

        @Override
        public List<SkillValue> skills(long snapshotId) {
            return skills.getOrDefault(snapshotId, List.of());
        }

        /** A listed clan (public, verified for its current clan name) with this player on its roster. */
        Fake clan(long guildId, String slug, String name, Visibility visibility, String rsn, OffsetDateTime lastPolled) {
            profiles.put(guildId, DirectoryPlayerLogicTest.profile(guildId, slug, name, visibility, null));
            clanNames.put(guildId, name);
            rosters.computeIfAbsent(guildId, g -> new ArrayList<>()).add(row(rsn, lastPolled));
            return this;
        }
    }

    private static ClanProfile profile(long guildId, String slug, String name, Visibility visibility, Instant suspended) {
        return new ClanProfile(guildId, visibility, slug, name, "", "", null, null, null, EnumSet.noneOf(ClanProfile.Focus.class), ClanProfile.Recruiting.CLOSED, "", null,
                false, name, Instant.EPOCH, null, suspended, suspended == null ? null : "spam", null);
    }

    private static MemberRow row(String rsn, OffsetDateTime lastPolled) {
        return new MemberRow(rsn, "Sergeant", 4, 200_000_000, 0, null, NOW.minusDays(100), 0, false, false, 2000, 120, lastPolled);
    }

    private static StatsSnapshotRow snapshot(long id, OffsetDateTime at, long totalXp) {
        return new StatsSnapshotRow(id, at, 2000 + (int) id, totalXp, 120, 50 + (int) id, 0, 0);
    }

    @Test
    void namesAreTrimmedAndCheckedAgainstTheRuneScapeRules() {
        assertEquals("Some Name", DirectoryPlayerLogic.cleanRsn("  Some Name "));
        assertEquals("a_b-c", DirectoryPlayerLogic.cleanRsn("a_b-c"));
        assertEquals("x", DirectoryPlayerLogic.cleanRsn("x"));
        assertEquals("123456789012", DirectoryPlayerLogic.cleanRsn("123456789012"));
        assertNull(DirectoryPlayerLogic.cleanRsn("1234567890123"), "13 characters is too long");
        assertNull(DirectoryPlayerLogic.cleanRsn("   "));
        assertNull(DirectoryPlayerLogic.cleanRsn(null));
        assertNull(DirectoryPlayerLogic.cleanRsn("bad;name"));
        assertNull(DirectoryPlayerLogic.cleanRsn("dröm"));
        assertNull(DirectoryPlayerLogic.cleanRsn("a%20b"));
    }

    @Test
    void anUnknownNameHasNoAnswer() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Known", NOW);
        assertNull(DirectoryPlayerLogic.answer("Nobody", fake, NOW));
    }

    @Test
    void aMemberHiddenFromTheRankingsLooksLikeANameNobodyKnows() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Shy", NOW);
        fake.hidden.add(GUILD_A + ":shy");
        assertNull(DirectoryPlayerLogic.answer("Shy", fake, NOW));
    }

    @Test
    void onlyClansThePublicDirectoryListsAreIncluded() {
        Fake fake = new Fake()
                .clan(GUILD_A, "alpha", "Alpha", Visibility.PRIVATE, "Player", NOW)
                .clan(GUILD_B, "beta", "Beta", Visibility.UNLISTED, "Player", NOW)
                .clan(GUILD_C, "gamma", "Gamma", Visibility.PUBLIC, "Player", NOW);
        fake.clanNames.put(GUILD_C, "Renamed since"); // verified for its old name: no longer listed
        assertNull(DirectoryPlayerLogic.answer("Player", fake, NOW), "private, unlisted and no longer verified clans are all left out");

        fake.clanNames.put(GUILD_C, "Gamma");
        DataObject answer = DirectoryPlayerLogic.answer("Player", fake, NOW);
        assertEquals(1, answer.getArray("clans").length());
        assertEquals("gamma", answer.getArray("clans").getObject(0).getString("slug"));
    }

    @Test
    void aSuspendedListingOrOneWithoutAnAddressIsLeftOut() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW).clan(GUILD_B, "beta", "Beta", Visibility.PUBLIC, "Player", NOW);
        fake.profiles.put(GUILD_A, profile(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, Instant.EPOCH));
        fake.profiles.put(GUILD_B, profile(GUILD_B, " ", "Beta", Visibility.PUBLIC, null));
        assertNull(DirectoryPlayerLogic.answer("Player", fake, NOW));
    }

    @Test
    void everyIncludedClanIsListedTheMostRecentlyPolledFirst() {
        Fake fake = new Fake()
                .clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW.minusDays(3))
                .clan(GUILD_B, "beta", "Beta", Visibility.PUBLIC, "player", NOW.minusHours(1))
                .clan(GUILD_C, "gamma", "Gamma", Visibility.PUBLIC, "PLAYER", null);
        fake.hidden.add(GUILD_C + ":player");
        fake.clan(GUILD_C, "gamma", "Gamma", Visibility.PUBLIC, "Other", NOW);

        DataObject answer = DirectoryPlayerLogic.answer("PlAyEr", fake, NOW);
        DataArray clans = answer.getArray("clans");
        assertEquals(2, clans.length(), "the clan where they hid themselves is left out, the others stay");
        assertEquals("beta", clans.getObject(0).getString("slug"));
        assertEquals("alpha", clans.getObject(1).getString("slug"));
        assertEquals("player", answer.getString("rsn"), "the name as the most recently polled clan spells it");
        DataObject beta = clans.getObject(0);
        assertEquals("Beta", beta.getString("name"));
        assertEquals("Sergeant", beta.getString("rank"));
        assertEquals(4, beta.getInt("rankOrder"));
        assertEquals(11, beta.getInt("maxRankOrder"));
        assertEquals(NOW.minusDays(100).toString(), beta.getString("firstSeen"));
        assertEquals(NOW.minusHours(1).toString(), beta.getString("lastPolled"));
        assertFalse(beta.getBoolean("joinedExact"));
    }

    @Test
    void statsComeFromTheNewestSnapshotWithGainsOverTheDayWeekAndMonth() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW);
        // out of order on purpose: the newest is picked by its time, not its place in the list
        fake.snapshots.add(snapshot(2, NOW.minusDays(3), 1_100));
        fake.snapshots.add(snapshot(4, NOW.minusMinutes(5), 1_500));
        fake.snapshots.add(snapshot(1, NOW.minusDays(40), 1_000));
        fake.snapshots.add(snapshot(3, NOW.minusHours(30), 1_300));
        fake.skills.put(4L, List.of(new SkillValue(0, 99, 13_034_431, 1234), new SkillValue(1, 80, 2_000_000, 99_999)));
        fake.skills.put(1L, List.of(new SkillValue(0, 1, 0, -1)));

        DataObject stats = DirectoryPlayerLogic.answer("Player", fake, NOW).getObject("stats");
        assertEquals(NOW.minusMinutes(5).toInstant().toString(), stats.getString("snapshotAt"));
        assertEquals(2004, stats.getInt("totalLevel"));
        assertEquals(1_500, stats.getLong("totalXp"));
        assertEquals(120, stats.getInt("combatLevel"));
        assertEquals(54, stats.getInt("questsComplete"));
        DataArray skills = stats.getArray("skills");
        assertEquals(2, skills.length());
        DataObject attack = skills.getObject(0);
        assertEquals(0, attack.getInt("id"));
        assertEquals("Attack", attack.getString("name"));
        assertEquals(99, attack.getInt("level"));
        assertEquals(13_034_431, attack.getLong("xp"));
        assertEquals(1234, attack.getInt("rank"));
        DataObject gains = stats.getObject("gains");
        assertEquals(200, gains.getLong("day"), "since the snapshot 30 hours ago");
        assertEquals(500, gains.getLong("week"), "since the one 40 days ago (the newest at or before a week ago)");
        assertEquals(500, gains.getLong("month"));
    }

    @Test
    void aPlayerInNoClanStillGetsTheirStatsOnceTheBotPolledThem() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Someone Else", NOW);
        assertNull(DirectoryPlayerLogic.answer("Loner", fake, NOW), "never polled: nothing to show");
        fake.snapshots.add(snapshot(1, NOW.minusMinutes(3), 9_000));
        DataObject answer = DirectoryPlayerLogic.answer("Loner", fake, NOW);
        assertEquals("Loner", answer.getString("rsn"));
        assertEquals(0, answer.getArray("clans").length(), "every linked player is tracked, clan or not (Drake 2026-11-02)");
        assertEquals(9_000, answer.getObject("stats").getLong("totalXp"));
    }

    @Test
    void aHiddenMemberStaysHiddenEvenWithStats() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Shy", NOW);
        fake.hidden.add(GUILD_A + ":shy");
        fake.snapshots.add(snapshot(1, NOW.minusMinutes(3), 9_000));
        assertNull(DirectoryPlayerLogic.answer("Shy", fake, NOW));
    }

    @Test
    void aMemberOfAClanTheDirectoryDoesNotListStaysUnknown() {
        for (Visibility v : List.of(Visibility.UNLISTED, Visibility.PRIVATE)) {
            Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", v, "Player", NOW);
            fake.snapshots.add(snapshot(1, NOW.minusMinutes(3), 9_000));
            assertNull(DirectoryPlayerLogic.answer("Player", fake, NOW), v + ": the clan keeps its members to itself");
        }
        Fake suspended = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW);
        suspended.profiles.put(GUILD_A, profile(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, Instant.EPOCH));
        suspended.snapshots.add(snapshot(1, NOW.minusMinutes(3), 9_000));
        assertNull(DirectoryPlayerLogic.answer("Player", suspended, NOW), "suspended");
        Fake unverified = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW);
        unverified.clanNames.put(GUILD_A, "Renamed since");
        unverified.snapshots.add(snapshot(1, NOW.minusMinutes(3), 9_000));
        assertNull(DirectoryPlayerLogic.answer("Player", unverified, NOW), "no longer verified");
    }

    @Test
    void aLinkedPlayerOnTheRosterOfAnUnlistedClanStillGetsTheirStatsWithNoClans() {
        // ABabyMe on live 2026-11-02: linked, polled, on a roster the directory doesn't list, and the profile said "No hiscores yet"
        for (Visibility v : List.of(Visibility.UNLISTED, Visibility.PRIVATE)) {
            Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", v, "Player", NOW);
            fake.snapshots.add(snapshot(1, NOW.minusMinutes(3), 9_000));
            fake.linked.add("player");
            DataObject answer = DirectoryPlayerLogic.answer("Player", fake, NOW);
            assertEquals(0, answer.getArray("clans").length(), v + ": the clan stays unnamed");
            assertEquals(9_000, answer.getObject("stats").getLong("totalXp"));
        }
    }

    @Test
    void aLinkedPlayerWhoHidSomewhereStaysHidden() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.UNLISTED, "Player", NOW);
        fake.snapshots.add(snapshot(1, NOW.minusMinutes(3), 9_000));
        fake.linked.add("player");
        fake.hiddenAnywhere.add("player");
        assertNull(DirectoryPlayerLogic.answer("Player", fake, NOW));
    }

    @Test
    void aPlayerWhoHidSomewhereStaysHiddenAfterLeavingTheClan() {
        Fake fake = new Fake();
        fake.snapshots.add(snapshot(1, NOW.minusMinutes(3), 9_000));
        fake.hiddenAnywhere.add("leaver");
        assertNull(DirectoryPlayerLogic.answer("Leaver", fake, NOW));
    }

    @Test
    void aPlayerWithNoSnapshotYetHasNoStats() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW);
        DataObject answer = DirectoryPlayerLogic.answer("Player", fake, NOW);
        assertTrue(answer.isNull("stats"));
        assertEquals(1, answer.getArray("clans").length());
    }

    @Test
    void historyIsOnePointPerDayForTheLastNinetyDaysOldestFirst() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW);
        fake.snapshots.add(snapshot(1, NOW.minusDays(120), 500));
        fake.snapshots.add(snapshot(2, NOW.minusDays(2).withHour(3), 1_000));
        fake.snapshots.add(snapshot(3, NOW.minusDays(2).withHour(20), 1_200));
        fake.snapshots.add(snapshot(4, NOW.minusMinutes(5), 1_500));

        DataArray history = DirectoryPlayerLogic.answer("Player", fake, NOW).getObject("stats").getArray("history");
        assertEquals(2, history.length(), "the 120-day-old snapshot is outside the window, two the same day count once");
        assertEquals(NOW.minusDays(2).toLocalDate().toString(), history.getObject(0).getString("date"));
        assertEquals(1_200, history.getObject(0).getLong("totalXp"), "that day's newest snapshot");
        assertEquals(NOW.toLocalDate().toString(), history.getObject(1).getString("date"));
        assertEquals(1_500, history.getObject(1).getLong("totalXp"));
        assertEquals(2004, history.getObject(1).getInt("totalLevel"));
    }

    @Test
    void skillGainsCompareTheNewestSkillsWithTheBaselineBiggestFirst() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW);
        fake.snapshots.add(snapshot(1, NOW.minusDays(40), 1_000));
        fake.snapshots.add(snapshot(2, NOW.minusDays(10), 1_100));
        fake.snapshots.add(snapshot(3, NOW.minusMinutes(5), 1_500));
        fake.skills.put(1L, List.of(new SkillValue(0, 50, 100_000, 1), new SkillValue(1, 50, 100_000, 1), new SkillValue(2, 50, 100_000, 1)));
        fake.skills.put(2L, List.of(new SkillValue(0, 50, 150_000, 1), new SkillValue(1, 50, 100_000, 1), new SkillValue(2, 50, 100_000, 1)));
        fake.skills.put(3L, List.of(new SkillValue(0, 60, 160_000, 1), new SkillValue(1, 60, 300_000, 1), new SkillValue(2, 50, 100_000, 1)));

        DataObject gains = DirectoryPlayerLogic.answer("Player", fake, NOW).getObject("stats").getObject("skillGains");
        DataArray week = gains.getArray("week");
        assertEquals(2, week.length(), "Strength gained nothing and is left out");
        assertEquals(1, week.getObject(0).getInt("id"));
        assertEquals("Defence", week.getObject(0).getString("name"));
        assertEquals(200_000, week.getObject(0).getLong("xp"));
        assertEquals(10_000, week.getObject(1).getLong("xp"), "Attack since the snapshot 10 days ago");
        DataArray month = gains.getArray("month");
        assertEquals(200_000, month.getObject(0).getLong("xp"));
        assertEquals(60_000, month.getObject(1).getLong("xp"), "Attack since the one 40 days ago");
    }

    @Test
    void oneSnapshotGainsNothingPerSkill() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW);
        fake.snapshots.add(snapshot(1, NOW.minusMinutes(5), 1_000));
        fake.skills.put(1L, List.of(new SkillValue(0, 50, 100_000, 1)));
        DataObject stats = DirectoryPlayerLogic.answer("Player", fake, NOW).getObject("stats");
        assertEquals(0, stats.getObject("skillGains").getArray("week").length());
        assertEquals(1, stats.getArray("history").length());
    }

    /** A top-players source: shared clans, their gains, and the members hidden from the rankings. */
    private static final class FakeTop implements DirectoryPlayerLogic.TopSource {
        final List<ClanProfile> clans = new ArrayList<>();
        final Map<Long, String> clanNames = new HashMap<>();
        final Map<Long, List<SiteStatsRepository.Gain>> gains = new HashMap<>();
        final Set<String> hidden = new HashSet<>();

        FakeTop clan(long guildId, String slug, String name, boolean optIn, SiteStatsRepository.Gain... members) {
            clans.add(new ClanProfile(guildId, Visibility.PUBLIC, slug, name, "", "", null, null, null, EnumSet.noneOf(ClanProfile.Focus.class),
                    ClanProfile.Recruiting.CLOSED, "", null, optIn, name, Instant.EPOCH, null, null, null, null));
            clanNames.put(guildId, name);
            gains.put(guildId, List.of(members));
            return this;
        }

        @Override
        public List<ClanProfile> sharedClans() {
            return clans;
        }

        @Override
        public String clanName(long guildId) {
            return clanNames.get(guildId);
        }

        @Override
        public List<SiteStatsRepository.Gain> gains(long guildId, OffsetDateTime since) {
            return gains.getOrDefault(guildId, List.of());
        }

        @Override
        public MemberRow visibleMember(long guildId, String rsn) {
            return hidden.contains(rsn.toLowerCase()) ? null : row(rsn, NOW);
        }
    }

    @Test
    void topPlayersComeFromOptedInListedClansOnlyVisibleMembersOncePerName() {
        FakeTop top = new FakeTop()
                .clan(GUILD_A, "alpha", "Alpha", true, new SiteStatsRepository.Gain("Big", 900), new SiteStatsRepository.Gain("Shy", 800),
                        new SiteStatsRepository.Gain("Both", 300), new SiteStatsRepository.Gain("Idle", 0))
                .clan(GUILD_B, "beta", "Beta", true, new SiteStatsRepository.Gain("Both", 500), new SiteStatsRepository.Gain("Small", 100))
                .clan(GUILD_C, "gamma", "Gamma", false, new SiteStatsRepository.Gain("OptedOut", 5_000));
        top.hidden.add("shy");

        DataArray entries = DirectoryPlayerLogic.topPlayers(top, NOW.minusDays(7), 10).getArray("entries");
        assertEquals(3, entries.length());
        assertEquals("Big", entries.getObject(0).getString("rsn"));
        assertEquals(1, entries.getObject(0).getInt("rank"));
        assertEquals("alpha", entries.getObject(0).getObject("clan").getString("slug"));
        assertEquals("Both", entries.getObject(1).getString("rsn"));
        assertEquals(500, entries.getObject(1).getLong("xpGained"), "the bigger of the two clans' gains");
        assertEquals("beta", entries.getObject(1).getObject("clan").getString("slug"));
        assertEquals(2, entries.getObject(1).getInt("rank"));
        assertEquals("Small", entries.getObject(2).getString("rsn"));
        assertEquals(1, DirectoryPlayerLogic.topPlayers(top, NOW.minusDays(7), 1).getArray("entries").length());
    }
}
