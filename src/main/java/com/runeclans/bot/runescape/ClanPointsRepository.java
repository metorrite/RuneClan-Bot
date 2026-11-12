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
 * ({@code PUT .../points}, {@code POST .../points/promoted}), and the award history ({@code .../points/awards}, {@code .../points/earned})
 * for the site members list, the recaps and the leaderboard race. The bot's old {@code clan_rank_config} / {@code clan_points_*} tables are
 * left as they were for T-980 to copy; nothing in the bot reads them. Reads are tried once (no retry sleeps: Discord and the website wait on
 * them), and a server the hub has no clan for is remembered for a minute so it doesn't cost a call each time. With no answer from the hub,
 * the ladder is the standard one with no thresholds and nobody is flagged; {@link #known} tells "unknown" from "not set".
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
    /** Servers the hub answered 404/409 for (no site, or a site with no clan), until when. */
    private final java.util.Map<Long, java.time.Instant> noClan = new java.util.concurrent.ConcurrentHashMap<>();

    public ClanPointsRepository(RuneClansHubClient hub) {
        this.hub = hub;
    }

    /** One tier of the ladder; {@code id} is its {@code rankOrder} (the hub's rank id). */
    public record RankConfigRow(long id, long guildId, String rankName, int rankOrder, long pointThreshold) {}

    public record PointsSettings(long dailyMembershipPoints, long citadelVisitPoints, long citadelCapPoints) {}

    public record MemberPointsRow(String rsn, long totalPoints, boolean promotionNeeded, LocalDate promotionNeededSince) {}

    /** The settings, ladder and members in one answer; null when the hub has none to give (down, or no clan for the server). */
    public DataObject known(long guildId) {
        return read(guildId);
    }

    /** Ordered lowest to highest. */
    public List<RankConfigRow> getRanksOrdered(long guildId) {
        return ranksOf(guildId, read(guildId));
    }

    /** The ladder in a points answer (the read's or a save's), or the standard one. */
    public static List<RankConfigRow> ranksOf(long guildId, DataObject json) {
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
        return settingsOf(read(guildId));
    }

    /** The three amounts in a points answer, 0 when there is none. */
    public static PointsSettings settingsOf(DataObject json) {
        if (json == null) return new PointsSettings(0, 0, 0);
        return new PointsSettings(json.getLong("dailyMembershipPoints", 0), json.getLong("citadelVisitPoints", 0), json.getLong("citadelCapPoints", 0));
    }

    /**
     * Saves the amounts given (a null amount is left as the hub has it, so a thresholds-only save never touches them) and the thresholds
     * given ({@code rankOrder -> threshold}, any number) for {@code actorId}. The hub's answer after the save (settings and ladder), or null
     * when the hub didn't take it (no clan site for the server, the hub down, or refused). Tried once, within
     * {@link RuneClansHubClient#WEB_BUDGET} (B-36): the dashboard and Discord wait on it, so a slow hub is "didn't save, try again" well before
     * the website gives up at 8 seconds, never a save still running behind its back. Saving again is safe: the same values set the same state.
     */
    public DataObject save(long guildId, long actorId, Long daily, Long visit, Long cap, java.util.Map<Long, Long> thresholds) {
        DataArray ranks = DataArray.empty();
        thresholds.forEach((order, threshold) -> ranks.add(DataObject.empty().put("id", Long.toString(order)).put("threshold", threshold)));
        DataObject body = DataObject.empty().put("actorId", Long.toString(actorId)).put("ranks", ranks);
        if (daily != null) body.put("dailyMembershipPoints", daily);
        if (visit != null) body.put("citadelVisitPoints", visit);
        if (cap != null) body.put("citadelCapPoints", cap);
        RuneClansHubClient.Answer a = RuneClansHubClient.withinBudget(RuneClansHubClient.WEB_BUDGET, () -> hub.call("PUT", path(guildId), body.toString()));
        hub.forget(tag(guildId));
        if (a != null && a.ok()) noClan.remove(guildId);
        return a != null && a.ok() ? a.json() : null;
    }

    /** One award: what for, how many points, and the UTC day it is for (a Citadel award: its week's Wednesday). */
    public record Award(String type, long points, LocalDate awardedFor) {}

    /** Points earned in a window by one member (and, with {@code byDay}, on one day; else {@code day} is null). */
    public record Earned(String rsn, LocalDate day, long points) {}

    /** {@code rsn}'s awards, newest first (at most {@code limit}, up to 200); empty when the hub can't say. */
    public List<Award> awards(long guildId, String rsn, int limit) {
        DataObject json = readOnce(path(guildId) + "/awards?rsn=" + encode(rsn) + "&limit=" + Math.max(1, Math.min(limit, 200)), guildId);
        List<Award> out = new ArrayList<>();
        if (json == null || !json.hasKey("awards")) return out;
        DataArray rows = json.getArray("awards");
        for (int i = 0; i < rows.length(); i++) {
            DataObject a = rows.getObject(i);
            out.add(new Award(a.getString("type", ""), a.getLong("points", 0), LocalDate.parse(a.getString("awardedFor"))));
        }
        return out;
    }

    /**
     * Points awarded for the UTC days in {@code [from, to)} ({@code to} null: up to today), per member, or per member and day; one member
     * with {@code rsn}. Empty when the hub can't say.
     */
    public List<Earned> earned(long guildId, LocalDate from, LocalDate to, String rsn, boolean byDay) {
        return earnedIfKnown(guildId, from, to, rsn, byDay).orElse(List.of());
    }

    /**
     * {@link #earned}, but empty (not an empty list) when the hub didn't answer, so a caller that keeps what it reads (a season's close)
     * can try again later instead of keeping zeros. A server the hub has no clan for has earned nothing: an empty list.
     */
    public java.util.Optional<List<Earned>> earnedIfKnown(long guildId, LocalDate from, LocalDate to, String rsn, boolean byDay) {
        StringBuilder q = new StringBuilder(path(guildId)).append("/earned?from=").append(from);
        if (to != null) q.append("&to=").append(to);
        if (rsn != null) q.append("&rsn=").append(encode(rsn));
        if (byDay) q.append("&by=day");
        java.util.Optional<DataObject> got = readKnown(q.toString(), guildId);
        if (got.isEmpty()) return java.util.Optional.empty();
        DataObject json = got.get();
        List<Earned> out = new ArrayList<>();
        if (!json.hasKey("earned")) return java.util.Optional.of(out);
        DataArray rows = json.getArray("earned");
        for (int i = 0; i < rows.length(); i++) {
            DataObject e = rows.getObject(i);
            String day = e.getString("day", null);
            out.add(new Earned(e.getString("rsn", ""), day == null ? null : LocalDate.parse(day), e.getLong("points", 0)));
        }
        return java.util.Optional.of(out);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** The name key members are matched by (case, spaces and underscores folded as the hub does). */
    public static String key(String rsn) {
        return rsn == null ? "" : rsn.strip().replace('_', ' ').replace('\u00A0', ' ').replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /** A rank's place on the standard ladder (the hub's ladder is always it), or -1 for a name that isn't on it. */
    public static int standardOrder(String rank) {
        if (rank == null) return -1;
        for (int i = 0; i < STANDARD_RANK_NAMES.size(); i++) if (STANDARD_RANK_NAMES.get(i).equalsIgnoreCase(rank.strip())) return i;
        return -1;
    }

    /** Every current member's total and flag by {@link #key}; empty when the hub can't say. */
    public java.util.Map<String, MemberPointsRow> totals(long guildId) {
        java.util.Map<String, MemberPointsRow> out = new java.util.HashMap<>();
        for (MemberPointsRow row : members(guildId)) out.put(key(row.rsn()), row);
        return out;
    }

    public MemberPointsRow getMemberPoints(long guildId, String rsn) {
        for (MemberPointsRow row : members(guildId)) if (row.rsn().equalsIgnoreCase(rsn)) return row;
        return new MemberPointsRow(rsn, 0, false, null);
    }

    /** Every member currently flagged as needing a promotion; empty when the hub can't say. */
    public List<MemberPointsRow> getAllNeedingPromotion(long guildId) {
        return needingPromotionIfKnown(guildId).orElse(List.of());
    }

    /**
     * {@link #getAllNeedingPromotion}, but empty (not an empty list) when the hub didn't answer (B-10), so the daily Clan Report can try that
     * server again later and the dashboard can say "didn't load" instead of "nobody is due". A server the hub has no clan for has nobody: an
     * empty list.
     */
    public java.util.Optional<List<MemberPointsRow>> needingPromotionIfKnown(long guildId) {
        java.util.Optional<DataObject> got = readKnown(path(guildId), guildId);
        return got.map(json -> membersOf(json == NO_CLAN ? null : json).stream().filter(MemberPointsRow::promotionNeeded).toList());
    }

    /** An admin promoted {@code rsn} in game: the flag clears until the hub's next daily check. False when the hub didn't take it. */
    public boolean markPromoted(long guildId, String rsn) {
        RuneClansHubClient.Answer a = hub.call("POST", path(guildId) + "/promoted", DataObject.empty().put("rsn", rsn).toString());
        hub.forget(tag(guildId));
        return a != null && a.ok();
    }

    private List<MemberPointsRow> members(long guildId) {
        return membersOf(read(guildId));
    }

    private static List<MemberPointsRow> membersOf(DataObject json) {
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
        return readOnce(path(guildId), guildId);
    }

    /** A cached {@code GET}, tried once; null when there is no answer to use (no clan for the server, or the hub didn't answer). */
    private DataObject readOnce(String path, long guildId) {
        java.util.Optional<DataObject> got = readKnown(path, guildId);
        return got.isEmpty() || got.get() == NO_CLAN ? null : got.get();
    }

    /** What {@link #readKnown} answers for a server the hub has no clan for (compared by identity). */
    private static final DataObject NO_CLAN = DataObject.empty();

    /** After the hub doesn't answer, reads give up at once for this long, so one web request doesn't wait on it several times in a row. */
    static final Duration QUIET = Duration.ofSeconds(30);
    private volatile java.time.Instant hubQuietUntil = java.time.Instant.MIN;

    /**
     * A cached {@code GET}, tried once: the answer, {@link #NO_CLAN} when the hub says the server has no clan (404/409, remembered for a
     * minute), or empty when the hub didn't answer.
     */
    private java.util.Optional<DataObject> readKnown(String path, long guildId) {
        if (hub == null) return java.util.Optional.empty();
        java.time.Instant until = noClan.get(guildId);
        if (until != null && java.time.Instant.now().isBefore(until)) return java.util.Optional.of(NO_CLAN);
        if (java.time.Instant.now().isBefore(hubQuietUntil)) return java.util.Optional.empty();
        RuneClansHubClient.Read read = RuneClansHubClient.withoutRetries(() -> hub.read(path, KEEP, tag(guildId)));
        if (read != null && read.answer() != null && (read.answer().status() == 404 || read.answer().status() == 409)) {
            noClan.put(guildId, java.time.Instant.now().plus(KEEP));
            return java.util.Optional.of(NO_CLAN);
        }
        if (read == null || read.answer() == null || !read.answer().ok() || read.answer().json() == null) {
            if (read == null || read.answer() == null || read.answer().status() >= 500 || read.answer().status() == 429) hubQuietUntil = java.time.Instant.now().plus(QUIET);
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(read.answer().json());
    }

    private static String path(long guildId) {
        return "/v1/guilds/" + guildId + "/points";
    }

    private static String tag(long guildId) {
        return "points:" + guildId;
    }
}
