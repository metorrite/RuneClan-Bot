package com.runeclans.bot.tracking;

import com.runeclans.bot.runeclans.HubFeedListener;
import com.runeclans.bot.runescape.PlayerActivity;
import com.runeclans.bot.runescape.RuneScapeStatsService;
import com.runeclans.bot.runescape.stats.StatsBackends;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Tracking posts from the hub's feed (T-851): while the hub polls, every new adventure-log line it finds arrives as a {@code player.activity}
 * feed entry, and this hands each one to the same classifier and router the bot's own polls used ({@link RuneScapeStatsService#announce}), so
 * the tracking channels read exactly as before. Entries of one feed page are gathered per player and announced together when the page ends,
 * so a session's repeats collapse into one line ("defeated X 10 times") as they did from one poll. An entry's hub id is remembered (the newest
 * {@value Buffer#REMEMBERED}) so a page the hub sends twice posts nothing twice; the feed cursor already keeps a restart from replaying old pages.
 * Nothing is posted while the bot polls itself (its own polls announce), and the hub never sends lines of a player who hid their log.
 */
@BService
public class HubActivityPosts implements HubFeedListener {
    private final Buffer buffer;

    public HubActivityPosts(RuneScapeStatsService stats, StatsBackends backends) {
        this.buffer = new Buffer(backends::hubPolls, stats::announce);
    }

    @Override
    public void onHubFeed(String kind, DataObject data) {
        buffer.onHubFeed(kind, data);
    }

    @Override
    public void onHubFeedPage() {
        buffer.onHubFeedPage();
    }

    /** What announces a player's new lines to every server whose clan they are in. */
    public interface Announcer {
        void announce(String rsn, List<PlayerActivity> newLines);
    }

    /** The gathering itself, apart from the framework's one-constructor service so tests can drive it with a switch and a sink. */
    static final class Buffer implements HubFeedListener {
        private static final Logger log = LoggerFactory.getLogger(HubActivityPosts.class);
        static final String KIND = "player.activity";
        static final int REMEMBERED = 50_000;

        private final BooleanSupplier hubPolls;
        private final Announcer announce;
        private final Map<String, List<PlayerActivity>> pending = new LinkedHashMap<>();
        private final Set<Long> seen = Collections.newSetFromMap(new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > REMEMBERED;
            }
        });

        Buffer(BooleanSupplier hubPolls, Announcer announce) {
            this.hubPolls = hubPolls;
            this.announce = announce;
        }

        @Override
        public synchronized void onHubFeed(String kind, DataObject data) {
            if (!KIND.equals(kind) || data == null) return;
            if (!hubPolls.getAsBoolean()) return;
            String rsn = data.getString("rsn", null);
            String text = data.getString("text", null);
            if (rsn == null || rsn.isBlank() || text == null) return;
            if (data.hasKey("id") && !data.isNull("id") && !seen.add(data.getLong("id"))) return;
            pending.computeIfAbsent(rsn, k -> new ArrayList<>()).add(new PlayerActivity(data.getString("date", ""), text, data.getString("details", "")));
        }

        @Override
        public void onHubFeedPage() {
            Map<String, List<PlayerActivity>> batch;
            synchronized (this) {
                if (pending.isEmpty()) return;
                batch = new LinkedHashMap<>(pending);
                pending.clear();
            }
            batch.forEach((rsn, lines) -> {
                try {
                    announce.announce(rsn, lines);
                } catch (RuntimeException e) {
                    log.warn("Announcing hub activity failed: {}", e.toString());
                }
            });
        }

        /** How many entries are waiting for the page to end (tests). */
        synchronized int pendingCount() {
            int n = 0;
            for (List<PlayerActivity> l : pending.values()) n += l.size();
            return n;
        }

        /** For tests: drive a page's worth of entries and its end. */
        static void page(Buffer posts, List<DataObject> entries) {
            for (DataObject e : entries) posts.onHubFeed(KIND, e);
            posts.onHubFeedPage();
        }
    }
}
