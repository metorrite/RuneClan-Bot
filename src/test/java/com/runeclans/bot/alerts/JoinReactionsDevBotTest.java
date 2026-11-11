package com.runeclans.bot.alerts;

import com.runeclans.bot.accountgate.AccountGateListener;
import com.runeclans.bot.accountgate.AccountGateRepository;
import com.runeclans.bot.autorole.AutoroleRepository;
import com.runeclans.bot.autorole.AutoroleService;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.eventannounce.EventAnnounceRepository;
import com.runeclans.bot.eventannounce.EventAnnounceService;
import com.runeclans.bot.goodbye.GoodbyeRepository;
import com.runeclans.bot.goodbye.GoodbyeService;
import com.runeclans.bot.moderation.ModService;
import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.rolekeep.RoleKeepRepository;
import com.runeclans.bot.rolekeep.RoleKeepService;
import com.runeclans.bot.rolerules.RoleRuleRepository;
import com.runeclans.bot.rolerules.RoleRuleService;
import com.runeclans.bot.servertag.ServerTagListener;
import com.runeclans.bot.servertag.ServerTagRepository;
import com.runeclans.bot.welcome.WelcomeCardRenderer;
import com.runeclans.bot.welcome.WelcomeCardRepository;
import com.runeclans.bot.welcome.WelcomeRepository;
import com.runeclans.bot.welcome.WelcomeService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-82: a bot that is not live (the dev bot, in a real server on a stale copy of the database) does nothing by itself when someone joins, leaves or
 * changes roles: no welcome or goodbye, no kick or ban, no roles given. The live bot does all of it. Each check here stops before the settings are
 * even read, so no stale setting can act.
 */
class JoinReactionsDevBotTest {
    private Guild guild;
    private Member member;
    private User user;

    @BeforeEach
    void setUp() {
        LiveSwitch.set(false);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(1L);
        user = mock(User.class);
        when(user.isBot()).thenReturn(false);
        member = mock(Member.class);
        when(member.getUser()).thenReturn(user);
        when(member.getGuild()).thenReturn(guild);
    }

    @AfterEach
    void reset() {
        AutoPostRouter.reset();
    }

    @Test
    void noWelcome() {
        WelcomeRepository repository = mock(WelcomeRepository.class);
        new WelcomeService(repository, mock(WelcomeCardRepository.class), mock(WelcomeCardRenderer.class)).welcomeNewMember(member);
        verify(repository, never()).get(anyLong());
    }

    @Test
    void noGoodbye() {
        GoodbyeRepository repository = mock(GoodbyeRepository.class);
        new GoodbyeService(repository).onLeave(guild, user);
        verify(repository, never()).get(anyLong());
    }

    @Test
    void noAccountGateKickOrBan() {
        AccountGateRepository repository = mock(AccountGateRepository.class);
        GuildMemberJoinEvent event = mock(GuildMemberJoinEvent.class, RETURNS_DEEP_STUBS);
        when(event.getUser()).thenReturn(user);
        when(event.getGuild()).thenReturn(guild);
        new AccountGateListener(repository).onGuildMemberJoin(event);
        verify(repository, never()).get(anyLong());
    }

    @Test
    void noAutoroleOnJoin() {
        AutoroleRepository repository = mock(AutoroleRepository.class);
        new AutoroleService(repository).onJoin(member);
        verify(repository, never()).get(anyLong());
    }

    @Test
    void noKeptRolesOnJoinAndTheStickyListIsKept() {
        RoleKeepRepository repository = mock(RoleKeepRepository.class);
        new RoleKeepService(repository, mock(GuildSettingsService.class), mock(PermissionGroupService.class)).onJoin(member);
        verify(repository, never()).keptFor(anyLong(), anyLong());
        verify(repository, never()).forgetSticky(anyLong(), anyLong());
    }

    @Test
    void noServerTagAction() {
        ServerTagRepository repository = mock(ServerTagRepository.class);
        GuildMemberJoinEvent event = mock(GuildMemberJoinEvent.class);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        new ServerTagListener(repository, mock(ModService.class), mock(AdminRoleFilter.class)).onGuildMemberJoin(event);
        verify(repository, never()).get(anyLong());
    }

    @Test
    void noRoleRules() {
        RoleRuleRepository repository = mock(RoleRuleRepository.class);
        new RoleRuleService(repository, mock(GuildSettingsService.class), mock(PermissionGroupService.class)).onRoleChange(guild, member, Set.of(5L), true);
        verify(repository, never()).list(anyLong());
    }

    @Test
    void aDeletedDiscordEventLeavesTheLiveBotsAnnouncementAndRoleAlone() {
        EventAnnounceRepository repository = mock(EventAnnounceRepository.class);
        new EventAnnounceService(repository).deleted(1L, 2L);
        verify(repository, after(300).never()).tracked(anyLong());
    }

    @Test
    void theLiveBotStillWelcomes() {
        LiveSwitch.set(true);
        WelcomeRepository repository = mock(WelcomeRepository.class);
        when(repository.get(1L)).thenReturn(mock(com.runeclans.bot.welcome.WelcomeConfig.class));
        new WelcomeService(repository, mock(WelcomeCardRepository.class), mock(WelcomeCardRenderer.class)).welcomeNewMember(member);
        verify(repository).get(1L);
    }
}
