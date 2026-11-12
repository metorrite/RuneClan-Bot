package com.runeclans.bot.runescape;

import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the verification sync writes to Discord (B-89 follow-up): one role per request, never the whole role list (which would undo a role
 * another feature gave a moment before), and the old role comes off only after the new one is on, so nobody holds neither.
 */
class VerificationRoleSyncWritesTest {
    private static final long GUILD = 9, USER = 42, MEMBER_ROLE = 10, GUEST_ROLE = 20;

    private Guild guild;
    private Member member;
    private Role memberRole;
    private Role guestRole;
    private VerificationRoleSyncService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        GuildSettingsService settings = mock(GuildSettingsService.class);
        ClanMemberRepository roster = mock(ClanMemberRepository.class);
        service = new VerificationRoleSyncService(settings, roster, mock(PlayerLinkService.class));
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, "Clan", null, null, null, MEMBER_ROLE, null, GUEST_ROLE, null, true, null, null, null));
        when(roster.isActiveMember(GUILD, "Test Player")).thenReturn(true);

        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SelfMember self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.canInteract(any(Role.class))).thenReturn(true);
        memberRole = role(MEMBER_ROLE);
        guestRole = role(GUEST_ROLE);

        member = mock(Member.class);
        when(member.getRoles()).thenReturn(List.of(guestRole));
        CacheRestAction<Member> lookup = mock(CacheRestAction.class);
        doAnswer(call -> {
            ((Consumer<Member>) call.getArgument(0)).accept(member);
            return null;
        }).when(lookup).queue(any(), any());
        when(guild.retrieveMemberById(USER)).thenReturn(lookup);
    }

    private Role role(long id) {
        Role r = mock(Role.class);
        when(r.getIdLong()).thenReturn(id);
        when(r.getId()).thenReturn(Long.toString(id));
        when(r.isPublicRole()).thenReturn(false);
        when(r.isManaged()).thenReturn(false);
        when(r.getPermissions()).thenReturn(EnumSet.noneOf(net.dv8tion.jda.api.Permission.class));
        when(guild.getRoleById(id)).thenReturn(r);
        return r;
    }

    /** A request that answers with success, or with a failure, as soon as it is queued. */
    @SuppressWarnings("unchecked")
    private static AuditableRestAction<Void> answers(boolean ok) {
        AuditableRestAction<Void> action = mock(AuditableRestAction.class);
        when(action.reason(anyString())).thenReturn(action);
        doAnswer(call -> {
            if (ok) ((Consumer<Void>) call.getArgument(0)).accept(null);
            else ((Consumer<Throwable>) call.getArgument(1)).accept(new RuntimeException("Missing Permissions"));
            return null;
        }).when(action).queue(any(), any());
        return action;
    }

    @Test
    void theNewRoleGoesOnAndThenTheOldOneComesOffOneRoleAtATime() {
        AuditableRestAction<Void> give = answers(true);
        AuditableRestAction<Void> take = answers(true);
        when(guild.addRoleToMember(member, memberRole)).thenReturn(give);
        when(guild.removeRoleFromMember(member, guestRole)).thenReturn(take);

        service.syncRoles(guild, USER, "Test Player");

        verify(guild).addRoleToMember(member, memberRole);
        verify(guild).removeRoleFromMember(member, guestRole);
        verify(guild, never()).modifyMemberRoles(any(Member.class), any(Collection.class), any(Collection.class));
    }

    @Test
    void whenTheNewRoleCannotBeGivenTheOldOneIsKept() {
        AuditableRestAction<Void> give = answers(false);
        when(guild.addRoleToMember(member, memberRole)).thenReturn(give);

        service.syncRoles(guild, USER, "Test Player");

        verify(guild, never()).removeRoleFromMember(any(Member.class), any(Role.class));
    }
}
