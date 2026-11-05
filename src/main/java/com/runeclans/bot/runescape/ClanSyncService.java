package com.runeclans.bot.runescape;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.tracking.ClassifiedEntry;
import com.runeclans.bot.tracking.TrackingEventRouter;
import com.runeclans.bot.tracking.TrackingGroup;
import com.runeclans.bot.tracking.TrackingIconCatalog;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers and tracks the clan's roster from the Clan Hiscores API, independent of
 * {@link PlayerLinkService}'s verified links — a name shows up here, and gets its full stats
 * polled, whether or not anyone has claimed it belongs to them. This is what makes "track the
 * whole clan even where nobody's verified" possible, since polling only ever needs a raw RSN, not a link.
 */
@BService
public class ClanSyncService {
    private static final Logger log = LoggerFactory.getLogger(ClanSyncService.class);

    private final RuneScapeApiClient apiClient;
    private final ClanMemberRepository clanMemberRepository;
    private final RsnRenameService renameService;
    private final GuildSettingsService guildSettingsService;
    private final TrackingEventRouter trackingEventRouter;
    private final WeeklyDigestRepository weeklyDigestRepository;
    private final TrackingIconCatalog trackingIconCatalog;
    private final VerificationRoleSyncService roleSyncService;
    private final PlayerLinkService linkService;

    /** Held while a refresh reads the stored roster, rewrites it and announces the difference. */
    private final Object rosterLock = new Object();
    /** When each guild's roster was last refreshed (by anything), as epoch millis — what {@link #refreshRosterIfStale} throttles on. */
    private final Map<Long, Long> lastRosterRefresh = new ConcurrentHashMap<>();
    private static final long QUICK_REFRESH_MIN_INTERVAL_MS = 60_000L;
    /** Fewer vanished names than this are never treated as a bad response, however small the clan. */
    private static final int MIN_DEPARTURE_CAP = 5;

    public ClanSyncService(RuneScapeApiClient apiClient, ClanMemberRepository clanMemberRepository,
                            RsnRenameService renameService,
                            GuildSettingsService guildSettingsService,
                            TrackingEventRouter trackingEventRouter, WeeklyDigestRepository weeklyDigestRepository,
                            TrackingIconCatalog trackingIconCatalog, VerificationRoleSyncService roleSyncService,
                            PlayerLinkService linkService) {
        this.apiClient = apiClient;
        this.clanMemberRepository = clanMemberRepository;
        this.renameService = renameService;
        this.guildSettingsService = guildSettingsService;
        this.trackingEventRouter = trackingEventRouter;
        this.weeklyDigestRepository = weeklyDigestRepository;
        this.trackingIconCatalog = trackingIconCatalog;
        this.roleSyncService = roleSyncService;
        this.linkService = linkService;
    }

    public record SyncResult(int rosterSize, int newMembers, int departedMembers, int polled, int pollFailed) {}

    /** This guild's configured clan name (own override, or the {@code BotConfig} default) — {@code null} if never configured either way. */
    public String getClanName(long guildId) {
        return guildSettingsService.getEffective(guildId).clanName();
    }

    /** The tracked roster from the last sync — {@code activeOnly} excludes members no longer seen in the clan. */
    public List<ClanMemberRepository.ClanMemberRow> getRoster(long guildId, boolean activeOnly) {
        return clanMemberRepository.getAll(guildId, activeOnly);
    }

    /** How many members the tracked roster has, without loading it ({@code activeOnly} as in {@link #getRoster}). */
    public int countRoster(long guildId, boolean activeOnly) {
        return clanMemberRepository.count(guildId, activeOnly);
    }

    /** Manual dev backfill for a clan member's join date — see {@link ClanMemberRepository#setClanJoinedAt}. */
    public boolean setClanJoinedAt(long guildId, String rsn, LocalDate joinedAt) {
        return clanMemberRepository.setClanJoinedAt(guildId, rsn, joinedAt);
    }

