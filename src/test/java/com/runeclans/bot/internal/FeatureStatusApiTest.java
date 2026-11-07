package com.runeclans.bot.internal;

import com.runeclans.bot.automod.AutomodRepository;
import com.runeclans.bot.branding.BrandingRepository;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.hub.HubCommand;
import com.runeclans.bot.hub.HubService;
import com.runeclans.bot.hub.HubSettings;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.tracking.TrackingGroup;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;

/** {@code GET feature-status} (T-461): every dashboard tab gets an answer, so a new tab can't be forgotten, and the "na"s are the right ones. */
class FeatureStatusApiTest {
    private static final long G = 999_000_000_000_000_462L;
    private FeatureStatusRepository repository;
    private DashboardAccess access;
    private BotOwners owners;
    private FeatureStatusApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(FeatureStatusRepository.class);
        Map<String, Boolean> flags = new LinkedHashMap<>();
        FeatureStatusRepository.FLAGS.keySet().forEach(k -> flags.put(k, true));
        Mockito.when(repository.read(G)).thenReturn(new FeatureStatusRepository.Facts(flags, Set.of("admin"), Set.of("vos"), Set.of(),
                Map.of(TrackingGroup.values()[0].name(), List.of(1L))));

        GuildSettingsService settings = Mockito.mock(GuildSettingsService.class);
        Mockito.when(settings.getEffective(G)).thenReturn(new GuildSettings(G, "Clan", 1L, null, 2L, 3L, null, null, null, true, "Clan", null, null));
        PermissionGroupService groups = Mockito.mock(PermissionGroupService.class);
        Mockito.when(groups.groups(G)).thenReturn(List.of(new PermissionGroup(1, G, "admin", "Admin", true, false, List.of(9L))));
        HubService hub = Mockito.mock(HubService.class);
        Mockito.when(hub.get(anyLong(), any(HubCommand.class))).thenAnswer(a -> HubSettings.defaults(G, ((HubCommand) a.getArgument(1)).key()));
        AutomodRepository automod = Mockito.mock(AutomodRepository.class);
        Mockito.when(automod.get(G)).thenReturn(new AutomodRepository.Config(G, false, Set.of(), Set.of(), Map.of()));
        BrandingRepository branding = Mockito.mock(BrandingRepository.class);
        Mockito.when(branding.profile(G)).thenReturn(new BrandingRepository.Profile(null, null, false));
        CheckupAdminApi checkup = Mockito.mock(CheckupAdminApi.class);
        Mockito.when(checkup.findings(any(Guild.class), anyList())).thenReturn(List.of());
        access = Mockito.mock(DashboardAccess.class);
        owners = Mockito.mock(BotOwners.class);
        api = new FeatureStatusApi(repository, settings, groups, hub, automod, branding, checkup, Mockito.mock(ClanProfileRepository.class), access, owners);

        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Mockito.when(guild.getSelfMember()).thenReturn(Mockito.mock(SelfMember.class));
        Mockito.when(guild.getScheduledEvents()).thenReturn(List.of());
        Mockito.when(guild.getJDA()).thenReturn(Mockito.mock(JDA.class));
        actor = Mockito.mock(Member.class);
        Mockito.when(actor.getIdLong()).thenReturn(42L);
    }

    private DataObject features(DashboardAccess.Tier tier, boolean owner) {
        Mockito.when(access.tierOf(guild, actor)).thenReturn(tier);
        Mockito.when(owners.isOwner(any(), Mockito.eq(42L))).thenReturn(owner);
        DataObject answer = api.route(guild, actor, "GET", new String[]{"feature-status"}, DataObject::empty);
        assertTrue(answer.hasKey("generatedAt"));
        return answer.getObject("features");
    }

    @Test
    void everyTabHasAnOnOrOffAnswerButTheViews() {
        DataObject features = features(DashboardAccess.Tier.ADMIN, true);
        assertEquals(new HashSet<>(FeatureStatusApi.TABS), features.keys());
        for (String tab : FeatureStatusApi.TABS) {
            String state = features.getString(tab);
            if (FeatureStatusApi.VIEWS.contains(tab) || FeatureStatusApi.HUB_OWNED.contains(tab)) assertEquals("na", state, tab);
            else assertTrue(state.equals("on") || state.equals("off"), tab + " has no rule: add it to FeatureStatusApi or FeatureStatusRepository");
        }
        assertEquals(FeatureStatusApi.TABS.size(), new HashSet<>(FeatureStatusApi.TABS).size(), "a tab is listed twice");
    }

    @Test
    void theAnswersFollowTheSettings() {
        DataObject features = features(DashboardAccess.Tier.ADMIN, true);
        assertEquals("on", features.getString("/setup/settings"));
        assertEquals("on", features.getString("/setup/commands"), "commands are on by default");
        assertEquals("off", features.getString("/setup/look"), "no custom name, nickname or avatar");
        assertEquals("off", features.getString("/mod/automod"));
        assertEquals("on", features.getString("/setup/guide"), "admins, clan, review channel, a feed and the welcome are all set");
        assertEquals("on", features.getString("/runescape/rs-timers"));
        assertEquals("on", features.getString("/roles/role-toolkit"));
        assertEquals("off", features.getString("/event-tools/discord-events"));
    }

    @Test
    void pagesThisPersonCantOpenHaveNoDot() {
        DataObject features = features(DashboardAccess.Tier.DEVELOPER, false);
        for (String tab : FeatureStatusApi.ADMIN_ONLY) assertEquals("na", features.getString(tab), tab);
        assertEquals("na", features.getString("/tools/snapshots"));
        assertEquals("on", features.getString("/joins/welcome"));
    }

    @Test
    void aFailedQueryLeavesTheStoredDotsGrey() {
        Mockito.when(repository.read(G)).thenReturn(null);
        DataObject features = features(DashboardAccess.Tier.ADMIN, true);
        assertEquals("na", features.getString("/joins/welcome"));
        assertEquals("na", features.getString("/setup/checkup"));
        assertEquals("on", features.getString("/setup/settings"), "the settings don't come from that query");
    }

    @Test
    void everyStoredFlagIsATabOrAHelper() {
        for (String key : FeatureStatusRepository.FLAGS.keySet()) {
            if (key.startsWith("/")) assertTrue(FeatureStatusApi.TABS.contains(key), key + " isn't a tab");
            else assertNotEquals("", key);
        }
    }

    @Test
    void otherPathsAreNotThisRoute() {
        assertNull(api.route(guild, actor, "GET", new String[]{"feature-status", "x"}, DataObject::empty));
        assertNull(api.route(guild, actor, "GET", new String[]{"setup"}, DataObject::empty));
    }
}
