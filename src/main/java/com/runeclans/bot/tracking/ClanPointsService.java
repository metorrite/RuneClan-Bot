package com.runeclans.bot.tracking;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.runescape.ClanMemberRepository;
import com.runeclans.bot.runescape.ClanPointsRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The clan points Clan Report: the RuneClans hub awards the points (membership and Citadel visits/caps) after its daily roster fetch and
 * flags anyone whose in-game rank is behind what their points have earned (T-978); this posts those flagged members daily
 * ({@link TrackingGroup#CLAN_REPORT}). The bot no longer awards or flags anything itself.
 */
@BService
public class ClanPointsService {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d");

    private final ClanPointsRepository repository;
    private final ClanMemberRepository clanMemberRepository;
    private final TrackingEventRouter router;
    private final TrackingIconCatalog trackingIconCatalog;

    public ClanPointsService(ClanPointsRepository repository, ClanMemberRepository clanMemberRepository, TrackingEventRouter router,
                              TrackingIconCatalog trackingIconCatalog) {
        this.repository = repository;
        this.clanMemberRepository = clanMemberRepository;
        this.router = router;
        this.trackingIconCatalog = trackingIconCatalog;
    }

    /**
     * Called once a day by {@code ClanPointsScheduler}: sends the Clan Report from the hub's flags if anyone needs a promotion. Returns how
     * many members the report listed (0 if nobody needed one, so nothing was sent) — the scheduler ignores it; {@code /dev clanreport}
     * reports it back.
     */
    public int runDailyPointsAndPromotionCheck(Guild guild) {
        return sendClanReport(guild);
    }

    private static Integer rankOrderFor(List<ClanPointsRepository.RankConfigRow> ranks, String rankName) {
        for (var rank : ranks) {
            if (rank.rankName().equalsIgnoreCase(rankName)) return rank.rankOrder();
        }
        return null;
    }

    /**
     * {@code ranks} must be ordered lowest to highest — the highest rank whose threshold
     * {@code totalPoints} meets or exceeds. The lowest rank is always earned (everyone starts there).
     * Every other tier needs a threshold above 0: 0 is what a tier defaults to until an admin sets it,
     * so counting it as "met" would hand every member the highest unconfigured rank — with only
     * Recruit through Captain configured, that listed the whole clan as eligible for Owner.
     */
    static int earnedRankOrder(List<ClanPointsRepository.RankConfigRow> ranks, long totalPoints) {
        int earned = ranks.getFirst().rankOrder();
        for (var rank : ranks.subList(1, ranks.size())) {
            if (rank.pointThreshold() > 0 && totalPoints >= rank.pointThreshold()) earned = rank.rankOrder();
        }
        return earned;
    }

    /** Returns how many members the report listed — 0 means nothing was sent. */
    private int sendClanReport(Guild guild) {
        long guildId = guild.getIdLong();
        List<ClanPointsRepository.MemberPointsRow> flagged = repository.getAllNeedingPromotion(guildId);
        if (flagged.isEmpty()) return 0;

        List<ClanPointsRepository.RankConfigRow> ranks = repository.getRanksOrdered(guildId);
        Map<Integer, String> rankNameByOrder = new HashMap<>();
        for (var rank : ranks) rankNameByOrder.put(rank.rankOrder(), rank.rankName());

        Map<String, ClanMemberRepository.ClanMemberRow> rosterByRsnLower = new HashMap<>();
        for (var member : clanMemberRepository.getAll(guildId, true)) rosterByRsnLower.put(member.rsn().toLowerCase(Locale.ROOT), member);

        flagged.sort(Comparator.comparing(ClanPointsRepository.MemberPointsRow::promotionNeededSince,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> entries = new ArrayList<>();
        for (var row : flagged) {
            var member = rosterByRsnLower.get(row.rsn().toLowerCase(Locale.ROOT));
            if (member == null) continue; // left the clan since being flagged — nothing to report

            Integer currentOrder = rankOrderFor(ranks, member.clanRank());
            int earnedOrder = earnedRankOrder(ranks, row.totalPoints());
            String earnedRankName = rankNameByOrder.getOrDefault(earnedOrder, "?");
            String since = row.promotionNeededSince() != null ? row.promotionNeededSince().format(DISPLAY_DATE) : "?";

            // The current-rank icon sits directly against the player's name — that pairing *is* how
            // their current rank is shown, rather than also spelling the name out again in text right
            // next to it (two ranks' worth of icon+bold-name in one line read as clutter, not clarity).
            String currentBadge = currentOrder != null ? trackingIconCatalog.mentionForRank(currentOrder) : trackingIconCatalog.mentionForDefaultBoss();
            String earnedBadge = trackingIconCatalog.mentionForRank(earnedOrder);

            entries.add(withIcon(currentBadge, "**" + member.rsn() + "**") + "\n"
                    + "Eligible for " + withIcon(earnedBadge, "**" + earnedRankName + "**")
                    + " • " + row.totalPoints() + " pts • waiting since " + since);
        }

        if (entries.isEmpty()) return 0; // everyone flagged has since left the clan

        // Discord caps a message's total text at 4000 characters, and JDA refuses to even build a longer
        // one — a long enough list would otherwise crash the whole report instead of just being long. So
        // the list is split across as many messages as it takes, each safely under that.
        List<String> pages = paginate(entries);
        for (int i = 0; i < pages.size(); i++) {
            String title = "### 🔺 Clan Report — Promotions Needed" + (pages.size() > 1 ? " (" + (i + 1) + "/" + pages.size() + ")" : "");
            router.dispatchContainer(guild, TrackingGroup.CLAN_REPORT,
                    Containers.card(Containers.WARNING, TextDisplay.of(title), TextDisplay.of(pages.get(i))));
        }
        return entries.size();
    }

    // Comfortably under Discord's 4000-character total, which counts the title and everything else too.
    static final int MAX_PAGE_CHARS = 3500;

    /** Packs {@code entries} (blank-line separated) into pages of at most {@link #MAX_PAGE_CHARS} characters; an entry is never split across pages. */
    static List<String> paginate(List<String> entries) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String entry : entries) {
            if (!page.isEmpty() && page.length() + 2 + entry.length() > MAX_PAGE_CHARS) {
                pages.add(page.toString());
                page.setLength(0);
            }
            if (!page.isEmpty()) page.append("\n\n");
            page.append(entry);
        }
        if (!page.isEmpty()) pages.add(page.toString());
        return pages;
    }

    private static String withIcon(String iconMention, String text) {
        return iconMention != null ? iconMention + " " + text : text;
    }

}
