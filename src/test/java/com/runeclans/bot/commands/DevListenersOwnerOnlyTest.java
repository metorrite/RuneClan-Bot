package com.runeclans.bot.commands;

import com.runeclans.bot.commands.signup.SignupInteractionListener;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.devtools.DevCommandFilter;
import com.runeclans.bot.hub.HubService;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.permission.AdminRoleFilter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-79: the buttons and the form that /dev posts refuse anyone but the bot's owner, like the commands do (filters don't run for them). */
class DevListenersOwnerOnlyTest {
    private static final long OWNER = 22;
    private static final long MEMBER = 33;

    private JDA jda;
    private BotConfig config;
    private DevCommandFilter devAccess;
    private AdminRoleFilter adminRoleFilter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = mock(BotConfig.class);
        when(config.getOwnerIds()).thenReturn(List.of(OWNER));
        when(config.getLiveEnvironment()).thenReturn(false);
        jda = mock(JDA.class);
        CacheRestAction<ApplicationInfo> action = mock(CacheRestAction.class);
        when(action.complete()).thenThrow(new RejectedExecutionException("offline in tests"));
        when(jda.retrieveApplicationInfo()).thenReturn(action);
        devAccess = new DevCommandFilter(new BotOwners(config));
        adminRoleFilter = mock(AdminRoleFilter.class);
        when(adminRoleFilter.isAuthorized(any(), any())).thenReturn(true); // a server admin, but not the bot's owner
    }

    private ButtonInteractionEvent press(String buttonId, long userId) {
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getGuild()).thenReturn(mock(Guild.class));
        when(event.getJDA()).thenReturn(jda);
        when(event.getComponentId()).thenReturn(buttonId);
        when(event.getUser().getIdLong()).thenReturn(userId);
        return event;
    }

    @ParameterizedTest
    @ValueSource(strings = {SignupInteractionListener.DEV_CLOSE_ALL, SignupInteractionListener.DEV_CLOSE_ALL_CONFIRM})
    void aServerAdminWhoIsNotTheOwnerCannotCloseEverySignup(String buttonId) {
        SignupService signups = mock(SignupService.class);
        SignupInteractionListener listener = new SignupInteractionListener(signups, config, adminRoleFilter, mock(HubService.class), devAccess);
        ButtonInteractionEvent event = press(buttonId, MEMBER);

        listener.onButtonInteraction(event);

        verify(signups, never()).closeAllActiveSignups(any());
        verify(event).replyComponents(anyList()); // told why, not left on "interaction failed"
    }

    @Test
    void theOwnersConfirmReallyClosesEverySignup() {
        SignupService signups = mock(SignupService.class);
        SignupInteractionListener listener = new SignupInteractionListener(signups, config, adminRoleFilter, mock(HubService.class), devAccess);

        listener.onButtonInteraction(press(SignupInteractionListener.DEV_CLOSE_ALL_CONFIRM, OWNER));

        verify(signups).closeAllActiveSignups(jda);
    }

    @Test
    void aServerAdminWhoIsNotTheOwnerCannotPostADevEmbed() {
        DevEmbedListener listener = new DevEmbedListener(config, adminRoleFilter, devAccess);
        ModalInteractionEvent event = mock(ModalInteractionEvent.class, RETURNS_DEEP_STUBS);
        Member member = mock(Member.class);
        when(member.getIdLong()).thenReturn(MEMBER);
        when(event.getModalId()).thenReturn(DevEmbedListener.MODAL_ID);
        when(event.getGuild()).thenReturn(mock(Guild.class));
        when(event.getMember()).thenReturn(member);
        when(event.getJDA()).thenReturn(jda);

        listener.onModalInteraction(event);

        verify(event).replyComponents(anyList());
        verify(event, never()).getValue(anyString());
    }
}
