package com.runeclans.bot.commands.events;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.events.EventPrep;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRoster;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventTeams;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.events.TeamBuilder;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashCommandGroupData;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.AutocompleteMode;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /events team ...}: building an event's teams from Discord, for staff and the event's own organisers. {@code create}, {@code rename} and {@code delete}
 * manage the teams themselves, {@code assign} and {@code unassign} put players on them, {@code captain} names a team's captain, {@code list} shows who is where and
 * who still needs a team, and {@code finalise} / {@code reopen} call the teams final or take that back. The teams are the hub's ({@link HubEvents}, T-934), the
 * same the website's team builder edits, so a player who hasn't passed the verification the event needs can't be put on a team until an organiser verifies or
 * waives them.
 */
@Command
public class EventsTeamCommand {
    static final String TEAMS = "event-team-names";
    /** How many players are listed for one team before "and N more", to stay inside Discord's message size. */
    private static final int LISTED = 30;
    /**
     * How much text {@code /events team list} fills before it leaves the lists of names out, and before it stops listing teams at all (a message holds 4000
     * characters in all, with room kept for the notes at the end).
     */
    private static final int BUDGET = 3000;
    private static final int HEADINGS_BUDGET = 3300;

    private final HubEvents service;
    private final EventTeams eventTeams;
    private final AdminRoleFilter adminRoleFilter;

    public EventsTeamCommand(HubEvents service, EventTeams eventTeams, AdminRoleFilter adminRoleFilter) {
        this.service = service;
        this.eventTeams = eventTeams;
        this.adminRoleFilter = adminRoleFilter;
    }

    private Actor actor(GuildSlashEvent event) {
        return Actor.of(event.getUser().getIdLong(), event.getGuild().getIdLong(), isStaff(event.getGuild(), event.getMember()));
    }

    // ---------- the teams ----------

    @JDASlashCommand(name = "events", group = "team", subcommand = "create", description = "Add a team to an event")
    public void onCreate(GuildSlashEvent event,
                         @SlashOption(name = "event", description = "The event's number") long eventId,
                         @SlashOption(description = "The team's name") String name,
                         @SlashOption(description = "A player to put on the team as its captain") @Nullable User captain) {
        Event e = organised(event, eventId);
        if (e == null) return;
        try {
            service.addTeam(event.getGuild().getIdLong(), eventId, name, captain == null ? null : captain.getIdLong(), actor(event));
            Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "events.team_added_to", name.strip(), e.name(), (captain == null ? "" : Text.of(event, "events.team_captain_suffix", captain.getAsMention()))),
                    Text.of(event, "events.put_players_on_it_with_events"));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    @JDASlashCommand(name = "events", group = "team", subcommand = "rename", description = "Give a team a new name")
    public void onRename(GuildSlashEvent event,
                         @SlashOption(name = "event", description = "The event's number") long eventId,
                         @SlashOption(description = "The team", autocomplete = TEAMS) String team,
                         @SlashOption(name = "new_name", description = "The new name") String newName) {
        Event e = organised(event, eventId);
        if (e == null) return;
        try {
            EventRepository.Team found = teamNamed(eventId, team);
            service.renameTeam(event.getGuild().getIdLong(), eventId, found.id(), newName, actor(event));
            Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "events.is_now", found.name(), newName.strip()));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    @JDASlashCommand(name = "events", group = "team", subcommand = "delete", description = "Remove a team (its players stay in the event, without a team)")
    public void onDelete(GuildSlashEvent event,
                         @SlashOption(name = "event", description = "The event's number") long eventId,
                         @SlashOption(description = "The team", autocomplete = TEAMS) String team) {
        Event e = organised(event, eventId);
        if (e == null) return;
        try {
            EventRepository.Team found = teamNamed(eventId, team);
            long members = service.participants(eventId).stream().filter(p -> p.teamId() != null && p.teamId() == found.id()).count();
            service.removeTeam(event.getGuild().getIdLong(), eventId, found.id(), actor(event));
            Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "events.team_was_removed_from", found.name(), e.name()),
                    members == 0 ? Text.of(event, "events.it_had_no_players") : Text.of(event, members == 1 ? "events.its_one_player_without_a_team" : "events.its_players_without_a_team", members));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    // ---------- the players ----------

