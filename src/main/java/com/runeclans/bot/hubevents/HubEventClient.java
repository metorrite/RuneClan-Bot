package com.runeclans.bot.hubevents;

import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RuneClan Bot's calls to the hub's event routes ({@code /v1/events/...}, {@code /v1/clans/{clanId}/events...}; RuneClan-API
 * {@code EventsModule}, T-932 F1), as the bot's first-party app. Who is acting goes in the headers the hub reads: {@code X-Actor-Discord-Id}
 * (the member whose command or button it is), {@code X-Actor-Guild-Id} (the server it ran in) and {@code X-Actor-Staff} (that server, when the
 * bot checked the member holds Manage Server or the admin role). With no actor the call is the application itself, which the hub treats as an
 * organiser of every event: that is how the bot reads an event to draw a panel.
 * <p>
 * The hub's refusals come back as the bot's own {@link EventProblem} (the hub answers {@code {error, message, problems}} with the same
 * statuses the bot's engine used), so every command and button shows them as before. A hub that can't be reached is a 503 problem with
 * words for the member. No cache and no write queue: a panel must show what the hub has now, and an event action either happened or didn't.
 */
@BConfiguration
public class HubEventClient {
    /** The hub calls this needs; {@link RuneClansHubClient} in the bot, a fake in tests. */
    public interface Hub {
        RuneClansHubClient.Answer call(String method, String path, String body, Map<String, String> headers);

        /** {@code GET path} for bytes (a board image); null when there are none. */
        default byte[] bytes(String path, Map<String, String> headers) {
            return null;
        }
    }

    /** Who a call is for: a member (in a server, perhaps checked as staff there), or nobody (the application itself). */
    public record Actor(Long discordId, Long guildId, boolean staff) {
        /** The application itself: the bot reading or tidying an event, not a member acting. */
        public static final Actor APP = new Actor(null, null, false);

        /** A member acting from {@code guildId}. */
        public static Actor member(long discordId, long guildId) {
            return new Actor(discordId, guildId, false);
        }

        /** A member acting from {@code guildId}, {@code staff} when the bot checked they hold Manage Server or the admin role there. */
        public static Actor of(long discordId, long guildId, boolean staff) {
            return new Actor(discordId, guildId, staff);
        }

        Map<String, String> headers() {
            if (discordId == null) return Map.of();
            Map<String, String> h = new LinkedHashMap<>();
            h.put(ACTOR_HEADER, Long.toString(discordId));
            if (guildId != null) h.put(GUILD_HEADER, Long.toString(guildId));
            if (staff && guildId != null) h.put(STAFF_HEADER, Long.toString(guildId));
            return h;
        }
    }

    public static final String ACTOR_HEADER = "X-Actor-Discord-Id";
    public static final String GUILD_HEADER = "X-Actor-Guild-Id";
    public static final String STAFF_HEADER = "X-Actor-Staff";
    static final String DOWN_TEXT = "RuneClans can't be reached right now, so nothing was changed. Try again in a minute.";
    static final String OFF_TEXT = "Events live on RuneClans, and this bot isn't connected to it. Ask the server's admins to connect it.";

    private final Hub hub;
    private final boolean enabled;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use the other. */
    @BService
    public static HubEventClient hubEventClient(RuneClansHubClient client) {
        return new HubEventClient(new Hub() {
            @Override
            public RuneClansHubClient.Answer call(String method, String path, String body, Map<String, String> headers) {
                return client.call(method, path, body, headers);
            }

            @Override
            public byte[] bytes(String path, Map<String, String> headers) {
                return client.bytes(path, headers);
            }
        }, client.enabled());
    }

    public HubEventClient(Hub hub, boolean enabled) {
        this.hub = hub;
        this.enabled = enabled;
    }

    /** Whether the bot has a hub to talk to ({@code RUNECLANS_HUB_ENABLED}). Without one, every event call is refused with words for the member. */
    public boolean enabled() {
        return enabled;
    }

    /** {@code GET path} as {@code actor}: the hub's JSON, or a problem (404 included). */
    public DataObject get(String path, Actor actor) {
        RuneClansHubClient.Answer a = call("GET", path, null, actor);
        if (a != null && a.ok() && a.json() != null) return a.json();
        throw problem(a);
    }

    /** {@code GET path}: the hub's JSON, empty when the hub says it isn't there (404), a problem otherwise. */
    public Optional<DataObject> find(String path, Actor actor) {
        RuneClansHubClient.Answer a = call("GET", path, null, actor);
        if (a != null && a.status() == 404) return Optional.empty();
        if (a != null && a.ok() && a.json() != null) return Optional.of(a.json());
        throw problem(a);
    }

    /** A write ({@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}) as {@code actor}. The hub's JSON (an empty object for a body-less 2xx). */
    public DataObject send(String method, String path, DataObject body, Actor actor) {
        RuneClansHubClient.Answer a = call(method, path, body == null ? null : body.toString(), actor);
        if (a != null && a.ok()) return a.json() == null ? DataObject.empty() : a.json();
        throw problem(a);
    }

    /** {@code GET path} for an image as {@code actor}: the bytes, or a 503 problem when the hub has none to give. */
    public byte[] bytes(String path, Actor actor) {
        if (!enabled) throw new EventProblem(503, OFF_TEXT);
        byte[] out = hub.bytes(path, actor == null ? Map.of() : actor.headers());
        if (out == null || out.length == 0) throw new EventProblem(503, DOWN_TEXT);
        return out;
    }

    private RuneClansHubClient.Answer call(String method, String path, String body, Actor actor) {
        if (!enabled) throw new EventProblem(503, OFF_TEXT);
        return hub.call(method, path, body, actor == null ? Map.of() : actor.headers());
    }

    /** The problem behind a hub answer that wasn't a success: none, 429 or 5xx is "can't be reached"; otherwise the hub's own words and list. */
    static EventProblem problem(RuneClansHubClient.Answer a) {
        if (a == null || a.status() >= 500 || a.status() == 429) return new EventProblem(503, DOWN_TEXT);
        String message = null;
        List<String> problems = new ArrayList<>();
        DataObject json = a.json();
        if (json != null) {
            message = json.getString("message", null);
            DataArray list = json.optArray("problems").orElse(null);
            if (list != null) for (int i = 0; i < list.length(); i++) problems.add(list.getString(i));
        }
        if (message == null || message.isBlank()) message = a.status() == 404 ? "That event doesn't exist (any more)." : "RuneClans said no to that (" + a.status() + ").";
        return new EventProblem(a.status(), message, problems);
    }
}
