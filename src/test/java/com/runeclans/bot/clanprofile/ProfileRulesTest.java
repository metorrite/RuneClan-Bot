package com.runeclans.bot.clanprofile;

import com.runeclans.bot.clanprofile.ClanProfile.Visibility;
import com.runeclans.bot.clanprofile.ProfileRules.Listing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileRulesTest {
    private static ClanProfile profile(Visibility visibility, String verifiedFor, Instant suspended) {
        return new ClanProfile(1, visibility, "younglings", "Younglings", "", "", null, null, null, EnumSet.noneOf(ClanProfile.Focus.class),
                ClanProfile.Recruiting.CLOSED, "", null, false, verifiedFor, verifiedFor == null ? null : Instant.EPOCH, null, suspended, suspended == null ? null : "spam", null);
    }

    @Test
    void slugsAreMadeFromNames() {
        assertEquals("the-younglings", ProfileRules.slugify("The Younglings!"));
        assertEquals("eternal-rising", ProfileRules.slugify("  Eternal   Rising "));
        assertEquals("", ProfileRules.slugify("!!!"));
        assertEquals("", ProfileRules.slugify(null));
        assertTrue(ProfileRules.slugify("a".repeat(80)).length() <= ProfileRules.MAX_SLUG);
    }

    @Test
    void slugsMustBeSensibleAndNotCollideWithDirectoryPages() {
        assertNull(ProfileRules.slugProblem("younglings"));
        assertNull(ProfileRules.slugProblem("eternal-rising-2"));
        assertNotNull(ProfileRules.slugProblem("ab"));
        assertNotNull(ProfileRules.slugProblem("Upper"));
        assertNotNull(ProfileRules.slugProblem("double--dash"));
        assertNotNull(ProfileRules.slugProblem("-lead"));
        assertNotNull(ProfileRules.slugProblem("search"));
        assertNotNull(ProfileRules.slugProblem("a".repeat(33)));
    }

    @Test
    void thePlatformsOwnSubdomainsAreReserved() {
        // a clan's address is its website's subdomain (T-290), so the site's own hosts can never be one
        for (String name : new String[] {"www", "api", "admin", "link", "dashboard", "hub", "auth", "mail", "status", "docs", "cdn", "assets", "static", "dev",
                "staging", "beta", "bot", "browse", "jonnybot"}) {
            assertNotNull(ProfileRules.slugProblem(name), name + " should be reserved");
        }
        assertNull(ProfileRules.slugProblem("green-angels"));
        assertNull(ProfileRules.slugProblem("beta-testers"), "only the whole word is reserved");
    }

    @Test
    void addressesThatImpersonateOrOffendAreRefused() {
        assertNotNull(ProfileRules.slugProblem("jagex-official"));
        assertNotNull(ProfileRules.slugProblem("thejagexteam"), "jagex anywhere");
        assertNotNull(ProfileRules.slugProblem("runeclans-support"));
        assertNotNull(ProfileRules.slugProblem("clan-mods"));
        assertNotNull(ProfileRules.slugProblem("jmodsquad"));
        // whole words only for the ordinary ones: these are fine
        assertNull(ProfileRules.slugProblem("modern-warriors"));
        assertNull(ProfileRules.slugProblem("essex-pvm"));
        assertNull(ProfileRules.slugProblem("the-officials-club"));
        assertNull(ProfileRules.slugProblem("team-rocket"));
    }

    @Test
    void aGoodProfileHasNoFieldProblems() {
        assertTrue(ProfileRules.fieldProblems("Younglings", "Friendly PvM", "About us", "Combat 100+", "https://example.com/logo.png", "https://discord.gg/abc", 0x336699, "America/New_York").isEmpty());
        assertTrue(ProfileRules.fieldProblems("Younglings", null, null, null, null, null, null, null).isEmpty());
    }

    @Test
    void textLengthsAreBounded() {
        assertEquals(1, ProfileRules.fieldProblems("", null, null, null, null, null, null, null).size());
        assertEquals(1, ProfileRules.fieldProblems("n".repeat(ProfileRules.MAX_NAME + 1), null, null, null, null, null, null, null).size());
        assertEquals(1, ProfileRules.fieldProblems("ok", "t".repeat(ProfileRules.MAX_TAGLINE + 1), null, null, null, null, null, null).size());
        assertEquals(1, ProfileRules.fieldProblems("ok", null, "d".repeat(ProfileRules.MAX_DESCRIPTION + 1), null, null, null, null, null).size());
        assertEquals(1, ProfileRules.fieldProblems("ok", null, null, "r".repeat(ProfileRules.MAX_REQUIREMENTS + 1), null, null, null, null).size());
    }

    @Test
    void onlyPlainHttpsLinksAreAccepted() {
        assertNull(ProfileRules.urlProblem(null));
        assertNull(ProfileRules.urlProblem("  "));
        assertNull(ProfileRules.urlProblem("https://discord.gg/abc"));
        assertNotNull(ProfileRules.urlProblem("http://example.com"));
        assertNotNull(ProfileRules.urlProblem("javascript:alert(1)"));
        assertNotNull(ProfileRules.urlProblem("https://exa mple.com"));
        assertNotNull(ProfileRules.urlProblem("https://nodot"));
        assertNotNull(ProfileRules.urlProblem("https://" + "a".repeat(ProfileRules.MAX_URL) + ".com"));
    }

    @Test
    void coloursAndTimeZonesAreChecked() {
        assertEquals(1, ProfileRules.fieldProblems("ok", null, null, null, null, null, -1, null).size());
        assertEquals(1, ProfileRules.fieldProblems("ok", null, null, null, null, null, 0x1000000, null).size());
        assertEquals(1, ProfileRules.fieldProblems("ok", null, null, null, null, null, null, "Mars/Olympus").size());
    }

    @Test
    void onlyClanLeadershipMayVerify() {
        assertTrue(ProfileRules.isLeadership("Owner"));
        assertTrue(ProfileRules.isLeadership("Deputy Owner"));
        assertFalse(ProfileRules.isLeadership(" overseer "));
        assertTrue(ProfileRules.isLeadership("DEPUTY_OWNER"));
        assertFalse(ProfileRules.isLeadership("Coordinator"));
        assertFalse(ProfileRules.isLeadership("General"));
        assertFalse(ProfileRules.isLeadership("Recruit"));
        assertFalse(ProfileRules.isLeadership(null));
    }

    @Test
    void verificationExplainsWhatIsMissing() {
        assertTrue(ProfileRules.verificationProblem(null, 0, false, 0, List.of()).contains("Server setup"));
        assertTrue(ProfileRules.verificationProblem("Younglings", 0, false, 0, List.of()).contains("couldn't find"));
        assertTrue(ProfileRules.verificationProblem("Younglings", 60, true, 1, List.of("Owner")).contains("another server"));
        assertTrue(ProfileRules.verificationProblem("Younglings", 60, false, 0, List.of()).contains("/rs"));
        assertTrue(ProfileRules.verificationProblem("Younglings", 60, false, 2, List.of()).contains("roster"));
        assertTrue(ProfileRules.verificationProblem("Younglings", 60, false, 1, List.of("General")).contains("Owner or Deputy Owner"));
        assertNull(ProfileRules.verificationProblem("Younglings", 60, false, 1, List.of("General", "Deputy Owner")));
    }

    @Test
    void verificationLastsOnlyWhileTheClanIsUnchanged() {
        var p = profile(Visibility.PUBLIC, "Younglings", null);

        assertTrue(ProfileRules.verified(p, "Younglings"));
        assertTrue(ProfileRules.verified(p, "  younglings "));
        assertFalse(ProfileRules.verified(p, "Other Clan"));
        assertFalse(ProfileRules.verified(p, null));
        assertFalse(ProfileRules.verified(profile(Visibility.PUBLIC, null, null), "Younglings"));
    }

    @Test
    void whereAProfileShowsUp() {
        assertEquals(Listing.HIDDEN, ProfileRules.listing(profile(Visibility.PRIVATE, "Younglings", null), "Younglings"));
        assertEquals(Listing.UNLISTED, ProfileRules.listing(profile(Visibility.UNLISTED, null, null), "Younglings"));
        assertEquals(Listing.LISTED, ProfileRules.listing(profile(Visibility.PUBLIC, "Younglings", null), "Younglings"));
        assertEquals(Listing.HIDDEN, ProfileRules.listing(profile(Visibility.PUBLIC, null, null), "Younglings"), "public without verification is not listed");
        assertEquals(Listing.HIDDEN, ProfileRules.listing(profile(Visibility.PUBLIC, "Younglings", null), "Renamed"), "a renamed clan drops out");
        assertEquals(Listing.HIDDEN, ProfileRules.listing(profile(Visibility.PUBLIC, "Younglings", Instant.EPOCH), "Younglings"), "suspended is hidden");
        assertEquals(Listing.HIDDEN, ProfileRules.listing(profile(Visibility.UNLISTED, null, Instant.EPOCH), "Younglings"));
    }

    @Test
    void goingPublicNeedsVerificationAndNoSuspension() {
        assertNull(ProfileRules.visibilityProblem(profile(Visibility.PRIVATE, null, null), "Younglings", Visibility.PRIVATE));
        assertNull(ProfileRules.visibilityProblem(profile(Visibility.PRIVATE, null, null), "Younglings", Visibility.UNLISTED));
        assertNotNull(ProfileRules.visibilityProblem(profile(Visibility.PRIVATE, null, null), "Younglings", Visibility.PUBLIC));
        assertNull(ProfileRules.visibilityProblem(profile(Visibility.PRIVATE, "Younglings", null), "Younglings", Visibility.PUBLIC));
        assertTrue(ProfileRules.visibilityProblem(profile(Visibility.PRIVATE, "Younglings", Instant.EPOCH), "Younglings", Visibility.PUBLIC).contains("suspended"));
        assertNull(ProfileRules.visibilityProblem(profile(Visibility.PUBLIC, "Younglings", Instant.EPOCH), "Younglings", Visibility.PRIVATE), "a suspended clan can always go private");
    }

    @Test
    void theAddressFilterReadsWholeWordsAndCatchesDisguises() {
        // innocent words that merely contain a blocked part
        for (String ok : new String[]{"white-canal", "whitecanal", "scunthorpe-clan", "mood-swing", "night-raiders", "essex-boys", "analyst-guild", "cockburn", "dickens"}) {
            assertNull(ProfileRules.slugProblem(ok), ok + " should be allowed");
        }
        // hyphenated, joined, digit-split, look-alike letters and stretched letters
        for (String bad : new String[]{"big-porn", "bigporn", "sex-clan", "sh1t-clan", "f-u-c-k", "fuuuck-clan", "nazi-club", "n1gger-clan", "seeex-clan", "clan-anal", "xxx-clan"}) {
            assertNotNull(ProfileRules.slugProblem(bad), bad + " should be refused");
        }
    }
}
