package com.runeclans.bot.runescape;

import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.runescape.RoleSyncBackfill.Action;
import com.runeclans.bot.runescape.RoleSyncBackfill.Entry;
import com.runeclans.bot.runescape.RoleSyncBackfill.Plan;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.utils.concurrent.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mocks only: no Discord, so no real member's role can be touched. */
class RoleSyncBackfillTest {
    private static final long GUILD = 10;
    private static final long MEMBER = 1;
    private static final long GUEST = 2;
    private static final long UNVERIFIED = 3;
    private static final long OTHER = 99;

    private GuildSettingsService settings;
    private ClanMemberRepository clan;
    private PlayerLinkService links;
    private RoleSyncBackfill backfill;
    private com.runeclans.bot.jobs.JobRepository jobs;
    private MovableClock clock;

    /** A clock the test can move forward (the job table's stale window). */
    private static final class MovableClock extends java.time.Clock {
        private volatile java.time.Instant now = java.time.Instant.now();

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }
    private Guild guild;
    private Role member;
    private Role guest;
    private Role unverified;

    @BeforeEach
    void setUp() {
        settings = mock(GuildSettingsService.class);
        clan = mock(ClanMemberRepository.class);
        links = mock(PlayerLinkService.class);
        clock = new MovableClock();
        jobs = com.runeclans.bot.jobs.JobRepository.inMemory(clock);
        backfill = new RoleSyncBackfill(settings, clan, links, jobs);
        backfill.pauseMillis = 0;
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SelfMember self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.canInteract(any(Member.class))).thenReturn(true);
        when(self.canInteract(any(Role.class))).thenReturn(true); // ordinary roles the bot may hand out (MemberApi.safeToAssign)
        member = role(MEMBER);
        guest = role(GUEST);
        unverified = role(UNVERIFIED);
        when(guild.getRoleById(MEMBER)).thenReturn(member);
        when(guild.getRoleById(GUEST)).thenReturn(guest);
        when(guild.getRoleById(UNVERIFIED)).thenReturn(unverified);
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, "Clan", null, null, null, MEMBER, GUEST, UNVERIFIED, null, true, null, null, null));
    }

    private Role role(long id) {
        Role r = mock(Role.class);
        when(r.getIdLong()).thenReturn(id);
        when(r.getPermissions()).thenReturn(java.util.EnumSet.noneOf(net.dv8tion.jda.api.Permission.class));
        return r;
    }

    private static PlayerLink link(long user, String rsn) {
        return new PlayerLink(user * 10, GUILD, user, rsn, "ADMIN", OffsetDateTime.now(), null);
    }

    private Member discord(long id, String name, Role... roles) {
        Member m = mock(Member.class);
        User u = mock(User.class);
        when(m.getUser()).thenReturn(u);
        when(m.getIdLong()).thenReturn(id);
        when(m.getEffectiveName()).thenReturn(name);
        when(m.getRoles()).thenReturn(List.of(roles));
        return m;
    }

    @SuppressWarnings("unchecked")
    private void members(Member... found) {
        Task<List<Member>> task = mock(Task.class);
        when(task.setTimeout(any(java.time.Duration.class))).thenReturn(task);
        try {
            when(task.get()).thenReturn(List.of(found));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(guild.retrieveMembersByIds(any(Collection.class))).thenReturn(task);
    }

    // ---------- the decision ----------

    @Test
    void aClanMemberStillGuestIsPromotedAndSwappedOffGuestAndUnverified() {
        Optional<Entry> entry = RoleSyncBackfill.decide(5, "Bob", "bob", true, Set.of(GUEST, UNVERIFIED, OTHER), MEMBER, GUEST, UNVERIFIED, id -> true);

        assertEquals(Action.PROMOTE, entry.orElseThrow().action());
        assertEquals(Set.of(MEMBER), entry.get().add());
        assertEquals(Set.of(GUEST, UNVERIFIED), entry.get().remove());
    }

    @Test
    void aClanMemberWhoAlreadyHasTheMemberRoleIsLeftAlone() {
        assertTrue(RoleSyncBackfill.decide(5, "Bob", "bob", true, Set.of(MEMBER), MEMBER, GUEST, UNVERIFIED, id -> true).isEmpty());
    }

    @Test
    void someoneWhoLeftTheClanButStillHoldsMemberIsDemotedToGuest() {
        Entry entry = RoleSyncBackfill.decide(5, "Bob", "bob", false, Set.of(MEMBER, OTHER), MEMBER, GUEST, UNVERIFIED, id -> true).orElseThrow();

        assertEquals(Action.DEMOTE, entry.action());
        assertEquals(Set.of(GUEST), entry.add());
        assertEquals(Set.of(MEMBER), entry.remove());
    }

    @Test
    void aDemotionNeedsBothRolesSetUpAndNeverHappensToSomeoneWithoutMember() {
        assertTrue(RoleSyncBackfill.decide(5, "Bob", "bob", false, Set.of(MEMBER), MEMBER, null, UNVERIFIED, id -> true).isEmpty(), "no Guest role to put them in");
        assertTrue(RoleSyncBackfill.decide(5, "Bob", "bob", false, Set.of(GUEST), MEMBER, GUEST, UNVERIFIED, id -> true).isEmpty(), "already not a member");
        assertTrue(RoleSyncBackfill.decide(5, "Bob", "bob", false, Set.of(MEMBER), MEMBER, MEMBER, UNVERIFIED, id -> true).isEmpty(), "Member and Guest are the same role");
    }

    @Test
    void aRoleThatNoLongerExistsIsNeverUsed() {
        assertTrue(RoleSyncBackfill.decide(5, "Bob", "bob", true, Set.of(GUEST), MEMBER, GUEST, UNVERIFIED, id -> id != MEMBER).isEmpty());
    }

    // ---------- the dry run ----------

    @Test
    void theDryRunListsPromotionsAndDemotionsAndChangesNobody() {
        when(links.getAllLinks(GUILD)).thenReturn(List.of(link(5, "bob"), link(6, "carol"), link(7, "dave"), link(8, "ghost")));
        when(clan.isActiveMember(GUILD, "bob")).thenReturn(true);
        when(clan.isActiveMember(GUILD, "carol")).thenReturn(false);
        when(clan.isActiveMember(GUILD, "dave")).thenReturn(true);
        members(discord(5, "Bob", guest), discord(6, "Carol", member), discord(7, "Dave", member));

        Plan plan = backfill.plan(guild);

        assertNull(plan.problem());
        assertEquals(2, plan.entries().size());
        assertEquals(Action.PROMOTE, plan.entries().get(0).action(), "promotions are listed first");
        assertEquals(Action.DEMOTE, plan.entries().get(1).action());
        assertEquals(1, plan.notInServer(), "ghost is not in the server");
        verify(guild, never()).modifyMemberRoles(any(Member.class), any(Collection.class), any(Collection.class));
        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
        verify(guild, never()).removeRoleFromMember(any(Member.class), any(Role.class));
    }

    @Test
    void aMemberRoleTheBotWontHandOutStopsTheFixBeforeAnyoneChanges() {
        when(member.getPermissions()).thenReturn(java.util.EnumSet.of(net.dv8tion.jda.api.Permission.ADMINISTRATOR));
        when(links.getAllLinks(GUILD)).thenReturn(List.of(link(5, "bob")));
        when(clan.isActiveMember(GUILD, "bob")).thenReturn(true);
        members(discord(5, "Bob", guest));

        Plan plan = backfill.plan(guild);

        assertNotNull(plan.problem());
        assertTrue(plan.problem().contains("powerful permissions"), plan.problem());
        assertTrue(plan.entries().isEmpty());
        assertNotNull(backfill.start(guild, Set.of(5L), 1), "nothing to start");
        verify(guild, never()).modifyMemberRoles(any(Member.class), any(Collection.class), any(Collection.class));
    }

    @Test
    void withoutAMemberRoleThereIsNothingToPlan() {
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, "Clan", null, null, null, null, GUEST, UNVERIFIED, null, true, null, null, null));

        Plan plan = backfill.plan(guild);

        assertNotNull(plan.problem());
        assertTrue(plan.entries().isEmpty());
    }

    // ---------- the button ----------

    @Test
    void applyingChangesOnlyThePeopleSelectedThatAFreshPlanStillWants() throws Exception {
        when(links.getAllLinks(GUILD)).thenReturn(List.of(link(5, "bob"), link(6, "carol")));
        when(clan.isActiveMember(GUILD, "bob")).thenReturn(true);
        when(clan.isActiveMember(GUILD, "carol")).thenReturn(true);
        Member bob = discord(5, "Bob", guest);
        Member carol = discord(6, "Carol", guest);
        members(bob, carol);
        stubLookupAndModify(bob);

        String refusal = backfill.start(guild, Set.of(5L, 777L), 1);
        waitUntilDone();

        assertNull(refusal);
        verify(guild, times(1)).modifyMemberRoles(any(Member.class), any(Collection.class), any(Collection.class));
        verify(guild).modifyMemberRoles(org.mockito.ArgumentMatchers.eq(bob), org.mockito.ArgumentMatchers.argThat((Collection<Role> add) -> add.contains(member)),
                org.mockito.ArgumentMatchers.argThat((Collection<Role> remove) -> remove.contains(guest)));
        assertEquals(1, backfill.progress(GUILD).done());
    }

    /** T-698: the run's progress is in the shared job table, so another bot process shows it and won't start a second run. */
    @Test
    void anotherProcessSeesTheRunAndWillNotStartASecondOne() throws Exception {
        when(links.getAllLinks(GUILD)).thenReturn(List.of(link(5, "bob")));
        when(clan.isActiveMember(GUILD, "bob")).thenReturn(true);
        Member bob = discord(5, "Bob", guest);
        members(bob);
        stubLookupAndModify(bob);
        RoleSyncBackfill elsewhere = new RoleSyncBackfill(settings, clan, links, jobs);
        backfill.pauseMillis = 300; // keeps the run going while the other process looks

        assertNull(backfill.start(guild, Set.of(5L), 1));
        assertTrue(elsewhere.progress(GUILD).running());
        assertEquals("A fix is already running in this server.", elsewhere.start(guild, Set.of(5L), 1));
        waitUntilDone();
        assertEquals(1, elsewhere.progress(GUILD).done());
        assertFalse(elsewhere.progress(GUILD).running());
    }

    /** T-698: a run that went quiet and was taken over stops before it changes anyone else. */
    @Test
    void aRunTakenOverByAnotherStopsBeforeTheNextPerson() throws Exception {
        when(links.getAllLinks(GUILD)).thenReturn(List.of(link(5, "bob"), link(6, "carol")));
        when(clan.isActiveMember(GUILD, "bob")).thenReturn(true);
        when(clan.isActiveMember(GUILD, "carol")).thenReturn(true);
        Member bob = discord(5, "Bob", guest);
        Member carol = discord(6, "Carol", guest);
        members(bob, carol);
        stubLookupAndModify(bob);
        stubLookupAndModify(carol);
        backfill.pauseMillis = 400;

        assertNull(backfill.start(guild, Set.of(5L, 6L), 1));
        for (int i = 0; i < 100 && backfill.progress(GUILD).done() < 1; i++) Thread.sleep(10);
        clock.now = clock.now.plus(com.runeclans.bot.jobs.JobRepository.STALE).plusSeconds(1); // it went quiet
        String taker = jobs.claim(com.runeclans.bot.jobs.JobRepository.ROLE_FIX, GUILD, "role-fix").row().runId();
        Thread.sleep(800); // past the pause, when it would have changed Carol

        verify(guild, times(1)).modifyMemberRoles(any(Member.class), any(Collection.class), any(Collection.class));
        assertEquals(taker, jobs.latest(com.runeclans.bot.jobs.JobRepository.ROLE_FIX, GUILD).orElseThrow().runId(), "the old run didn't overwrite the new one");
    }

    @Test
    void whenTheDatabaseCannotSayARunIsGoingNoneIsStarted() {
        com.runeclans.bot.jobs.JobRepository down = mock(com.runeclans.bot.jobs.JobRepository.class);
        when(down.latest(anyString(), anyLong())).thenThrow(new IllegalStateException("down"));
        RoleSyncBackfill blind = new RoleSyncBackfill(settings, clan, links, down);
        assertEquals("The bot could not reach its database just now. Try again in a moment.", blind.start(guild, Set.of(5L), 1));
        verify(links, never()).getAllLinks(anyLong());
    }

    @Test
    void someoneWhoWasSelectedButNoLongerNeedsAChangeIsNotTouched() {
        when(links.getAllLinks(GUILD)).thenReturn(List.of(link(5, "bob")));
        when(clan.isActiveMember(GUILD, "bob")).thenReturn(true);
        members(discord(5, "Bob", member)); // fixed by hand since the dry run

        String refusal = backfill.start(guild, Set.of(5L), 1);

        assertNotNull(refusal);
        verify(guild, never()).modifyMemberRoles(any(Member.class), any(Collection.class), any(Collection.class));
    }

    @Test
    void anEmptyOrTooLargeSelectionIsRefusedWithoutLookingAnyoneUp() {
        assertNotNull(backfill.start(guild, Set.of(), 1));
        java.util.Set<Long> many = new java.util.HashSet<>();
        for (long i = 0; i <= RoleSyncBackfill.MAX_PER_RUN; i++) many.add(i);
        assertNotNull(backfill.start(guild, many, 1));
        verify(links, never()).getAllLinks(anyLong());
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private void stubLookupAndModify(Member who) {
        CacheRestAction<Member> lookup = mock(CacheRestAction.class);
        when(lookup.complete()).thenReturn(who);
        when(guild.retrieveMemberById(who.getIdLong())).thenReturn(lookup);
        AuditableRestAction<Void> modify = mock(AuditableRestAction.class);
        when(modify.reason(anyString())).thenReturn(modify);
        when(guild.modifyMemberRoles(any(Member.class), any(Collection.class), any(Collection.class))).thenReturn(modify);
    }

    private void waitUntilDone() throws InterruptedException {
        for (int i = 0; i < 100 && backfill.progress(GUILD).running(); i++) Thread.sleep(20);
    }
}
