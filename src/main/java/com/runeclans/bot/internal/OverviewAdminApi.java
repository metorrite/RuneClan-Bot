package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.notice.Notice;
import com.runeclans.bot.notice.NoticeRepository;
import com.runeclans.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * What the dashboard's Overview shows: a quick read of what the bot tracks for one server (members, open tickets, signups, polls,
 * whether the bot itself is healthy) and the notices the bot's owner has posted for every server. Read-only apart from the
 * notices, which only the bot's owner may add or remove. Reached only through {@link TicketAdminApi}, which has already checked
 * the asker may use the dashboard in this server and audit-logs every write.
 */
@BService
public class OverviewAdminApi {
    private static final Logger log = LoggerFactory.getLogger(OverviewAdminApi.class);

    private final AdminOpsApi ops;
    private final TicketRepository tickets;
    private final SiteStatsRepository siteStats;
    private final PollService polls;
    private final com.runeclans.bot.commands.signup.SignupService signups;
    private final NoticeRepository notices;
    private final BotOwners owners;

    public OverviewAdminApi(AdminOpsApi ops, TicketRepository tickets, SiteStatsRepository siteStats, PollService polls,
                            com.runeclans.bot.commands.signup.SignupService signups, NoticeRepository notices, BotOwners owners) {
        this.signups = signups;
        this.ops = ops;
        this.tickets = tickets;
        this.siteStats = siteStats;
        this.polls = polls;
        this.notices = notices;
        this.owners = owners;
    }

    boolean isOwner(Guild guild, long userId) {
        return owners.isOwner(guild.getJDA(), userId);
    }

    /** {@link #isOwner} from what is already known, never asking Discord ({@link BotOwners#isKnownOwner}). */
    boolean isKnownOwner(long userId) {
        return owners.isKnownOwner(userId);
    }

    DataObject overview(Guild guild, Member actor) {
        long guildId = guild.getIdLong();
        DataObject attention = ops.attention(guild);
        DataObject health = ops.health(guild);

        int total = attention.getInt("total", 0);
        int unverified = attention.getArray("unverified").length();
        DataObject members = DataObject.empty()
                .put("rosterSize", total).put("linked", total - unverified).put("unverified", unverified)
                .put("stale", attention.getArray("stale").length()).put("inactive", attention.getArray("inactive").length())
                .put("promotionsDue", attention.getArray("promotions").length())
                .put("discordMembers", guild.getMemberCount());

        DataObject result = DataObject.empty()
                .put("members", members)
                .put("community", DataObject.empty().put("openSignups", openSignups(guildId)).put("openPolls", polls.activePolls(guildId).size()))
                .put("health", healthSummary(health))
                .put("notices", noticeList())
                .put("isOwner", isOwner(guild, actor.getIdLong()));

        try {
            var stats = tickets.stats(guildId);
            // A server that has never used tickets gets no ticket card: nothing is open and nothing was ever opened.
            boolean used = stats.open() + stats.closed() > 0 || !stats.byPanel().isEmpty();
            if (used) result.put("tickets", DataObject.empty().put("open", stats.open()).put("escalated", stats.escalated()).put("flagged", stats.flagged()));
        } catch (Exception e) {
            log.warn("Couldn't read ticket stats for the overview of guild {}", guildId, e);
        }
        return result;
    }

    /** The few things worth a green tick or a red cross at a glance, with the plain-language reasons when it isn't fine. */
    private static DataObject healthSummary(DataObject health) {
        List<String> problems = new ArrayList<>();
        String discord = health.getObject("discord").getString("status", "UNKNOWN");
        if (!discord.equals("CONNECTED")) problems.add("The bot isn't fully connected to Discord (" + discord.toLowerCase() + ").");
        if (!health.getObject("database").getBoolean("ok", false)) problems.add("The bot's database isn't answering.");
        DataObject polling = health.getObject("polling");
        if (polling.getInt("rateLimitedQueue", 0) > 0) problems.add("RuneScape is rate limiting the bot, so some updates are running slower than usual.");

        DataArray list = DataArray.empty();
        problems.forEach(list::add);
        return DataObject.empty().put("ok", problems.isEmpty()).put("problems", list)
                .put("uptimeSeconds", health.getLong("uptimeSeconds", 0)).put("autoPoll", health.getObject("environment").getBoolean("autoPoll", false));
    }

    // ---------- notices ----------

    /** The banner every dashboard page shows while this bot is not live: its automatic posts are kept out of the servers' channels. Never stored; it is there while it is true. */
    public static DataObject devModeNotice() {
        Long dev = com.runeclans.bot.alerts.AutoPostRouter.devChannelId();
        String where = dev == null ? "only written to the log" : "sent to one test channel without pings";
        return DataObject.empty().put("id", "dev-mode").put("severity", "warning").put("createdAt", java.time.Instant.now().toString())
                .put("body", "Dev mode: this bot is not the live one, so its automatic alerts (clan sync, rename alerts, reminders, feeds and other scheduled posts) are " + where
                        + " and nobody is pinged. Background jobs that change the server by themselves (auto-clean, delayed autoroles, timed roles, event roles and wrap-up, and the daily clan sync's Member/Guest roles) are paused;"
                        + " temporary voice channels still work. Things you run yourself still work normally.");
    }

    private DataArray noticeList() {
        DataArray array = DataArray.empty();
        if (com.runeclans.bot.alerts.AutoPostRouter.diverting()) array.add(devModeNotice());
        for (Notice notice : notices.all()) {
            array.add(DataObject.empty().put("id", Long.toString(notice.id())).put("severity", notice.severity()).put("body", notice.body()).put("createdAt", notice.createdAt().toString()));
        }
        return array;
    }

    DataObject listNotices() {
        return DataObject.empty().put("notices", noticeList());
    }

    DataObject addNotice(Guild guild, Member actor, DataObject body) {
        requireOwner(guild, actor);
        String text = body.getString("body", "").trim();
        String severity = body.getString("severity", "info").trim().toLowerCase();
        if (text.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.write_the_message_first"));
        if (text.length() > Notice.MAX_BODY) throw new ApiError(400, Text.of(guild, "dashboard.keep_it_under_characters", Notice.MAX_BODY));
        if (!Notice.SEVERITIES.contains(severity)) throw new ApiError(400, Text.of(guild, "dashboard.pick_info_warning_or_issue"));
        if (notices.all().size() >= Notice.MAX_ACTIVE) throw new ApiError(400, Text.of(guild, "dashboard.there_are_already_notices_up_remove", Notice.MAX_ACTIVE));

        notices.add(severity, text, actor.getIdLong());
        return listNotices();
    }

    DataObject removeNotice(Guild guild, Member actor, String rawId) {
        requireOwner(guild, actor);
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_notice"));
        }
        if (!notices.delete(id)) throw new ApiError(404, Text.of(guild, "dashboard.that_notice_is_already_gone"));
        return listNotices();
    }

    private void requireOwner(Guild guild, Member actor) {
        if (!isOwner(guild, actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_post"));
    }

    /** Open sheets, capped at 20 as the dashboard's list: from the hub while it keeps them ({@code hub-community}), else the bot's tables. */
    private int openSignups(long guildId) {
        if (signups.onHub()) return Math.min(20, signups.getVisibleSignups(guildId).size());
        return siteStats.countActiveSignups(guildId);
    }
}
