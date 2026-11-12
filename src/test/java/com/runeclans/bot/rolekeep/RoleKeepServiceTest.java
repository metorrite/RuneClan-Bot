package com.runeclans.bot.rolekeep;

import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.rolekeep.RoleKeepRepository.Kept;
import com.runeclans.bot.rolekeep.RoleKeepRepository.Temp;
import com.runeclans.bot.rolekeep.RoleKeepRules.Source;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mocks only: no Discord and no database, so no real member's role can be touched. */
class RoleKeepServiceTest {
    private static final long GUILD = 10;
    private static final long MUTED = 100;
    private static final long CLAN_ROLE = 200;
    private static final long ADMIN_ROLE = 300;

    private RoleKeepRepository repository;
    private RoleKeepService service;
    private Guild guild;
    private Role muted;
    private Role clanRole;
    private Role adminRole;

    @BeforeEach
    void setUp() {
        repository = mock(RoleKeepRepository.class);
        GuildSettingsService settings = mock(GuildSettingsService.class);
        PermissionGroupService groups = mock(PermissionGroupService.class);
        service = new RoleKeepService(repository, settings, groups);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SelfMember self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.canInteract(any(Member.class))).thenReturn(true);
        when(self.canInteract(any(Role.class))).thenReturn(true);
        muted = role(MUTED, "Muted");
        clanRole = role(CLAN_ROLE, "Member");
        adminRole = role(ADMIN_ROLE, "Admin");
        when(guild.getRoleById(MUTED)).thenReturn(muted);
        when(guild.getRoleById(CLAN_ROLE)).thenReturn(clanRole);
        when(guild.getRoleById(ADMIN_ROLE)).thenReturn(adminRole);
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, "Clan", null, null, null, CLAN_ROLE, null, null, null, true, null, null, null));
        when(groups.groups(GUILD)).thenReturn(List.of(new PermissionGroup(1, GUILD, "admin", "Admin", true, true, List.of(ADMIN_ROLE))));
        when(repository.sticky(GUILD)).thenReturn(Set.of(MUTED, CLAN_ROLE, ADMIN_ROLE));
    }

    private Role role(long id, String name) {
        Role r = mock(Role.class);
        when(r.getIdLong()).thenReturn(id);
        when(r.getName()).thenReturn(name);
        when(r.isPublicRole()).thenReturn(false);
        when(r.isManaged()).thenReturn(false);
        when(r.getPermissions()).thenReturn(java.util.EnumSet.noneOf(net.dv8tion.jda.api.Permission.class));
        return r;
    }

    private Member member(long id, Role... roles) {
        Member m = mock(Member.class);
        User user = mock(User.class);
        when(m.getUser()).thenReturn(user);
        when(m.getIdLong()).thenReturn(id);
        when(m.getId()).thenReturn(Long.toString(id));
        when(m.getGuild()).thenReturn(guild);
        when(m.getRoles()).thenReturn(List.of(roles));
        return m;
    }

    @SuppressWarnings("unchecked")
    private AuditableRestAction<Void> action() {
        AuditableRestAction<Void> action = mock(AuditableRestAction.class);
        when(action.reason(any(String.class))).thenReturn(action);
        return action;
    }

    // ---------- leaving ----------

    @Test
    void onLeavingOnlyTheSafeStickyRoleIsRememberedNeverTheClanSyncOrStaffRole() {
        service.onLeave(guild, 5, List.of(muted, clanRole, adminRole));

        verify(repository).keep(GUILD, 5, MUTED, Source.STICKY, 0);
        verify(repository, never()).keep(eq(GUILD), eq(5L), eq(CLAN_ROLE), any(), anyLong());
        verify(repository, never()).keep(eq(GUILD), eq(5L), eq(ADMIN_ROLE), any(), anyLong());
    }

    @Test
    void anEmptyStickyListRemembersNothing() {
        when(repository.sticky(GUILD)).thenReturn(Set.of());

        service.onLeave(guild, 5, List.of(muted));

        verify(repository, never()).keep(anyLong(), anyLong(), anyLong(), any(), anyLong());
    }

    // ---------- rejoining ----------

    @Test
    void onRejoiningTheKeptMutedRoleIsGivenBackOnItsOwnAndNeverTheClanRole() {
        Member back = member(5);
        when(repository.keptFor(GUILD, 5)).thenReturn(List.of(kept(1, MUTED), kept(2, CLAN_ROLE)));
        when(repository.tempFor(GUILD, 5)).thenReturn(List.of());
        AuditableRestAction<Void> action = action();
        when(guild.addRoleToMember(back, muted)).thenReturn(action);

        service.onJoin(back);

        // one role at a time, never the whole list: a whole-list write would overwrite the autoroles given on the same join (B-89)
        verify(guild).addRoleToMember(back, muted);
        verify(guild, never()).addRoleToMember(back, clanRole);
        verify(guild, never()).modifyMemberRoles(any(Member.class), any(Collection.class), any(Collection.class));
        verify(repository).forgetSticky(GUILD, 5);
    }

    @Test
    void aMemberWhoAlreadyHoldsEverythingIsLeftAloneSoRepeatedJoinsCannotLoop() {
        Member back = member(5, muted);
        when(repository.keptFor(GUILD, 5)).thenReturn(List.of(kept(1, MUTED)));
        when(repository.tempFor(GUILD, 5)).thenReturn(List.of());

        service.onJoin(back);

        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
    }

    @Test
    void aTimedRoleStillRunningIsGivenBackAndAnEndedOneIsNot() {
        Member back = member(5);
        Role event = role(400, "Event pass");
        when(guild.getRoleById(400L)).thenReturn(event);
        when(repository.keptFor(GUILD, 5)).thenReturn(List.of());
        when(repository.tempFor(GUILD, 5)).thenReturn(List.of(new Temp(1, GUILD, 5, 400, Instant.now().plusSeconds(600), 1)));
        AuditableRestAction<Void> give = action();
        when(guild.addRoleToMember(back, event)).thenReturn(give);

        service.onJoin(back);

        verify(guild).addRoleToMember(back, event);
    }

    @Test
    void aMemberWithNothingKeptCostsNoDiscordCall() {
        Member back = member(5);
        when(repository.keptFor(GUILD, 5)).thenReturn(List.of());
        when(repository.tempFor(GUILD, 5)).thenReturn(List.of());

        service.onJoin(back);

        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
        verify(repository, never()).forgetSticky(anyLong(), anyLong());
    }

    // ---------- what a person sets ----------

    @Test
    void theClanSyncStaffAndUnsafeRolesCannotBeKeptTimedOrMadeSticky() {
        Member target = member(5);
        when(muted.isManaged()).thenReturn(false);

        assertNotNull(service.persist(guild, 1, target, clanRole));
        assertNotNull(service.persist(guild, 1, target, adminRole));
        assertNotNull(service.giveTemp(guild, 1, target, clanRole, Duration.ofHours(1)));
        assertNotNull(service.saveSticky(guild, Set.of(MUTED, CLAN_ROLE)));
        verify(repository, never()).keep(anyLong(), anyLong(), anyLong(), any(), anyLong());
        verify(repository, never()).setTemp(anyLong(), anyLong(), anyLong(), any(), anyLong());
        verify(repository, never()).setSticky(anyLong(), any());
    }

    @Test
    void aSafeRoleIsKeptAndGivenNow() {
        Member target = member(5);
        AuditableRestAction<Void> add = action();
        when(guild.addRoleToMember(target, muted)).thenReturn(add);

        assertNull(service.persist(guild, 1, target, muted));

        verify(repository).keep(GUILD, 5, MUTED, Source.MANUAL, 1);
        verify(guild).addRoleToMember(target, muted);
    }

    @Test
    void aTimedRoleIsStoredWithItsEndAndGivenNow() {
        Member target = member(5);
        AuditableRestAction<Void> add = action();
        when(guild.addRoleToMember(target, muted)).thenReturn(add);

        assertNull(service.giveTemp(guild, 1, target, muted, Duration.ofHours(2)));

        verify(repository).setTemp(eq(GUILD), eq(5L), eq(MUTED), argThat((Instant at) -> at.isAfter(Instant.now().plusSeconds(7000))), eq(1L));
    }

    // ---------- the clock ----------

    @Test
    void anEndedTimedRoleIsTakenOffOnce() {
        JDA jda = mock(JDA.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        Member holder = member(5, muted);
        Temp ended = new Temp(9, GUILD, 5, MUTED, Instant.now().minusSeconds(5), 1);
        when(repository.expired(100)).thenReturn(List.of(ended));
        when(repository.deleteTemp(GUILD, 9)).thenReturn(true);
        CacheRestAction<Member> lookup = lookupReturning(holder);
        when(guild.retrieveMemberById(5L)).thenReturn(lookup);
        AuditableRestAction<Void> remove = action();
        when(guild.removeRoleFromMember(holder, muted)).thenReturn(remove);

        service.expire(jda);

        verify(guild).removeRoleFromMember(holder, muted);
        verify(repository).deleteTemp(GUILD, 9);
    }

    @Test
    void anEndedTimedRoleThatBecameAClanSyncRoleIsForgottenButNeverTakenOff() {
        JDA jda = mock(JDA.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        Temp ended = new Temp(9, GUILD, 5, CLAN_ROLE, Instant.now().minusSeconds(5), 1);
        when(repository.expired(100)).thenReturn(List.of(ended));
        when(repository.deleteTemp(GUILD, 9)).thenReturn(true);

        service.expire(jda);

        verify(repository).deleteTemp(GUILD, 9);
        verify(guild, never()).removeRoleFromMember(any(Member.class), any(Role.class));
        verify(guild, never()).retrieveMemberById(anyLong());
    }

    @Test
    void whenTheBotIsNotInTheServerRightNowNothingIsForgotten() {
        JDA jda = mock(JDA.class);
        Temp ended = new Temp(9, GUILD, 5, MUTED, Instant.now().minusSeconds(5), 1);
        when(repository.expired(100)).thenReturn(List.of(ended));

        service.expire(jda);

        verify(repository, never()).deleteTemp(anyLong(), anyLong());
    }

    // ---------- loop protection ----------

    @Test
    void theListenerOnlyHearsJoinsAndLeavesNeverRoleChangesSoItCannotAnswerItsOwnChanges() {
        for (var method : RoleKeepListener.class.getDeclaredMethods()) {
            String name = method.getName();
            assertTrue(name.equals("onGuildMemberJoin") || name.equals("onGuildMemberRemove"), "unexpected handler " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static CacheRestAction<Member> lookupReturning(Member member) {
        CacheRestAction<Member> lookup = mock(CacheRestAction.class);
        org.mockito.Mockito.doAnswer(call -> {
            ((Consumer<Member>) call.getArgument(0)).accept(member);
            return null;
        }).when(lookup).queue(any(Consumer.class), any(Consumer.class));
        return lookup;
    }

    private static Kept kept(long id, long roleId) {
        return new Kept(id, GUILD, 5, roleId, Source.MANUAL, 1, Instant.EPOCH);
    }
}
