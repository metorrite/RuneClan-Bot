package com.runeclans.bot.access;

import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.runeclans.HubFeedListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The hub's {@code admins.changed} feed entry (B-32): a RuneClan Admin was added, had their note changed or was removed on the website. The
 * bot reads the list again ({@link OverallAdmins#changed}) and then drops every cached clan-site viewer answer ({@link SiteRoleGuard#forgetAll}),
 * so a removed admin loses the admin panel and every clan site's admin on their next request instead of up to about 90 seconds later.
 */
@BService
public class AdminListFeed implements HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(AdminListFeed.class);
    static final String KIND = "admins.changed";

    private final OverallAdmins admins;
    private final SiteRoleGuard siteRoles;

    public AdminListFeed(OverallAdmins admins, SiteRoleGuard siteRoles) {
        this.admins = admins;
        this.siteRoles = siteRoles;
    }

    @Override
    public void onHubFeed(String kind, DataObject data) {
        if (!KIND.equals(kind)) return;
        try {
            boolean removed = data != null && data.optObject("data").map(d -> d.getBoolean("removed", true)).orElse(true);
            admins.changed(removed);
            siteRoles.forgetAll();
        } catch (RuntimeException e) {
            log.warn("The RuneClan Admin list change couldn't be applied: {}", e.getClass().getSimpleName());
        }
    }
}
