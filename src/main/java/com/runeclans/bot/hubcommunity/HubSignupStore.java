package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.commands.signup.SignupEntry;
import com.runeclans.bot.commands.signup.SignupMessage;
import com.runeclans.bot.commands.signup.SignupSession;
import com.runeclans.bot.commands.signup.SignupType;
import com.runeclans.bot.commands.signup.SubmissionField;
import com.runeclans.bot.signup.SignupStore;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Signups on the RuneClans hub (ticket F3): {@code SignupService}'s store while {@code hub-community} is on. The sheet, its places and their
 * order live in the hub ({@code /v1/clans/{guildId}/signups}, {@code /v1/signups/{id}}, {@code .../entries}, {@code .../next},
 * {@code .../clear}); the bot keeps the sheet's local id, its submission questions and group role, the messages that show it, and the name
 * a member signed up under when that isn't a RuneScape name ({@link HubCommunityMaps}).
 * <ul>
 *   <li>Paused in the bot is {@code CLOSED} on the hub, and the other way round.</li>
 *   <li>A sign-up the hub refuses because the member is already on it, the name is taken, it is full or closed answers false, as the bot's
 *       own table does; one the hub can't take right now is held in the hub write queue (the member is told it will sync).</li>
 *   <li>A queue's RSN goes to the hub; a group or submission sheet's display name stays in the bot (it is a Discord name, not an RSN).</li>
 *   <li>The hub keeps one answer label for a submission sheet; the bot keeps its full list of questions beside it.</li>
 *   <li>Upkeep (closing sheets untouched for weeks, purging deleted ones) is the hub's: {@link #getInactiveSignups} is empty here.</li>
 * </ul>
 */
public final class HubSignupStore implements SignupStore {
    static final Duration FRESH = Duration.ofSeconds(2);
    static final Pattern RSN = Pattern.compile("[A-Za-z0-9 _-]{1,12}");
    /** Refusals that mean "not added" the way the bot's own table answers false. */
    static final Set<String> NOT_ADDED = Set.of("already_in", "rsn_taken", "full", "closed");
    /** The hub's label length for a submission sheet's answer. */
    static final int MAX_LABEL = 80;

    private final HubCommunityClient hub;
    private final HubCommunityMaps maps;
    private final Clock clock;
    private record Snap(DataObject json, Instant at) {}
    private final Map<UUID, Snap> recent = new ConcurrentHashMap<>();

    public HubSignupStore(HubCommunityClient hub, HubCommunityMaps maps, Clock clock) {
        this.hub = hub;
        this.maps = maps;
        this.clock = clock;
    }

    @Override
    public boolean hub() {
        return true;
    }

    /** Drops the kept answer for a hub signup (a feed entry said it changed). */
    public void forget(UUID hubId) {
        recent.remove(hubId);
    }

    // ---------- sheets ----------

    @Override
    public long createSignup(long guildId, String title, SignupType type, String notificationMessage, String submissionFieldLabel, Integer maxSignups,
                             long createdByUserId, Long groupRoleId) {
        DataObject body = DataObject.empty().put("title", title).put("kind", type.name()).put("byDiscordId", Long.toString(createdByUserId));
        if (notificationMessage != null) body.put("notificationMessage", notificationMessage);
        if (maxSignups != null) body.put("maxSignups", maxSignups);
        String label = answerLabel(submissionFieldLabel);
        if (type == SignupType.SUBMISSION && label != null) body.put("submissionLabel", label);
        DataObject json = hub.send("POST", "/v1/clans/" + guildId + "/signups", body, null);
        UUID hubId = UUID.fromString(json.getString("id"));
        long local = maps.signupLocalId(hubId, guildId);
        maps.setSignupDetails(local, submissionFieldLabel, groupRoleId);
        keep(hubId, json);
        return local;
    }

    @Override
    public void updateGroupRoleId(long signupId, long roleId) {
        maps.setSignupRole(signupId, roleId);
    }

    /** No start-up warm-up on the hub: sheets load on first use. */
    @Override
    public List<SignupSession> getOpenSignups() {
        return List.of();
    }

    @Override
    public SignupSession getOpenSignupById(long signupId) {
        Optional<HubCommunityMaps.SignupRow> row = maps.signup(signupId);
        if (row.isEmpty()) return null;
        return sheet(row.get().hubId()).map(json -> session(row.get(), json)).orElse(null);
    }

    @Override
    public List<SignupSession> getInactiveSignups(Instant cutoff) {
        return List.of();
    }

    @Override
    public int purgeDeletedBefore(Instant cutoff) {
        return 0;
    }

    @Override
    public String getSignupStatus(long signupId) {
        return hubId(signupId).flatMap(this::sheet).map(json -> botStatus(json.getString("status", "ACTIVE"))).orElse(null);
    }

    @Override
    public void setSignupStatus(long signupId, String status) {
        UUID hubId = hubId(signupId).orElseThrow(HubSignupStore::gone);
        DataObject now = fresh(hubId).orElseThrow(HubSignupStore::gone);
        String want = "ACTIVE".equalsIgnoreCase(status) ? "ACTIVE" : "CLOSED";
        DataObject json = hub.send("PATCH", "/v1/signups/" + hubId, DataObject.empty().put("status", want), now.getLong("rev"));
        keepIfSheet(hubId, json);
    }

