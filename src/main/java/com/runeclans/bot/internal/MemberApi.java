package com.runeclans.bot.internal;

import com.runeclans.bot.permission.RoleSafety;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.commands.runescape.RsInteractionListener;
import com.runeclans.bot.commands.poll.PollOption;
import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.commands.poll.PollSession;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.commands.signup.SignupSession;
import com.runeclans.bot.commands.signup.SubmissionField;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.member.HubMemberPrivacy;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.permission.MemberAccess;
import com.runeclans.bot.member.MemberProfileRepository.Goal;
import com.runeclans.bot.member.MemberProfileRepository.Profile;
import com.runeclans.bot.member.MemberProfileRepository.SelfRole;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.RuneScapeSkillCatalog;
import com.runeclans.bot.runescape.RuneScapeXpTable;
import com.runeclans.bot.runescape.SkillValue;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a logged-in member does to their own record from the website, under {@code /internal/me/}: profile
 * text and privacy, DM preferences, skill goals, and the self-assignable roles. Like the older nickname and
 * colour endpoints, these take a {@code userId} and trust it — the website must source it only from the
 * visitor's verified login session, never from anything the browser sends. Each call only ever touches that
 * one user's own rows (or their own roles).
 */
@BService
public class MemberApi {
    private static final Logger log = LoggerFactory.getLogger(MemberApi.class);
    private static final String PREFIX = "/internal/me/";
    private static final int MAX_BIO = 280;
    private static final int MAX_GOALS = 12;
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** A role carrying any of these must never be self-assignable, whatever an admin picks. */
    static final EnumSet<Permission> DANGEROUS = EnumSet.of(Permission.ADMINISTRATOR, Permission.MANAGE_SERVER, Permission.MANAGE_ROLES,
            Permission.MANAGE_CHANNEL, Permission.KICK_MEMBERS, Permission.BAN_MEMBERS, Permission.MODERATE_MEMBERS, Permission.MESSAGE_MANAGE,
            Permission.MESSAGE_MENTION_EVERYONE, Permission.MANAGE_WEBHOOKS, Permission.MANAGE_PERMISSIONS, Permission.NICKNAME_MANAGE,
            Permission.VOICE_MUTE_OTHERS, Permission.VOICE_DEAF_OTHERS, Permission.VOICE_MOVE_OTHERS, Permission.VIEW_AUDIT_LOGS,
            Permission.MANAGE_EVENTS, Permission.CREATE_GUILD_EXPRESSIONS, Permission.MANAGE_GUILD_EXPRESSIONS);

    private final MemberProfileRepository repository;
    private final HubMemberPrivacy hubPrivacy;
    private final PlayerLinkRepository links;
    private final PollService pollService;
    private final SignupService signupService;
    private final MemberAccess memberAccess;
    private final Debouncer debouncer;
    private final SiteCache cache;
    private final CommunitySettings community;
    private final SiteStatsRepository stats;
    private final RsInteractionListener rsLinks;
    private final MemberEventsApi events;
    private final HighlightMemberApi highlights;
    private final ChatLevelMemberApi chatLevel;
    private final SkillWeekMemberApi skillWeek;
    private final DiscordOutbox outbox;
    // Per member: a handful of quick clicks is normal, a sustained flood is not. Reads are far cheaper than writes.
    private final RateLimiter writes = new RateLimiter(8, 1_500);
    private final RateLimiter reads = new RateLimiter(40, 500);

    public MemberApi(MemberProfileRepository repository, PlayerLinkRepository links, PollService pollService, SignupService signupService, MemberAccess memberAccess,
                     Debouncer debouncer, SiteCache cache, CommunitySettings community, SiteStatsRepository stats, RsInteractionListener rsLinks, MemberEventsApi events,
                     HighlightMemberApi highlights, ChatLevelMemberApi chatLevel, DiscordOutbox outbox, SkillWeekMemberApi skillWeek, HubMemberPrivacy hubPrivacy) {
        this.hubPrivacy = hubPrivacy;
        this.outbox = outbox;
        this.skillWeek = skillWeek;
        this.highlights = highlights;
        this.chatLevel = chatLevel;
        this.rsLinks = rsLinks;
        this.events = events;
        this.community = community;
        this.stats = stats;
        this.debouncer = debouncer;
        this.cache = cache;
        this.repository = repository;
        this.links = links;
        this.pollService = pollService;
        this.signupService = signupService;
        this.memberAccess = memberAccess;
    }

