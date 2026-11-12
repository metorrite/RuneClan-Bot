package com.runeclans.bot.internal;

import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsRepository.Balance;
import com.runeclans.bot.points.PointsRepository.Entry;
import com.runeclans.bot.points.PointsRepository.Settings;
import com.runeclans.bot.points.PointsRepository.Submission;
import com.runeclans.bot.points.PointsRules;
import com.runeclans.bot.points.PointsRules.Category;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A server's points for the website: the settings, the leaderboard, the latest ledger entries, and awarding. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard here and audit-logs every change.
 */
@BService
public class PointsAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(PointsAdminApi.class);
    private static final int TOP = 25;
    private static final int RECENT = 50;
    /** The longest the submissions page waits for Discord's review posts, all of them together. Well inside the website's 8 s. */
    static final Duration PROOF_BUDGET = Duration.ofSeconds(3);

    private final PointsRepository repository;
    private final PointsService service;
    private final RewardsRepository rewards;

    public PointsAdminApi(PointsRepository repository, PointsService service, RewardsRepository rewards) {
        this.repository = repository;
        this.service = service;
        this.rewards = rewards;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("points")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return overview(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveSettings(guild, actor, body.get());
        }
        if (parts.length >= 2 && parts[1].equals("rewards")) return rewardsRoute(guild, actor, method, parts, body);
        if (parts.length == 2 && parts[1].equals("redemptions")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return redemptions(guild);
        }
        if (parts.length == 4 && parts[1].equals("redemptions") && (parts[3].equals("fulfil") || parts[3].equals("cancel"))) {
            AdminRouteExtension.requireMethod(method, "POST");
            return handleRedemption(guild, actor, parts[2], parts[3].equals("fulfil"));
        }
        if (parts.length == 2 && parts[1].equals("submissions")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return submissions(guild);
        }
        if (parts.length == 4 && parts[1].equals("submissions") && (parts[3].equals("approve") || parts[3].equals("deny"))) {
            AdminRouteExtension.requireMethod(method, "POST");
            return decide(guild, actor, parts[2], parts[3].equals("approve"), body.get());
        }
        if (parts.length == 2 && parts[1].equals("award")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return award(guild, actor, body.get());
        }
        return null;
    }

    DataObject overview(Guild guild) {
        long guildId = guild.getIdLong();
        Settings settings = repository.settings(guildId);
        DataArray top = DataArray.empty();
        for (Balance b : repository.top(guildId, TOP)) {
            top.add(DataObject.empty().put("userId", Long.toString(b.userId())).put("userName", name(guild, b.userId())).put("points", b.points()));
        }
        DataArray recent = DataArray.empty();
        for (Entry e : repository.recent(guildId, RECENT)) {
            recent.add(DataObject.empty().put("id", Long.toString(e.id())).put("userId", Long.toString(e.userId())).put("userName", name(guild, e.userId()))
                    .put("points", e.points()).put("reason", e.reason()).put("category", e.category().name())
                    .put("awardedBy", e.awardedBy() == null ? null : Long.toString(e.awardedBy())).put("awardedByName", e.awardedBy() == null ? null : name(guild, e.awardedBy()))
                    .put("createdAt", e.createdAt().toString()));
        }
        PointsRepository.Totals totals = repository.totals(guildId);
        return DataObject.empty().put("enabled", settings.enabled()).put("unit", settings.unit())
                .put("reviewChannelId", settings.reviewChannelId() == null ? null : Long.toString(settings.reviewChannelId()))
                .put("top", top).put("recent", recent)
                .put("totals", DataObject.empty().put("members", totals.members()).put("outstanding", totals.outstanding()).put("awarded", totals.awarded()).put("spent", totals.spent()))
                .put("maxPerEntry", PointsRules.MAX_PER_ENTRY);
    }

    DataObject saveSettings(Guild guild, Member actor, DataObject body) {
        String unit = body.getString("unit", "points").strip();
        var problems = new java.util.ArrayList<>(PointsRules.settingsProblems(unit));
        Long reviewChannel = null;
        String rawChannel = body.isNull("reviewChannelId") ? "" : body.getString("reviewChannelId", "").strip();
        if (!rawChannel.isEmpty()) {
            if (!rawChannel.matches("\\d{1,20}") || guild.getTextChannelById(rawChannel) == null) {
                problems.add("Pick a text channel in this server for submissions.");
            } else {
                var channel = guild.getTextChannelById(rawChannel);
                if (!guild.getSelfMember().hasPermission(channel, net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_SEND, net.dv8tion.jda.api.Permission.MESSAGE_ATTACH_FILES)) {
                    problems.add("The bot needs to see, send messages and attach files in #" + channel.getName() + ".");
                }
                reviewChannel = Long.parseLong(rawChannel);
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.the_points_settings_can_t_be"), problems);
        repository.saveSettings(new Settings(guild.getIdLong(), body.getBoolean("enabled", false), unit, reviewChannel));
        log.info("Dashboard: {} saved the points settings", actor.getId());
        return overview(guild);
    }

    DataObject award(Guild guild, Member actor, DataObject body) {
        String rawId = body.getString("userId", "").strip();
        if (!rawId.matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.pick_a_member"));
        long userId = Long.parseLong(rawId);
        // the bot caches only online members: ask Discord for anyone else (a 503 "try again" when it's slow, never a wrong "not here")
        Member target = com.runeclans.bot.discord.DiscordLookups.memberOrSlow(guild, userId);
        if (target == null) throw new ApiError(400, Text.of(guild, "dashboard.that_member_isn_t_in_this"));
        if (target.getUser().isBot()) throw new ApiError(400, Text.of(guild, "dashboard.bots_can_t_hold_points"));
        long points;
        try {
            points = body.getLong("points", 0);
        } catch (RuntimeException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.the_amount_must_be_a_whole"));
        }
        var result = service.apply(guild.getIdLong(), userId, points, body.getString("reason", ""), Category.MANUAL, null, actor.getIdLong());
        if (result.outcome() != PointsService.Outcome.DONE) throw new ApiError(400, result.message());
        log.info("Dashboard: {} gave {} point(s) to {}", actor.getId(), points, userId);
        return overview(guild);
    }

    /**
     * Waiting submissions (oldest first) and the latest decided ones. Proof links are re-read from the review post, since Discord's expire.
     * The review posts are all asked for at once and waited on together for at most {@link #PROOF_BUDGET}; a post that hasn't arrived by then
     * shows the stored link, as a deleted or unreadable one always has.
     */
    DataObject submissions(Guild guild) {
        long guildId = guild.getIdLong();
        List<Submission> waiting = repository.pending(guildId, 25);
        List<Submission> done = repository.decided(guildId, 25);
        List<String> proofs = freshProofs(guild, waiting, PROOF_BUDGET);
        DataArray pending = DataArray.empty();
        for (int i = 0; i < waiting.size(); i++) pending.add(submissionJson(guild, waiting.get(i), proofs.get(i)));
        DataArray decided = DataArray.empty();
        for (Submission s : done) decided.add(submissionJson(guild, s, s.proofUrl()));
        return DataObject.empty().put("pending", pending).put("decided", decided).put("maxPerEntry", PointsRules.MAX_PER_ENTRY);
    }

    /**
     * Each submission's proof link, in order: the first attachment of its review post when Discord answers within {@code budget} (all posts are
     * asked for before any is waited on), otherwise the stored link. A post still on its way at the deadline is cancelled.
     */
    static List<String> freshProofs(Guild guild, List<Submission> submissions, Duration budget) {
        List<CompletableFuture<Message>> asked = new ArrayList<>(submissions.size());
        for (Submission s : submissions) {
            CompletableFuture<Message> future = null;
            if (s.reviewChannelId() != null && s.reviewMessageId() != null) {
                var channel = guild.getTextChannelById(s.reviewChannelId());
                if (channel != null) {
                    try {
                        future = channel.retrieveMessageById(s.reviewMessageId()).submit();
                    } catch (RuntimeException e) {
                        // can't even be asked (no access to the channel): the stored link
                    }
                }
            }
            asked.add(future);
        }
        long deadline = System.nanoTime() + budget.toNanos();
        List<String> proofs = new ArrayList<>(submissions.size());
        for (int i = 0; i < submissions.size(); i++) {
            String proof = submissions.get(i).proofUrl();
            CompletableFuture<Message> future = asked.get(i);
            if (future != null) {
                try {
                    Message message = future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (message != null && !message.getAttachments().isEmpty()) proof = message.getAttachments().get(0).getUrl();
                } catch (TimeoutException e) {
                    future.cancel(true); // Discord is slow: the stored link, which may have expired
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.cancel(true);
                } catch (ExecutionException | RuntimeException e) {
                    // the review post was deleted or unreadable: fall back to the stored link, which may have expired
                }
            }
            proofs.add(proof);
        }
        return proofs;
    }

    private DataObject submissionJson(Guild guild, Submission s, String proof) {
        return DataObject.empty().put("id", Long.toString(s.id())).put("userId", Long.toString(s.userId())).put("userName", name(guild, s.userId()))
                .put("description", s.description()).put("proofUrl", proof).put("status", s.status().name())
                .put("decidedByName", s.decidedBy() == null ? null : name(guild, s.decidedBy())).put("decisionNote", s.decisionNote())
                .put("pointsAwarded", s.pointsAwarded()).put("createdAt", s.createdAt().toString())
                .put("decidedAt", s.decidedAt() == null ? null : s.decidedAt().toString());
    }

    DataObject decide(Guild guild, Member actor, String rawId, boolean approve, DataObject body) {
        if (rawId == null || !rawId.matches("\\d{1,18}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_submission"));
        long points = 0;
        if (approve) {
            try {
                points = body.getLong("points", 0);
            } catch (RuntimeException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.the_amount_must_be_a_whole"));
            }
        }
        String note = body.isNull("note") ? "" : body.getString("note", "");
        var result = service.decide(guild.getIdLong(), Long.parseLong(rawId), approve ? PointsRules.Status.APPROVED : PointsRules.Status.DENIED, points, note, actor.getIdLong());
        switch (result.outcome()) {
            case NOT_FOUND -> throw new ApiError(404, result.message());
            case ALREADY_DECIDED -> throw new ApiError(409, result.message());
            case INVALID -> throw new ApiError(400, result.message());
            default -> { }
        }
        log.info("Dashboard: {} {} submission {}", actor.getId(), approve ? "approved" : "denied", rawId);
        notifyMember(guild, result.submission(), approve);
        return submissions(guild);
    }

    private void notifyMember(Guild guild, Submission s, boolean approve) {
        String unit = repository.settings(guild.getIdLong()).unit();
        String text = approve
                ? "Your submission in **" + guild.getName() + "** was approved: you got " + PointsRules.format(s.pointsAwarded() == null ? 0 : s.pointsAwarded(), unit) + "."
                : "Your submission in **" + guild.getName() + "** wasn't approved." + (s.decisionNote() == null ? "" : " Staff said: " + s.decisionNote());
        guild.getJDA().retrieveUserById(s.userId()).flatMap(user -> user.openPrivateChannel()).flatMap(channel -> channel.sendMessage(text))
                .queue(ok -> { }, error -> { });
    }

    // ---------- rewards ----------

    private DataObject rewardsRoute(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 2) {
            if (method.equals("GET")) return rewardList(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            return saveReward(guild, actor, null, body.get());
        }
        if (parts.length == 3) {
            AdminRouteExtension.requireMethod(method, "PUT");
            if (!parts[2].matches("\\d{1,18}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_reward"));
            return saveReward(guild, actor, Long.parseLong(parts[2]), body.get());
        }
        return null;
    }

    DataObject rewardList(Guild guild) {
        DataArray list = DataArray.empty();
        for (var r : rewards.rewards(guild.getIdLong(), false)) {
            list.add(DataObject.empty().put("id", Long.toString(r.id())).put("name", r.name()).put("description", r.description()).put("cost", r.cost())
                    .put("stock", r.stock()).put("active", r.active()));
        }
        return DataObject.empty().put("rewards", list).put("maxRewards", PointsRules.MAX_REWARDS).put("maxPerEntry", PointsRules.MAX_PER_ENTRY);
    }

    DataObject saveReward(Guild guild, Member actor, Long id, DataObject body) {
        String name = body.getString("name", "").strip();
        String description = body.isNull("description") ? "" : body.getString("description", "").strip();
        long cost;
        Long stock = null;
        try {
            cost = body.getLong("cost", 0);
            if (!body.isNull("stock") && body.hasKey("stock")) stock = body.getLong("stock");
        } catch (RuntimeException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.the_cost_and_stock_must_be"));
        }
        boolean active = body.getBoolean("active", true);
        String problem = PointsRules.rewardProblem(name, description, cost, stock);
        if (problem != null) throw new ApiError(400, problem);
        if (id == null) {
            if (rewards.activeCount(guild.getIdLong()) >= PointsRules.MAX_REWARDS) throw new ApiError(400, Text.of(guild, "dashboard.a_server_can_have_active_rewards", PointsRules.MAX_REWARDS));
            rewards.addReward(guild.getIdLong(), name, description, cost, stock);
        } else if (!rewards.updateReward(guild.getIdLong(), id, name, description, cost, stock, active)) {
            throw new ApiError(404, Text.of(guild, "dashboard.that_reward_doesn_t_exist"));
        }
        log.info("Dashboard: {} saved a points reward", actor.getId());
        return rewardList(guild);
    }

    DataObject redemptions(Guild guild) {
        long guildId = guild.getIdLong();
        DataArray pending = DataArray.empty();
        for (var r : rewards.pendingRedemptions(guildId, 50)) pending.add(redemptionJson(guild, r));
        DataArray handled = DataArray.empty();
        for (var r : rewards.handledRedemptions(guildId, 25)) handled.add(redemptionJson(guild, r));
        return DataObject.empty().put("pending", pending).put("handled", handled);
    }

    private DataObject redemptionJson(Guild guild, RewardsRepository.Redemption r) {
        return DataObject.empty().put("id", Long.toString(r.id())).put("rewardName", r.rewardName()).put("userId", Long.toString(r.userId()))
                .put("userName", name(guild, r.userId())).put("cost", r.cost()).put("status", r.status().name())
                .put("handledByName", r.handledBy() == null ? null : name(guild, r.handledBy())).put("createdAt", r.createdAt().toString())
                .put("handledAt", r.handledAt() == null ? null : r.handledAt().toString());
    }

    DataObject handleRedemption(Guild guild, Member actor, String rawId, boolean fulfil) {
        if (rawId == null || !rawId.matches("\\d{1,18}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_redemption"));
        var result = service.handle(guild.getIdLong(), Long.parseLong(rawId), fulfil, actor.getIdLong());
        switch (result.outcome()) {
            case NOT_FOUND -> throw new ApiError(404, result.message());
            case ALREADY_HANDLED -> throw new ApiError(409, result.message());
            default -> { }
        }
        log.info("Dashboard: {} {} redemption {}", actor.getId(), fulfil ? "fulfilled" : "cancelled", rawId);
        String unit = repository.settings(guild.getIdLong()).unit();
        var r = result.redemption();
        String text = fulfil ? "Your reward **" + r.rewardName() + "** in **" + guild.getName() + "** has been handed over."
                : "Your reward **" + r.rewardName() + "** in **" + guild.getName() + "** was cancelled and " + PointsRules.format(r.cost(), unit) + " refunded.";
        guild.getJDA().retrieveUserById(r.userId()).flatMap(user -> user.openPrivateChannel()).flatMap(channel -> channel.sendMessage(text)).queue(ok -> { }, error -> { });
        return redemptions(guild);
    }

    private static String name(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getEffectiveName();
    }
}
