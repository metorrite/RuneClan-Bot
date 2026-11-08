package com.runeclans.bot.commands;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.tracking.TrackingGroup;
import com.runeclans.bot.tracking.TrackingSendNowService;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Dev-only: the Clan Report's "Send Now" without opening the Tracking panel — reads the hub's points and
 * promotion flags (the same job {@code ClanPointsScheduler} fires at 01:30 UTC) and posts the
 * report to the channels configured for {@link TrackingGroup#CLAN_REPORT}. It's a thin wrapper over
 * {@link TrackingSendNowService#sendGroupNow}, so it can't drift from what the panel button does, and
 * says why when nothing was posted. Safe to repeat — the bot awards nothing (the hub does). {@code @Test} (with
 * {@link CommandScope#GUILD}) keeps this out of production's command list entirely, and
 * {@link BotConfig#getLiveEnvironment()} is checked again at runtime as a second layer, same pattern
 * as {@code DevTogglePostingCommand}.
 */
@Command
public class DevClanReportCommand {
    private static final Logger log = LoggerFactory.getLogger(DevClanReportCommand.class);

    private final BotConfig botConfig;
    private final TrackingSendNowService sendNowService;

    public DevClanReportCommand(BotConfig botConfig, TrackingSendNowService sendNowService) {
        this.botConfig = botConfig;
        this.sendNowService = sendNowService;
    }

    @JDASlashCommand(name = "dev", subcommand = "clanreport", description = "Recomputes points and posts the Clan Report to its channels now")
    public void onDevClanReport(GuildSlashEvent event) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "commands.this_command_is_dev_only"));
            return;
        }

        event.deferReply(true).queue();
        try {
            TrackingSendNowService.Result result = sendNowService.sendGroupNow(event.getGuild(), TrackingGroup.CLAN_REPORT);
            event.getHook().editOriginalComponents(List.of(Containers.toast(
                    result.problem() ? Containers.WARNING : Containers.SUCCESS, result.lines().toArray(new String[0]))))
                    .useComponentsV2(true).queue();
        } catch (Exception e) {
            log.error("Manual clan report run failed for guild {}", event.getGuild().getIdLong(), e);
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER,
                    "The run failed — check the bot's logs."))).useComponentsV2(true).queue();
        }
    }
}
