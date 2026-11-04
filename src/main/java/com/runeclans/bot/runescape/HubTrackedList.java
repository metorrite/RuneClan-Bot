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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tells the hub which names the bot wants polled though nobody linked them (T-851, plan D-3 and D-4): every {@code /rsn} account, and every
 * active member of every clan the bot's servers have set up, each with the server's id. Sent whole once an hour to {@code POST /v1/polling/tracked}
 * (idempotent; a name not sent for three days leaves the hub's tracked tier), in batches of {@value #BATCH}, on its own lane, whatever the
 * polling switch says, so the hub's list is complete before the handover. A name the hub already polls in a higher tier (a registered clan's
 * roster, a RuneLink link) stays there. Needs the key's {@code clans:write}: a 403 is logged once, not every hour.
 */
@BService
public class HubTrackedList {
    private static final Logger log = LoggerFactory.getLogger(HubTrackedList.class);
    static final String PATH = "/v1/polling/tracked";
    static final int BATCH = 1000;
    static final Duration EVERY = Duration.ofHours(1);
    static final Duration FIRST = Duration.ofMinutes(3);

    /** One name the bot tracks, with the server whose clan lists it (null for a {@code /rsn} account). */
    record Tracked(String rsn, Long guildId) {}

    private final Sender sender;
    private final boolean enabled;
    private volatile JDA jda;
    private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();

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
        private volatile boolean refused;

        Sender(Function<String, RuneClansHubClient.Answer> post, Supplier<List<Tracked>> names) {
            this.post = post;
            this.names = names;
        }

        /** Sends the whole list now. Returns how many names the hub took (0 when it refused or couldn't be reached). */
        int send() {
            List<Tracked> all = names.get();
            int accepted = 0;
            for (int i = 0; i < all.size(); i += BATCH) {
                List<Tracked> batch = all.subList(i, Math.min(all.size(), i + BATCH));
                RuneClansHubClient.Answer a = post.apply(body(batch));
                if (a == null) {
                    log.warn("The hub couldn't be reached for the tracked names; trying again in an hour.");
                    return accepted;
                }
                if (!a.ok()) {
                    if (!refused) log.warn("The hub refused the tracked names (status {}{}): the bot's key needs clans:write.", a.status(), a.json() == null ? "" : ", " + a.json().getString("error", ""));
                    refused = true;
                    return accepted;
                }
                if (refused) log.info("The hub takes the tracked names again.");
                refused = false;
                accepted += a.json() == null ? batch.size() : a.json().getInt("received", batch.size());
            }
            if (!all.isEmpty()) log.debug("Sent {} tracked names to the hub.", accepted);
            return accepted;
        }
    }

    /** The {@code /rsn} accounts and every clan server's active members, once each per (name, server). */
    static List<Tracked> gather(JDA jda, ClanMemberRepository members, ClanSyncService clans, PlayerLinkService links) {
        Map<String, Tracked> out = new LinkedHashMap<>();
        if (jda != null) {
            for (Guild guild : Shards.guilds(jda)) {
                if (clans.getClanName(guild.getIdLong()) == null) continue;
                for (ClanMemberRepository.ClanMemberRow m : members.getAll(guild.getIdLong(), true)) {
                    out.putIfAbsent(m.rsn().toLowerCase(Locale.ROOT) + "|" + guild.getIdLong(), new Tracked(m.rsn(), guild.getIdLong()));
                }
            }
        }
        for (String rsn : links.getAllAccountRsns()) out.putIfAbsent(rsn.toLowerCase(Locale.ROOT) + "|", new Tracked(rsn, null));
        return new ArrayList<>(out.values());
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
