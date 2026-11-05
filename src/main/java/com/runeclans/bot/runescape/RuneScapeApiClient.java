package com.runeclans.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The bot's one way to a clan's member list. The bot makes no Jagex call of its own (Drake, 2026-11-04: after the hub move only the hub talks
 * to Jagex). A clan registered on RuneClans is read from the roster the hub already keeps ({@code GET /v1/clans/by-name/{name}}, then
 * {@code GET /v1/clans/{clanId}/roster}): the hub polls every registered clan's list itself, so the bot's daily sync costs no extra Jagex call
 * (T-912; Drake 2026-11-04: one player list, never a double poll). Only a clan the hub doesn't keep yet (not registered, or never fetched) falls
 * back to the hub's live fetch, {@code POST /v1/clans/live-roster}. A hub failure, or a bot with its hub client off, is an empty roster, as a
 * Jagex failure always was.
 * <p>
 * The RuneMetrics profile, hiscores and avatar fetches that used to live here went with the RuneMetrics shutdown on 2026-11-10; the hub polls
 * profiles from the Jagex Platform API and the bot reads them from the hub.
 */
@BService
public class RuneScapeApiClient {
    private static final Logger log = LoggerFactory.getLogger(RuneScapeApiClient.class);

    /** Clan rosters fetched in the last 90 seconds, by lower-cased clan name; only successful, non-empty fetches are kept. */
    private final com.runeclans.bot.util.TtlCache<String, List<ClanMember>> clanRosterCache = new com.runeclans.bot.util.TtlCache<>(Duration.ofSeconds(90));

    private final com.runeclans.bot.runeclans.RuneClansHubClient hub;

    public RuneScapeApiClient(com.runeclans.bot.runeclans.RuneClansHubClient hub) {
        this.hub = hub;
    }

    /** One row of the clan's member roster — see {@link #fetchClanRoster}. */
    public record ClanMember(String rsn, String clanRank, long totalXp, long kills) {}

    /**
     * The clan's current member roster, from the hub. No join dates: nothing Jagex serves says when a member joined — that's only visible
     * in-game. {@code kills} came back {@code 0} for every member tested, so it may no longer be populated by Jagex; kept anyway.
     * <p>
     * Empty if the clan doesn't exist or the request failed — not something to crash a sync over.
     */
    public List<ClanMember> fetchClanRoster(String clanName) {
        // Two servers tracking the same clan hit this within moments of each other at the daily sync (and a self-refresh can follow
        // it), so a roster fetched in the last minute and a half is reused instead of asking the hub again.
        String key = clanName.strip().toLowerCase(java.util.Locale.ROOT);
        var cached = clanRosterCache.get(key);
        if (cached.isPresent()) return cached.get();
        List<ClanMember> roster = fetchClanRosterFromHub(clanName);
        if (!roster.isEmpty()) clanRosterCache.put(key, roster);
        return roster;
    }

    private List<ClanMember> fetchClanRosterFromHub(String clanName) {
        if (hub == null || !hub.enabled()) {
            log.warn("The hub client is off (RUNECLANS_HUB_ENABLED), so there is no clan roster for '{}'.", clanName);
            return List.of();
        }
        List<ClanMember> kept = keptRoster(clanName);
        return kept != null ? kept : liveRoster(clanName);
    }

    /** The roster the hub keeps for a registered clan; null when the hub keeps none for it (then the live fetch decides). */
    private List<ClanMember> keptRoster(String clanName) {
        try {
            var clan = hub.call("GET", "/v1/clans/by-name/" + java.net.URLEncoder.encode(clanName.strip(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"), null);
            if (clan == null || !clan.ok() || clan.json() == null || clan.json().getString("clanId", "").isBlank()) return null;
            var a = hub.call("GET", "/v1/clans/" + clan.json().getString("clanId") + "/roster", null);
            if (a == null || !a.ok() || a.json() == null || a.json().isNull("poll") || !a.json().hasKey("poll")) return null;
            List<ClanMember> members = members(a.json().getArray("members"));
            return members.isEmpty() ? null : members;
        } catch (RuntimeException e) {
            log.debug("The hub's kept roster for '{}' couldn't be read, so the live list is asked instead: {}", clanName, e.toString());
            return null;
        }
    }

    private static List<ClanMember> members(net.dv8tion.jda.api.utils.data.DataArray rows) {
        List<ClanMember> members = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            var m = rows.getObject(i);
            if (!m.getBoolean("active", true)) continue;
            members.add(new ClanMember(m.getString("rsn"), m.getString("rank"), m.getLong("totalXp", 0), m.getLong("kills", 0)));
        }
        return members;
    }

    private List<ClanMember> liveRoster(String clanName) {
        try {
            var a = hub.call("POST", "/v1/clans/live-roster", DataObject.empty().put("clanName", clanName).toString());
            if (a == null || !a.ok() || a.json() == null) {
                log.warn("The hub's live roster for '{}' didn't answer ({}).", clanName, a == null ? "unreachable" : "HTTP " + a.status());
                return List.of();
            }
            return members(a.json().getArray("members"));
        } catch (RuntimeException e) {
            log.warn("The hub's live roster for '{}' couldn't be read: {}", clanName, e.toString());
            return List.of();
        }
    }
}
