package com.runeclans.bot.runescape;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.runeclans.PanelSwitches;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.PlayerLinkRepository.PlayerAccount;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The bot's RSN links in the RuneClans hub (T-839), a stopgap until RuneLink is on the plugin market: so a player the bot linked (the
 * makeover-mage check, or staff adding the name) counts as linked on the website. With the switch on (RuneClan Admin > Switches "Bot links
 * in the API", or {@code RUNECLANS_HUB_LINK_SYNC}, which wins while set) and the hub client on:
 * <ul>
 *   <li>every link the bot makes, renames or unlinks is sent to the hub's {@code POST /v1/link/bot}, on its own lane, never in a Discord
 *       interaction or a website request. The hub keeps them as trust-only links and never lets one take a name another account holds.</li>
 *   <li>the catch-up sends every account the bot has as an {@code add} once after the switch comes on and then daily. Adding is a no-op for
 *       a link the hub already has, so this is what copies the existing links the first time and heals a send lost to a restart.</li>
 * </ul>
 * Off, nothing is written: the bot runs the catch-up once as a dry run (the hub counts what it would do and writes nothing) and logs the
 * counts, so the copy can be checked before the switch is turned on. A server admin removing a name from one server changes nothing in
 * the hub: the account stays its owner's.
 * <p>
 * Each change is held, newest per name and person, until the hub takes it; one the hub can't take now is tried again every
 * {@value #RETRY_SECONDS} seconds, one it refuses is dropped. What is still held when the bot stops is lost; the next catch-up re-adds,
 * but an unlink lost that way stays in the hub until the player unlinks there.
 */
@BConfiguration
public class HubLinkSync {
    private static final Logger log = LoggerFactory.getLogger(HubLinkSync.class);
    public static final String SETTING_ENV = "RUNECLANS_HUB_LINK_SYNC";
    /** The switch on RuneClan Admin > Switches (runeclans-hub {@code SwitchCatalogue.BOT_LINK_SYNC}). */
    public static final String SWITCH_ID = "bot-link-sync";
    static final String PATH = "/v1/link/bot";
    static final long RETRY_SECONDS = 30;
    static final Duration CATCH_UP_EVERY = Duration.ofHours(24);
    /** The most items the hub takes in one call ({@code BotLinks.MAX_ITEMS}). */
    static final int BATCH = 100;
    static final int MAX_HELD = 10_000;

    /** {@code POST path body} to the hub: its answer, or null when it couldn't be reached. */
    public interface Sender {
        RuneClansHubClient.Answer post(String path, String body);
    }

    private record Change(String op, long discordId, String rsn, String linkedAt) {}

    /** Null when the hub client is off. */
    private final Sender sender;
    private final BooleanSupplier switchOn;
    private final Supplier<List<PlayerAccount>> accounts;
    private final Executor lane;
    private final Clock clock;
    /** Person and name (lower case) to the newest change not yet taken by the hub, oldest first. */
    private final Map<String, Change> held = new LinkedHashMap<>();
    private final Object flushing = new Object();
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    /** When the last catch-up with the switch on went through, or null since the switch came on (or the bot started). */
    private Instant caughtUpAt;
    private boolean dryRunDone;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static HubLinkSync hubLinkSync(BotConfig config, RuneClansHubClient hub, PanelSwitches panel, PlayerLinkRepository repository) {
        String railway = config.getRuneClansSetting(SETTING_ENV);
        if (Boolean.TRUE.equals(PanelSwitches.hold(railway)) && !hub.enabled()) {
            log.warn("{} is on but the hub client is off (RUNECLANS_HUB_ENABLED): the bot's links stay in the bot only.", SETTING_ENV);
        }
        if (!hub.enabled()) return off();
        ScheduledExecutorService lane = BotScheduler.lane("hub-link-sync");
        HubLinkSync sync = new HubLinkSync((path, body) -> hub.call("POST", path, body), panel.toggle(SWITCH_ID, railway), repository::getAllAccounts,
                lane, Clock.systemUTC());
        lane.scheduleWithFixedDelay(sync::round, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS);
        return sync;
    }

    HubLinkSync(Sender sender, BooleanSupplier switchOn, Supplier<List<PlayerAccount>> accounts, Executor lane, Clock clock) {
        this.sender = sender;
        this.switchOn = switchOn;
        this.accounts = accounts;
        this.lane = lane;
        this.clock = clock;
    }

    /** Sends nothing: the hub client off, and tests elsewhere. */
    public static HubLinkSync off() {
        return new HubLinkSync(null, () -> false, List::of, Runnable::run, Clock.systemUTC());
    }

    public boolean on() {
        return sender != null && switchOn.getAsBoolean();
    }

    /** The bot linked {@code rsn} to this person (a new link, or one staff moved to them). */
    public void linked(long discordId, String rsn) {
        hold(new Change("add", discordId, rsn, null));
    }

    /** This person's link to {@code rsn} came off the bot (unlinked, renamed away from, or moved to someone else). */
    public void unlinked(long discordId, String rsn) {
        hold(new Change("end", discordId, rsn, null));
    }

    private void hold(Change change) {
        if (sender == null || change.rsn() == null) return;
        if (!on()) return; // off: the catch-up after the switch comes on adds what is linked then
        String key = change.discordId() + "|" + change.rsn().toLowerCase(Locale.ROOT);
        synchronized (this) {
            if (!held.containsKey(key) && held.size() >= MAX_HELD) {
                log.warn("Too many link changes wait for the hub ({}): one more was dropped.", MAX_HELD);
                return;
            }
            held.remove(key); // the newest goes to the back
            held.put(key, change);
        }
        if (!flushQueued.compareAndSet(false, true)) return;
        try {
            lane.execute(() -> {
                flushQueued.set(false);
                flush();
            });
        } catch (RuntimeException e) {
            flushQueued.set(false);
            log.warn("Link changes couldn't be queued for the hub: {}", e.getClass().getSimpleName());
        }
    }

    /** One lane round: the dry run while off, else a due catch-up and then what is held. */
    void round() {
        if (sender == null) return;
        try {
            if (!on()) {
                synchronized (this) {
                    caughtUpAt = null; // so turning the switch on catches up straight away
                }
                if (!dryRunDone) dryRunDone = catchUp(true) != null;
                return;
            }
            Instant last;
            synchronized (this) {
                last = caughtUpAt;
            }
            Instant now = clock.instant();
            if (last == null || Duration.between(last, now).compareTo(CATCH_UP_EVERY) >= 0) {
                if (catchUp(false) != null) {
                    synchronized (this) {
                        caughtUpAt = now;
                    }
                }
            }
            flush();
        } catch (RuntimeException e) {
            log.warn("Link sync round failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Sends every account the bot has as an {@code add} in batches of {@value #BATCH} ({@code dryRun}: the hub writes nothing). Returns the
     * hub's counts added up, or null when a batch couldn't go through (tried again next round). Logs counts only, never ids or names.
     */
    DataObject catchUp(boolean dryRun) {
        List<PlayerAccount> all = accounts.get();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (int from = 0; from < all.size(); from += BATCH) {
            List<Change> batch = new ArrayList<>();
            for (PlayerAccount a : all.subList(from, Math.min(all.size(), from + BATCH))) {
                batch.add(new Change("add", a.discordUserId(), a.rsn(), a.verifiedAt() == null ? null : a.verifiedAt().toString()));
            }
            RuneClansHubClient.Answer answer = call(batch, dryRun);
            if (answer == null || !answer.ok() || answer.json() == null || !answer.json().hasKey("counts")) {
                log.warn("Link catch-up{} couldn't reach the hub ({}); trying again later.", dryRun ? " (dry run)" : "", answer == null ? "no answer" : answer.status());
                return null;
            }
            DataObject counts = answer.json().getObject("counts");
            for (String k : counts.keys()) totals.merge(k, counts.getInt(k), Integer::sum);
        }
        DataObject out = DataObject.empty();
        totals.forEach(out::put);
        log.info("Link catch-up{}: {} accounts sent, hub counts {}", dryRun ? " (dry run, nothing written)" : "", all.size(), out);
        return out;
    }

    /** Sends what is held, {@value #BATCH} at a time; keeps what the hub couldn't take now. */
    void flush() {
        if (!on()) return;
        synchronized (flushing) {
            while (true) {
                List<Map.Entry<String, Change>> round;
                synchronized (this) {
                    round = new ArrayList<>(held.entrySet()).subList(0, Math.min(held.size(), BATCH));
                    round = new ArrayList<>(round);
                }
                if (round.isEmpty()) return;
                RuneClansHubClient.Answer a = call(round.stream().map(Map.Entry::getValue).toList(), false);
                if (a == null || a.status() == 429 || a.status() >= 500) return; // kept for the next round
                synchronized (this) {
                    for (Map.Entry<String, Change> e : round) held.remove(e.getKey(), e.getValue()); // a newer change stays for next round
                }
                if (!a.ok()) log.warn("The hub refused {} link change(s) ({}).", round.size(), a.status());
            }
        }
    }

    private RuneClansHubClient.Answer call(List<Change> changes, boolean dryRun) {
        DataArray items = DataArray.empty();
        for (Change c : changes) {
            DataObject item = DataObject.empty().put("op", c.op()).put("discordId", Long.toString(c.discordId())).put("rsn", c.rsn());
            if (c.linkedAt() != null) item.put("linkedAt", c.linkedAt());
            items.add(item);
        }
        try {
            return sender.post(PATH, DataObject.empty().put("dryRun", dryRun).put("items", items).toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** How many changes wait to be sent. */
    public synchronized int held() {
        return held.size();
    }
}
