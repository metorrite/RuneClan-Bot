package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.commands.signup.SignupEntry;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.commands.signup.SignupSession;
import com.runeclans.bot.commands.signup.SignupType;
import com.runeclans.bot.commands.signup.SubmissionField;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Admin controls for polls and signups, for the website. Reached only through {@link TicketAdminApi}, which has
 * already verified the acting user is an Admin or Developer. Everything here goes through the same services the
 * Discord buttons use, so the Discord messages stay in step; Discord message refreshes are debounced so a flurry
 * of admin clicks becomes one edit.
 */
@BService
public class CommunityAdminApi {
    private static final Logger log = LoggerFactory.getLogger(CommunityAdminApi.class);

    // Mirrors the limits the /poll panel in Discord enforces.
    private static final long REFRESH_DELAY_MILLIS = 800;

    private final PollService polls;
    private final SignupService signups;
    private final SiteStatsRepository stats;
    private final PlayerLinkRepository links;
    private final Debouncer debouncer;
    private final SiteCache cache;
    private final CommunitySettings community;

    public CommunityAdminApi(PollService polls, SignupService signups, SiteStatsRepository stats, PlayerLinkRepository links, Debouncer debouncer, SiteCache cache, CommunitySettings community) {
        this.community = community;
        this.polls = polls;
        this.signups = signups;
        this.stats = stats;
        this.links = links;
        this.debouncer = debouncer;
        this.cache = cache;
    }

