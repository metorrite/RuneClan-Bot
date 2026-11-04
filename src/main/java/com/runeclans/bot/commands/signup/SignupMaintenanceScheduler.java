package com.runeclans.bot.commands.signup;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically sweeps signups for two kinds of cleanup Discord gives no event for:
 * <ul>
 *     <li>Hard-deleting signups that were soft-deleted (via {@code /signup delete} or the dev
 *     bulk-close tool) more than {@link #PURGE_AFTER} ago — they're kept around briefly for the
 *     admin archive, not forever.</li>
 *     <li>Auto-closing signups nobody has touched (no new entries) in {@link #AUTO_CLOSE_AFTER} —
 *     abandoned signups that were never explicitly closed.</li>
 * </ul>
 * Runs in-process on a daemon thread rather than as a database-level scheduled job (e.g. {@code
 * pg_cron}), since that needs a Postgres extension enabled at the server level that may not be
 * available on every host — this works identically regardless of where the database lives.
 */
@BService
public class SignupMaintenanceScheduler {
    private static final Logger log = LoggerFactory.getLogger(SignupMaintenanceScheduler.class);

    private static final Duration CHECK_INTERVAL = Duration.ofHours(6);
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(1);
    private static final Duration PURGE_AFTER = Duration.ofDays(3);
    private static final Duration AUTO_CLOSE_AFTER = Duration.ofDays(45);

    private final SignupService signupService;
    private final ScheduledExecutorService executor = BotScheduler.lane("signup-maintenance");

    public SignupMaintenanceScheduler(SignupService signupService) {
        this.signupService = signupService;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> runSweep(jda),
                INITIAL_DELAY.toSeconds(), CHECK_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private void runSweep(JDA jda) {
        if (AutoPostRouter.diverting()) return;
        if (signupService.onHub()) return; // hub-community: the hub keeps the sheets and closes the untouched ones itself
        try {
            int purged = signupService.purgeOldDeletedSignups(Instant.now().minus(PURGE_AFTER));
            if (purged > 0) log.info("Signup maintenance: purged {} old soft-deleted signup(s).", purged);
        } catch (Exception e) {
            log.error("Signup maintenance: purge sweep failed", e);
        }

        try {
            int closed = signupService.autoCloseInactiveSignups(jda, Instant.now().minus(AUTO_CLOSE_AFTER));
            if (closed > 0) log.info("Signup maintenance: auto-closed {} inactive signup(s).", closed);
        } catch (Exception e) {
            log.error("Signup maintenance: auto-close sweep failed", e);
        }
    }
}
