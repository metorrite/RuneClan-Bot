package com.runeclans.bot.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Refresh button on a player profile ({@link DirectoryPlayerRefreshRoutes}): the website's minutes kept in range (the hub rules are in DirectoryPlayerRefreshHubTest). */
class DirectoryPlayerRefreshRoutesTest {
    @Test
    void theWebsitesMinutesAreKeptInRange() {
        assertEquals(30, DirectoryPlayerRefreshRoutes.waitMinutes(null));
        assertEquals(30, DirectoryPlayerRefreshRoutes.waitMinutes("soon"));
        assertEquals(30, DirectoryPlayerRefreshRoutes.waitMinutes("-5"));
        assertEquals(90, DirectoryPlayerRefreshRoutes.waitMinutes(" 90 "));
        assertEquals(DirectoryPlayerRefreshRoutes.MIN_MINUTES, DirectoryPlayerRefreshRoutes.waitMinutes("0"));
        assertEquals(DirectoryPlayerRefreshRoutes.MAX_MINUTES, DirectoryPlayerRefreshRoutes.waitMinutes("99999"));
    }

    @Test
    void theWebsitesMinutesAtTheEdgesOfTheRange() {
        assertEquals(5, DirectoryPlayerRefreshRoutes.waitMinutes("4"));
        assertEquals(5, DirectoryPlayerRefreshRoutes.waitMinutes("5"));
        assertEquals(1440, DirectoryPlayerRefreshRoutes.waitMinutes("1440"));
        assertEquals(1440, DirectoryPlayerRefreshRoutes.waitMinutes("1441"));
        assertEquals(30, DirectoryPlayerRefreshRoutes.waitMinutes("1234567"), "more than six digits isn't a number we take");
        assertEquals(30, DirectoryPlayerRefreshRoutes.waitMinutes("1.5"));
    }
}
