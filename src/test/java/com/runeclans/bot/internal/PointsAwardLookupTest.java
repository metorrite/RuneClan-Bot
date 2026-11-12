package com.runeclans.bot.internal;

import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B-67: dashboard "Give points" finds members the bot doesn't have cached (everyone offline), and says "try again" when Discord is slow. */
class PointsAwardLookupTest {
    private static final long GUILD = 1L, OFFLINE = 812_000_000_000_000_001L;

    private PointsService service;
    private PointsAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        DiscordLookups.forgetAll();
        service = mock(PointsService.class);
        api = new PointsAdminApi(mock(PointsRepository.class), service, mock(RewardsRepository.class));
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getMemberById(OFFLINE)).thenReturn(null); // not in the cache
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(9L);
    }

    @AfterEach
    void tearDown() {
        DiscordLookups.forgetAll();
    }

    @SuppressWarnings("unchecked")
    private void discordAnswers(CompletableFuture<Member> answer) {
        CacheRestAction<Member> action = mock(CacheRestAction.class);
        when(action.submit()).thenReturn(answer);
        when(guild.retrieveMemberById(OFFLINE)).thenReturn(action);
    }

    private DataObject body() {
        return DataObject.empty().put("userId", Long.toString(OFFLINE)).put("points", 5).put("reason", "Helped at a raid");
    }

    @Test
    void anOfflineMemberIsAskedOfDiscordAndGetsThePoints() {
        Member offline = mock(Member.class);
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(OFFLINE);
        when(offline.getUser()).thenReturn(user);
        discordAnswers(CompletableFuture.completedFuture(offline));
        // reaching the award is the point: past the "not in this server" check
        when(service.apply(eq(GUILD), eq(OFFLINE), eq(5L), anyString(), any(), any(), anyLong())).thenThrow(new IllegalStateException("awarded"));

        IllegalStateException reached = assertThrows(IllegalStateException.class, () -> api.award(guild, actor, body()));
        assertEquals("awarded", reached.getMessage());
    }

    @Test
    void aSlowDiscordIsATryAgainNotANotInThisServer() {
        discordAnswers(new CompletableFuture<>()); // never answers
        assertThrows(DiscordLookups.Slow.class, () -> api.award(guild, actor, body()));
    }
}
