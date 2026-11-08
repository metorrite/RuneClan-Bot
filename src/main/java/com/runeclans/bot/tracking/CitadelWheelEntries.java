package com.runeclans.bot.tracking;

import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.runescape.ClanMemberRepository;
import com.runeclans.bot.runescape.ClanPointsRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.wheel.WheelEligibility;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Who is on the Citadel wheel for a week: the accounts that capped, reduced to one entry per person and without staff (see {@link WheelEligibility}).
 * Admin in Discord means the Administrator permission or the bot's Admin tier; admin in the clan means the "Admin" rank or higher on the server's
 * rank list. Used by the Discord wheel, its Copy names button and the dashboard's wheel page, so all three agree.
 */
@BService
public class CitadelWheelEntries {
    private static final Logger log = LoggerFactory.getLogger(CitadelWheelEntries.class);
    static final String ADMIN_RANK = "Admin";
    /** How long "is this person a Discord admin" is remembered. The page asks about the same people every time it loads. */
    private static final long ADMIN_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    /** Discord answers a member lookup by ids for up to 100 people at once. */
    private static final int LOOKUP_BATCH = 100;

    private record KnownAdmin(boolean admin, long at) {}

    private final Map<String, KnownAdmin> adminCache = new ConcurrentHashMap<>();

    private final WeeklyDigestService digest;
    private final ClanMemberRepository clanMembers;
    private final PlayerLinkRepository links;
    private final ClanPointsRepository ranks;
    private final AdminRoleFilter adminFilter;

    public CitadelWheelEntries(WeeklyDigestService digest, ClanMemberRepository clanMembers, PlayerLinkRepository links, ClanPointsRepository ranks, AdminRoleFilter adminFilter) {
        this.digest = digest;
        this.clanMembers = clanMembers;
        this.links = links;
        this.ranks = ranks;
        this.adminFilter = adminFilter;
    }

    public WheelEligibility.Result forWeek(Guild guild, OffsetDateTime start, OffsetDateTime end) {
        long guildId = guild.getIdLong();
        long began = System.nanoTime();
        List<String> cappers = digest.getCappedRsns(guildId, start, end);
        long cappersDone = System.nanoTime();

        Map<String, String> rankByRsn = new HashMap<>();
        for (var member : clanMembers.getAll(guildId, true)) rankByRsn.put(member.rsn().toLowerCase(Locale.ROOT), member.clanRank());
        var predicate = WheelEligibility.atLeast(ClanPointsRepository.STANDARD_RANK_NAMES, ADMIN_RANK); // the hub's ladder is always the standard one (T-978)

        List<WheelEligibility.Link> accountLinks = links.getAllLinks(guildId).stream().map(l -> new WheelEligibility.Link(l.rsn(), l.discordUserId())).toList();
        long dataDone = System.nanoTime();

        Set<String> capperNames = new HashSet<>();
        for (String capper : cappers) capperNames.add(capper.toLowerCase(Locale.ROOT));
        Set<Long> people = new HashSet<>();
        for (var link : accountLinks) if (capperNames.contains(link.rsn().toLowerCase(Locale.ROOT))) people.add(link.discordUserId());
        loadAdmins(guild, people);
        long adminsDone = System.nanoTime();

        var result = WheelEligibility.select(cappers, rankByRsn, predicate, accountLinks, user -> isDiscordAdmin(guild, user));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        if (ms > 1000) {
            log.warn("The Citadel wheel's entries took {} ms in guild {} (capped list {} ms, roster/ranks/links {} ms, {} Discord lookups {} ms)", ms, guildId,
                    TimeUnit.NANOSECONDS.toMillis(cappersDone - began), TimeUnit.NANOSECONDS.toMillis(dataDone - cappersDone), people.size(),
                    TimeUnit.NANOSECONDS.toMillis(adminsDone - dataDone));
        }
        return result;
    }

    /**
     * Works out which of these people are Discord admins in as few Discord requests as possible: those whose member the bot doesn't hold are asked for
     * in batches of up to 100 (one request each) rather than one request per person, and the answers are remembered for a few minutes.
     */
    private void loadAdmins(Guild guild, Set<Long> people) {
        List<Long> missing = new ArrayList<>();
        for (long id : people) if (known(guild, id) == null && guild.getMemberById(id) == null) missing.add(id);
        for (int from = 0; from < missing.size(); from += LOOKUP_BATCH) {
            List<Long> batch = missing.subList(from, Math.min(missing.size(), from + LOOKUP_BATCH));
            try {
                var found = new CompletableFuture<List<Member>>();
                guild.retrieveMembersByIds(batch).onSuccess(found::complete).onError(found::completeExceptionally);
                Set<Long> seen = new HashSet<>();
                for (Member member : found.get(10, TimeUnit.SECONDS)) {
                    seen.add(member.getIdLong());
                    remember(guild, member.getIdLong(), isAdmin(guild, member));
                }
                // not in the server any more: not staff
                for (long id : batch) if (!seen.contains(id)) remember(guild, id, false);
            } catch (Exception e) {
                // each person is then looked up on their own in isDiscordAdmin
                log.debug("Couldn't look up {} members in one go", batch.size(), e);
            }
        }
    }

    private boolean isDiscordAdmin(Guild guild, long userId) {
        Boolean cached = known(guild, userId);
        if (cached != null) return cached;
        try {
            Member member = guild.getMemberById(userId);
            if (member == null) member = guild.retrieveMemberById(userId).submit().get(5, TimeUnit.SECONDS);
            boolean admin = isAdmin(guild, member);
            remember(guild, userId, admin);
            return admin;
        } catch (Exception e) {
            // someone who has left the server, or a lookup that failed, isn't treated as staff
            log.debug("Couldn't check whether {} is an admin", userId, e);
            return false;
        }
    }

    private boolean isAdmin(Guild guild, Member member) {
        return member.hasPermission(Permission.ADMINISTRATOR) || adminFilter.isAuthorized(guild, member);
    }

    private Boolean known(Guild guild, long userId) {
        KnownAdmin known = adminCache.get(guild.getIdLong() + ":" + userId);
        return known != null && System.currentTimeMillis() - known.at() < ADMIN_TTL_MS ? known.admin() : null;
    }

    private void remember(Guild guild, long userId, boolean admin) {
        adminCache.put(guild.getIdLong() + ":" + userId, new KnownAdmin(admin, System.currentTimeMillis()));
    }
}
