package com.runeclans.bot.commands.signup;

import com.runeclans.bot.signup.SignupRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SignupService keeps its boot-loaded signups only as a cache: a signup another process made (the website) is read from the database on a
 * miss, so its buttons work without a restart. A miss that finds nothing is remembered briefly; a failed read is not remembered.
 */
class SignupLoadThroughTest {
    private static final long GUILD = 1L, USER = 11L;

    private static SignupSession signup(long id) {
        return new SignupSession(id, GUILD, "Signup " + id, null, null, SignupType.QUEUE, null, null);
    }

    private static SignupRepository emptyRepository() {
        SignupRepository repository = mock(SignupRepository.class);
        when(repository.getOpenSignups()).thenReturn(List.of());
        return repository;
    }

    @Test
    void aSignupMadeElsewhereIsReadOnceAndThenServedFromTheCache() {
        SignupRepository repository = emptyRepository();
        SignupService service = new SignupService(repository);
        when(repository.getOpenSignupById(7)).thenReturn(signup(7));

        assertEquals("Signup 7", service.getSessionFromComponentId("signup_join:7").title());
        assertEquals("Signup 7", service.getSessionById(7).title());
        verify(repository, times(1)).getOpenSignupById(7);
    }

    @Test
    void aDeletedOrUnknownSignupMissIsRememberedBriefly() {
        SignupRepository repository = emptyRepository();
        SignupService service = new SignupService(repository);

        assertNull(service.getSessionById(8));
        assertNull(service.getSessionById(8));
        verify(repository, times(1)).getOpenSignupById(8);
    }

    @Test
    void aFailedReadReturnsNothingAndIsTriedAgainNextTime() {
        SignupRepository repository = emptyRepository();
        SignupService service = new SignupService(repository);
        when(repository.getOpenSignupById(anyLong())).thenThrow(new RuntimeException("database down"));

        assertNull(service.getSessionById(7));
        assertNull(service.getSessionById(7));
        verify(repository, times(2)).getOpenSignupById(7);
    }

    @Test
    void joiningASignupMadeElsewhereWorks() {
        SignupRepository repository = emptyRepository();
        SignupService service = new SignupService(repository);
        when(repository.getOpenSignupById(7)).thenReturn(signup(7));
        when(repository.getSignupStatus(7)).thenReturn("ACTIVE");
        when(repository.addEntry(7, USER, "Some Rsn", null, USER, SignupType.QUEUE, null)).thenReturn(true);

        assertTrue(service.addUser(null, 7, USER, "Some Rsn", null));
    }

    @Test
    void theListWarmsTheCacheSoTheLookupsAfterItDoNotQueryOneByOne() {
        SignupRepository repository = mock(SignupRepository.class);
        when(repository.getOpenSignups()).thenReturn(List.of(signup(3), signup(4)));
        SignupService service = new SignupService(repository);
        // signup 4 was deleted on another process; signup 5 was made there
        when(repository.getVisibleSignups(GUILD)).thenReturn(List.of(signup(3), signup(5)));

        assertEquals(2, service.getVisibleSignups(GUILD).size());
        assertNotNull(service.getSessionById(5));
        assertNull(service.getSessionById(4), "the deleted signup is no longer served from the cache");
        verify(repository, times(0)).getOpenSignupById(5);
    }
}
