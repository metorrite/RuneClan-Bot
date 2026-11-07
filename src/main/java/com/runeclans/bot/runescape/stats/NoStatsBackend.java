package com.runeclans.bot.runescape.stats;

import com.runeclans.bot.internal.SiteStatsRepository;
import com.runeclans.bot.runescape.PlayerActivity;
import com.runeclans.bot.runescape.PlayerLinkRepository.SkillHistoryPoint;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.runeclans.bot.runescape.SkillValue;
import com.runeclans.bot.runescape.SkillXpPoint;
import com.runeclans.bot.runescape.WeeklyDigestRepository.CitadelActivityRow;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The stats a bot with its hub client off can see: none (T-912). The hub is the only store of polled stats and adventure-log entries; the
 * bot's old stats tables are no longer read (T-853 drops them). A local run or a test without a hub gets empty answers, as it would have
 * from the empty tables before.
 */
final class NoStatsBackend implements StatsBackend {
    static final NoStatsBackend INSTANCE = new NoStatsBackend();

    private NoStatsBackend() {}

    @Override public StatsSnapshotRow latest(String rsn) { return null; }
    @Override public Map<String, StatsSnapshotRow> latestMany(Collection<String> rsns) { return Map.of(); }
    @Override public List<SkillValue> skillsForSnapshot(long snapshotId) { return List.of(); }
    @Override public List<StatsSnapshotRow> history(String rsn, int limit) { return List.of(); }
    @Override public List<StatsSnapshotRow> snapshotsSince(String rsn, OffsetDateTime since) { return List.of(); }
    @Override public List<SkillXpPoint> skillXpHistory(String rsn, int skillId, OffsetDateTime since) { return List.of(); }
    @Override public List<SkillHistoryPoint> allSkillsXpHistorySince(String rsn, OffsetDateTime since) { return List.of(); }
    @Override public List<PlayerActivity> recentActivities(String rsn, int limit) { return List.of(); }
    @Override public List<PlayerActivity> activitiesSince(String rsn, OffsetDateTime since) { return List.of(); }
    @Override public List<String> recentActivityTexts(Collection<String> rsns, int limit) { return List.of(); }
    @Override public Map<String, StatsSnapshotRow> membersLatest(long guildId) { return Map.of(); }
    @Override public List<SiteStatsRepository.Gain> xpGainsBetween(long guildId, OffsetDateTime from, OffsetDateTime to) { return List.of(); }
    @Override public OffsetDateTime firstSnapshotAt(long guildId) { return null; }
    @Override public List<SiteStatsRepository.Capper> cappersBetween(long guildId, OffsetDateTime from, OffsetDateTime to, int limit) { return List.of(); }
    @Override public List<SiteStatsRepository.SkillGain> skillGainsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) { return List.of(); }
    @Override public List<SiteStatsRepository.DayXp> dailyXpBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) { return List.of(); }
    @Override public List<SiteStatsRepository.ActivityRow> activitiesBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) { return List.of(); }
    @Override public Integer[] totalLevelsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to) { return new Integer[]{null, null}; }
    @Override public List<SiteStatsRepository.ActivityRow> recentActivities(long guildId, int limit) { return List.of(); }
    @Override public List<SiteStatsRepository.ActivityRow> activitiesStartingWith(long guildId, String prefix) { return List.of(); }
    @Override public List<SiteStatsRepository.BigDay> biggestDays(long guildId, int limit) { return List.of(); }
    @Override public List<SiteStatsRepository.ClubMember> twoHundredMillionClub(long guildId, int limit) { return List.of(); }
    @Override public List<SiteStatsRepository.CapWeek> citadelGrid(long guildId, int weeks) { return List.of(); }
    @Override public List<SiteStatsRepository.CapWeek> allCapWeeks(long guildId) { return List.of(); }
    @Override public List<SiteStatsRepository.WeekCount> citadelByWeek(long guildId, int weeks) { return List.of(); }
    @Override public List<SiteStatsRepository.Capper> topCappers(long guildId, int limit) { return List.of(); }
    @Override public List<SiteStatsRepository.SkillLeader> skillLeaders(long guildId, int perSkill) { return List.of(); }
    @Override public List<SiteStatsRepository.DayPoint> dailyHistory(long guildId, String rsn, int days) { return List.of(); }
    @Override public SiteStatsRepository.CitadelTotals citadelFor(long guildId, String rsn) { return new SiteStatsRepository.CitadelTotals(0, 0, List.of()); }
    @Override public List<CitadelActivityRow> citadelActivityInWindow(long guildId, OffsetDateTime from, OffsetDateTime to) { return List.of(); }
    @Override public Map<String, OffsetDateTime> lastActivity(long guildId) { return Map.of(); }
}
