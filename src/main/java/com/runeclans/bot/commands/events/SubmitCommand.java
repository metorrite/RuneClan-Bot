package com.runeclans.bot.commands.events;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.events.BingoBoardView;
import com.runeclans.bot.events.BingoTemplate;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventSettings;
import com.runeclans.bot.events.EventSubmissions;
import com.runeclans.bot.events.EventSubmit;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.AutocompleteMode;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code /submit}: how a player sends proof. {@code /submit event} sends a screenshot for a tile or objective and {@code /submit event-verify} sends the
 * proof an event asks for before it starts. In an event's own channel the event, team and player are known from the channel, so the player only picks
 * the tile (from the ones they can still send proof for) and attaches the screenshot; anywhere else they name the event, unless the event requires
 * submissions to come from its channels ({@code submissions.require}), when only staff and the event's organisers may. Staff approve with the buttons
 * under the proof, and the receipt in the player's own channel is updated. {@code /events submit} stays as the explicit-number form.
 */
@Command
public class SubmitCommand {
    static final String TILES = "event-submit-tiles";

    private final HubEvents service;
    private final EventSubmissions submissions;
    private final AdminRoleFilter adminRoleFilter;

    public SubmitCommand(HubEvents service, EventSubmissions submissions, AdminRoleFilter adminRoleFilter) {
        this.service = service;
        this.submissions = submissions;
        this.adminRoleFilter = adminRoleFilter;
    }

