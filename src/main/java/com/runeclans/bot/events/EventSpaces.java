package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventSpace.Choice;
import com.runeclans.bot.events.EventSpace.Facts;
import com.runeclans.bot.events.EventSpace.HeldChannel;
import com.runeclans.bot.events.EventSpace.HeldRole;
import com.runeclans.bot.events.EventSpace.Kind;
import com.runeclans.bot.events.EventSpace.Plan;
import com.runeclans.bot.events.EventSpace.PlayerRef;
import com.runeclans.bot.events.EventSpace.Readiness;
import com.runeclans.bot.events.EventSpace.Room;
import com.runeclans.bot.events.EventSpace.TeamRef;
import com.runeclans.bot.events.EventSpace.Use;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Reads everything an event's roles and channels depend on (the roster, teams and organisers; what JonnyBot made before and what is still there; which roles
 * are staff; how much room and which permissions the server has) and hands it to {@link EventSpace} to work out what to do. This class only reads: it shows
 * what setting up the space would do and changes nothing on Discord. The event, its roster and teams are the hub's (T-934); what the bot made is its own.
 */
@BService
public class EventSpaces {
    private static final Logger log = LoggerFactory.getLogger(EventSpaces.class);

    private final HubEvents events;
    private final EventSpaceRepository space;
    private final PermissionGroupService groups;

    public EventSpaces(HubEvents events, EventSpaceRepository space, PermissionGroupService groups) {
        this.events = events;
        this.space = space;
        this.groups = groups;
    }

    /**
     * The channel a bingo's live board goes in: the one the event's settings name, else the live board channel the bot made for the event. The ids of what
     * the bot made live in its own tables, not on the hub (T-945), so a channel made by a setup is found here rather than in the event's config.
     */
    public static Long boardChannelOf(Event event, EventSpaceRepository space) {
        Long chosen = settingsOf(event).boardChannelId();
        return chosen != null ? chosen : madeChannel(event, space, Kind.BOARD);
    }

    /** The channel players' proof lands in for staff to review: the settings' choice, else the review channel the bot made for the event. */
    public static Long reviewChannelOf(Event event, EventSpaceRepository space) {
        Long chosen = settingsOf(event).reviewChannelId();
        return chosen != null ? chosen : madeChannel(event, space, Kind.REVIEW);
    }

    private static Long madeChannel(Event event, EventSpaceRepository space, Kind kind) {
        for (EventSpaceRepository.ChannelRow row : space.channels(event.id())) {
            if (row.key().equals(kind.key())) return row.channelId();
        }
        return null;
    }

