package com.runeclans.bot.runescape;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.tracking.CitadelWeek;
import com.runeclans.bot.tracking.CitadelWheelPoster;
import com.runeclans.bot.tracking.WeeklyDigestService;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Every Wednesday at 12:00 UTC sends the weekly joins/leaves and Citadel visits/caps digests (see {@link WeeklyDigestService}) for every guild with a
 * clan configured, covering the Citadel week that just finished: from the previous Wednesday 00:10 UTC to this Wednesday's 00:10 UTC reset (see
 * {@link CitadelWeek}).
 * <p>
 * Twelve hours after the reset is deliberate. The regular polls (every few hours) have by then seen everybody's adventure log after the week turned
 * over, so a cap made in the last minutes of the week is in the data, and the report groups caps by when they happened rather than when they were
 * noticed. Just before sending, anyone whose latest profile is older than the reset is polled again so nothing rests on luck.
 * <p>
 * Same JDA-ready + {@code scheduleAtFixedRate} pattern as {@link ClanSyncScheduler}, and same {@link BotConfig#getRunescapeAutoPollEnabled()} gate as
 * every other scheduler here.
 */
@BService
public class WeeklyDigestScheduler {
    private static final Logger log = LoggerFactory.getLogger(WeeklyDigestScheduler.class);

    private static final LocalTime RUN_TIME_UTC = LocalTime.of(12, 0);
    private static final Duration INTERVAL = Duration.ofDays(7);

    private final WeeklyDigestService weeklyDigestService;
    private final ClanSyncService clanSyncService;
    private final CitadelWheelPoster wheelPoster;
    private final BotConfig botConfig;
    private final ScheduledExecutorService executor = BotScheduler.lane("weekly-digest-scheduler");

    public WeeklyDigestScheduler(WeeklyDigestService weeklyDigestService, ClanSyncService clanSyncService,
                                 CitadelWheelPoster wheelPoster, BotConfig botConfig) {
        this.weeklyDigestService = weeklyDigestService;
        this.clanSyncService = clanSyncService;
        this.wheelPoster = wheelPoster;
        this.botConfig = botConfig;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED=false) — the weekly digest will not run automatically.");
            return;
        }

        JDA jda = event.getJda();
        Duration initialDelay = durationUntilNextWednesdayNoonUtc(OffsetDateTime.now(ZoneOffset.UTC));
        log.info("Weekly digest scheduler starting: first run in {}, then every {}.", initialDelay, INTERVAL);
        executor.scheduleAtFixedRate(() -> runForAllGuilds(jda), initialDelay.toSeconds(), INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /** How long from {@code now} until the next Wednesday 12:00 UTC. */
    static Duration durationUntilNextWednesdayNoonUtc(OffsetDateTime now) {
        OffsetDateTime candidate = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY)).with(RUN_TIME_UTC);
        if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1);
        return Duration.between(now, candidate);
    }

    private void runForAllGuilds(JDA jda) {
        // The week that most recently finished, worked out from "now" rather than hardcoded, so a run that fires a little late still reports the right week.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime[] window = CitadelWeek.lastCompleted(now);
        OffsetDateTime windowStart = window[0];
        OffsetDateTime windowEnd = window[1];

        for (Guild guild : Shards.guilds(jda)) {
            if (clanSyncService.getClanName(guild.getIdLong()) == null) continue; // nothing configured to report on

            try {
                // the hub keeps every member's profile current, so there is nothing to refresh first
                weeklyDigestService.sendWeeklyDigests(guild, windowStart, windowEnd);
                wheelPoster.autoPost(guild, window); // if the server turned it on, the wheel's winner is announced with the list
                log.info("Weekly digest sent for guild {} covering {} to {}.", guild.getIdLong(), windowStart, windowEnd);
            } catch (Exception e) {
                log.error("Weekly digest failed for guild {}", guild.getIdLong(), e);
            }
        }
    }
}
