package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.premium.PremiumGuildRepository;
import com.runeclans.bot.runescape.ClanMemberRepository;
import com.runeclans.bot.runescape.polling.PollCoordinator;
import com.runeclans.bot.runescape.polling.PollEngine;
import com.runeclans.bot.runescape.polling.PollPriority;
import com.runeclans.bot.runescape.polling.PollTally.Counts;
import com.runeclans.bot.runescape.polling.RuneMetricsBudget;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The owner's RuneMetrics budget page: what each tier of the shared request queue has sent today, how far behind each is, what rate-limiting and failures
 * there have been, what each server's clan adds to the load, and the last two weeks of it. Only counters and roster sizes: no player names and no
 * per-request records. {@code GET runemetrics-budget}, owner only (the bot refuses anyone else whatever the website shows).
 */
@BService
public class RuneMetricsBudgetAdminApi implements AdminRouteExtension {
    static final int HISTORY_DAYS = 14;

    private final PollCoordinator coordinator;
    private final RuneMetricsBudget budget;
    private final ClanMemberRepository clanMembers;
    private final GuildSettingsService settings;
    private final PremiumGuildRepository premium;
    private final BotConfig config;
    private final BotOwners owners;

    public RuneMetricsBudgetAdminApi(PollCoordinator coordinator, RuneMetricsBudget budget, ClanMemberRepository clanMembers, GuildSettingsService settings,
                                     PremiumGuildRepository premium, BotConfig config, BotOwners owners) {
        this.coordinator = coordinator;
        this.budget = budget;
        this.clanMembers = clanMembers;
        this.settings = settings;
        this.premium = premium;
        this.config = config;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("runemetrics-budget")) return null;
        AdminRouteExtension.requireMethod(method, "GET");
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_see_2"));
        return overview(guild);
    }

    /** One server's clan and what it asks of the queue, before knowing which servers share it. */
    record ServerLoad(long guildId, String name, String clanKey, String clanName, boolean premium, int players) {}

    DataObject overview(Guild guild) {
        PollEngine.Status engine = coordinator.status().engine();
        Map<PollPriority, Long> lag = coordinator.oldestWaitingByPriority();
        var today = coordinator.tally().snapshot();
        long clanWindow = config.getRunescapeClanPollWindowMinutes();
        long premiumWindow = config.getRunescapePremiumPollWindowMinutes();
        long linkedWindow = config.getRunescapeLinkedPollWindowMinutes();
        long communityWindow = config.getRunescapeCommunityPollWindowMinutes();

        DataArray tiers = DataArray.empty();
        for (PollPriority p : PollPriority.values()) {
            Counts c = today.byPriority().getOrDefault(p, new Counts(0, 0, 0, 0, 0));
            Long window = switch (p) {
                case PREMIUM_CLAN -> premiumWindow;
                case CLAN -> clanWindow;
                case COMMUNITY -> communityWindow;
                case LINKED -> linkedWindow;
                case INTERACTIVE -> null;
            };
            long lagSeconds = lag.getOrDefault(p, 0L) / 1000;
            tiers.add(DataObject.empty().put("tier", p.name()).put("windowMinutes", window).put("queued", engine.queuedByPriority().getOrDefault(p, 0))
                    .put("lagSeconds", lagSeconds).put("behind", window != null && lagSeconds > window * 60 / 2)
                    .put("sent", c.sent()).put("reused", c.reused()).put("rateLimited", c.rateLimited()).put("failed", c.failed()).put("gaveUp", c.gaveUp()));
        }

        List<ServerLoad> loads = new ArrayList<>();
        for (Guild g : Shards.guilds(guild.getJDA())) {
            String clan = settings.getEffective(g.getIdLong()).clanName();
            if (clan == null) continue;
            loads.add(new ServerLoad(g.getIdLong(), g.getName(), key(clan), clan, premium.isPremium(g.getIdLong()), clanMembers.getAll(g.getIdLong(), true).size()));
        }
        Map<String, Integer> serversPerClan = new HashMap<>();
        loads.forEach(l -> serversPerClan.merge(l.clanKey(), 1, Integer::sum));

        DataArray servers = DataArray.empty();
        double totalClanDemand = 0;
        Map<String, Boolean> counted = new HashMap<>();
        for (ServerLoad l : loads.stream().sorted(Comparator.comparingInt(ServerLoad::players).reversed()).toList()) {
            double perDay = demandPerDay(l, clanWindow, premiumWindow);
            int sharedWith = serversPerClan.get(l.clanKey()) - 1;
            // a clan set up in two servers is fetched and polled once, so it counts once in the total
            if (counted.putIfAbsent(l.clanKey(), true) == null) totalClanDemand += perDay;
            servers.add(DataObject.empty().put("guildId", Long.toString(l.guildId())).put("name", l.name()).put("clan", l.clanName()).put("tier", l.premium() ? "PREMIUM_CLAN" : "CLAN")
                    .put("players", l.players()).put("requestsPerDay", Math.round(perDay)).put("sharedWith", sharedWith));
        }

        double capacityPerDay = engine.intervalMs() <= 0 ? 0 : 86_400_000.0 / engine.intervalMs();
        DataArray history = DataArray.empty();
        for (RuneMetricsBudget.Row row : budget.history(HISTORY_DAYS)) {
            history.add(DataObject.empty().put("day", row.day().toString()).put("tier", row.priority().name()).put("sent", row.counts().sent()).put("reused", row.counts().reused())
                    .put("rateLimited", row.counts().rateLimited()).put("failed", row.counts().failed()).put("gaveUp", row.counts().gaveUp()));
        }
        // T-851: while the hub polls, these counters stay at zero; the numbers live on the hub's /admin/polling (RuneClan Admin's RuneMetrics tab)
        return DataObject.empty().put("hubPolls", coordinator.hubPolls()).put("tiers", tiers).put("servers", servers).put("history", history)
                .put("pace", DataObject.empty().put("intervalSeconds", Math.round(engine.intervalMs() / 1000.0)).put("requestsLastMinute", engine.requestsLastMinute())
                        .put("requestsLast10Minutes", engine.requestsLast10Minutes()).put("slowedBy", engine.multiplier()).put("queued", engine.queued()))
                .put("capacityPerDay", Math.round(capacityPerDay)).put("clanDemandPerDay", Math.round(totalClanDemand)).put("day", today.day().toString());
    }

    /** What a server's clan asks of the queue in a day: every active member once per window of its tier. */
    static double demandPerDay(ServerLoad load, long clanWindowMinutes, long premiumWindowMinutes) {
        long window = load.premium() ? premiumWindowMinutes : clanWindowMinutes;
        return window <= 0 ? 0 : load.players() * (1440.0 / window);
    }

    static String key(String clanName) {
        return clanName.strip().replace('_', ' ').replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
