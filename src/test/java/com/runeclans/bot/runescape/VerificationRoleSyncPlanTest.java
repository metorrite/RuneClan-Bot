package com.runeclans.bot.runescape;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Guest/Member swap decision, including the case where one role is both "unverified" and "verified (not a clan member)". */
class VerificationRoleSyncPlanTest {
    private static final long GUEST = 1, MEMBER = 2, OTHER = 3, GONE = 99;

    private static VerificationRoleSyncService.RolePlan plan(Long verified, Long unverified, Set<Long> held) {
        return VerificationRoleSyncService.plan(verified, unverified, held, id -> id != GONE);
    }

    @Test
    void aClanMemberTradesGuestForMember() {
        var plan = plan(MEMBER, GUEST, Set.of(GUEST));
        assertEquals(Set.of(MEMBER), plan.add());
        assertEquals(Set.of(GUEST), plan.remove());
    }

    @Test
    void aNonClanPlayerKeepsGuestWhenGuestIsBothTheVerifiedAndTheUnverifiedRole() {
        var plan = plan(GUEST, GUEST, Set.of(GUEST));
        assertEquals(Set.of(), plan.add());
        assertEquals(Set.of(), plan.remove());
    }

    @Test
    void aNonClanPlayerWhoLacksGuestIsGivenIt() {
        var plan = plan(GUEST, GUEST, Set.of());
        assertEquals(Set.of(GUEST), plan.add());
        assertEquals(Set.of(), plan.remove());
    }

    @Test
    void nothingChangesWhenTheMemberAlreadyHasTheRightRoles() {
        var plan = plan(MEMBER, GUEST, Set.of(MEMBER));
        assertEquals(Set.of(), plan.add());
        assertEquals(Set.of(), plan.remove());
    }

    @Test
    void unrelatedRolesAreNeverTouched() {
        var plan = plan(MEMBER, GUEST, Set.of(GUEST, OTHER));
        assertEquals(Set.of(MEMBER), plan.add());
        assertEquals(Set.of(GUEST), plan.remove());
    }

    @Test
    void unsetSlotsAreSkipped() {
        assertEquals(Set.of(MEMBER), plan(MEMBER, null, Set.of()).add());
        assertEquals(Set.of(GUEST), plan(null, GUEST, Set.of(GUEST)).remove());
        var none = plan(null, null, Set.of(GUEST));
        assertEquals(Set.of(), none.add());
        assertEquals(Set.of(), none.remove());
    }

    private static VerificationRoleSyncService.RolePlan leave(Long clan, Long nonClan, Set<Long> held) {
        return VerificationRoleSyncService.planLeave(clan, nonClan, held, id -> id != GONE);
    }

    @Test
    void aPlayerWhoLeavesTheClanTradesMemberForGuest() {
        var plan = leave(MEMBER, GUEST, Set.of(MEMBER, OTHER));
        assertEquals(Set.of(GUEST), plan.add());
        assertEquals(Set.of(MEMBER), plan.remove());
    }

    @Test
    void aPlayerWhoLeavesButAlreadyHasGuestOnlyLosesMember() {
        var plan = leave(MEMBER, GUEST, Set.of(MEMBER, GUEST));
        assertEquals(Set.of(), plan.add());
        assertEquals(Set.of(MEMBER), plan.remove());
    }

    @Test
    void leavingChangesNothingForSomeoneWhoWasNeverAMember() {
        var plan = leave(MEMBER, GUEST, Set.of(GUEST));
        assertEquals(Set.of(), plan.add());
        assertEquals(Set.of(), plan.remove());
    }

    @Test
    void leavingNeverStripsARoleThatIsAlsoTheGuestRole() {
        var plan = leave(GUEST, GUEST, Set.of(GUEST));
        assertEquals(Set.of(), plan.add());
        assertEquals(Set.of(), plan.remove());
    }

    @Test
    void leavingNeverTakesMemberAwayWithoutAGuestRoleToPutInItsPlace() {
        assertEquals(Set.of(), leave(MEMBER, null, Set.of(MEMBER)).remove());
        assertEquals(Set.of(), leave(null, GUEST, Set.of(MEMBER)).remove());

        var guestGone = leave(MEMBER, GONE, Set.of(MEMBER));
        assertEquals(Set.of(GONE), guestGone.missing());
        assertEquals(Set.of(), guestGone.add());
        assertEquals(Set.of(), guestGone.remove(), "Member stays rather than leaving the player with no role");
    }

    @Test
    void aDeletedMemberRoleIsReportedButGuestIsStillGiven() {
        var plan = leave(GONE, GUEST, Set.of());
        assertEquals(Set.of(GONE), plan.missing());
        assertEquals(Set.of(GUEST), plan.add());
    }

    @Test
    void aDeletedRoleIsReportedAndSkippedNeverGuessed() {
        var plan = plan(GONE, GUEST, Set.of(GUEST));
        assertEquals(Set.of(GONE), plan.missing());
        assertEquals(Set.of(), plan.add());
        assertEquals(Set.of(GUEST), plan.remove(), "the other slot still works");

        var gone2 = plan(MEMBER, GONE, Set.of(GONE));
        assertEquals(Set.of(GONE), gone2.missing());
        assertEquals(Set.of(MEMBER), gone2.add());
        assertEquals(Set.of(), gone2.remove());
    }

    // ---------- a saved role the bot won't hand out any more (B-55) ----------

    @Test
    void aRefusedVerifiedRoleIsNotGivenAndTheUnverifiedRoleIsKept() {
        var plan = VerificationRoleSyncService.plan(MEMBER, GUEST, Set.of(GUEST), id -> true, id -> id != MEMBER);
        assertEquals(Set.of(), plan.add());
        assertEquals(Set.of(), plan.remove(), "taking Guest away with nothing in its place would leave them with neither");
        assertEquals(Set.of(MEMBER), plan.refused());
    }

    @Test
    void aRefusedRoleTheMemberAlreadyHoldsStillLetsTheSwapFinish() {
        var plan = VerificationRoleSyncService.plan(MEMBER, GUEST, Set.of(MEMBER, GUEST), id -> true, id -> id != MEMBER);
        assertEquals(Set.of(GUEST), plan.remove());
        assertEquals(Set.of(), plan.refused());
    }

    @Test
    void leavingTheClanWithARefusedGuestRoleKeepsTheMemberRole() {
        var plan = VerificationRoleSyncService.planLeave(MEMBER, GUEST, Set.of(MEMBER), id -> true, id -> id != GUEST);
        assertEquals(Set.of(), plan.add());
        assertEquals(Set.of(), plan.remove());
        assertEquals(Set.of(GUEST), plan.refused());
    }
}
