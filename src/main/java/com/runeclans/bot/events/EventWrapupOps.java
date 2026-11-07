package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventSpace.Kind;
import com.runeclans.bot.events.EventSpace.Overwrite;
import com.runeclans.bot.events.EventSpace.Principal;
import com.runeclans.bot.events.EventSpaceOps.Job;
import com.runeclans.bot.events.EventSpaceRepository.ChannelRow;
import com.runeclans.bot.events.EventSpaceRepository.RoleRow;
import com.runeclans.bot.events.EventWrapup.Msg;
import com.runeclans.bot.events.EventWrapup.Step;
import com.runeclans.bot.i18n.Text;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Wraps an event up on Discord once it is over: makes its channels read-only, takes its roles back, archives, compresses or deletes its channels, and
 * writes transcripts. Each is an action an organiser can start from the dashboard; the first two (and, if the event asks for it, the channels
 * step) also happen by themselves on the event's own schedule ({@link EventWrapup#due}). They run as the event's one background job at a time, shared with
 * the setup ({@link EventSpaceOps#start}), and only ever change roles and channels the bot made (channels an organiser chose to use are left alone).
 * Nothing here is undone by itself: deleting is permanent, which is why the dashboard asks first and why export and archive exist.
 */
@BService
public class EventWrapupOps {
    private static final Logger log = LoggerFactory.getLogger(EventWrapupOps.class);
    private static final long SWEEP_MINUTES = 15;
    private static final int MAX_MESSAGES_PER_CHANNEL = 20_000;
    private static final int PART_CHARS = 1_500_000;
    private static final int CATEGORY_LIMIT = 50;

    /** The hub's record of an event's wrap-up: when it finished, which steps are done (and when the rest are due), how the channels were dealt with. */
    public record View(EventWrapup.State state, Map<Step, Instant> dueAt, String channelsHow, Instant exportedAt) {
        static View of(DataObject json) {
            DataObject steps = json.optObject("steps").orElse(DataObject.empty());
            Map<Step, Instant> due = new java.util.EnumMap<>(Step.class);
            Map<Step, Instant> done = new java.util.EnumMap<>(Step.class);
            for (Step step : Step.values()) {
                DataObject one = steps.optObject(step.name()).orElse(DataObject.empty());
                done.put(step, at(one, "doneAt"));
                Instant d = at(one, "dueAt");
                if (d != null) due.put(step, d);
            }
            return new View(new EventWrapup.State(at(json, "finishedAt"), done.get(Step.LOCK), done.get(Step.ROLES), done.get(Step.CHANNELS)), due, json.getString("channelsHow", null), at(json, "exportedAt"));
        }

        private static Instant at(DataObject json, String key) {
            if (!json.hasKey(key) || json.isNull(key)) return null;
            try {
                return Instant.parse(json.getString(key));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private final HubEvents events;
    private final EventSpaceRepository space;
    private final EventSpaceOps ops;
    private final EventSpaces spaces;
    private final EventChanges changes;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, (ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "event-wrapup");
        thread.setDaemon(true);
        return thread;
    });
    private volatile JDA jda;

    public EventWrapupOps(HubEvents events, EventSpaceRepository space, EventSpaceOps ops, EventSpaces spaces, EventChanges changes) {
        this.events = events;
        this.space = space;
        this.ops = ops;
        this.spaces = spaces;
        this.changes = changes;
        changes.subscribe(change -> {
            if (change.kind() == EventChanges.Kind.STAGE) executor.execute(() -> onStage(change.guildId(), change.eventId()));
        });
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        executor.scheduleWithFixedDelay(this::sweep, 2, SWEEP_MINUTES, TimeUnit.MINUTES);
        log.info("Event wrap-up is ready: finished events are tidied up on their own schedule every {} minutes.", SWEEP_MINUTES);
    }

    // ---------- noticing the end ----------

    private void onStage(long guildId, long eventId) {
        try {
            Event event = events.eventById(eventId).orElse(null);
            if (event == null || event.status() == Status.DONE || event.status() == Status.CANCELLED) return; // the hub notes the end itself
            // reopened: the channels the bot locked open again (unless switched off); the hub starts the wrap-up record over
            if (EventWrapup.unlocksOnReopen(events.settingsOf(event).wrapup(), event.status(), view(eventId).state())) unlockReopened(guildId, event);
        } catch (RuntimeException e) {
            log.warn("Could not note the stage of event {} for its wrap-up", eventId, e);
        }
    }

    private void unlockReopened(long guildId, Event event) {
        JDA client = jda;
        Guild guild = client == null ? null : Shards.guild(client, guildId);
        if (guild == null || ownedChannels(event.id()).isEmpty()) return;
        try {
            startUnlock(guild, event);
            log.info("Event {} was reopened, so its channels are being unlocked", event.id());
        } catch (EventProblem busy) {
            log.info("Event {} was reopened but its channels couldn't be unlocked now: {}", event.id(), busy.getMessage());
        }
    }

    // ---------- the schedule ----------

    private void sweep() {
        sweep(jda);
    }

    /**
     * Runs the wrap-up steps that are due. Skipped while the bot is not live (T-187): it locks and deletes channels, takes roles back and posts the
     * archived transcripts as files in the real server. An organiser starting a step from the dashboard is not affected.
     */
    void sweep(JDA client) {
        if (client == null || AutoPostRouter.skipSweep("event wrap-up")) return;
        for (Guild guild : Shards.guilds(client)) {
            try {
                for (long eventId : space.eventsWithSpace(guild.getIdLong())) runDue(guild, eventId);
            } catch (RuntimeException e) {
                log.warn("Could not wrap up the finished events of {}", guild.getId(), e);
            }
        }
    }

    private void runDue(Guild guild, long eventId) {
        Event event = events.eventById(eventId).filter(e -> e.guildId() == guild.getIdLong()).orElse(null);
        if (event == null || (event.status() != Status.DONE && event.status() != Status.CANCELLED)) return;
        EventWrapup.Setting setting = events.settingsOf(event).wrapup();
        List<Step> due = EventWrapup.due(setting, event.status(), !ownedChannels(eventId).isEmpty(), !space.roles(eventId).isEmpty(), view(eventId).state(), Instant.now());
        for (Step step : due) {
            try {
                switch (step) {
                    case LOCK -> startLock(guild, event);
                    case ROLES -> startRoles(guild, event);
                    case CHANNELS -> {
                        if (setting.channelsAction() == EventWrapup.ChannelsAction.DELETE) startDelete(guild, event);
                        else startArchive(guild, event);
                    }
                }
                return; // one step at a time: the next one is picked up by the next sweep
            } catch (EventProblem busy) {
                return; // a job is running for this event; try again next time
            }
        }
    }

    // ---------- what there is ----------

    /** The hub's record of the event's wrap-up (the bot does the Discord steps, the hub keeps which are done and when the rest are due). */
    public View view(long eventId) {
        return View.of(events.wrapupById(eventId));
    }

    private void done(Event event, Step step, String how) {
        events.recordWrapup(event.guildId(), event.id(), step.name().toLowerCase(java.util.Locale.ROOT), how);
    }

    /** The channels the bot made (not the categories, and not channels an organiser chose to use). */
    List<ChannelRow> ownedChannels(long eventId) {
        return space.channels(eventId).stream().filter(c -> c.owned() && !EventSpace.isCategoryKey(c.key())).toList();
    }

    /** What each wrap-up action would touch, for the dashboard to show before anything is done. */
    public record Counts(int channels, int categories, int roles, int chosenChannels) {}

    public Counts counts(long eventId) {
        List<ChannelRow> rows = space.channels(eventId);
        return new Counts((int) rows.stream().filter(c -> c.owned() && !EventSpace.isCategoryKey(c.key())).count(), (int) rows.stream().filter(c -> c.owned() && EventSpace.isCategoryKey(c.key())).count(),
                space.roles(eventId).size(), (int) rows.stream().filter(c -> !c.owned()).count());
    }

    // ---------- locking ----------

    /** Makes the event's channels read-only for the players. */
    public Job startLock(Guild guild, Event event) {
        requireOver(event);
        if (ownedChannels(event.id()).isEmpty()) throw new EventProblem(409, Text.of(guild, "events.problem.there_are_no_channels_jonnybot_made"));
        return ops.start(event.id(), "lock", job -> {
            int n = applyAccess(guild, event, true, job);
            done(event, Step.LOCK, null);
            job.finish("Locked " + n + (n == 1 ? " channel" : " channels") + ": players can read them but not write.");
        });
    }

    /** Lets the players write in the channels again (for an event that was reopened). */
    public Job startUnlock(Guild guild, Event event) {
        if (ownedChannels(event.id()).isEmpty()) throw new EventProblem(409, Text.of(guild, "events.problem.there_are_no_channels_jonnybot_made"));
        return ops.start(event.id(), "unlock", job -> {
            int n = applyAccess(guild, event, false, job);
            job.finish("Unlocked " + n + (n == 1 ? " channel." : " channels."));
        });
    }

    private void requireOver(Event event) {
        if (event.status() != Status.DONE && event.status() != Status.CANCELLED) throw new EventProblem(409, Text.of(event.guildId(), "events.problem.the_event_isn_t_over_yet"));
    }

    private Function<Principal, List<Long>> idsOf(Guild guild, long eventId) {
        Map<String, Long> roleIds = new HashMap<>();
        space.roles(eventId).forEach(r -> roleIds.put(r.key(), r.roleId()));
        List<Long> staff = spaces.staffRoles(guild).stream().map(Role::getIdLong).toList();
        long everyone = guild.getPublicRole().getIdLong();
        long bot = guild.getSelfMember().getIdLong();
        return who -> switch (who.who()) {
            case EVERYONE -> List.of(everyone);
            case STAFF -> staff;
            case ORGANISERS -> roleIds.containsKey(EventSpace.ORGANISER_ROLE) ? List.of(roleIds.get(EventSpace.ORGANISER_ROLE)) : List.of();
            case PARTICIPANTS -> roleIds.containsKey(EventSpace.PARTICIPANT_ROLE) ? List.of(roleIds.get(EventSpace.PARTICIPANT_ROLE)) : List.of();
            case TEAM -> roleIds.containsKey(EventSpace.teamKey(who.ref())) ? List.of(roleIds.get(EventSpace.teamKey(who.ref()))) : List.of();
            case PLAYER -> List.of(who.ref());
            case BOT -> List.of(bot);
        };
    }

    private static long refOf(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) return 0;
        try {
            return Long.parseLong(key.substring(colon + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int applyAccess(Guild guild, Event event, boolean locked, Job job) {
        List<ChannelRow> rows = ownedChannels(event.id());
        job.total(rows.size());
        Function<Principal, List<Long>> idsOf = idsOf(guild, event.id());
        int done = 0;
        for (ChannelRow row : rows) {
            GuildChannel channel = guild.getGuildChannelById(row.channelId());
            if (!(channel instanceof IPermissionContainer container)) {
                job.step("#" + row.name() + " is gone.");
                continue;
            }
            Kind kind = EventSpace.keyKind(row.key());
            var grants = EventSpace.access(kind, refOf(row.key()));
            for (Overwrite o : EventSpace.overwrites(locked ? EventWrapup.lockedAccess(grants) : grants, idsOf)) putOverwrite(guild, container, o);
            done++;
            job.step((locked ? "Locked #" : "Unlocked #") + row.name() + ".");
        }
        return done;
    }

    private void putOverwrite(Guild guild, IPermissionContainer container, Overwrite o) {
        if (o.isRole()) {
            Role role = guild.getRoleById(o.id());
            if (role != null) container.upsertPermissionOverride(role).setPermissions(o.allow(), o.deny()).reason("Wrapping up an event").complete();
        } else {
            Member member = member(guild, o.id());
            if (member != null) container.upsertPermissionOverride(member).setPermissions(o.allow(), o.deny()).reason("Wrapping up an event").complete();
        }
    }

    private static Member member(Guild guild, long id) {
        Member cached = guild.getMemberById(id);
        if (cached != null) return cached;
        try {
            return guild.retrieveMemberById(id).complete();
        } catch (RuntimeException e) {
            return null; // left the server
        }
    }

    // ---------- the roles ----------

    /** Takes back (deletes) the roles the bot made for the event, so the players lose what they could see through them. */
    public Job startRoles(Guild guild, Event event) {
        requireOver(event);
        if (space.roles(event.id()).isEmpty()) throw new EventProblem(409, Text.of(guild, "events.problem.there_are_no_roles_jonnybot_made"));
        return ops.start(event.id(), "roles", job -> {
            List<RoleRow> roles = space.roles(event.id());
            job.total(roles.size());
            for (RoleRow r : roles) {
                job.say("Removing the role " + r.name() + "…");
                ops.deleteRole(guild, event.id(), r.key(), r.roleId());
                job.step("Removed the role " + r.name() + ".");
            }
            done(event, Step.ROLES, null);
            changes.publish(EventChanges.Kind.SETUP, event.guildId(), event.id());
            job.finish("Took back " + roles.size() + (roles.size() == 1 ? " role." : " roles."));
        });
    }

    // ---------- deleting ----------

    /** Deletes everything the bot made for the event (the same as taking the space down) and notes the channels as dealt with. */
    public Job startDelete(Guild guild, Event event) {
        requireOver(event);
        Job job = ops.startRemoval(guild, event);
        executor.execute(() -> {
            try {
                for (int i = 0; i < 600 && job.running(); i++) Thread.sleep(1000);
                if ("done".equals(job.state())) {
                    done(event, Step.CHANNELS, "deleted");
                    done(event, Step.ROLES, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return job;
    }

    // ---------- archiving ----------

    /** Moves the event's channels into an archive category where only staff can see them, and deletes the event's own categories. */
    public Job startArchive(Guild guild, Event event) {
        requireOver(event);
        if (ownedChannels(event.id()).isEmpty()) throw new EventProblem(409, Text.of(guild, "events.problem.there_are_no_channels_jonnybot_made"));
        return ops.start(event.id(), "archive", job -> {
            List<ChannelRow> rows = ownedChannels(event.id());
            job.total(rows.size() + 1);
            EventWrapup.Setting setting = events.settingsOf(event).wrapup();
            Function<Principal, List<Long>> idsOf = idsOf(guild, event.id());
            int moved = 0;
            Category archive = null;
            int inArchive = 0;
            for (ChannelRow row : rows) {
                GuildChannel channel = guild.getGuildChannelById(row.channelId());
                if (!(channel instanceof TextChannel text)) {
                    space.deleteChannel(event.id(), row.key());
                    job.step("#" + row.name() + " is gone.");
                    continue;
                }
                if (archive == null || inArchive >= CATEGORY_LIMIT - 1) {
                    archive = archiveCategory(guild, setting.archiveCategoryId(), idsOf, archive == null ? 0 : 1);
                    inArchive = archive.getChannels().size();
                }
                job.say("Archiving #" + row.name() + "…");
                text.getManager().setParent(archive).reason("Archiving an event").complete();
                for (PermissionOverride existing : new ArrayList<>(text.getPermissionOverrides())) existing.delete().complete();
                for (Overwrite o : EventSpace.overwrites(EventWrapup.archivedAccess(), idsOf)) putOverwrite(guild, text, o);
                space.deleteChannel(event.id(), row.key());
                moved++;
                inArchive++;
                job.step("Archived #" + row.name() + ".");
            }
            for (ChannelRow cat : space.channels(event.id())) {
                if (cat.owned() && EventSpace.isCategoryKey(cat.key())) ops.deleteChannel(guild, event.id(), cat.key(), cat.channelId());
            }
            done(event, Step.CHANNELS, "archived");
            changes.publish(EventChanges.Kind.SETUP, event.guildId(), event.id());
            job.step("Done.");
            job.finish("Archived " + moved + (moved == 1 ? " channel" : " channels") + " in " + archive.getName() + ". Only staff can see them now.");
        });
    }

    /** The category archived channels go in: the one the event names, else "Event archive" (a numbered one when it fills). Created staff-only when it doesn't exist. */
    private Category archiveCategory(Guild guild, Long chosen, Function<Principal, List<Long>> idsOf, int skipFull) {
        if (chosen != null) {
            Category category = guild.getCategoryById(chosen);
            if (category != null && category.getChannels().size() < CATEGORY_LIMIT) return category;
        }
        for (int n = 1; n < 100; n++) {
            String name = n == 1 ? "Event archive" : "Event archive " + n;
            List<Category> found = guild.getCategoriesByName(name, true);
            if (!found.isEmpty()) {
                if (found.get(0).getChannels().size() < CATEGORY_LIMIT) return found.get(0);
                continue;
            }
            var action = guild.createCategory(name);
            for (Overwrite o : EventSpace.overwrites(EventWrapup.archivedAccess(), idsOf)) action = o.isRole() ? action.addRolePermissionOverride(o.id(), o.allow(), o.deny()) : action.addMemberPermissionOverride(o.id(), o.allow(), o.deny());
            return action.reason("Archive for finished events").complete();
        }
        throw new EventProblem(409, Text.of(guild, "events.problem.there_are_too_many_archive_categories"));
    }

    // ---------- reading messages ----------

    private List<Msg> history(TextChannel channel) {
        List<Message> newestFirst = new ArrayList<>();
        channel.getIterableHistory().cache(false).forEachRemaining(m -> {
            newestFirst.add(m);
            return newestFirst.size() < MAX_MESSAGES_PER_CHANNEL;
        });
        List<Msg> oldestFirst = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) oldestFirst.add(msgOf(channel.getName(), newestFirst.get(i)));
        return oldestFirst;
    }

    private static Msg msgOf(String channelName, Message m) {
        StringBuilder text = new StringBuilder(m.getContentRaw());
        for (var embed : m.getEmbeds()) {
            if (embed.getTitle() != null) text.append(text.length() > 0 ? "\n" : "").append(embed.getTitle());
            if (embed.getDescription() != null) text.append(text.length() > 0 ? "\n" : "").append(embed.getDescription());
        }
        String author = m.getMember() != null ? m.getMember().getEffectiveName() : m.getAuthor().getName();
        return new Msg(m.getIdLong(), channelName, m.getAuthor().getIdLong(), author, m.getAuthor().isBot(), m.getTimeCreated().toInstant(), text.toString(),
                m.getAttachments().stream().map(Message.Attachment::getUrl).toList());
    }

    // ---------- compressing ----------

    /**
     * Writes each private channel's messages as a transcript file into one new staff-only archive channel, so a hundred player channels become one. With
     * {@code deleteOriginals} the channels are deleted once their transcript is safely posted; otherwise they stay as they are.
     * {@code all} takes every channel the bot made, otherwise only the players' own and teams' channels.
     */
    public Job startCompress(Guild guild, Event event, boolean all, boolean deleteOriginals) {
        requireOver(event);
        List<ChannelRow> chosen = compressible(event.id(), all);
        if (chosen.isEmpty()) throw new EventProblem(409, all ? Text.of(guild, "events.problem.there_are_no_channels_jonnybot_made") : Text.of(guild, "events.problem.there_are_no_player_or_team"));
        return ops.start(event.id(), "compress", job -> {
            job.total(chosen.size() + 1);
            EventWrapup.Setting setting = events.settingsOf(event).wrapup();
            Function<Principal, List<Long>> idsOf = idsOf(guild, event.id());
            Category category = archiveCategory(guild, setting.archiveCategoryId(), idsOf, 0);
            var action = guild.createTextChannel(EventWrapup.safeName(event.name()) + "-archive", category).setTopic("Transcripts of " + event.name() + "'s channels");
            for (Overwrite o : EventSpace.overwrites(EventWrapup.archivedAccess(), idsOf)) action = o.isRole() ? action.addRolePermissionOverride(o.id(), o.allow(), o.deny()) : action.addMemberPermissionOverride(o.id(), o.allow(), o.deny());
            TextChannel target = action.reason("Transcripts of an event's channels").complete();
            job.step("Made #" + target.getName() + ".");

            Set<String> reviewNames = reviewChannelNames(guild, event);
            List<Msg> all1 = new ArrayList<>();
            Map<Long, List<Msg>> byChannel = new LinkedHashMap<>();
            for (ChannelRow row : chosen) {
                GuildChannel channel = guild.getGuildChannelById(row.channelId());
                if (!(channel instanceof TextChannel text)) continue;
                job.say("Reading #" + row.name() + "…");
                List<Msg> messages = history(text);
                byChannel.put(row.channelId(), messages);
                all1.addAll(messages);
            }
            // a receipt and its review message count once; the review message wins when both are in the set
            Set<Long> kept = new HashSet<>();
            Map<Long, Msg> keptById = new HashMap<>();
            for (Msg m : EventWrapup.dedupe(all1, reviewNames).messages()) {
                kept.add(m.id());
                keptById.put(m.id(), m);
            }
            int posted = 0;
            List<ChannelRow> postedRows = new ArrayList<>();
            for (ChannelRow row : chosen) {
                List<Msg> messages = byChannel.get(row.channelId());
                if (messages == null) continue;
                List<Msg> tidy = messages.stream().filter(m -> kept.contains(m.id()) || !isReceipt(m)).map(m -> keptById.getOrDefault(m.id(), m)).toList();
                postTranscript(target, row.name(), tidy);
                posted++;
                postedRows.add(row);
                job.step("Wrote the transcript of #" + row.name() + ".");
            }
            int deleted = 0;
            if (deleteOriginals) {
                for (ChannelRow row : postedRows) {
                    ops.deleteChannel(guild, event.id(), row.key(), row.channelId());
                    deleted++;
                }
            }
            done(event, Step.CHANNELS, deleteOriginals && all ? "compressed" : "archived");
            changes.publish(EventChanges.Kind.SETUP, event.guildId(), event.id());
            job.finish("Wrote " + posted + (posted == 1 ? " transcript" : " transcripts") + " into #" + target.getName() + (deleteOriginals ? " and deleted " + deleted + " original channels." : ". The original channels are still there."));
        });
    }

    private static boolean isReceipt(Msg m) {
        return m.bot() && m.content().matches("(?s).*(submission|verification) #\\d+.*");
    }

    /** The channels compressing would act on: every channel the bot made, or just the players' and teams' own ones. */
    List<ChannelRow> compressible(long eventId, boolean all) {
        return ownedChannels(eventId).stream().filter(c -> all || c.key().startsWith("player:") || c.key().startsWith("team:")).toList();
    }

    /** How many channels compressing only the players' and teams' own ones would take. */
    public int compressibleCount(long eventId) {
        return compressible(eventId, false).size();
    }

    private Set<String> reviewChannelNames(Guild guild, Event event) {
        Set<String> names = new HashSet<>();
        Long id = events.settingsOf(event).reviewChannelId();
        if (id != null) {
            GuildChannel review = guild.getGuildChannelById(id);
            if (review != null) names.add(review.getName());
        }
        return names;
    }

    private void postTranscript(TextChannel target, String name, List<Msg> messages) {
        String text = EventWrapup.transcript("#" + name, messages);
        List<String> parts = new ArrayList<>();
        for (int from = 0; from < Math.max(1, text.length()); from += PART_CHARS) parts.add(text.substring(from, Math.min(text.length(), from + PART_CHARS)));
        for (int i = 0; i < parts.size(); i++) {
            String file = EventWrapup.safeName(name) + (parts.size() > 1 ? "-part-" + (i + 1) : "") + ".txt";
            String label = "Transcript of **#" + name + "** · " + messages.size() + (messages.size() == 1 ? " message" : " messages") + (parts.size() > 1 ? " · part " + (i + 1) + " of " + parts.size() : "");
            target.sendMessage(label).addFiles(FileUpload.fromData(parts.get(i).getBytes(StandardCharsets.UTF_8), file)).complete();
        }
    }
}
