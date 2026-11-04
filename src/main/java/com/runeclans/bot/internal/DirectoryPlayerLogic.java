package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ProfileRules;
import com.runeclans.bot.internal.SiteStatsRepository.MemberRow;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.runeclans.bot.runescape.RuneScapeSkillCatalog;
import com.runeclans.bot.runescape.SkillValue;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules behind a player's public profile ({@code GET /internal/directory/players/{rsn}}, {@link DirectoryPlayerRoutes}), apart from the
 * database so they can be tested on their own. A clan is included only while the public directory lists it ({@link ProfileRules#listing} is
 * {@code LISTED}: public, verified, not suspended) and the player's member page on that clan's site is public ({@link SiteApi#visibleMember}:
 * a current member who hasn't hidden themselves from the rankings). Nothing here is new: it is what those clan pages already show.
 */
final class DirectoryPlayerLogic {
    /** RuneScape names: 1 to 12 letters, digits, spaces, underscores or hyphens. */
    private static final Pattern RSN = Pattern.compile("[A-Za-z0-9 _-]{1,12}");
    /** As many snapshots as a clan site's member page reads, so the month's gain reaches back as far. */
    static final int SNAPSHOTS = 2000;
    /** Days of total XP history the profile's charts draw (one point per UTC day). */
    static final int HISTORY_DAYS = 90;
    /** Players on the "top players" list of the website's Find a player page. */
    static final int TOP_PLAYERS = 10;
    /** Clans read for the top players list, as many as the clan leaderboards read. */
    static final int TOP_PLAYER_CLANS = 100;

    private DirectoryPlayerLogic() {
    }

    /** What the rules need to read; the route reads it from the database, tests from plain lists. */
    interface Source {
        /** The servers whose roster has this name as an active member. */
        List<Long> guildsWithActiveMember(String rsn);

        Optional<ClanProfile> profile(long guildId);

        /** The server's in-game clan name ({@code ClanProfileService#clanNameOf}), which a listed profile must be verified for. */
        String clanName(long guildId);

        /** {@link SiteApi#visibleMember}: the roster row when the member page is public, else null. */
        MemberRow visibleMember(long guildId, String rsn);

        /** {@link SiteApi#hidesFromRankingsAnywhere}: the player hid from the rankings on some server, current member or not. */
        boolean hiddenAnywhere(String rsn);

        /** {@link com.runeclans.bot.runescape.RosterPollScheduler#tracked}: the name is linked (RuneLink in the hub, or {@code /rsn}). */
        boolean linked(String rsn);

        /** The highest rank order of the server's clan ranks. */
        int maxRankOrder(long guildId);

        List<StatsSnapshotRow> snapshots(String rsn);

        List<SkillValue> skills(long snapshotId);
    }

    /** What the top players list reads: every shared clan profile, and each clan's members' XP gained since a moment. */
    interface TopSource {
        List<ClanProfile> sharedClans();

        String clanName(long guildId);

        /** {@link SiteStatsRepository#xpGains}: each active member's XP gained since {@code since}. */
        List<SiteStatsRepository.Gain> gains(long guildId, OffsetDateTime since);

        /** {@link SiteApi#visibleMember}: the roster row when the member page is public (not hidden from the rankings), else null. */
        MemberRow visibleMember(long guildId, String rsn);
    }

    record Membership(ClanProfile clan, MemberRow row, int maxRankOrder) {}

    /** The name trimmed, or null when it isn't a RuneScape name (the route answers 400). */
    static String cleanRsn(String raw) {
        if (raw == null) return null;
        String rsn = raw.strip();
        return RSN.matcher(rsn).matches() ? rsn : null;
    }

    /** The clans that may show this player, the most recently polled first (then by name). */
    static List<Membership> memberships(String rsn, Source source) {
        return memberships(rsn, source, source.guildsWithActiveMember(rsn));
    }

    private static List<Membership> memberships(String rsn, Source source, List<Long> guilds) {
        List<Membership> out = new ArrayList<>();
        for (long guildId : guilds) {
            ClanProfile profile = source.profile(guildId).orElse(null);
            if (profile == null || profile.slug() == null || profile.slug().isBlank()) continue;
            if (ProfileRules.listing(profile, source.clanName(guildId)) != ProfileRules.Listing.LISTED) continue;
            MemberRow row = source.visibleMember(guildId, rsn);
            if (row == null) continue;
            out.add(new Membership(profile, row, source.maxRankOrder(guildId)));
        }
        out.sort(Comparator.comparing((Membership m) -> m.row().lastPolled(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(m -> m.clan().displayName() == null ? "" : m.clan().displayName(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /**
     * The whole answer, or null (the route answers 404, as for a name nobody knows). A player no listed clan shows still gets their stats with no
     * clans (Drake 2026-11-02: every linked player is tracked, clan or not), unless they hid from the rankings on some server (even one they
     * left) or the bot never polled them. A name that is NOT linked and is only on the roster of a clan the directory doesn't list (private,
     * unlisted, suspended, unverified) or that hides them answers null, so nothing a clan keeps to itself shows here; a linked player's own
     * stats choice is the hub's, which the website applies, and {@code clans: []} says nothing about the clan.
     */
    static DataObject answer(String rsn, Source source, OffsetDateTime now) {
        List<Long> guilds = source.guildsWithActiveMember(rsn);
        List<Membership> memberships = memberships(rsn, source, guilds);
        if (memberships.isEmpty()) {
            if (source.hiddenAnywhere(rsn)) return null;
            if (!guilds.isEmpty() && !source.linked(rsn)) return null;
            DataObject stats = stats(rsn, source, now);
            return stats == null ? null : DataObject.empty().put("rsn", rsn).put("clans", DataArray.empty()).put("stats", stats);
        }
        String name = memberships.getFirst().row().rsn();
        DataArray clans = DataArray.empty();
        for (Membership m : memberships) {
            MemberRow row = m.row();
            clans.add(DataObject.empty().put("slug", m.clan().slug()).put("name", m.clan().displayName()).put("rank", row.clanRank())
                    .put("rankOrder", row.rankOrder()).put("maxRankOrder", m.maxRankOrder())
                    .put("firstSeen", row.firstSeen() == null ? null : row.firstSeen().toString())
                    .put("joined", row.clanJoinedAt() != null ? row.clanJoinedAt().toString() : row.firstSeen() == null ? null : row.firstSeen().toLocalDate().toString())
                    .put("joinedExact", row.clanJoinedAt() != null)
                    .put("lastPolled", row.lastPolled() == null ? null : row.lastPolled().toString()));
        }
        return DataObject.empty().put("rsn", name).put("clans", clans).put("stats", stats(name, source, now));
    }

    /** The newest snapshot's totals and skills, with XP gained over the last day, week and month ({@link SiteApi#gainSince}); null with none. */
    static DataObject stats(String rsn, Source source, OffsetDateTime now) {
        List<StatsSnapshotRow> snapshots = new ArrayList<>(source.snapshots(rsn));
        if (snapshots.isEmpty()) return null;
        snapshots.sort(Comparator.comparing(StatsSnapshotRow::snapshotAt).reversed());
        StatsSnapshotRow latest = snapshots.getFirst();
        DataArray skills = DataArray.empty();
        List<SkillValue> latestSkills = source.skills(latest.snapshotId());
        for (SkillValue skill : latestSkills) {
            skills.add(DataObject.empty().put("id", skill.skillId()).put("name", RuneScapeSkillCatalog.nameFor(skill.skillId())).put("level", skill.level())
                    .put("xp", skill.xp()).put("rank", skill.rank()));
        }
        return DataObject.empty().put("snapshotAt", latest.snapshotAt().toInstant().toString()).put("totalLevel", latest.totalLevel())
                .put("totalXp", latest.totalXp()).put("combatLevel", latest.combatLevel()).put("questsComplete", latest.questsComplete())
                .put("skills", skills)
                .put("gains", DataObject.empty()
                        .put("day", SiteApi.gainSince(snapshots, now.minus(Duration.ofDays(1))))
                        .put("week", SiteApi.gainSince(snapshots, now.minus(Duration.ofDays(7))))
                        .put("month", SiteApi.gainSince(snapshots, now.minus(Duration.ofDays(30)))))
                .put("history", history(snapshots, now))
                .put("skillGains", DataObject.empty()
                        .put("week", skillGains(snapshots, latestSkills, now.minus(Duration.ofDays(7)), source))
                        .put("month", skillGains(snapshots, latestSkills, now.minus(Duration.ofDays(30)), source)));
    }

    /** One point per UTC day over the last {@link #HISTORY_DAYS} days (that day's newest snapshot), oldest first: {@code [{date, totalXp, totalLevel}]}. */
    static DataArray history(List<StatsSnapshotRow> newestFirst, OffsetDateTime now) {
        LocalDate first = now.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().minusDays(HISTORY_DAYS - 1L);
        Map<LocalDate, StatsSnapshotRow> byDay = new LinkedHashMap<>();
        for (StatsSnapshotRow row : newestFirst) {
            LocalDate day = row.snapshotAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
            if (day.isBefore(first)) break;
            byDay.putIfAbsent(day, row);
        }
        List<LocalDate> days = new ArrayList<>(byDay.keySet());
        days.sort(null);
        DataArray out = DataArray.empty();
        for (LocalDate day : days) {
            StatsSnapshotRow row = byDay.get(day);
            out.add(DataObject.empty().put("date", day.toString()).put("totalXp", row.totalXp()).put("totalLevel", row.totalLevel()));
        }
        return out;
    }

    /**
     * XP gained in each skill since {@code since}, biggest first, skills with no gain left out: {@code [{id, name, xp}]}. The baseline is the
     * newest snapshot at or before {@code since}, else the oldest there is (as {@link SiteApi#gainSince}); one snapshot alone gains nothing.
     */
    static DataArray skillGains(List<StatsSnapshotRow> newestFirst, List<SkillValue> latestSkills, OffsetDateTime since, Source source) {
        DataArray out = DataArray.empty();
        if (newestFirst.size() < 2) return out;
        StatsSnapshotRow baseline = null;
        for (StatsSnapshotRow row : newestFirst) {
            if (!row.snapshotAt().isAfter(since)) {
                baseline = row;
                break;
            }
        }
        if (baseline == null) baseline = newestFirst.getLast();
        if (baseline.snapshotId() == newestFirst.getFirst().snapshotId()) return out;
        Map<Integer, Long> before = new HashMap<>();
        for (SkillValue skill : source.skills(baseline.snapshotId())) before.put(skill.skillId(), skill.xp());
        List<long[]> gains = new ArrayList<>();
        for (SkillValue skill : latestSkills) {
            Long was = before.get(skill.skillId());
            if (was != null && skill.xp() > was) gains.add(new long[]{skill.skillId(), skill.xp() - was});
        }
        gains.sort(Comparator.comparingLong((long[] g) -> g[1]).reversed().thenComparingLong(g -> g[0]));
        for (long[] g : gains) {
            out.add(DataObject.empty().put("id", (int) g[0]).put("name", RuneScapeSkillCatalog.nameFor((int) g[0])).put("xp", g[1]));
        }
        return out;
    }

    /**
     * The players who gained the most XP since {@code since}, across the clans on the public clan leaderboards ({@link ProfileRules#listing}
     * {@code LISTED} and opted in): {@code {since, entries: [{rank, rsn, xpGained, clan: {slug, name}}]}}. Only members whose member page is
     * public and who haven't hidden themselves from the rankings ({@link TopSource#visibleMember}), so nothing here is new: each clan's own
     * leaderboard already shows it. A player in two clans is counted once, with the bigger gain.
     */
    static DataObject topPlayers(TopSource source, OffsetDateTime since, int limit) {
        record Candidate(ClanProfile clan, String rsn, long xp) {}
        List<Candidate> candidates = new ArrayList<>();
        int clans = 0;
        for (ClanProfile p : source.sharedClans()) {
            if (clans >= TOP_PLAYER_CLANS) break;
            if (!p.leaderboardOptIn() || p.slug() == null || p.slug().isBlank()) continue;
            if (ProfileRules.listing(p, source.clanName(p.guildId())) != ProfileRules.Listing.LISTED) continue;
            clans++;
            for (SiteStatsRepository.Gain g : source.gains(p.guildId(), since)) {
                if (g.xp() > 0 && g.rsn() != null) candidates.add(new Candidate(p, g.rsn(), g.xp()));
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::xp).reversed().thenComparing(Candidate::rsn, String.CASE_INSENSITIVE_ORDER));
        DataArray entries = DataArray.empty();
        Set<String> seen = new HashSet<>();
        for (Candidate c : candidates) {
            if (entries.length() >= limit) break;
            String key = c.rsn().toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            MemberRow row = source.visibleMember(c.clan().guildId(), c.rsn());
            if (row == null) continue;
            seen.add(key);
            entries.add(DataObject.empty().put("rank", entries.length() + 1).put("rsn", row.rsn()).put("xpGained", c.xp())
                    .put("clan", DataObject.empty().put("slug", c.clan().slug()).put("name", c.clan().displayName())));
        }
        return DataObject.empty().put("since", since.toInstant().toString()).put("entries", entries);
    }
}
