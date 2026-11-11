package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.giveaway.Giveaway;
import com.runeclans.bot.giveaway.GiveawayRepository;
import com.runeclans.bot.giveaway.GiveawayService;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A server's giveaways for the website: list, start, end now, reroll, delete. A start is checked whole before anything is posted. Whatever reaches
 * Discord runs as a Discord job ({@link GiveawayJobs}, T-456): the answer is the list at once with {@code job} for the page to follow.
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs
 * every change.
 */
@BService
public class GiveawayAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(GiveawayAdminApi.class);
    private static final Duration MIN = Duration.ofMinutes(1);
    private static final Duration MAX = Duration.ofDays(60);

    private final GiveawayRepository repository;
    private final DiscordOutbox outbox;

    public GiveawayAdminApi(GiveawayRepository repository, DiscordOutbox outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("giveaways")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return list(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            return start(guild, actor, body.get());
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            return delete(guild, actor, parts[1]);
        }
        if (parts.length == 3 && parts[2].equals("end")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return act(guild, actor, parts[1], true);
        }
        if (parts.length == 3 && parts[2].equals("reroll")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return act(guild, actor, parts[1], false);
        }
        return null;
    }

    DataObject list(Guild guild) {
        DataArray giveaways = DataArray.empty();
        for (Giveaway g : repository.recent(guild.getIdLong(), 50)) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, g.channelId());
            giveaways.add(DataObject.empty().put("id", Long.toString(g.id())).put("prize", g.prize()).put("status", g.status().name())
                    .put("channelId", Long.toString(g.channelId())).put("channelName", channel == null ? null : channel.getName())
                    .put("winnerCount", g.winnerCount()).put("endsAt", g.endsAt().toString()).put("entries", repository.entryCount(g.id()))
                    .put("requiredRoleId", g.requiredRoleId() == null ? null : Long.toString(g.requiredRoleId()))
                    .put("winnerRoleId", g.winnerRoleId() == null ? null : Long.toString(g.winnerRoleId())).put("dmWinners", g.dmWinners()).put("reactionEntry", g.reactionEntry())
                    .put("winners", DataArray.fromCollection(g.winners().stream().map(String::valueOf).toList())));
        }
        return DataObject.empty().put("giveaways", giveaways).put("maxWinners", Giveaway.MAX_WINNERS).put("maxPrize", Giveaway.MAX_PRIZE);
    }

    DataObject start(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();
        String prize = body.getString("prize", "").strip();
        if (prize.isEmpty() || prize.length() > Giveaway.MAX_PRIZE) problems.add("Say what is being given away, in at most " + Giveaway.MAX_PRIZE + " characters.");
        int winners = body.getInt("winners", 1);
        if (winners < 1 || winners > Giveaway.MAX_WINNERS) problems.add("Choose between 1 and " + Giveaway.MAX_WINNERS + " winners.");
        long minutes = body.getLong("durationMinutes", 0);
        Duration duration = Duration.ofMinutes(Math.max(0, minutes));
        if (duration.compareTo(MIN) < 0 || duration.compareTo(MAX) > 0) problems.add("It must run for between 1 minute and 60 days.");
        String channelText = body.getString("channelId", "").strip();
        if (!channelText.matches("\\d{1,20}")) {
            problems.add("Choose a channel to post it in.");
        } else {
            try {
                GiveawayService.postable(guild, Long.parseLong(channelText));
            } catch (GiveawayService.GiveawayException e) {
                problems.add(e.getMessage());
            }
        }
        Long roleId = null;
        if (body.hasKey("requiredRoleId") && !body.isNull("requiredRoleId") && !body.getString("requiredRoleId", "").isBlank()) {
            String raw = body.getString("requiredRoleId").strip();
            if (!raw.matches("\\d{1,20}")) {
                problems.add("That role isn't valid.");
            } else {
                roleId = Long.parseLong(raw);
                Role role = guild.getRoleById(roleId);
                if (role == null) problems.add("That role doesn't exist in this server.");
            }
        }
        Long winnerRoleId = null;
        if (body.hasKey("winnerRoleId") && !body.isNull("winnerRoleId") && !body.getString("winnerRoleId", "").isBlank()) {
            String raw = body.getString("winnerRoleId").strip();
            Role role = raw.matches("\\d{1,20}") ? guild.getRoleById(Long.parseLong(raw)) : null;
            if (role == null) problems.add("The winner role doesn't exist in this server.");
            else if (!MemberApi.safeToAssign(guild, role)) problems.add("Winner role: " + MemberApi.handOutRefusal(guild, role));
            else winnerRoleId = role.getIdLong();
        }
        boolean dmWinners = body.getBoolean("dmWinners", false);
        boolean reactionEntry = body.getBoolean("reactionEntry", false);
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_giveaway_can_t_be_started"), problems);

        DiscordJob job = outbox.enqueue(GiveawayJobs.START, guild.getIdLong(), actor.getIdLong(), null,
                GiveawayJobs.startPayload(Long.parseLong(channelText), prize, winners, Instant.now().plus(duration), roleId, winnerRoleId, dmWinners, reactionEntry));
        log.info("Dashboard: {} queued a giveaway ({}) for channel {} (job {})", actor.getId(), prize, channelText, job.id());
        return list(guild).put("job", job.ticket());
    }

    private DataObject act(Guild guild, Member actor, String rawId, boolean end) {
        Giveaway g = find(guild, rawId);
        if (end && !g.open()) throw new ApiError(400, "That giveaway has already ended.");
        if (!end && g.open()) throw new ApiError(400, "That giveaway is still running. End it first.");
        // a second press while the first waits replaces it, so one press draws once
        DiscordJob job = outbox.enqueue(end ? GiveawayJobs.END : GiveawayJobs.REROLL, guild.getIdLong(), actor.getIdLong(),
                (end ? "giveaway-end:" : "giveaway-reroll:") + guild.getIdLong() + ":" + g.id(), GiveawayJobs.giveawayPayload(g.id()));
        log.info("Dashboard: {} queued {} giveaway {} (job {})", actor.getId(), end ? "ending" : "rerolling", g.id(), job.id());
        return list(guild).put("job", job.ticket());
    }

    DataObject delete(Guild guild, Member actor, String rawId) {
        Giveaway g = find(guild, rawId);
        DiscordJob job = null;
        if (g.messageId() != null && guild.getChannelById(GuildMessageChannel.class, g.channelId()) != null) {
            job = outbox.enqueue(GiveawayJobs.DELETE, guild.getIdLong(), actor.getIdLong(), null, GiveawayJobs.deletePayload(g.channelId(), g.messageId()));
        }
        repository.delete(guild.getIdLong(), g.id());
        log.info("Dashboard: {} deleted giveaway {}", actor.getId(), g.id());
        DataObject answer = list(guild);
        return job == null ? answer : answer.put("job", job.ticket());
    }

    private Giveaway find(Guild guild, String rawId) {
        if (rawId == null || !rawId.strip().matches("\\d{1,18}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_giveaway"));
        return repository.get(guild.getIdLong(), Long.parseLong(rawId.strip())).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.that_giveaway_doesn_t_exist")));
    }
}
