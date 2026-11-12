package com.runeclans.bot.commands;

import com.runeclans.bot.commands.signup.SignupDevCommand;
import com.runeclans.bot.commands.ticket.DevHelpSetupCommand;
import com.runeclans.bot.commands.ticket.DevTicketPostCommand;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every developer command lives under one {@code /dev} command, and that command is guild-scoped and test-only — which is
 * what keeps all of it off the live bot (BotCommands only pushes {@code @Test} commands to the test guild, and production has none).
 */
class DevCommandGroupTest {
    private static final List<Class<?>> DEV_CLASSES = List.of(
            DevClanReportCommand.class, DevClearCommandsCommand.class, DevEmbedCommand.class, DevExportMembersCommand.class,
            DevTogglePostingCommand.class, SignupDevCommand.class, DevTicketPostCommand.class, DevHelpSetupCommand.class, SlashPing.class);

    private static List<Method> slashMethods() {
        List<Method> methods = new ArrayList<>();
        for (Class<?> c : DEV_CLASSES) {
            for (Method m : c.getDeclaredMethods()) if (m.isAnnotationPresent(JDASlashCommand.class)) methods.add(m);
        }
        return methods;
    }

    @org.junit.jupiter.api.Test
    void everyDevCommandIsASubcommandOfDev() {
        Set<String> subcommands = new TreeSet<>();
        for (Method m : slashMethods()) {
            JDASlashCommand a = m.getAnnotation(JDASlashCommand.class);
            assertEquals("dev", a.name(), m.getDeclaringClass().getSimpleName() + " should be under /dev");
            assertFalse(a.subcommand().isEmpty(), m.getDeclaringClass().getSimpleName() + " needs a subcommand name");
            assertTrue(subcommands.add(a.subcommand()), "duplicate /dev " + a.subcommand());
        }
        assertEquals(Set.of("clanreport", "clearcommands", "embed", "exportmembers", "helpsetup", "ping", "signups", "toggleposting", "ticketpost"), subcommands);
    }

    @org.junit.jupiter.api.Test
    void theGroupIsDeclaredOnceAndIsTestOnly() {
        List<Method> declaring = slashMethods().stream().filter(m -> m.isAnnotationPresent(TopLevelSlashCommandData.class)).toList();
        assertEquals(1, declaring.size(), "exactly one method declares the /dev group");
        Method carrier = declaring.get(0);
        assertEquals(CommandScope.GUILD, carrier.getAnnotation(TopLevelSlashCommandData.class).scope());
        assertTrue(carrier.isAnnotationPresent(Test.class), "the group must be @Test so it is never pushed to the live bot");
        assertTrue(carrier.getAnnotation(TopLevelSlashCommandData.class).defaultLocked(),
                "/dev is hidden from members without Administrator (the bot also checks the owner: DevCommandFilter)");
    }
}
