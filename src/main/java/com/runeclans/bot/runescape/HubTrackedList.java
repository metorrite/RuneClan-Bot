package com.runeclans.bot.runescape;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Tells the hub which names the bot wants polled though nobody linked them (T-851, plan D-3 and D-4): every {@code /rsn} account, and every
 * active member of every clan the bot's servers have set up, each with the server's id. Sent whole once an hour to {@code POST /v1/polling/tracked}
 * (idempotent; a name not sent for three days leaves the hub's tracked tier), in batches of at most {@value #BATCH} names and
 * {@value #MAX_BODY_BYTES} bytes (the hub's request body cap is 16 KiB), on its own lane, whatever the polling switch says, so the hub's list is
 * complete before the handover. Names are spelt the hub's way first ({@link #clean}): a name the hub would refuse is left out, so one odd row in
 * a roster never costs the other names their place. A name the hub already polls in a higher tier (a registered clan's roster, a RuneLink link)
 * stays there. Needs the key's {@code clans:write}: a refusal is logged when it starts and when it changes, not every hour.
 */
@BService
public class HubTrackedList {
    private static final Logger log = LoggerFactory.getLogger(HubTrackedList.class);
    static final String PATH = "/v1/polling/tracked";
    /** Names per batch at most (the hub takes 5000 per call); the byte cap below is what usually ends a batch. */
    static final int BATCH = 1000;
    /** A batch's body stays under this, with room to spare under the hub's 16 KiB request body cap. */
    static final int MAX_BODY_BYTES = 14 * 1024;
    static final Duration EVERY = Duration.ofHours(1);
    static final Duration FIRST = Duration.ofMinutes(3);
    /** The hub's rule for a name once underscores are spaces: letters, digits, spaces and hyphens, 1 to 12 of them. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9 -]{1,12}");

    /** One name the bot tracks, with the server whose clan lists it (null for a {@code /rsn} account). */
    record Tracked(String rsn, Long guildId) {}

    private final Sender sender;
    private final boolean enabled;
    private volatile JDA jda;
    private final AtomicBoolean started = new AtomicBoolean();

    /** The bot's instance: posts hourly once the bot is connected, while the hub client is on. */
    public HubTrackedList(RuneClansHubClient hub, ClanMemberRepository members, ClanSyncService clans, PlayerLinkService links) {
        this.enabled = hub.enabled();
        this.sender = new Sender(body -> hub.call("POST", PATH, body), () -> gather(jda, members, clans, links));
    }

    @BEventListener
    public void onReady(ReadyEvent event) {
        jda = event.getJDA();
        if (!enabled || !started.compareAndSet(false, true)) return; // one schedule, however many shards or reconnects say ready
        BotScheduler.lane("hub-tracked").scheduleWithFixedDelay(() -> {
            try {
                sender.send();
            } catch (RuntimeException e) {
                log.warn("Sending the tracked names to the hub failed: {}", e.toString());
            }
        }, FIRST.toSeconds(), EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    /** The sending itself, apart from the service wiring so tests can drive it with a fake hub. */
    static final class Sender {
        private final Function<String, RuneClansHubClient.Answer> post;
        private final Supplier<List<Tracked>> names;
        /** The last trouble logged ({@code "status <n> <code>"} or {@code "unreachable"}), so the log says it once and again only when it changes. */
        private volatile String problem;

        Sender(Function<String, RuneClansHubClient.Answer> post, Supplier<List<Tracked>> names) {
            this.post = post;
            this.names = names;
        }

        /** Sends the whole list now. Returns how many names the hub took (0 when it refused or couldn't be reached). */
        int send() {
            List<Tracked> all = names.get();
            List<List<Tracked>> batches = batches(all);
            int accepted = 0;
            int sent = 0;
            for (List<Tracked> batch : batches) {
                RuneClansHubClient.Answer a = post.apply(body(batch));
                if (a == null) {
                    trouble("unreachable", "The hub couldn't be reached for the tracked names; trying again in an hour.");
                    return accepted;
                }
                if (!a.ok()) {
                    String code = a.json() == null ? "" : a.json().getString("error", "");
                    String key = "status " + a.status() + " " + code;
                    if (a.status() == 401 || a.status() == 403) {
                        trouble(key, "The hub refused the tracked names (status " + a.status() + ", " + code + "): the bot's key needs clans:write. Trying again in an hour.");
                        return accepted; // every batch would get the same answer
                    }
                    if (a.status() >= 500 || a.status() == 429) {
                        trouble(key, "The hub couldn't take the tracked names (status " + a.status() + ", " + code + "); trying again in an hour.");
                        return accepted;
                    }
                    // a 400-class answer is about this batch (a name or id the hub won't have, or a body too big): the other batches still go
                    trouble(key, "The hub refused one batch of " + batch.size() + " tracked names (status " + a.status() + ", " + code + "); the other batches are still sent.");
                    continue;
                }
                sent++;
                accepted += a.json() == null ? batch.size() : a.json().getInt("received", batch.size());
            }
            if (sent == batches.size() && problem != null) {
                log.info("The hub takes the tracked names again.");
                problem = null;
            }
            if (!all.isEmpty()) log.debug("Sent {} tracked names to the hub in {} batches.", accepted, sent);
            return accepted;
        }

        private void trouble(String key, String message) {
            if (!key.equals(problem)) log.warn(message);
            problem = key;
        }

        /** What is wrong with the sending right now, or null (tests). */
        String problem() {
            return problem;
        }
    }

    /** The {@code /rsn} accounts and every clan server's active members, spelt the hub's way, once each per (name, server). */
    static List<Tracked> gather(JDA jda, ClanMemberRepository members, ClanSyncService clans, PlayerLinkService links) {
        Map<String, Tracked> out = new LinkedHashMap<>();
        int skipped = 0;
        if (jda != null) {
            for (Guild guild : Shards.guilds(jda)) {
                if (clans.getClanName(guild.getIdLong()) == null) continue;
                for (ClanMemberRepository.ClanMemberRow m : members.getAll(guild.getIdLong(), true)) {
                    String name = clean(m.rsn());
                    if (name == null) {
                        skipped++;
                        continue;
                    }
                    out.putIfAbsent(name.toLowerCase(Locale.ROOT) + "|" + guild.getIdLong(), new Tracked(name, guild.getIdLong()));
                }
            }
        }
        for (String rsn : links.getAllAccountRsns()) {
            String name = clean(rsn);
            if (name == null) {
                skipped++;
                continue;
            }
            out.putIfAbsent(name.toLowerCase(Locale.ROOT) + "|", new Tracked(name, null));
        }
        if (skipped > 0) log.info("{} stored names aren't RuneScape names the hub accepts and were left out of the tracked list.", skipped);
        return new ArrayList<>(out.values());
    }

    /** The name as the hub spells it (underscores as spaces, one space between words), or null when the hub would refuse it. */
    static String clean(String rsn) {
        if (rsn == null) return null;
        String name = rsn.replace('_', ' ').strip().replaceAll("\\s+", " ");
        return NAME.matcher(name).matches() ? name : null;
    }

    /** The list cut into batches of at most {@link #BATCH} names whose body stays under {@link #MAX_BODY_BYTES}. */
    static List<List<Tracked>> batches(List<Tracked> all) {
        List<List<Tracked>> out = new ArrayList<>();
        List<Tracked> current = new ArrayList<>();
        int bytes = EMPTY_BODY_BYTES;
        for (Tracked t : all) {
            int size = bytesOf(t);
            if (!current.isEmpty() && (current.size() >= BATCH || bytes + size > MAX_BODY_BYTES)) {
                out.add(current);
                current = new ArrayList<>();
                bytes = EMPTY_BODY_BYTES;
            }
            current.add(t);
            bytes += size;
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    /** {@code {"characters":[]}} */
    private static final int EMPTY_BODY_BYTES = 17;

    /** The bytes one name adds to a body: {@code {"rsn":"<name>","guildId":"<id>"}} and its comma. */
    private static int bytesOf(Tracked t) {
        int n = 10 + t.rsn().getBytes(StandardCharsets.UTF_8).length + 1;
        if (t.guildId() != null) n += 13 + Long.toString(t.guildId()).length();
        return n;
    }

    static String body(List<Tracked> batch) {
        DataArray arr = DataArray.empty();
        for (Tracked t : batch) {
            DataObject ch = DataObject.empty().put("rsn", t.rsn());
            if (t.guildId() != null) ch.put("guildId", Long.toString(t.guildId()));
            arr.add(ch);
        }
        return DataObject.empty().put("characters", arr).toString();
    }
}
