package com.runeclans.bot.member;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.member.MemberProfileRepository.PrivacyDefaults;
import com.runeclans.bot.runeclans.PanelSwitches;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Member privacy in the RuneClans hub (T-750, runeclans-hub docs/MEMBER-PRIVACY.md). With the switch on (RuneClan Admin > Switches "Member
 * privacy in the API", or {@code RUNECLANS_HUB_MEMBER_PRIVACY=on}, which wins while set; T-817) and the hub client on, every member privacy change the bot saves (a member's own toggles, Site Admin > Member privacy) is also sent to the hub, so the
 * hub's XP rankings and member pages hide the same people. The bot is the one place these are edited (the website saves them through the
 * bot), so its tables stay the ones it reads and the hub gets each change after it. What the hub had before the switch came on is moved
 * with the hub's move tool ({@code member-privacy}). Off, nothing is sent.
 * <p>
 * Sends run on their own lane, never in the website's request. Each hub row (one path) keeps only its newest body until the hub takes it,
 * so a save held while the hub is down can never land after a newer one: a held "show" is replaced by the "hide" that follows it. A send
 * the hub can't take now is tried again every {@value #RETRY_SECONDS} seconds; one it refuses is dropped. What is still held when the bot
 * stops is lost: the hub catches up on the member's next save or the next {@code MoveTool copy member-privacy}.
 * <p>
 * Each bot process sends what it saved on its own lane, so the newest-wins rule holds within one process. Two processes saving the same
 * row at nearly the same moment can reach the hub in either order; the member's next save puts it right.
 */
@BConfiguration
public class HubMemberPrivacy {
    private static final Logger log = LoggerFactory.getLogger(HubMemberPrivacy.class);
    public static final String SETTING_ENV = "RUNECLANS_HUB_MEMBER_PRIVACY";
    /** The switch on RuneClan Admin > Switches (runeclans-hub {@code SwitchCatalogue.MEMBER_PRIVACY}, T-817). */
    public static final String SWITCH_ID = "member-privacy";
    static final long RETRY_SECONDS = 30;
    /** Hub rows held at most; past that a new one is dropped (and logged), never an older one replaced by surprise. */
    static final int MAX_HELD = 10_000;

    /** {@code PUT path body} to the hub: its answer, or null when it couldn't be reached. */
    public interface Sender {
        RuneClansHubClient.Answer put(String path, String body);
    }

    /** Null when the hub client is off. */
    private final Sender sender;
    /** The switch, read on every save: the panel's {@code member-privacy} unless {@code RUNECLANS_HUB_MEMBER_PRIVACY} holds it. */
    private final BooleanSupplier switchOn;
    private final Executor lane;
    /** Hub path to the newest body not yet taken by the hub, oldest first. */
    private final Map<String, String> held = new LinkedHashMap<>();
    private final Object flushing = new Object();
    /** A flush is waiting on the lane: a burst of saves queues one round, not one each. */
    private final AtomicBoolean flushQueued = new AtomicBoolean();

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static HubMemberPrivacy hubMemberPrivacy(BotConfig config, RuneClansHubClient hub, PanelSwitches panel) {
        String railway = config.getRuneClansSetting(SETTING_ENV);
        if (on(railway) && !hub.enabled()) log.warn("{} is on but the hub client is off (RUNECLANS_HUB_ENABLED): member privacy stays in the bot only.", SETTING_ENV);
        if (!hub.enabled()) return off();
        // the lane runs whatever the switch says, so the panel can turn it on while the bot runs (T-817); off, nothing is sent
        ScheduledExecutorService lane = BotScheduler.lane("hub-member-privacy");
        HubMemberPrivacy privacy = forHub(hub, lane, panel.toggle(SWITCH_ID, railway));
        lane.scheduleWithFixedDelay(privacy::flush, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS);
        log.info("Member privacy changes go to the RuneClans hub while its switch is on ({} on Railway, else the Switches panel).", SETTING_ENV);
        return privacy;
    }

    /** Sends through {@code hub}'s first-party key on {@code lane} while {@code switchOn} says so. */
    static HubMemberPrivacy forHub(RuneClansHubClient hub, Executor lane, BooleanSupplier switchOn) {
        return new HubMemberPrivacy((path, body) -> hub.call("PUT", path, body), lane, switchOn);
    }

