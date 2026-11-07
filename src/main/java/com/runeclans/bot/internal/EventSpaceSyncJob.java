package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventSpaceOps;
import com.runeclans.bot.hubevents.HubEvents;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * Puts who holds each of an event's roles right ({@code events/{id}/space/sync}), run by the outbox (T-456) instead of inside the request: one
 * Discord request per member changed. It sets each role to who should have it, so running it again is harmless; when some changes didn't go
 * through it ends as failed with how many, and Retry on the website runs it again. The event is the hub's ({@link HubEvents}, by its local id), the
 * same one {@link EventSpaceOps} manages the roles of.
 */
@BService
public class EventSpaceSyncJob implements DiscordJobHandler {
    static final String KIND = "event.space.sync";

    private final HubEvents service;
    private final EventSpaceOps ops;

    public EventSpaceSyncJob(HubEvents service, EventSpaceOps ops) {
        this.service = service;
        this.ops = ops;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean idempotent() {
        return true;
    }

    static DataObject payload(long eventId) {
        return DataObject.empty().put("eventId", Long.toString(eventId));
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        int failed;
        try {
            failed = ops.syncMembers(guild, service.event(guild.getIdLong(), job.payload().getLong("eventId")));
        } catch (EventProblem e) {
            throw new DiscordJobs.Refused(e.getMessage());
        }
        if (failed > 0) {
            throw new DiscordJobs.Refused(failed + (failed == 1 ? " role change" : " role changes")
                    + " didn't go through. Check the bot's role is above the event roles, then try again.");
        }
        return DataObject.empty().put("syncFailed", 0);
    }
}
