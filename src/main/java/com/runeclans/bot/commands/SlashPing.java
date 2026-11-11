package com.runeclans.bot.commands;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;

/** Dev-only connectivity check — {@code @Test} keeps it off production, same as {@code /dev signups}. */
@Command
public class SlashPing {

    // The one place the /dev group is declared as guild-scoped and test-only: @Test means it is only ever pushed to the
    // test guild(s), so none of /dev exists on the live bot. Every subcommand also refuses to run if it somehow did.
    // The dev bot's test guild is a real community server, so the bot also lets only its owner run any of it (DevCommandFilter),
    // and defaultLocked hides /dev from members without Administrator in Discord's command list as a first layer.
    @TopLevelSlashCommandData(scope = CommandScope.GUILD, defaultLocked = true, description = "Developer tools for testing the bot (never on the live bot)")
    @Test({})
    @JDASlashCommand(name = "dev", subcommand = "ping", description = "Replies with pong")
    public void onPing(GuildSlashEvent event) {
        Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "commands.pong"));
    }
}