    private static long id(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_id"));
        }
    }

    private static <T> T required(T value, String message) {
        if (value == null) throw new ApiError(400, message);
        return value;
    }

    // ---------- polls ----------

    DataObject createPoll(Guild guild, Member actor, DataObject body) {
        String title = body.getString("title", "").strip();
        boolean anonymous = body.getBoolean("anonymous", false);
        boolean multiple = body.getBoolean("multiple", false);

        List<String> options = new ArrayList<>();
        if (!body.isNull("options")) {
            DataArray array = body.getArray("options");
            for (int i = 0; i < array.length(); i++) {
                String option = array.getString(i).strip();
                if (!option.isEmpty()) options.add(option);
            }
        }

        Integer hours = body.isNull("durationHours") ? null : body.getInt("durationHours");
        String problem = PollRules.validate(title, options);
        if (problem == null) problem = PollRules.validateDuration(hours);
        if (problem != null) throw new ApiError(400, problem);

        GuildMessageChannel channel = postableChannel(guild, body.getString("channelId", ""), GuildMessageChannel.class);
        // on the hub the poll carries its end time and the hub closes it; on the bot's own tables the poll closer does
        java.time.Instant closesAt = hours == null ? null : java.time.Instant.now().plus(java.time.Duration.ofHours(hours));
        long pollId = polls.createPoll(guild, channel, title, anonymous, multiple, options, actor.getIdLong(), closesAt);
        if (hours != null && !polls.onHub()) community.scheduleClose(pollId, java.time.OffsetDateTime.now().plusHours(hours));
        cache.invalidate("polls");
        log.info("Dashboard: {} created a poll '{}' in #{}", actor.getId(), title, channel.getName());
        return DataObject.empty().put("created", true);
    }

    DataObject endPoll(Guild guild, Member actor, long pollId) {
        var poll = polls.getSessionById(pollId);
        if (poll == null || poll.guildId() != guild.getIdLong()) throw new ApiError(404, Text.of(guild, "dashboard.that_poll_doesn_t_exist_or"));
        polls.closePoll(guild, pollId);
        cache.invalidate("polls");
        log.info("Dashboard: {} ended poll {}", actor.getId(), pollId);
        return DataObject.empty().put("ended", true);
    }

    // ---------- signups ----------

    /** The sheets as an admin sees them: the same list as the public page, but with who is who (Discord ids included). */
    DataObject signupList(Guild guild) {
        if (signups.onHub()) return hubSignupList(guild);
        DataArray array = DataArray.empty();
        for (var sheet : stats.activeSignups(guild.getIdLong())) {
            SignupSession session = signups.getSessionById(sheet.id());
            DataArray entries = DataArray.empty();
            for (var entry : sheet.entries()) {
                entries.add(DataObject.empty().put("userId", Long.toString(entry.userId())).put("name", entryName(guild, session, entry.userId(), entry.rsn())).put("position", entry.position()));
            }
            array.add(DataObject.empty().put("id", Long.toString(sheet.id())).put("title", sheet.title()).put("type", session == null ? "QUEUE" : session.type().name())
                    .put("paused", !"ACTIVE".equalsIgnoreCase(sheet.status())).put("entries", entries));
        }
        return DataObject.empty().put("signups", array);
    }

    /** How long one hub-backed signup list may spend reading sheets' entries: the website gives up on the bot after 8 seconds. */
    static final java.time.Duration HUB_LIST_BUDGET = java.time.Duration.ofSeconds(4);

    /**
     * {@link #signupList} while the hub keeps the sheets ({@code hub-community}): the same shape, read through the signup service. Each
     * sheet's entries are one hub read, so they are read only within {@link #HUB_LIST_BUDGET}, and not at all after the hub fails one:
     * a sheet left out says so ({@code entriesUnknown: true}, no entries) instead of the whole list timing out.
     */
    private DataObject hubSignupList(Guild guild) {
        DataArray array = DataArray.empty();
        List<SignupSession> sheets = new ArrayList<>(signups.getVisibleSignups(guild.getIdLong()));
        java.util.Collections.reverse(sheets); // newest first, as the bot's own list
        long deadline = System.nanoTime() + HUB_LIST_BUDGET.toNanos();
        boolean reading = true;
        for (SignupSession session : sheets.subList(0, Math.min(20, sheets.size()))) {
            DataObject sheet = DataObject.empty().put("id", Long.toString(session.signupId())).put("title", session.title()).put("type", session.type().name());
            DataArray entries = DataArray.empty();
            boolean known = false;
            if (reading && System.nanoTime() < deadline) {
                try {
                    int position = 1;
                    for (SignupEntry entry : signups.getEntries(session.signupId())) {
                        entries.add(DataObject.empty().put("userId", Long.toString(entry.userId())).put("name", entryName(guild, session, entry.userId(), entry.username()))
                                .put("position", position++));
                    }
                    sheet.put("paused", !signups.isSignupActive(session.signupId()));
                    known = true;
                } catch (com.runeclans.bot.hubcommunity.HubCommunityProblem e) {
                    reading = false; // the hub is failing: the other sheets would only wait for it too
                    entries = DataArray.empty();
                }
            }
            if (!known) sheet.put("paused", false).put("entriesUnknown", true);
            array.add(sheet.put("entries", entries));
        }
        return DataObject.empty().put("signups", array);
    }

    private String entryName(Guild guild, SignupSession session, long userId, String stored) {
        if (session == null || session.type() == SignupType.QUEUE) return stored;
        var linked = links.getLinksForUser(guild.getIdLong(), userId);
        if (!linked.isEmpty()) return linked.getFirst().rsn();
        Member member = guild.getMemberById(userId);
        return member == null ? "Member " + userId : member.getEffectiveName();
    }

    private <C extends net.dv8tion.jda.api.entities.channel.middleman.GuildChannel> C postableChannel(Guild guild, String rawId, Class<C> type) {
        long channelId = id(rawId);
        C channel = guild.getChannelById(type, channelId);
        if (channel == null) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_text_channel"));
        if (channel instanceof GuildMessageChannel messageChannel && !messageChannel.canTalk()) {
            throw new ApiError(400, Text.of(guild, "dashboard.jonnybot_can_t_post_in_it", channel.getName()));
        }
        return channel;
    }

    DataObject createSignup(Guild guild, Member actor, DataObject body) {
        SignupType type;
        try {
            type = SignupType.valueOf(body.getString("type", "QUEUE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.choose_a_signup_type"));
        }
        String title = body.getString("title", "").strip();
        if (title.isEmpty() || title.length() > 100) throw new ApiError(400, Text.of(guild, "dashboard.the_title_must_be_1_to"));

        Integer max = null;
        if (!body.isNull("max")) {
            int value = body.getInt("max");
            if (value < 1 || value > 500) throw new ApiError(400, Text.of(guild, "dashboard.the_limit_must_be_between_1"));
            max = value;
        }
        TextChannel signupChannel = postableChannel(guild, body.getString("signupChannelId", ""), TextChannel.class);
        TextChannel adminChannel = postableChannel(guild, body.getString("adminChannelId", ""), TextChannel.class);

        switch (type) {
            case QUEUE -> {
                String note = body.getString("note", "").strip();
                if (note.length() > 500) throw new ApiError(400, Text.of(guild, "dashboard.the_notification_message_can_be_at"));
                signups.createQueueSession(guild, signupChannel, adminChannel, title, note.isEmpty() ? null : note, max, actor.getIdLong());
            }
            case GROUP -> signups.createGroupSession(guild, signupChannel, adminChannel, title, actor.getIdLong());
            case SUBMISSION -> {
                List<SubmissionField> fields = new ArrayList<>();
                if (!body.isNull("fields")) {
                    DataArray array = body.getArray("fields");
                    for (int i = 0; i < array.length() && i < 3; i++) {
                        DataObject f = array.getObject(i);
                        String label = f.getString("label", "").strip();
                        if (label.isEmpty() || label.length() > 45) throw new ApiError(400, Text.of(guild, "dashboard.each_question_needs_a_label_of"));
                        fields.add(new SubmissionField(label, SubmissionField.normalizeType(f.getString("type", "TEXT")), f.getBoolean("required", true)));
                    }
                }
                if (fields.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.a_submission_signup_needs_at_least"));
                signups.createSubmissionSession(guild, signupChannel, adminChannel, title, fields, max, actor.getIdLong());
            }
        }
        cache.invalidate("signups");
        log.info("Dashboard: {} created a {} signup '{}'", actor.getId(), type, title);
        return DataObject.empty().put("created", true);
    }

    private SignupSession ownSignup(Guild guild, long signupId) {
        SignupSession session = signups.getSessionById(signupId);
        if (session == null || session.guildId() != guild.getIdLong()) throw new ApiError(404, Text.of(guild, "dashboard.that_signup_doesn_t_exist_any"));
        return session;
    }

    private void refresh(Guild guild, long signupId) {
        cache.invalidate("signups");
        debouncer.run("signup:" + signupId, REFRESH_DELAY_MILLIS, () -> signups.updateMessages(guild, signupId));
    }

    /** One of: pause (toggles), clear, remove (needs {@code userId}), skip, removefirst, pick, delete. */
    DataObject signupAction(Guild guild, Member actor, long signupId, String action, DataObject body) {
        ownSignup(guild, signupId);
        DataObject result = DataObject.empty().put("done", true);

        switch (action) {
            case "pause" -> {
                String status = required(signups.togglePause(signupId), "That signup doesn't exist any more.");
                result.put("paused", !"ACTIVE".equalsIgnoreCase(status));
                refresh(guild, signupId);
            }
            case "clear" -> {
                signups.clear(guild, signupId);
                refresh(guild, signupId);
            }
            case "remove" -> {
                long userId = id(body.getString("userId", ""));
                if (!signups.removeUser(guild, signupId, userId)) throw new ApiError(404, Text.of(guild, "dashboard.that_person_isn_t_on_the"));
                refresh(guild, signupId);
            }
            case "skip" -> {
                if (signups.skip(signupId) == null) throw new ApiError(409, Text.of(guild, "dashboard.nobody_to_skip"));
                refresh(guild, signupId);
            }
            case "removefirst" -> {
                if (signups.remove(signupId) == null) throw new ApiError(409, Text.of(guild, "dashboard.nobody_to_remove"));
                refresh(guild, signupId);
            }
            case "pick" -> {
                SignupEntry winner = signups.pickRandom(signupId);
                if (winner == null) throw new ApiError(409, Text.of(guild, "dashboard.nobody_has_signed_up_yet"));
                SignupSession session = signups.getSessionById(signupId);
                result.put("winner", entryName(guild, session, winner.userId(), winner.username()));
            }
            case "delete" -> {
                signups.deleteSignup(guild, signupId);
                cache.invalidate("signups");
            }
            default -> throw new ApiError(404, Text.of(guild, "dashboard.unknown_action"));
        }
        log.info("Dashboard: {} {} on signup {}", actor.getId(), action, signupId);
        return result;
    }
}
