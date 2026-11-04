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
 * Every read of player stats the bot makes (snapshots, skills, adventure-log lines), in the shapes the bot's features already use, so a
 * feature doesn't care where the numbers live (T-851, RuneClan-API docs/PHASE-4-STATS-POLLING.md section D). Two sources answer it: the bot's
 * own tables, through the SQL that stays in {@code PlayerLinkRepository}, {@code SiteStatsRepository} and the other repositories (what T-853
 * drops), and the hub's stats routes ({@link HubStatsBackend}). {@link StatsBackends} says which one is current: the hub's once its
 * {@code hub-polling} switch is on, because from then on the hub polls RuneMetrics and the bot's tables stop filling.
 * <p>
 * Guild-scoped reads take the server's clan roster from the bot's own {@code clan_member} table (the roster handover is T-852) and answer for
 * its active members, exactly as the SQL did. Every list keeps the order the SQL gave (noted on each method).
 */
public interface StatsBackend {

    // ---------------------------------------------------------------- one player

    /** The newest snapshot, or null when the player was never polled. */
    StatsSnapshotRow latest(String rsn);

    /** The newest snapshot of each of {@code rsns}, keyed by the lower-cased name; names without one are absent. */
    Map<String, StatsSnapshotRow> latestMany(Collection<String> rsns);

    /** The skills of snapshot {@code snapshotId} (as {@link #latest} or {@link #history} numbered it); empty when unknown. */
    List<SkillValue> skillsForSnapshot(long snapshotId);

    /** The newest {@code limit} snapshots, newest first. */
    List<StatsSnapshotRow> history(String rsn, int limit);

    /** Every snapshot since {@code since}, oldest first. */
    List<StatsSnapshotRow> snapshotsSince(String rsn, OffsetDateTime since);

    /** One skill's XP at each poll since {@code since}, oldest first. */
    List<SkillXpPoint> skillXpHistory(String rsn, int skillId, OffsetDateTime since);

    /** Every skill's XP at every poll since {@code since}, ordered by time then skill. */
    List<SkillHistoryPoint> allSkillsXpHistorySince(String rsn, OffsetDateTime since);

    /** The newest {@code limit} adventure-log lines, newest first. */
    List<PlayerActivity> recentActivities(String rsn, int limit);

    /** Adventure-log lines recorded since {@code since}, oldest first. */
    List<PlayerActivity> activitiesSince(String rsn, OffsetDateTime since);

    /** The newest {@code limit} adventure-log texts across {@code rsns} (lower-cased names), newest first. */
    List<String> recentActivityTexts(Collection<String> rsns, int limit);

    // ---------------------------------------------------------------- a server's clan (its active roster)

    /** The newest snapshot of each active member, keyed by the lower-cased name. */
    Map<String, StatsSnapshotRow> membersLatest(long guildId);

    /** Total XP each active member gained inside {@code [from, to]}, in roster order (lower-cased name); members without a snapshot left out. */
    List<SiteStatsRepository.Gain> xpGainsBetween(long guildId, OffsetDateTime from, OffsetDateTime to);

    /** The first moment any snapshot exists for the clan, or null. */
    OffsetDateTime firstSnapshotAt(long guildId);

    /** Members ranked by Citadel weeks capped inside {@code [from, to)}: most weeks, then most caps, then name. */
    List<SiteStatsRepository.Capper> cappersBetween(long guildId, OffsetDateTime from, OffsetDateTime to, int limit);

    /** Skill XP gained inside {@code [from, to]} by one member, or with {@code rsn == null} by every active member together; biggest first, gains only. */
    List<SiteStatsRepository.SkillGain> skillGainsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to);

    /** XP gained on each UTC day inside the window (one member, or the clan summed), oldest first. */
    List<SiteStatsRepository.DayXp> dailyXpBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to);

    /** Adventure-log lines first recorded inside {@code [from, to)}, for one member or everyone, oldest first. */
    List<SiteStatsRepository.ActivityRow> activitiesBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to);

    /** A member's total level at the start and end of the window ({@code null} where they have no snapshot). */
    Integer[] totalLevelsBetween(long guildId, String rsn, OffsetDateTime from, OffsetDateTime to);

    /** The newest adventure-log lines across active members, newest first. */
    List<SiteStatsRepository.ActivityRow> recentActivities(long guildId, int limit);

    /** Every active member's lines whose text starts with {@code prefix}, newest first. */
    List<SiteStatsRepository.ActivityRow> activitiesStartingWith(long guildId, String prefix);

    /** The biggest single-day XP gains anyone had, biggest first. */
    List<SiteStatsRepository.BigDay> biggestDays(long guildId, int limit);

    /** Members with at least one 200M skill, most skills first then name. */
    List<SiteStatsRepository.ClubMember> twoHundredMillionClub(long guildId, int limit);

    /** Which Citadel weeks (Wednesday starts) each active member visited or capped in the last {@code weeks} weeks. */
    List<SiteStatsRepository.CapWeek> citadelGrid(long guildId, int weeks);

    /** Every Citadel week in which a member capped. */
    List<SiteStatsRepository.CapWeek> allCapWeeks(long guildId);

    /** Capped and visited head-counts per Citadel week for the last {@code weeks} weeks, oldest first. */
    List<SiteStatsRepository.WeekCount> citadelByWeek(long guildId, int weeks);

    /** The members who capped the most Citadel weeks, ever. */
    List<SiteStatsRepository.Capper> topCappers(long guildId, int limit);

    /** The top {@code perSkill} members by XP in every skill from each member's newest snapshot, by skill then rank. */
    List<SiteStatsRepository.SkillLeader> skillLeaders(long guildId, int perSkill);

    /** One point per UTC day (that day's last snapshot) for the last {@code days} days, oldest first. */
    List<SiteStatsRepository.DayPoint> dailyHistory(long guildId, String rsn, int days);

    /** One member's Citadel record. */
    SiteStatsRepository.CitadelTotals citadelFor(long guildId, String rsn);

    /** Every Citadel visit or cap that happened inside {@code [from, to)} by anyone ever on the roster (the weekly digest's input). */
    List<CitadelActivityRow> citadelActivityInWindow(long guildId, OffsetDateTime from, OffsetDateTime to);

    /** Each active member's newest adventure-log entry time, keyed by the lower-cased name. */
    Map<String, OffsetDateTime> lastActivity(long guildId);
}
