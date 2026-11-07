package com.runeclans.bot.internal;

import com.runeclans.bot.events.ClanVsClan;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.leagues.LeagueDiscordStore;
import com.runeclans.bot.leagues.LeagueHub;
import com.runeclans.bot.leagues.LeagueReporter;
import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Alliance leagues for the dashboard (T-759, RuneClan docs/ALLIANCE-LEAGUE.md 10): {@code GET leagues} is the server's clan's leagues with
 * their rounds as the hub describes them ({@code GET /v1/guilds/{guildId}/leagues}) plus this server's clan-vs-clan events, so the website
 * can offer "Attach an event" on an event round the clan hosts; {@code POST leagues/{leagueId}/rounds/{n}/event {eventId}} attaches one
 * of them (the hub keeps the reference), {@code .../detach} takes it off, and {@code .../report} sends the attached event's result now (the
 * reporter does the same on its own when the event finishes). The league itself (terms, standings, confirmations) lives on the website's
 * Leagues manager, which talks to the hub directly. Reached only through {@link TicketAdminApi}, which has already checked the asker may
 * use the dashboard in this server and audit-logs every change.
 */
@BService
public class LeagueAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(LeagueAdminApi.class);
    private static final Pattern LEAGUE_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final int EVENTS_LIMIT = 50;
    private static final List<Status> EVENT_STATUSES = List.of(Status.DRAFT, Status.SIGNUPS, Status.RUNNING, Status.SCORING, Status.DONE);

    private final LeagueHub hub;
    private final LeagueReporter reporter;
    private final HubEvents events;
    private final LeagueDiscordStore discord;

    public LeagueAdminApi(LeagueHub hub, LeagueReporter reporter, HubEvents events, LeagueDiscordStore discord) {
        this.hub = hub;
        this.reporter = reporter;
        this.events = events;
        this.discord = discord;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("leagues")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild);
        }
        // leagues/settings: the channel the server's league posts go to (T-760)
        if (parts.length == 2 && parts[1].equals("settings")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveSettings(guild, actor, body.get());
        }
        // leagues/{leagueId}/rounds/{n}/{event|detach|report}
        if (parts.length != 5 || !parts[2].equals("rounds")) return null;
        String leagueId = parts[1];
        if (!LEAGUE_ID.matcher(leagueId).matches()) throw new ApiError(400, "That isn't a league.");
        if (!parts[3].matches("\\d{1,3}")) throw new ApiError(400, "That isn't a round.");
        int round = Integer.parseInt(parts[3]);
        AdminRouteExtension.requireMethod(method, "POST");
        return switch (parts[4]) {
            case "event" -> attach(guild, actor, leagueId, round, body.get());
            case "detach" -> detach(guild, actor, leagueId, round);
            case "report" -> report(guild, actor, leagueId, round);
            default -> null;
        };
    }

    /** The hub's answer for this server plus the clan-vs-clan events it could attach; {@code clan: false} when the server has no clan on the hub. */
    DataObject list(Guild guild) {
        DataObject out = DataObject.empty().put("on", hub.enabled()).put("channelId", discord.channel(guild.getIdLong()).map(c -> Long.toString(c)).orElse(null));
        if (!hub.enabled()) return out.put("clan", false).put("leagues", DataArray.empty()).put("events", DataArray.empty());
        RuneClansHubClient.Answer answer = hub.guildLeagues(guild.getIdLong());
        if (answer == null) throw new ApiError(503, "Can't reach RuneClans right now. Try again in a minute.");
        if (answer.status() == 404 || answer.json() == null) {
            out.put("clan", false).put("leagues", DataArray.empty());
        } else if (!answer.ok()) {
            throw new ApiError(502, LeagueHub.refusal(new HubWriteQueue.Result(HubWriteQueue.Outcome.REFUSED, answer), "RuneClans couldn't answer."));
        } else {
            DataObject json = answer.json();
            out.put("clan", true).put("clanId", json.getString("clanId", null)).put("clanName", json.getString("clanName", null))
                    .put("leagues", json.optArray("leagues").orElse(DataArray.empty()));
        }
        DataArray list = DataArray.empty();
        // the server's events on the hub (T-932): their local ids are what the bot's league reporter looks for in a round's eventRef
        List<Event> mine;
        try {
            mine = events.events(guild.getIdLong(), EVENT_STATUSES, EVENTS_LIMIT);
        } catch (EventProblem p) {
            mine = List.of(); // no hub clan: nothing to attach
        }
        for (Event e : mine) {
            if (!ClanVsClan.is(e.template()) || e.archived()) continue;
            list.add(DataObject.empty().put("id", Long.toString(e.id())).put("name", e.name()).put("status", e.status().name())
                    .put("startsAt", e.startsAt() == null ? null : e.startsAt().toString()).put("endsAt", e.endsAt() == null ? null : e.endsAt().toString()));
        }
        return out.put("events", list);
    }

    /** {@code PUT leagues/settings {channelId|null}}: where the league posts go; a channel the bot can post in, or none. */
    DataObject saveSettings(Guild guild, Member actor, DataObject body) {
        Long channelId = null;
        if (body.hasKey("channelId") && !body.isNull("channelId")) {
            String raw = body.getString("channelId", "").strip();
            if (!raw.matches("\\d{15,20}")) throw new ApiError(400, "Pick a channel of this server, or none.");
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, raw);
            if (channel == null) throw new ApiError(400, "That channel isn't in this server.");
            if (!channel.canTalk()) throw new ApiError(400, "The bot can't post in #" + channel.getName() + ".");
            channelId = channel.getIdLong();
        }
        discord.setChannel(guild.getIdLong(), channelId, actor.getIdLong());
        log.info("Dashboard: {} set the league channel of {} to {}", actor.getId(), guild.getId(), channelId);
        return DataObject.empty().put("ok", true).put("channelId", channelId == null ? null : Long.toString(channelId));
    }

    DataObject attach(Guild guild, Member actor, String leagueId, int round, DataObject body) {
        String raw = body.getString("eventId", "").strip();
        if (!raw.matches("\\d{1,18}")) throw new ApiError(400, "Pick one of this server's clan-vs-clan events.");
        Event event = hubEvent(guild.getIdLong(), Long.parseLong(raw)).orElseThrow(() -> new ApiError(404, "That event doesn't exist in this server."));
        if (!ClanVsClan.is(event.template())) throw new ApiError(400, "Only a clan-vs-clan event can be a league round's event.");
        if (event.status() == Status.CANCELLED) throw new ApiError(400, "That event was cancelled.");
        HubWriteQueue.Result result = hub.attach(leagueId, round, guild.getIdLong(), event.id(), event.name(), null);
        DataObject answer = answerOf(result, "The hub didn't attach the event.");
        log.info("Dashboard: {} attached event {} to round {} of league {}", actor.getId(), event.id(), round, leagueId);
        return answer;
    }

    DataObject detach(Guild guild, Member actor, String leagueId, int round) {
        DataObject answer = answerOf(hub.attach(leagueId, round, guild.getIdLong(), null, null, null), "The hub didn't detach the event.");
        log.info("Dashboard: {} detached the event of round {} of league {}", actor.getId(), round, leagueId);
        return answer;
    }

    /** Reports the round's attached event now (a finished event whose automatic report failed, or a correction after a rescore). */
    DataObject report(Guild guild, Member actor, String leagueId, int round) {
        RuneClansHubClient.Answer answer = hub.guildLeagues(guild.getIdLong());
        if (answer == null) throw new ApiError(503, "Can't reach RuneClans right now. Try again in a minute.");
        if (!answer.ok() || answer.json() == null) throw new ApiError(404, "This server's clan has no leagues on RuneClans.");
        Long eventId = eventOf(answer.json(), leagueId, round);
        if (eventId == null) throw new ApiError(409, "No event is attached to round " + round + ".");
        Event event = hubEvent(guild.getIdLong(), eventId).orElseThrow(() -> new ApiError(404, "The attached event doesn't exist in this server any more."));
        if (event.status() != Status.DONE) throw new ApiError(409, "The event isn't finished yet; its result is reported when it is.");
        LeagueReporter.Outcome outcome = reporter.report(event);
        if (!outcome.ok()) throw new ApiError(outcome.status() >= 400 && outcome.status() < 600 ? outcome.status() : 502, outcome.message());
        log.info("Dashboard: {} reported event {} for round {} of league {}", actor.getId(), event.id(), round, leagueId);
        return DataObject.empty().put("ok", true).put("queued", outcome.status() == 202).put("message", outcome.message());
    }

    /** One of this server's hub events by local id; empty when it isn't one (or the hub doesn't have it any more). */
    private Optional<Event> hubEvent(long guildId, long id) {
        try {
            return events.eventById(id).filter(e -> e.guildId() == guildId);
        } catch (EventProblem p) {
            return Optional.empty();
        }
    }

    /** The bot event attached to a round, by league id and number, from the hub's guild answer. */
    static Long eventOf(DataObject guildLeagues, String leagueId, int round) {
        DataArray leagues = guildLeagues.optArray("leagues").orElse(DataArray.empty());
        for (int i = 0; i < leagues.length(); i++) {
            DataObject league = leagues.getObject(i);
            if (!leagueId.equalsIgnoreCase(league.getString("id", ""))) continue;
            DataArray rounds = league.optArray("rounds").orElse(DataArray.empty());
            for (int j = 0; j < rounds.length(); j++) {
                DataObject r = rounds.getObject(j);
                if (r.getInt("number", 0) != round) continue;
                Optional<DataObject> ref = r.optObject("eventRef");
                if (ref.isEmpty()) return null;
                String id = ref.get().getString("eventId", "");
                return id.matches("\\d{1,18}") ? Long.parseLong(id) : null;
            }
        }
        return null;
    }

    private static DataObject answerOf(HubWriteQueue.Result result, String fallback) {
        LeagueReporter.Outcome outcome = LeagueReporter.outcomeOf(result, fallback);
        if (!outcome.ok()) throw new ApiError(outcome.status() >= 400 && outcome.status() < 600 ? outcome.status() : 502, outcome.message());
        DataObject out = DataObject.empty().put("ok", true).put("queued", outcome.status() == 202).put("message", outcome.message());
        if (result.answer() != null && result.answer().json() != null) out.put("round", result.answer().json());
        return out;
    }
}
