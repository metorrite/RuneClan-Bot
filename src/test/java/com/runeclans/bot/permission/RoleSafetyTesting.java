package com.runeclans.bot.permission;

import java.util.List;

/** Lets tests in other packages give {@link RoleSafety} a fixed set of groups, and take it away again. */
public final class RoleSafetyTesting {
    private RoleSafetyTesting() {}

    public static void install(List<PermissionGroup> groups) {
        RoleSafety.install(guildId -> groups.stream().filter(g -> g.guildId() == guildId).toList());
    }

    public static void uninstall() {
        RoleSafety.uninstall();
    }
}