    /** A failure with the status and message the website should show. */
    private static final class Rejected extends RuntimeException {
        final int status;

        Rejected(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** The dangerous permissions among {@code granted} — empty means the role is safe to hand out. */
    static EnumSet<Permission> dangerousIn(Collection<Permission> granted) {
        EnumSet<Permission> found = EnumSet.noneOf(Permission.class);
        for (Permission p : granted) if (DANGEROUS.contains(p)) found.add(p);
        return found;
    }

    /** Whether the bot may safely add/remove {@code role} for members: an ordinary role, below the bot's own, with nothing powerful in it. */
    public static boolean safeToAssign(Guild guild, Role role) {
        return handOutRefusal(guild, role) == null;
    }

    /**
     * Why the bot won't add/remove {@code role} for members, worded for the dashboard, or null when it may. The one check every path that
     * hands roles to members uses (B-03, B-55): not @everyone, not managed, below the bot's own role, no powerful permissions, and not a
     * role that would make its holder bot staff ({@link RoleSafety}).
     */
    public static String handOutRefusal(Guild guild, Role role) {
        if (role == null) return "That role no longer exists in this server.";
        if (role.isPublicRole()) return "@everyone can't be handed out.";
        if (role.isManaged()) return "@" + role.getName() + " is managed by an integration, so the bot can't give it to anyone.";
        if (!guild.getSelfMember().canInteract(role)) return "The bot can't give @" + role.getName() + " out. Move the bot's own role above it in Server Settings > Roles.";
        EnumSet<Permission> dangerous = dangerousIn(role.getPermissions());
        if (!dangerous.isEmpty()) return "@" + role.getName() + " carries powerful permissions (" + dangerous.iterator().next().getName()
                + (dangerous.size() > 1 ? " and more" : "") + "), so the bot won't hand it out.";
        return RoleSafety.refusal(guild, role);
    }

    /** The home site's {@code /internal/me/<route>}. */
    public void handle(HttpExchange exchange, Guild guild) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String route = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
        if (route.endsWith("/")) route = route.substring(0, route.length() - 1);
        handle(exchange, guild, route, false);
    }

    /** Why a clan site's "me" action was refused for someone who isn't in that Discord server. */
    static final String NOT_IN_SERVER = "Join the clan's Discord server to do this.";

    /**
     * One "me" action ({@code settings}, {@code goals}, {@code polls/vote}, ...) in {@code guild}: the home site's, or a clan site's
     * ({@code sites/{slug}/me/<route>}, {@link DirectoryClanSiteMemberRoutes}, T-369). Every action reads and writes only that server's rows.
     *
     * @param clanSite a clan site's call: the user must be in {@code guild} (refused with 403 before anything is read or written), and the
     *                 server profile routes ({@code server-profile}, {@code nickname}, {@code color-role}) are open, each change only while
     *                 the server allows it ({@link CommunitySettings#siteMemberOptions}); the home site keeps its own endpoints for those
     */
    public void handle(HttpExchange exchange, Guild guild, String route, boolean clanSite) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase();

            DataObject body = method.equals("GET") || method.equals("DELETE") ? DataObject.empty() : readBody(exchange);
            long userId = parseId(method.equals("GET") ? query(exchange, "userId") : method.equals("DELETE") ? query(exchange, "userId") : body.getString("userId", null), "userId");
            if (!(method.equals("GET") ? reads : writes).tryAcquire(Long.toString(userId))) {
                throw new Rejected(429, "You're doing that too fast — give it a moment.");
            }
            if (clanSite && ServerProfile.resolveMember(guild, userId) == null) throw new Rejected(403, NOT_IN_SERVER);

