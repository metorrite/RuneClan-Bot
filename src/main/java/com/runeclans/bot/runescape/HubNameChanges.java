package com.runeclans.bot.runescape;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.runeclans.HubFeedListener;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.tracking.ClassifiedEntry;
import com.runeclans.bot.tracking.TrackingEventRouter;
import com.runeclans.bot.tracking.TrackingGroup;
import com.runeclans.bot.tracking.TrackingIconCatalog;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The bot follows the RuneClans hub's name changes (T-1160). The hub is the only place that spots a player's new name and links it to the old
 * one (by itself when it is sure, else a Site Admin or RuneClan Admin decides); the bot no longer spots renames itself and never asks Jagex.
 * <ul>
 *   <li>{@code player.renamed} on the hub feed: the bot's own rows move to the new name ({@link HubNameChangeStore}) in the servers whose clan
 *       the change belongs to, each such server's joins and leaves channel gets one "X is now Y" line (never a leave and a join), and the
 *       linked player keeps their Member role and has the old name in their nickname swapped for the new one (through the Discord job
 *       outbox).</li>
 *   <li>{@code player.rename_undone}: the rows go back, and so does the nickname; the next roster sync then reports the leave and the join.</li>
 *   <li>Admin decisions in Discord use the hub's routes ({@link #changes}, {@link #decide}, {@link #undo}), the same ones the website uses.</li>
 * </ul>
 * A change delivered twice does nothing twice (the hub's change id is remembered). Work runs on its own lane, never the feed's. A change
 * the bot couldn't follow (the hub didn't answer a lookup, the database failed) is recorded as nothing, tried again after 1, 5 and 15
 * minutes, and then by the hourly catch-up, which reads the last 48 hours of linked and undone changes of every clan the bot keeps. The
 * catch-up also reads rejected changes: their old name really left, so the roles the roster sync held for it are released.
 */