    @JDASlashCommand(name = "events", group = "team", subcommand = "assign", description = "Put a player on a team")
    public void onAssign(GuildSlashEvent event,
                         @SlashOption(name = "event", description = "The event's number") long eventId,
                         @SlashOption(description = "The player") User player,
                         @SlashOption(description = "The team", autocomplete = TEAMS) String team) {
        Event e = organised(event, eventId);
        if (e == null) return;
        try {
            EventRepository.Team found = teamNamed(eventId, team);
            service.setTeam(event.getGuild().getIdLong(), eventId, player.getIdLong(), found.id(), actor(event));
            Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "events.is_on_in", player.getAsMention(), found.name(), e.name()));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    @JDASlashCommand(name = "events", group = "team", subcommand = "unassign", description = "Take a player off their team")
    public void onUnassign(GuildSlashEvent event,
                           @SlashOption(name = "event", description = "The event's number") long eventId,
                           @SlashOption(description = "The player") User player) {
        Event e = organised(event, eventId);
        if (e == null) return;
        try {
            service.setTeam(event.getGuild().getIdLong(), eventId, player.getIdLong(), null, actor(event));
            Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "events.has_no_team_in_now", player.getAsMention(), e.name()));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    @JDASlashCommand(name = "events", group = "team", subcommand = "captain", description = "Name a team's captain (leave the player out to clear it)")
    public void onCaptain(GuildSlashEvent event,
                          @SlashOption(name = "event", description = "The event's number") long eventId,
                          @SlashOption(description = "The team", autocomplete = TEAMS) String team,
                          @SlashOption(description = "The captain, who has to be on the team") @Nullable User player) {
        Event e = organised(event, eventId);
        if (e == null) return;
        try {
            EventRepository.Team found = teamNamed(eventId, team);
            service.setCaptain(event.getGuild().getIdLong(), eventId, found.id(), player == null ? null : player.getIdLong(), actor(event));
            Containers.replyEphemeral(event, Containers.SUCCESS, player == null ? Text.of(event, "events.has_no_captain_now", found.name()) : Text.of(event, "events.is_the_captain_of", player.getAsMention(), found.name()));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    // ---------- looking and finishing ----------

    @SlashCommandGroupData(description = "Build an event's teams: staff and the event's organisers")
    @JDASlashCommand(name = "events", group = "team", subcommand = "list", description = "The teams, who is on them and who still needs one")
    public void onList(GuildSlashEvent event, @SlashOption(name = "event", description = "The event's number") long eventId) {
        Event e = organised(event, eventId);
        if (e == null) return;
        if (e.scoringMode() != ScoringMode.TEAM) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "events.is_scored_by_player_so_it", e.name()));
            return;
        }
        long guildId = event.getGuild().getIdLong();
        List<TeamBuilder.Player> players = eventTeams.players(guildId, e);
        List<TeamBuilder.Team> teams = eventTeams.teams(eventId);
        Map<Long, Participant> participants = new HashMap<>();
        service.participants(eventId).forEach(p -> participants.put(p.userId(), p));
        Map<Long, String> blocked = new HashMap<>();
        for (TeamBuilder.Player p : players) {
            if (service.teamBlock(e, participants.get(p.userId())) != null) {
                blocked.put(p.userId(), EventRoster.effective(participants.get(p.userId()).verification(), service.settings(e).verification()) == EventRoster.Verification.REJECTED ? "turned down" : "not verified");
            }
        }
        EventPrep.Step step = service.prepOf(guildId, e.id()).steps().stream().filter(s -> s.key().equals("teams")).findFirst().orElse(null);
        Containers.replyEphemeral(event, Containers.INFO, listLines(e.name(), teams, TeamBuilder.totals(teams, players), players, blocked, step == null ? "" : step.detail()).toArray(String[]::new));
    }

    @JDASlashCommand(name = "events", group = "team", subcommand = "finalise", description = "Call the teams final: every player is on a team and nobody is missing")
    public void onFinalise(GuildSlashEvent event, @SlashOption(name = "event", description = "The event's number") long eventId) {
        Event e = organised(event, eventId);
        if (e == null) return;
        try {
            service.finaliseTeams(event.getGuild().getIdLong(), eventId, actor(event));
            Containers.reply(event, Containers.SUCCESS, false, Text.of(event, "events.the_teams_are_final", e.name()), Text.of(event, "events.players_can_still_be_moved_with"));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    @JDASlashCommand(name = "events", group = "team", subcommand = "reopen", description = "Take back \"the teams are final\" so they can be reworked")
    public void onReopen(GuildSlashEvent event, @SlashOption(name = "event", description = "The event's number") long eventId) {
        Event e = organised(event, eventId);
        if (e == null) return;
        try {
            service.reopenTeams(event.getGuild().getIdLong(), eventId, actor(event));
            Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "events.the_teams_are_open_again", e.name()), Text.of(event, "events.finalise_them_again_with_events_team"));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    // ---------- suggestions ----------

    /** The team names of the event typed in the command's {@code event} option, so the right team is a click away. Nothing for an event of another server. */
    @AutocompleteHandler(value = TEAMS, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggestTeams(CommandAutoCompleteInteractionEvent event) {
        OptionMapping option = event.getOption("event");
        if (option == null || event.getGuild() == null) return List.of();
        long eventId;
        try {
            eventId = option.getAsLong();
        } catch (RuntimeException e) {
            return List.of();
        }
        try {
            if (service.map().event(eventId).filter(r -> r.guildId() == event.getGuild().getIdLong()).isEmpty()) return List.of();
            return service.teams(eventId).stream().map(EventRepository.Team::name).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ---------- helpers ----------

    private boolean isStaff(Guild guild, Member member) {
        return member != null && (member.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, member));
    }

    /** The event, when the person may build its teams (staff, or one of its organisers); otherwise tells them why not and returns null. */
    private Event organised(GuildSlashEvent event, long eventId) {
        Guild guild = event.getGuild();
        Event found;
        try {
            found = service.event(guild.getIdLong(), eventId);
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, p.getMessage());
            return null;
        }
        if (!service.mayOrganise(found, event.getUser().getIdLong(), isStaff(guild, event.getMember()))) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.only_staff_and_this_event_organisers"));
            return null;
        }
        return found;
    }

    private EventRepository.Team teamNamed(long eventId, String name) {
        String wanted = name == null ? "" : name.strip();
        Optional<EventRepository.Team> found = service.teams(eventId).stream().filter(t -> t.name().equalsIgnoreCase(wanted)).findFirst();
        return found.orElseThrow(() -> new EventProblem(404, "This event has no team called **" + wanted + "**. See `/events team list`."));
    }

    private static String refusal(EventProblem p) {
        return p.problems().isEmpty() ? p.getMessage() : p.getMessage() + "\n- " + String.join("\n- ", p.problems());
    }

    private static String mentions(List<Long> ids) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (long id : ids) {
            if (shown == LISTED) break;
            if (shown > 0) text.append(' ');
            text.append("<@").append(id).append('>');
            shown++;
        }
        if (ids.size() > shown) text.append(" and ").append(ids.size() - shown).append(" more");
        return text.toString();
    }

    /**
     * The lines of {@code /events team list}: each team with its size, rating and members, then the players who still need a team, then those who can't be on one
     * yet (with why), and how the teams stand. {@code blocked} maps a player to a few words on why they can't be on a team.
     */
    static List<String> listLines(String eventName, List<TeamBuilder.Team> teams, Map<Long, TeamBuilder.Totals> totals, List<TeamBuilder.Player> players,
                                  Map<Long, String> blocked, String standing) {
        List<String> lines = new ArrayList<>();
        int[] used = {0};
        boolean[] trimmed = {false};
        // a section: its heading line, and the names under it only while they still fit in what Discord lets one message hold
        java.util.function.BiConsumer<String, String> add = (heading, names) -> {
            if (names.isEmpty()) {
                lines.add(heading);
                used[0] += heading.length();
            } else if (used[0] + heading.length() + names.length() <= BUDGET) {
                lines.add(heading + "\n-# " + names);
                used[0] += heading.length() + names.length() + 4;
            } else {
                lines.add(heading);
                used[0] += heading.length();
                trimmed[0] = true;
            }
        };
        add.accept("### Teams · " + eventName, "");
        if (teams.isEmpty()) add.accept("No teams yet. Add one with `/events team create`.", "");
        for (int i = 0; i < teams.size(); i++) {
            TeamBuilder.Team team = teams.get(i);
            TeamBuilder.Totals t = totals.get(team.id());
            List<Long> members = players.stream().filter(p -> p.teamId() != null && p.teamId() == team.id()).map(TeamBuilder.Player::userId).toList();
            String heading = "**" + team.name() + "** · " + members.size() + (members.size() == 1 ? " player" : " players") + (members.isEmpty() ? "" : " · rating " + t.ratingTotal() + " (average " + t.average() + ")")
                    + (team.captainId() == null ? "" : " · captain <@" + team.captainId() + ">");
            if (used[0] + heading.length() > HEADINGS_BUDGET) {
                lines.add("-# …and " + (teams.size() - i) + (teams.size() - i == 1 ? " more team" : " more teams") + ".");
                trimmed[0] = true;
                break;
            }
            add.accept(heading, members.isEmpty() ? "" : mentions(members));
        }
        List<Long> without = players.stream().filter(p -> p.teamId() == null && !blocked.containsKey(p.userId())).map(TeamBuilder.Player::userId).toList();
        if (!without.isEmpty()) add.accept("**No team** · " + without.size(), mentions(without));
        if (!blocked.isEmpty()) {
            List<String> who = new ArrayList<>();
            blocked.forEach((id, why) -> who.add("<@" + id + "> (" + why + ")"));
            add.accept("**Can't be on a team yet** · " + blocked.size(), String.join(" ", who.stream().limit(LISTED).toList()) + (who.size() > LISTED ? " and " + (who.size() - LISTED) + " more" : ""));
        }
        if (!standing.isBlank()) lines.add("-# " + standing);
        if (trimmed[0]) lines.add("-# Some lists of names are left out to fit Discord's message size: the dashboard's Team builder shows everyone.");
        return lines;
    }
}