            DataObject result = switch (route) {
                case "server-profile" -> clanSite && method.equals("GET") ? serverProfile(guild, userId) : null;
                case "nickname" -> clanSite && method.equals("POST") ? setNickname(guild, userId, body) : null;
                case "color-role" -> clanSite && method.equals("POST") ? setColorRole(guild, userId, body) : null;
                case "settings" -> method.equals("GET") ? getSettings(guild, userId) : method.equals("PUT") ? saveSettings(guild, userId, body) : null;
                case "goals" -> switch (method) {
                    case "GET" -> goals(guild, userId);
                    case "POST" -> addGoal(guild, userId, body);
                    case "DELETE" -> deleteGoal(guild, userId, parseId(query(exchange, "id"), "id"));
                    default -> null;
                };
                case "roles" -> method.equals("GET") ? roles(guild, userId) : method.equals("POST") ? toggleRole(guild, userId, body) : null;
                case "polls" -> method.equals("GET") ? myPolls(guild, userId) : method.equals("POST") ? createPoll(guild, userId, body) : null;
                case "coffer" -> method.equals("GET") ? myCoffer(guild, userId) : null;
                case "events" -> method.equals("GET") ? events.mine(guild, userId) : null;
                case "events/detail" -> method.equals("GET") ? events.detail(guild, userId, parseId(query(exchange, "eventId"), "eventId")) : null;
                case "events/join" -> method.equals("POST") ? events.join(guild, userId, body) : null;
                case "events/leave" -> method.equals("POST") ? events.leave(guild, userId, body) : null;
                case "link" -> switch (method) {
                    case "GET" -> linkStatus(guild, userId);
                    case "POST" -> submitLink(guild, userId, body);
                    case "DELETE" -> cancelLink(guild, userId);
                    default -> null;
                };
                case "polls/vote" -> method.equals("POST") ? votePoll(guild, userId, body) : null;
                case "highlights" -> switch (method) {
                    case "GET" -> highlights.get(guild, userId);
                    case "POST" -> highlights.add(guild, userId, body);
                    case "DELETE" -> highlights.remove(guild, userId, query(exchange, "id"));
                    default -> null;
                };
                case "highlights/ignore" -> method.equals("POST") ? highlights.ignore(guild, userId, body) : null;
                case "level" -> method.equals("GET") ? chatLevel.get(guild, userId) : null;
                case "skill-week/vote" -> method.equals("GET") ? skillWeek.get(guild, userId) : method.equals("POST") ? skillWeek.vote(guild, userId, body) : null;
                case "skill-week/auto" -> method.equals("GET") ? skillWeek.auto(guild, userId) : method.equals("POST") ? skillWeek.setAuto(guild, userId, body) : null;
                case "signups" -> method.equals("GET") ? mySignups(guild, userId) : null;
                case "signups/join" -> method.equals("POST") ? joinSignup(guild, userId, body) : null;
                case "signups/leave" -> method.equals("POST") ? leaveSignup(guild, userId, body) : null;
                default -> null;
            };

