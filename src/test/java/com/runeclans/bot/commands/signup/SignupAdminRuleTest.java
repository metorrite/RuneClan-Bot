package com.runeclans.bot.commands.signup;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B-103: the people /signup lets build a sheet (the bot's Admin group) can also run its admin panel, as can Manage Server. */
class SignupAdminRuleTest {
    @Test
    void theAdminGroupRunsTheAdminPanelWithoutManageServer() {
        assertTrue(SignupInteractionListener.mayManage(false, () -> true));
    }

    @Test
    void manageServerStillRunsItAndTheGroupIsNotAskedThen() {
        AtomicInteger asked = new AtomicInteger();
        assertTrue(SignupInteractionListener.mayManage(true, () -> {
            asked.incrementAndGet();
            return false;
        }));
        assertEquals(0, asked.get());
    }

    @Test
    void anyoneElseIsRefused() {
        assertFalse(SignupInteractionListener.mayManage(false, () -> false));
    }

    // the listener's own check, wired to the bot's Admin group

    private final com.runeclans.bot.permission.AdminRoleFilter adminGroup = mock(com.runeclans.bot.permission.AdminRoleFilter.class);
    private final SignupInteractionListener listener = new SignupInteractionListener(mock(SignupService.class), mock(com.runeclans.bot.config.BotConfig.class),
            adminGroup, mock(com.runeclans.bot.hub.HubService.class), mock(com.runeclans.bot.devtools.DevCommandFilter.class));
    private final Guild guild = mock(Guild.class);

    private Member member(boolean manageServer) {
        Member member = mock(Member.class);
        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(manageServer);
        return member;
    }

    @Test
    void theListenerLetsTheAdminGroupIn() {
        Member staff = member(false);
        when(adminGroup.isAuthorized(guild, staff)).thenReturn(true);
        assertTrue(listener.isAdmin(guild, staff));
    }

    @Test
    void theListenerLetsManageServerIn() {
        assertTrue(listener.isAdmin(guild, member(true)));
    }

    @Test
    void theListenerKeepsEveryoneElseOut() {
        Member someone = member(false);
        when(adminGroup.isAuthorized(guild, someone)).thenReturn(false);
        assertFalse(listener.isAdmin(guild, someone));
        assertFalse(listener.isAdmin(guild, null));
        assertFalse(listener.isAdmin(null, someone));
    }
}
