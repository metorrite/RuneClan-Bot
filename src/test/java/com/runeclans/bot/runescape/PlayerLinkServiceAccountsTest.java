package com.runeclans.bot.runescape;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Linking belongs to JonnyBot, not to a server: what each way of removing or approving a link does to the account. */
class PlayerLinkServiceAccountsTest {
    private static final long HOME = 1L;
    private static final long OTHER = 2L;
    private static final long USER = 100L;

    private PlayerLinkRepository repository;
    private PlayerLinkService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlayerLinkRepository.class);
        service = new PlayerLinkService(repository, HubLinkSync.off());
    }

    private static PlayerLink link(long linkId, long guildId, long userId, String rsn) {
        return new PlayerLink(linkId, guildId, userId, rsn, PlayerLinkService.METHOD_ADMIN_MANUAL, OffsetDateTime.now(), null);
    }

    @Test
    void theOwnerUnlinkingTakesTheAccountOffJonnyBotEverywhere() {
        when(repository.getLinksForUser(HOME, USER)).thenReturn(List.of(link(7, HOME, USER, "Jonny Young")));

        assertTrue(service.unlink(HOME, USER, 7));

        verify(repository).deleteAccount(HOME, 7);
        verify(repository, never()).deleteLink(anyLong(), anyLong());
    }

    @Test
    void someoneCannotUnlinkAnAccountThatIsNotTheirs() {
        when(repository.getLinksForUser(HOME, 999L)).thenReturn(List.of());

        assertFalse(service.unlink(HOME, 999L, 7));

        verify(repository, never()).deleteAccount(anyLong(), anyLong());
    }

    @Test
    void anAdminRemovingALinkOnlyRemovesItFromTheirServer() {
        when(repository.getAllLinks(OTHER)).thenReturn(List.of(link(9, OTHER, USER, "Jonny Young")));

        assertTrue(service.removeFromServer(OTHER, 9));

        verify(repository).deleteLink(OTHER, 9);
        verify(repository, never()).deleteAccount(anyLong(), anyLong());
    }

    @Test
    void anAdminCannotRemoveAnotherServersLink() {
        when(repository.getAllLinks(OTHER)).thenReturn(List.of(link(9, HOME, USER, "Jonny Young")));

        assertFalse(service.removeFromServer(OTHER, 8));

        verify(repository, never()).deleteLink(anyLong(), anyLong());
    }

    @Test
    void approvingCreatesTheLinkBeforeClosingTheRequest() {
        var attempt = new VerificationAttempt(5, HOME, USER, "Jonny Young", "h", "c", "s", "PENDING");
        when(repository.getAttempt(5)).thenReturn(attempt);

        assertTrue(service.approve(5, 200L));

        var order = inOrder(repository);
        order.verify(repository).createLink(HOME, USER, "Jonny Young", PlayerLinkService.METHOD_MAKEOVER_MAGE);
        order.verify(repository).resolveAttempt(5, "APPROVED", 200L);
    }

    @Test
    void aNameOwnedByAnotherAccountLeavesTheRequestPendingInsteadOfHalfApproved() {
        var attempt = new VerificationAttempt(5, OTHER, USER, "Jonny Young", "h", "c", "s", "PENDING");
        when(repository.getAttempt(5)).thenReturn(attempt);
        org.mockito.Mockito.doThrow(new PlayerLinkRepository.RsnTakenException("Jonny Young", 555L))
                .when(repository).createLink(anyLong(), anyLong(), anyString(), anyString());

        var thrown = assertThrows(PlayerLinkRepository.RsnTakenException.class, () -> service.approve(5, 200L));

        assertEquals(555L, thrown.ownerDiscordUserId());
        verify(repository, never()).resolveAttempt(anyLong(), anyString(), anyLong());
    }

    @Test
    void usingJonnyBotInAnotherServerRegistersTheAccountsThere() {
        when(repository.adoptAccounts(OTHER, USER)).thenReturn(List.of("Jonny Young"));

        assertEquals(List.of("Jonny Young"), service.adoptAccounts(OTHER, USER));
    }
}
