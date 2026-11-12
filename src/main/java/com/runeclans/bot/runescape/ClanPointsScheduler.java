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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Once a day, at 01:30 UTC, sends the Clan Report ({@link ClanPointsService#runDailyPointsAndPromotionCheck}) for every guild with a
 * clan configured. The points and promotion flags are the RuneClans hub's (T-978): it awards them after its own daily roster fetch of
 * each clan, which lands somewhere in 00:00-00:59 UTC (a failed fetch is tried again 15 minutes later), so the report waits until
 * 01:30 to read today's flags rather than yesterday's.
 * <p>
 * A server the hub didn't answer for (down, slow or busy: "unknown", not "nobody flagged") is tried again {@link #RETRY_AFTER} later, up to
 * {@link #TRIES} times in all (B-10), so one hub blip doesn't silently cost those clans the day's report.
 * <p>
 * Same JDA-ready + {@code scheduleAtFixedRate} pattern as {@link ClanSyncScheduler}/{@code WeeklyDigestScheduler},
 * and same {@link BotConfig#getRunescapeAutoPollEnabled()} gate as every other scheduler here.
 */
@BService
public class ClanPointsScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClanPointsScheduler.class);

    private static final LocalTime RUN_TIME_UTC = LocalTime.of(1, 30);
    private static final Duration INTERVAL = Duration.ofDays(1);
    /** How long after the hub didn't answer a server is tried again, and how many tries a server gets in one day. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(15);
    static final int TRIES = 4;

    private final ClanPointsService clanPointsService;
    private final ClanSyncService clanSyncService;
    private final BotConfig botConfig;
    /** Replaceable in tests, to see the retries scheduled. */
    ScheduledExecutorService executor = BotScheduler.lane("clan-points-scheduler");

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
        List<Guild> configured = new ArrayList<>();
        for (Guild guild : Shards.guilds(jda)) {
            if (clanSyncService.getClanName(guild.getIdLong()) != null) configured.add(guild); // else nothing configured to check
        }
        runAndRetry(jda, configured, 1);
    }

    /** Try {@code tryNumber} for {@code guilds}; the ones the hub didn't answer for are tried again later, while tries are left. */
    void runAndRetry(JDA jda, List<Guild> guilds, int tryNumber) {
        List<Long> again = run(guilds);
        if (again.isEmpty()) return;
        if (tryNumber >= TRIES) {
            log.warn("Clan Report: RuneClans didn't answer for {} server(s) after {} tries today; no report for them today.", again.size(), TRIES);
            return;
        }
        log.warn("Clan Report: RuneClans didn't answer for {} server(s); trying them again in {}.", again.size(), RETRY_AFTER);
        executor.schedule(() -> {
            List<Guild> still = new ArrayList<>();
            for (long id : again) {
                Guild g = Shards.guild(jda, id);
                if (g != null) still.add(g);
            }
            runAndRetry(jda, still, tryNumber + 1);
        }, RETRY_AFTER.toSeconds(), TimeUnit.SECONDS);
    }

    /** One pass over {@code guilds}: the ids of the servers the hub didn't answer for. Package-private for the tests. */
    List<Long> run(List<Guild> guilds) {
        List<Long> again = new ArrayList<>();
        for (Guild guild : guilds) {
            try {
                int listed = clanPointsService.runDailyPointsAndPromotionCheck(guild);
                if (listed == ClanPointsService.HUB_DIDNT_ANSWER) again.add(guild.getIdLong());
                else log.info("Clan points/promotion check finished for guild {}.", guild.getIdLong());
            } catch (Exception e) {
                log.error("Clan points/promotion check failed for guild {}", guild.getIdLong(), e);
            }
        }
        return again;
    }
}
