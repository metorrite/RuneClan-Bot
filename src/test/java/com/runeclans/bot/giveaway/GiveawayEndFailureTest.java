package com.runeclans.bot.giveaway;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ending a giveaway when something fails (bug hunt B-48 review): a draw that fails leaves the giveaway open for the sweep to try again, the
 * sweep leaves a giveaway someone else just ended alone, and one giveaway that can't be ended doesn't stop the rest of the batch.
 */
class GiveawayEndFailureTest {
    private static final long GUILD = 1L;

    private GiveawayRepository repository;
    private GiveawayService service;
    private Guild guild;
    private JDA jda;

    @BeforeEach
    void setUp() {
        repository = mock(GiveawayRepository.class);
        service = new GiveawayService(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        jda = mock(JDA.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
    }

    private static Giveaway open(long id) {
        return new Giveaway(id, GUILD, 2L, 3L, "1m gp", 4L, 1, Instant.now().minusSeconds(1), null, Giveaway.Status.OPEN, List.of(), null, false, false);
    }

    @Test
    void aDrawThatFailsLeavesTheGiveawayOpen() {
        when(repository.entrants(7L)).thenThrow(new RuntimeException("database down"));
        assertThrows(RuntimeException.class, () -> service.end(guild, open(7L)));
        verify(repository, never()).finishIfOpen(anyLong(), any());
    }

    @Test
    void theSweepLeavesAGiveawayEndedElsewhereAlone() {
        when(repository.due(any(), anyInt())).thenReturn(List.of(open(7L)));
        when(repository.entrants(7L)).thenReturn(List.of());
        when(repository.finishIfOpen(7L, List.of())).thenReturn(false); // End now got there first

        service.sweep(jda);

        verify(repository).finishIfOpen(7L, List.of()); // the one end attempt, nothing after it
        verify(repository, never()).get(anyLong(), eq(7L));
    }

    @Test
    void oneGiveawayThatCantBeEndedDoesntStopTheBatch() {
        when(repository.due(any(), anyInt())).thenReturn(List.of(open(7L), open(8L)));
        when(repository.entrants(7L)).thenThrow(new RuntimeException("database down"));
        when(repository.finishIfOpen(7L, List.of())).thenThrow(new RuntimeException("still down"));
        when(repository.entrants(8L)).thenReturn(List.of());
        when(repository.finishIfOpen(8L, List.of())).thenReturn(true);
        when(repository.get(GUILD, 8L)).thenReturn(java.util.Optional.of(open(8L)));

        service.sweep(jda);

        verify(repository).finishIfOpen(8L, List.of());
    }
}