@BService
public class HubNameChanges implements HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(HubNameChanges.class);
    public static final String KIND_RENAMED = "player.renamed";
    public static final String KIND_UNDONE = "player.rename_undone";

    /** Waits before trying a feed entry that failed again (the hourly catch-up is the backstop after these). */
    static final List<Duration> RETRY_AFTER = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));
    static final Duration CATCH_UP_EVERY = Duration.ofHours(1);

    private final Follower follower;
    private final LiveDiscord discord;
    private final RuneClansHubClient hub;
    private final HubNameChangeReads reads;
    private final GuildSettingsService settings;
    private final PlayerLinkService links;
    private final VerificationRoleSyncService roleSync;
    private final ScheduledExecutorService lane = BotScheduler.lane("hub-name-changes");

    public HubNameChanges(HubNameChangeStore store, RuneClansHubClient hub, HubNameChangeReads reads, GuildSettingsService settings, ClanSyncService clanSync,
                          TrackingEventRouter router, TrackingIconCatalog icons, VerificationRoleSyncService roleSync, DiscordOutbox outbox,
                          PlayerLinkService links) {
        this.hub = hub;
        this.reads = reads;
        this.settings = settings;
        this.links = links;
        this.roleSync = roleSync;
        this.discord = new LiveDiscord(router, icons, roleSync, outbox, links);
        this.follower = new Follower(store, guildId -> settings.getEffective(guildId).clanName(), reads, clanSync::withRosterLock, discord);
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        discord.jda = event.getJda();
        if (!hub.enabled()) return;
        lane.scheduleWithFixedDelay(() -> {
            try {
                follower.catchUp(Instant.now());
            } catch (RuntimeException e) {
                log.warn("The name change catch-up didn't run this time ({}); next hour.", e.getMessage());
            }
        }, 2 * 60, CATCH_UP_EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void onHubFeed(String kind, DataObject data) {
        if (data == null || !(KIND_RENAMED.equals(kind) || KIND_UNDONE.equals(kind))) return;
        lane.execute(() -> attempt(kind, data, 0));
    }

    /** One try at a feed entry; a failure is tried again after {@link #RETRY_AFTER}, then left to the hourly catch-up. */
    private void attempt(String kind, DataObject data, int tries) {
        try {
            follower.handle(kind, data);
        } catch (RuntimeException e) {
            String change = data.hasKey("changeId") ? String.valueOf(data.get("changeId")) : "?";
            if (tries < RETRY_AFTER.size()) {
                log.error("Following hub name change {} ({}) failed; trying again in {}", change, kind, RETRY_AFTER.get(tries), e);
                lane.schedule(() -> attempt(kind, data, tries + 1), RETRY_AFTER.get(tries).toSeconds(), TimeUnit.SECONDS);
            } else {
                log.error("Following hub name change {} ({}) failed {} times; the hourly catch-up will try it again", change, kind, tries + 1, e);
            }
        }
    }

    /**
     * After a reject from Discord: the roster sync left the old name's linked player their roles while the change waited
     * ({@link ClanSyncService}); the old name has really left, so the player goes back to Guest (unless still in the clan another way).
     */
    public void afterReject(Guild guild, Change change) {
        try {
            PlayerLink link = links.getLinkForRsn(guild.getIdLong(), change.from());
            if (link != null) roleSync.syncLeftClan(guild, link.discordUserId());
        } catch (RuntimeException e) {
            log.warn("Couldn't update roles after rejecting name change {}: {}", change.id(), e.toString());
        }
    }

    // ---------------------------------------------------------------- admin decisions (the hub's routes)

    /** One name change as the hub lists it, with what the Discord card shows. */
    public record Change(long id, String from, String to, String confidence, String basis, String status, boolean canUndo) {
        static Change of(DataObject j) {
            return new Change(j.getLong("id"), j.getString("from", "?"), j.getString("to", "?"), j.getString("confidence", ""),
                    j.getString("basis", ""), j.getString("status", ""), j.getBoolean("canUndo", false));
        }
    }

    /** What a hub call came back with: the changes or change, or a plain reason it didn't work. */
    public record Result<T>(T value, String problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    /** This server's clan on the hub, if its clan is registered there. */
    public Optional<String> clanIdOf(long guildId) {
        return reads.clanId(settings.getEffective(guildId).clanName());
    }

    /** The hub's name changes for this server's clan with {@code status} ({@code open} = waiting for an admin, {@code linked}), newest first. */
    public Result<List<Change>> changes(long guildId, String status) {
        Optional<String> clan;
        try {
            clan = clanIdOf(guildId);
        } catch (HubNameChangeReads.Unanswered e) {
            return new Result<>(null, problem(null));
        }
        if (clan.isEmpty()) return new Result<>(null, "This server's clan isn't on RuneClans yet, so there are no name changes to show.");
        RuneClansHubClient.Answer a = hub.call("GET", "/v1/clans/" + clan.get() + "/name-changes?status=" + status, null);
        if (a == null || !a.ok() || a.json() == null) return new Result<>(null, problem(a));
        List<Change> out = new ArrayList<>();
        DataArray rows = a.json().optArray("changes").orElse(DataArray.empty());
        for (int i = 0; i < rows.length(); i++) out.add(Change.of(rows.getObject(i)));
        return new Result<>(out, null);
    }

    /** Links ({@code link}) or rejects ({@code reject}) a waiting change for this server's clan, as {@code bot:<discord user id>}. */
    public Result<Change> decide(long guildId, long changeId, String decision, long actorId) {
        Optional<String> clan;
        try {
            clan = clanIdOf(guildId);
        } catch (HubNameChangeReads.Unanswered e) {
            return new Result<>(null, problem(null));
        }
        if (clan.isEmpty()) return new Result<>(null, "This server's clan isn't on RuneClans yet.");
        String body = DataObject.empty().put("decision", decision).put("decidedBy", decidedBy(actorId)).toString();
        RuneClansHubClient.Answer a = hub.call("PUT", "/v1/clans/" + clan.get() + "/name-changes/" + changeId, body);
        if (a == null || !a.ok() || a.json() == null) return new Result<>(null, problem(a));
        return new Result<>(Change.of(a.json()), null);
    }

    /** Undoes a linked change for this server's clan (allowed for 30 days after the link), as {@code bot:<discord user id>}. */
    public Result<Change> undo(long guildId, long changeId, long actorId) {
        Optional<String> clan;
        try {
            clan = clanIdOf(guildId);
        } catch (HubNameChangeReads.Unanswered e) {
            return new Result<>(null, problem(null));
        }
        if (clan.isEmpty()) return new Result<>(null, "This server's clan isn't on RuneClans yet.");
        String body = DataObject.empty().put("decidedBy", decidedBy(actorId)).toString();
        RuneClansHubClient.Answer a = hub.call("POST", "/v1/clans/" + clan.get() + "/name-changes/" + changeId + "/undo", body);
        if (a == null || !a.ok() || a.json() == null) return new Result<>(null, problem(a));
        return new Result<>(Change.of(a.json()), null);
    }

    static String decidedBy(long discordUserId) {
        return "bot:" + discordUserId;
    }

    /** The hub's answer in plain words for the admin. */
    static String problem(RuneClansHubClient.Answer a) {
        if (a == null) return "RuneClans didn't answer. Try again in a minute.";
        String code = a.json() == null ? "" : a.json().getString("error", "");
        return switch (code) {
            case "already_decided" -> "Someone already decided this one.";
            case "rename_stale" -> "The clan list no longer fits this change, so it can't be linked.";
            case "undo_expired" -> "This link is more than 30 days old, so it can't be undone any more.";
            case "not_linked" -> "Only a linked name change can be undone.";
            case "name_taken" -> "The old name belongs to another player now, so this can't be undone.";
            case "renamed_again" -> "This player changed name again since. Undo the newer change first.";
            default -> a.status() == 404 ? "That name change isn't there any more." : "RuneClans couldn't do that (HTTP " + a.status() + "). Try again in a minute.";
        };
    }

    // ---------------------------------------------------------------- following the feed

    /** What the follower does in Discord: apart from the service so tests can watch it. */
    interface Discord {
        /** Posts one line to the server's joins and leaves channel (nothing for a bot-less site, or with posting off). */
        void announce(long guildId, String from, String to);

        /** Gives the linked player the roles their (renamed) account's clan membership says. */
        void followRoles(long guildId, long discordUserId, String rsn);

        /** Swaps {@code from} for {@code to} in the member's nickname, if it has it. */
        void followNickname(long guildId, long discordUserId, String from, String to);

        /**
         * A change from {@code oldRsn} was rejected: the roster sync held its linked player's roles while it waited, and the name really left,
         * so the player goes back to Guest (unless still in the clan on another account).
         */
        void releaseRoles(long guildId, String oldRsn);
    }

    /** The feed-following logic, apart from the framework's one-constructor service so tests can drive it. */
    static final class Follower {
        /** How far back the hourly catch-up looks for links and undos the feed didn't bring (or that failed every try). */
        static final Duration CATCH_UP_WINDOW = Duration.ofHours(48);

        private final HubNameChangeStore store;
        private final Function<Long, String> clanNameOf;
        private final HubNameChangeReads reads;
        private final Function<Supplier<HubNameChangeStore.Applied>, HubNameChangeStore.Applied> rosterLock;
        private final Discord discord;

        Follower(HubNameChangeStore store, Function<Long, String> clanNameOf, HubNameChangeReads reads,
                 Function<Supplier<HubNameChangeStore.Applied>, HubNameChangeStore.Applied> rosterLock, Discord discord) {
            this.store = store;
            this.clanNameOf = clanNameOf;
            this.reads = reads;
            this.rosterLock = rosterLock;
            this.discord = discord;
        }

        /** Follows one feed entry. Throws when it couldn't (the hub didn't answer a lookup, or the database failed): nothing is kept then. */
        void handle(String kind, DataObject data) {
            if (!data.hasKey("changeId") || data.isNull("changeId")) return;
            long changeId = data.getLong("changeId");
            String from = data.getString("from", null);
            String to = data.getString("to", null);
            if (from == null || to == null || from.isBlank() || to.isBlank()) return;
            if (KIND_RENAMED.equals(kind)) {
                Set<String> clans = new HashSet<>();
                DataArray ids = data.optArray("clanIds").orElse(DataArray.empty());
                for (int i = 0; i < ids.length(); i++) clans.add(ids.getString(i).toLowerCase(java.util.Locale.ROOT));
                renamed(changeId, from, to, clans, parse(data.getString("at", null)));
            } else if (KIND_UNDONE.equals(kind)) {
                undone(changeId, from, to);
            }
        }

        /**
         * Applies a linked change to the servers whose clan is in {@code clans}. Every server's clan is looked up before anything is kept: a
         * lookup the hub didn't answer throws, so the change is never recorded as done with a server left out.
         */
        void renamed(long changeId, String from, String to, Set<String> clans, Instant at) {
            if (store.state(changeId) != null) return; // delivered before
            List<Long> guilds = new ArrayList<>();
            for (long guildId : store.guildsHolding(from, to)) {
                String clanName = clanNameOf.apply(guildId);
                if (clanName == null) continue;
                Optional<String> clanId = reads.clanId(clanName); // throws when unanswered
                if (clanId.isPresent() && clans.contains(clanId.get().toLowerCase(java.util.Locale.ROOT))) guilds.add(guildId);
            }
            HubNameChangeStore.Applied applied = rosterLock.apply(() -> store.apply(changeId, from, to, at, guilds));
            if (applied == null) return;
            for (long guildId : applied.rosterGuildIds()) {
                try {
                    discord.announce(guildId, from, to);
                    if (applied.ownerDiscordId() != null) {
                        discord.followRoles(guildId, applied.ownerDiscordId(), to);
                        discord.followNickname(guildId, applied.ownerDiscordId(), from, to);
                    }
                } catch (RuntimeException e) {
                    log.error("Name change {} moved, but its Discord follow-up failed in guild {}", changeId, guildId, e);
                }
            }
        }

        void undone(long changeId, String from, String to) {
            HubNameChangeStore.Applied back = rosterLock.apply(() -> store.undoApplied(changeId));
            if (back == null || back.ownerDiscordId() == null) return;
            for (long guildId : back.rosterGuildIds()) {
                try {
                    discord.followNickname(guildId, back.ownerDiscordId(), to, from);
                } catch (RuntimeException e) {
                    log.error("Name change {} undone, but the nickname follow-up failed in guild {}", changeId, guildId, e);
                }
            }
        }

        /**
         * The backstop for a feed entry that never arrived or failed every try: reads the linked and undone changes of every clan the bot
         * keeps a roster for, and follows any from the last {@link #CATCH_UP_WINDOW} it hasn't. Every clan is looked up first; if the hub
         * doesn't answer one, nothing is done this time (a change found through one clan must still reach every server it belongs to).
         */
        void catchUp(Instant now) {
            Set<String> clanIds = new java.util.LinkedHashSet<>();
            java.util.Map<String, List<Long>> guildsOfClan = new java.util.HashMap<>();
            for (long guildId : store.knownGuilds()) {
                String clanName = clanNameOf.apply(guildId);
                if (clanName == null) continue;
                reads.clanId(clanName).ifPresent(id -> {
                    clanIds.add(id);
                    guildsOfClan.computeIfAbsent(id, k -> new ArrayList<>()).add(guildId);
                });
            }
            record Linked(long id, String from, String to, Instant at, Set<String> clans) {}
            java.util.Map<Long, Linked> linked = new java.util.LinkedHashMap<>();
            java.util.Map<Long, DataObject> undone = new java.util.LinkedHashMap<>();
            List<Released> rejected = new ArrayList<>();
            Instant since = now.minus(CATCH_UP_WINDOW);
            for (String clanId : clanIds) {
                for (DataObject c : reads.changes(clanId, "linked")) {
                    Instant at = parse(c.getString("decidedAt", null));
                    if (at == null || at.isBefore(since)) continue;
                    Linked l = linked.computeIfAbsent(c.getLong("id"), id -> new Linked(id, c.getString("from", ""), c.getString("to", ""), at, new HashSet<>()));
                    l.clans().add(clanId.toLowerCase(java.util.Locale.ROOT));
                }
                for (DataObject c : reads.changes(clanId, "undone")) {
                    Instant at = parse(c.getString("undoneAt", null));
                    if (at != null && !at.isBefore(since)) undone.put(c.getLong("id"), c);
                }
                for (DataObject c : reads.changes(clanId, "rejected")) {
                    Instant at = parse(c.getString("decidedAt", null));
                    String from = c.getString("from", "");
                    if (at == null || at.isBefore(since) || from.isBlank()) continue;
                    for (long guildId : guildsOfClan.getOrDefault(clanId, List.of())) rejected.add(new Released(c.getLong("id"), guildId, from));
                }
            }
            // oldest first: two renames in a row (A to B, then B to C) must be followed in that order, or the rows end on B
            List<Linked> inOrder = new ArrayList<>(linked.values());
            inOrder.sort(java.util.Comparator.comparingLong(Linked::id));
            for (Linked l : inOrder) {
                if (l.from().isBlank() || l.to().isBlank()) continue;
                try {
                    renamed(l.id(), l.from(), l.to(), l.clans(), l.at());
                } catch (RuntimeException e) {
                    log.error("The catch-up couldn't follow name change {}", l.id(), e);
                }
            }
            for (DataObject c : undone.values()) {
                if (!"linked".equals(store.state(c.getLong("id")))) continue;
                try {
                    undone(c.getLong("id"), c.getString("from", ""), c.getString("to", ""));
                } catch (RuntimeException e) {
                    log.error("The catch-up couldn't undo name change {}", c.getLong("id"), e);
                }
            }
            // a rejected change (by an admin anywhere, or by the hub itself) means the old name really left: release the roles the roster
            // sync held for it, once per change and server, unless the name is back on the roster
            for (Released r : rejected) {
                if (!released.add(r.changeId() + ":" + r.guildId())) continue;
                try {
                    if (!store.activeOnRoster(r.guildId(), r.oldRsn())) discord.releaseRoles(r.guildId(), r.oldRsn());
                } catch (RuntimeException e) {
                    released.remove(r.changeId() + ":" + r.guildId());
                    log.error("The catch-up couldn't release the roles held for rejected name change {} in guild {}", r.changeId(), r.guildId(), e);
                }
            }
        }

        private record Released(long changeId, long guildId, String oldRsn) {}

        /** Rejected changes already released, as {@code changeId:guildId} (the 48-hour window lists each for two days). */
        private final Set<String> released = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private static Instant parse(String at) {
            try {
                return at == null ? null : Instant.parse(at);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** The real Discord side. */
    static final class LiveDiscord implements Discord {
        volatile JDA jda;
        static final String NICKNAME_JOB = NameChangeNicknameJob.KIND;

        private final TrackingEventRouter router;
        private final TrackingIconCatalog icons;
        private final VerificationRoleSyncService roleSync;
        private final DiscordOutbox outbox;
        private final PlayerLinkService links;

        LiveDiscord(TrackingEventRouter router, TrackingIconCatalog icons, VerificationRoleSyncService roleSync, DiscordOutbox outbox, PlayerLinkService links) {
            this.router = router;
            this.icons = icons;
            this.roleSync = roleSync;
            this.outbox = outbox;
            this.links = links;
        }

        @Override
        public void releaseRoles(long guildId, String oldRsn) {
            if (AutoPostRouter.skipSweep("rejected name change roles")) return;
            Guild guild = guild(guildId);
            if (guild == null) return;
            PlayerLink link = links.getLinkForRsn(guildId, oldRsn);
            if (link != null) roleSync.syncLeftClan(guild, link.discordUserId());
        }

        private Guild guild(long guildId) {
            JDA current = jda;
            return current == null ? null : Shards.guild(current, guildId);
        }

        @Override
        public void announce(long guildId, String from, String to) {
            Guild guild = guild(guildId);
            if (guild == null) return;
            String icon = icons.mentionForCategory("citadel");
            router.dispatchAll(guild, List.of(new ClassifiedEntry(TrackingGroup.CLAN_JOINS_LEAVES, line(icon, from, to, Instant.now()))));
        }

        @Override
        public void followRoles(long guildId, long discordUserId, String rsn) {
            if (AutoPostRouter.skipSweep("name change roles")) return;
            Guild guild = guild(guildId);
            if (guild == null) return;
            try {
                roleSync.syncRoles(guild, discordUserId, rsn);
            } catch (RuntimeException e) {
                log.warn("Couldn't update roles after a name change in guild {}: {}", guildId, e.toString());
            }
        }

        @Override
        public void followNickname(long guildId, long discordUserId, String from, String to) {
            if (AutoPostRouter.skipSweep("name change nicknames")) return;
            if (guild(guildId) == null) return;
            outbox.enqueue(NICKNAME_JOB, guildId, 0L, "nickname-follow:" + guildId + ":" + discordUserId, NameChangeNicknameJob.payload(discordUserId, from, to));
        }
    }

    /** The one line a linked rename posts, in the style of the join and leave lines. */
    static String line(String icon, String from, String to, Instant now) {
        String day = now.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH));
        return (icon != null ? icon + " " : "") + "**" + from + "** is now **" + to + "**. (" + day + ")";
    }
}
