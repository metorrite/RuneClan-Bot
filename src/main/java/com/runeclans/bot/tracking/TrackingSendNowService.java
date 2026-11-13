package com.runeclans.bot.tracking;

import com.runeclans.bot.runescape.ClanPointsRepository;
import com.runeclans.bot.runescape.ClanSyncService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the Tracking panel's "Send Now" buttons do: refresh a section's (or one group's) data right now
 * instead of waiting for its schedule, and post whatever that produces to the group's own channels.
 * <p>
 * Two levels, deliberately different in cost. A <b>section</b> ({@link TrackingGroup#source()}) Send Now
 * is the full refresh — it polls every clan member's RuneMetrics profile (which posts each new
 * activity as its own individual entry, exactly like the scheduled polling does), refreshes the roster,
 * or recomputes points, depending on the section, and then sends that section's report where it has
 * one. A single <b>group</b>'s Send Now (only the three computed reports have one) is the cheap
 * version: no polling, just recompute from what's already stored and send. The Discord Admin Log has
 * neither — its entries are real-time events with nothing to refresh.
 * <p>
 * Every result says what happened and, when something was built but couldn't be delivered, why — see
 * {@link TrackingEventRouter#undeliverableReason}.
 */
@BService
public class TrackingSendNowService {
    private static final String RUNEMETRICS = "Adventurer's Log";
    private static final String CITADEL = "Clan Citadel";
    private static final String ROSTER = "Clan Roster";
    private static final String POINTS = "Points & Promotions";

    /** {@code problem} is true when any line is a warning — the panel colors the reply by it. */
    public record Result(List<String> lines, boolean problem) {
        static Result of(List<String> lines) {
            return new Result(lines, lines.stream().anyMatch(line -> line.startsWith("⚠️")));
        }
    }

    private final ClanSyncService clanSyncService;
    private final WeeklyDigestService weeklyDigestService;
    private final ClanPointsService clanPointsService;
    private final ClanPointsRepository clanPointsRepository;
    private final TrackingEventRouter router;
    private final TrackingService trackingService;
    private final CitadelWheelPoster wheelPoster;

    public TrackingSendNowService(ClanSyncService clanSyncService, WeeklyDigestService weeklyDigestService,
                                   ClanPointsService clanPointsService, ClanPointsRepository clanPointsRepository,
                                   TrackingEventRouter router, TrackingService trackingService, CitadelWheelPoster wheelPoster) {
        this.clanSyncService = clanSyncService;
        this.weeklyDigestService = weeklyDigestService;
        this.clanPointsService = clanPointsService;
        this.clanPointsRepository = clanPointsRepository;
        this.router = router;
        this.trackingService = trackingService;
        this.wheelPoster = wheelPoster;
    }

    /** Every section except the Discord Admin Log, whose entries are real-time events with nothing to refresh. */
    public boolean supportsSection(String source) {
        return source.equals(RUNEMETRICS) || source.equals(CITADEL) || source.equals(ROSTER) || source.equals(POINTS);
    }

    /** Only the three computed reports have a per-group Send Now. */
    public boolean supportsGroup(TrackingGroup group) {
        return group == TrackingGroup.CLAN_REPORT || group == TrackingGroup.WEEKLY_JOINS_LEAVES || group == TrackingGroup.WEEKLY_CITADEL_REPORT;
    }

    /** These sections poll the whole roster over the network — the panel allows only one such run per guild at a time, so two clicks can't double the request rate. */
    public boolean pollsRoster(String source) {
        return source.equals(RUNEMETRICS) || source.equals(CITADEL) || source.equals(ROSTER);
    }

    /** One line for the section screen, saying what its Send Now button will actually do. */
    public String describeSection(String source) {
        return switch (source) {
            case RUNEMETRICS -> "-# **Send Now** polls every clan member's Jagex hiscores and Adventurer's Log right now (a couple of minutes) and posts anything new, one entry at a time, to these groups' channels.";
            case CITADEL -> "-# **Send Now** polls every clan member's Adventurer's Log right now (a couple of minutes), posting any Citadel visits/caps, then sends this past week's Citadel report.";
            case ROSTER -> "-# **Send Now** refreshes the clan roster right now (a couple of minutes), posting any joins/leaves it finds, then sends this past week's joins/leaves report.";
            case POINTS -> "-# **Send Now** recomputes everyone's points and who needs a promotion, then sends the Clan Report.";
            default -> "";
        };
    }

    public Result sendSectionNow(Guild guild, String source) {
        long guildId = guild.getIdLong();
        if (clanSyncService.getClanName(guildId) == null) {
            return Result.of(List.of("⚠️ No clan name is configured for this server yet — nothing to refresh."));
        }

        List<String> lines = new ArrayList<>();
        switch (source) {
            case RUNEMETRICS -> {
                pollRoster(guildId, lines);
                noteUndeliverableGroups(guildId, source, lines);
            }
            case CITADEL -> {
                pollRoster(guildId, lines);
                noteUndeliverableGroups(guildId, source, lines);
                sendCitadelDigest(guild, lines);
            }
            case ROSTER -> {
                var sync = clanSyncService.syncAndPoll(guild);
                if (sync.rosterSize() == 0) {
                    lines.add("⚠️ Couldn't fetch the clan roster from the RuneScape Clan Hiscores — try again in a minute.");
                } else {
                    lines.add("Roster refreshed: **" + sync.rosterSize() + "** members (" + sync.newMembers() + " new, "
                            + sync.departedMembers() + " departed); polled " + sync.polled() + " of " + sync.rosterSize() + ".");
                    if (sync.newMembers() + sync.departedMembers() > 0) {
                        router.undeliverableReason(guildId, TrackingGroup.CLAN_JOINS_LEAVES)
                                .ifPresent(reason -> lines.add("⚠️ Joins/leaves were detected, but " + reason + "."));
                    }
                }
                sendJoinsLeavesDigest(guild, lines);
            }
            case POINTS -> recomputeAndSendClanReport(guild, lines);
            default -> lines.add("⚠️ There's nothing to send for this section.");
        }
        return Result.of(lines);
    }

    /** The cheap per-group version: no polling — recompute from what's already stored and send. */
    public Result sendGroupNow(Guild guild, TrackingGroup group) {
        if (clanSyncService.getClanName(guild.getIdLong()) == null) {
            return Result.of(List.of("⚠️ No clan name is configured for this server yet — nothing to send."));
        }

        List<String> lines = new ArrayList<>();
        switch (group) {
            case CLAN_REPORT -> recomputeAndSendClanReport(guild, lines);
            case WEEKLY_JOINS_LEAVES -> sendJoinsLeavesDigest(guild, lines);
            case WEEKLY_CITADEL_REPORT -> sendCitadelDigest(guild, lines);
            default -> lines.add("⚠️ There's nothing to send for this group.");
        }
        return Result.of(lines);
    }

    private void pollRoster(long guildId, List<String> lines) {
        // Duration.ZERO = no spreading, just the API's own minimum safe gap between players.
        var poll = clanSyncService.pollActiveRosterOnly(guildId, Duration.ZERO);
        int total = poll.polled() + poll.pollFailed();
        if (total == 0) {
            lines.add("⚠️ The clan roster is empty — refresh it from the Clan Roster section first.");
            return;
        }
        lines.add("Polled **" + poll.polled() + "** of **" + total + "** clan members' Jagex profiles; anything new was posted as it was found."
                + (poll.pollFailed() > 0 ? " (" + poll.pollFailed() + " private or unavailable profile(s) were skipped.)" : ""));
    }

    /** Per-event groups post straight from polling — if any of this section's groups would drop that on the floor, say so rather than letting a quiet result look like "nothing happened". */
    private void noteUndeliverableGroups(long guildId, String source, List<String> lines) {
        if (!router.isPostingEnabled()) {
            lines.add("⚠️ Posting is toggled off on this instance (`/dev toggleposting`) — nothing was actually sent.");
            return;
        }
        List<String> reasons = new ArrayList<>();
        for (TrackingGroup group : trackingService.groupsInSource(source)) {
            Optional<String> reason = router.undeliverableReason(guildId, group);
            reason.ifPresent(reasons::add);
        }
        if (reasons.isEmpty()) return;
        if (reasons.size() <= 3) {
            for (String reason : reasons) lines.add("⚠️ " + capitalize(reason) + " — anything for it wasn't posted.");
        } else {
            lines.add("⚠️ " + reasons.size() + " of this section's groups aren't posting (disabled, or no destination channel) — anything for them wasn't posted.");
        }
    }

    private void recomputeAndSendClanReport(Guild guild, List<String> lines) {
        long guildId = guild.getIdLong();
        int listed = clanPointsService.runDailyPointsAndPromotionCheck(guild);
        lines.add("Read points and promotion flags from RuneClans (awarded there after each day's roster check).");
        if (listed == ClanPointsService.HUB_DIDNT_ANSWER) {
            lines.add("⚠️ RuneClans didn't answer, so no Clan Report was sent. Try again in a few minutes.");
            return;
        }

        if (clanPointsRepository.known(guildId) == null) {
            lines.add("⚠️ RuneClans didn't answer for this server's clan points (or the server has no clan site), so the report may be empty.");
        }
        ClanPointsRepository.PointsSettings settings = clanPointsRepository.getSettings(guildId);
        if (clanPointsRepository.known(guildId) != null && settings.dailyMembershipPoints() == 0 && settings.citadelVisitPoints() == 0 && settings.citadelCapPoints() == 0) {
            lines.add("⚠️ All three point values are 0, so no points are being awarded — set them in `/rsadmin` → Configure Points & Ranks.");
        }

        if (listed == 0) {
            lines.add("Nobody is currently flagged for a promotion, so no Clan Report was sent.");
            return;
        }
        lines.add("**" + listed + "** member(s) flagged for a promotion.");
        lines.add(deliveryLine(guildId, TrackingGroup.CLAN_REPORT));
    }

    private void sendJoinsLeavesDigest(Guild guild, List<String> lines) {
        OffsetDateTime[] window = WeeklyDigestService.lastCompletedWindow();
        if (!weeklyDigestService.sendJoinsLeaves(guild, window[0], window[1])) {
            lines.add("No joins or leaves in the last completed week, so no weekly roster report was sent.");
            return;
        }
        lines.add(deliveryLine(guild.getIdLong(), TrackingGroup.WEEKLY_JOINS_LEAVES));
    }

    private void sendCitadelDigest(Guild guild, List<String> lines) {
        OffsetDateTime[] window = WeeklyDigestService.lastCompletedWindow();
        if (!weeklyDigestService.sendCitadelReport(guild, window[0], window[1])) {
            lines.add("No Citadel visits or caps in the last completed week, so no weekly Citadel report was sent.");
            return;
        }
        lines.add(deliveryLine(guild.getIdLong(), TrackingGroup.WEEKLY_CITADEL_REPORT));
        // when the server has auto-posting on, the wheel's winner is announced with the list, just as in the weekly run
        wheelPoster.autoPost(guild, window);
    }

    /** For a report that was built: either where it went, or why it went nowhere. */
    private String deliveryLine(long guildId, TrackingGroup group) {
        return router.undeliverableReason(guildId, group)
                .map(reason -> "⚠️ " + capitalize(reason) + " — the **" + group.displayName() + "** was built but not sent.")
                .orElseGet(() -> "Sent the **" + group.displayName() + "** to " + router.destinationCount(guildId, group) + " channel(s).");
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
