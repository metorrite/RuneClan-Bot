package com.runeclans.bot.runescape;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.premium.Premium;
import com.runeclans.bot.premium.PremiumFeatures;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.polling.PollCoordinator;
import com.runeclans.bot.runescape.polling.PollJob;
import com.runeclans.bot.runescape.polling.PollPriority;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps every tracked player's data (and the tracking feed it drives) fresh by registering the recurring poll jobs
 * with the {@link PollCoordinator}; see {@link ClanSyncScheduler} for the separate, once-a-day concern of keeping each
 * clan's roster itself (who's a member at all) up to date.
 * <p>
 * A player belongs to JonnyBot, not to a server, so there is one polling list for all of them and each name is
 * polled once per cycle however many servers it is in. The list has three tiers, each a job with its own window:
 * <ul>
 *   <li><b>Clan players</b> — everyone currently in the clan of a server that has registered one, linked or not
 *       (rosters are read from every such server and merged, so a clan tracked by two servers is polled once).
 *       They are the reason for the tracking feeds, so they are refreshed often: every
 *       {@link BotConfig#getRunescapeClanPollWindowMinutes()} minutes. A poll's new activity is announced in
 *       every server whose clan the player is in (see {@link RuneScapeStatsService}). With the hub's polling list on, RuneLink-linked
 *       characters the hub places in a clan registered on RuneClans join this tier too, whether or not any of the bot's servers has
 *       that clan (T-736).</li>
 *   <li><b>Community players</b> (T-736) — linked characters the hub's polling list places in a community and in no registered clan.
 *       Tracked fully like the rest, every {@link BotConfig#getRunescapeCommunityPollWindowMinutes()} minutes (the linked window
 *       unless set). Empty while the hub's list is off.</li>
 *   <li><b>Everyone else who linked</b> — registered with JonnyBot ({@code /rsn}) or linked with RuneLink (the hub's polling list,
 *       T-736), and in no registered clan or community. Their profile and
 *       history only need to stay roughly current, so they are spread across
 *       {@link BotConfig#getRunescapeLinkedPollWindowMinutes()} minutes, anchored so a cycle's last poll lands right
 *       before {@link #LINKED_ANCHOR_UTC} UTC, 30 minutes ahead of RuneScape's own daily reset — that keeps the day's
 *       numbers as current as reasonably possible without cutting it too close.</li>
 * </ul>
 * A tracked name with no stats at all (a new link, or one linked before anything polled it) is polled within minutes by the "first polls"
 * job ({@link FirstPolls}, Drake 2026-11-02: everyone is polled once on linking). Players nobody has linked and no registered clan lists are
 * not polled at all. Every name is in one tier only ({@link #tiers}), and the
 * engine merges a name already queued at another tier, so nobody is polled twice in a short window (Drake's rule, plan C-8 Q-6).
 * <p>
 * Each run works out its list afresh, so a server that registers a clan, or a player who links, joins the next run
 * without a restart. Each job spreads its players evenly across its window; the coordinator then paces them against
 * everything else the bot is polling, and skips anyone who was polled by something else within the last half window.
 * This class no longer sleeps between polls or owns a thread: it only says who to poll, how often and how urgently.
 */
@BService
public class RosterPollScheduler {
    private static final Logger log = LoggerFactory.getLogger(RosterPollScheduler.class);

    private static final Duration CLAN_INITIAL_DELAY = Duration.ofMinutes(2);
    private static final LocalTime LINKED_ANCHOR_UTC = LocalTime.of(23, 30);
    /** How often the "first polls" job looks for tracked names with no stats yet (a new link waits at most this plus the hub list's keep). */
    static final Duration FIRST_POLL_EVERY = Duration.ofMinutes(2);
    /** The most names one "first polls" run submits, spread over its two minutes, so a backlog never crowds out the clan tier. */
    static final int FIRST_POLLS_PER_RUN = 10;
    /**
     * A first poll counts as done by any poll newer than this: long enough to skip a name something else just polled, short enough that a
     * name RuneMetrics answered "private" for is really asked again after {@link FirstPolls#RETRY} (the engine remembers definite answers).
     */
    static final Duration FIRST_POLL_MAX_AGE = Duration.ofHours(1);

    private final ClanMemberRepository clanMemberRepository;
    private final ClanSyncService clanSyncService;
    private final PlayerLinkService linkService;
    private final PollCoordinator pollCoordinator;
    private final BotConfig botConfig;
    private final Premium premiumGuilds;
    private final HubPollingList hubList;
    private final PlayerLinkRepository links;
    private final FirstPolls firstPolls = new FirstPolls(java.time.Clock.systemUTC());

    public RosterPollScheduler(ClanMemberRepository clanMemberRepository, ClanSyncService clanSyncService, PlayerLinkService linkService,
                                PollCoordinator pollCoordinator, BotConfig botConfig, Premium premiumGuilds, RuneClansHubClient hub,
                                PlayerLinkRepository links) {
        this.links = links;
        this.premiumGuilds = premiumGuilds;
        this.hubList = HubPollingList.from(hub);
        this.clanMemberRepository = clanMemberRepository;
        this.clanSyncService = clanSyncService;
        this.linkService = linkService;
        this.pollCoordinator = pollCoordinator;
        this.botConfig = botConfig;
    }

    // InjectedJDAEvent fires once the JDA object exists, not once it's actually finished populating
    // its guild cache from the gateway — enumerating Shards.guilds(jda) immediately in onJdaReady can (and
    // did, in testing) see zero guilds. Deferring the first run by this long gives the cache time to
    // settle first; every run after that finds its servers fresh anyway.
    private static final Duration GUILD_DISCOVERY_DELAY = Duration.ofSeconds(30);

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED=false) — recurring polling will not run automatically.");
            return;
        }

        JDA jda = event.getJda();
        Duration clanWindow = Duration.ofMinutes(botConfig.getRunescapeClanPollWindowMinutes());
        Duration linkedWindow = Duration.ofMinutes(botConfig.getRunescapeLinkedPollWindowMinutes());
        Duration communityWindow = Duration.ofMinutes(botConfig.getRunescapeCommunityPollWindowMinutes());

        Duration premiumWindow = Duration.ofMinutes(botConfig.getRunescapePremiumPollWindowMinutes());
        Duration firstRun = CLAN_INITIAL_DELAY.compareTo(GUILD_DISCOVERY_DELAY) > 0 ? CLAN_INITIAL_DELAY : GUILD_DISCOVERY_DELAY;

        // the faster plan: clans of servers the bot's owner has put on it, ahead of every ordinary clan in the queue
        pollCoordinator.schedule(new PollJob("premium clan players", PollPriority.PREMIUM_CLAN, premiumWindow,
                firstRun, premiumWindow, premiumWindow.dividedBy(2), () -> premiumClanPlayers(jda)));
        pollCoordinator.schedule(new PollJob("clan players", PollPriority.CLAN, clanWindow,
                firstRun, clanWindow, clanWindow.dividedBy(2), () -> tiers(jda).clan()));
        pollCoordinator.schedule(new PollJob("community players", PollPriority.COMMUNITY, communityWindow,
                firstRun, communityWindow, communityWindow.dividedBy(2), () -> tiers(jda).community()));
        pollCoordinator.schedule(new PollJob("other linked players", PollPriority.LINKED, linkedWindow,
                durationUntilNextUtc(LINKED_ANCHOR_UTC), linkedWindow, linkedWindow.dividedBy(2), () -> tiers(jda).linked()));
        // Drake 2026-11-02: a player is polled once on linking, then on their tier's cycle. Any tracked name with no stats at all is polled
        // within minutes, a few per run spread over the run and queued behind the clan tier, which also backfills links nobody polled yet
        pollCoordinator.schedule(new PollJob("first polls", PollPriority.COMMUNITY, FIRST_POLL_EVERY,
                firstRun, FIRST_POLL_EVERY, FIRST_POLL_MAX_AGE, this::firstPollTargets));

        log.info("Roster poll jobs registered: players in a registered clan are spread across every {}; community players across every {}; " +
                        "other linked players across every {}, first cycle ending near {} UTC.",
                clanWindow, communityWindow, linkedWindow, LINKED_ANCHOR_UTC);
    }

    /** Every tracked name (the hub's polling list and the /rsn accounts) that has no stats snapshot yet ({@link FirstPolls}). */
    private List<String> firstPollTargets() {
        HubPollingList.Groups hub = hubList.groups();
        List<String> names = new ArrayList<>(hub.clan());
        names.addAll(hub.community());
        names.addAll(hub.linked());
        names.addAll(linkService.getAllAccountRsns());
        return firstPolls.due(names, links::namesWithSnapshot, FIRST_POLLS_PER_RUN);
    }

    /**
     * Whether the bot tracks this name (T-846, the Refresh button): a {@code /rsn} account or in the hub's last polling list. Never asks the
     * hub, so a web request doesn't wait on it.
     */
    public boolean tracked(String rsn) {
        if (rsn == null || rsn.isBlank()) return false;
        String key = rsn.strip().toLowerCase(Locale.ROOT);
        HubPollingList.Groups hub = hubList.lastKnown();
        for (List<String> list : List.of(hub.clan(), hub.community(), hub.linked(), linkService.getAllAccountRsns())) {
            for (String name : list) if (name.strip().toLowerCase(Locale.ROOT).equals(key)) return true;
        }
        return false;
    }

    /** The servers the bot is in that have a clan set up. Their rosters are the first tier. */
    private List<Long> clanGuildIds(JDA jda) {
        List<Long> ids = new ArrayList<>();
        for (Guild guild : Shards.guilds(jda)) {
            if (clanSyncService.getClanName(guild.getIdLong()) != null) ids.add(guild.getIdLong());
        }
        return ids;
    }

    private List<String> clanPlayers(JDA jda) {
        return clanMemberRepository.activeRsnsInGuilds(clanGuildIds(jda));
    }

    /** Players in the clan of a server on the premium plan (and that server has a clan set up). */
    private List<String> premiumClanPlayers(JDA jda) {
        Set<Long> premium = new HashSet<>();
        premium.addAll(premiumGuilds.guildsAllowing(PremiumFeatures.FAST_POLLING));
        List<Long> ids = clanGuildIds(jda).stream().filter(premium::contains).toList();
        return ids.isEmpty() ? List.of() : clanMemberRepository.activeRsnsInGuilds(ids);
    }

    /** The three recurring tiers below premium, worked out afresh for one job's run (the hub's list is shared for a few minutes). */
    private Tiers tiers(JDA jda) {
        return tiers(clanPlayers(jda), premiumClanPlayers(jda), linkService.getAllAccountRsns(), hubList.groups());
    }

    /** What the clan, community and linked jobs submit. Each name is in one list only, and never in a list below premium if it is premium. */
    record Tiers(List<String> clan, List<String> community, List<String> linked) {}

    /**
     * Splits everyone the bot should keep fresh into one tier each (T-736): the clan tier is the bot's clan rosters plus the hub's {@code clan}
     * group, the community tier the hub's {@code community} group, the linked tier the bot's {@code /rsn} accounts plus the hub's {@code linked}
     * group. A name goes to the first tier that has it (premium, clan, community, linked; names compared ignoring case) and the list keeps the
     * spelling it first came with.
     */
    static Tiers tiers(List<String> botClanPlayers, List<String> premiumPlayers, List<String> rsnAccounts, HubPollingList.Groups hub) {
        Set<String> taken = new HashSet<>();
        for (String rsn : premiumPlayers) taken.add(rsn.toLowerCase(Locale.ROOT));
        List<String> clan = claim(taken, botClanPlayers, hub.clan());
        List<String> community = claim(taken, hub.community());
        List<String> linked = claim(taken, rsnAccounts, hub.linked());
        return new Tiers(clan, community, linked);
    }

    /** The names of {@code lists} not yet in {@code taken}, once each in their first spelling; they are then taken. */
    @SafeVarargs
    private static List<String> claim(Set<String> taken, List<String>... lists) {
        Map<String, String> out = new LinkedHashMap<>();
        for (List<String> list : lists) {
            for (String rsn : list) {
                String key = rsn.toLowerCase(Locale.ROOT);
                if (!taken.contains(key)) out.putIfAbsent(key, rsn);
            }
        }
        taken.addAll(out.keySet());
        return List.copyOf(out.values());
    }

    private static Duration durationUntilNextUtc(LocalTime target) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime candidate = now.toLocalDate().atTime(target).atOffset(ZoneOffset.UTC);
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return Duration.between(now, candidate);
    }
}