    HubMemberPrivacy(Sender sender, Executor lane, BooleanSupplier switchOn) {
        this.sender = sender;
        this.lane = lane;
        this.switchOn = switchOn;
    }

    /** With the switch on, sending through {@code sender} on {@code lane} (for tests elsewhere). */
    public static HubMemberPrivacy sendingTo(Sender sender, Executor lane) {
        return new HubMemberPrivacy(sender, lane, () -> true);
    }

    /** Sends nothing: the switch off. */
    public static HubMemberPrivacy off() {
        return new HubMemberPrivacy(null, Runnable::run, () -> false);
    }

    /** A Railway value holding the switch on ({@link PanelSwitches#hold}). */
    static boolean on(String raw) {
        return Boolean.TRUE.equals(PanelSwitches.hold(raw));
    }

    public boolean on() {
        return sender != null && switchOn.getAsBoolean();
    }

    /**
     * A member saved their own toggles on a server (so they chose them): all three are sent, as the bot now has them, and the hub marks the
     * member chosen.
     */
    public void memberSaved(long guildId, long userId, boolean hideFromLeaderboards, boolean hideAdventureLog, boolean hideDiscordLink) {
        send(memberPath(guildId, userId), toggles(hideFromLeaderboards, hideAdventureLog, hideDiscordLink));
    }

    /** A site admin saved the server's defaults. */
    public void defaultsSaved(long guildId, PrivacyDefaults d) {
        send(defaultsPath(guildId), toggles(d.hideFromLeaderboards(), d.hideAdventureLog(), d.hideDiscordLink()));
    }

    static String memberPath(long guildId, long userId) {
        return "/v1/guilds/" + guildId + "/member-privacy/members/" + userId;
    }

    static String defaultsPath(long guildId) {
        return "/v1/guilds/" + guildId + "/member-privacy/defaults";
    }

    static String toggles(boolean hideFromLeaderboards, boolean hideAdventureLog, boolean hideDiscordLink) {
        return DataObject.empty().put("hideFromLeaderboards", hideFromLeaderboards).put("hideAdventureLog", hideAdventureLog).put("hideDiscordLink", hideDiscordLink)
                .toString();
    }

    /** How many hub rows wait to be sent. */
    public synchronized int held() {
        return held.size();
    }

    private void send(String path, String body) {
        if (!on()) {
            // off: nothing is sent, and an older save held for this row is dropped, so turning the switch on later never sends a stale one
            synchronized (this) {
                held.remove(path);
            }
            return;
        }
        synchronized (this) {
            if (!held.containsKey(path) && held.size() >= MAX_HELD) {
                log.warn("Too many member privacy changes wait for the hub ({}): one more was dropped.", MAX_HELD);
                return;
            }
            held.remove(path); // the newest goes to the back, behind the ones held longer
            held.put(path, body);
        }
        if (!flushQueued.compareAndSet(false, true)) return; // the round already waiting sends this too
        try {
            lane.execute(this::queuedFlush);
        } catch (RuntimeException e) {
            flushQueued.set(false);
            log.warn("Member privacy changes couldn't be queued for the hub: {}", e.getClass().getSimpleName()); // tried again on the next round
        }
    }

    private void queuedFlush() {
        flushQueued.set(false); // cleared first: a save from here on queues the next round
        flush();
    }

    /**
     * Sends every held row's newest body; keeps what the hub couldn't take now. Runs on the lane, one round at a time; the held rows are only
     * locked to copy and to drop, never while the hub is asked, so a save never waits on the hub.
     */
    void flush() {
        // while the switch is off nothing goes out; rows held from before stay for when it is on again
        if (!on()) return;
        synchronized (flushing) {
            List<String> round;
            synchronized (this) {
                round = new ArrayList<>(held.keySet());
            }
            for (String path : round) {
                String body;
                synchronized (this) {
                    body = held.get(path); // the newest, re-read: a save during this round is sent, not the body it replaced
                }
                if (body == null) continue;
                RuneClansHubClient.Answer a;
                try {
                    a = sender.put(path, body);
                } catch (RuntimeException ex) {
                    a = null;
                }
                if (a == null || a.status() == 429 || a.status() >= 500) continue; // kept for the next round
                synchronized (this) {
                    held.remove(path, body); // a newer body saved while this one was sent stays, and goes next round
                }
                if (!a.ok()) log.warn("The hub refused a member privacy change ({}).", a.status());
            }
        }
    }
}
