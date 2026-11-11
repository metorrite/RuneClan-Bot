package com.runeclans.bot.hubsites;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With SITES_BACKEND=hub every site write route answers "moved" and every read keeps working (T-506, D-19). */
class SitesMovedTest {
    private static final String A = "/internal/admin/";

    /** Every site write route of ClanSiteAdminApi, SiteOwnerAdminApi, SitePictureAdminApi, ClanProfileAdminApi, AllianceAdminApi, ClanRecruitingAdminApi. */
    static final List<String[]> WRITES = List.of(
            new String[]{"PUT", "clan-site"},
            new String[]{"PUT", "clan-site/layout"},
            new String[]{"POST", "clan-site/layout/publish"},
            new String[]{"POST", "clan-site/layout/versions/12/restore"},
            new String[]{"POST", "clan-site/publish"},
            new String[]{"POST", "clan-site/unpublish"},
            new String[]{"PUT", "clan-site/blocks/home"},
            new String[]{"PUT", "clan-site/roles"},
            new String[]{"PUT", "clan-site/roles/admin-access"},
            new String[]{"POST", "clan-site/roles/hand-over"},
            new String[]{"POST", "clan-site/address"},
            new String[]{"POST", "clan-site/address/lock-in"},
            new String[]{"POST", "clan-site/address/cancel"},
            new String[]{"POST", "clan-site/delete"},
            new String[]{"POST", "clan-site/takedown"},
            new String[]{"POST", "clan-site/restore"},
            new String[]{"POST", "owner-sites/test"},
            new String[]{"POST", "owner-sites/7/address"},
            new String[]{"POST", "owner-sites/7/owner"},
            new String[]{"POST", "owner-sites/7/disable"},
            new String[]{"POST", "owner-sites/7/enable"},
            new String[]{"POST", "owner-sites/7/delete"},
            new String[]{"POST", "owner-sites/7/reset-draft"},
            new String[]{"POST", "overall/owner-sites/7/address"},
            new String[]{"PUT", "site-pictures"},
            new String[]{"DELETE", "site-pictures/abc"},
            new String[]{"PUT", "images/site/logo"},
            new String[]{"DELETE", "images/site/logo"},
            new String[]{"PUT", "clan-profile"},
            new String[]{"PUT", "clan-recruiting"},
            new String[]{"POST", "alliances"},
            new String[]{"PUT", "alliances/visibility"},
            new String[]{"POST", "alliances/3/invite"},
            new String[]{"POST", "alliances/3/accept"},
            new String[]{"POST", "alliances/3/leave"},
            new String[]{"POST", "alliances/3/remove"});

    @Test
    void everySiteWriteIsMoved() {
        for (String[] w : WRITES) assertTrue(SitesMoved.movedWrite(w[0], A + w[1]), w[0] + " " + w[1]);
        assertTrue(SitesMoved.movedWrite("put", A + "clan-site/layout/"), "any case, trailing slash");
    }

    @Test
    void readsKeepWorking() {
        for (String[] w : WRITES) assertFalse(SitesMoved.movedWrite("GET", A + w[1]), "GET " + w[1]);
        assertFalse(SitesMoved.movedWrite("HEAD", A + "clan-site"));
    }

    @Test
    void whatStaysWithTheBotIsNotMoved() {
        assertFalse(SitesMoved.movedWrite("PUT", A + "clan-site/connections"), "channel picks stay bot mappings");
        assertFalse(SitesMoved.movedWrite("POST", A + "clan-site/connections/connect"));
        assertFalse(SitesMoved.movedWrite("PUT", A + "clan-site/clan-name"), "the server's clan name is a bot setting");
        assertFalse(SitesMoved.movedWrite("PUT", A + "clan-site/member-options"), "nickname and colour changes are the bot's Discord actions (T-976)");
        assertFalse(SitesMoved.movedWrite("PUT", A + "clan-site/seasons/settings"), "the hub has no seasons route yet (B-84)");
        assertFalse(SitesMoved.movedWrite("PUT", A + "overall/clan-site/seasons/settings/"), "B-84, overall prefix and trailing slash");
        assertFalse(SitesMoved.movedWrite("POST", A + "clan-site/seasons"), "B-84");
        assertFalse(SitesMoved.movedWrite("POST", A + "clan-profile/verify"), "verification isn't a profile edit");
        assertFalse(SitesMoved.movedWrite("PUT", A + "images/embed/banner"), "pictures that aren't the site's");
        assertFalse(SitesMoved.movedWrite("PUT", A + "selfroles"));
        assertFalse(SitesMoved.movedWrite("POST", A + "overall/beta-testers"));
        assertFalse(SitesMoved.movedWrite("POST", "/internal/directory/sites/x/discord/me"), "only the dashboard routes");
        assertFalse(SitesMoved.movedWrite("POST", A));
    }

    @Test
    void theAnswerSaysMoved() {
        assertEquals("hub", SitesMoved.answer().getString("moved"));
    }
}
