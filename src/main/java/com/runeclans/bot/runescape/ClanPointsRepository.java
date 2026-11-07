package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Clan points (see {@code com.runeclans.bot.tracking.ClanPointsService}), kept on the RuneClans hub since T-978: the rank ladder and its
 * point thresholds, the daily/Citadel point values, each member's running total and promotion-needed flag. The hub awards the points after
 * its daily roster fetch; this reads them ({@code GET /v1/guilds/{guildId}/points}, kept a minute) and sends the Discord panel's changes
 * ({@code PUT .../points}, {@code POST .../points/promoted}). The bot's old {@code clan_rank_config} / {@code clan_points_*} tables are left
 * as they were (T-980 copies them; they are never read here). With no answer from the hub, the ladder is the standard one with no
 * thresholds and nobody is flagged.
 */
@BService
public class ClanPointsRepository {
    // The standard Jagex clan rank titles, lowest to highest — confirmed against the wiki's own Clan
    // Chat rank table (runescape.wiki/w/RuneScape:Clan_Chat#Ranks), which is also where TrackingIconCatalog's
    // rank icons (images/ranks/<order>.png) come from. 12 tiers; index = rank_order.
    public static final List<String> STANDARD_RANK_NAMES = List.of(
            "Recruit", "Corporal", "Sergeant", "Lieutenant", "Captain", "General",
            "Admin", "Organiser", "Coordinator", "Overseer", "Deputy Owner", "Owner");

    private static final Duration KEEP = Duration.ofMinutes(1);

    private final RuneClansHubClient hub;

    public ClanPointsRepository(RuneClansHubClient hub) {
        this.hub = hub;
    }

    /** One tier of the ladder; {@code id} is its {@code rankOrder} (the hub's rank id). */
    public record RankConfigRow(long id, long guildId, String rankName, int rankOrder, long pointThreshold) {}

    public record PointsSettings(long dailyMembershipPoints, long citadelVisitPoints, long citadelCapPoints) {}

    public record MemberPointsRow(String rsn, long totalPoints, boolean promotionNeeded, LocalDate promotionNeededSince) {}

    /** Ordered lowest to highest. */
    public List<RankConfigRow> getRanksOrdered(long guildId) {
        DataObject json = read(guildId);
        List<RankConfigRow> out = new ArrayList<>();
        if (json != null && json.hasKey("ranks")) {
            DataArray ranks = json.getArray("ranks");
            for (int i = 0; i < ranks.length(); i++) {
                DataObject r = ranks.getObject(i);
                int order = r.getInt("order", i);
                out.add(new RankConfigRow(order, guildId, r.getString("name", STANDARD_RANK_NAMES.get(Math.min(order, STANDARD_RANK_NAMES.size() - 1))), order,
                        r.getLong("threshold", 0)));
            }
        }
        if (out.size() == STANDARD_RANK_NAMES.size()) return out;
        List<RankConfigRow> standard = new ArrayList<>();
        for (int i = 0; i < STANDARD_RANK_NAMES.size(); i++) standard.add(new RankConfigRow(i, guildId, STANDARD_RANK_NAMES.get(i), i, 0));
        return standard;
    }

    public PointsSettings getSettings(long guildId) {
        DataObject json = read(guildId);
        if (json == null) return new PointsSettings(0, 0, 0);
        return new PointsSettings(json.getLong("dailyMembershipPoints", 0), json.getLong("citadelVisitPoints", 0), json.getLong("citadelCapPoints", 0));
    }

    /**
     * Saves the three amounts and the thresholds given ({@code rankOrder -> threshold}, any number) for {@code actorId}. False when the
     * hub didn't take it (no clan site for the server, the hub down, or refused).
     */
    public boolean save(long guildId, long actorId, long daily, long visit, long cap, java.util.Map<Long, Long> thresholds) {
        DataArray ranks = DataArray.empty();
        thresholds.forEach((order, threshold) -> ranks.add(DataObject.empty().put("id", Long.toString(order)).put("threshold", threshold)));
        DataObject body = DataObject.empty().put("actorId", Long.toString(actorId)).put("dailyMembershipPoints", daily).put("citadelVisitPoints", visit)
                .put("citadelCapPoints", cap).put("ranks", ranks);
        RuneClansHubClient.Answer a = hub.call("PUT", path(guildId), body.toString());
        hub.forget(tag(guildId));
        return a != null && a.ok();
    }

    public MemberPointsRow getMemberPoints(long guildId, String rsn) {
        for (MemberPointsRow row : members(guildId)) if (row.rsn().equalsIgnoreCase(rsn)) return row;
        return new MemberPointsRow(rsn, 0, false, null);
    }

    /** Every member currently flagged as needing a promotion — what the daily Clan Report is built from. */
    public List<MemberPointsRow> getAllNeedingPromotion(long guildId) {
        return members(guildId).stream().filter(MemberPointsRow::promotionNeeded).toList();
    }

    /** An admin promoted {@code rsn} in game: the flag clears until the hub's next daily check. False when the hub didn't take it. */
    public boolean markPromoted(long guildId, String rsn) {
        RuneClansHubClient.Answer a = hub.call("POST", path(guildId) + "/promoted", DataObject.empty().put("rsn", rsn).toString());
        hub.forget(tag(guildId));
        return a != null && a.ok();
    }

    private List<MemberPointsRow> members(long guildId) {
        DataObject json = read(guildId);
        List<MemberPointsRow> out = new ArrayList<>();
        if (json == null || !json.hasKey("members")) return out;
        DataArray members = json.getArray("members");
        for (int i = 0; i < members.length(); i++) {
            DataObject m = members.getObject(i);
            String since = m.isNull("promotionNeededSince") ? null : m.getString("promotionNeededSince", null);
            out.add(new MemberPointsRow(m.getString("rsn", ""), m.getLong("totalPoints", 0), m.getBoolean("promotionNeeded", false),
                    since == null ? null : LocalDate.parse(since)));
        }
        return out;
    }

    private DataObject read(long guildId) {
        RuneClansHubClient.Read read = hub == null ? null : hub.read(path(guildId), KEEP, tag(guildId));
        return read == null || read.answer() == null || !read.answer().ok() ? null : read.answer().json();
    }

    private static String path(long guildId) {
        return "/v1/guilds/" + guildId + "/points";
    }

    private static String tag(long guildId) {
        return "points:" + guildId;
    }
}
