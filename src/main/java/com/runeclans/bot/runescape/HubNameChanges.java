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
import com.runeclans.bot.util.TtlCache;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * A change delivered twice does nothing twice (the hub's change id is remembered). Work runs on its own lane, never the feed's.
 */
@BService
public class HubNameChanges implements HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(HubNameChanges.class);
    public static final String KIND_RENAMED = "player.renamed";
    public static final String KIND_UNDONE = "player.rename_undone";

    private final Follower follower;
    private final LiveDiscord discord;
    private final RuneClansHubClient hub;
    private final ClanIds clanIds;
    private final GuildSettingsService settings;
    private final ScheduledExecutorService lane = BotScheduler.lane("hub-name-changes");

    public HubNameChanges(HubNameChangeStore store, RuneClansHubClient hub, GuildSettingsService settings, ClanSyncService clanSync,
                          TrackingEventRouter router, TrackingIconCatalog icons, VerificationRoleSyncService roleSync, DiscordOutbox outbox) {
        this.hub = hub;
        this.settings = settings;
        this.clanIds = new ClanIds(hub);
        this.discord = new LiveDiscord(router, icons, roleSync, outbox);
        this.follower = new Follower(store, guildId -> settings.getEffective(guildId).clanName(), clanIds::of, clanSync::withRosterLock, discord);
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        discord.jda = event.getJda();
    }

    @Override
    public void onHubFeed(String kind, DataObject data) {
        if (data == null || !(KIND_RENAMED.equals(kind) || KIND_UNDONE.equals(kind))) return;
        lane.execute(() -> {
            try {
                follower.handle(kind, data);
            } catch (RuntimeException e) {
                log.warn("Following a hub name change failed: {}", e.toString());
            }
        });
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
        return clanIds.of(settings.getEffective(guildId).clanName());
    }

    /** The hub's name changes for this server's clan with {@code status} ({@code open} = waiting for an admin, {@code linked}), newest first. */
    public Result<List<Change>> changes(long guildId, String status) {
        Optional<String> clan = clanIdOf(guildId);
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
        Optional<String> clan = clanIdOf(guildId);
        if (clan.isEmpty()) return new Result<>(null, "This server's clan isn't on RuneClans yet.");
        String body = DataObject.empty().put("decision", decision).put("decidedBy", decidedBy(actorId)).toString();
        RuneClansHubClient.Answer a = hub.call("PUT", "/v1/clans/" + clan.get() + "/name-changes/" + changeId, body);
        if (a == null || !a.ok() || a.json() == null) return new Result<>(null, problem(a));
        return new Result<>(Change.of(a.json()), null);
    }

    /** Undoes a linked change for this server's clan (allowed for 30 days after the link), as {@code bot:<discord user id>}. */
    public Result<Change> undo(long guildId, long changeId, long actorId) {
        Optional<String> clan = clanIdOf(guildId);
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
    }

    /** The feed-following logic, apart from the framework's one-constructor service so tests can drive it. */
    static final class Follower {
        private final HubNameChangeStore store;
        private final Function<Long, String> clanNameOf;
        private final Function<String, Optional<String>> clanIdOf;
        private final Function<Supplier<HubNameChangeStore.Applied>, HubNameChangeStore.Applied> rosterLock;
        private final Discord discord;

        Follower(HubNameChangeStore store, Function<Long, String> clanNameOf, Function<String, Optional<String>> clanIdOf,
                 Function<Supplier<HubNameChangeStore.Applied>, HubNameChangeStore.Applied> rosterLock, Discord discord) {
            this.store = store;
            this.clanNameOf = clanNameOf;
            this.clanIdOf = clanIdOf;
            this.rosterLock = rosterLock;
            this.discord = discord;
        }

        void handle(String kind, DataObject data) {
            if (!data.hasKey("changeId") || data.isNull("changeId")) return;
            long changeId = data.getLong("changeId");
            String from = data.getString("from", null);
            String to = data.getString("to", null);
            if (from == null || to == null || from.isBlank() || to.isBlank()) return;
            if (KIND_RENAMED.equals(kind)) renamed(changeId, from, to, data);
            else if (KIND_UNDONE.equals(kind)) undone(changeId, from, to);
        }

        private void renamed(long changeId, String from, String to, DataObject data) {
            if (store.state(changeId) != null) return; // delivered before
            Set<String> clans = new HashSet<>();
            DataArray ids = data.optArray("clanIds").orElse(DataArray.empty());
            for (int i = 0; i < ids.length(); i++) clans.add(ids.getString(i).toLowerCase(java.util.Locale.ROOT));
            List<Long> guilds = new ArrayList<>();
            for (long guildId : store.guildsHolding(from, to)) {
                String clanName = clanNameOf.apply(guildId);
                if (clanName == null) continue;
                Optional<String> clanId = clanIdOf.apply(clanName);
                if (clanId.isPresent() && clans.contains(clanId.get().toLowerCase(java.util.Locale.ROOT))) guilds.add(guildId);
            }
            Instant at = parse(data.getString("at", null));
            HubNameChangeStore.Applied applied = rosterLock.apply(() -> store.apply(changeId, from, to, at, guilds));
            if (applied == null) return;
            for (long guildId : applied.rosterGuildIds()) {
                discord.announce(guildId, from, to);
                if (applied.ownerDiscordId() != null) {
                    discord.followRoles(guildId, applied.ownerDiscordId(), to);
                    discord.followNickname(guildId, applied.ownerDiscordId(), from, to);
                }
            }
        }

        private void undone(long changeId, String from, String to) {
            HubNameChangeStore.Applied back = rosterLock.apply(() -> store.undoApplied(changeId));
            if (back == null || back.ownerDiscordId() == null) return;
            for (long guildId : back.rosterGuildIds()) discord.followNickname(guildId, back.ownerDiscordId(), to, from);
        }

        private static Instant parse(String at) {
            try {
                return at == null ? null : Instant.parse(at);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** The hub's id of a clan, by name, kept for ten minutes (only found ones). */
    static final class ClanIds {
        private final RuneClansHubClient hub;
        private final TtlCache<String, String> ids = new TtlCache<>(Duration.ofMinutes(10));

        ClanIds(RuneClansHubClient hub) {
            this.hub = hub;
        }

        Optional<String> of(String clanName) {
            if (clanName == null || clanName.isBlank()) return Optional.empty();
            String key = ClanSyncService.clanKey(clanName);
            Optional<String> cached = ids.get(key);
            if (cached.isPresent()) return cached;
            RuneClansHubClient.Answer a = hub.call("GET", "/v1/clans/by-name/" + URLEncoder.encode(clanName.strip(), StandardCharsets.UTF_8).replace("+", "%20"), null);
            if (a == null || !a.ok() || a.json() == null) return Optional.empty();
            String id = a.json().getString("clanId", "");
            if (id.isBlank()) return Optional.empty();
            ids.put(key, id);
            return Optional.of(id);
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

        LiveDiscord(TrackingEventRouter router, TrackingIconCatalog icons, VerificationRoleSyncService roleSync, DiscordOutbox outbox) {
            this.router = router;
            this.icons = icons;
            this.roleSync = roleSync;
            this.outbox = outbox;
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
