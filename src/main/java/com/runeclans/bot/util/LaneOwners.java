package com.runeclans.bot.util;

import com.runeclans.bot.config.BotRole;

import java.util.Map;

/**
 * Which {@code BOT_ROLE} runs each scheduler lane's repeating work (T-702, docs/SCALING-STATE.md section 5). {@link BotScheduler} asks
 * {@link #runsHere} on every turn of a repeating task.
 * <ul>
 *   <li>{@link Owner#GATEWAY}: work that needs Discord's gateway cache (servers, channels, members) or reacts to events. Runs in
 *   {@code all} and {@code gateway}.</li>
 *   <li>{@link Owner#API}: work on database rows, the hub or outside APIs that needs no server list (posts go through the Discord job
 *   outbox). Runs in {@code all}, and in {@code api} only on the one replica that holds the api leader lock ({@link #leading}), so two api
 *   replicas never both run it.</li>
 *   <li>{@link Owner#EVERY}: local work each process needs for itself (batching, its own caches, the leader election). Runs everywhere.</li>
 * </ul>
 * A lane missing from the table counts as {@link Owner#GATEWAY} (the classic bot); {@code LaneOwnersTest} makes sure none is missing.
 */
public final class LaneOwners {
    public enum Owner { GATEWAY, API, EVERY }

    private static final Map<String, Owner> OWNERS = Map.ofEntries(
            // database, hub and outside-API work that reads no server list (posts, if any, go through the Discord job outbox)
            Map.entry("runemetrics-clock-check", Owner.API),
            Map.entry("rsnlink-verify", Owner.API), // Hiscores read budget (M8)
            Map.entry("rsnlink-retention", Owner.API),
            Map.entry("linked-roles", Owner.API),
            Map.entry("premium-expiry", Owner.API),
            Map.entry("billing-reconcile", Owner.API),
            Map.entry("plugin-retention", Owner.API),
            Map.entry("message-log-purge", Owner.API),
            Map.entry("clan-site-cleanup", Owner.API),
            Map.entry("clan-site-race", Owner.API), // the leaderboard race's hub reads (T-710), for the website's overview
            Map.entry("address-change-scheduler", Owner.API),
            Map.entry("quick-groups", Owner.API),
            Map.entry("event-export-expiry", Owner.API), // deletes expired wrap-up exports (T-698)
            // RuneMetrics polling, its budget and the event trackers that feed it: one budget, kept in the one gateway process (M1, M2).
            // Gateway, not api, because the poll targets are the servers the bot is in (RosterPollScheduler reads the server list).
            Map.entry("poll-jobs", Owner.GATEWAY),
            Map.entry("runemetrics-budget", Owner.GATEWAY),
            Map.entry("event-tracker", Owner.GATEWAY),
            Map.entry("plugin-xp-tracker", Owner.GATEWAY),
            // work that reads the gateway's servers, channels or members (M9, M11)
            Map.entry("clan-sync-scheduler", Owner.GATEWAY),
            Map.entry("clan-points-scheduler", Owner.GATEWAY),
            Map.entry("runeclans-feed", Owner.GATEWAY),
            Map.entry("skill-of-the-week", Owner.GATEWAY),
            Map.entry("plugin-xp-points", Owner.GATEWAY),
            Map.entry("clan-seasons", Owner.GATEWAY),
            // the rank-up advisor's weekly update reads Discord names and channels from the cache
            Map.entry("rank-weekly", Owner.GATEWAY),
            Map.entry("event-draws", Owner.GATEWAY),
            Map.entry("event-board-updates", Owner.GATEWAY),
            Map.entry("guild-retention", Owner.GATEWAY),
            Map.entry("discord-jobs", Owner.GATEWAY),
            Map.entry("discord-job-claims", Owner.GATEWAY),
            Map.entry("weekly-digest-scheduler", Owner.GATEWAY),
            Map.entry("rsnlink-hub-dm", Owner.GATEWAY),
            Map.entry("community-command", Owner.GATEWAY),
            Map.entry("hub-sites", Owner.GATEWAY),
            Map.entry("request-cards", Owner.GATEWAY),
            Map.entry("ephemeral-lifecycle", Owner.GATEWAY),
            Map.entry("event-board-panels", Owner.GATEWAY),
            Map.entry("cohost-panels", Owner.GATEWAY),
            Map.entry("event-signups", Owner.GATEWAY),
            Map.entry("event-announcer-sweep", Owner.GATEWAY),
            Map.entry("reminders", Owner.GATEWAY),
            Map.entry("server-reminders", Owner.GATEWAY),
            Map.entry("scheduled-posts", Owner.GATEWAY),
            Map.entry("poll-closer", Owner.GATEWAY),
            Map.entry("giveaways", Owner.GATEWAY),
            Map.entry("ticket-scheduler", Owner.GATEWAY),
            Map.entry("signup-maintenance", Owner.GATEWAY),
            Map.entry("lookup-reminders", Owner.GATEWAY),
            Map.entry("member-notifier", Owner.GATEWAY),
            Map.entry("autorole", Owner.GATEWAY),
            Map.entry("role-keep", Owner.GATEWAY),
            Map.entry("temp-voice", Owner.GATEWAY),
            Map.entry("autopurge", Owner.GATEWAY),
            Map.entry("clan-anniversaries", Owner.GATEWAY),
            Map.entry("staff-digest", Owner.GATEWAY),
            Map.entry("presence-members", Owner.GATEWAY),
            Map.entry("twitch", Owner.GATEWAY),
            Map.entry("kick", Owner.GATEWAY),
            Map.entry("feeds", Owner.GATEWAY),
            Map.entry("ops-alerts", Owner.GATEWAY),
            // every process for itself
            Map.entry("command-usage", Owner.EVERY),
            Map.entry("phishing-list", Owner.EVERY),
            Map.entry("debouncer", Owner.EVERY),
            Map.entry("site-invalidator", Owner.EVERY),
            Map.entry("hub-member-privacy", Owner.EVERY), // the member privacy changes this process saved, on to the hub (T-750)
            Map.entry("panel-switches", Owner.EVERY), // this process's read of the website's Switches panel from the hub (T-817)
            // the league reporter sends a finished clan-vs-clan event's result to the hub from the process that finished it (T-759)
            Map.entry("leagues", Owner.EVERY),
            Map.entry("cache-bus-send", Owner.EVERY),
            Map.entry("cache-bus-listen", Owner.EVERY),
            Map.entry("api-leader", Owner.EVERY),
            Map.entry("job-heartbeat", Owner.EVERY)); // each process keeps its own running dashboard jobs alive (T-698)

    /** Whether this api replica holds the api leader lock (set by {@code ApiLeaderElection}; false until it has it). */
    private static volatile boolean leading;

    private LaneOwners() {}

    /** The lane's owner ({@link Owner#GATEWAY} for a lane not in the table). */
    public static Owner owner(String lane) {
        return OWNERS.getOrDefault(lane, Owner.GATEWAY);
    }

    /** The table, for the tests. */
    static Map<String, Owner> all() {
        return OWNERS;
    }

    /** Whether this process does the lane's repeating work now. */
    public static boolean runsHere(String lane) {
        return runs(owner(lane), BotRole.current(), leading);
    }

    static boolean runs(Owner owner, BotRole role, boolean leader) {
        return switch (owner) {
            case EVERY -> true;
            case GATEWAY -> role.gateway();
            case API -> role == BotRole.ALL || (role == BotRole.API && leader);
        };
    }

    /** Records whether this api replica holds the leader lock. */
    public static void leading(boolean holds) {
        leading = holds;
    }

    public static boolean leading() {
        return leading;
    }
}