    @TopLevelSlashCommandData(description = "Send proof for an event")
    @JDASlashCommand(name = "submit", subcommand = "event", description = "Send a screenshot as proof for a tile or objective")
    public void onEvent(GuildSlashEvent event,
                        @SlashOption(description = "The tile or objective", autocomplete = TILES) String tile,
                        @SlashOption(description = "A screenshot of the drop or the kill") Message.Attachment screenshot,
                        @SlashOption(description = "Anything staff should know") @Nullable String note,
                        @SlashOption(name = "event", description = "The event's number (only needed outside the event's channel)") @Nullable Long eventId) {
        Prepared p = prepare(event, eventId, screenshot);
        if (p == null) return;
        long guildId = event.getGuild().getIdLong();
        ObjectiveSpec objective;
        long claimId;
        try {
            List<ObjectiveSpec> all = service.objectives(p.event.id());
            objective = findTile(p.event, all, tile);
            if (objective == null) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.i_couldn_t_tell_which_tile", tile, (com.runeclans.bot.events.ClanVsClan.plays(p.event, BingoTemplate.KEY) ? Text.of(event, "events.or_use_the_number_drawn") : ".")));
                return;
            }
            claimId = service.submitClaim(guildId, p.event.id(), objective.key(), p.userId, 1, note == null ? "" : note, screenshot.getUrl());
        } catch (EventProblem problem) {
            Containers.replyEphemeral(event, Containers.WARNING, problem.getMessage());
            return;
        }
        send(event, p, claimId, objective.label(), note, screenshot, false);
    }

    @JDASlashCommand(name = "submit", subcommand = "event-verify", description = "Send the proof an event asks for before it starts")
    public void onVerify(GuildSlashEvent event,
                         @SlashOption(description = "The screenshot the organisers asked for") Message.Attachment screenshot,
                         @SlashOption(description = "Anything staff should know") @Nullable String note,
                         @SlashOption(name = "event", description = "The event's number (only needed outside the event's channel)") @Nullable Long eventId) {
        Prepared p = prepare(event, eventId, screenshot);
        if (p == null) return;
        long claimId;
        try {
            claimId = service.submitVerification(event.getGuild().getIdLong(), p.event.id(), p.userId, note == null ? "" : note, screenshot.getUrl());
        } catch (EventProblem problem) {
            Containers.replyEphemeral(event, Containers.WARNING, problem.getMessage());
            return;
        }
        send(event, p, claimId, "Verification", note, screenshot, true);
    }

    /** What the two subcommands share once the event and the person are settled. */
    private record Prepared(Event event, Participant player, long userId, TextChannel review) {}

    /** Works out the event, checks the person may send from here and that the screenshot and the review channel are usable; otherwise tells them why and returns null. */
    private Prepared prepare(GuildSlashEvent event, @Nullable Long explicitEvent, Message.Attachment screenshot) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        long userId = event.getUser().getIdLong();
        EventSubmit.Channel channel = submissions.channelOf(guild.getIdLong(), event.getChannel().getIdLong());
        Participant memberInEvent = null;
        Long eventId = explicitEvent != null ? explicitEvent : channel == null ? null : channel.eventId();
        if (eventId != null) {
            try {
                memberInEvent = service.participant(eventId, userId).orElse(null);
            } catch (EventProblem problem) {
                Containers.replyEphemeral(event, Containers.WARNING, problem.getMessage());
                return null;
            }
        }
        EventSubmit.Route route = EventSubmit.route(channel, explicitEvent, userId, memberInEvent == null ? null : memberInEvent.teamId());
        if (!route.ok()) {
            Containers.replyEphemeral(event, Containers.INFO, route.refusal());
            return null;
        }
        Event target;
        try {
            target = service.event(guild.getIdLong(), route.eventId());
        } catch (EventProblem problem) {
            Containers.replyEphemeral(event, Containers.WARNING, problem.getMessage());
            return null;
        }
        boolean staff = member != null && (member.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, member));
        EventSettings settings = service.settingsOf(target);
        boolean manages = service.mayOrganise(target, userId, staff);
        String refusal = EventSubmit.refusal(route.place(), settings.submissions().require(), manages);
        if (refusal != null) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal);
            return null;
        }
        if (memberInEvent == null) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "events.you_aren_t_in_so_there", target.name()));
            return null;
        }
        String problem = EventSubmissions.imageProblem(screenshot);
        if (problem != null) {
            Containers.replyEphemeral(event, Containers.WARNING, problem);
            return null;
        }
        TextChannel review = submissions.reviewChannel(guild, target);
        if (review == null) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "events.this_event_doesn_t_have_a_2"));
            return null;
        }
        return new Prepared(target, memberInEvent, userId, review);
    }

    private void send(GuildSlashEvent event, Prepared p, long claimId, String title, @Nullable String note, Message.Attachment screenshot, boolean verification) {
        event.deferReply(true).queue();
        submissions.deliver(event.getGuild(), p.event, claimId, event.getMember(), p.player, title, note, screenshot, verification, p.review)
                .whenComplete((text, error) -> {
                    if (error != null) {
                        event.getHook().editOriginal(Text.of(event, "events.i_couldn_t_pass_your_screenshot")).queue();
                        return;
                    }
                    event.getHook().editOriginal(text).queue();
                });
    }

    /** The objective a typed tile means: a label from the suggestions, or on a bingo the number drawn on the tile. */
    private ObjectiveSpec findTile(Event target, List<ObjectiveSpec> objectives, String typed) {
        ObjectiveSpec byLabel = EventSubmit.byLabel(objectives, typed);
        if (byLabel != null) return byLabel;
        if (com.runeclans.bot.events.ClanVsClan.plays(target, BingoTemplate.KEY) && typed != null && typed.strip().matches("\\d{1,3}")) {
            String key = BingoBoardView.keyForNumber(DataObject.fromJson(target.config()), objectives, Integer.parseInt(typed.strip()));
            return key == null ? null : objectives.stream().filter(o -> o.key().equals(key)).findFirst().orElse(null);
        }
        return null;
    }

    // ---------- suggestions ----------

    /** The tiles and objectives the person can still send proof for in the event of this channel (or the event number typed), by name. */
    @AutocompleteHandler(value = TILES, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggestTiles(CommandAutoCompleteInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) return List.of();
        try {
            long userId = event.getUser().getIdLong();
            OptionMapping typedEvent = event.getOption("event");
            EventSubmit.Channel channel = submissions.channelOf(guild.getIdLong(), event.getChannel().getIdLong());
            Long eventId = typedEvent != null ? typedEvent.getAsLong() : channel == null ? null : channel.eventId();
            if (eventId == null) return List.of();
            Event target = service.map().event(eventId).filter(r -> r.guildId() == guild.getIdLong()).isEmpty() ? null : service.event(guild.getIdLong(), eventId);
            Participant player = target == null ? null : service.participant(eventId, userId).orElse(null);
            if (player == null || !EventRules.acceptsProgress(target.status())) return List.of();
            String subject = target.scoringMode() == EventRules.ScoringMode.TEAM ? (player.teamId() == null ? null : "t:" + player.teamId()) : "u:" + userId;
            Standing mine = subject == null ? null : service.standings(guild.getIdLong(), eventId).stream().filter(s -> s.subject().equals(subject)).findFirst().orElse(null);
            return EventSubmit.openObjectives(service.objectives(eventId), mine).stream().map(ObjectiveSpec::label).distinct().limit(100).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
