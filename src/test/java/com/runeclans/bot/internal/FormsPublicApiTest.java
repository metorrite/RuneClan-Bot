package com.runeclans.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.forms.WebFormRepository;
import com.runeclans.bot.forms.WebFormService;
import com.runeclans.bot.forms.WebForms.Form;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The public form page asks whether the person is in the server; a slow Discord must say "try again", never "join the server first". */
class FormsPublicApiTest {
    private static final long GUILD = 812_000_000_000_000_001L;
    private static final long FORM = 5;
    private static final long USER = 812_000_000_000_000_777L;

    private JDA jda;
    private Guild guild;
    private FormsPublicApi api;

    @BeforeEach
    void setUp() {
        DiscordLookups.forgetAll();
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn(Long.toString(GUILD));
        when(guild.getName()).thenReturn("Clan");
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        WebFormRepository repository = mock(WebFormRepository.class);
        when(repository.get(FORM)).thenReturn(Optional.of(new Form(FORM, GUILD, "apply", "Apply", "", true, null, null, null, false, 0, List.of())));
        api = new FormsPublicApi(repository, mock(WebFormService.class), com.runeclans.bot.hubcommunity.HubCommunity.off());
    }

    @AfterEach
    void tearDown() {
        DiscordLookups.forgetAll();
    }

    record Reply(int status, DataObject body) {}

    private Reply get() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] status = {-1};
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/internal/forms/" + GUILD + "/" + FORM + "?userId=" + USER));
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(out);
        doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(any(Integer.class), any(Long.class));
        api.handle(exchange, jda);
        String text = out.toString(StandardCharsets.UTF_8);
        return new Reply(status[0], text.isBlank() ? DataObject.empty() : DataObject.fromJson(text));
    }

    @SuppressWarnings("unchecked")
    private void discordAnswers(CompletableFuture<Member> future) {
        CacheRestAction<Member> action = mock(CacheRestAction.class);
        when(action.submit()).thenReturn(future);
        when(guild.retrieveMemberById(USER)).thenReturn(action);
    }

    @Test
    void whenDiscordHangsTheFormAnswers503Quickly() throws Exception {
        discordAnswers(new CompletableFuture<>());

        long started = System.nanoTime();
        Reply r = get();
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(503, r.status());
        assertEquals("DISCORD_SLOW", r.body().getString("code"));
        assertTrue(tookMs < 3_000, "took " + tookMs + " ms");
    }

    @Test
    void aMemberDiscordFindsMaySendTheForm() throws Exception {
        Member member = mock(Member.class);
        when(member.getUser()).thenReturn(mock(net.dv8tion.jda.api.entities.User.class));
        discordAnswers(CompletableFuture.completedFuture(member));

        Reply r = get();

        assertEquals(200, r.status());
        assertTrue(r.body().getBoolean("canSubmit"));
    }

    @Test
    void someoneDiscordSaysIsNotInTheServerIsToldToJoin() throws Exception {
        net.dv8tion.jda.api.exceptions.ErrorResponseException unknown = mock(net.dv8tion.jda.api.exceptions.ErrorResponseException.class);
        when(unknown.getErrorResponse()).thenReturn(net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MEMBER);
        discordAnswers(CompletableFuture.failedFuture(unknown));

        Reply r = get();

        assertEquals(200, r.status());
        assertFalse(r.body().getBoolean("canSubmit"));
    }
}
