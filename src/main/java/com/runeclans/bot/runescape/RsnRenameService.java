package com.runeclans.bot.runescape;

import com.runeclans.bot.discord.Mentions;
import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects a player renaming their RuneScape account, using only data {@link ClanSyncService}
 * already fetches during a normal sync — no extra polling, no per-poll storage.
 * <p>
 * The only discovery signal available at all is the clan roster: a name that vanishes from it one
 * sync and a name that appears the next are the only pair worth comparing, since there's no way to
 * enumerate RS3 accounts otherwise. From there, two independent, ever-stronger checks (both
 * comparing only within that one sync's disappeared/appeared sets, never against continuing
 * members):
 * <ol>
 *     <li><b>XP-plausible pairing</b> — the appeared name's Total XP must be >= the vanished name's
 *     last known Total XP (RS3 XP never decreases). Free: both values come from the Clan Hiscores
 *     CSV a sync already fetched.</li>
 *     <li><b>Per-skill monotonicity + activity overlap</b> — every one of the 29 skills must be
 *     individually non-decreasing (any decrease rules the pair out entirely), and ideally the new
 *     name's freshly-polled activity feed contains entries that exactly match ones already recorded
 *     for the old name. A coincidental Total XP match at the very top end is possible between two
 *     unrelated high-level accounts; matching activity log entries (specific actions on specific
 *     dates) essentially isn't. Free: the profile poll a sync already does for every current roster
 *     member includes {@code activities} in the same response.</li>
 * </ol>
 * A pair that only clears check 1 (or where more than one candidate pairs with the same vanished
 * name, or vice versa) is flagged to admins rather than auto-resolved — see {@link #detectAndNotify}.
 */
@BService
public class RsnRenameService {
    private static final Logger log = LoggerFactory.getLogger(RsnRenameService.class);

    // A generous per-sync-cycle ceiling on how much Total XP could plausibly separate the same
    // account's before/after readings — wide enough to never reject a real rename, tight enough to
    // reject an obviously-unrelated pairing (e.g. a max-XP veteran coarsely "matching" a fresh recruit).
    private static final long MAX_PLAUSIBLE_XP_GAIN = 2_000_000_000L;
    private static final int ACTIVITY_MATCH_DEPTH = 5;

    private final RuneScapeStatsService statsService;
    private final PlayerLinkService linkService;
    private final RsnRenameRepository renameRepository;
    private final GuildSettingsService guildSettingsService;
    private final PermissionGroupService permissionGroups;

    public RsnRenameService(RuneScapeStatsService statsService, PlayerLinkService linkService,
                             RsnRenameRepository renameRepository, GuildSettingsService guildSettingsService, PermissionGroupService permissionGroups) {
        this.permissionGroups = permissionGroups;
        this.statsService = statsService;
        this.linkService = linkService;
        this.renameRepository = renameRepository;
        this.guildSettingsService = guildSettingsService;
    }

    private record Pair(String oldRsn, RuneScapeApiClient.ClanMember newMember) {
    }

    /**
     * Compares this sync's disappeared names against its appeared names and flags any plausible
     * renames. {@code polledResults} only needs entries for {@code newLower}'s names — the profile
     * poll {@link ClanSyncService} already runs for every current roster member during its own sync.
     * <p>
     * Returns the (lowercase) departed names that look like they might be a rename — any name that
     * paired with an appeared one, whether it was then flagged, ambiguous, or couldn't be checked further
     * — so the caller can leave that player's roles alone until an admin has decided.
     */
    public Set<String> detectAndNotify(Guild guild, List<ClanMemberRepository.ClanMemberRow> before,
                                        List<RuneScapeApiClient.ClanMember> currentRoster,
                                        Set<String> departedLower, Set<String> newLower,
                                        Map<String, ProfileResult> polledResults) {
        if (departedLower.isEmpty() || newLower.isEmpty()) return Set.of();

        Map<String, ClanMemberRepository.ClanMemberRow> beforeByLower = new HashMap<>();
        for (var row : before) beforeByLower.put(row.rsn().toLowerCase(), row);

        Map<String, RuneScapeApiClient.ClanMember> currentByLower = new HashMap<>();
        for (var member : currentRoster) currentByLower.put(member.rsn().toLowerCase(), member);

        List<Pair> pairs = new ArrayList<>();
        for (String oldLower : departedLower) {
            var oldRow = beforeByLower.get(oldLower);
            if (oldRow == null) continue;

            for (String newLowerName : newLower) {
                var newMember = currentByLower.get(newLowerName);
                if (newMember != null && isXpPlausible(oldRow.totalXp(), newMember.totalXp())) {
                    pairs.add(new Pair(oldRow.rsn(), newMember));
                }
            }
        }
        if (pairs.isEmpty()) return Set.of();

        // Run the deeper checks (per-skill monotonicity + activity overlap) on EVERY XP-plausible pair first,
        // so an ambiguity on Total XP alone can still be settled by the stronger evidence.
        Map<Pair, Assessment> survivors = new LinkedHashMap<>();
        for (Pair pair : pairs) {
            Assessment assessment = assess(pair.oldRsn(), pair.newMember().rsn(), polledResults.get(pair.newMember().rsn().toLowerCase()));
            if (assessment != null) survivors.put(pair, assessment);
        }

        Map<String, List<Pair>> byOld = pairs.stream().collect(Collectors.groupingBy(p -> p.oldRsn().toLowerCase(), LinkedHashMap::new, Collectors.toList()));
        Map<String, List<Pair>> survivorsByOld = survivors.keySet().stream().collect(Collectors.groupingBy(p -> p.oldRsn().toLowerCase()));
        Map<String, List<Pair>> survivorsByNew = survivors.keySet().stream().collect(Collectors.groupingBy(p -> p.newMember().rsn().toLowerCase()));

        for (String oldKey : byOld.keySet()) {
            List<Pair> candidates = byOld.get(oldKey);
            String oldRsn = candidates.get(0).oldRsn();
            List<Pair> passed = survivorsByOld.getOrDefault(oldKey, List.of());

            if (passed.isEmpty()) continue; // every candidate failed the per-skill check — nothing to report

            // Several candidates (or a shared new name): settle it if exactly one clears the activity check
            // and nobody else claims that new name; otherwise leave it to an admin with only the survivors listed.
            List<Pair> exclusive = passed.stream()
                    .filter(p -> survivorsByNew.get(p.newMember().rsn().toLowerCase()).size() == 1)
                    .toList();
            if (passed.size() == 1 && exclusive.size() == 1) {
                raise(guild, oldRsn, exclusive.get(0).newMember().rsn(), survivors.get(exclusive.get(0)));
                continue;
            }
            List<Pair> high = exclusive.stream().filter(p -> survivors.get(p).overlap() > 0).toList();
            if (high.size() == 1 && passed.stream().noneMatch(p -> p != high.get(0) && survivors.get(p).overlap() > 0)) {
                raise(guild, oldRsn, high.get(0).newMember().rsn(), survivors.get(high.get(0)));
                continue;
            }
            notifyAmbiguous(guild, oldRsn, passed.stream().map(p -> p.newMember().rsn()).distinct().toList());
        }
        return Set.copyOf(byOld.keySet());
    }

    private boolean isXpPlausible(long oldTotalXp, long newTotalXp) {
        return newTotalXp >= oldTotalXp && newTotalXp - oldTotalXp <= MAX_PLAUSIBLE_XP_GAIN;
    }

    /** The outcome of the deeper checks for one pair that passed them. */
    private record Assessment(int overlap) {
    }

    /** Runs the deeper (non-free-but-already-fetched) checks; null if the pair is ruled out or can't be checked. */
    private Assessment assess(String oldRsn, String newRsn, ProfileResult newResult) {
        // the bot polls nobody (T-851): the hub's newest snapshot of the newcomer is what there is to compare
        if (!(newResult instanceof ProfileResult.Found)) newResult = statsService.latestAsResult(newRsn);
        if (!(newResult instanceof ProfileResult.Found(var newProfile))) return null;

        var oldSnapshot = statsService.getLatestSnapshot(oldRsn);
        if (oldSnapshot == null) return null; // never actually polled before — nothing to compare against

        List<SkillValue> oldSkills = statsService.getSkillsForSnapshot(oldSnapshot.snapshotId());
        if (!isMonotonic(oldSkills, newProfile.skills())) return null; // a real decrease anywhere rules this pair out

        List<PlayerActivity> oldActivities = statsService.getRecentActivities(oldRsn, ACTIVITY_MATCH_DEPTH);
        return new Assessment(countActivityOverlap(oldActivities, newProfile.activities()));
    }

    /** Records a rename candidate and notifies. */
    private void raise(Guild guild, String oldRsn, String newRsn, Assessment assessment) {
        int overlapCount = assessment.overlap();
        String confidence = overlapCount > 0 ? "HIGH" : "MEDIUM";
        String basis = overlapCount > 0
                ? "Every skill's XP is non-decreasing, plus " + overlapCount + " matching activity log entr" + (overlapCount == 1 ? "y" : "ies") + "."
                : "Every skill's XP is non-decreasing, but no overlapping activity log entries to confirm it (the old account had little recorded activity, or too much has happened since to still overlap).";

        long candidateId = renameRepository.create(guild.getIdLong(), oldRsn, newRsn, confidence, basis);
        notify(guild, renameRepository.getById(candidateId));
    }

    private boolean isMonotonic(List<SkillValue> oldSkills, List<SkillValue> newSkills) {
        Map<Integer, Long> newXpBySkill = new HashMap<>();
        for (SkillValue skill : newSkills) newXpBySkill.put(skill.skillId(), skill.xp());

        for (SkillValue oldSkill : oldSkills) {
            Long newXp = newXpBySkill.get(oldSkill.skillId());
            if (newXp == null || newXp < oldSkill.xp()) return false;
        }
        return true;
    }

    private int countActivityOverlap(List<PlayerActivity> oldActivities, List<PlayerActivity> newActivities) {
        if (oldActivities.isEmpty()) return 0;

        Set<String> newKeys = new HashSet<>();
        for (PlayerActivity activity : newActivities) newKeys.add(activity.date() + "\u0000" + activity.text());

        int count = 0;
        for (PlayerActivity old : oldActivities) {
            if (newKeys.contains(old.date() + "\u0000" + old.text())) count++;
        }
        return count;
    }

    // --- Notification ---

    private void notify(Guild guild, RsnRenameRepository.RenameCandidate candidate) {
        postAdminAlert(guild, candidate);
        if ("HIGH".equals(candidate.confidence())) {
            PlayerLink link = linkService.getLinkForRsn(candidate.guildId(), candidate.oldRsn());
            if (link != null) dmPlayer(guild, link.discordUserId(), candidate);
        }
    }

    private void postAdminAlert(Guild guild, RsnRenameRepository.RenameCandidate candidate) {
        GuildMessageChannel channel = resolveAlertChannel(guild);
        if (channel == null) return;

        String ping = adminPing(guild);
        Container container = Containers.card(
                "HIGH".equals(candidate.confidence()) ? Containers.WARNING : Containers.INFO,
                TextDisplay.of(ping + "### Possible RSN Rename Detected"),
                TextDisplay.of("**" + candidate.oldRsn() + "** → **" + candidate.newRsn() + "**\n" +
                        "Confidence: **" + candidate.confidence() + "**\n" + candidate.basis()),
                ActionRow.of(
                        Button.success("rsnrename_confirm:" + candidate.id(), "Confirm Rename"),
                        Button.danger("rsnrename_reject:" + candidate.id(), "Reject")
                ));

        Mentions.onlyRoles(channel.sendMessageComponents(List.of(container)).useComponentsV2(true), pinged(guild, ping))
                .queue(success -> {}, error -> log.warn("Failed to post rename alert for candidate {}", candidate.id(), error));
    }

    private void notifyAmbiguous(Guild guild, String oldRsn, List<String> candidateNames) {
        GuildMessageChannel channel = resolveAlertChannel(guild);
        if (channel == null) return;

        String ping = adminPing(guild);
        Container container = Containers.card(Containers.WARNING,
                TextDisplay.of(ping + "### Ambiguous Possible Rename"),
                TextDisplay.of("**" + oldRsn + "** disappeared from the clan roster and matched more than one " +
                        "newly-seen name, and the per-skill and activity checks couldn't settle it: " + String.join(", ", candidateNames) + ". Not automated " +
                        "— please check manually with **Player Lookup**."));

        Mentions.onlyRoles(channel.sendMessageComponents(List.of(container)).useComponentsV2(true), pinged(guild, ping))
                .queue(success -> {}, error -> log.warn("Failed to post ambiguous-rename alert for '{}'", oldRsn, error));
    }

    private void dmPlayer(Guild guild, long discordUserId, RsnRenameRepository.RenameCandidate candidate) {
        if (AutoPostRouter.skip("a rename question DM to a player")) return;
        guild.retrieveMemberById(discordUserId).queue(member -> {
            Container container = Containers.card(Containers.INFO,
                    TextDisplay.of("### Did you rename your RuneScape account?"),
                    TextDisplay.of("**" + candidate.oldRsn() + "** left the tracked clan roster and **" + candidate.newRsn() +
                            "** appeared with matching stats. Did you rename **" + candidate.oldRsn() + "** to **" + candidate.newRsn() + "**?"),
                    ActionRow.of(
                            Button.success("rsnrename_confirm:" + candidate.id(), "Yes, that's me"),
                            Button.danger("rsnrename_reject:" + candidate.id(), "No")
                    ));

            member.getUser().openPrivateChannel().queue(
                    dm -> dm.sendMessageComponents(List.of(container)).useComponentsV2(true)
                            .queue(success -> {}, error -> log.info("Couldn't DM user {} about a possible rename (DMs likely closed)", discordUserId)),
                    error -> log.info("Couldn't open a DM with user {} about a possible rename", discordUserId));
        }, error -> log.warn("Failed to retrieve member {} for rename DM", discordUserId, error));
    }

    private GuildMessageChannel resolveAlertChannel(Guild guild) {
        Long channelId = guildSettingsService.getEffective(guild.getIdLong()).renameAlertChannelId();
        if (channelId == null) {
            log.warn("No rename alert channel configured for guild {} — a possible rename was detected but nobody was notified.", guild.getIdLong());
            return null;
        }
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            log.warn("Configured rename alert channel {} not found in guild {}", channelId, guild.getIdLong());
        }
        return AutoPostRouter.route(channel, "a rename alert");
    }

    private String adminPing(Guild guild) {
        if (AutoPostRouter.noPings()) return ""; // a bot that is not live never pings the admin role
        return permissionGroups.roleIds(guild.getIdLong(), PermissionGroup.ADMIN).stream().map(id -> "<@&" + id + "> ").collect(Collectors.joining());
    }

    /** The admin roles {@link #adminPing} named, the only roles the alert may ping (RSNs in it are players' text). */
    private long[] pinged(Guild guild, String ping) {
        if (ping.isEmpty()) return new long[0];
        return permissionGroups.roleIds(guild.getIdLong(), PermissionGroup.ADMIN).stream().mapToLong(Long::longValue).toArray();
    }

    // --- Resolution (confirm/reject buttons, or a manual admin rename) ---

    public RsnRenameRepository.RenameCandidate getCandidate(long candidateId) {
        return renameRepository.getById(candidateId);
    }

    /** Applies the rename to the linked account (if any) and marks the candidate resolved. False if it was already resolved or is gone. */
    public boolean confirm(long candidateId, long resolvedByUserId) {
        RsnRenameRepository.RenameCandidate candidate = renameRepository.getById(candidateId);
        if (candidate == null || !"PENDING".equals(candidate.status())) return false;

        PlayerLink link = linkService.getLinkForRsn(candidate.guildId(), candidate.oldRsn());
        if (link != null) linkService.renameLink(candidate.guildId(), link.linkId(), candidate.newRsn());

        renameRepository.resolve(candidateId, "CONFIRMED");
        log.info("Rename candidate {} confirmed by {}: '{}' -> '{}'", candidateId, resolvedByUserId, candidate.oldRsn(), candidate.newRsn());
        return true;
    }

    /** False if it was already resolved or is gone. */
    public boolean reject(long candidateId, long resolvedByUserId) {
        RsnRenameRepository.RenameCandidate candidate = renameRepository.getById(candidateId);
        if (candidate == null || !"PENDING".equals(candidate.status())) return false;

        renameRepository.resolve(candidateId, "REJECTED");
        log.info("Rename candidate {} rejected by {}", candidateId, resolvedByUserId);
        return true;
    }

    /** Records the audit trail for an admin's manual "Update RSN" action — the actual link update happens at the call site. */
    public void recordManualRename(long guildId, String oldRsn, String newRsn) {
        long id = renameRepository.create(guildId, oldRsn, newRsn, "MANUAL", "Manually updated by an admin via Player Lookup.");
        renameRepository.resolve(id, "CONFIRMED");
    }
}
