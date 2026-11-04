package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.QuickGroupRepository.Group;
import com.runeclans.bot.events.QuickGroupRepository.Joined;
import com.runeclans.bot.events.QuickGroupRepository.Settings;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The group finder (T-589, "LFG board"): members of a server post a quick group on its clan site ("need 2 for Solak at 20:00", with role slots),
 * others take a slot, and the host or staff close it. Off until a server's owner turns it on (dashboard: Events, Group finder). When the owner also
 * picks a channel, the bot posts each group there and keeps the post up to date, through the Discord job outbox ({@link QuickGroupJobs}), so a
 * website request never waits on Discord. The posts ping nobody.
 * <p>
 * Who may do what is decided by the caller ({@code DirectoryGroupFinderRoutes}): the person must be in the server's Discord, and {@code staff} is
 * the site's own staff rule. Refusals are {@link EventProblem}s in words a member can read.
 */
@BService
public class QuickGroupService {
    private static final Logger log = LoggerFactory.getLogger(QuickGroupService.class);
    /** How often groups past their listing are closed. */
    static final int SWEEP_MINUTES = 5;
    public static final int LISTED = 30;

    /** The member-facing answer while the hub keeps the group finder ({@code hub-community}): the website talks to the hub itself. */
    static final String MOVED = "The group finder is on RuneClans now. Reload the page and try again.";

    private final QuickGroupRepository repository;
    private final DiscordOutbox outbox;
    private final com.runeclans.bot.hubcommunity.HubCommunity community;
    private final com.runeclans.bot.hubcommunity.HubCommunityClient hub;
    private final ScheduledExecutorService lane = BotScheduler.lane("quick-groups");

    public QuickGroupService(QuickGroupRepository repository, DiscordOutbox outbox, com.runeclans.bot.hubcommunity.HubCommunity community,
                             com.runeclans.bot.hubcommunity.HubCommunityClient hub) {
        this.repository = repository;
        this.outbox = outbox;
        this.community = community;
        this.hub = hub;
    }

    /** The hub keeps the groups and the finder's on/off ({@code hub-community}); the bot keeps only the channel it posts in. */
    public boolean onHub() {
        return community.onHub();
    }

    private void notOnHub() {
        if (community.onHub()) throw new EventProblem(409, MOVED);
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        lane.scheduleWithFixedDelay(this::sweep, 1, SWEEP_MINUTES, TimeUnit.MINUTES);
    }