    private static EventSettings settingsOf(Event event) {
        try {
            return EventSettings.parse(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return EventSettings.defaults();
        }
    }

    /** What the plan was made from, kept so the page can say which roles count as staff and what settings were used. */
    public record Gathered(Facts facts, List<Role> staffRoles, EventSettings settings) {}

    /** What setting up the space would do right now: the plan, plus a note on players who haven't joined the server. */
    public record Preview(Plan plan, Gathered gathered) {}

    public Preview preview(Guild guild, Event event) {
        return preview(guild, event, null);
    }

    /** {@link #preview(Guild, Event)} as it would be with the roles and channels settings {@code instead} (null: the saved ones), which are not saved. */
    public Preview preview(Guild guild, Event event, EventSpace.Setting instead) {
        Gathered gathered = gather(guild, event, instead);
        Plan plan = EventSpace.plan(gathered.facts());
        long absent = gathered.facts().players().stream().filter(p -> !p.inServer()).count();
        if (absent > 0) {
            plan = plan.withWarning(absent == 1 ? "1 player hasn't joined this Discord server yet. They get their role as soon as they do." : absent + " players haven't joined this Discord server yet. They get their role as soon as they do.");
        }
        return new Preview(plan, gathered);
    }

    /** The facts for {@code event} in {@code guild}, read now. */
    public Gathered gather(Guild guild, Event event) {
        return gather(guild, event, null);
    }

    private Gathered gather(Guild guild, Event event, EventSpace.Setting instead) {
        EventSettings settings = instead == null ? events.settings(event) : events.settings(event).withChannels(instead);
        List<Participant> counted = events.countedPlayers(event);
        int excluded = events.participants(event.id()).size() - counted.size();
        List<EventRepository.Team> teams = events.teams(event.id());
        Set<Long> teamIds = new HashSet<>();
        teams.forEach(t -> teamIds.add(t.id()));
        boolean teamScored = event.scoringMode() == ScoringMode.TEAM;

        Set<Long> ask = new LinkedHashSet<>();
        counted.forEach(p -> ask.add(p.userId()));
        ask.addAll(settings.organisers());
        Presence presence = presenceOf(guild, ask);
        List<PlayerRef> players = new ArrayList<>();
        for (Participant p : counted) {
            String name = presence.names().get(p.userId());
            Long team = p.teamId() != null && teamIds.contains(p.teamId()) ? p.teamId() : null;
            // when Discord couldn't be asked, nobody is assumed to be missing
            players.add(new PlayerRef(p.userId(), name != null ? name : "player " + shortId(p.userId()), team, name != null || !presence.complete()));
        }

        Map<String, HeldRole> heldRoles = new LinkedHashMap<>();
        for (EventSpaceRepository.RoleRow row : space.roles(event.id())) {
            Role role = guild.getRoleById(row.roleId());
            if (role != null) heldRoles.put(row.key(), new HeldRole(role.getIdLong(), role.getName()));
        }
        Map<String, HeldChannel> heldChannels = new LinkedHashMap<>();
        for (EventSpaceRepository.ChannelRow row : space.channels(event.id())) {
            if (!row.owned()) continue;
            GuildChannel channel = guild.getGuildChannelById(row.channelId());
            if (channel != null) heldChannels.put(row.key(), new HeldChannel(channel.getIdLong(), channel.getName(), row.parent(), overwritesOf(channel)));
        }

        EventRepository.Setup setup = events.setup(event.id());
        Readiness readiness = new Readiness(event.status(), teamScored, setup.signupsClosed(), setup.rosterConfirmed(), setup.teamsFinalised());
        EventSpace.Setting setting = settings.channels();
        Facts facts = new Facts(event.name(), ClanVsClan.game(event), teamScored, setting, settings.boardChannelId(), settings.reviewChannelId(),
                teams.stream().map(t -> new TeamRef(t.id(), t.name())).toList(), players, settings.organisers(), excluded, heldRoles, heldChannels,
                pickProblems(guild, event, settings), roomOf(guild), readiness, EventBoards.displayOf(DataObject.fromJson(event.config())).shared(),
                new EventSpace.Ids(guild.getPublicRole().getIdLong(), guild.getSelfMember().getIdLong(), staffRoles(guild).stream().map(net.dv8tion.jda.api.entities.Role::getIdLong).toList()));
        return new Gathered(facts, staffRoles(guild), settings);
    }

    /** Who may see and write in a channel as Discord has it now, or null for a channel that has no permission overwrites of its own. */
    static List<EventSpace.Overwrite> overwritesOf(GuildChannel channel) {
        if (!(channel instanceof net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer container)) return null;
        List<EventSpace.Overwrite> list = new ArrayList<>();
        for (var o : container.getPermissionOverrides()) list.add(new EventSpace.Overwrite(o.getIdLong(), o.isRoleOverride(), o.getAllowedRaw(), o.getDeniedRaw()));
        return list;
    }

    private static String shortId(long id) {
        String text = Long.toString(id);
        return text.length() <= 4 ? text : text.substring(text.length() - 4);
    }

    /** Who is in the server among those asked about (by name), and whether Discord could be asked (if not, those missing from {@code names} may be in the server after all). */
    record Presence(Map<Long, String> names, boolean complete) {}

    /** What was found out about one player a moment ago. */
    private record Seen(String name, boolean inServer, long at) {}

    /** Asking Discord is slow and the dashboard asks every two seconds while a setup runs, so what was found is kept for this long. */
    private static final long SEEN_MILLIS = 60_000;
    private final Map<Long, Map<Long, Seen>> seen = new ConcurrentHashMap<>();

    /** Forgets what was found out about a player, because something changed (they joined the server). */
    public void forget(long guildId, long userId) {
        Map<Long, Seen> forGuild = seen.get(guildId);
        if (forGuild != null) forGuild.remove(userId);
    }

    /** The members among {@code ids} who are in the server: from the cache or what was found a moment ago, and for the rest by asking Discord (100 at a time). */
    Presence presenceOf(Guild guild, Collection<Long> ids) {
        Map<Long, String> found = new HashMap<>();
        Map<Long, Seen> known = seen.computeIfAbsent(guild.getIdLong(), id -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        List<Long> missing = new ArrayList<>();
        for (long id : ids) {
            Member cached = guild.getMemberById(id);
            if (cached != null) {
                found.put(id, cached.getEffectiveName());
                known.put(id, new Seen(cached.getEffectiveName(), true, now));
                continue;
            }
            Seen before = known.get(id);
            if (before != null && now - before.at() < SEEN_MILLIS) {
                if (before.inServer()) found.put(id, before.name());
            } else {
                missing.add(id);
            }
        }
        boolean complete = true;
        for (int i = 0; i < missing.size(); i += 100) {
            List<Long> chunk = missing.subList(i, Math.min(missing.size(), i + 100));
            try {
                CompletableFuture<List<Member>> asked = new CompletableFuture<>();
                guild.retrieveMembersByIds(chunk).onSuccess(asked::complete).onError(asked::completeExceptionally);
                Set<Long> answered = new HashSet<>();
                for (Member m : asked.get(30, TimeUnit.SECONDS)) {
                    found.put(m.getIdLong(), m.getEffectiveName());
                    known.put(m.getIdLong(), new Seen(m.getEffectiveName(), true, now));
                    answered.add(m.getIdLong());
                }
                for (long id : chunk) {
                    if (!answered.contains(id)) known.put(id, new Seen("", false, now));
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Could not check which players are in the server", e);
                complete = false;
            }
        }
        return new Presence(found, complete);
    }

    /**
     * The roles that get access to every event's channels: the Admin group, and when it includes higher roles, every role above its lowest (bots' own roles
     * aside). A role with the Administrator permission is left out: it sees everything on the server whatever a channel says, so naming it would only clutter
     * every channel's permission list.
     */
    public List<Role> staffRoles(Guild guild) {
        Optional<PermissionGroup> group = groups.group(guild.getIdLong(), PermissionGroup.ADMIN);
        if (group.isEmpty()) return List.of();
        List<Role> configured = new ArrayList<>();
        for (long id : group.get().roleIds()) {
            Role role = guild.getRoleById(id);
            if (role != null) configured.add(role);
        }
        Set<Role> staff = new LinkedHashSet<>(configured);
        if (group.get().includeHigher() && !configured.isEmpty()) {
            int lowest = configured.stream().mapToInt(Role::getPosition).min().orElse(Integer.MAX_VALUE);
            for (Role role : guild.getRoles()) {
                if (!role.isPublicRole() && !role.isManaged() && role.getPosition() >= lowest) staff.add(role);
            }
        }
        return staff.stream().filter(role -> !role.hasPermission(Permission.ADMINISTRATOR)).toList();
    }

    /** How many more roles and channels the server can take and what JonnyBot lacks permission for. */
    Room roomOf(Guild guild) {
        List<String> missing = new ArrayList<>();
        for (Permission p : EventSpace.BOT_NEEDS) {
            if (!guild.getSelfMember().hasPermission(p)) missing.add(p.getName());
        }
        return new Room(EventSpace.MAX_ROLES - guild.getRoles().size(), EventSpace.MAX_CHANNELS - guild.getChannels().size(), missing);
    }

    /** Why a channel an organiser chose for a slot can't be used, per slot. */
    private Map<Kind, String> pickProblems(Guild guild, Event event, EventSettings settings) {
        Map<Kind, String> problems = new EnumMap<>(Kind.class);
        boolean sharedBoard = EventBoards.displayOf(DataObject.fromJson(event.config())).shared();
        for (Kind kind : Kind.slots()) {
            Choice choice = EventSpace.choice(settings.channels(), kind, event.template(), settings.boardChannelId(), settings.reviewChannelId(), sharedBoard);
            if (choice == null || choice.use() != Use.EXISTING) continue;
            String problem = pickProblem(guild, kind, choice.channelId());
            if (problem != null) problems.put(kind, problem);
        }
        return problems;
    }

    private static String pickProblem(Guild guild, Kind kind, long channelId) {
        String what = kind.label().toLowerCase(java.util.Locale.ROOT);
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (channel == null) return "The channel chosen for the " + what + " can't be found. It may have been deleted.";
        if (channel.getType() != ChannelType.TEXT && channel.getType() != ChannelType.NEWS) return "#" + channel.getName() + ", chosen for the " + what + ", isn't a text channel.";
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS)) {
            return "The bot needs to see, send messages, attach files and embed links in #" + channel.getName() + ", chosen for the " + what + ".";
        }
        return null;
    }
}
