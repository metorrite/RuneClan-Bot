package com.runeclans.bot.voicetext;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands voice joins, leaves and moves to {@link VoiceTextService}. Bots are ignored. Registered with JDA by {@code Bot} because it is a {@link BotListener};
 * it needs the voice-state gateway intent and cache, which {@code Bot} turns on for it.
 */
@BService
public class VoiceTextListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(VoiceTextListener.class);

    private final VoiceTextService service;

    public VoiceTextListener(VoiceTextService service) {
        this.service = service;
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (AutoPostRouter.skipSweep("voice text")) return; // changes channel access in the real server: only the live bot does that (B-82 follow-up)
        if (event.getMember().getUser().isBot()) return;
        try {
            if (event.getChannelLeft() != null) service.left(event.getGuild(), event.getMember(), event.getChannelLeft());
            if (event.getChannelJoined() != null) service.joined(event.getGuild(), event.getMember(), event.getChannelJoined());
        } catch (Exception e) {
            log.error("Voice text link failed for {} in guild {}", event.getMember().getId(), event.getGuild().getId(), e);
        }
    }
}