    /**
     * Refreshes the roster (adds new members, updates ranks/XP/kills, marks anyone no longer listed
     * as inactive). The hub keeps every listed member's stats current, so nothing is polled here and every listed
     * member counts as up to date in the result.
     * <p>
     * Also runs rename detection ({@link RsnRenameService}) against this cycle's departed/new sets
     * — takes a {@link Guild}, not just a guild ID, since a detected rename may need to DM the
     * linked player or post to the admin alert channel. Returns an all-zero {@link SyncResult} if
     * this guild has no clan name configured yet (via {@code /configure}) — callers should check
     * {@link #getClanName} first to tell that apart from "fetch failed" with a clearer message.
     */
    public SyncResult syncAndPoll(Guild guild) {
        return syncAndPollGroup(List.of(guild)).getOrDefault(guild.getIdLong(), EMPTY_RESULT);
    }

    private static final SyncResult EMPTY_RESULT = new SyncResult(0, 0, 0, 0, 0);

    /** A clan name as a key: case, runs of spaces and underscores don't tell two clans apart (RuneScape treats them as the same name). */
    public static String clanKey(String clanName) {
        return clanName == null ? null : clanName.strip().replace('_', ' ').replace(' ', ' ').replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * {@link #syncAndPoll} for every server that has set up the same clan, doing the shared work once: the clan list is fetched once, every roster
     * player is polled once, and the result is then applied to each server on its own (roster rows, join/leave announcements, rename detection,
     * role sync), so each server ends up exactly as if it had synced alone. A server with no clan configured, or whose clan is not the first
     * server's, is skipped. A server that fails is logged and does not stop the others. The answer holds one result per server that was synced.
     */
    public Map<Long, SyncResult> syncAndPollGroup(List<Guild> guilds) {
        return syncAndPollGroup(guilds, List.of());
    }

    /**
     * {@link #syncAndPollGroup(List)} with bot-less sites of the same clan too ({@code botlessGuildIds}: servers the bot isn't in whose clan
     * website shows this clan). Their roster rows, join dates and roster events are stored by server id like any other server's, so their site
     * shows the clan's stats; nothing is posted or changed in Discord for them (no join/leave posts, rename checks or roles), since the bot isn't
     * there. The clan is still fetched and polled once for the whole group.
     */
    public Map<Long, SyncResult> syncAndPollGroup(List<Guild> guilds, List<Long> botlessGuildIds) {
        return syncAndPollGroup(guilds, botlessGuildIds, false);
    }

    /**
     * {@link #syncAndPollGroup(List, List)}, saying whether this is the daily background run. A background run on a bot that is not live leaves
     * the servers' Member/Guest roles alone ({@link AutoPostRouter#skipSweep}, T-187): it would give and take roles in the real server by itself.
     * An admin's "sync now" still applies them.
     */
    public Map<Long, SyncResult> syncAndPollGroup(List<Guild> guilds, List<Long> botlessGuildIds, boolean background) {
        Map<Long, SyncResult> results = new HashMap<>();
        if (guilds.isEmpty() && botlessGuildIds.isEmpty()) return results;

        String clanName = getClanName(guilds.isEmpty() ? botlessGuildIds.get(0) : guilds.get(0).getIdLong());
        if (clanName == null) return results;
        String key = clanKey(clanName);
        List<Guild> members = new ArrayList<>();
        for (Guild guild : guilds) {
            String name = getClanName(guild.getIdLong());
            if (name != null && key.equals(clanKey(name))) members.add(guild);
        }
        List<Long> botlessMembers = new ArrayList<>();
        for (long guildId : botlessGuildIds) {
            String name = getClanName(guildId);
            if (name != null && key.equals(clanKey(name))) botlessMembers.add(guildId);
        }

        // one fetch for the whole group
        List<RuneScapeApiClient.ClanMember> roster = apiClient.fetchClanRoster(clanName);
        if (roster.isEmpty()) return results;

        Map<Guild, RosterChange> changes = new java.util.LinkedHashMap<>();
        Map<Long, RosterChange> botlessChanges = new java.util.LinkedHashMap<>();
        Set<String> allNewLower = new HashSet<>();
        for (Guild guild : members) {
            try {
                RosterChange change = refreshRoster(guild, roster);
                changes.put(guild, change);
                allNewLower.addAll(change.newLower());
            } catch (Exception e) {
                log.error("Clan roster refresh failed for guild {}", guild.getIdLong(), e);
            }
        }
        for (long guildId : botlessMembers) {
            try {
                botlessChanges.put(guildId, refreshRoster(guildId, null, roster));
            } catch (Exception e) {
                log.error("Clan roster refresh failed for the bot-less site of guild {}", guildId, e);
            }
        }
        if (changes.isEmpty() && botlessChanges.isEmpty()) return results;

        // Only kept for names that just appeared (in any of the servers) — the rename check is the only thing
        // that needs the full profile result, not just pass/fail, and there's no reason to hold onto
        // every other roster member's full skills/activities in memory once its snapshot is saved.
        Map<String, ProfileResult> newMemberResults = new HashMap<>();
        PollTally tally = pollRsns(roster.stream().map(RuneScapeApiClient.ClanMember::rsn).toList(), allNewLower, newMemberResults);

        for (var entry : changes.entrySet()) {
            Guild guild = entry.getKey();
            RosterChange change = entry.getValue();
            try {
                Set<String> maybeRenamed = detectRenames(guild, change, newMemberResults);
                if (!background || !AutoPostRouter.skipSweep("clan sync roles")) applyMembershipRoles(guild, change, maybeRenamed);
                log.info("Clan sync for '{}' (guild {}) finished: {} in roster, {} new, {} departed, {}/{} up to date.",
                        clanName, guild.getIdLong(), roster.size(), change.newLower().size(), change.departedLower().size(), tally.polled(), roster.size());
                results.put(guild.getIdLong(), new SyncResult(roster.size(), change.newLower().size(), change.departedLower().size(), tally.polled(), tally.pollFailed()));
            } catch (Exception e) {
                log.error("Clan sync follow-up failed for guild {}", guild.getIdLong(), e);
            }
        }
        botlessChanges.forEach((guildId, change) -> results.put(guildId,
                new SyncResult(roster.size(), change.newLower().size(), change.departedLower().size(), tally.polled(), tally.pollFailed())));
        return results;
    }

    /** What the clan roster looked like before and after one refresh: who appeared and who vanished. */
    private record RosterChange(List<RuneScapeApiClient.ClanMember> roster, List<ClanMemberRepository.ClanMemberRow> before,
                                List<String> newNames, Set<String> newLower, List<String> departedNames, Set<String> departedLower) {}

    /** What {@link #refreshRosterIfStale} found: whether it looked at all, and which Discord users' linked accounts have just joined the clan. */
    public record QuickRefresh(boolean refreshed, Set<Long> joinedDiscordUserIds) {
        static final QuickRefresh SKIPPED = new QuickRefresh(false, Set.of());
    }

    /** Fetches the clan list once, updates the stored roster to match, and announces joins and leaves. Doesn't poll anyone: that's what makes it quick. Null if there's nothing to go on (no clan configured, or the fetch came back empty). */
    private RosterChange refreshRoster(Guild guild, String clanName) {
        List<RuneScapeApiClient.ClanMember> roster = apiClient.fetchClanRoster(clanName);
        if (roster.isEmpty()) return null;
        return refreshRoster(guild, roster);
    }

    /** {@link #refreshRoster(Guild, String)} with the clan list already fetched (non-empty), so several servers of one clan can share the one fetch. */
    private RosterChange refreshRoster(Guild guild, List<RuneScapeApiClient.ClanMember> roster) {
        return refreshRoster(guild.getIdLong(), guild, roster);
    }

    /** The refresh for a server by id; {@code guild} null for a bot-less site's server, where the join and leave posts are left out. */
    private RosterChange refreshRoster(long guildId, Guild guild, List<RuneScapeApiClient.ClanMember> roster) {
        // One refresh at a time: a manual refresh and the daily sync both read "who was in the roster", change it,
        // and announce the difference, so two at once would each announce the same join.
        synchronized (rosterLock) {
            lastRosterRefresh.put(guildId, System.currentTimeMillis());

            List<ClanMemberRepository.ClanMemberRow> before = clanMemberRepository.getAll(guildId, true);
            Set<String> beforeLower = new HashSet<>();
            for (var row : before) beforeLower.add(row.rsn().toLowerCase());
            // The very first sync for a guild has an empty `before` — every roster member would
            // otherwise look "new" and flood the joins/leaves channel with the whole clan at once.
            boolean firstSyncEver = before.isEmpty();

            Set<String> currentLower = new HashSet<>();
            Set<String> newLower = new HashSet<>();
            List<String> newNames = new ArrayList<>();
            for (var member : roster) {
                clanMemberRepository.upsert(guildId, member.rsn(), member.clanRank(), member.totalXp(), member.kills());
                String lower = member.rsn().toLowerCase();
                currentLower.add(lower);
                if (!beforeLower.contains(lower)) {
                    newLower.add(lower);
                    newNames.add(member.rsn());
                }
            }

            Set<String> departedLower = new HashSet<>();
            List<String> departedNames = new ArrayList<>();
            for (var row : before) {
                String rsnLower = row.rsn().toLowerCase();
                if (!currentLower.contains(rsnLower)) {
                    clanMemberRepository.markInactive(guildId, rsnLower);
                    departedLower.add(rsnLower);
                    departedNames.add(row.rsn());
                }
            }

            // The day of detection, not an exact time — a roster diff only runs when someone asks for one
            // (the daily sync, or a member updating themselves), so that's all this moment actually knows.
            // Also becomes clan_joined_at for a brand-new member automatically (see the loop below); an
            // existing member's clan_joined_at is never touched here, since for them "today" would be
            // wrong — it stays whatever's been manually backfilled for the roster as it stood when this
            // system was built.
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            String todayDisplay = today.format(DateTimeFormatter.ofPattern("MMM d"));

            if (!firstSyncEver) {
                // No dedicated icon for a join/leave — the Clan Citadel icon fills in as the general
                // "clan" icon here, not RuneScore (a personal-achievement icon that doesn't fit a
                // clan-membership event).
                String icon = trackingIconCatalog.mentionForCategory("citadel");
                String iconPrefix = icon != null ? icon + " " : "";

                List<ClassifiedEntry> joinLeaveEntries = new ArrayList<>();
                for (String name : newNames) {
                    joinLeaveEntries.add(new ClassifiedEntry(TrackingGroup.CLAN_JOINS_LEAVES, iconPrefix + "**" + name + "** joined the clan. (" + todayDisplay + ")"));
                    weeklyDigestRepository.recordRosterEvent(guildId, name, "JOIN");
                    clanMemberRepository.setClanJoinedAt(guildId, name, today);
                }
                for (String name : departedNames) {
                    joinLeaveEntries.add(new ClassifiedEntry(TrackingGroup.CLAN_JOINS_LEAVES, iconPrefix + "**" + name + "** left the clan. (" + todayDisplay + ")"));
                    weeklyDigestRepository.recordRosterEvent(guildId, name, "LEAVE");
                }
                if (guild != null) trackingEventRouter.dispatchAll(guild, joinLeaveEntries);
            }

            return new RosterChange(roster, before, newNames, newLower, departedNames, departedLower);
        }
    }

    /**
     * Pairs this refresh's vanished names with its appeared ones as possible renames. Returns the vanished
     * names it couldn't rule out (see {@link RsnRenameService#detectAndNotify}); if the check itself fails,
     * that is every vanished name, so nobody is demoted on the strength of a roster diff nobody could vet.
     */
    private Set<String> detectRenames(Guild guild, RosterChange change, Map<String, ProfileResult> newMemberResults) {
        try {
            return renameService.detectAndNotify(guild, change.before(), change.roster(), change.departedLower(), change.newLower(), newMemberResults);
        } catch (Exception e) {
            log.error("Rename detection failed during clan sync for guild {}", guild.getIdLong(), e);
            return change.departedLower();
        }
    }

    /**
     * What a member updating themselves does when their RSN isn't on the stored roster yet: fetches the clan
     * list and updates the roster from it — one request and a few database writes, nobody's profile is polled
     * — and, if their name (or anyone else's) has just appeared, gives that linked player their Member role
     * straight away instead of at the next daily sync. Guild-wide, at most one fetch a minute, so a run of
     * people pressing Update doesn't turn into a run of requests to Jagex; within that minute it does nothing.
     * <p>
     * Only when a name has both vanished and appeared does it poll anything (just the appeared names), because
     * rename detection needs their profiles to tell a rename from a leave and a join.
     */
    public QuickRefresh refreshRosterIfStale(Guild guild) {
        long guildId = guild.getIdLong();
        String clanName = getClanName(guildId);
        if (clanName == null) return QuickRefresh.SKIPPED;

        Long last = lastRosterRefresh.get(guildId);
        if (last != null && System.currentTimeMillis() - last < QUICK_REFRESH_MIN_INTERVAL_MS) return QuickRefresh.SKIPPED;

        RosterChange change = refreshRoster(guild, clanName);
        if (change == null) return QuickRefresh.SKIPPED;

        Map<String, ProfileResult> newMemberResults = new HashMap<>();
        if (!change.newLower().isEmpty() && !change.departedLower().isEmpty()) {
            pollRsns(change.newNames(), change.newLower(), newMemberResults);
        }
        Set<String> maybeRenamed = detectRenames(guild, change, newMemberResults);

        log.info("Quick roster refresh for '{}' (guild {}): {} in roster, {} new, {} departed.",
                clanName, guildId, change.roster().size(), change.newLower().size(), change.departedLower().size());
        return new QuickRefresh(true, applyMembershipRoles(guild, change, maybeRenamed));
    }

    /**
     * Brings Discord roles in line with a roster refresh: a linked player whose RSN just joined gets Member,
     * one whose RSN just left goes back to Guest (unless another of their accounts is still in the clan).
     * Skipped for a name that might be a rename — its player hasn't left, they've changed name, and an admin
     * hasn't yet said so — and for departures altogether when an implausible share of the roster vanished at
     * once, which is far likelier a bad response from Jagex than a real exodus. Returns the Discord users
     * whose joins were applied.
     */
    private Set<Long> applyMembershipRoles(Guild guild, RosterChange change, Set<String> maybeRenamedLower) {
        long guildId = guild.getIdLong();
        Set<Long> joined = new HashSet<>();

        for (String name : change.newNames()) {
            try {
                PlayerLink link = linkService.getLinkForRsn(guildId, name);
                if (link == null) continue;
                roleSyncService.syncRoles(guild, link.discordUserId(), name);
                joined.add(link.discordUserId());
            } catch (Exception e) {
                log.warn("Couldn't update roles for '{}' joining the clan", name, e);
            }
        }

        int departureCap = Math.max(MIN_DEPARTURE_CAP, change.before().size() / 5);
        if (change.departedNames().size() > departureCap) {
            log.warn("{} of {} clan members vanished from the roster at once in guild {} — treating it as a bad response and leaving their roles alone.",
                    change.departedNames().size(), change.before().size(), guildId);
            return joined;
        }
        for (String name : change.departedNames()) {
            if (maybeRenamedLower.contains(name.toLowerCase())) continue;
            try {
                PlayerLink link = linkService.getLinkForRsn(guildId, name);
                if (link != null) roleSyncService.syncLeftClan(guild, link.discordUserId());
            } catch (Exception e) {
                log.warn("Couldn't update roles for '{}' leaving the clan", name, e);
            }
        }
        return joined;
    }

    /** Whether {@code rsn} is on the stored roster as a current clan member — a single lookup, no fetch. */
    public boolean isActiveMember(long guildId, String rsn) {
        return clanMemberRepository.isActiveMember(guildId, rsn);
    }

    public record RosterPollResult(int polled, int pollFailed) {}

    /**
     * What the "send now" tools report for a server's clan: every currently-active roster member counts as polled, because the hub keeps
     * them current on its own (the bot polls nothing). Doesn't touch the roster itself or run rename detection, that is
     * {@link #syncAndPoll}'s job, once a day.
     */
    public RosterPollResult pollActiveRosterOnly(long guildId, Duration window) {
        int count = clanMemberRepository.getAll(guildId, true).size();
        return new RosterPollResult(count, 0);
    }

    private record PollTally(int polled, int pollFailed) {}

    /**
     * Shared by {@link #syncAndPoll} and {@link #refreshRosterIfStale}: the hub keeps every roster member current (T-851), so there is nothing
     * to queue and every name counts as polled; the rename check reads the hub's snapshots itself, so a name that just appeared gets an
     * "unavailable" result here.
     */
    private PollTally pollRsns(List<String> rsns, Set<String> newLower, Map<String, ProfileResult> newMemberResultsOut) {
        for (String rsn : rsns) if (newLower.contains(rsn.toLowerCase())) newMemberResultsOut.put(rsn.toLowerCase(), new ProfileResult.Unavailable());
        return new PollTally(rsns.size(), 0);
    }
}
