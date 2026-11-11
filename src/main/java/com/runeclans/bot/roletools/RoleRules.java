package com.runeclans.bot.roletools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The decisions of the role toolkit that need neither Discord nor a database, so they can be tested on their own. */
public final class RoleRules {
    /** A bulk job never plans more members than this: a bigger change is a sign something is wrong with the choice. */
    public static final int MAX_BULK_MEMBERS = 5_000;
    /** Names shown in a dry run. */
    public static final int PREVIEW_NAMES = 25;
    /** Pause between two role changes of a bulk job, kept well inside Discord's rate limits. */
    public static final long BULK_PAUSE_MILLIS = 1_200;

    private RoleRules() {}

    public enum Mode {
        /** Give the target role to everyone who holds the source role and lacks the target. */
        ADD,
        /** Take the target role from everyone who holds the source role and has the target. */
        REMOVE;

        public static java.util.Optional<Mode> ofKey(String key) {
            for (Mode mode : values()) if (mode.name().equalsIgnoreCase(key)) return java.util.Optional.of(mode);
            return java.util.Optional.empty();
        }
    }

    public enum Status { PLANNED, RUNNING, PAUSED, DONE, CANCELLED }

    /** Who holds what, for one member, as the dry run saw it. */
    public record Holder(long userId, boolean holdsSource, boolean holdsTarget) {}

    /** The members a job would change. */
    public static List<Long> affected(Mode mode, List<Holder> holders) {
        List<Long> ids = new ArrayList<>();
        for (Holder holder : holders) {
            if (!holder.holdsSource()) continue;
            if (mode == Mode.ADD ? !holder.holdsTarget() : holder.holdsTarget()) ids.add(holder.userId());
        }
        return ids;
    }

    /**
     * Why a role cannot be handed out or taken by the toolkit, or null if it can. A role the clan role sync owns is refused outright (the sync would
     * undo the change, and two systems fighting over a role is how members lose access); so is a role the bot may not hand out at all.
     */
    public static String roleRefusal(boolean exists, boolean everyone, boolean syncOwned, boolean safeToAssign, String roleName) {
        if (!exists) return "That role does not exist in this server.";
        if (everyone) return "Nobody needs to be given @everyone.";
        if (syncOwned) return "@" + roleName + " is kept by the clan role sync (Member / Guest / Unverified), so it cannot be changed here.";
        if (!safeToAssign) return "The bot will not hand out @" + roleName + ": it is above the bot's own role, managed by an integration, carries powerful permissions, or would make its holder bot staff.";
        return null;
    }

    /** Whether a caller may give or take a role with /role: Admins any safe role, anyone else only roles on the list of a group they are in. */
    public static boolean mayUse(boolean admin, Set<Long> allowedForCaller, long roleId) {
        return admin || allowedForCaller.contains(roleId);
    }

    /** Whether a bulk job's two roles make sense together. */
    public static String bulkRefusal(long sourceRoleId, long targetRoleId) {
        if (sourceRoleId == targetRoleId) return "Choose two different roles.";
        return null;
    }

    /** Whether a job may go from {@code from} to {@code to}. Only a person asks for these changes; nothing here moves a job by itself. */
    public static boolean canMove(Status from, Status to) {
        return switch (to) {
            case RUNNING -> from == Status.PLANNED || from == Status.PAUSED;
            case PAUSED -> from == Status.RUNNING;
            case CANCELLED -> from == Status.PLANNED || from == Status.RUNNING || from == Status.PAUSED;
            case DONE -> from == Status.RUNNING;
            case PLANNED -> false;
        };
    }
}
