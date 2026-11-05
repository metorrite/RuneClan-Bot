package com.runeclans.bot.runescape;

/**
 * One entry from the adventure log feed (quest completions, level-ups, boss kills, etc). {@code date} is kept
 * exactly as the source sends it rather than parsed into a timestamp — see {@code RuneScapeDatabaseInitializer}
 * for why. {@code type} is the log's own event code (an {@code rs_plog_*} string) as the hub passes it on;
 * it is {@code null} for lines that carry none (the bot's old own polls), and such a line is left unclassified.
 */
public record PlayerActivity(String date, String text, String details, String type) {
    public PlayerActivity(String date, String text, String details) {
        this(date, text, details, null);
    }
}
