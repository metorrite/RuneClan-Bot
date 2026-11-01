package com.runeclans.bot.database;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T-836: the bot database pool size and the log level, with their defaults. */
class DatabaseSourceTest {

    @Test
    void poolSizeDefaultsToTenAndAcceptsOnlyASaneWholeNumber() {
        assertEquals(10, DatabaseSource.POOL_SIZE);
        assertEquals(10, DatabaseSource.poolSize(null));
        assertEquals(10, DatabaseSource.poolSize("  "));
        assertEquals(15, DatabaseSource.poolSize(" 15 "));
        assertEquals(1, DatabaseSource.poolSize("1"));
        assertEquals(50, DatabaseSource.poolSize("50"));
        assertEquals(10, DatabaseSource.poolSize("0"));
        assertEquals(10, DatabaseSource.poolSize("51"));
        assertEquals(10, DatabaseSource.poolSize("-3"));
        assertEquals(10, DatabaseSource.poolSize("ten"));
    }

    @Test
    void logsAtInfoUnlessLogLevelSaysOtherwise() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Level expected = Level.toLevel(System.getenv("LOG_LEVEL"), Level.INFO);
        assertEquals(expected, context.getLogger(Logger.ROOT_LOGGER_NAME).getLevel(), "logback.xml sets the root level (DEBUG without it)");
    }
}
