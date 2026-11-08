package com.runeclans.bot.runescape;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.tracking.ClanPointsService;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Once a day, at 01:30 UTC, sends the Clan Report ({@link ClanPointsService#runDailyPointsAndPromotionCheck}) for every guild with a
 * clan configured. The points and promotion flags are the RuneClans hub's (T-978): it awards them after its own daily roster fetch of
 * each clan, which lands somewhere in 00:00-00:59 UTC (a failed fetch is tried again 15 minutes later), so the report waits until
 * 01:30 to read today's flags rather than yesterday's.
 * <p>
 * Same JDA-ready + {@code scheduleAtFixedRate} pattern as {@link ClanSyncScheduler}/{@code WeeklyDigestScheduler},
 * and same {@link BotConfig#getRunescapeAutoPollEnabled()} gate as every other scheduler here.
 */
@BService
public class ClanPointsScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClanPointsScheduler.class);

    private static final LocalTime RUN_TIME_UTC = LocalTime.of(1, 30);
    private static final Duration INTERVAL = Duration.ofDays(1);

    private final ClanPointsService clanPointsService;
    private final ClanSyncService clanSyncService;
    private final BotConfig botConfig;
    private final ScheduledExecutorService executor = BotScheduler.lane("clan-points-scheduler");

    public ClanPointsScheduler(ClanPointsService clanPointsService, ClanSyncService clanSyncService, BotConfig botConfig) {
        this.clanPointsService = clanPointsService;
        this.clanSyncService = clanSyncService;
        this.botConfig = botConfig;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED not set) — the clan points/promotion check will not run automatically.");
            return;
        }

        JDA jda = event.getJda();
        Duration initialDelay = durationUntilNextRun();
        log.info("Clan points scheduler starting: first run in {}, then every {}.", initialDelay, INTERVAL);
        executor.scheduleAtFixedRate(() -> runForAllGuilds(jda), initialDelay.toSeconds(), INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private static Duration durationUntilNextRun() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime candidate = now.toLocalDate().atTime(RUN_TIME_UTC).atOffset(ZoneOffset.UTC);
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return Duration.between(now, candidate);
    }

    private void runForAllGuilds(JDA jda) {
        for (Guild guild : Shards.guilds(jda)) {
            if (clanSyncService.getClanName(guild.getIdLong()) == null) continue; // nothing configured to check

            try {
                clanPointsService.runDailyPointsAndPromotionCheck(guild);
                log.info("Clan points/promotion check finished for guild {}.", guild.getIdLong());
            } catch (Exception e) {
                log.error("Clan points/promotion check failed for guild {}", guild.getIdLong(), e);
            }
        }
    }
}
