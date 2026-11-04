package com.runeclans.bot.runeclans;

import com.runeclans.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * JonnyBot as a client of the RuneClans hub's public API (runeclans-hub docs/API.md), with JonnyBot's own first-party key. Every call is a
 * small JSON request with {@code Authorization: Bearer <key>}; nothing here is used unless {@link #enabled()} (see {@link RuneClansSettings}).
 * The key and any Discord id are never logged.
 * <p>
 * For the bot reading hub data instead of its own database (runeclans-hub PLATFORM-DESIGN B-0.7, 9.2, 9.4): one keep-alive HTTP/1.1
 * connection pool, {@link HubRetry} retries for calls safe to repeat, {@link #read} with a small bounded cache that hub changes empty
 * ({@link #onHubChange}) and that answers "may be a few minutes old" while the hub is down, and {@link #write} with a bounded in-memory
 * retry queue for writes the hub can't take right now.
 */
@BConfiguration
public class RuneClansHubClient {
    private static final Logger log = LoggerFactory.getLogger(RuneClansHubClient.class);

    /** One HTTP exchange with the hub; null when it can't be reached. Replaceable in tests. */
    public interface Transport {
        Answer call(String method, String path, String body);

        /** The same with extra request headers ({@code If-Match}); a transport that has none to send ignores them. */
        default Answer call(String method, String path, String body, Map<String, String> headers) {
            return call(method, path, body);
        }
    }

    /** The hub's status and JSON answer ({@code json} null when the body wasn't JSON), and its {@code Retry-After} in seconds, if any. */
    public record Answer(int status, DataObject json, Integer retryAfterSeconds) {
        public Answer(int status, DataObject json) {
            this(status, json, null);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** The hub couldn't be asked (down, busy or failing), as opposed to answering "nobody": {@link #aliasOf}. */
    public static final class Unreachable extends RuntimeException {
        public Unreachable(String message) {
            super(message);
        }
    }

    /** A {@link #read} answer: from the cache or not, and stale when the hub couldn't be asked and the cached copy is past its fresh time. */
    public record Read(Answer answer, boolean fromCache, boolean stale) {}

    private final RuneClansSettings settings;
    private final Transport transport;
    private HubCache cache;
    private HubWriteQueue writes;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use the other. */
    @BService
    public static RuneClansHubClient runeClansHubClient(BotConfig config) {
        RuneClansSettings settings = RuneClansSettings.from(config::getRuneClansSetting);
        if (settings.problem() != null) log.error("RuneClans hub client is off: {}", settings.problem());
        else if (settings.enabled()) log.info("RuneClans hub client is on ({}).", settings.baseUrl());
        return new RuneClansHubClient(settings, settings.enabled() ? HubRetry.around(http(settings)) : (m, p, b) -> null);
    }

    RuneClansHubClient(RuneClansSettings settings, Transport transport) {
        this.settings = settings;
        this.transport = transport;
        withClock(Clock.systemUTC());
    }

    /** Fresh cache and write queue on {@code clock}; for tests. */
    RuneClansHubClient withClock(Clock clock) {
        this.cache = new HubCache(HubCache.MAX_ENTRIES, clock);
        this.writes = new HubWriteQueue(transport, clock, HubWriteQueue.MAX_SIZE);
        return this;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** A raw call, for {@code RsLinkAuth}'s transport. Null when off or unreachable. */
    public Answer call(String method, String path, String body) {
        if (!enabled()) return null;
        return transport.call(method, path, body);
    }

    /** A raw call with extra headers ({@code If-Match: <rev>} for the hub's request cards, T-734). Null when off or unreachable. */
    public Answer call(String method, String path, String body, Map<String, String> headers) {
        if (!enabled()) return null;
        return transport.call(method, path, body, headers);
    }

    // ---------- cached reads, queued writes, change eviction ----------

    /**
     * {@code GET path} through the cache: a fresh cached answer if there is one, else the hub's (kept when it is a success, tagged with
     * {@code tags} such as {@code clan:<id>} so {@link #onHubChange} can drop it). When the hub can't be reached or is busy, the cached copy
     * is used while it is under 15 minutes old, marked stale. Null when off, or when the hub is down and nothing is cached.
     */
    public Read read(String path, String... tags) {
        return read(path, HubCache.FRESH, tags);
    }

    /** {@link #read}, keeping a success fresh for {@code keepFresh} (at most 15 minutes): for answers that a tagged hub change empties. */
    public Read read(String path, Duration keepFresh, String... tags) {
        if (!enabled()) return null;
        Answer fresh = cache.fresh(path);
        if (fresh != null) return new Read(fresh, true, false);
        Answer a = transport.call("GET", path, null);
        if (a != null && a.ok()) {
            cache.put(path, a, Set.of(tags), keepFresh);
            return new Read(a, false, false);
        }
        if (HubRetry.worthRetrying(a)) {
            HubCache.Hit hit = cache.lookup(path);
            if (hit != null) return new Read(hit.answer(), true, hit.stale());
        }
        return a == null ? null : new Read(a, false, false);
    }

    /**
     * A write ({@code POST}/{@code PUT}/{@code DELETE}) that may wait: sent now, or held up to 5 minutes when the hub can't take it
     * ({@link HubWriteQueue.Outcome#QUEUED}: tell the member "saved, will sync shortly"); {@code done} hears how a held write ended.
     * {@link HubWriteQueue.Outcome#REFUSED} when off, when the queue is full, or when the hub said no.
     */
    public HubWriteQueue.Result write(String method, String path, String body, Consumer<HubWriteQueue.Result> done) {
        if (!enabled()) return new HubWriteQueue.Result(HubWriteQueue.Outcome.REFUSED, null);
        return writes.submit(new HubWriteQueue.Write(method, path, body, done));
    }

    /** {@link #write}, replacing any held write with the same {@code supersedeKey} (see {@link HubWriteQueue.Write}). */
    public HubWriteQueue.Result write(String method, String path, String body, Consumer<HubWriteQueue.Result> done, String supersedeKey) {
        if (!enabled()) return new HubWriteQueue.Result(HubWriteQueue.Outcome.REFUSED, null);
        return writes.submit(new HubWriteQueue.Write(method, path, body, done, supersedeKey));
    }

    /** Sends the held writes (the feed lane runs this every 10 seconds). Returns how many were sent. */
    public int drainWrites() {
        return enabled() ? writes.drain() : 0;
    }

    /** A hub change (feed entry or verified webhook: {@code kind} and {@code data}): drops the cached answers it makes out of date. */
    public int onHubChange(String kind, DataObject data) {
        return HubChanges.evict(cache, kind, data);
    }

    /** Drops every cached answer under {@code tag}: what a module does after its own write, so its next read is not its stale copy (T-759). */
    public int forget(String tag) {
        return cache == null ? 0 : cache.evictTag(tag);
    }

    HubCache cache() {
        return cache;
    }

    HubWriteQueue writes() {
        return writes;
    }

    // ---------- reads ----------

    /** {@code GET /v1/feed}: JonnyBot's change feed after {@code cursor} (null for the start). */
    public Answer feed(String cursor, int limit) {
        return call("GET", "/v1/feed?limit=" + limit + (cursor == null ? "" : "&cursor=" + enc(cursor)), null);
    }

    /** The Discord id behind one of JonnyBot's aliases, when the player granted JonnyBot identity. */
    public Optional<Long> discordIdOf(String alias) {
        Answer a = call("GET", "/v1/players/" + enc(alias), null);
        if (a == null || !a.ok() || a.json() == null) return Optional.empty();
        String id = a.json().getString("discordId", null);
        try {
            return id == null ? Optional.empty() : Optional.of(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * JonnyBot's alias for the player behind a Discord account ({@code GET /v1/links/discord/{id}}): only when they granted JonnyBot identity.
     * Empty when they didn't, have no link, or the hub client is off; {@link Unreachable} when the hub can't be asked right now, so a caller
     * never takes "the hub is down" for "no link".
     */
    public Optional<String> aliasOf(long discordId) {
        Answer a = call("GET", "/v1/links/discord/" + discordId, null);
        if (enabled() && (a == null || a.status() >= 500 || a.status() == 429)) throw new Unreachable("The hub couldn't look up a link (" + (a == null ? "no answer" : a.status()) + ")");
        if (a == null || !a.ok() || a.json() == null) return Optional.empty();
        return Optional.ofNullable(a.json().getString("alias", null));
    }

    /**
     * {@link #aliasOf} for work that must not take an outage for an answer: empty only when the hub said the player has no alias for JonnyBot
     * ({@code 404}), and null when it couldn't be asked or answered anything else.
     */
    public Optional<String> aliasIfAnswered(long discordId) {
        Answer a = call("GET", "/v1/links/discord/" + discordId, null);
        if (a == null) return null;
        if (a.status() == 404) return Optional.empty();
        return a.ok() && a.json() != null ? Optional.ofNullable(a.json().getString("alias", null)) : null;
    }

    /**
     * {@code POST /v1/guilds/{id}/xp/gains} (T-536): the XP each player gained from {@code from} to {@code to} in the hub's ledger, RuneSync's
     * timed XP only (catch-up and suspect XP left out). {@code skillId} null is every skill. Null when the hub can't be reached; the hub's own
     * answer otherwise ({@code 404} while its XP ledger is off or the guild isn't registered).
     */
    public Answer xpGains(long guildId, java.util.Collection<String> aliases, Integer skillId, java.time.Instant from, java.time.Instant to) {
        DataObject body = DataObject.empty().put("players", net.dv8tion.jda.api.utils.data.DataArray.fromCollection(aliases)).put("from", from.toString()).put("to", to.toString())
                .put("catchup", "exclude").put("suspect", "exclude");
        if (skillId != null) body.put("skills", String.valueOf(skillId));
        return call("POST", "/v1/guilds/" + guildId + "/xp/gains", body.toString());
    }

    /**
     * {@code POST /v1/guilds/{id}/xp/gainers} (T-712): who among the server's members gained timed RuneSync XP on one UTC day, and how much, with
     * their Discord id when their grant shares it. Null when the hub can't be reached; {@code 404} from a hub without the route, with its ledger
     * off, or when the server isn't registered.
     */
    public Answer xpGainers(long guildId, java.time.LocalDate day) {
        return call("POST", "/v1/guilds/" + guildId + "/xp/gainers", DataObject.empty().put("day", day.toString()).toString());
    }

    /**
     * {@code GET /v1/guilds/{id}/events}: the newest plugin events of a server's members, as the hub filters them (only members whose grant
     * to JonnyBot covers that server, only categories both the grant and the player's current consent allow). For clan websites (T-303).
     */
    public Answer guildEvents(long guildId, int limit) {
        return call("GET", "/v1/guilds/" + guildId + "/events?limit=" + Math.max(1, Math.min(limit, 100)), null);
    }

    /**
     * {@code GET /v1/guilds/{id}/xp/activity}: a server's XP per day and its busy hours of the week, as clan totals only, for the clan website's
     * activity heatmap (T-583). Through the cache, kept fresh 15 minutes: it changes by the hour.
     */
    public Read guildXpActivity(long guildId) {
        return read("/v1/guilds/" + guildId + "/xp/activity", java.time.Duration.ofMinutes(15));
    }

    /**
     * {@code GET /v1/guilds/{id}/xp/race} (T-710): the server's top 25 RuneSync XP gainers over the last 30 UTC days, each with their running
     * total at the end of every day, for the clan website's leaderboard race (which keeps the top 10 the clan may show). Through the cache,
     * kept fresh 15 minutes.
     */
    public Read guildXpRace(long guildId) {
        return read("/v1/guilds/" + guildId + "/xp/race?days=30&limit=25", java.time.Duration.ofMinutes(15));
    }

    // ---------- writes ----------

    /** {@code PUT /v1/guilds/{id}}: tells the hub a server JonnyBot is in, by name. */
    public boolean putGuild(long guildId, String name) {
        Answer a = call("PUT", "/v1/guilds/" + guildId, DataObject.empty().put("name", name == null ? null : name.length() > 100 ? name.substring(0, 100) : name).toString());
        return a != null && a.ok();
    }

    /** {@code PUT /v1/guilds/{id}/members/{alias}}: a player who granted JonnyBot is in that server. 404 when their grant doesn't cover it. */
    public boolean putGuildMember(long guildId, String alias) {
        Answer a = call("PUT", "/v1/guilds/" + guildId + "/members/" + enc(alias), "{}");
        return a != null && a.ok();
    }

    /** {@code Retry-After} in whole seconds (the hub sends seconds), or null when absent or not a small number. */
    static Integer retryAfter(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,5}")) return null;
        return Integer.parseInt(raw.strip());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static Transport http(RuneClansSettings settings) {
        // one client for the bot's life: HTTP/1.1 keeps its connections to the hub open between calls (no connect cost per call)
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        return new Transport() {
            @Override
            public Answer call(String method, String path, String body) {
                return call(method, path, body, Map.of());
            }

            @Override
            public Answer call(String method, String path, String body, Map<String, String> headers) {
                return send(client, settings, method, path, body, headers);
            }
        };
    }

    private static Answer send(HttpClient client, RuneClansSettings settings, String method, String path, String body, Map<String, String> headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.baseUrl() + path)).timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + settings.apiKey()).header("Content-Type", "application/json").header("User-Agent", "JonnyBot runeclans-client");
            // the website's request id, when this call is part of an internal API request (T-674), so the hub's log line carries it too
            String requestId = com.runeclans.bot.ops.OpsTelemetry.currentRequestId();
            if (requestId != null) request.header(com.runeclans.bot.ops.RequestIds.HEADER, requestId);
            headers.forEach(request::header);
            request = body == null ? request.method(method, HttpRequest.BodyPublishers.noBody()) : request.method(method, HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            DataObject json = null;
            try {
                json = DataObject.fromJson(response.body());
            } catch (RuntimeException ignored) {
                // not JSON: no body
            }
            return new Answer(response.statusCode(), json, retryAfter(response.headers().firstValue("Retry-After").orElse(null)));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
}
