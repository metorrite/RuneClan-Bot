package com.runeclans.bot.commands.signup;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
