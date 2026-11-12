package com.runeclans.bot.devtools;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.commands.application.ApplicationCommandFilter;
import io.github.freya022.botcommands.api.commands.application.ApplicationCommandInfo;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Only the bot's owner (see {@link BotOwners}: the Discord application's owner or team, plus {@code OWNER_IDS}) may use the
 * {@code /dev} commands. The dev bot sits in a real community server, so "only on test servers" is not enough on its own: without
 * this, any member there could turn posting on, close every signup on every server, or export the member list.
 * <p>
 * Global, so every {@code /dev} subcommand is covered, including ones added later, without each having to remember the check.
 * {@link #isAllowed} is public for the buttons and forms those commands post (filters don't run for those).
 * Fails closed: if Discord can't say who owns the bot and nobody is configured, nobody is let in.
 */
@BService
@NullMarked
public class DevCommandFilter implements ApplicationCommandFilter {
    /** The one top-level name every developer tool lives under (see {@code SlashPing}). */
    public static final String DEV_COMMAND = "dev";
    static final String REFUSED = "Only the bot's owner can use the /dev tools.";

    private final BotOwners owners;

    public DevCommandFilter(BotOwners owners) {
        this.owners = owners;
    }

    @Override
    public boolean getGlobal() {
        return true;
    }

    @Override
    public @Nullable String check(GenericCommandInteractionEvent event, ApplicationCommandInfo commandInfo) {
        if (!DEV_COMMAND.equals(event.getName())) return null;
        if (isAllowed(event.getJDA(), event.getUser().getIdLong())) return null;

        Containers.replyEphemeral(event, Containers.WARNING, REFUSED);
        return "Not the bot's owner (/dev)";
    }

    /** True only for the bot's owner. Checks what is already known first, so the owner rarely waits on a Discord lookup. */
    public boolean isAllowed(JDA jda, long userId) {
        return owners.isKnownOwner(userId) || owners.isOwner(jda, userId);
    }

    /** The message to show anyone else who presses a /dev button or sends a /dev form. */
    public static String refusal() {
        return REFUSED;
    }
}
