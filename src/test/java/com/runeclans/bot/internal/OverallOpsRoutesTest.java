package com.runeclans.bot.internal;

import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.plugin.RuneSyncService;
import com.runeclans.bot.rsnlink.bridge.RsnLinkAdminApi;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The overall admin panel's read-only bot health: the path answers from the home server, anything else is not theirs. */
class OverallOpsRoutesTest {
    private final AdminOpsApi ops = mock(AdminOpsApi.class);
    private final RuneSyncService runeSync = mock(RuneSyncService.class);
    private final PremiumAdminApi premium = mock(PremiumAdminApi.class);
    private final RsnLinkAdminApi rsLink = mock(RsnLinkAdminApi.class);
    private final BotOwners owners = mock(BotOwners.class);
    private final JDA jda = mock(JDA.class);
    private final OverallOpsRoutes routes = new OverallOpsRoutes(ops, runeSync, owners, premium, rsLink);
    private final Guild home = mock(Guild.class);

    @Test
    void healthAnswersFromTheHomeServer() {
        when(ops.health(home)).thenReturn(DataObject.empty().put("uptimeSeconds", 5));
        assertEquals(5, routes.route(jda, 1L, home, "GET", new String[]{"health"}, DataObject::empty).getInt("uptimeSeconds"));
        assertNull(routes.route(jda, 1L, home, "GET", new String[]{"runemetrics-budget"}, DataObject::empty), "the RuneMetrics budget page is gone");
    }

    @Test
    void onlyReadingIsAllowedAndOtherPathsAreNotThese() {
        assertEquals(405, assertThrows(ApiError.class, () -> routes.route(jda, 1L, home, "POST", new String[]{"health"}, DataObject::empty)).status);
        assertNull(routes.route(jda, 1L, home, "GET", new String[]{"health", "more"}, DataObject::empty));
        assertNull(routes.route(jda, 1L, home, "GET", new String[]{"admins"}, DataObject::empty));
    }

    @Test
    void runeSyncDiagnosticsAreForTheBotOwnerOnly() {
        when(runeSync.diagnostic("RS-7K2M9Q", null)).thenReturn(null);
        assertEquals(403, assertThrows(ApiError.class, () -> routes.route(jda, 1L, home, "GET", new String[]{"runesync", "diagnostics", "RS-7K2M9Q"}, DataObject::empty)).status);
        when(owners.isOwner(jda, 1L)).thenReturn(true);
        assertEquals(405, assertThrows(ApiError.class, () -> routes.route(jda, 1L, home, "POST", new String[]{"runesync", "diagnostics", "RS-7K2M9Q"}, DataObject::empty)).status);
    }

    @Test
    void rsLinkGoesToTheRsLinkApiAsTheAskingAdmin() {
        when(rsLink.routeFor(org.mockito.ArgumentMatchers.eq(jda), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("GET"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(DataObject.empty().put("activeLinks", 3));
        assertEquals(3, routes.route(jda, 1L, home, "GET", new String[]{"rsnlink", "overview"}, DataObject::empty).getInt("activeLinks"));
    }

    @Test
    void premiumPlansGoToThePlansApiAsTheAskingAdmin() {
        when(premium.routeFor(org.mockito.ArgumentMatchers.eq(home), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("GET"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(DataObject.empty().put("canEdit", true));
        assertEquals(true, routes.route(jda, 1L, home, "GET", new String[]{"premium-guilds"}, DataObject::empty).getBoolean("canEdit"));
    }
}
