package com.runeclans.bot.events;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The board update posts an event has had (T-650): each daily recap and each milestone, by a key ({@code recap:2026-10-26}, {@code line},
 * {@code blackout}, {@code tile:<key>}) so each is posted once even across restarts, with its text for the player's event page. A post is noted here
 * before it is handed to the Discord outbox, so a post that fails half way is never sent twice. The event repository is a dependency only so its table
 * exists first.
 */
@BService
public class EventUpdateRepository {
    private static final Logger log = LoggerFactory.getLogger(EventUpdateRepository.class);

    public record Post(String key, String kind, String text, Instant at) {}

    private final ConnectionSupplier connectionSupplier;

    public EventUpdateRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "event update posts", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_update_post (
                    event_id BIGINT NOT NULL,
                    post_key TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    text TEXT NOT NULL DEFAULT '',
                    posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_id, post_key)
                );
                """,
                // T-934: the rows name hub events by their local id, which the bot's old event table never held
                "ALTER TABLE younglings.event_update_post DROP CONSTRAINT IF EXISTS event_update_post_event_id_fkey;"));
    }

    /** Notes a post as made. True when this call was the first (go ahead and post it); false when it was already made. */
    public boolean claim(long eventId, String key, String kind, String text) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.event_update_post (event_id, post_key, kind, text) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, eventId);
            s.setString(2, key);
            s.setString(3, kind);
            s.setString(4, text == null ? "" : text);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to note an event update post", e);
        }
    }

    /** The newest posts first, at most {@code limit}. */
    public List<Post> recent(long eventId, int limit) {
        List<Post> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT post_key, kind, text, posted_at FROM younglings.event_update_post WHERE event_id = ? ORDER BY posted_at DESC, post_key LIMIT ?")) {
            s.setLong(1, eventId);
            s.setInt(2, Math.max(1, limit));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Post(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event's update posts", e);
        }
    }
}
