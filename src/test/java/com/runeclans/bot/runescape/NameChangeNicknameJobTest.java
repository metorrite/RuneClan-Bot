package com.runeclans.bot.runescape;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** T-1160: a linked player's nickname follows their name change only where it holds the old name. */
class NameChangeNicknameJobTest {
    @Test
    void theOldNameIsSwappedForTheNewOneAnyCase() {
        assertEquals("Drake | New Name", NameChangeNicknameJob.swapped("Drake | old name", "Old Name", "New Name"));
        assertEquals("New Name", NameChangeNicknameJob.swapped("Old Name", "Old Name", "New Name"));
    }

    @Test
    void aNicknameWithoutTheOldNameIsLeftAlone() {
        assertNull(NameChangeNicknameJob.swapped("Drake", "Old Name", "New Name"));
        assertNull(NameChangeNicknameJob.swapped(null, "Old Name", "New Name"));
    }

    @Test
    void runningTwiceChangesNothingTheSecondTime() {
        String once = NameChangeNicknameJob.swapped("Bob", "Bob", "Bob2");
        assertEquals("Bob2", once);
        assertNull(NameChangeNicknameJob.swapped(once, "Bob", "Bob2"), "already holds the new name");
    }

    @Test
    void aNicknameThatWouldBeTooLongForDiscordIsLeftAlone() {
        assertNull(NameChangeNicknameJob.swapped("The Very Long Prefix Here | Ab", "Ab", "Abcdefghijkl"));
    }
}
