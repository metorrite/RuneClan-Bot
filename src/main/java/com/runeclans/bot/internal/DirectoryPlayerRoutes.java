package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.internal.SiteStatsRepository.MemberRow;
import com.runeclans.bot.runescape.ClanPointsRepository;
import com.runeclans.bot.runescape.ClanPointsRepository.RankConfigRow;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.TrackedNames;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.runeclans.bot.runescape.SkillValue;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A player's clans and stats for their public profile on runeclans.com: {@code GET players/{rsn}} answers
 * {@code {rsn, clans: [{slug, name, rank, rankOrder, maxRankOrder, firstSeen, joined, joinedExact, lastPolled}], stats: {snapshotAt, totalLevel,
 * totalXp, combatLevel, questsComplete, skills: [{id, name, level, xp, rank}], gains: {day, week, month}, history: [{date, totalXp, totalLevel}],
 * skillGains: {week, month: [{id, name, xp}]}} | null}}. {@code GET players} answers the top players of the last seven days across the clan
 * leaderboards ({@link DirectoryPlayerLogic#topPlayers}), for the website's Find a player page.
 * <p>
 * Only clans the public directory lists, where the player's member page is public ({@link DirectoryPlayerLogic}); 404 when there is none, so a
 * hidden member looks the same as a name nobody knows; 400 for a name that isn't a RuneScape name. Everything here is already on those clans'
 * public pages, and no Discord identity is ever read. Nothing waits on Discord: it is all the bot's own database. Answers are cached for a
 * minute, by name, in the shared {@link SiteCache}, so a privacy toggle or an admin hiding a member (which clear that cache) drops them at once.
 */
@BService
public class DirectoryPlayerRoutes implements DirectoryRouteModule {
    private static final long TTL_MILLIS = 60_000;
    /** The top players list reads every leaderboard clan's gains, so it is kept a little longer. */
    private static final long TOP_TTL_MILLIS = 120_000;
    /** Key prefix in the shared cache. Names are typed by visitors; the cache's own sweep drops names nobody asks for again. */
    static final String CACHE_PREFIX = "directory-player:";

    private final DirectoryPlayerLogic.Source source;
    private final DirectoryPlayerLogic.TopSource top;
    private final SiteCache cache;

    public DirectoryPlayerRoutes(ClanProfileRepository profiles, ClanProfileService service, SiteApi siteApi, SiteStatsRepository stats,
                                 PlayerLinkRepository links, ClanPointsRepository points, SiteCache cache, TrackedNames tracker) {
        this.cache = cache;
        this.source = new DirectoryPlayerLogic.Source() {
            @Override
            public List<Long> guildsWithActiveMember(String rsn) {
                return stats.guildsWithActiveMember(rsn);
            }

            @Override
            public Optional<ClanProfile> profile(long guildId) {
                return profiles.get(guildId);
            }

            @Override
            public String clanName(long guildId) {
                return service.clanNameOf(guildId);
            }

            @Override
            public MemberRow visibleMember(long guildId, String rsn) {
                return siteApi.visibleMember(guildId, rsn);
            }

            @Override
            public boolean hiddenAnywhere(String rsn) {
                return siteApi.hidesFromRankingsAnywhere(rsn);
            }

            @Override
            public boolean linked(String rsn) {
                return tracker.tracked(rsn);
            }

            @Override
            public int maxRankOrder(long guildId) {
                // the hub's ladder is always the standard one (T-978): no hub read needed
                return com.runeclans.bot.runescape.ClanPointsRepository.STANDARD_RANK_NAMES.size() - 1;
            }

            @Override
            public List<StatsSnapshotRow> snapshots(String rsn) {
                return links.getSnapshotHistory(rsn, DirectoryPlayerLogic.SNAPSHOTS);
            }

            @Override
            public List<SkillValue> skills(long snapshotId) {
                return links.getSkillsForSnapshot(snapshotId);
            }
        };
        this.top = new DirectoryPlayerLogic.TopSource() {
            @Override
            public List<ClanProfile> sharedClans() {
                return profiles.shared();
            }

            @Override
            public String clanName(long guildId) {
                return service.clanNameOf(guildId);
            }

            @Override
            public List<SiteStatsRepository.Gain> gains(long guildId, OffsetDateTime since) {
                return stats.xpGains(guildId, since);
            }

            @Override
            public MemberRow visibleMember(long guildId, String rsn) {
                return siteApi.visibleMember(guildId, rsn);
            }
        };
    }

    /** Whether {@code GET players/{rsn}} shows this name (the same cached answer), for the Refresh route: a hidden name stays unknown there too. */
    boolean shown(String rsn) {
        String clean = DirectoryPlayerLogic.cleanRsn(rsn);
        if (clean == null) return false;
        return cache.get(CACHE_PREFIX + clean.toLowerCase(Locale.ROOT), TTL_MILLIS, () -> {
            DataObject built = DirectoryPlayerLogic.answer(clean, source, OffsetDateTime.now(ZoneOffset.UTC));
            return built == null ? null : built.put("generatedAt", Instant.now().toString());
        }) != null;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("players") || parts.length > 2) return false;
        if (!request.requireGet()) return true;
        if (parts.length == 1) {
            request.send(200, cache.get(CACHE_PREFIX + "#top", TOP_TTL_MILLIS, () -> DirectoryPlayerLogic
                    .topPlayers(top, OffsetDateTime.now(ZoneOffset.UTC).minusDays(7), DirectoryPlayerLogic.TOP_PLAYERS)
                    .put("generatedAt", Instant.now().toString())));
            return true;
        }
        String rsn = DirectoryPlayerLogic.cleanRsn(parts[1]);
        if (rsn == null) {
            request.sendError(400, "That isn't a RuneScape name.");
            return true;
        }
        DataObject answer = cache.get(CACHE_PREFIX + rsn.toLowerCase(Locale.ROOT), TTL_MILLIS, () -> {
            DataObject built = DirectoryPlayerLogic.answer(rsn, source, OffsetDateTime.now(ZoneOffset.UTC));
            return built == null ? null : built.put("generatedAt", Instant.now().toString());
        });
        if (answer == null) {
            request.sendError(404, "No such player");
            return true;
        }
        request.send(200, answer);
        return true;
    }
}