            if (result == null) {
                InternalApiServer.sendJson(exchange, route.isEmpty() ? 404 : 405, DataObject.empty().put("error", "Not found or method not allowed"));
            } else {
                InternalApiServer.sendJson(exchange, 200, result);
            }
        } catch (Rejected e) {
            InternalApiServer.sendJson(exchange, e.status, DataObject.empty().put("error", e.getMessage()));
        } catch (ServerProfile.Refused e) {
            InternalApiServer.sendJson(exchange, e.status, DataObject.empty().put("error", e.getMessage()));
        } catch (HighlightMemberApi.Refused e) {
            InternalApiServer.sendJson(exchange, e.status, DataObject.empty().put("error", e.getMessage()));
        } catch (com.runeclans.bot.hubcommunity.HubCommunityProblem e) {
            // hub-community: the hub's answer, in words a member can read
            InternalApiServer.sendJson(exchange, e.httpStatus(), DataObject.empty().put("error", e.getMessage()));
        } catch (com.runeclans.bot.events.EventProblem e) {
            // the event engine's own refusals already say why in words a member can read
            InternalApiServer.sendJson(exchange, e.status(), DataObject.empty().put("error", e.getMessage()));
        } catch (DiscordLookups.Slow e) {
            InternalApiServer.sendSlow(exchange);
        } catch (Exception e) {
            log.error("Member API request {} failed", exchange.getRequestURI().getPath(), e);
            InternalApiServer.sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    // ---------- the server profile on a clan site (T-369) ----------

    /** The member's names, avatar and colour in this server, and which of them the server lets members change from its site. */
    private DataObject serverProfile(Guild guild, long userId) {
        Member member = ServerProfile.resolveMember(guild, userId);
        if (member == null) throw new Rejected(403, NOT_IN_SERVER);
        CommunitySettings.SiteMemberOptions options = community.siteMemberOptions(guild.getIdLong());
        return ServerProfile.info(member).put("canChangeNickname", options.nickname()).put("canChangeColor", options.color());
    }

    private DataObject setNickname(Guild guild, long userId, DataObject body) {
        if (!community.siteMemberOptions(guild.getIdLong()).nickname()) throw new Rejected(403, "This clan doesn't let members change their nickname from the website.");
        Member member = ServerProfile.resolveMember(guild, userId);
        if (member == null) throw new Rejected(403, NOT_IN_SERVER);
        try {
            return ServerProfile.setNickname(outbox, guild, member, body);
        } catch (ServerProfile.Refused e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Rejected(502, "Couldn't change your nickname: the bot may not have permission in this server.");
        }
    }

    private DataObject setColorRole(Guild guild, long userId, DataObject body) {
        if (!community.siteMemberOptions(guild.getIdLong()).color()) throw new Rejected(403, "This clan doesn't let members change their name colour from the website.");
        Member member = ServerProfile.resolveMember(guild, userId);
        if (member == null) throw new Rejected(403, NOT_IN_SERVER);
        try {
            return ServerProfile.setColorRole(outbox, guild, member, body);
        } catch (ServerProfile.Refused e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Rejected(502, "Couldn't change your colour: the bot may not have permission in this server.");
        }
    }

    // ---------- linking a RuneScape name ----------

    private static final java.util.regex.Pattern RSN = java.util.regex.Pattern.compile("[A-Za-z0-9 _-]{1,12}");

    /** NONE, PENDING (an admin is reviewing a request) or LINKED, with the names involved. */
    private DataObject linkStatus(Guild guild, long userId) {
        DataArray rsns = DataArray.empty();
        links.getLinksForUser(guild.getIdLong(), userId).forEach(l -> rsns.add(l.rsn()));
        var pending = links.getPendingAttemptForUser(guild.getIdLong(), userId);
        String state = rsns.length() > 0 ? "LINKED" : pending != null ? "PENDING" : "NONE";
        return DataObject.empty().put("state", state).put("rsns", rsns).put("pendingRsn", pending == null ? null : pending.rsn());
    }

    private DataObject submitLink(Guild guild, long userId, DataObject body) {
        String rsn = body.getString("rsn", "").strip().replaceAll("\\s+", " ");
        if (!RSN.matcher(rsn).matches()) throw new Rejected(400, "RuneScape names are 1 to 12 letters, numbers, spaces, hyphens or underscores.");
        net.dv8tion.jda.api.entities.Member member = DiscordLookups.memberOrSlow(guild, userId);
        if (member == null) throw new Rejected(403, "Join the Discord server first, then link your name.");

        var result = rsLinks.submitLinkRequest(guild, member, userId, rsn);
        cache.invalidate("me");
        return switch (result.status()) {
            case ALREADY_YOURS -> throw new Rejected(409, rsn + " is already linked to your account.");
            case TAKEN -> throw new Rejected(409, rsn + " is already linked to another Discord account. If that's you, ask an admin.");
            case PENDING_EXISTS -> throw new Rejected(409, "You already have a request waiting for review (" + result.attempt().rsn() + "). Cancel it first to submit a different name.");
            case SUBMITTED -> linkStatus(guild, userId).put("submitted", true);
        };
    }

    private DataObject cancelLink(Guild guild, long userId) {
        if (!rsLinks.cancelLinkRequest(guild, userId)) throw new Rejected(404, "There's no pending request to cancel.");
        cache.invalidate("me");
        return linkStatus(guild, userId);
    }

    // ---------- settings ----------

    private DataObject settingsJson(Profile p) {
        return DataObject.empty()
                .put("bio", p.bio()).put("accentColor", p.accentColor()).put("pinnedSkill", p.pinnedSkill())
                .put("hideAdventureLog", p.hideAdventureLog()).put("hideFromLeaderboards", p.hideFromLeaderboards())
                .put("dmGoals", p.dmGoals()).put("dmEvents", p.dmEvents()).put("hideDiscordLink", p.hideDiscordLink())
                .put("hideRecap", p.hideRecap());
    }

    /**
     * The member's settings with the privacy that applies to them: until they choose their own toggles, the server's member privacy defaults
     * (T-395) can turn a toggle on, and {@code privacyChosen} false says so.
     */
    private DataObject getSettings(Guild guild, long userId) {
        DataArray rsns = DataArray.empty();
        links.getLinksForUser(guild.getIdLong(), userId).forEach(l -> rsns.add(l.rsn()));
        Profile p = repository.getProfile(guild.getIdLong(), userId).withDefaults(repository.privacyDefaults(guild.getIdLong()));
        return settingsJson(p).put("rsns", rsns).put("privacyChosen", p.privacyChosen());
    }

    /**
     * The toggles the server's member privacy defaults cover: saving any of them is choosing them ({@code privacyChosen}). {@code hideRecap}
     * is not one of them, so turning recaps off or on never detaches a member from the defaults for the others.
     */
    static final java.util.List<String> PRIVACY_KEYS = java.util.List.of("hideAdventureLog", "hideFromLeaderboards", "hideDiscordLink");

    private DataObject saveSettings(Guild guild, long userId, DataObject body) {
        // the privacy that applies now is what a toggle left out of the save keeps, so choosing never shows something a default hid
        Profile current = repository.getProfile(guild.getIdLong(), userId).withDefaults(repository.privacyDefaults(guild.getIdLong()));
        boolean chose = current.privacyChosen() || PRIVACY_KEYS.stream().anyMatch(body::hasKey);

        String bio = body.getString("bio", current.bio()).replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
        if (bio.length() > MAX_BIO) throw new Rejected(400, "Your bio can be at most " + MAX_BIO + " characters.");

        String accent = body.isNull("accentColor") ? null : body.getString("accentColor").strip();
        if (accent != null && accent.isEmpty()) accent = null;
        if (accent != null && !HEX.matcher(accent).matches()) throw new Rejected(400, "The accent colour must look like #d4af37.");
        if (accent != null) accent = accent.toLowerCase();

        Integer pinned = null;
        if (!body.isNull("pinnedSkill")) {
            int id = body.getInt("pinnedSkill");
            if (id < 0 || id >= RuneScapeSkillCatalog.skillCount()) throw new Rejected(400, "That isn't a skill.");
            pinned = id;
        }

        Profile saved = new Profile(guild.getIdLong(), userId, bio, accent, pinned,
                body.getBoolean("hideAdventureLog", current.hideAdventureLog()), body.getBoolean("hideFromLeaderboards", current.hideFromLeaderboards()),
                body.getBoolean("dmGoals", current.dmGoals()), body.getBoolean("dmEvents", current.dmEvents()), body.getBoolean("hideDiscordLink", current.hideDiscordLink()),
                chose, body.getBoolean("hideRecap", current.hideRecap()));
        repository.saveProfile(saved);
        cache.clear(); // a profile change can alter many cached answers (privacy toggles hide people from several lists) — cleared after the save so nothing re-caches the old answer
        // the hub's copy (T-750), sent on its own lane: only a save that names a privacy toggle changes what the hub holds
        if (PRIVACY_KEYS.stream().anyMatch(body::hasKey))
            hubPrivacy.memberSaved(guild.getIdLong(), userId, saved.hideFromLeaderboards(), saved.hideAdventureLog(), saved.hideDiscordLink());
        return getSettings(guild, userId);
    }

    // ---------- goals ----------

    private Map<Integer, SkillValue> latestSkills(Guild guild, String rsn) {
        var snapshot = links.getLatestSnapshot(rsn);
        Map<Integer, SkillValue> skills = new HashMap<>();
        if (snapshot != null) links.getSkillsForSnapshot(snapshot.snapshotId()).forEach(s -> skills.put(s.skillId(), s));
        return skills;
    }

    private DataObject goals(Guild guild, long userId) {
        Map<String, Map<Integer, SkillValue>> byRsn = new HashMap<>();
        DataArray array = DataArray.empty();
        for (Goal goal : repository.goalsFor(guild.getIdLong(), userId)) {
            SkillValue now = byRsn.computeIfAbsent(goal.rsn().toLowerCase(), k -> latestSkills(guild, goal.rsn())).get(goal.skillId());
            long xp = now == null ? 0 : now.xp();
            long startXp = RuneScapeXpTable.xpForLevel(goal.skillId(), Math.max(1, goal.targetLevel() - 1));
            long targetXp = RuneScapeXpTable.xpForLevel(goal.skillId(), goal.targetLevel());
            array.add(DataObject.empty()
                    .put("id", Long.toString(goal.id())).put("skillId", goal.skillId()).put("skill", RuneScapeSkillCatalog.nameFor(goal.skillId()))
                    .put("targetLevel", goal.targetLevel()).put("targetXp", targetXp).put("currentXp", xp).put("currentLevel", now == null ? null : now.level())
                    .put("xpRemaining", Math.max(0, targetXp - xp))
                    .put("progress", goal.achievedAt() != null ? 1.0 : targetXp <= startXp ? 0.0 : Math.max(0, Math.min(1, (double) (xp - startXp) / (targetXp - startXp))))
                    .put("rsn", goal.rsn()).put("createdAt", goal.createdAt().toString()).put("achievedAt", goal.achievedAt() == null ? null : goal.achievedAt().toString()));
        }
        return DataObject.empty().put("goals", array);
    }

    private DataObject addGoal(Guild guild, long userId, DataObject body) {
        List<PlayerLink> linked = links.getLinksForUser(guild.getIdLong(), userId);
        if (linked.isEmpty()) throw new Rejected(409, "Link your RuneScape name with /rs in Discord first — goals track your linked account.");

        int skillId = body.getInt("skillId", -1);
        int target = body.getInt("targetLevel", -1);
        if (skillId < 0 || skillId >= RuneScapeSkillCatalog.skillCount()) throw new Rejected(400, "Choose a skill.");
        if (target < 2 || target > 120) throw new Rejected(400, "The target level must be between 2 and 120.");

        PlayerLink link = linked.getFirst();
        SkillValue now = latestSkills(guild, link.rsn()).get(skillId);
        if (now != null && now.level() >= target) throw new Rejected(400, "You're already level " + now.level() + " in " + RuneScapeSkillCatalog.nameFor(skillId) + ".");

        long active = repository.goalsFor(guild.getIdLong(), userId).stream().filter(g -> g.achievedAt() == null).count();
        if (active >= MAX_GOALS) throw new Rejected(409, "You can have up to " + MAX_GOALS + " active goals — finish or remove one first.");

        if (repository.addGoal(guild.getIdLong(), userId, link.rsn(), skillId, target) < 0) throw new Rejected(409, "You already have that goal.");
        return goals(guild, userId);
    }

    private DataObject deleteGoal(Guild guild, long userId, long goalId) {
        if (!repository.deleteGoal(guild.getIdLong(), userId, goalId)) throw new Rejected(404, "That goal doesn't exist.");
        return goals(guild, userId);
    }

    // ---------- self-assignable roles ----------

    private DataObject roles(Guild guild, long userId) {
        Member member = memberOf(guild, userId);
        DataArray array = DataArray.empty();
        for (SelfRole configured : repository.selfRoles(guild.getIdLong())) {
            Role role = guild.getRoleById(configured.roleId());
            if (role == null || !safeToAssign(guild, role)) continue;
            array.add(DataObject.empty().put("roleId", Long.toString(role.getIdLong())).put("name", role.getName())
                    .put("label", configured.label() == null || configured.label().isBlank() ? role.getName() : configured.label())
                    .put("description", configured.description()).put("color", role.getColors().getPrimaryRaw() & 0xFFFFFF)
                    .put("has", member.getRoles().contains(role)));
        }
        return DataObject.empty().put("roles", array);
    }

    /** The job's half of a self-role change: gives or takes the role in Discord. */
    static void applySelfRole(Guild guild, Member member, Role role, boolean on) {
        if (member.getRoles().contains(role) == on) return;
        if (on) DiscordJobs.send(guild.addRoleToMember(member, role).reason("Self-assigned on the website"));
        else DiscordJobs.send(guild.removeRoleFromMember(member, role).reason("Removed on the website"));
    }

    private DataObject toggleRole(Guild guild, long userId, DataObject body) {
        long roleId = parseId(body.getString("roleId", null), "roleId");
        boolean on = body.getBoolean("on", true);

        if (repository.selfRoles(guild.getIdLong()).stream().noneMatch(r -> r.roleId() == roleId)) throw new Rejected(403, "That role isn't self-assignable.");
        Role role = guild.getRoleById(roleId);
        if (role == null) throw new Rejected(404, "That role no longer exists.");
        if (!safeToAssign(guild, role)) throw new Rejected(403, "That role can't be assigned from here.");

        Member member = memberOf(guild, userId);
        // T-456: queued, so the page never waits on Discord; the website follows the job. The newest choice for this role wins.
        DiscordJob job = outbox.enqueue(SelfRoleJob.KIND, guild.getIdLong(), userId, "self-role:" + guild.getIdLong() + ":" + userId + ":" + roleId,
                DataObject.empty().put("userId", Long.toString(member.getIdLong())).put("roleId", Long.toString(roleId)).put("on", on));
        DataObject answer = roles(guild, userId);
        // show the role as it will be, not as Discord has it this instant
        for (int i = 0; i < answer.getArray("roles").length(); i++) {
            DataObject row = answer.getArray("roles").getObject(i);
            if (row.getString("roleId", "").equals(Long.toString(roleId))) row.put("has", on);
        }
        return answer.put("job", job.ticket());
    }

    // ---------- polls ----------

    private Member memberOf(Guild guild, long userId) {
        Member member = DiscordLookups.memberOrSlow(guild, userId);
        if (member == null) throw new Rejected(404, "You're not in the server.");
        return member;
    }

    /** Which options the member has picked in each active poll. */
    private DataObject myPolls(Guild guild, long userId) {
        DataArray array = DataArray.empty();
        for (PollSession poll : pollService.activePolls(guild.getIdLong())) {
            DataArray mine = DataArray.empty();
            pollService.myOptionNumbers(poll.pollId(), userId).forEach(mine::add);
            array.add(DataObject.empty().put("pollId", Long.toString(poll.pollId())).put("mine", mine));
        }
        return DataObject.empty().put("polls", array);
    }

    /** A member starts a poll from the website: same rules and limits as /poll, posted in the channel an admin chose for member polls. */
    private DataObject createPoll(Guild guild, long userId, DataObject body) {
        Member member = memberOf(guild, userId);
        if (!memberAccess.isMemberTier(guild, member)) throw new Rejected(403, "Starting a poll is for verified clan members.");

        Long channelId = community.pollChannel(guild.getIdLong());
        net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel channel = channelId == null ? null
                : guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, channelId);
        if (channel == null) throw new Rejected(409, "Member polls aren't set up yet — an admin needs to choose a channel for them.");
        if (!channel.canTalk()) throw new Rejected(502, "The bot can't post in the polls channel right now.");

        String title = body.getString("title", "").strip();
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
        if (problem != null) throw new Rejected(400, problem);

        if (pollService.activePollsOwnedBy(guild.getIdLong(), userId).size() >= 3) throw new Rejected(409, "You already have 3 polls running — end one first.");

        long pollId = pollService.createPoll(guild, channel, title, body.getBoolean("anonymous", false), body.getBoolean("multiple", false), options, userId);
        if (hours != null) community.scheduleClose(pollId, java.time.OffsetDateTime.now().plusHours(hours));
        cache.invalidate("polls");
        return DataObject.empty().put("created", true).put("channel", channel.getName());
    }

