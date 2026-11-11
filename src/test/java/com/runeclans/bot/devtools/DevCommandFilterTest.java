package com.runeclans.bot.devtools;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.notice.BotOwners;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-79: the dev bot lives in a real community server, so /dev must refuse everyone but the bot's owner. */
class DevCommandFilterTest {
    private static final long APP_OWNER = 11;
    private static final long CONFIGURED = 22;
    private static final long MEMBER = 33;

    private BotConfig config;
    private JDA jda;
    private DevCommandFilter filter;

    @BeforeEach
    void setUp() {
        config = mock(BotConfig.class);
        when(config.getOwnerIds()).thenReturn(List.of(CONFIGURED));
        jda = mock(JDA.class);
        filter = new DevCommandFilter(new BotOwners(config));
    }

    @SuppressWarnings("unchecked")
    private void discordSays(long ownerId) {
        ApplicationInfo info = mock(ApplicationInfo.class);
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(ownerId);
        when(info.getOwner()).thenReturn(user);
        CacheRestAction<ApplicationInfo> action = mock(CacheRestAction.class);
        when(action.complete()).thenReturn(info);
        when(jda.retrieveApplicationInfo()).thenReturn(action);
    }

    @SuppressWarnings("unchecked")
    private void discordIsDown() {
        CacheRestAction<ApplicationInfo> action = mock(CacheRestAction.class);
        when(action.complete()).thenThrow(new RejectedExecutionException("The Requester has been stopped!"));
        when(jda.retrieveApplicationInfo()).thenReturn(action);
    }

    private GenericCommandInteractionEvent command(String name, long userId) {
        GenericCommandInteractionEvent event = mock(GenericCommandInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getName()).thenReturn(name);
        when(event.getJDA()).thenReturn(jda);
        when(event.getUser().getIdLong()).thenReturn(userId);
        return event;
    }

    @Test
    void appliesToEveryCommand() {
        assertTrue(filter.getGlobal());
    }

    @Test
    void aServerMemberWhoIsNotTheOwnerIsRefusedAndToldWhy() {
        discordSays(APP_OWNER);
        GenericCommandInteractionEvent event = command("dev", MEMBER);

        assertNotNull(filter.check(event, null));
        verify(event).replyComponents(anyList());
    }

    @Test
    void theApplicationOwnerAndConfiguredOwnersMayUseDev() {
        discordSays(APP_OWNER);

        assertNull(filter.check(command("dev", APP_OWNER), null));
        assertNull(filter.check(command("dev", CONFIGURED), null));
    }

    @Test
    void otherCommandsPassStraightThrough() {
        GenericCommandInteractionEvent event = command("signup", MEMBER);

        assertNull(filter.check(event, null));
        verify(jda, never()).retrieveApplicationInfo();
    }

    @Test
    void failsClosedWhenDiscordCannotSayWhoOwnsTheBot() {
        discordIsDown();

        assertFalse(filter.isAllowed(jda, APP_OWNER));
        assertFalse(filter.isAllowed(jda, MEMBER));
        assertTrue(filter.isAllowed(jda, CONFIGURED)); // OWNER_IDS still works without Discord
    }

    @Test
    void aKnownOwnerIsLetInWithoutAskingDiscordAgain() {
        discordSays(APP_OWNER);
        assertTrue(filter.isAllowed(jda, APP_OWNER));
        discordIsDown();

        assertTrue(filter.isAllowed(jda, APP_OWNER));
    }
}
