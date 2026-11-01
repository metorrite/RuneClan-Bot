package com.runeclans.bot.database;

import com.runeclans.bot.config.BotConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.jspecify.annotations.NonNull;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

@BService
public class DatabaseSource implements ConnectionSupplier {

    /**
     * Connections to the bot database by default. Five ran out during a single website page load once the bot also served hub work (T-836:
     * the live log showed all five busy with threads waiting); ten leaves room while staying far below Postgres' default limit of 100.
     */
    static final int POOL_SIZE = 10;
    /** The most {@code BOT_DB_POOL_SIZE} may ask for, so a typo can't use up the database's connection limit. */
    static final int POOL_SIZE_MAX = 50;

    private final int poolSize;

    private final HikariDataSource dataSource;
    private final java.util.concurrent.atomic.LongAdder waitNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder borrows = new java.util.concurrent.atomic.LongAdder();

    public DatabaseSource(BotConfig botConfig) {
        HikariConfig hikariConfig = createHikariConfig(botConfig.getDatabaseUrl());
        this.poolSize = poolSize(System.getenv("BOT_DB_POOL_SIZE"));
        hikariConfig.setMaximumPoolSize(poolSize);

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    @Override
    public @NonNull Duration getMaxTransactionDuration() {
        return ConnectionSupplier.super.getMaxTransactionDuration();
    }

    @Override
    public int getMaxConnections() {
        return poolSize;
    }

    /** {@code BOT_DB_POOL_SIZE} as a pool size: a whole number from 1 to {@link #POOL_SIZE_MAX}, else {@link #POOL_SIZE}. */
    static int poolSize(String setting) {
        if (setting == null || setting.isBlank()) return POOL_SIZE;
        try {
            int size = Integer.parseInt(setting.trim());
            return size >= 1 && size <= POOL_SIZE_MAX ? size : POOL_SIZE;
        } catch (NumberFormatException e) {
            return POOL_SIZE;
        }
    }

    @Override
    public @NonNull Connection getConnection() throws SQLException {
        long started = System.nanoTime();
        try {
            return dataSource.getConnection();
        } finally {
            waitNanos.add(System.nanoTime() - started);
            borrows.increment();
        }
    }

    /** The pool right now and since start (T-674 metrics): connections in use, idle, threads waiting for one, and the time spent waiting. */
    public record PoolStats(int active, int idle, int waiting, int max, long borrows, double waitSeconds) {}

    public PoolStats poolStats() {
        var pool = dataSource.getHikariPoolMXBean();
        return new PoolStats(pool == null ? 0 : pool.getActiveConnections(), pool == null ? 0 : pool.getIdleConnections(),
                pool == null ? 0 : pool.getThreadsAwaitingConnection(), dataSource.getMaximumPoolSize(), borrows.sum(), waitNanos.sum() / 1e9);
    }

    private static HikariConfig createHikariConfig(String databaseUrl) {
        URI uri = URI.create(databaseUrl.trim());

        String userInfo = uri.getUserInfo();
        if (userInfo == null || !userInfo.contains(":")) {
            throw new IllegalArgumentException(
                    "DATABASE_URL must include credentials: postgresql://user:password@host:port/db"
            );
        }

        int colonIdx = userInfo.indexOf(':');
        String username = userInfo.substring(0, colonIdx);
        String password = userInfo.substring(colonIdx + 1);

        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);

        return config;
    }
}
