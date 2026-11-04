package com.runeclans.bot.rsnlink.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.internal.InternalRouteModule;
import com.runeclans.bot.rsnlink.LinkDatabase;
import com.runeclans.bot.rsnlink.LinkHttp;
import com.runeclans.bot.rsnlink.LinkService;
import com.runeclans.bot.rsnlink.LinkSettings;
import com.runeclans.bot.rsnlink.LinkVerifier;
import com.runeclans.bot.rsnlink.verify.PgVerifyStore;
import com.runeclans.bot.rsnlink.verify.VerifySettings;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The glue between JonnyBot and the RSN link service (LINK-SYSTEM.md section 2). The service itself ({@code com.runeclans.bot.rsnlink})
 * imports nothing from the bot; this package is the only place that does both, so splitting the service out later means replacing this
 * class with a small {@code main}. It:
 * <ul>
 *   <li>reads the {@code LINK_*} settings; with no {@code LINK_DATABASE_URL} the service is off and its routes answer 503;</li>
 *   <li>prepares the link database's schema;</li>
 *   <li>mounts {@code /internal/rsnlink/} on the internal API (behind {@code X-Internal-Secret});</li>
 *   <li>runs the hourly retention sweep;</li>
 *   <li>when {@code LINK_VERIFY_ENABLED=true}, adds the "plugin knew it first" check (T-170) as a {@link LinkVerifier};</li>
 *   <li>gives the DM fallback (T-115) JonnyBot's gateway connection to send codes with.</li>
 * </ul>
 * A misconfigured service (keys missing or short) refuses to start and says which variables are wrong, but the bot keeps running.
 */
@BService
public class RsnLinkModule implements InternalRouteModule {
    private static final Logger log = LoggerFactory.getLogger(RsnLinkModule.class);

    private final LinkService service;
    private final LinkHttp http;
    private final JdaLinkDmSender dmSender = new JdaLinkDmSender();

    public RsnLinkModule(BotConfig config) {
        LinkService made = null;
        LinkHttp routes = null;
        try {
            LinkSettings settings = LinkSettings.fromEnv(config::getLinkSetting);
            if (settings == null) {
                log.info("RSN link service is off (LINK_DATABASE_URL not set).");
            } else {
                LinkDatabase database = new LinkDatabase(settings.databaseUrl(), 4);
                VerifySettings verify = VerifySettings.fromEnv(config::getLinkSetting, "RS Link verification (" + config.getJonnybotUrl() + ")");
                PgVerifyStore verifyStore = new PgVerifyStore(database::connection, settings.schema());
                // T-851/T-876: the bot makes no Jagex call of its own any more (the hub verifies links); LINK_VERIFY_ENABLED no longer starts the old checker
                if (verify.enabled()) log.warn("LINK_VERIFY_ENABLED is set, but the bot's own \"plugin knew it first\" check was retired: the hub does link verification now.");
                List<LinkVerifier> verifiers = List.of();
                made = new LinkService(settings, database, dmSender, verifiers, Clock.systemUTC(), new SecureRandom());
                made.start();
                routes = new LinkHttp(made);
                startSweeps(made);
                log.info("RSN link service is on (schema {}).", settings.schema());
            }
        } catch (LinkSettings.InvalidSettings e) {
            log.error("RSN link service refused to start: {}", e.getMessage());
            made = null;
            routes = null;
        } catch (RuntimeException e) {
            log.error("RSN link service couldn't start: {}", e.getClass().getSimpleName());
            made = null;
            routes = null;
        }
        this.service = made;
        this.http = routes;
    }

    /** The DM fallback needs the gateway connection, which exists only once JDA is ready. */
    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        dmSender.attach(event.getJda());
    }

    /** The running service, or null when it is off. For the bot-side pieces that plug into it (the DM fallback, the abuse view). */
    public LinkService service() {
        return service;
    }

    public LinkHttp http() {
        return http;
    }

    @Override
    public String prefix() {
        return LinkHttp.PREFIX;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (http == null) {
            LinkHttp.send(exchange, 503, DataObject.empty().put("error", "link_off").put("message", "Linking isn't available right now."));
            return;
        }
        http.handle(exchange);
    }

    private static void startSweeps(LinkService service) {
        ScheduledExecutorService executor = BotScheduler.lane("rsnlink-retention");
        executor.scheduleWithFixedDelay(() -> {
            try {
                service.sweep();
            } catch (RuntimeException e) {
                log.warn("RSN link retention sweep failed: {}", e.getClass().getSimpleName());
            }
        }, 5, 60, TimeUnit.MINUTES);
    }
}
