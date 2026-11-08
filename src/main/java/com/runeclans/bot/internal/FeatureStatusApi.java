package com.runeclans.bot.internal;

import com.runeclans.bot.automod.AutomodRepository;
import com.runeclans.bot.branding.BrandingRepository;
import com.runeclans.bot.checkup.ServerCheckup;
import com.runeclans.bot.checkup.ServerCheckup.Level;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.PublicEvents;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.hub.HubCommand;
import com.runeclans.bot.hub.HubService;
import com.runeclans.bot.lookup.ReminderKind;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.tracking.TrackingGroup;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@code GET /internal/admin/feature-status} (T-461): every dashboard tab's on/off dot in one answer, {@code {"features": {"<tab path>": "on" | "off" |
 * "na"}, "generatedAt": "<ISO instant>"}}, so the dashboard's sidebar counts, tab dots and Feature map cost one request instead of about fifty. Each
 * answer is the same rule the dashboard applied to that page's own read (runeclans packages/dashboard/src/lib/featureStatus.ts).
 * <p>
 * Read from the database (most of it in one query, {@link FeatureStatusRepository}) and the bot's in-memory copy of the server; never a call to Discord.
 * "na" means no dot: the Overview and the Feature map are views, the hub-owned pages ({@link #HUB_OWNED}) are read from the hub, a page this person can't open (the Admin-only role pages for a Developer, the
 * owner's Snapshots) stays grey as it did, and so does every stored dot if the query fails, as a failed read always left it. The dashboard reads a
 * tab the old way only when this answer doesn't name it (a tab newer than this bot), or for every tab from an older bot without this route.
 * <p>
 * An {@link AdminRouteExtension}, so it gets the same dashboard access check as every per-server admin route.
 */
@BService
public class FeatureStatusApi implements AdminRouteExtension {
    /**
     * Every tab of the dashboard's nav, by its path (runeclans packages/dashboard/src/components/shell/nav.ts, T-460). The website's test checks its
     * copy (packages/api-client FEATURE_STATUS_TABS) against the nav and against this list, and {@code FeatureStatusApiTest} checks each has an answer.
     */
    static final List<String> TABS = List.of(
            "", "/feature-map",
            "/setup/guide", "/setup/settings", "/setup/permissions", "/setup/commands", "/setup/look", "/setup/checkup",
            "/joins/welcome", "/joins/goodbye", "/joins/newaccounts",
            "/roles/autoroles", "/roles/self-roles", "/roles/role-panels", "/roles/role-keeper", "/roles/role-rules", "/roles/role-toolkit",
            "/mod/automod", "/mod/warnings", "/mod/cases", "/mod/appeals", "/mod/lockdown",
            "/protection/autoclean", "/protection/autodelete", "/protection/honeypot", "/protection/servertags",
            "/logs/modlog", "/logs/message-logs", "/logs/audit", "/logs/staff-digest",
            "/messages/post", "/messages/scheduled", "/messages/server-reminders", "/messages/autoresponder", "/messages/custom-tags", "/messages/embedder",
            "/alerts/feeds", "/alerts/twitch", "/alerts/kick", "/alerts/highlights",
            "/community/polls", "/community/giveaways", "/community/suggestions", "/community/starboard", "/community/tickets", "/community/forms",
            "/levels/points", "/levels/chat-levels", "/levels/skill-week",
            "/voice/temp-voice", "/voice/voice-text",
            "/event-tools/event-manager", "/event-tools/event-announcer", "/event-tools/discord-events", "/event-tools/citadel-wheel",
            "/event-tools/coffer",
            "/clan/clan-profile", "/clan/members", "/clan/attention", "/clan/promotions", "/clan/clan-points", "/clan/role-sync",
            "/runescape/tracking", "/runescape/rs-timers", "/runescape/anniversaries", "/runescape/pvm-help", "/runescape/alliances", "/runescape/plugin",
            "/tools/snapshots");

    /** The tabs that are views, not features: never a dot. */
    static final Set<String> VIEWS = Set.of("", "/feature-map");

    /** The tabs whose feature lives on the RuneClans hub (T-941; the event manager, T-942), which this one query can't read: no dot, like a view. */
    static final Set<String> HUB_OWNED = Set.of("/levels/skill-week", "/event-tools/event-manager");

    /** The role pages the bot answers only to an Admin (RoleKeepAdminApi and the others): a Developer never had a dot for them. */
    static final Set<String> ADMIN_ONLY = Set.of("/roles/role-keeper", "/roles/role-rules", "/roles/role-toolkit");

    private final FeatureStatusRepository repository;
    private final GuildSettingsService settings;
    private final PermissionGroupService groups;
    private final HubService hub;
    private final AutomodRepository automod;
    private final BrandingRepository branding;
    private final CheckupAdminApi checkup;
    private final ClanProfileRepository clanProfiles;
    private final DashboardAccess access;
    private final BotOwners owners;
    private final com.runeclans.bot.runescape.ClanPointsRepository clanPoints;

    public FeatureStatusApi(FeatureStatusRepository repository, GuildSettingsService settings, PermissionGroupService groups, HubService hub,
                            AutomodRepository automod, BrandingRepository branding, CheckupAdminApi checkup, ClanProfileRepository clanProfiles,
                            DashboardAccess access, BotOwners owners, com.runeclans.bot.runescape.ClanPointsRepository clanPoints) {
        this.clanPoints = clanPoints;
        this.repository = repository;
        this.settings = settings;
        this.groups = groups;
        this.hub = hub;
        this.automod = automod;
        this.branding = branding;
        this.checkup = checkup;
        this.clanProfiles = clanProfiles;
        this.access = access;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("feature-status")) return null;
        AdminRouteExtension.requireMethod(method, "GET");
        Map<String, Boolean> on = statuses(guild, access.tierOf(guild, actor) == DashboardAccess.Tier.ADMIN, owners.isOwner(guild.getJDA(), actor.getIdLong()));
        DataObject features = DataObject.empty();
        for (String tab : TABS) {
            Boolean state = on.get(tab);
            features.put(tab, state == null ? "na" : state ? "on" : "off");
        }
        return DataObject.empty().put("features", features).put("generatedAt", Instant.now().toString());
    }

    /** On/off by tab path; a tab left out has no dot ("na"). */
    Map<String, Boolean> statuses(Guild guild, boolean admin, boolean owner) {
        long guildId = guild.getIdLong();
        Map<String, Boolean> on = new LinkedHashMap<>();
        GuildSettings s = settings.getEffective(guildId);
        List<PermissionGroup> permissionGroups = groups.groups(guildId);

        on.put("/setup/settings", s.clanEnabled());
        on.put("/setup/permissions", permissionGroups.stream().anyMatch(g -> !g.roleIds().isEmpty()));
        on.put("/setup/commands", Arrays.stream(HubCommand.values()).anyMatch(c -> hub.get(guildId, c).enabled()));
        BrandingRepository.Profile profile = branding.profile(guildId);
        var self = guild.getSelfMember();
        on.put("/setup/look", (profile.displayName() != null && !profile.displayName().isBlank()) || self.getNickname() != null || self.getAvatarUrl() != null);
        AutomodRepository.Config mod = automod.get(guildId);
        on.put("/mod/automod", mod.enabled() && mod.rules().values().stream().anyMatch(r -> r.enabled()));
        for (String clanTab : List.of("/clan/members", "/clan/attention", "/clan/promotions")) on.put(clanTab, s.clanEnabled());
        on.put("/clan/role-sync", s.clanEnabled() && s.verifiedClanRoleId() != null);
        on.put("/event-tools/discord-events", sharedEventAhead(guild));
        // clan points are the hub's (T-978): on once any amount is set; no dot while the hub can't say
        net.dv8tion.jda.api.utils.data.DataObject points = clanPoints.known(guildId);
        if (points != null) {
            var amounts = com.runeclans.bot.runescape.ClanPointsRepository.settingsOf(points);
            on.put("/clan/clan-points", amounts.dailyMembershipPoints() > 0 || amounts.citadelVisitPoints() > 0 || amounts.citadelCapPoints() > 0);
        }

        FeatureStatusRepository.Facts facts = repository.read(guildId);
        if (facts != null) {
            for (String tab : TABS) if (tab.startsWith("/") && facts.flags().containsKey(tab)) on.put(tab, facts.flag(tab));

            List<ServerCheckup.TrackingState> tracking = new ArrayList<>();
            for (TrackingGroup group : TrackingGroup.values())
                tracking.add(new ServerCheckup.TrackingState(group.displayName(), !facts.trackingOff().contains(group.name()),
                        facts.trackingChannels().getOrDefault(group.name(), List.of())));
            on.put("/runescape/tracking", tracking.stream().anyMatch(ServerCheckup.TrackingState::enabled));
            on.put("/setup/checkup", checkup.findings(guild, tracking).stream().noneMatch(f -> f.level() == Level.ERROR));

            // the Overview's setup checklist, all done (runeclans setupChecklist.ts)
            boolean admins = permissionGroups.stream().anyMatch(g -> g.key().equals("admin") && !g.roleIds().isEmpty());
            boolean feeds = tracking.stream().anyMatch(t -> t.enabled() && !t.channelIds().isEmpty());
            on.put("/setup/guide", admins && s.clanActive() && s.verificationReviewChannelId() != null && feeds && facts.flag("welcomeWithChannel"));

            Set<String> groupKeys = permissionGroups.stream().map(PermissionGroup::key).collect(Collectors.toSet());
            on.put("/roles/role-toolkit", facts.toolkitGroups().stream().anyMatch(groupKeys::contains) || facts.flag("toolkitJobs"));
            on.put("/runescape/rs-timers", Arrays.stream(ReminderKind.values()).anyMatch(k -> facts.enabledLookupKinds().contains(k.key())));
        }

        if (!admin) ADMIN_ONLY.forEach(on::remove);
        if (!owner) on.remove("/tools/snapshots");
        return on;
    }

    /** Discord events: an upcoming event of the server's own that it shares on its public page (ClanEventsAdminApi's "shared"). From JDA's cache. */
    private boolean sharedEventAhead(Guild guild) {
        List<PublicEvents.EventInfo> upcoming = DirectoryApi.eventsOf(guild).stream().filter(e -> PublicEvents.upcoming(e, Instant.now())).toList();
        if (upcoming.isEmpty()) return false;
        Map<Long, String> scopes = clanProfiles.eventScopes(guild.getIdLong());
        return upcoming.stream().anyMatch(e -> "PUBLIC".equals(scopes.get(e.id())));
    }
}
