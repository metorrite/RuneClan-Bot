package com.runeclans.bot.runescape;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import com.runeclans.bot.runescape.stats.StatsBackend;
import com.runeclans.bot.runescape.stats.StatsBackends;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Backs the weekly clan digest (joins/leaves, Citadel visits/caps) — see {@code WeeklyDigestService}. */
@BConfiguration
public class WeeklyDigestRepository {
    private static final Logger log = LoggerFactory.getLogger(WeeklyDigestRepository.class);

    private final ConnectionSupplier connectionSupplier;


    /** Stats reads answer from the hub while it polls, else from this class's own SQL (T-851, {@link StatsBackends}). */
    private volatile StatsBackends stats = StatsBackends.none();

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it.  */
    @BService
    public static WeeklyDigestRepository weeklyDigestRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer, StatsBackends backends) {
        WeeklyDigestRepository repository = new WeeklyDigestRepository(connectionSupplier, initializer);
        repository.stats = backends;
        return repository;
    }

    /** Reads from {@code backends} while the hub polls (tests). */
    public WeeklyDigestRepository withStats(StatsBackends backends) {
        this.stats = backends;
        return this;
    }
    public WeeklyDigestRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** {@code eventType} is {@code "JOIN"} or {@code "LEAVE"}. */
    public void recordRosterEvent(long guildId, String rsn, String eventType) {
        String sql = "INSERT INTO younglings.clan_roster_event (guild_id, rsn, event_type) VALUES (?, ?, ?)";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.setString(3, eventType);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to record clan roster event for guild {} rsn {}", guildId, rsn, e);
            throw new RuntimeException("Failed to record clan roster event", e);
        }
    }

    public record RosterEvent(String rsn, String eventType, OffsetDateTime eventAt) {}

    public List<RosterEvent> getRosterEventsInWindow(long guildId, OffsetDateTime from, OffsetDateTime to) {
        String sql = """
                SELECT rsn, event_type, event_at FROM younglings.clan_roster_event
                WHERE guild_id = ? AND event_at >= ? AND event_at < ?
                ORDER BY event_at
                """;

        List<RosterEvent> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setTimestamp(2, Timestamp.from(from.toInstant()));
            statement.setTimestamp(3, Timestamp.from(to.toInstant()));

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new RosterEvent(rs.getString("rsn"), rs.getString("event_type"),
                            rs.getObject("event_at", OffsetDateTime.class)));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get clan roster events for guild {}", guildId, e);
            throw new RuntimeException("Failed to get clan roster events", e);
        }
    }

    /** {@code occurredAt} is when it really happened (the activity's own time), or null if that couldn't be read. */
    public record CitadelActivityRow(String rsn, String activityText, String activityDate, java.time.Instant occurredAt) {
        public CitadelActivityRow(String rsn, String activityText, String activityDate) {
            this(rsn, activityText, activityDate, RuneMetricsDates.toInstant(activityDate));
        }
    }

    /**
     * Every "Visited"/"Capped at my Clan Citadel" activity that <i>happened</i> in the window (by {@code occurred_at}, the activity's own
     * time, so it doesn't matter when we polled it: a cap at 23:55 that we only saw at 03:00 still counts for the week it was made in, and one
     * made just after the reset doesn't leak back), regardless of who's still a current clan member; the caller cross-references against the
     * live roster. An activity whose time couldn't be read falls back to when we first saw it.
     */
    public List<CitadelActivityRow> getCitadelActivityInWindow(long guildId, OffsetDateTime from, OffsetDateTime to) {
        StatsBackend hub = stats.hub();
        if (hub != null) return hub.citadelActivityInWindow(guildId, from, to);
        String sql = """
                SELECT a.rsn, a.activity_text, a.activity_date, a.occurred_at FROM younglings.player_activity a
                WHERE EXISTS (SELECT 1 FROM younglings.clan_member m WHERE m.guild_id = ? AND LOWER(m.rsn) = LOWER(a.rsn))
                  AND COALESCE(a.occurred_at, a.recorded_at) >= ? AND COALESCE(a.occurred_at, a.recorded_at) < ?
                  AND (a.activity_text LIKE 'Visited my Clan Citadel%' OR a.activity_text LIKE 'Capped at my Clan Citadel%')
                """;

        List<CitadelActivityRow> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setTimestamp(2, Timestamp.from(from.toInstant()));
            statement.setTimestamp(3, Timestamp.from(to.toInstant()));

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    java.sql.Timestamp occurred = rs.getTimestamp("occurred_at");
                    results.add(new CitadelActivityRow(rs.getString("rsn"), rs.getString("activity_text"), rs.getString("activity_date"), occurred == null ? null : occurred.toInstant()));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get Citadel activity for guild {}", guildId, e);
            throw new RuntimeException("Failed to get Citadel activity", e);
        }
    }
}
