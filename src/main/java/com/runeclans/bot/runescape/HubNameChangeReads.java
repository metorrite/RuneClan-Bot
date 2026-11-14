package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.TtlCache;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The hub reads behind the bot's name changes (T-1160): a clan's hub id by name, and a clan's name changes by status. Kept apart from
 * {@link HubNameChanges} so the roster sync can ask it too (which names wait for an admin) without a loop between the two services.
 * <p>
 * A read the hub didn't answer throws {@link Unanswered}; only a clear "no such clan" is an empty answer. Callers never take silence for "no".
 */
@BService
public class HubNameChangeReads {
    private final RuneClansHubClient hub;
    private final TtlCache<String, String> ids = new TtlCache<>(Duration.ofMinutes(10));

    public HubNameChangeReads(RuneClansHubClient hub) {
        this.hub = hub;
    }

    /** The hub didn't answer (off, unreachable, busy or an error): the caller must not decide anything from it. */
    public static final class Unanswered extends RuntimeException {
        public Unanswered(String message) {
            super(message);
        }
    }

    /** The hub's id of this clan; empty when the hub clearly doesn't know it (404). Found ids are kept ten minutes. */
    public Optional<String> clanId(String clanName) {
        if (clanName == null || clanName.isBlank()) return Optional.empty();
        String key = ClanSyncService.clanKey(clanName);
        Optional<String> cached = ids.get(key);
        if (cached.isPresent()) return cached;
        RuneClansHubClient.Answer a = hub.call("GET", "/v1/clans/by-name/" + URLEncoder.encode(clanName.strip(), StandardCharsets.UTF_8).replace("+", "%20"), null);
        if (a != null && a.status() == 404) return Optional.empty();
        if (a == null || !a.ok() || a.json() == null) throw new Unanswered("the hub didn't answer the clan lookup (" + (a == null ? "no answer" : "HTTP " + a.status()) + ")");
        String id = a.json().getString("clanId", "");
        if (id.isBlank()) return Optional.empty();
        ids.put(key, id);
        return Optional.of(id);
    }

    /** The clan's name changes with this status ({@code open}, {@code linked}, {@code undone}), newest first, as the hub's JSON. */
    public List<DataObject> changes(String clanId, String status) {
        RuneClansHubClient.Answer a = hub.call("GET", "/v1/clans/" + clanId + "/name-changes?status=" + status + "&limit=100", null);
        if (a == null || !a.ok() || a.json() == null) throw new Unanswered("the hub didn't answer the name changes (" + (a == null ? "no answer" : "HTTP " + a.status()) + ")");
        DataArray rows = a.json().optArray("changes").orElse(DataArray.empty());
        List<DataObject> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) out.add(rows.getObject(i));
        return out;
    }

    /**
     * The old names (lower case) of the clan's changes waiting for an admin: a linked player with one of these names has changed name, not
     * left, so the roster sync leaves their roles alone. Empty when the clan isn't on the hub. Throws {@link Unanswered} when the hub
     * didn't answer.
     */
    public Set<String> waitingOldNames(String clanName) {
        Optional<String> clan = clanId(clanName);
        if (clan.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        for (DataObject c : changes(clan.get(), "open")) out.add(c.getString("from", "").toLowerCase(Locale.ROOT));
        return out;
    }
}
