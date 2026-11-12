package com.runeclans.bot.rolerules;

import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.rolerules.RoleRuleRules.Event;
import com.runeclans.bot.rolerules.RoleRuleRules.Rule;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mocks only: no Discord and no database, so no real member's role can be touched. */
class RoleRuleServiceTest {
    private static final long GUILD = 10;
    private static final long MEMBER_ROLE = 900; // the clan role sync's
    private static final long ADMIN_ROLE = 901; // a staff permission role

    private RoleRuleRepository repository;
    private RoleRuleService service;
    private Guild guild;
    private final AtomicLong now = new AtomicLong(1_000_000);

    @BeforeEach
    void setUp() {
        repository = mock(RoleRuleRepository.class);
        GuildSettingsService settings = mock(GuildSettingsService.class);
        PermissionGroupService groups = mock(PermissionGroupService.class);
        service = new RoleRuleService(repository, settings, groups);
        service.clock = now::get;
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SelfMember self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.canInteract(any(Member.class))).thenReturn(true);
        when(self.canInteract(any(Role.class))).thenReturn(true);
        for (long id : new long[]{1, 2, 3, 20, 21, 30, MEMBER_ROLE, ADMIN_ROLE}) {
            Role made = role(id);
            when(guild.getRoleById(id)).thenReturn(made);
        }
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, "Clan", null, null, null, MEMBER_ROLE, null, null, null, true, null, null, null));
        when(groups.groups(GUILD)).thenReturn(List.of(new PermissionGroup(1, GUILD, "admin", "Admin", true, true, List.of(ADMIN_ROLE))));
    }

    private Role role(long id) {
        Role r = mock(Role.class);
        when(r.getIdLong()).thenReturn(id);
        when(r.getName()).thenReturn("role" + id);
        when(r.isPublicRole()).thenReturn(false);
        when(r.isManaged()).thenReturn(false);
        when(r.getPermissions()).thenReturn(java.util.EnumSet.noneOf(net.dv8tion.jda.api.Permission.class));
        return r;
    }

    private Member member(long id, long... held) {
        Member m = mock(Member.class);
        User user = mock(User.class);
        when(m.getUser()).thenReturn(user);
        when(m.getIdLong()).thenReturn(id);
        when(m.getId()).thenReturn(Long.toString(id));
        List<Role> roles = new java.util.ArrayList<>();
        for (long r : held) roles.add(guild.getRoleById(r));
        when(m.getRoles()).thenReturn(roles);
        return m;
    }

    @SuppressWarnings("unchecked")
    private AuditableRestAction<Void> action() {
        AuditableRestAction<Void> action = mock(AuditableRestAction.class);
        when(action.reason(any(String.class))).thenReturn(action);
        return action;
    }

    private void rules(Rule... rules) {
        when(repository.list(GUILD)).thenReturn(List.of(rules));
        service.changed(GUILD);
    }

    private static Rule rule(long id, Event event, List<Long> when, List<Long> add, List<Long> remove) {
        return new Rule(id, GUILD, "rule" + id, event, when, add, remove, true);
    }

    // ---------- running ----------

    @Test
    void gainingTheConditionRoleGivesTheOtherRoleAndTakesTheRemovedOneRoleByRole() {
        rules(rule(1, Event.GAINS, List.of(1L), List.of(20L), List.of(30L)));
        Member m = member(5, 1, 30);
        AuditableRestAction<Void> action = action();
        when(guild.addRoleToMember(eq(m), any(Role.class))).thenReturn(action);
        when(guild.removeRoleFromMember(eq(m), any(Role.class))).thenReturn(action);

        service.onRoleChange(guild, m, Set.of(1L), true);

        // one request per role, never a whole-list write that could undo another feature's change on the same member (B-89)
        verify(guild).addRoleToMember(eq(m), argThat((Role r) -> r.getIdLong() == 20));
        verify(guild).removeRoleFromMember(eq(m), argThat((Role r) -> r.getIdLong() == 30));
        verify(guild, never()).modifyMemberRoles(any(Member.class), any(Collection.class), any(Collection.class));
    }

    @Test
    void nothingHappensWhenNoRuleFiresOrThereAreNoRules() {
        rules();
        service.onRoleChange(guild, member(5, 1), Set.of(1L), true);
        rules(rule(1, Event.GAINS, List.of(1L), List.of(20L), List.of()));
        service.onRoleChange(guild, member(5, 2), Set.of(2L), true);

        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
    }

    @Test
    void aRuleThatAlreadyHasItsResultMakesNoDiscordCall() {
        rules(rule(1, Event.GAINS, List.of(1L), List.of(20L), List.of()));

        service.onRoleChange(guild, member(5, 1, 20), Set.of(1L), true);

        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
    }

    // ---------- the clan sync always wins ----------

    @Test
    void aRuleCannotBeSavedThatGivesOrTakesTheClanSyncRolesOrAStaffRole() {
        Rule givesMember = rule(0, Event.GAINS, List.of(1L), List.of(MEMBER_ROLE), List.of());
        Rule takesMember = rule(0, Event.GAINS, List.of(1L), List.of(), List.of(MEMBER_ROLE));
        Rule givesAdmin = rule(0, Event.GAINS, List.of(1L), List.of(ADMIN_ROLE), List.of());

        assertTrue(service.problemWith(guild, givesMember, List.of()).contains("clan role sync"));
        assertTrue(service.problemWith(guild, takesMember, List.of()).contains("clan role sync"));
        assertTrue(service.problemWith(guild, givesAdmin, List.of()).contains("staff permission"));
    }

    @Test
    void aRuleMayReactToTheClanRoleButNeverChangeIt() {
        // Member gained -> give a chat role: a normal, allowed use
        Rule reacts = rule(0, Event.GAINS, List.of(MEMBER_ROLE), List.of(20L), List.of());

        assertNull(service.problemWith(guild, reacts, List.of()));
    }

    @Test
    void aSavedRuleWhoseTargetBecameTheClanSyncRoleSinceIsRefusedAtRunTime() {
        rules(rule(1, Event.GAINS, List.of(1L), List.of(MEMBER_ROLE), List.of()));

        service.onRoleChange(guild, member(5, 1), Set.of(1L), true);

        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
    }

    // ---------- loops ----------

    @Test
    void aRuleThatWouldCompleteALoopIsRefusedWhenSaving() {
        Rule existing = rule(1, Event.GAINS, List.of(1L), List.of(2L), List.of());
        Rule closing = rule(0, Event.GAINS, List.of(2L), List.of(1L), List.of());

        String problem = service.problemWith(guild, closing, List.of(existing));

        assertNotNull(problem);
        assertTrue(problem.contains("loop"));
    }

    @Test
    void editingARuleIsCheckedWithoutItsOldSelf() {
        Rule existing = rule(1, Event.GAINS, List.of(1L), List.of(2L), List.of());
        Rule edited = rule(1, Event.GAINS, List.of(1L), List.of(3L), List.of());

        assertNull(service.problemWith(guild, edited, List.of(existing)));
    }

    @Test
    void aChainOfRuleMadeChangesIsCutAfterTheLimitEvenIfTheRulesSomehowLoop() {
        // a loop that got past saving (say, edited straight in the database): 1 gives 2, 2 gives 3, 3 gives 1... each change follows the last
        rules(rule(1, Event.GAINS, List.of(1L), List.of(2L), List.of()), rule(2, Event.GAINS, List.of(2L), List.of(3L), List.of()), rule(3, Event.GAINS, List.of(3L), List.of(1L), List.of()),
                rule(4, Event.LOSES, List.of(1L), List.of(), List.of()));
        AuditableRestAction<Void> action = action();
        when(guild.addRoleToMember(any(Member.class), any(Role.class))).thenReturn(action);
        Member m = member(5, 1);

        service.onRoleChange(guild, m, Set.of(1L), true);      // the person's own change: depth 0, makes a change (gives 2)
        service.onRoleChange(guild, member(5, 1, 2), Set.of(2L), true); // follows ours: depth 1 (gives 3)
        service.onRoleChange(guild, member(5, 1, 2, 3), Set.of(3L), true); // depth 2 (would give 1, already held)
        service.onRoleChange(guild, member(5, 2, 3), Set.of(1L), false);

        verify(guild, times(2)).addRoleToMember(any(Member.class), any(Role.class));
    }

    @Test
    void aMemberCannotBeChangedMoreThanTheLimitPerMinute() {
        rules(rule(1, Event.GAINS, List.of(1L), List.of(20L), List.of()));
        AuditableRestAction<Void> action = action();
        when(guild.addRoleToMember(any(Member.class), any(Role.class))).thenReturn(action);

        for (int i = 0; i < RoleRuleRules.MAX_CHANGES_PER_MEMBER_PER_MINUTE + 5; i++) service.onRoleChange(guild, member(5, 1), Set.of(1L), true);
        verify(guild, times(RoleRuleRules.MAX_CHANGES_PER_MEMBER_PER_MINUTE)).addRoleToMember(any(Member.class), any(Role.class));

        now.addAndGet(61_000); // a minute later the member may be changed again
        service.onRoleChange(guild, member(5, 1), Set.of(1L), true);
        verify(guild, times(RoleRuleRules.MAX_CHANGES_PER_MEMBER_PER_MINUTE + 1)).addRoleToMember(any(Member.class), any(Role.class));
    }

    @Test
    void botsAreNeverChangedByRules() {
        rules(rule(1, Event.GAINS, List.of(1L), List.of(20L), List.of()));
        Member bot = member(6, 1);
        when(bot.getUser().isBot()).thenReturn(true);

        service.onRoleChange(guild, bot, Set.of(1L), true);

        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
    }

    @Test
    void theListenerHearsRoleChangesAndNothingElse() {
        for (var method : RoleRuleListener.class.getDeclaredMethods()) {
            String name = method.getName();
            assertTrue(name.equals("onGuildMemberRoleAdd") || name.equals("onGuildMemberRoleRemove") || name.equals("handle"), "unexpected handler " + name);
        }
    }
}
