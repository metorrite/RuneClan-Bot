package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.runeclans.HubFeedListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * The hub feed's poll, signup, group and form entries, turned into Discord work while {@code hub-community} is on (ticket F3): a vote,
 * entry, edit or close made on the website (or by the hub's own sweep) updates the Discord panel within seconds; a group made on a clan site
 * is posted in the server's group finder channel and kept up to date; a form answered on the website opens its ticket, posts its answers
 * and hands out its role. Each becomes one Discord outbox job ({@link HubCommunityJobs}, {@code forms.HubFormJob}), so it runs in the
 * process holding the server's shard, waits out rate limits and respects the dev-bot posting switch. Refreshes for the same panel share a
 * dedupe key, so a burst of votes becomes one edit.
 * <p>
 * The bot's own writes come back through the feed too; refreshing the panel again is harmless (and catches up a vote the hub write queue
 * sent late). Entries about items the bot never showed (a poll made on the website, so no Discord message) are skipped.
 */
@BService
public class HubCommunityFeed implements HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(HubCommunityFeed.class);

    private final HubCommunity community;
    private final HubCommunityMaps maps;
    private final HubPollStore polls;
    private final HubSignupStore signups;
    private final DiscordOutbox outbox;

    public HubCommunityFeed(HubCommunity community, HubCommunityMaps maps, HubPollStore polls, HubSignupStore signups, DiscordOutbox outbox) {
        this.community = community;
        this.maps = maps;
        this.polls = polls;
        this.signups = signups;
        this.outbox = outbox;
    }

    /** One Discord job to queue: kind, server, dedupe key (null for none) and payload. */
    record Job(String kind, long guildId, String dedupe, DataObject payload) {}

    @Override
    public void onHubFeed(String kind, DataObject data) {
        if (kind == null || !community.onHub()) return;
        if (!(kind.startsWith("poll.") || kind.startsWith("signup.") || kind.startsWith("group.") || kind.startsWith("form."))) return;
        try {
            Optional<Job> job = plan(kind, data);
            job.ifPresent(j -> outbox.enqueue(j.kind(), j.guildId(), 0L, j.dedupe(), j.payload()));
        } catch (RuntimeException e) {
            log.warn("Hub {} entry couldn't be passed to Discord: {}", kind, e.toString());
        }
    }

    /** What a feed entry asks of Discord, if anything. Package-private for the tests. */
    Optional<Job> plan(String kind, DataObject entry) {
        DataObject inner = entry.optObject("data").orElse(DataObject.empty());
        UUID id = uuid(entry.getString("id", null));
        if (id == null) return Optional.empty();
        if (kind.startsWith("poll.")) {
            // the poll's id is the entry's id for every poll kind (a vote's item is its poll)
            Optional<HubCommunityMaps.PollRow> row = maps.pollByHubId(id);
            if (row.isEmpty() || row.get().messageId() == null) return Optional.empty();
            polls.forget(id);
            boolean ended = kind.equals("poll.closed") || kind.equals("poll.deleted");
            long local = row.get().localId();
            return Optional.of(new Job(HubCommunityJobs.REFRESH, row.get().guildId(), "hubcommunity:poll:" + local + (ended ? ":ended" : ""),
                    HubCommunityJobs.refresh(HubCommunityJobs.POLL, local, ended)));
        }
        if (kind.startsWith("signup.")) {
            Optional<HubCommunityMaps.SignupRow> row = maps.signupByHubId(id);
            if (row.isEmpty()) return Optional.empty();
            signups.forget(id);
            boolean ended = kind.equals("signup.deleted");
            long local = row.get().localId();
            return Optional.of(new Job(HubCommunityJobs.REFRESH, row.get().guildId(), "hubcommunity:signup:" + local + (ended ? ":ended" : ""),
                    HubCommunityJobs.refresh(HubCommunityJobs.SIGNUP, local, ended)));
        }
        long guildId = guildOf(inner);
        if (guildId == 0) return Optional.empty(); // a clan with no Discord server: nothing to show there
        if (kind.startsWith("group.")) {
            if (!"group".equals(entry.getString("item", "group"))) return Optional.empty(); // the finder's own settings
            if (kind.equals("group.created")) {
                return Optional.of(new Job(HubCommunityJobs.GROUP_POST, guildId, null, HubCommunityJobs.group(id)));
            }
            return Optional.of(new Job(HubCommunityJobs.GROUP_SYNC, guildId, "hubcommunity:group:" + id, HubCommunityJobs.group(id)));
        }
        if (kind.equals("form.submitted")) {
            UUID formId = uuid(inner.getString("formId", null));
            if (formId == null) return Optional.empty();
            Optional<HubCommunityMaps.FormRow> form = maps.form(formId);
            if (form.isEmpty() || form.get().guildId() != guildId) return Optional.empty();
            HubCommunityMaps.FormRow f = form.get();
            if (f.panelId() == null && f.channelId() == null && f.roleId() == null) return Optional.empty();
            DataObject payload = DataObject.empty().put("formId", formId.toString()).put("submissionId", id.toString())
                    .put("discordId", inner.getString("discordId", null)).put("formKind", inner.getString("formKind", "plain"))
                    .put("answers", inner.optArray("answers").orElse(net.dv8tion.jda.api.utils.data.DataArray.empty()));
            return Optional.of(new Job(com.runeclans.bot.forms.HubFormJob.KIND, guildId, null, payload));
        }
        return Optional.empty();
    }

    static long guildOf(DataObject inner) {
        String raw = inner.getString("guildId", null);
        return raw != null && raw.matches("[0-9]{15,20}") ? Long.parseLong(raw) : 0L;
    }

    static UUID uuid(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
