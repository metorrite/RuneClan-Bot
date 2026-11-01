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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *       interaction or a website request. The hub keeps them as trust-only links and never lets one take a name another account holds,
 *       nor brings back one the player removed themself (a new link made in the bot, sent {@code fresh}, is their new choice).</li>
 *   <li>the catch-up, once after the switch comes on (or the bot starts) and then daily, makes the hub match the bot: it reads the hub's
 *       bot links ({@code GET /v1/link/bot}), adds every account the hub lacks and ends every bot link the bot no longer has. That copies
 *       the existing links the first time and heals any change lost to a restart or made while the switch was off.</li>
 * </ul>
 * Off, nothing is written: changes are still held (so an unlink made meanwhile goes out when the switch comes on), and the catch-up runs
 * once as a dry run (the hub counts what it would do and writes nothing) whose counts are logged, so the copy can be checked before the
 * switch is turned on. A server admin removing a name from one server changes nothing in the hub: the account stays its owner's.
 * <p>
 * Each change is held, newest per name and person, until the hub takes it. One the hub can't take now (unreachable, 401/403/404 while
 * the key or route isn't there yet, 429, 5xx) is tried again every {@value #RETRY_SECONDS} seconds; one it refuses as malformed (another
 * 4xx) is dropped. A catch-up the hub can't take now is tried again after {@link #CATCH_UP_RETRY}; a batch it refuses is skipped and logged.
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
    /** How long a catch-up (or the dry run) that couldn't reach the hub waits before trying again. */
    static final Duration CATCH_UP_RETRY = Duration.ofMinutes(10);
    /** The most items the hub takes in one call ({@code BotLinks.MAX_ITEMS}). */
    static final int BATCH = 100;
    static final int MAX_HELD = 10_000;

    /** {@code method path body} to the hub: its answer, or null when it couldn't be reached. */
    public interface Sender {
        RuneClansHubClient.Answer call(String method, String path, String body);
    }

    private record Change(String op, long discordId, String rsn, String linkedAt, boolean fresh) {}

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
    /** No catch-up (or dry run) is tried before this, after one couldn't reach the hub. */
    private Instant notBefore = Instant.MIN;
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
        HubLinkSync sync = new HubLinkSync(hub::call, panel.toggle(SWITCH_ID, railway), repository::getAllAccounts, lane, Clock.systemUTC());
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

    /** The player (or staff for them) just linked {@code rsn} in the bot: a new choice, so it lifts an earlier unlink of theirs. */
    public void linked(long discordId, String rsn) {
        hold(new Change("add", discordId, rsn, null, true));
    }

    /** A link renamed to {@code rsn} in the bot (the same choice under a new name). */
    public void renamedTo(long discordId, String rsn) {
        hold(new Change("add", discordId, rsn, null, false));
    }

    /** This person's link to {@code rsn} came off the bot (unlinked, renamed away from, or moved to someone else). */
    public void unlinked(long discordId, String rsn) {
        hold(new Change("end", discordId, rsn, null, false));
    }

    private void hold(Change change) {
        if (sender == null || change.rsn() == null) return;
        String key = change.discordId() + "|" + nameKey(change.rsn());
        synchronized (this) {
            if (!held.containsKey(key) && held.size() >= MAX_HELD) {
                log.warn("Too many link changes wait for the hub ({}): one more was dropped; the next catch-up puts it right.", MAX_HELD);
                return;
            }
            held.remove(key); // the newest goes to the back
            held.put(key, change);
        }
        if (!on()) return; // held for when the switch comes on
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
            Instant now = clock.instant();
            if (!on()) {
                synchronized (this) {
                    caughtUpAt = null; // so turning the switch on catches up straight away
                }
                if (!dryRunDone && !now.isBefore(notBefore)) {
                    if (catchUp(true) != null) dryRunDone = true;
                    else notBefore = now.plus(CATCH_UP_RETRY);
                }
                return;
            }
            flush(); // first, so a held unlink reaches the hub before the catch-up compares
            Instant last;
            synchronized (this) {
                last = caughtUpAt;
            }
            if ((last == null || Duration.between(last, now).compareTo(CATCH_UP_EVERY) >= 0) && !now.isBefore(notBefore)) {
                if (catchUp(false) != null) {
                    synchronized (this) {
                        caughtUpAt = now;
                    }
                } else {
                    notBefore = now.plus(CATCH_UP_RETRY);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Link sync round failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Makes the hub match the bot ({@code dryRun}: the hub writes nothing and the ends are only counted). Reads the hub's bot links first and
     * the bot's accounts after, so an account linked in between is never taken for one the bot dropped. Returns the hub's counts added up
     * (plus {@code toEnd}, the bot links the bot no longer has), or null when the hub couldn't be reached (tried again later). A batch the
     * hub refuses is skipped and logged. Logs counts only, never ids or names.
     */
    DataObject catchUp(boolean dryRun) {
        RuneClansHubClient.Answer listed = send("GET", null);
        if (!usable(listed) || !listed.json().hasKey("links")) {
            log.warn("Link catch-up{} couldn't read the hub's bot links ({}); trying again later.", dryRun ? " (dry run)" : "", describe(listed));
            return null;
        }
        Set<String> inHub = new HashSet<>();
        Map<String, Change> hubOnly = new LinkedHashMap<>();
        DataArray links = listed.json().getArray("links");
        for (int i = 0; i < links.length(); i++) {
            DataObject l = links.getObject(i);
            long id;
            try {
                id = Long.parseLong(l.getString("discordId", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            String key = id + "|" + nameKey(l.getString("rsn", ""));
            inHub.add(key);
            hubOnly.put(key, new Change("end", id, l.getString("rsn", ""), null, false));
        }
        List<Change> adds = new ArrayList<>();
        for (PlayerAccount a : accounts.get()) {
            String key = a.discordUserId() + "|" + nameKey(a.rsn());
            hubOnly.remove(key);
            if (!inHub.contains(key)) adds.add(new Change("add", a.discordUserId(), a.rsn(), a.verifiedAt() == null ? null : a.verifiedAt().toString(), false));
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        if (!sendAll(adds, dryRun, totals)) return null;
        totals.put("toEnd", hubOnly.size());
        if (!dryRun && !sendAll(new ArrayList<>(hubOnly.values()), false, totals)) return null;
        DataObject out = DataObject.empty();
        totals.forEach(out::put);
        log.info("Link catch-up{}: {} already in the hub, {} sent to add, {} bot links the bot no longer has; hub counts {}",
                dryRun ? " (dry run, nothing written)" : "", inHub.size(), adds.size(), hubOnly.size(), out);
        return out;
    }

    /** Sends {@code changes} in batches, adding up the hub's counts; false when the hub couldn't be reached (a refused batch is skipped). */
    private boolean sendAll(List<Change> changes, boolean dryRun, Map<String, Integer> totals) {
        for (int from = 0; from < changes.size(); from += BATCH) {
            RuneClansHubClient.Answer answer = post(changes.subList(from, Math.min(changes.size(), from + BATCH)), dryRun);
            if (retryable(answer)) {
                log.warn("Link catch-up{} couldn't reach the hub ({}); trying again later.", dryRun ? " (dry run)" : "", describe(answer));
                return false;
            }
            if (!usable(answer) || !answer.json().hasKey("counts")) {
                log.warn("The hub refused a link catch-up batch ({}); it is skipped.", describe(answer));
                totals.merge("refused", Math.min(BATCH, changes.size() - from), Integer::sum);
                continue;
            }
            DataObject counts = answer.json().getObject("counts");
            for (String k : counts.keys()) totals.merge(k, counts.getInt(k), Integer::sum);
        }
        return true;
    }

    /** Sends what is held, {@value #BATCH} at a time; keeps what the hub couldn't take now. */
    void flush() {
        if (!on()) return;
        synchronized (flushing) {
            while (true) {
                List<Map.Entry<String, Change>> round;
                synchronized (this) {
                    round = new ArrayList<>(new ArrayList<>(held.entrySet()).subList(0, Math.min(held.size(), BATCH)));
                }
                if (round.isEmpty()) return;
                RuneClansHubClient.Answer a = post(round.stream().map(Map.Entry::getValue).toList(), false);
                if (retryable(a)) return; // kept for the next round
                synchronized (this) {
                    for (Map.Entry<String, Change> e : round) held.remove(e.getKey(), e.getValue()); // a newer change stays for next round
                }
                if (!a.ok()) log.warn("The hub refused {} link change(s) ({}); they are dropped.", round.size(), a.status());
            }
        }
    }

    /**
     * Worth trying again later: no answer, 401/403/404 (the key or the route isn't there yet: a release in progress), 408, 429 or 5xx. Any
     * other answer is final.
     */
    static boolean retryable(RuneClansHubClient.Answer a) {
        if (a == null) return true;
        int s = a.status();
        return s == 401 || s == 403 || s == 404 || s == 408 || s == 429 || s >= 500;
    }

    private static boolean usable(RuneClansHubClient.Answer a) {
        return a != null && a.ok() && a.json() != null;
    }

    private static String describe(RuneClansHubClient.Answer a) {
        return a == null ? "no answer" : String.valueOf(a.status());
    }

    /** How the hub matches names: lower case, with space, _ and - alike. */
    static String nameKey(String rsn) {
        return rsn.strip().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
    }

    private RuneClansHubClient.Answer post(List<Change> changes, boolean dryRun) {
        DataArray items = DataArray.empty();
        for (Change c : changes) {
            DataObject item = DataObject.empty().put("op", c.op()).put("discordId", Long.toString(c.discordId())).put("rsn", c.rsn());
            if (c.linkedAt() != null) item.put("linkedAt", c.linkedAt());
            if (c.fresh()) item.put("fresh", true);
            items.add(item);
        }
        return send("POST", DataObject.empty().put("dryRun", dryRun).put("items", items).toString());
    }

    private RuneClansHubClient.Answer send(String method, String body) {
        try {
            return sender.call(method, PATH, body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** How many changes wait to be sent. */
    public synchronized int held() {
        return held.size();
    }
}
