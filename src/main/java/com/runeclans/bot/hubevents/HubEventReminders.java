package com.runeclans.bot.hubevents;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.Mentions;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventSpace;
import com.runeclans.bot.events.EventSpaceRepository;
import com.runeclans.bot.events.EventSpaces;
import com.runeclans.bot.runeclans.HubFeedListener;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Reminds the players before an event starts (T-928, T-934): the hub keeps each event's reminder times and feeds {@code clan_event.reminder_due}
 * when one comes; this posts it in Discord. It goes in the event's announcements channel, else its general channel, else its live board channel,
 * pinging the event's participant role when the bot made one and nothing else; an event with none of those has each player told by DM. A bot that
 * isn't live posts nothing ({@link AutoPostRouter}). Each due reminder reaches the feed once, so nothing here needs to remember what was sent.
 */
@BService
public class HubEventReminders implements HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(HubEventReminders.class);
    static final String KIND = "clan_event.reminder_due";
    /** At most this many players are DMed about one reminder. */
    static final int MAX_DMS = 200;

    private final HubEvents events;
    private final HubEventMap map;
    private final EventSpaceRepository space;
    private final ScheduledExecutorService executor = BotScheduler.lane("hub-event-reminders");
    private volatile JDA jda;

    public HubEventReminders(HubEvents events, EventSpaceRepository space) {
        this.events = events;
        this.map = events.map();
        this.space = space;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    /** A seam for the tests, which have no JDA start-up event. */
    void useJda(JDA client) {
        this.jda = client;
    }

    @Override
    public void onHubFeed(String kind, DataObject entry) {
        if (!KIND.equals(kind) || !events.enabled()) return;
        executor.execute(() -> {
            try {
                log.info("Event reminder: {}", remind(entry));
            } catch (RuntimeException e) {
                log.warn("An event reminder couldn't be posted: {}", e.toString());
            }
        });
    }

    /** Posts one due reminder. Returns a sentence on what happened, for the logs and the tests. */
    String remind(DataObject entry) {
        DataObject data = entry.optObject("data").orElse(DataObject.empty());
        DataObject json = data.optObject("event").orElse(null);
        UUID hubId = HubEvents.uuid(json == null ? null : json.getString("id", null));
        if (hubId == null) return "skipped: no event in the entry";
        HubEventMap.Row row = map.eventByHubId(hubId).orElse(null);
        if (row == null) {
            UUID clanId = HubEvents.uuid(json.getString("clanId", null));
            long guildId = HubEvents.discordId(data.hasKey("guildId") && !data.isNull("guildId") ? String.valueOf(data.get("guildId")) : null);
            if (clanId == null || guildId == 0) return "skipped: the event isn't one of this bot's servers'";
            map.localId(hubId, clanId, guildId);
            row = map.eventByHubId(hubId).orElseThrow();
        }
        Event event = events.toEvent(json, row);
        if (event.startsAt() == null) return "skipped: " + event.name() + " has no start time";
        if (AutoPostRouter.diverting()) return "skipped: this bot is not live";
        JDA client = jda;
        Guild guild = client == null ? null : Shards.guild(client, row.guildId());
        if (guild == null) return "skipped: the bot isn't in the event's server";

        long starts = event.startsAt().getEpochSecond();
        String text = "**" + event.name() + "** starts <t:" + starts + ":R> (<t:" + starts + ":f>).";
        Long channelId = channelOf(event);
        GuildMessageChannel channel = channelId == null ? null : guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel != null && channel.canTalk()) {
            Long role = participantRole(event.id());
            var send = role == null ? Mentions.onlyRoles(channel.sendMessage(text)) : Mentions.onlyRoles(channel.sendMessage("<@&" + role + "> " + text), role);
            send.queue(ok -> { }, error -> log.warn("Couldn't post the reminder of event {} in #{}: {}", event.id(), channel.getName(), error.getMessage()));
            return "posted in #" + channel.getName() + " for " + event.name() + (role == null ? "" : ", pinging the participant role");
        }
        int told = 0;
        for (Participant p : events.participants(event.id())) {
            if (p.userId() == 0 || p.waiting()) continue;
            if (told++ >= MAX_DMS) break;
            long userId = p.userId();
            client.retrieveUserById(userId).flatMap(user -> user.openPrivateChannel()).flatMap(dm -> dm.sendMessage(text))
                    .queue(ok -> { }, error -> log.debug("Couldn't DM {} the reminder of event {}: {}", userId, event.id(), error.getMessage()));
        }
        return "no channel to post in: " + Math.min(told, MAX_DMS) + " players DMed about " + event.name();
    }

    /** Where the reminder goes: the announcements channel the bot made, else the general one, else the live board channel. */
    Long channelOf(Event event) {
        Long announcements = null, general = null;
        for (EventSpaceRepository.ChannelRow r : space.channels(event.id())) {
            if (r.key().equals(EventSpace.Kind.ANNOUNCEMENTS.key())) announcements = r.channelId();
            if (r.key().equals(EventSpace.Kind.GENERAL.key())) general = r.channelId();
        }
        if (announcements != null) return announcements;
        if (general != null) return general;
        return EventSpaces.boardChannelOf(event, space);
    }

    private Long participantRole(long eventId) {
        for (EventSpaceRepository.RoleRow r : space.roles(eventId)) if (r.key().equals(EventSpace.PARTICIPANT_ROLE)) return r.roleId();
        return null;
    }
}
