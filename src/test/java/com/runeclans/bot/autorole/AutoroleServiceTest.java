package com.runeclans.bot.autorole;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoroleServiceTest {
    private static final long GUILD = 5L;
    private AutoroleRepository repository;
    private AutoroleService service;
    private Guild guild;
    private Member member;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        repository = mock(AutoroleRepository.class);
        service = new AutoroleService(repository);
        guild = mock(Guild.class, Mockito.RETURNS_DEEP_STUBS);
        when(guild.getIdLong()).thenReturn(GUILD);
        self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        member = mock(Member.class);
        User user = mock(User.class);
        when(user.isBot()).thenReturn(false);
        when(member.getUser()).thenReturn(user);
        when(member.getGuild()).thenReturn(guild);
    }

    private Role role(long id, boolean interactable) {
        Role role = mock(Role.class);
        when(role.isPublicRole()).thenReturn(false);
        when(role.isManaged()).thenReturn(false);
        when(role.getPermissions()).thenReturn(java.util.EnumSet.noneOf(net.dv8tion.jda.api.Permission.class));
        when(self.canInteract(role)).thenReturn(interactable);
        when(guild.getRoleById(id)).thenReturn(role);
        return role;
    }

    @Test
    void aJoinerHasTheConfiguredRoleTakenAway() {
        Role guest = role(10L, true);
        when(member.getRoles()).thenReturn(List.of(guest));
        when(repository.get(GUILD)).thenReturn(new AutoroleConfig(GUILD, true, 0, List.of(), List.of(), List.of(10L), List.of()));

        service.onJoin(member);

        verify(guild).removeRoleFromMember(member, guest);
        verify(guild, never()).modifyMemberRoles(any(Member.class), anyCollection(), anyCollection());
    }

    @Test
    void eachRoleIsGivenOnItsOwnSoAKeptRoleGivenOnTheSameJoinIsNotOverwritten() {
        Role member1 = role(20L, true);
        Role member2 = role(21L, true);
        when(member.getRoles()).thenReturn(List.of());
        when(repository.get(GUILD)).thenReturn(new AutoroleConfig(GUILD, true, 0, List.of(20L, 21L), List.of(), List.of(), List.of()));

        service.onJoin(member);

        verify(guild).addRoleToMember(member, member1);
        verify(guild).addRoleToMember(member, member2);
        verify(guild, never()).modifyMemberRoles(any(Member.class), anyCollection(), anyCollection());
    }

    @Test
    void aRoleTheJoinerDoesNotHaveOrTheBotCannotManageIsLeftAlone() {
        role(10L, true);
        Role above = role(11L, false);
        when(member.getRoles()).thenReturn(List.of(above));
        when(repository.get(GUILD)).thenReturn(new AutoroleConfig(GUILD, true, 0, List.of(), List.of(), List.of(10L, 11L), List.of()));

        service.onJoin(member);

        verify(guild, never()).removeRoleFromMember(any(Member.class), any(Role.class));
    }

    @Test
    void whenTheServerIsSwitchedOffNothingIsRemoved() {
        Role guest = role(10L, true);
        when(member.getRoles()).thenReturn(List.of(guest));
        when(repository.get(GUILD)).thenReturn(new AutoroleConfig(GUILD, false, 0, List.of(), List.of(), List.of(10L), List.of()));

        service.onJoin(member);

        verify(guild, never()).removeRoleFromMember(any(Member.class), any(Role.class));
    }
}