    @Override
    public void deleteSignupForAdminArchive(long signupId) {
        softDeleteSignup(signupId);
        maps.allSignupMessagesInactive(signupId);
    }

    @Override
    public void softDeleteSignup(long signupId) {
        Optional<UUID> hubId = hubId(signupId);
        if (hubId.isEmpty()) return;
        try {
            hub.send("DELETE", "/v1/signups/" + hubId.get(), null, null);
        } catch (HubCommunityProblem p) {
            if (p.status() != 404) throw p; // already gone: nothing to delete
        }
        recent.remove(hubId.get());
    }

    @Override
    public List<SignupSession> getVisibleSignups(long guildId) {
        Optional<DataObject> answer = hub.find("/v1/clans/" + guildId + "/signups");
        if (answer.isEmpty()) return List.of(); // the server has no clan on the hub
        List<SignupSession> out = new ArrayList<>();
        DataArray sheets = answer.get().optArray("signups").orElse(DataArray.empty());
        for (int i = 0; i < sheets.length(); i++) {
            DataObject json = sheets.getObject(i);
            UUID hubId = UUID.fromString(json.getString("id"));
            long local = maps.signupLocalId(hubId, guildId);
            HubCommunityMaps.SignupRow row = maps.signup(local).orElse(new HubCommunityMaps.SignupRow(local, hubId, guildId, null, null));
            out.add(session(row, json));
        }
        out.sort(java.util.Comparator.comparing(SignupSession::signupId));
        return out;
    }

    @Override
    public List<SignupSession> searchVisibleSignups(long guildId, String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return getVisibleSignups(guildId).stream().filter(s -> s.title().toLowerCase(Locale.ROOT).contains(q)).limit(25).toList();
    }

    // ---------- places ----------

    @Override
    public int getEntryCount(long signupId) {
        return hubId(signupId).flatMap(this::sheet).map(json -> json.optObject("seats").map(s -> s.getInt("taken", 0)).orElse(0)).orElse(0);
    }

    @Override
    public boolean addEntry(long signupId, long discordUserId, String rsn, String submissionValue, long addedByUserId, SignupType type) {
        UUID hubId = hubId(signupId).orElseThrow(HubSignupStore::gone);
        DataObject body = DataObject.empty().put("discordUserId", Long.toString(discordUserId)).put("byDiscordId", Long.toString(addedByUserId));
        String name = rsn == null ? null : rsn.strip();
        if (type == SignupType.QUEUE && name != null && RSN.matcher(name).matches()) body.put("rsn", name);
        if (submissionValue != null && !submissionValue.isBlank()) body.put("submissionValue", submissionValue);
        try {
            hub.memberWrite("POST", "/v1/signups/" + hubId + "/entries", body);
        } catch (HubCommunityProblem p) {
            if (p.kind() == HubCommunityProblem.Kind.REFUSED && p.status() == 409 && NOT_ADDED.contains(p.code())) return false;
            if (p.kind() == HubCommunityProblem.Kind.QUEUED && name != null && !name.isEmpty()) maps.saveSignupName(signupId, discordUserId, name);
            throw p;
        } finally {
            recent.remove(hubId);
        }
        if (name != null && !name.isEmpty()) maps.saveSignupName(signupId, discordUserId, name);
        return true;
    }

    @Override
    public boolean removeEntryByUserId(long signupId, long discordUserId) {
        UUID hubId = hubId(signupId).orElseThrow(HubSignupStore::gone);
        try {
            DataObject json = hub.memberWrite("DELETE", "/v1/signups/" + hubId + "/entries?playerId=" + discordUserId, null);
            return json.getInt("removed", 0) > 0;
        } finally {
            recent.remove(hubId);
        }
    }

    @Override
    public List<SignupEntry> getEntries(long signupId) {
        Optional<UUID> hubId = hubId(signupId);
        if (hubId.isEmpty()) return List.of();
        Optional<DataObject> json = sheet(hubId.get());
        if (json.isEmpty()) return List.of();
        Map<Long, String> names = maps.signupNames(signupId);
        List<SignupEntry> out = new ArrayList<>();
        DataArray entries = json.get().optArray("entries").orElse(DataArray.empty());
        for (int i = 0; i < entries.length(); i++) {
            DataObject e = entries.getObject(i);
            long user = HubPollStore.discordId(e.getString("discordId", null));
            String rsn = e.getString("rsn", null);
            String name = names.containsKey(user) ? names.get(user) : rsn != null ? rsn : "Member";
            out.add(new SignupEntry(user, name, e.getString("submissionValue", null)));
        }
        return out;
    }

