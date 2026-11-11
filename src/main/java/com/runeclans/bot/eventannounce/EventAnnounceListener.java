package com.runeclans.bot.eventannounce;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.BotListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.guild.scheduledevent.ScheduledEventCreateEvent;
import net.dv8tion.jda.api.events.guild.scheduledevent.ScheduledEventDeleteEvent;
import net.dv8tion.jda.api.events.guild.scheduledevent.update.GenericScheduledEventUpdateEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands Discord's scheduled event changes to {@link EventAnnounceService} (which does nothing for a server that hasn't turned it on), and answers the
 * "I'm interested" button. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class EventAnnounceListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(EventAnnounceListener.class);

    private final EventAnnounceService service;

    public EventAnnounceListener(EventAnnounceService service) {
        this.service = service;
    }

    @Override
    public void onScheduledEventCreate(ScheduledEventCreateEvent event) {
        service.created(event.getScheduledEvent());
    }

    @Override
    public void onGenericScheduledEventUpdate(GenericScheduledEventUpdateEvent<?> event) {
        service.changed(event.getScheduledEvent());
    }

    @Override
    public void onScheduledEventDelete(ScheduledEventDeleteEvent event) {
        service.deleted(event.getGuild().getIdLong(), event.getScheduledEvent().getIdLong());
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(EventAnnounceService.BUTTON_PREFIX) || !id.substring(EventAnnounceService.BUTTON_PREFIX.length()).matches("\\d{1,19}")) return;
        try {
            if (event.getGuild() == null || event.getMember() == null) return;
            String answer = service.toggleInterest(event.getGuild(), event.getMember(), Long.parseLong(id.substring(EventAnnounceService.BUTTON_PREFIX.length())));
            event.reply(answer).setEphemeral(true).queue();
        } catch (Exception e) {
            log.error("Event announcer button '{}' failed", id, e);
            Containers.replyError(event); // always answer, or Discord shows "This interaction failed" (B-104)
        }
    }
}
