package com.runeclans.bot.alerts;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The one switch for the bot's own, unprompted posts. The live bot posts them where each server chose. Any other copy of the bot (a development
 * bot sitting in the real server on a stale copy of the database) would post stale or wrong alerts next to the live bot's correct ones, ping the admin
 * role, and DM real players. So when the bot is not live (and posting has not been turned on deliberately with {@code /dev toggleposting}):
 * <ul>
 *     <li>an alert that is only a message ({@link #route}) goes to the channel named by {@code DEV_ALERT_CHANNEL_ID}, or is only logged when that is
 *     not set, and never pings anybody ({@link #noPings});</li>
 *     <li>anything tied to stored state or a person (a post the bot later edits, a DM) is skipped and logged ({@link #skip});</li>
 *     <li>a background sweep that changes the real server by itself (auto-clean deleting messages, autoroles, timed roles, event roles, event
 *     wrap-up, the daily clan sync's Member/Guest roles) does not run at all ({@link #skipSweep}, T-187). The live bot runs the same sweeps, so nothing is lost in the server.
 *     The same goes for what the bot does by itself when someone joins, leaves, chats or changes roles (welcome and goodbye, the account gate and honeypot,
 *     autoroles and kept roles on join, server tag, role rules, chat level-ups, B-82).</li>
 * </ul>
 * What somebody runs on purpose (a command, a button, a dashboard action) never goes through here and replies where it was run. Sites reach this
 * through the static methods so the many posters need no new constructor arguments; with no router built (a unit test) everything passes through.
 */
@BService
public class AutoPostRouter {
    private static final Logger log = LoggerFactory.getLogger(AutoPostRouter.class);
    private static volatile Routing instance;
    /** The sweeps whose skipping has been logged since the bot last stopped being live, so a sweep that runs every few seconds logs once. */
    private static final Set<String> sweepsSkipped = ConcurrentHashMap.newKeySet();

    /** The switch and the dev channel. Kept apart from the service because the framework needs the service to have exactly one constructor. */
    private record Routing(BooleanSupplier diverting, Long devChannelId) {}

    public AutoPostRouter(BotConfig config, TrackingPostingToggle posting) {
        install(() -> !posting.isEnabled(), config.getDevAlertChannelId());
        boolean diverting = diverting();
        Long devChannelId = devChannelId();
        log.info("Automatic posts are {}{}.", diverting ? "DIVERTED (this bot is not live): " : "posted normally",
                diverting ? devChannelId == null ? "logged only, DEV_ALERT_CHANNEL_ID is not set" : "sent to channel " + devChannelId + " without pings" : "");
    }

    /** Having an event listener is what makes the framework build this service at startup, so the switch is in place before any poster runs. */
    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        log.debug("Automatic post routing is active (diverting: {}).", diverting());
    }

    /** For tests and the constructor above. */
    static void install(BooleanSupplier diverting, Long devChannelId) {
        instance = new Routing(diverting, devChannelId);
    }

    /** Forgets the router (tests). */
    public static void reset() {
        instance = null;
        sweepsSkipped.clear();
    }

    /** Whether the bot's automatic posts are being kept out of the servers' channels right now. */
    public static boolean diverting() {
        Routing router = instance;
        return router != null && router.diverting().getAsBoolean();
    }

    /** The configured dev channel id, or null. */
    public static Long devChannelId() {
        Routing router = instance;
        return router == null ? null : router.devChannelId();
    }

    /**
     * Where a stateless alert should go: {@code target} when posting normally, otherwise the dev channel, or null (and a log line) when there is none.
     * Callers send nothing when this is null, and use {@link #noPings} when it is not the configured channel.
     */
    public static GuildMessageChannel route(GuildMessageChannel target, String what) {
        Routing router = instance;
        if (router == null || !router.diverting().getAsBoolean() || target == null) return target;
        Long dev = router.devChannelId();
        GuildMessageChannel devChannel = dev == null ? null : Shards.channel(target.getJDA(), GuildMessageChannel.class, dev);
        if (devChannel == null) {
            log.info("[dev, not posted] {} would have gone to #{} ({}).", what, target.getName(), target.getId());
            return null;
        }
        log.debug("[dev] {} diverted from #{} to #{}.", what, target.getName(), devChannel.getName());
        return devChannel;
    }

    /** True while automatic posts are diverted, for what must simply not happen from a non-live bot (DMs, posts the bot keeps track of). Logs what was skipped. */
    public static boolean skip(String what) {
        if (!diverting()) return false;
        log.info("[dev, skipped] {}.", what);
        return true;
    }

    /**
     * True while the bot is not live, for a background sweep that would change the real server by itself (delete messages, give or take roles, post
     * files): the sweep returns at once. Logs one line the first time each sweep is skipped (again after posting has been on in between), not every round.
     */
    public static boolean skipSweep(String sweep) {
        if (!diverting()) {
            sweepsSkipped.remove(sweep);
            return false;
        }
        if (sweepsSkipped.add(sweep)) log.info("[dev, skipped] The {} sweep does not run while this bot is not live (turn posting on with /dev toggleposting to test it).", sweep);
        return true;
    }

    /** True while diverted: the post must not ping a role, everyone or anybody. */
    public static boolean noPings() {
        return diverting();
    }

    /** Text with its pings switched off: {@code <@&role>}, {@code <@user>}, {@code @everyone} and {@code @here} become plain words. Only while diverted. */
    public static String defang(String text) {
        if (text == null || !diverting()) return text;
        return text.replaceAll("<@[&!]?(\\d+)>", "<$1>").replace("@everyone", "everyone").replace("@here", "here");
    }
}
