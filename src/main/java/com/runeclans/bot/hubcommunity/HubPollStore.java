package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.commands.poll.PollOption;
import com.runeclans.bot.commands.poll.PollSession;
import com.runeclans.bot.poll.PollStore;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls on the RuneClans hub (ticket F3): {@code PollService}'s store while {@code hub-community} is on. Every poll, option and vote lives in
 * the hub ({@code /v1/clans/{guildId}/polls}, {@code /v1/polls/{id}}, {@code .../votes}, {@code .../results}, {@code .../close}); the bot keeps
 * only the poll's local id, channel and message ({@link HubCommunityMaps}). An option's number in the bot is its place in the poll (1, 2, ...),
 * the same number its button carries. A member's vote is one {@code POST .../votes} with all their picks (or a {@code DELETE} for none), sent
 * through the hub write queue so a click while the hub is busy is kept for up to 5 minutes.
 * <p>
 * One click reads the poll a few times (session, counts, the member's picks), so the hub's answer is kept for {@link #FRESH} and replaced by
 * every write's answer; a feed entry about the poll drops it ({@link #forget}).
 */
public final class HubPollStore implements PollStore {
    static final Duration FRESH = Duration.ofSeconds(2);

    private final HubCommunityClient hub;
    private final HubCommunityMaps maps;
    private final Clock clock;
    private record Snap(DataObject json, Instant at) {}
    private final Map<UUID, Snap> recent = new ConcurrentHashMap<>();
    /** A member's own picks, as their last vote here left them (key: poll and member), so the reply after a click needs no second read. */
    private record Mine(Set<Long> numbers, Instant at) {}
    private final Map<String, Mine> mine = new ConcurrentHashMap<>();

    public HubPollStore(HubCommunityClient hub, HubCommunityMaps maps, Clock clock) {
        this.hub = hub;
        this.maps = maps;
        this.clock = clock;
    }

    @Override
    public boolean hub() {
        return true;
    }

    /** Drops the kept answer for a hub poll (a feed entry said it changed). */
    public void forget(UUID hubId) {
        recent.remove(hubId);
        mine.keySet().removeIf(k -> k.startsWith(hubId + ":"));
    }

    // ---------- making one ----------

    @Override
    public Created create(long guildId, long channelId, String title, boolean anonymous, boolean multipleVotes, List<String> labels, long createdByUserId,
                          Instant closesAt) {
        DataArray options = DataArray.empty();
        labels.forEach(options::add);
        DataObject body = DataObject.empty().put("title", title).put("options", options).put("anonymous", anonymous).put("multipleVotes", multipleVotes)
                .put("byDiscordId", Long.toString(createdByUserId));
        if (closesAt != null) body.put("closesAt", closesAt.toString());
        DataObject json = hub.send("POST", "/v1/clans/" + guildId + "/polls", body, null);
        UUID hubId = UUID.fromString(json.getString("id"));
        long local = maps.pollLocalId(hubId, guildId);
        maps.setPollChannel(local, channelId);
        keep(hubId, json);
        return new Created(local, options(local, json));
    }

    @Override
    public long createPoll(long guildId, long channelId, String title, boolean anonymous, boolean multipleVotes, long createdByUserId) {
        throw new UnsupportedOperationException("a hub poll is made with its options in one go: create(...)");
    }

    @Override
    public long addOption(long pollId, int optionNumber, String label) {
        throw new UnsupportedOperationException("a hub poll is made with its options in one go: create(...)");
    }

    @Override
    public void saveMessageId(long pollId, long messageId) {
        maps.setPollMessage(pollId, messageId);
    }

    // ---------- reading ----------

    /** No start-up warm-up on the hub: polls load on first use. */
    @Override
    public List<PollSession> getAllActivePolls() {
        return List.of();
    }

    @Override
    public List<PollSession> getActivePolls(long guildId) {
        Optional<DataObject> answer = hub.find("/v1/clans/" + guildId + "/polls?status=OPEN");
        if (answer.isEmpty()) return List.of(); // the server has no clan on the hub
        Map<UUID, HubCommunityMaps.PollRow> posted = new HashMap<>();
        for (HubCommunityMaps.PollRow row : maps.pollsOf(guildId)) posted.put(row.hubId(), row);
        List<PollSession> out = new ArrayList<>();
        DataArray polls = answer.get().optArray("polls").orElse(DataArray.empty());
        for (int i = 0; i < polls.length(); i++) {
            DataObject p = polls.getObject(i);
            UUID hubId = UUID.fromString(p.getString("id"));
            HubCommunityMaps.PollRow row = posted.get(hubId);
            long local = row != null ? row.localId() : maps.pollLocalId(hubId, guildId);
            out.add(session(local, guildId, row, p));
        }
        return out;
    }

    @Override
    public PollSession getPollById(long pollId) {
        Optional<HubCommunityMaps.PollRow> row = maps.poll(pollId);
        if (row.isEmpty()) return null;
        Optional<DataObject> json = poll(row.get().hubId());
        return json.map(p -> session(pollId, row.get().guildId(), row.get(), p)).orElse(null);
    }

    @Override
    public List<PollOption> getOptions(long pollId) {
        return hubId(pollId).flatMap(this::poll).map(p -> options(pollId, p)).orElse(List.of());
    }

    @Override
    public Set<Long> getUserVotes(long pollId, long userId) {
        Optional<UUID> hubId = hubId(pollId);
        if (hubId.isEmpty()) return Set.of();
        Mine kept = mine.get(voteKey(hubId.get(), userId));
        if (kept != null && Duration.between(kept.at(), clock.instant()).compareTo(FRESH) < 0) return kept.numbers();
        // the member's own picks: the hub names them for a first-party app that says whose (playerId = a Discord id)
        DataObject p = hub.get("/v1/polls/" + hubId.get() + "?playerId=" + userId);
        keep(hubId.get(), p); // the whole poll came with it: the vote that follows needs no second read
        Map<String, Long> numbers = numbersById(p);
        Set<Long> out = new LinkedHashSet<>();
        DataArray mine = p.optArray("myVotes").orElse(DataArray.empty());
        for (int i = 0; i < mine.length(); i++) {
            Long n = numbers.get(mine.getString(i));
            if (n != null) out.add(n);
        }
        return out;
    }

    @Override
    public boolean hasVotedForOption(long pollId, long optionId, long userId) {
        return getUserVotes(pollId, userId).contains(optionId);
    }

    @Override
    public boolean hasVotedInPoll(long pollId, long userId) {
        return !getUserVotes(pollId, userId).isEmpty();
    }

    @Override
    public Map<Long, Integer> getVoteCounts(long pollId) {
        Optional<DataObject> p = hubId(pollId).flatMap(this::poll);
        Map<Long, Integer> out = new LinkedHashMap<>();
        if (p.isEmpty()) return out;
        DataArray options = p.get().optArray("options").orElse(DataArray.empty());
        for (int i = 0; i < options.length(); i++) {
            DataObject o = options.getObject(i);
            out.put((long) o.getInt("position"), o.getInt("votes", 0));
        }
        return out;
    }

    @Override
    public Map<Long, List<Long>> getVotersByOption(long pollId) {
        Optional<UUID> hubId = hubId(pollId);
        Map<Long, List<Long>> out = new LinkedHashMap<>();
        if (hubId.isEmpty()) return out;
        DataObject results = hub.get("/v1/polls/" + hubId.get() + "/results");
        DataArray options = results.optArray("options").orElse(DataArray.empty());
        for (int i = 0; i < options.length(); i++) {
            DataObject o = options.getObject(i);
            List<Long> voters = new ArrayList<>();
            DataArray list = o.optArray("voters").orElse(DataArray.empty());
            for (int j = 0; j < list.length(); j++) {
                String discord = list.getObject(j).getString("discordId", null);
                if (discord != null && discord.matches("[0-9]{1,20}")) voters.add(Long.parseLong(discord));
            }
            if (!voters.isEmpty()) out.put((long) o.getInt("position"), voters);
        }
        return out;
    }

    // ---------- voting ----------

    @Override
    public void setVotes(long pollId, long userId, Set<Long> optionIds) {
        UUID hubId = hubId(pollId).orElseThrow(() -> new HubCommunityProblem(HubCommunityProblem.Kind.REFUSED, 404, "not_found", "This poll is no longer active."));
        DataObject json;
        if (optionIds.isEmpty()) {
            json = hub.memberWrite("DELETE", "/v1/polls/" + hubId + "/votes?playerId=" + userId, null, voteKey(hubId, userId));
        } else {
            Map<Long, String> ids = idsByNumber(poll(hubId).orElseThrow(() -> new HubCommunityProblem(HubCommunityProblem.Kind.REFUSED, 404, "not_found", "This poll is no longer active.")));
            DataArray picks = DataArray.empty();
            for (long n : optionIds) {
                String id = ids.get(n);
                if (id == null) throw new HubCommunityProblem(HubCommunityProblem.Kind.REFUSED, 400, "bad_vote", "That option is no longer in this poll.");
                picks.add(id);
            }
            json = hub.memberWrite("POST", "/v1/polls/" + hubId + "/votes", DataObject.empty().put("optionIds", picks).put("discordUserId", Long.toString(userId)),
                    voteKey(hubId, userId));
        }
        if (json.hasKey("id")) keep(hubId, json);
        else recent.remove(hubId);
        mine.put(voteKey(hubId, userId), new Mine(Set.copyOf(optionIds), clock.instant()));
    }

    /** One member's answer to one poll: a newer vote replaces an older one still held in the write queue. */
    static String voteKey(UUID hubId, long userId) {
        return hubId + ":" + userId;
    }

    @Override
    public boolean addVote(long pollId, long optionId, long userId) {
        Set<Long> mine = new LinkedHashSet<>(getUserVotes(pollId, userId));
        if (!mine.add(optionId)) return false;
        setVotes(pollId, userId, mine);
        return true;
    }

    @Override
    public boolean removeVote(long pollId, long optionId, long userId) {
        Set<Long> mine = new LinkedHashSet<>(getUserVotes(pollId, userId));
        if (!mine.remove(optionId)) return false;
        setVotes(pollId, userId, mine);
        return true;
    }

    @Override
    public void removeAllVotesForUser(long pollId, long userId) {
        setVotes(pollId, userId, Set.of());
    }

    // ---------- closing ----------

    @Override
    public void closePoll(long pollId) {
        Optional<UUID> hubId = hubId(pollId);
        if (hubId.isEmpty()) return;
        DataObject json = hub.send("POST", "/v1/polls/" + hubId.get() + "/close", DataObject.empty(), null);
        if (json.hasKey("id")) keep(hubId.get(), json);
    }

    // ---------- helpers ----------

    private Optional<UUID> hubId(long pollId) {
        return maps.pollHubId(pollId);
    }

    /** The hub's poll, from the last couple of seconds when there is one. Empty when the hub says it is gone. */
    Optional<DataObject> poll(UUID hubId) {
        Snap snap = recent.get(hubId);
        Instant now = clock.instant();
        if (snap != null && Duration.between(snap.at(), now).compareTo(FRESH) < 0) return Optional.of(snap.json());
        Optional<DataObject> json = hub.find("/v1/polls/" + hubId);
        json.ifPresentOrElse(j -> keep(hubId, j), () -> recent.remove(hubId));
        return json;
    }

    private void keep(UUID hubId, DataObject json) {
        recent.put(hubId, new Snap(json, clock.instant()));
    }

    static PollSession session(long local, long guildId, HubCommunityMaps.PollRow row, DataObject p) {
        String status = "OPEN".equalsIgnoreCase(p.getString("status", "OPEN")) ? "ACTIVE" : "CLOSED";
        // a poll made by a due closesAt the hub hasn't swept yet still counts as open here; the hub refuses its votes with "closed"
        long channel = row == null || row.channelId() == null ? 0L : row.channelId();
        Long message = row == null ? null : row.messageId();
        return new PollSession(local, guildId, channel, message, p.getString("title", ""), p.getBoolean("anonymous", false), p.getBoolean("multipleVotes", false), status,
                discordId(p.getString("createdByDiscordId", null)));
    }

    static List<PollOption> options(long local, DataObject p) {
        List<PollOption> out = new ArrayList<>();
        DataArray options = p.optArray("options").orElse(DataArray.empty());
        for (int i = 0; i < options.length(); i++) {
            DataObject o = options.getObject(i);
            int n = o.getInt("position");
            out.add(new PollOption(n, local, n, o.getString("label", "")));
        }
        out.sort(Comparator.comparingInt(PollOption::optionNumber));
        return out;
    }

    private static Map<Long, String> idsByNumber(DataObject p) {
        Map<Long, String> out = new HashMap<>();
        DataArray options = p.optArray("options").orElse(DataArray.empty());
        for (int i = 0; i < options.length(); i++) out.put((long) options.getObject(i).getInt("position"), options.getObject(i).getString("id"));
        return out;
    }

    private static Map<String, Long> numbersById(DataObject p) {
        Map<String, Long> out = new HashMap<>();
        idsByNumber(p).forEach((n, id) -> out.put(id, n));
        return out;
    }

    static long discordId(String raw) {
        return raw != null && raw.matches("[0-9]{1,19}") ? Long.parseLong(raw) : 0L;
    }
}
