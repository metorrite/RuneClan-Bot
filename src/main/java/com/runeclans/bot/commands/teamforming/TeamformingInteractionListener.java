package com.runeclans.bot.commands.teamforming;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@BService
public class TeamformingInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TeamformingInteractionListener.class);

    private static final Color ADDED_COLOR = new Color(0x2E, 0xCC, 0x71);   // green
    private static final Color REMOVED_COLOR = new Color(0xE7, 0x4C, 0x3C); // red
    private static final Duration CONFIRMATION_LIFETIME = Duration.ofSeconds(90);

    private final TeamformingService teamformingService;

    public TeamformingInteractionListener(TeamformingService teamformingService) {
        this.teamformingService = teamformingService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (event.getGuild() == null || event.getMember() == null) return;

        if (id.equals(TeamformingService.OPEN_PANEL_BUTTON_ID)) {
            handleOpenPanel(event);
        } else if (id.startsWith(TeamformingService.TOGGLE_PREFIX)) {
            handleToggleMonthlyMass(event);
        } else if (id.equals(TeamformingService.UPDATE_ROLES_BUTTON_ID)) {
            handleUpdateRoles(event);
        }
    }

    /** Opens a fresh, personalized ephemeral panel — every checkmark/button color reflects this member's actual roles. */
    private void handleOpenPanel(ButtonInteractionEvent event) {
        try {
            List<ContainerChildComponent> components = teamformingService.buildPersonalPanelComponents(event.getMember());
            Container container = Container.of(components).withAccentColor(TeamformingService.PANEL_ACCENT_COLOR);

            event.replyComponents(List.of(container))
                    .setEphemeral(true)
                    .useComponentsV2()
                    .queue();
        } catch (Exception e) {
            log.error("Failed to open personal teamforming panel for user {}", event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    /** Flips the staged Monthly Mass state and re-renders the same ephemeral panel in place. */
    private void handleToggleMonthlyMass(ButtonInteractionEvent event) {
        try {
            boolean currentlyOn = event.getButton().getStyle() == ButtonStyle.SUCCESS;
            teamformingService.stageMonthlyMass(event.getUser().getIdLong(), !currentlyOn);

            rerenderPanel(event);
        } catch (Exception e) {
            log.error("Failed to toggle Monthly Mass for user {}", event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    private void handleUpdateRoles(ButtonInteractionEvent event) {
        try {
            TeamformingService.BatchResult result = teamformingService.applyPersonalPanel(event.getGuild(), event.getMember());
            showResult(event, result);
        } catch (Exception e) {
            log.error("Failed to apply personal teamforming panel for user {}", event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (event.getGuild() == null || event.getMember() == null) return;
        if (!id.startsWith(TeamformingService.SELECT_PREFIX)) return;

        String sectionKey = id.substring(TeamformingService.SELECT_PREFIX.length());
        if (TeamformingCatalog.sectionByKey(sectionKey) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "teamforming.this_teamforming_section_no_longer_exists"));
            return;
        }

        try {
            teamformingService.stageSectionSelection(event.getUser().getIdLong(), sectionKey, event.getValues());
            rerenderPanel(event);
        } catch (Exception e) {
            log.error("Failed to stage teamforming selection for section '{}', user {}",
                    sectionKey, event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    /** Rebuilds the personal panel from current staged+live state and edits the same (ephemeral) message in place. */
    private void rerenderPanel(ComponentInteraction event) {
        List<ContainerChildComponent> components = teamformingService.buildPersonalPanelComponents(event.getMember());
        Container container = Container.of(components).withAccentColor(TeamformingService.PANEL_ACCENT_COLOR);

        event.editComponents(List.of(container))
                .useComponentsV2(true)
                .queue();
    }

    /** Replaces the personal panel with a final "Added"/"Removed" result view, then auto-deletes it. */
    private void showResult(ButtonInteractionEvent event, TeamformingService.BatchResult result) {
        List<MessageTopLevelComponent> components = new ArrayList<>();

        if (!result.refused().isEmpty()) {
            components.add(TextDisplay.of("The bot can't hand out " + result.refused().stream().map(r -> "@" + r.getName()).collect(java.util.stream.Collectors.joining(", "))
                    + ": ask a server admin to check " + (result.refused().size() == 1 ? "that role." : "those roles.")));
        }
        if (result.isEmpty()) {
            if (result.refused().isEmpty()) components.add(TextDisplay.of("No changes were made — your tags already matched your selections."));
        } else {
            if (!result.added().isEmpty()) {
                components.add(Container.of(TextDisplay.of("Added roles:\n" + mentionsGroupedByBoss(result.added())))
                        .withAccentColor(ADDED_COLOR));
            }
            if (!result.removed().isEmpty()) {
                components.add(Container.of(TextDisplay.of("Removed roles:\n" + mentionsGroupedByBoss(result.removed())))
                        .withAccentColor(REMOVED_COLOR));
            }
        }

        event.editComponents(components)
                .useComponentsV2(true)
                .setAllowedMentions(List.of()) // show the role mention(s) without actually pinging them
                .delay(CONFIRMATION_LIFETIME)
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }

    /**
     * Groups roles by the boss/category they belong to (in catalog order), one line per group, so
     * a batch touching several bosses reads as clearly separated blocks instead of one long run of
     * mentions. Roles not tied to any section (e.g. Monthly Mass) are listed on their own first line.
     */
    private String mentionsGroupedByBoss(List<Role> roles) {
        List<Role> ungrouped = new ArrayList<>();
        Map<TeamformingSection, List<Role>> bySection = new LinkedHashMap<>();

        for (Role role : roles) {
            TeamformingSection section = TeamformingCatalog.sectionForRoleName(role.getName());
            if (section == null) {
                ungrouped.add(role);
            } else {
                bySection.computeIfAbsent(section, s -> new ArrayList<>()).add(role);
            }
        }

        List<String> lines = new ArrayList<>();
        if (!ungrouped.isEmpty()) lines.add(mentionAll(ungrouped));
        for (TeamformingSection section : TeamformingCatalog.SECTIONS) {
            List<Role> group = bySection.get(section);
            if (group != null && !group.isEmpty()) lines.add(mentionAll(group));
        }

        return String.join("\n\n", lines);
    }

    private String mentionAll(List<Role> roles) {
        StringBuilder sb = new StringBuilder();
        for (Role role : roles) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append("<@&").append(role.getIdLong()).append(">");
        }
        return sb.toString();
    }

    private void replyError(ComponentInteraction event) {
        Containers.replyError(event);
    }
}