    /** What the member has put into and got out of the clan coffer: their donations (matched by their linked RuneScape names), balance and giveaways. */
    private DataObject myCoffer(Guild guild, long userId) {
        List<String> names = links.getLinksForUser(guild.getIdLong(), userId).stream().map(PlayerLink::rsn).toList();
        var donations = stats.donationsBy(guild.getIdLong(), names, 50);

        DataArray donationArray = DataArray.empty();
        donations.forEach(d -> donationArray.add(DataObject.empty().put("name", d.donorName()).put("amount", d.amount()).put("at", d.at().toString())));
        DataArray giveaways = DataArray.empty();
        stats.giveawaysTo(guild.getIdLong(), userId, 20).forEach(g -> giveaways.add(DataObject.empty().put("amount", g.amount()).put("description", g.description()).put("at", g.at().toString())));

        return DataObject.empty().put("linked", !names.isEmpty()).put("donated", donations.stream().mapToLong(SiteStatsRepository.MyDonation::amount).sum())
                .put("balance", stats.holderBalance(guild.getIdLong(), userId)).put("donations", donationArray).put("giveaways", giveaways);
    }

    /** Votes the way the Discord button does — same rules, same message update — so the site and the poll message always agree. */
    private DataObject votePoll(Guild guild, long userId, DataObject body) {
        long pollId = parseId(body.getString("pollId", null), "pollId");
        int number = body.getInt("optionNumber", -1);

        Member member = memberOf(guild, userId);
        if (!memberAccess.isMemberTier(guild, member)) throw new Rejected(403, "Voting is for verified clan members.");

        PollSession poll = pollService.getSessionById(pollId);
        if (poll == null || poll.guildId() != guild.getIdLong()) throw new Rejected(404, "That poll doesn't exist or has ended.");
        PollOption option = pollService.getOptions(pollId).stream().filter(o -> o.optionNumber() == number).findFirst().orElse(null);
        if (option == null) throw new Rejected(400, "That isn't an option in this poll.");

        if (pollService.toggleVote(pollId, option.optionId(), userId) == PollService.VoteResult.POLL_CLOSED) throw new Rejected(409, "That poll has ended.");
        // One Discord edit for a whole burst of votes (Discord rate-limits message edits), and drop the cached poll numbers now.
        cache.invalidate("polls");
        debouncer.run("poll:" + pollId, 1_200, () -> pollService.updateMessage(guild, pollId));
        return myPolls(guild, userId);
    }

