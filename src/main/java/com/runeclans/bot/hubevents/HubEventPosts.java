package com.runeclans.bot.hubevents;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.BoardUpdates;
import com.runeclans.bot.events.EventBoardUpdates;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventUpdatePostJob;
import com.runeclans.bot.events.EventUpdateRepository;
import com.runeclans.bot.runeclans.HubFeedListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * The posts the hub's event engine asks the bot to make (T-941): a prize draw's result ({@code clan_event.draw}, drawn by the hub at the event's end or
 * from the website), the prizes being worked out ({@code clan_event.award}), and skill of the week's announcements ({@code skill_week.changed}
 * with a text and the clan's chosen channel). The hub writes the words; the bot posts them where the event's updates go (its chosen channel, its
 * announcements, else its board) through the Discord outbox ({@link EventUpdatePostJob}, no mentions), each noted once ({@link EventUpdateRepository})
 * so a feed page read twice never posts twice. A bot that is not live posts nothing. Called on the feed's lane; never throws.
 */
@BService
public class HubEventPosts implements HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(HubEventPosts.class);
    static final String DRAW = "clan_event.draw";
    static final String AWARD = "clan_event.award";
    static final String SKILL_WEEK = "skill_week.changed";

    private final HubEvents events;
    private final HubEventMap map;
    private final EventUpdateRepository posts;
    private final EventBoardUpdates updates;
    private final DiscordOutbox outbox;

    public HubEventPosts(HubEvents events, EventUpdateRepository posts, EventBoardUpdates updates, DiscordOutbox outbox) {
        this.events = events;
        this.map = events.map();
        this.posts = posts;
        this.updates = updates;
        this.outbox = outbox;
    }

    @Override
    public void onHubFeed(String kind, DataObject entry) {
        if (kind == null || !events.enabled() || !(kind.equals(DRAW) || kind.equals(AWARD) || kind.equals(SKILL_WEEK))) return;
        try {
            dispatch(kind, entry);
        } catch (RuntimeException e) {
            log.warn("Hub {} entry couldn't be posted: {}", kind, e.toString());
        }
    }

    /** Applies one entry. True when a post was handed to the outbox. */
    boolean dispatch(String kind, DataObject entry) {
        DataObject data = entry.optObject("data").orElse(DataObject.empty());
        UUID hubId = HubEvents.uuid(data.getString("eventId", null));
        if (hubId == null) return false; // a skill-week setting change names no event: nothing to post
        HubEventMap.Row row = rowOf(hubId, data);
        if (row == null) return false;
        String key;
        String text;
        String postKind;
        Long channel;
        switch (kind) {
            case DRAW -> {
                key = data.getString("key", null);
                text = data.getString("text", null);
                postKind = "DRAW";
                channel = updatesChannel(row);
            }
            case SKILL_WEEK -> {
                key = data.getString("key", null);
                text = data.getString("text", null);
                postKind = "SKILL_WEEK";
                long chosen = HubEvents.discordId(data.getString("channelId", null));
                channel = chosen == 0 ? null : chosen;
            }
            default -> {
                String change = data.getString("change", "");
                long added = data.getLong("added", 0);
                if (!(change.equals("generated") || change.equals("recorded")) || added <= 0) return false;
                Event event = eventOf(row);
                if (event == null) return false;
                key = "award:" + entry.getString("id", hubId.toString()) + ":" + change;
                text = "🏆 The prizes of **" + event.name() + "** are worked out: " + added + (added == 1 ? " award" : " awards") + " recorded. The winners can see theirs on RuneClans.";
                postKind = "AWARD";
                channel = updatesChannel(row);
            }
        }
        if (key == null || key.isBlank() || text == null || text.isBlank()) return false;
        if (AutoPostRouter.diverting()) {
            log.info("Event {}: {} post skipped, this bot is not live", row.localId(), postKind);
            return false;
        }
        if (channel == null) {
            log.info("Event {}: a {} post has nowhere to go (no announcements or board channel)", row.localId(), postKind);
            return false;
        }
        if (!posts.claim(row.localId(), key, postKind, text)) return false;
        outbox.enqueue(EventUpdatePostJob.KIND, row.guildId(), 0L, "event-update:" + row.localId() + ":" + key, EventUpdatePostJob.payload(row.localId(), channel, key, text));
        return true;
    }

    /** The event's row in the map; an event this bot never saw is mapped by the server the entry names (every clan_event entry carries it, T-933). */
    private HubEventMap.Row rowOf(UUID hubId, DataObject data) {
        Optional<HubEventMap.Row> row = map.eventByHubId(hubId);
        if (row.isPresent()) return row.get();
        UUID clanId = HubEvents.uuid(data.getString("clanId", null));
        long guildId = data.hasKey("guildId") && !data.isNull("guildId") ? HubEvents.discordId(String.valueOf(data.get("guildId"))) : 0;
        if (clanId == null || guildId == 0) return null;
        map.localId(hubId, clanId, guildId);
        return map.eventByHubId(hubId).orElse(null);
    }

    private Event eventOf(HubEventMap.Row row) {
        try {
            return events.event(row.guildId(), row.localId());
        } catch (EventProblem e) {
            return null;
        }
    }

    /** Where the event's update posts go: its rules' channel, its announcements channel, else its live board channel. */
    private Long updatesChannel(HubEventMap.Row row) {
        Event event = eventOf(row);
        if (event == null) return null;
        return updates.channelFor(event, BoardUpdates.of(DataObject.fromJson(event.config())));
    }
}
