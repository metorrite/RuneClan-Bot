package com.runeclans.bot.hubevents;

import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.events.EventRepository.ClaimStatus;
import com.runeclans.bot.runeclans.HubFeedListener;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The hub feed's {@code clan_event.*} entries (RuneClan-API {@code EventService.KINDS}, {@code EventEngine.KINDS}), turned into the bot's own
 * {@link EventChanges} so everything that draws an event in Discord (signup mirror, receipts, boards, co-host panels) redraws within the feed's poll:
 * an edit on the website reaches the panel in seconds. The event is mapped to its server's local id on first sight (an event made on the website has
 * none yet), by the server and clan its data names (T-933); a clan whose server the bot isn't in is skipped. A participant entry names the person by
 * hub id only, so the signup mirror reconciles the roster with the Discord signup on every roster change rather than trusting the entry. Each event revision is dispatched once ({@code rev_seen}),
 * so a page that repeats an entry, or the bot's own write coming back, doesn't redraw twice. Called on the feed's lane; never throws.
 */
@BService
public class HubEventFeed implements HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(HubEventFeed.class);
    static final String PREFIX = "clan_event.";

    private final HubEvents events;
    private final HubEventMap map;
    private final EventChanges changes;
    private volatile JDA jda;

    public HubEventFeed(HubEvents events, HubEventMap map, EventChanges changes) {
        this.events = events;
        this.map = map;
        this.changes = changes;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    /** The servers the bot is in, for a clan the event map hasn't met. Package-private so tests can hand a list. */
    List<Long> candidateGuilds() {
        JDA client = jda;
        return client == null ? List.of() : com.runeclans.bot.discord.Shards.guilds(client).stream().map(Guild::getIdLong).toList();
    }

    @Override
    public void onHubFeed(String kind, DataObject entry) {
        if (kind == null || !kind.startsWith(PREFIX) || !events.enabled()) return;
        try {
            dispatch(kind.substring(PREFIX.length()), entry);
        } catch (RuntimeException e) {
            log.warn("Hub {} entry couldn't be applied: {}", kind, e.toString());
        }
    }

    /** Applies one entry. The change it published, for the tests; empty when it was skipped. */
    Optional<EventChanges.Change> dispatch(String what, DataObject entry) {
        DataObject data = entry.optObject("data").orElse(DataObject.empty());
        DataObject event = data.optObject("event").orElse(null);
        UUID hubId = HubEvents.uuid(event != null ? event.getString("id", null) : data.getString("eventId", null));
        if (hubId == null) hubId = HubEvents.uuid(entry.getString("id", null));
        if (hubId == null) return Optional.empty();
        UUID clanId = HubEvents.uuid(event != null ? event.getString("clanId", null) : data.getString("clanId", null));

        if (what.equals("deleted")) {
            Optional<HubEventMap.Row> row = map.eventByHubId(hubId);
            if (row.isEmpty()) return Optional.empty();
            EventChanges.Change change = new EventChanges.Change(EventChanges.Kind.STAGE, row.get().guildId(), row.get().localId(), 0, 0, false, "deleted");
            changes.publish(change);
            map.forget(row.get().localId());
            return Optional.of(change);
        }

        HubEventMap.Row row = map.eventByHubId(hubId).orElse(null);
        if (row == null && clanId != null && data.hasKey("guildId") && !data.isNull("guildId")) {
            // T-933: every clan_event entry names the host clan's server, so an event made on the website is mapped without a lookup
            long guildId = HubEvents.discordId(String.valueOf(data.get("guildId")));
            if (guildId != 0) {
                map.localId(hubId, clanId, guildId);
                row = map.eventByHubId(hubId).orElseThrow();
            }
        }
        if (row == null) {
            if (clanId == null) {
                // a claim, progress or standings entry about an event this bot never saw: its event is looked up to learn the clan
                DataObject json;
                try {
                    json = events.client().find("/v1/events/" + hubId, HubEventClient.Actor.APP).orElse(null);
                } catch (RuntimeException e) {
                    json = null;
                }
                clanId = json == null ? null : HubEvents.uuid(json.getString("clanId", null));
                if (clanId == null) return Optional.empty();
            }
            Optional<Long> guild = events.guildOf(clanId, candidateGuilds());
            if (guild.isEmpty()) return Optional.empty();
            map.localId(hubId, clanId, guild.get());
            row = map.eventByHubId(hubId).orElseThrow();
        }
        long guildId = row.guildId();
        long local = row.localId();
        long rev = event == null ? 0 : event.getLong("rev", 0);
        if (rev > 0 && !map.seen(local, rev)) return Optional.empty();

        EventChanges.Change change = switch (what) {
            case "created", "updated" -> new EventChanges.Change(EventChanges.Kind.SETUP, guildId, local, 0, 0, false, "");
            case "stage" -> new EventChanges.Change(EventChanges.Kind.STAGE, guildId, local, 0, 0, false, data.getString("to", ""));
            case "participant" -> {
                String kind = data.getString("change", "");
                long who = HubEvents.discordId(data.getString("discordId", null));
                if (who != 0 && kind.equals("joined")) changes.publish(new EventChanges.Change(EventChanges.Kind.PLAYER_JOINED, guildId, local, 0, who, false, ""));
                if (who != 0 && (kind.equals("left") || kind.equals("banned"))) changes.publish(new EventChanges.Change(EventChanges.Kind.PLAYER_LEFT, guildId, local, 0, who, false, ""));
                yield new EventChanges.Change(EventChanges.Kind.ROSTER, guildId, local, 0, who, false, kind);
            }
            case "claim" -> {
                DataObject claim = data.optObject("claim").orElse(DataObject.empty());
                if (!data.getString("change", "").equals("decided")) yield null;
                UUID claimHubId = HubEvents.uuid(claim.getString("id", null));
                long claimLocal = claimHubId == null ? 0 : map.localId(HubEventMap.CLAIM, claimHubId, local);
                boolean approved = ClaimStatus.APPROVED.name().equals(claim.getString("status", ""));
                if (claimLocal != 0 && events.echoOfDecisionHere(claimLocal, approved)) yield null; // decided from Discord: already published there
                yield new EventChanges.Change(EventChanges.Kind.CLAIM_DECIDED, guildId, local, claimLocal, HubEvents.discordId(claim.getString("discordId", null)), approved,
                        claim.getString("decisionNote", ""));
            }
            case "progress", "standings" -> new EventChanges.Change(EventChanges.Kind.PROGRESS, guildId, local, 0, 0, false, "");
            case "award", "draw", "wrapup" -> new EventChanges.Change(EventChanges.Kind.SETUP, guildId, local, 0, 0, false, what);
            default -> null; // reminder_due and anything newer: PR 2's reminders listen for it themselves
        };
        if (change == null) return Optional.empty();
        changes.publish(change);
        return Optional.of(change);
    }
}