    // ---------- signups ----------

    /** The signup sheets the member is currently on. */
    private DataObject mySignups(Guild guild, long userId) {
        DataArray joined = DataArray.empty();
        for (SignupSession session : signupService.getVisibleSignups(guild.getIdLong())) {
            if (signupService.getEntries(session.signupId()).stream().anyMatch(e -> e.userId() == userId)) joined.add(Long.toString(session.signupId()));
        }
        DataArray rsns = DataArray.empty();
        links.getLinksForUser(guild.getIdLong(), userId).forEach(l -> rsns.add(l.rsn()));
        return DataObject.empty().put("joined", joined).put("rsns", rsns);
    }

    private SignupSession ownSignup(Guild guild, long signupId) {
        SignupSession session = signupService.getSessionById(signupId);
        if (session == null || session.guildId() != guild.getIdLong()) throw new Rejected(404, "That signup doesn't exist any more.");
        return session;
    }

    private DataObject joinSignup(Guild guild, long userId, DataObject body) {
        long signupId = parseId(body.getString("signupId", null), "signupId");
        SignupSession session = ownSignup(guild, signupId);
        memberOf(guild, userId); // must be in the server, like clicking the button there
        if (!signupService.isSignupActive(signupId)) throw new Rejected(409, "This signup is paused right now.");

        String rsn;
        String submission = null;
        switch (session.type()) {
            case QUEUE -> {
                rsn = body.getString("rsn", "").strip();
                if (rsn.isEmpty()) rsn = links.getLinksForUser(guild.getIdLong(), userId).stream().findFirst().map(PlayerLink::rsn).orElse("");
                if (rsn.isEmpty() || rsn.length() > 50) throw new Rejected(400, "Enter your RuneScape name (up to 50 characters).");
            }
            case GROUP -> rsn = String.valueOf(userId);
            case SUBMISSION -> {
                rsn = String.valueOf(userId);
                List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                DataArray given = body.isNull("fields") ? DataArray.empty() : body.getArray("fields");
                List<String> values = new ArrayList<>();
                for (int i = 0; i < fields.size(); i++) {
                    String value = i < given.length() ? given.getString(i).strip() : "";
                    SubmissionField field = fields.get(i);
                    if (value.length() > 500) throw new Rejected(400, "\"" + field.label() + "\" is too long.");
                    if (field.required() && value.isEmpty()) throw new Rejected(400, "\"" + field.label() + "\" is required.");
                    if (!value.isEmpty() && !field.type().equals(SubmissionField.TYPE_TEXT) && !value.matches("^https?://\\S+$")) {
                        throw new Rejected(400, "\"" + field.label() + "\" needs a link starting with https://.");
                    }
                    values.add(value);
                }
                submission = SubmissionField.serializeValues(values);
            }
            default -> throw new Rejected(400, "That signup type isn't supported here.");
        }

        if (!signupService.addUser(guild, signupId, userId, rsn, submission)) throw new Rejected(409, "You're already signed up, or it's full.");
        cache.invalidate("signups");
        debouncer.run("signup:" + signupId, 1_200, () -> signupService.updateMessages(guild, signupId));
        return mySignups(guild, userId);
    }

    private DataObject leaveSignup(Guild guild, long userId, DataObject body) {
        long signupId = parseId(body.getString("signupId", null), "signupId");
        ownSignup(guild, signupId);
        if (!signupService.removeUser(guild, signupId, userId)) throw new Rejected(409, "You aren't on that signup.");
        cache.invalidate("signups");
        debouncer.run("signup:" + signupId, 1_200, () -> signupService.updateMessages(guild, signupId));
        return mySignups(guild, userId);
    }

    // ---------- helpers ----------

    private static DataObject readBody(HttpExchange exchange) {
        try (var in = exchange.getRequestBody()) {
            return DataObject.fromJson(in);
        } catch (Exception e) {
            throw new Rejected(400, "The request body isn't valid JSON.");
        }
    }

    private static long parseId(String raw, String name) {
        try {
            long id = Long.parseLong(raw == null ? "" : raw.trim());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new Rejected(400, "\"" + name + "\" is missing or not a valid id.");
        }
    }

    private static String query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
