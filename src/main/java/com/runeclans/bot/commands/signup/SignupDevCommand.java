package com.runeclans.bot.commands.signup;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-only diagnostic command — lists every currently active signup across every guild the bot
 * is in (not just this one), with a one-click "close everything" tool for wiping out test data
 * between iterations. {@code @Test} (with {@link CommandScope#GUILD}) keeps this out of
 * production entirely, and {@link BotConfig#getLiveEnvironment()} is checked again at runtime as
 * a second layer, since a bulk-close tool is exactly the kind of thing worth not trusting to a
 * single gate.
 */
@Command
public class SignupDevCommand {
    private static final int MAX_LISTED = 40;

    private final SignupService signupService;
    private final BotConfig botConfig;

    public SignupDevCommand(SignupService signupService, BotConfig botConfig) {
        this.signupService = signupService;
        this.botConfig = botConfig;
    }

    @JDASlashCommand(name = "dev", subcommand = "signups", description = "Lists every active signup across every server this bot is in")
    public void onDevSignups(GuildSlashEvent event) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "signup.this_command_is_dev_only"));
            return;
        }

        List<SignupSession> all = signupService.getAllActiveSignups();

        if (all.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "signup.no_active_signups_found_across_any"));
            return;
        }

        Map<Long, List<SignupSession>> byGuild = new LinkedHashMap<>();
        for (SignupSession session : all) {
            byGuild.computeIfAbsent(session.guildId(), id -> new ArrayList<>()).add(session);
        }

        StringBuilder sb = new StringBuilder();
        int shown = 0;

        outer:
        for (var entry : byGuild.entrySet()) {
            Guild guild = Shards.guild(event.getJDA(), entry.getKey());
            sb.append("**").append(guild != null ? guild.getName() : "Unknown guild")
                    .append("** (`").append(entry.getKey()).append("`)\n");

            for (SignupSession session : entry.getValue()) {
                if (shown++ >= MAX_LISTED) {
                    sb.append("*...and more.*\n");
                    break outer;
                }
                sb.append("  • `").append(session.signupId()).append("` — ")
                        .append(session.title()).append(" (").append(session.type()).append(")\n");
            }
        }

        sb.append("\n**Total: ").append(all.size()).append(" active signup(s) across ")
                .append(byGuild.size()).append(" server(s).**");

        Container container = Containers.card(Containers.WARNING,
                TextDisplay.of("# Active Signups (all servers)"),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of(sb.toString()),
                ActionRow.of(Button.danger(SignupInteractionListener.DEV_CLOSE_ALL, "Close ALL Active Signups")));

        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
    }
}
