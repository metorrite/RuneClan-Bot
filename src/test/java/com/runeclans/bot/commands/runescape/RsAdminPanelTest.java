package com.runeclans.bot.commands.runescape;

import com.runeclans.bot.commands.configure.ConfigureInteractionListener;
import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.runescape.ClanSyncService;
import com.runeclans.bot.tracking.WeeklyDigestService;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The admin panel's clan sections only exist when the server has a clan; the Support tier sees only the request-review tools. */
class RsAdminPanelTest {
    private ClanSyncService clanSyncService;
    private AdminRoleFilter adminRoleFilter;
    private WeeklyDigestService weeklyDigestService;
    private RsAdminInteractionListener listener;
    private Guild guild;
    private Member viewer;

    @BeforeEach
    void setUp() {
        clanSyncService = mock(ClanSyncService.class);
        adminRoleFilter = mock(AdminRoleFilter.class);
        weeklyDigestService = mock(WeeklyDigestService.class);
        listener = new RsAdminInteractionListener(null, null, adminRoleFilter, null, null, clanSyncService, null, null, null, null, null,
                mock(ConfigureInteractionListener.class), weeklyDigestService, null);
        guild = mock(Guild.class);
        viewer = mock(Member.class);
        when(guild.getIdLong()).thenReturn(1L);
    }

    private static List<String> ids(Container panel) {
        return ComponentTree.of(List.of(panel)).findAll(Button.class).stream().map(Button::getCustomId).toList();
    }

    private void asAdmin() {
        when(adminRoleFilter.isAuthorized(guild, viewer)).thenReturn(true);
        when(adminRoleFilter.isSupportTier(guild, viewer)).thenReturn(true);
    }

    private void asSupportOnly() {
        when(adminRoleFilter.isAuthorized(guild, viewer)).thenReturn(false);
        when(adminRoleFilter.isSupportTier(guild, viewer)).thenReturn(true);
    }

    @Test
    void withAClanEverythingShowsAndFitsInAMessage() {
        asAdmin();
        when(clanSyncService.getClanName(1L)).thenReturn("Younglings");
        Container panel = listener.buildPanel(guild, viewer);
        new MessageCreateBuilder().useComponentsV2(true).setComponents(panel).build();

        assertTrue(ids(panel).contains("rsnadmin_syncclan:_"));
        assertTrue(ids(panel).contains("clanpoints_open:_"));
        assertTrue(ids(panel).contains("rsnadmin_open_configure:_"));
    }

    @Test
    void withoutAClanTheClanToolsAreHiddenButConfigureIsStillThere() {
        asAdmin();
        when(clanSyncService.getClanName(1L)).thenReturn(null);
        Container panel = listener.buildPanel(guild, viewer);
        new MessageCreateBuilder().useComponentsV2(true).setComponents(panel).build();

        assertFalse(ids(panel).contains("rsnadmin_syncclan:_"));
        assertFalse(ids(panel).contains("rsnadmin_clanlist:_"));
        assertFalse(ids(panel).contains("clanpoints_open:_"));
        assertTrue(ids(panel).contains("rsnadmin_open_configure:_"));
        assertTrue(ids(panel).contains("rsnadmin_manualverify:_"), "manual verification must stay so a brand-new server can bootstrap");
    }

    @Test
    void theSupportTierSeesOnlyTheRequestReviewTools() {
        asSupportOnly();
        when(clanSyncService.getClanName(1L)).thenReturn("Younglings");
        Container panel = listener.buildPanel(guild, viewer);
        new MessageCreateBuilder().useComponentsV2(true).setComponents(panel).build();

        assertEquals(Set.of("rsnadmin_review_pending:_", "rsnadmin_manualverify:_", "rsnadmin_post_pending:_"), Set.copyOf(ids(panel)));
    }

