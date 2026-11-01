package com.runeclans.bot.hubsites;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.config.PrivilegedIntents;
import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.HubFeedListener;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * What the bot tells the hub under {@code SITES_BACKEND=hub} (T-505), on its own lane so no hub call holds up JDA's event thread:
 * <ul>
 *   <li>on start, RuneClan Bot's capabilities for all servers and for each server it is in ({@link CapabilityReports}: the per-server list is
 *   what makes the hub count the bot as connected there), and in every server the holders of each role the hub's {@code role-mappings} name</li>
 *   <li>on joining a server, its capabilities there; on leaving one, an empty list</li>
 *   <li>on a member's role change, the holders of the mapped roles that changed ({@link RoleReports}: gathered for a few seconds, one
 *   report per role)</li>
 *   <li>when a server's role mappings change on the hub ({@code site.roles_changed}, or {@code feature.changed} for
 *   {@code requests.discord}, whose request handler roles join them: T-734; or {@code site.kind.changed}, since a clan site uses the
 *   clan's connections and a community site its own: T-741), the holders of every mapped role there again</li>
 * </ul>
 * Writes go through the hub client's queue, so a hub that is briefly down gets them a little later. Off: nothing here runs.
 */
@BService
public class HubSiteReporter extends ListenerAdapter implements BotListener, HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(HubSiteReporter.class);

    private final HubSites sites;
    private final RuneClansHubClient hub;
    private final ScheduledExecutorService lane = BotScheduler.lane("hub-sites");
    private volatile JDA jda;
    private final RoleReports reports;
    private final CapabilityReports capabilities;
    /** When to declare: every shard's ready (as before T-817), and once when the panel moves clan sites to the hub while the bot runs. */
    private final Declarer declarer;

    public HubSiteReporter(HubSites sites, RuneClansHubClient hub) {
        this.sites = sites;
        this.hub = hub;
        this.reports = new RoleReports(sites::mappedRoles, this::holders, this::send,
                (delay, task) -> lane.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS));
        this.capabilities = new CapabilityReports((path, body) -> hub.write("PUT", path, body, HubSiteReporter::done));
        // the Switches panel can move clan sites to the hub while the bot runs (T-817): declare then, not only on start
        this.declarer = new Declarer(sites::readsHub, () -> jda != null, this::declareAll);
        lane.scheduleWithFixedDelay(declarer::tick, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public void onReady(ReadyEvent event) {
        jda = event.getJDA();
        lane.execute(declarer::ready);
    }

    /**
     * When to declare RuneClan Bot's capabilities and role holders to the hub. {@link #ready}: on every shard's {@code ReadyEvent} while clan
     * sites come from the hub, as before T-817 (the last shard to be ready covers every server; a new session declares again). {@link #tick}:
     * every 30 seconds, once after the Switches panel moves clan sites to the hub while the bot runs, and again after any move back and forth.
     */
    static final class Declarer {
        private final java.util.function.BooleanSupplier readsHub;
        private final java.util.function.BooleanSupplier discordReady;
        private final Runnable declare;
        private final java.util.concurrent.atomic.AtomicBoolean declared = new java.util.concurrent.atomic.AtomicBoolean();

        Declarer(java.util.function.BooleanSupplier readsHub, java.util.function.BooleanSupplier discordReady, Runnable declare) {
            this.readsHub = readsHub;
            this.discordReady = discordReady;
            this.declare = declare;
        }

        void ready() {
            if (!readsHub.getAsBoolean()) return;
            declared.set(true);
            declare.run();
        }

        void tick() {
            if (!readsHub.getAsBoolean()) {
                declared.set(false); // back on the bot: declare again if they move once more
                return;
            }
            if (discordReady.getAsBoolean() && declared.compareAndSet(false, true)) declare.run();
        }
    }

    /** Declares capabilities for every server the bot is in and reports their role holders. Runs on the lane. */
    private void declareAll() {
        JDA ready = jda;
        if (ready == null) return;
        try {
            capabilities.start(Shards.guilds(ready).stream().map(Guild::getIdLong).toList(), PrivilegedIntents.presence(), PrivilegedIntents.messageContent());
            int roles = 0;
            for (Guild guild : Shards.guilds(ready)) {
                try {
                    roles += reports.all(guild.getIdLong());
                } catch (RuntimeException e) {
                    log.warn("Hub role report for a server failed: {}", e.getClass().getSimpleName());
                }
            }
            log.info("RuneClans hub: declared capabilities and reported {} mapped role(s)", roles);
        } catch (RuntimeException e) {
            log.warn("Declaring RuneClan Bot's capabilities to the hub failed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        if (!sites.readsHub()) return;
        long guildId = event.getGuild().getIdLong();
        lane.execute(() -> capabilities.joined(guildId, PrivilegedIntents.presence(), PrivilegedIntents.messageContent()));
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        if (!sites.readsHub()) return;
        long guildId = event.getGuild().getIdLong();
        lane.execute(() -> capabilities.left(guildId));
    }

    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        changed(event.getGuild(), event.getRoles());
    }

    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        changed(event.getGuild(), event.getRoles());
    }

    @Override
    public void onHubFeed(String kind, DataObject data) {
        if (!sites.readsHub()) return;
        Long guildId = remappedGuild(kind, data);
        if (guildId == null) return;
        lane.execute(() -> {
            try {
                reports.all(guildId);
            } catch (RuntimeException e) {
                log.warn("Hub role report after a mapping change failed: {}", e.getClass().getSimpleName());
            }
        });
    }

    /** The server whose role mappings this hub change may have changed, or null. */
    static Long remappedGuild(String kind, DataObject data) {
        // site.kind.changed (plan C-13): a clan site uses the clan's connections, a community site its own, so the mapped roles may change
        if (data == null || !(kind.equals("site.roles_changed") || kind.equals("feature.changed") || kind.equals("site.kind.changed"))) return null;
        DataObject inner = data.optObject("data").orElse(null);
        if (inner == null) return null;
        if (kind.equals("feature.changed")) {
            String key = inner.optObject("feature").map(f -> f.getString("key", "")).orElse("");
            if (!key.equals("requests.discord")) return null;
        }
        String guild = inner.getString("guildId", null);
        if (guild == null || !guild.matches("\\d{15,20}")) return null;
        return Long.parseLong(guild);
    }

    private void changed(Guild guild, List<Role> roles) {
        if (!sites.readsHub()) return;
        List<Long> ids = roles.stream().map(Role::getIdLong).toList();
        long guildId = guild.getIdLong();
        lane.execute(() -> reports.changed(guildId, ids));
    }

    /** Every holder of the role, loaded from Discord (the member cache holds only online members); null when Discord couldn't say. */
    private List<Long> holders(long guildId, long roleId) {
        JDA current = jda;
        Guild guild = current == null ? null : Shards.guild(current, guildId);
        Role role = guild == null ? null : guild.getRoleById(roleId);
        if (role == null) return guild == null ? null : List.of(); // a mapped role deleted in Discord has no holders
        try {
            List<Member> members = guild.findMembersWithRoles(role).setTimeout(30, TimeUnit.SECONDS).get();
            List<Long> ids = new ArrayList<>(members.size());
            for (Member m : members) ids.add(m.getIdLong());
            return ids;
        } catch (RuntimeException e) {
            log.warn("Couldn't load a mapped role's holders: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void send(long guildId, long roleId, List<Long> holders) {
        hub.write("PUT", "/v1/guilds/" + guildId + "/role-members/" + roleId, body(holders).toString(), HubSiteReporter::done);
    }

    /** {@code {"userIds": ["..."]}}: ids as strings, as everywhere in the hub's API. */
    static DataObject body(List<Long> holders) {
        DataArray ids = DataArray.empty();
        for (long id : holders) ids.add(Long.toString(id));
        return DataObject.empty().put("userIds", ids);
    }

    private static void done(HubWriteQueue.Result result) {
        if (result.outcome() != HubWriteQueue.Outcome.SENT) log.warn("A hub site report ended {}", result.outcome());
    }
}
