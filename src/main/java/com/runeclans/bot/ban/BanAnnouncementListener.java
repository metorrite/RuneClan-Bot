package com.runeclans.bot.ban;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Announces bans. Registered with JDA by {@code Bot} because it is a {@link BotListener}. */
@BService
public class BanAnnouncementListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(BanAnnouncementListener.class);

    private final BanAnnouncementService service;

    public BanAnnouncementListener(BanAnnouncementService service) {
        this.service = service;
    }

    @Override
    public void onGuildBan(GuildBanEvent event) {
        if (AutoPostRouter.skipSweep("ban announcement")) return; // posts in the real server: only the live bot does that (B-82 follow-up)
        try {
            service.onBan(event.getGuild(), event.getUser());
        } catch (Exception e) {
            log.error("The ban announcement for {} failed", event.getUser().getId(), e);
        }
    }
}
