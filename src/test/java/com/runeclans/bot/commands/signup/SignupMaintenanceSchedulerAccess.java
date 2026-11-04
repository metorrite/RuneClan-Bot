package com.runeclans.bot.commands.signup;

import net.dv8tion.jda.api.JDA;

/** Lets tests in other packages run one signup maintenance sweep. */
public final class SignupMaintenanceSchedulerAccess {
    private SignupMaintenanceSchedulerAccess() {}

    public static void sweep(SignupService signups, JDA jda) {
        new SignupMaintenanceScheduler(signups).runSweep(jda);
    }
}
