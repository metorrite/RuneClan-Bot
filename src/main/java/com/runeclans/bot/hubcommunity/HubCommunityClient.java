package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * RuneClan Bot's calls to the hub's community routes (runeclans-hub {@code community/}, T-864): polls, signups, the group finder and forms,
 * as the bot's first-party app. A member is named by {@code discordUserId}; whoever made a change by {@code byDiscordId}. Every failure
 * becomes a {@link HubCommunityProblem} with words for the member, so a button can show it as it is.
 * <ul>
 *   <li>{@link #get}/{@link #find}: a read (no cache: the panels must show what the hub has now).</li>
 *   <li>{@link #send}: an edit or staff action, with {@code If-Match} when a rev is given.</li>
 *   <li>{@link #memberWrite}: a member's own write (vote, entry), held in the hub write queue for up to 5 minutes when the hub can't take it
 *       ({@link HubCommunityProblem.Kind#QUEUED}).</li>
 * </ul>
 */
@BConfiguration
public class HubCommunityClient {
    /** The hub calls this needs; {@link RuneClansHubClient} in the bot, a fake in tests. */
    public interface Hub {
        RuneClansHubClient.Answer call(String method, String path, String body, Map<String, String> headers);

        /** A member write; {@code supersedeKey} names the member's answer to one item, so a newer one replaces an older held one. */
        HubWriteQueue.Result write(String method, String path, String body, String supersedeKey);
    }

    private final Hub hub;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static HubCommunityClient hubCommunityClient(RuneClansHubClient client) {
        return new HubCommunityClient(new Hub() {
            @Override
            public RuneClansHubClient.Answer call(String method, String path, String body, Map<String, String> headers) {
                return client.call(method, path, body, headers);
            }

            @Override
            public HubWriteQueue.Result write(String method, String path, String body, String supersedeKey) {
                return client.write(method, path, body, null, supersedeKey);
            }
        });
    }

    public HubCommunityClient(Hub hub) {
        this.hub = hub;
    }

    /** {@code GET path}: the hub's JSON, or a problem (404 included). */
    public DataObject get(String path) {
        RuneClansHubClient.Answer a = hub.call("GET", path, null, Map.of());
        if (a != null && a.ok() && a.json() != null) return a.json();
        throw HubCommunityProblem.of(a);
    }

    /** {@code GET path}: the hub's JSON, empty when the hub says it isn't there (404), a problem otherwise. */
    public Optional<DataObject> find(String path) {
        RuneClansHubClient.Answer a = hub.call("GET", path, null, Map.of());
        if (a != null && a.status() == 404) return Optional.empty();
        if (a != null && a.ok() && a.json() != null) return Optional.of(a.json());
        throw HubCommunityProblem.of(a);
    }

    /** An edit or staff action, with {@code If-Match: rev} when {@code rev} isn't null. The hub's JSON (an empty object for a body-less 2xx). */
    public DataObject send(String method, String path, DataObject body, Long rev) {
        Map<String, String> headers = rev == null ? Map.of() : Map.of("If-Match", Long.toString(rev));
        RuneClansHubClient.Answer a = hub.call(method, path, body == null ? null : body.toString(), headers);
        if (a != null && a.ok()) return a.json() == null ? DataObject.empty() : a.json();
        throw HubCommunityProblem.of(a);
    }

    /**
     * A member's own write: sent now, or held for up to 5 minutes when the hub can't take it (then a {@code QUEUED} problem). {@code key}
     * names the member's answer to one item (their vote in a poll, their place on a sheet): a newer write with the same key drops an older
     * one still held, so the member's last click is the one that lands.
     */
    public DataObject memberWrite(String method, String path, DataObject body, String key) {
        HubWriteQueue.Result r = hub.write(method, path, body == null ? null : body.toString(), key);
        if (r == null) throw HubCommunityProblem.down();
        return switch (r.outcome()) {
            case SENT -> r.answer() == null || r.answer().json() == null ? DataObject.empty() : r.answer().json();
            case QUEUED -> throw HubCommunityProblem.queued();
            case REFUSED -> throw HubCommunityProblem.of(r.answer());
        };
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
