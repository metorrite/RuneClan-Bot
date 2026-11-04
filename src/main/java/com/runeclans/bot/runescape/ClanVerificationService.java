package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether someone may set a server's clan: the clan has to exist, and the person asking has to
 * be a verified member of it holding the Admin rank or higher — so a server can't claim a clan it has
 * nothing to do with. Checked against the live clan roster for the <em>proposed</em> name (not the
 * synced one, which doesn't exist yet on a fresh setup).
 * <p>
 * Every requirement is reported on its own, met or not, so a refusal can say exactly what's missing.
 */
@BService
public class ClanVerificationService {
    /** The lowest clan rank allowed to set a server's clan. */
    static final String MINIMUM_RANK = "Admin";

    public record Check(String requirement, boolean met, String detail) {}

    public record Result(List<Check> checks) {
        public boolean allMet() {
            return checks.stream().allMatch(Check::met);
        }
    }

    private final RuneScapeApiClient apiClient;
    private final PlayerLinkService linkService;
    private final RuneClansHubClient hub;

    public ClanVerificationService(RuneScapeApiClient apiClient, PlayerLinkService linkService, RuneClansHubClient hub) {
        this.apiClient = apiClient;
        this.linkService = linkService;
        this.hub = hub;
    }

    /** Blocking (one clan-roster request) — call off the event thread, or after deferring the interaction. */
    public Result verify(long guildId, long discordUserId, String clanName) {
        List<String> linkedRsns = linkService.getLinksForUser(guildId, discordUserId).stream().map(PlayerLink::rsn).toList();
        // T-878: with the hub on, the hub makes the Jagex call (one place for every RuneMetrics/Jagex request)
        if (hub != null && hub.enabled()) {
            RuneClansHubClient.Answer a = hub.call("POST", "/v1/clans/admin-check", DataObject.empty().put("clanName", clanName)
                    .put("rsns", DataArray.fromCollection(linkedRsns)).toString());
            if (a != null && a.ok() && a.json() != null) {
                DataObject m = a.json().isNull("member") ? null : a.json().getObject("member");
                return evaluate(clanName, a.json().getBoolean("exists", false), a.json().getInt("members", 0), m == null ? null : m.getString("rsn"),
                        m == null ? null : m.getString("rank"), linkedRsns);
            }
            return new Result(List.of(new Check("The clan **" + clanName + "** can be checked", false, "The clan list couldn't be reached right now — try again in a minute.")));
        }
        List<RuneScapeApiClient.ClanMember> roster = apiClient.fetchClanRoster(clanName);
        return evaluate(clanName, roster, linkedRsns);
    }

    static Result evaluate(String clanName, boolean exists, int size, String memberRsn, String memberRank, List<String> linkedRsns) {
        return build(clanName, exists, size, memberRsn, memberRank, linkedRsns);
    }

    static Result evaluate(String clanName, List<RuneScapeApiClient.ClanMember> roster, List<String> linkedRsns) {
        RuneScapeApiClient.ClanMember me = null;
        for (String rsn : linkedRsns) {
            for (var member : roster) {
                if (normalize(member.rsn()).equals(normalize(rsn))) {
                    me = member;
                    break;
                }
            }
            if (me != null) break;
        }
        return build(clanName, !roster.isEmpty(), roster.size(), me == null ? null : me.rsn(), me == null ? null : me.clanRank(), linkedRsns);
    }

    private static Result build(String clanName, boolean exists, int size, String meRsn, String meRank, List<String> linkedRsns) {
        boolean hasLink = !linkedRsns.isEmpty();
        boolean isMember = meRsn != null;
        boolean rankOk = isMember && isAtLeast(meRank, MINIMUM_RANK);

        return new Result(List.of(
                new Check("The clan **" + clanName + "** exists",
                        exists, exists ? size + " members found." : "No clan with that exact name came back — check the spelling."),
                new Check("You have a verified RuneScape name",
                        hasLink, hasLink ? "Linked: " + String.join(", ", linkedRsns) + "." : "Link yours with `/rs` first (an admin can verify you if you can't yet)."),
                new Check("One of your names is a member of that clan",
                        isMember, isMember ? "**" + meRsn + "** is in the clan." : !exists ? "Can't be checked until the clan exists." : !hasLink ? "Can't be checked until you have a verified name." : "None of your linked names appear in that clan's roster."),
                new Check("You hold the " + MINIMUM_RANK + " rank or higher",
                        rankOk, !isMember ? "Can't be checked until the step above is met." : "**" + meRsn + "** is a " + meRank + (rankOk ? "." : " — that's below " + MINIMUM_RANK + "."))));
    }

    /** {@code true} if {@code rank} sits at or above {@code minimum} on the standard clan rank ladder; an unrecognized rank never qualifies. */
    static boolean isAtLeast(String rank, String minimum) {
        int have = rankIndex(rank);
        int need = rankIndex(minimum);
        return have >= 0 && need >= 0 && have >= need;
    }

    private static int rankIndex(String rank) {
        String wanted = normalize(rank);
        List<String> ladder = ClanPointsRepository.STANDARD_RANK_NAMES;
        for (int i = 0; i < ladder.size(); i++) {
            if (normalize(ladder.get(i)).equals(wanted)) return i;
        }
        return -1;
    }

    /** RuneScape treats underscores and spaces as the same character, and Jagex's CSV uses a non-breaking space — fold all of them together. */
    private static String normalize(String value) {
        return value.replace('_', ' ').replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
    }
}
