package com.runeclans.bot.ops;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.config.BotBrand;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.Mentions;
import com.runeclans.bot.util.BotScheduler;
import com.runeclans.bot.discord.Shards;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ops alerts and the weekly numbers post to the owner's ops channel (T-674, docs/SCALING.md section 7). Both off by default:
 * {@code OPS_ALERTS=on} runs {@link OpsAlertRules} every minute, {@code OPS_WEEKLY=on} posts {@link OpsReport} on Mondays at 09:00 UTC, and
 * both need {@code OPS_ALERT_CHANNEL_ID}. Posts go through {@link AutoPostRouter}, so a bot that isn't live (posting switched off) sends them
 * to its dev channel or only logs them, and they never ping anybody.
 */
@BService
public class OpsAlerts {
    private static final Logger log = LoggerFactory.getLogger(OpsAlerts.class);

    private final BotConfig config;
    private final OpsMetrics metrics;
    private final ScheduledExecutorService lane = BotScheduler.lane("ops-alerts");
    private final OpsAlertRules rules;
    private final OpsReport.Previous previous = new OpsReport.Previous();
    private HttpClient http;
    private volatile JDA jda;

    public OpsAlerts(BotConfig config, OpsMetrics metrics) {
        this.config = config;
        this.metrics = metrics;
        this.rules = new OpsAlertRules(config.getOps429Threshold());
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        boolean alerts = config.isOpsAlertsEnabled();
        boolean weekly = config.isOpsWeeklyEnabled();
        if (!alerts && !weekly) return;
        if (config.getOpsAlertChannelId() == null) {
            log.warn("OPS_ALERTS / OPS_WEEKLY is on but OPS_ALERT_CHANNEL_ID is not a channel id: nothing will be posted.");
            return;
        }
        this.jda = event.getJda();
        if (alerts) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
            lane.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.MINUTES);
            log.info("Ops alerts are on ({} health address(es), 429 threshold {}).", config.getOpsHealthUrls().size(), config.getOps429Threshold());
        }
        if (weekly) {
            long delay = Duration.between(Instant.now(), nextWeekly(Instant.now())).toMinutes();
            lane.scheduleAtFixedRate(this::weekly, Math.max(1, delay), TimeUnit.DAYS.toMinutes(7), TimeUnit.MINUTES);
            log.info("The weekly ops numbers post is on (next in {} minutes).", delay);
        }
    }

    /** The next Monday 09:00 UTC after {@code now}. */
    static Instant nextWeekly(Instant now) {
        ZonedDateTime t = now.atZone(ZoneOffset.UTC);
        ZonedDateTime candidate = t.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).withHour(9).withMinute(0).withSecond(0).withNano(0);
        if (!candidate.isAfter(t)) candidate = candidate.plusWeeks(1);
        return candidate.toInstant();
    }

    private void tick() {
        try {
            Map<String, Boolean> health = new LinkedHashMap<>();
            for (String url : config.getOpsHealthUrls()) health.put(url, healthy(url));
            OpsMetrics.Snapshot s = metrics.snapshot();
            for (OpsAlertRules.Alert a : rules.evaluate(new OpsAlertRules.Sample(Instant.now(), health, s.outboxOldestSeconds(), s.outboxDue(),
                    s.discord429Total()))) {
                log.warn("Ops alert {}: {}", a.rule(), a.text());
                post((a.recovered() ? "✅ " : "⚠️ ") + a.text());
            }
        } catch (RuntimeException e) {
            log.warn("The ops alert check failed: {}", e.getClass().getSimpleName());
        }
    }

    private boolean healthy(String url) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET()
                    .header("User-Agent", "JonnyBot ops-health").build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() >= 200 && r.statusCode() < 300;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void weekly() {
        try {
            JDA current = jda;
            String text = OpsReport.text(BotBrand.NAME, metrics.snapshot(), metrics.gauges(), OpsTelemetry.registry(), rules.firing(),
                    current == null ? -1 : Shards.guilds(current).size(), previous);
            post(text);
        } catch (RuntimeException e) {
            log.warn("The weekly ops numbers post failed: {}", e.getClass().getSimpleName());
        }
    }

    private void post(String text) {
        JDA current = jda;
        Long id = config.getOpsAlertChannelId();
        if (current == null || id == null) return;
        GuildMessageChannel channel = current.getChannelById(GuildMessageChannel.class, id);
        if (channel == null) {
            log.warn("OPS_ALERT_CHANNEL_ID names no channel the bot can see; the ops post was only logged.");
            return;
        }
        GuildMessageChannel target = AutoPostRouter.route(channel, "an ops post");
        if (target == null) return;
        String body = text.length() > 1900 ? text.substring(0, 1900) + "…" : text;
        target.sendMessage(AutoPostRouter.defang(body)).setAllowedMentions(Mentions.NONE)
                .queue(ok -> {}, err -> log.warn("Posting to the ops channel failed: {}", err.getClass().getSimpleName()));
    }

    /** Stops the timers (tests). */
    void stop() {
        lane.shutdownNow();
    }

    List<String> firing() {
        return rules.firing();
    }
}