    /** Closes groups whose listing ended, and marks their Discord posts closed (not from a development bot: those posts are the live bot's). */
    void sweep() {
        if (community.onHub()) return; // the hub closes groups past their listing, and the feed updates their posts
        try {
            for (Group group : repository.closeEnded(Instant.now())) {
                if (repository.post(group.id()).isPresent() && !AutoPostRouter.skip("marking group " + group.id() + " closed in Discord")) {
                    outbox.enqueue(QuickGroupJobs.SYNC, group.guildId(), group.hostId(), QuickGroupJobs.syncDedupe(group.id()), QuickGroupJobs.payload(group.id()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("The group finder sweep failed", e);
        }
    }

    public Settings settings(long guildId) {
        Settings bot = repository.settings(guildId);
        if (!community.onHub()) return bot;
        return new Settings(hubEnabled(guildId).orElse(false), bot.channelId());
    }

    /** The hub's on/off for the server's clan; empty when the server has no clan on the hub. */
    private java.util.Optional<Boolean> hubEnabled(long guildId) {
        return hub.find("/v1/clans/" + guildId + "/groups/settings").map(j -> j.getBoolean("enabled", false));
    }

    public List<Group> open(long guildId) {
        if (community.onHub()) {
            List<Group> out = new java.util.ArrayList<>();
            var answer = hub.find("/v1/clans/" + guildId + "/groups?status=OPEN");
            if (answer.isEmpty()) return out;
            var list = answer.get().optArray("groups").orElse(net.dv8tion.jda.api.utils.data.DataArray.empty());
            for (int i = 0; i < list.length() && out.size() < LISTED; i++) out.add(com.runeclans.bot.hubcommunity.HubCommunityJobs.asGroup(list.getObject(i), guildId));
            return out;
        }
        return repository.open(guildId, Instant.now(), LISTED);
    }

    public List<QuickGroupRepository.Member> members(long groupId) {
        notOnHub();
        return repository.members(groupId);
    }

    /**
     * Turns the finder on or off and chooses the channel groups are posted in (null: not posted). Groups already posted stay as they are. On the
     * hub the on/off is the hub's ({@code PUT .../groups/settings}, If-Match) and only the channel stays in the bot's table.
     */
    public void saveSettings(long guildId, Settings settings, long by) {
        if (community.onHub()) {
            var now = hub.find("/v1/clans/" + guildId + "/groups/settings")
                    .orElseThrow(() -> com.runeclans.bot.hubcommunity.HubCommunityProblem.refused(404, "This server has no clan on RuneClans yet, so the group finder can't be switched on."));
            hub.send("PUT", "/v1/clans/" + guildId + "/groups/settings", DataObject.empty().put("enabled", settings.enabled()).put("byDiscordId", Long.toString(by)),
                    now.getLong("rev", 0));
            repository.saveSettings(guildId, new Settings(repository.settings(guildId).enabled(), settings.channelId()), by);
            return;
        }
        repository.saveSettings(guildId, settings, by);
    }

    private Settings requireOn(long guildId) {
        Settings settings = repository.settings(guildId);
        if (!settings.enabled()) throw new EventProblem(409, "This clan hasn't turned the group finder on.");
        return settings;
    }

    private Group requireGroup(long guildId, long groupId) {
        return repository.group(guildId, groupId).orElseThrow(() -> new EventProblem(404, "That group doesn't exist any more."));
    }

    /** Posts a new group for {@code hostId}, and has the bot post it in Discord when the server chose a channel. Answers the group's id. */
    public long create(long guildId, long hostId, DataObject body, Instant now) {
        notOnHub();
        Settings settings = requireOn(guildId);
        QuickGroups.Plan plan;
        try {
            plan = QuickGroups.check(body, now);
        } catch (IllegalArgumentException e) {
            throw new EventProblem(400, e.getMessage());
        }
        int[] open = repository.countOpen(guildId, hostId, now);
        if (open[0] >= QuickGroups.MAX_OPEN_PER_SERVER) throw new EventProblem(409, "There are already " + QuickGroups.MAX_OPEN_PER_SERVER + " open groups. Join one, or try again later.");
        if (open[1] >= QuickGroups.MAX_OPEN_PER_HOST) throw new EventProblem(409, "You already have " + QuickGroups.MAX_OPEN_PER_HOST + " open groups. Close one first.");
        long id = repository.create(guildId, plan, hostId);
        if (settings.channelId() != null) {
            outbox.enqueue(QuickGroupJobs.POST, guildId, hostId, "quickgroup:post:" + id, QuickGroupJobs.payload(id).put("channelId", Long.toString(settings.channelId())));
        }
        return id;
    }

    /** Takes a place in {@code role} (or moves the member to it from their other role). */
    public Joined join(long guildId, long userId, long groupId, String role) {
        notOnHub();
        requireOn(guildId);
        Group group = requireGroup(guildId, groupId);
        if (!group.open()) throw new EventProblem(409, "This group is closed.");
        QuickGroups.Slot slot = QuickGroups.slotNamed(QuickGroups.slotsOf(group.config()), role == null ? "" : role);
        if (slot == null) throw new EventProblem(400, "Choose one of the group's roles.");
        Joined joined = repository.join(guildId, groupId, userId, slot);
        switch (joined) {
            case FULL -> throw new EventProblem(409, slot.role() + " is full. Choose another role.");
            case CLOSED -> throw new EventProblem(409, "This group is closed.");
            case JOINED, SWITCHED -> changed(group, userId);
            case ALREADY -> {
            }
        }
        return joined;
    }

    /** Gives up the member's place. */
    public void leave(long guildId, long userId, long groupId) {
        notOnHub();
        Group group = requireGroup(guildId, groupId);
        if (!group.open()) throw new EventProblem(409, "This group is closed.");
        if (repository.leave(groupId, userId)) changed(group, userId);
    }

    /** Closes a group: only its host or the site's staff. Works while the finder is off too, so staff can tidy up after switching it off. */
    public void close(long guildId, long userId, long groupId, boolean staff) {
        notOnHub();
        Group group = requireGroup(guildId, groupId);
        if (group.hostId() != userId && !staff) throw new EventProblem(403, "Only the person who posted this group, or staff, can close it.");
        if (repository.close(guildId, groupId)) changed(group, userId);
    }

    /** Brings the group's Discord post (if it has one, or one is on its way) up to date: the newest change wins. */
    private void changed(Group group, long actorId) {
        if (repository.post(group.id()).isEmpty() && repository.settings(group.guildId()).channelId() == null) return;
        outbox.enqueue(QuickGroupJobs.SYNC, group.guildId(), actorId, QuickGroupJobs.syncDedupe(group.id()), QuickGroupJobs.payload(group.id()));
    }
}
