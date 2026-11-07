package com.runeclans.bot.ops;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.jobs.DiscordJobRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The bot's ops numbers against a real database (T-674): the outbox's due jobs and oldest age and the weekly text. */
class OpsMetricsDatabaseTest {
    private static final String KIND = "test.ops-metrics";

    @Test
    void outboxAndTheWeeklyPost() throws Exception {
        ConnectionSupplier connections = LocalDatabase.supplier();
        new DiscordJobRepository(connections); // makes the table
        BotConfig config = mock(BotConfig.class);
        OpsMetrics metrics = new OpsMetrics(connections, config);
        try {
            clean(connections);
            try (Connection c = connections.getConnection(); PreparedStatement s = c.prepareStatement(
                    "INSERT INTO younglings.discord_job (kind, guild_id, actor_id, payload, state, next_attempt_at) VALUES (?, 1, 1, '{}', 'queued', now() - interval '10 minutes'), "
                            + "(?, 1, 1, '{}', 'retrying', now() + interval '1 hour')")) {
                s.setString(1, KIND);
                s.setString(2, KIND);
                s.executeUpdate();
            }
            double[] outbox = metrics.outbox();
            assertTrue(outbox[0] >= 1, "the due job counts, the one waiting for later doesn't make it older");
            assertTrue(outbox[1] >= 590, "about ten minutes: " + outbox[1]);

            OpsMetrics.Snapshot snap = metrics.snapshot();
            assertTrue(snap.outboxOldestSeconds() >= 590);

            String text = metrics.render();
            assertTrue(text.contains("rc_queue_oldest_age_seconds{queue=\"discord_job\"} "), text);
            assertTrue(text.contains("rc_jvm_heap_used_bytes "), text);

            OpsReport.Previous previous = new OpsReport.Previous();
            String weekly = OpsReport.text("RuneClan Bot", snap, metrics.gauges(), new MetricRegistry(), List.of("queue"), 3, previous);
            assertTrue(weekly.startsWith("**RuneClan Bot weekly numbers**"), weekly);
            assertTrue(weekly.contains("- Servers: 3"), weekly);
            assertTrue(weekly.contains("since the bot started"), weekly);
            assertTrue(weekly.contains("Firing now: queue."), weekly);
            assertFalse(OpsReport.text("RuneClan Bot", snap, metrics.gauges(), new MetricRegistry(), List.of(), 3, previous).contains("since the bot started"),
                    "the second post is week on week");
        } finally {
            clean(connections);
        }
    }

    private static void clean(ConnectionSupplier connections) throws Exception {
        try (Connection c = connections.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.discord_job WHERE kind = ?")) {
            s.setString(1, KIND);
            s.executeUpdate();
        }
    }
}
