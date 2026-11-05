package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.clansite.SiteRoles;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.CockpitRules.CapWeek;
import com.runeclans.bot.internal.CockpitRules.Caps;
import com.runeclans.bot.internal.CockpitRules.Joiner;
import com.runeclans.bot.internal.CockpitRules.Leaver;
import com.runeclans.bot.internal.CockpitRules.Mark;
import com.runeclans.bot.internal.CockpitRules.Member;
import com.runeclans.bot.internal.CockpitRules.Missed;
import com.runeclans.bot.internal.CockpitRules.Quiet;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The site admin's Clan health page (T-593, the "officer cockpit"), reached through {@link ClanSiteAdminApi} (and so for bot-less sites too,
 * through {@link BotlessAdminApi}), which has already checked the site role each route needs:
 * <ul>
 *   <li>{@code GET clan-site/cockpit} (site admins): members gone quiet (no XP on the clan Hiscores for the site's threshold), missed Citadel
 *   caps (2 of the last 4 finished weeks, weeks with no cap data at all left out; {@code available: false} with a {@code reason} and no rows
 *  ), new members not linked in the server, and leaves of the
 *   last 7 days, each row with the staff's "done" mark when there is one. {@code canChangeSettings} says whether the asker may change the
 *   threshold.</li>
 *   <li>{@code PUT clan-site/cockpit/settings} {@code {quietDays}} (the site's owner): 7 to 90 days; answers the page.</li>
 *   <li>{@code POST clan-site/cockpit/marks} {@code {kind, rsn, done, name?}} (site admins): marks a row done or clears the mark; {@code name}
 *   is how the website shows the asker (used where the bot can't see them). Answers {@code {mark}} (null when cleared).</li>
 *   <li>{@code clan-site/cockpit/ranks...} (T-594): the rank-up advisor ({@link SiteRankAdvisor}); its rules are the owner's.</li>
 * </ul>
 * Read-only on Discord: a linked member's Discord name comes from the bot's member cache only, never a request.
 */
@BService
public class SiteCockpitAdmin {
    static final String PATH = "cockpit";
    static final int MAX_NAME = 64;

    private final SiteCockpitStore store;
    private final SiteStatsRepository stats;
    private final AdminToolsStore activity;
    private final SiteRoleGuard siteRoles;
    private final SiteRankAdvisor ranks;
    /** Marks per person: a burst of 10 (working down a list), then 2 a second. */
    private final RateLimiter marks = new RateLimiter(10, 500);

    public SiteCockpitAdmin(SiteCockpitStore store, SiteStatsRepository stats, AdminToolsStore activity, SiteRoleGuard siteRoles, SiteRankAdvisor ranks) {
        this.store = store;
        this.ranks = ranks;
        this.stats = stats;
        this.activity = activity;
        this.siteRoles = siteRoles;
    }

    /** The site role a cockpit route needs: changing the threshold is the owner's (Drake's planning answer); the rest is any site admin. */
    static SiteRoles.Action actionFor(String method, String[] parts) {
        if (parts.length >= 3 && parts[2].equals(SiteRankAdvisor.PATH)) return SiteRankAdvisor.actionFor(method, parts);
        return parts.length == 3 && parts[2].equals("settings") && !method.equals("GET") ? SiteRoles.Action.OWN : SiteRoles.Action.SETTINGS;
    }

    DataObject route(SiteCaller c, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 2 || !parts[1].equals(PATH)) return null;
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "GET");
            return view(c, OffsetDateTime.now(ZoneOffset.UTC));
        }
        if (parts[2].equals(SiteRankAdvisor.PATH)) return ranks.route(c, method, parts, body);
        if (parts.length != 3) return null;
        return switch (parts[2]) {
            case "settings" -> {
                AdminRouteExtension.requireMethod(method, "PUT");
                yield saveSettings(c, body.get());
            }
            case "marks" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield mark(c, body.get());
            }
            default -> null;
        };
    }

    /** Drops the page's settings and marks with the server's deleted website. */
    void forget(long guildId) {
        store.forget(guildId);
        ranks.forget(guildId);
    }

    // ---------- the page ----------

    DataObject view(SiteCaller c, OffsetDateTime now) {
        long guildId = c.guildId();
        int quietDays = CockpitRules.quietDays(store.quietDays(guildId));
        Map<String, Long> links = store.links(guildId);
        List<Member> members = store.members(guildId, links);
        Map<String, Mark> marks = CockpitRules.byKey(store.marks(guildId));
        OffsetDateTime eventsFrom = now.minusDays(Math.max(CockpitRules.NEW_JOIN_DAYS, CockpitRules.LEFT_DAYS));
        List<CockpitRules.RosterEvent> events = store.rosterEvents(guildId, eventsFrom);

        DataArray quiet = DataArray.empty();
        for (Quiet q : CockpitRules.quiet(members, quietDays)) {
            quiet.add(person(c, q.member().rsn(), q.member().discordId()).put("rank", q.member().rank()).put("lastXpAt", iso(q.lastXpAt()))
                    .put("days", q.days()).put("atLeast", q.atLeast())
                    .put("mark", markJson(CockpitRules.activeMark(marks, "quiet", q.member().key(), q.lastXpAt(), now))));
        }

        LocalDate thisWeek = RecapPeriod.citadelWeekStart(now.toLocalDate());
        List<CapWeek> grid = stats.citadelGrid(guildId, CockpitRules.CAP_WEEKS + 1).stream()
                .map(w -> new CapWeek(w.rsn(), w.weekStart(), w.capped())).toList();
        DataObject capsJson = capsJson(c, CockpitRules.caps(members, grid, activity.lastActivity(guildId), thisWeek), marks, now);

        DataArray unlinked = DataArray.empty();
        if (!c.botless()) {
            for (Joiner j : CockpitRules.unlinked(members, events, now)) {
                unlinked.add(DataObject.empty().put("rsn", j.member().rsn()).put("rank", j.member().rank()).put("joinedAt", iso(j.joinedAt()))
                        .put("mark", markJson(CockpitRules.activeMark(marks, "unlinked", j.member().key(), j.joinedAt(), now))));
            }
        }

        DataArray left = DataArray.empty();
        List<CockpitRules.Rename> renames = store.renames(guildId, now.minusDays(CockpitRules.LEFT_DAYS + 1));
        for (Leaver l : CockpitRules.left(members, events, renames, links, now)) {
            left.add(person(c, l.rsn(), l.discordId()).put("leftAt", iso(l.leftAt())).put("maybeRenamedTo", l.maybeRenamedTo())
                    .put("mark", markJson(CockpitRules.activeMark(marks, "left", CockpitRules.keyOf(l.rsn()), l.leftAt(), now))));
        }

        OffsetDateTime checkedAt = CockpitRules.rosterCheckedAt(members);
        return DataObject.empty()
                .put("quietDays", quietDays)
                .put("limits", DataObject.empty().put("minQuietDays", CockpitRules.MIN_QUIET_DAYS).put("maxQuietDays", CockpitRules.MAX_QUIET_DAYS)
                        .put("capWeeks", CockpitRules.CAP_WEEKS).put("capMisses", CockpitRules.CAP_MISSES).put("newJoinDays", CockpitRules.NEW_JOIN_DAYS)
                        .put("leftDays", CockpitRules.LEFT_DAYS).put("markDays", CockpitRules.MARK_DAYS))
                .put("canChangeSettings", c.may(siteRoles, SiteRoles.Action.OWN))
                .put("botPresent", !c.botless())
                .put("roster", DataObject.empty().put("size", members.size()).put("checkedAt", iso(checkedAt))
                        .put("stale", CockpitRules.rosterStale(checkedAt, now)).put("xpWatchStarted", iso(CockpitRules.watchStarted(members))))
                .put("quiet", quiet)
                .put("caps", capsJson)
                .put("unlinked", DataObject.empty().put("available", !c.botless()).put("rows", unlinked))
                .put("left", left)
                .put("generatedAt", now.toString());
    }

    /** The missed-caps section: {@code available} true, the counted weeks, members with no log, and who missed caps. */
    static DataObject capsJson(SiteCaller c, Caps caps, Map<String, Mark> marks, OffsetDateTime now) {
        DataArray capRows = DataArray.empty();
        for (Missed m : caps.rows()) {
            DataArray weeks = DataArray.empty();
            for (Boolean w : m.weeks()) weeks.add(w);
            capRows.add(person(c, m.member().rsn(), m.member().discordId()).put("rank", m.member().rank()).put("weeks", weeks).put("missed", m.missed())
                    .put("mark", markJson(CockpitRules.activeMark(marks, "caps", m.member().key(), null, now))));
        }
        DataArray capWeeks = DataArray.empty();
        for (CockpitRules.Week w : caps.weeks()) capWeeks.add(DataObject.empty().put("start", w.start().toString()).put("hasData", w.hasData()));
        return DataObject.empty().put("available", true).put("reason", null).put("weeks", capWeeks).put("noLog", caps.noLog()).put("rows", capRows);
    }

    /** A row's name and, where the bot is in the server and the name is linked, the Discord account (its name from the cache only). */
    private static DataObject person(SiteCaller c, String rsn, Long discordId) {
        DataObject row = DataObject.empty().put("rsn", rsn);
        if (discordId == null || c.botless()) return row.put("discordId", null).put("discordName", null);
        net.dv8tion.jda.api.entities.Member m = c.guild().getMemberById(discordId);
        return row.put("discordId", Long.toString(discordId)).put("discordName", m == null ? null : m.getEffectiveName());
    }

    static DataObject markJson(Mark m) {
        if (m == null) return null;
        return DataObject.empty().put("by", Long.toString(m.by())).put("byName", m.byName()).put("at", iso(m.at()));
    }

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    // ---------- writes ----------

    DataObject saveSettings(SiteCaller c, DataObject body) {
        if (!body.hasKey("quietDays") || !body.isType("quietDays", DataType.INT)) throw refused(c, "Say how many days without XP count as gone quiet.");
        int days = body.getInt("quietDays");
        if (days < CockpitRules.MIN_QUIET_DAYS || days > CockpitRules.MAX_QUIET_DAYS) {
            throw refused(c, "Pick " + CockpitRules.MIN_QUIET_DAYS + " to " + CockpitRules.MAX_QUIET_DAYS + " days.");
        }
        store.saveQuietDays(c.guildId(), days, c.userId());
        return view(c, OffsetDateTime.now(ZoneOffset.UTC));
    }

    DataObject mark(SiteCaller c, DataObject body) {
        if (!marks.tryAcquire(Long.toString(c.userId()))) throw new ApiError(429, Text.of(c.guildId(), "dashboard.you_re_doing_that_too_fast"));
        String kind = body.getString("kind", "");
        String rsn = body.getString("rsn", "");
        if (!CockpitRules.KINDS.contains(kind)) throw refused(c, "That list isn't on the Clan health page.");
        if (!CockpitRules.plausibleName(rsn) || !store.knownName(c.guildId(), rsn)) throw refused(c, "That name isn't on your clan's roster.");
        if (!body.hasKey("done") || !body.isType("done", DataType.BOOLEAN)) throw refused(c, "Say whether the row is done.");
        String key = CockpitRules.keyOf(rsn);
        if (!body.getBoolean("done")) {
            store.unmark(c.guildId(), kind, key);
            return DataObject.empty().put("mark", null);
        }
        String name = displayName(c, body.getString("name", ""));
        store.mark(c.guildId(), kind, key, c.userId(), name);
        return DataObject.empty().put("mark", markJson(new Mark(kind, key, c.userId(), name, OffsetDateTime.now(ZoneOffset.UTC))));
    }

    /** How the marker shows: their name in the server where the bot sees them, else the name the website signed them in with. */
    static String displayName(SiteCaller c, String fromWebsite) {
        if (c.member() != null) return c.member().getEffectiveName();
        String clean = fromWebsite == null ? "" : fromWebsite.strip().replaceAll("\\p{Cntrl}", "");
        if (clean.isEmpty()) return null;
        return clean.length() > MAX_NAME ? clean.substring(0, MAX_NAME) : clean;
    }

    private static ApiError refused(SiteCaller c, String problem) {
        return new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of(problem));
    }
}