    @Test
    void everyButtonOnTheSupportPanelIsAllowedToSupport() {
        asSupportOnly();
        for (String id : ids(listener.buildPanel(guild, viewer))) {
            assertTrue(RsAdminInteractionListener.SUPPORT_ACTIONS.contains(id.split(":")[0]), id);
        }
    }

    @Test
    void theSupportTierCannotReachBulkOrAdminActions() {
        for (String action : List.of("rsnadmin_review_approve_all", "rsnadmin_review_approve_all_confirm", "rsnadmin_poll_all",
                "rsnadmin_syncclan", "rsnadmin_lookup", "rsnadmin_unlink", "rsnadmin_open_configure", "rsnadmin_joindate")) {
            assertFalse(RsAdminInteractionListener.SUPPORT_ACTIONS.contains(action), action);
        }
        // but the per-request review steps and the manual verify form are open to it
        for (String action : List.of("rsnadmin_review_approve", "rsnadmin_review_reject", "rsnadmin_manualverify_modal")) {
            assertTrue(RsAdminInteractionListener.SUPPORT_ACTIONS.contains(action), action);
        }
    }

    @Test
    void theGuestsWithoutALinkListIsForAdminsOnly() {
        when(clanSyncService.getClanName(1L)).thenReturn("Younglings");

        asAdmin();
        Container panel = listener.buildPanel(guild, viewer);
        new MessageCreateBuilder().useComponentsV2(true).setComponents(panel).build();
        assertTrue(ids(panel).contains("rsnadmin_guests:_"));

        asSupportOnly();
        assertFalse(ids(listener.buildPanel(guild, viewer)).contains("rsnadmin_guests:_"));
        assertFalse(RsAdminInteractionListener.SUPPORT_ACTIONS.contains("rsnadmin_guests"));
        assertFalse(RsAdminInteractionListener.SUPPORT_ACTIONS.contains("rsnadmin_guests_page"));
    }

    @Test
    void theCitadelViewerOffersQuickPicksARangeAndTwelveWeeksAndFitsInAMessage() {
        when(weeklyDigestService.citadelSection(1L, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 5)))
                .thenReturn(List.of(TextDisplay.of("### Citadel"), TextDisplay.of("totals"), TextDisplay.of("**Visited & capped (1)**\nAxley")));

        Container viewer = listener.buildCitadelViewer(guild, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 5));
        new MessageCreateBuilder().useComponentsV2(true).setComponents(viewer).build();

        assertEquals(List.of("rsnadmin_citadel_view:current", "rsnadmin_citadel_view:last", "rsnadmin_citadel_range:_", "rsnadmin_back:_"), ids(viewer));
        var menus = ComponentTree.of(List.of(viewer)).findAll(StringSelectMenu.class);
        assertEquals(1, menus.size());
        assertEquals("rsnadmin_citadel_week:_", menus.getFirst().getCustomId());
        assertEquals(12, menus.getFirst().getOptions().size());
    }

    @Test
    void theCitadelViewerIsNotSomethingSupportCanReach() {
        for (String action : List.of("rsnadmin_citadel", "rsnadmin_citadel_view", "rsnadmin_citadel_range", "rsnadmin_citadel_range_modal",
                "rsnadmin_citadel_week", "rsnadmin_back")) {
            assertFalse(RsAdminInteractionListener.SUPPORT_ACTIONS.contains(action), action);
        }
    }

    @Test
    void theCoffersMenuIsReachedFromTheAdminPanelButNotFromTheSupportPanel() {
        when(clanSyncService.getClanName(1L)).thenReturn("Younglings");

        asAdmin();
        assertTrue(ids(listener.buildPanel(guild, viewer)).contains("rsnadmin_coffer:_"));
        assertFalse(RsAdminInteractionListener.SUPPORT_ACTIONS.contains("rsnadmin_coffer"));

        asSupportOnly();
        assertFalse(ids(listener.buildPanel(guild, viewer)).contains("rsnadmin_coffer:_"));
    }
}