    @Override
    public SignupEntry getFirstEntry(long signupId) {
        List<SignupEntry> entries = getEntries(signupId);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    /** Takes the first place off, as the bot's table does, and answers who is first now. */
    @Override
    public SignupEntry next(long signupId) {
        UUID hubId = hubId(signupId).orElseThrow(HubSignupStore::gone);
        hub.send("POST", "/v1/signups/" + hubId + "/next", DataObject.empty(), null);
        recent.remove(hubId);
        return getFirstEntry(signupId);
    }

    @Override
    public SignupEntry removeFirst(long signupId) {
        return next(signupId);
    }

    /** Swaps the first two places (the first waits one turn) and answers who is first now; null with fewer than two, as the bot's table does. */
    @Override
    public SignupEntry skipFirst(long signupId) {
        UUID hubId = hubId(signupId).orElseThrow(HubSignupStore::gone);
        if (getEntries(signupId).size() < 2) return null;
        hub.send("POST", "/v1/signups/" + hubId + "/next", DataObject.empty().put("skip", true), null);
        recent.remove(hubId);
        return getFirstEntry(signupId);
    }

    @Override
    public void clearEntries(long signupId) {
        UUID hubId = hubId(signupId).orElseThrow(HubSignupStore::gone);
        hub.send("POST", "/v1/signups/" + hubId + "/clear", DataObject.empty(), null);
        recent.remove(hubId);
    }

    // ---------- the messages showing a sheet (Discord's, kept by the bot) ----------

    @Override
    public void saveMessage(long signupId, long guildId, long channelId, long messageId, String messageType) {
        maps.saveSignupMessage(signupId, guildId, channelId, messageId, messageType);
    }

    @Override
    public List<SignupMessage> getActiveMessages(long signupId) {
        return maps.signupMessages(signupId, null).stream().map(HubSignupStore::message).toList();
    }

    @Override
    public List<SignupMessage> getActivePingMessages(long signupId) {
        return maps.signupMessages(signupId, "PING").stream().map(HubSignupStore::message).toList();
    }

    @Override
    public SignupMessage getFirstActivePublicMessage(long signupId) {
        return maps.signupMessages(signupId, "PUBLIC").stream().findFirst().map(HubSignupStore::message).orElse(null);
    }

    @Override
    public void markMessageInactive(long messageId) {
        maps.signupMessageInactive(messageId);
    }

    // ---------- helpers ----------

    private Optional<UUID> hubId(long signupId) {
        return maps.signupHubId(signupId);
    }

    /** The hub's sheet with its places, from the last couple of seconds when there is one. Empty when the hub says it is gone. */
    Optional<DataObject> sheet(UUID hubId) {
        Snap snap = recent.get(hubId);
        if (snap != null && Duration.between(snap.at(), clock.instant()).compareTo(FRESH) < 0) return Optional.of(snap.json());
        return fresh(hubId);
    }

    private Optional<DataObject> fresh(UUID hubId) {
        Optional<DataObject> json = hub.find("/v1/signups/" + hubId);
        json.ifPresentOrElse(j -> keep(hubId, j), () -> recent.remove(hubId));
        return json;
    }

    private void keep(UUID hubId, DataObject json) {
        recent.put(hubId, new Snap(json, clock.instant()));
    }

    /** A write's answer replaces the kept sheet only when it carries the places (a PATCH answers the sheet without them). */
    private void keepIfSheet(UUID hubId, DataObject json) {
        if (json.hasKey("entries")) keep(hubId, json);
        else recent.remove(hubId);
    }

    static SignupSession session(HubCommunityMaps.SignupRow row, DataObject json) {
        SignupType type;
        try {
            type = SignupType.valueOf(json.getString("kind", "QUEUE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = SignupType.QUEUE;
        }
        String fields = row.submissionFields();
        String label = json.getString("submissionLabel", null);
        if (type == SignupType.SUBMISSION && (fields == null || fields.isBlank())) {
            // a sheet made on the website: its one label is its one question
            fields = SubmissionField.serialize(List.of(new SubmissionField(label == null ? "Your answer" : label, SubmissionField.TYPE_TEXT, true)));
        }
        Integer max = json.isNull("maxSignups") || !json.hasKey("maxSignups") ? null : json.getInt("maxSignups");
        return new SignupSession(row.localId(), row.guildId(), json.getString("title", ""), json.getString("notificationMessage", null), max, type, fields,
                row.groupRoleId());
    }

    /** The bot's words for the hub's status: open is ACTIVE, closed is PAUSED. */
    static String botStatus(String hubStatus) {
        return "ACTIVE".equalsIgnoreCase(hubStatus) ? "ACTIVE" : "PAUSED";
    }

    /** The hub's one answer label for the bot's questions: the first question's label. */
    static String answerLabel(String serializedFields) {
        if (serializedFields == null || serializedFields.isBlank()) return null;
        List<SubmissionField> fields = SubmissionField.deserialize(serializedFields);
        if (fields.isEmpty()) return null;
        String label = fields.getFirst().label();
        return label.length() <= MAX_LABEL ? label : label.substring(0, MAX_LABEL);
    }

    private static SignupMessage message(HubCommunityMaps.SignupMessageRow r) {
        return new SignupMessage(r.messageId(), r.localId(), r.guildId(), r.channelId(), r.type());
    }

    private static HubCommunityProblem gone() {
        return new HubCommunityProblem(HubCommunityProblem.Kind.REFUSED, 404, "not_found", "That signup doesn't exist any more.");
    }
}
