package com.runeclans.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.forms.WebFormRepository;
import com.runeclans.bot.forms.WebFormService;
import com.runeclans.bot.forms.WebForms;
import com.runeclans.bot.forms.WebForms.Form;
import com.runeclans.bot.forms.WebForms.Question;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-590: a clan site's application form widget sends its answers to the clan's application web form ({@code POST .../site-application}). The
 * route reads the site's own questions as labels, drops blank ones, keeps the order, passes the site's address on, and never runs for a form
 * that is switched off. {@link WebForms#asSiteForm} keeps everything about the form but its questions.
 */
class FormsSiteApplicationTest {
    // fake ids, made at run time
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long USER = Long.parseLong("8".repeat(18));
    private static final long FORM = 9;
    private static final long CLOSED_FORM = 10;

    private JDA jda;
    private WebFormService service;
    private FormsPublicApi api;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn(Long.toString(GUILD));
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        WebFormRepository repository = mock(WebFormRepository.class);
        when(repository.get(FORM)).thenReturn(Optional.of(new Form(FORM, GUILD, "apply", "Apply", "", true, null, 1L, null, false, 0, List.of())));
        when(repository.get(CLOSED_FORM)).thenReturn(Optional.of(new Form(CLOSED_FORM, GUILD, "old", "Old", "", false, null, 1L, null, false, 0, List.of())));
        service = mock(WebFormService.class);
        when(service.submitFromSite(any(), anyLong(), anyLong(), anyList(), anyList(), any())).thenReturn(new WebFormService.Outcome(1, false, List.of("A note.")));
        api = new FormsPublicApi(repository, service, com.runeclans.bot.hubcommunity.HubCommunity.off());
    }

    private record Reply(int status, DataObject body) {}

    private Reply post(long formId, DataObject body) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] status = {-1};
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/internal/forms/" + GUILD + "/" + formId + "/site-application"));
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)));
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

    private static DataObject answer(String label, String value) {
        return DataObject.empty().put("label", label).put("value", value);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsTheSitesLabelsAndAnswersInOrder() throws Exception {
        DataArray answers = DataArray.empty().add(answer("RuneScape name", "Zezima")).add(answer("   ", "dropped")).add(answer("Why join?", "Friends"));
        DataObject body = DataObject.empty().put("userId", Long.toString(USER)).put("site", "iron-legion").put("answers", answers);
        Reply reply = post(FORM, body);
        assertEquals(200, reply.status(), reply.body().toString());
        assertEquals(List.of("A note."), reply.body().getArray("notes").toList());
        org.mockito.ArgumentCaptor<List<String>> labels = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<List<String>> values = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(service).submitFromSite(any(), eq(USER), eq(FORM), labels.capture(), values.capture(), eq("iron-legion"));
        assertEquals(List.of("RuneScape name", "Why join?"), labels.getValue());
        assertEquals(List.of("Zezima", "Friends"), values.getValue());
    }

    @Test
    void aBadSiteAddressIsLeftOutAndAClosedFormRefuses() throws Exception {
        DataObject body = DataObject.empty().put("userId", Long.toString(USER)).put("site", "<b>not an address</b>").put("answers", DataArray.empty().add(answer("Name", "Zezima")));
        assertEquals(200, post(FORM, body).status());
        verify(service).submitFromSite(any(), eq(USER), eq(FORM), anyList(), anyList(), eq(null));

        Reply closed = post(CLOSED_FORM, body);
        assertEquals(404, closed.status());
        verify(service, never()).submitFromSite(any(), anyLong(), eq(CLOSED_FORM), anyList(), anyList(), any());
    }

    @Test
    void asSiteFormKeepsTheFormButAsksTheSitesQuestions() {
        Form saved = new Form(FORM, GUILD, "apply", "Apply", "Text", true, 3L, 4L, 5L, true, 48,
                List.of(new Question("rsn", "RuneScape name", WebForms.Kind.RSN, true, List.of(), 12)));
        List<String> labels = new ArrayList<>(List.of("Name", "x".repeat(WebForms.MAX_LABEL + 10)));
        for (int i = 0; i < 20; i++) labels.add("More " + i);
        Form asked = WebForms.asSiteForm(saved, labels);
        assertEquals(WebForms.MAX_SITE_ANSWERS, asked.questions().size(), "at most as many as a form may have");
        assertEquals(WebForms.MAX_LABEL, asked.questions().get(1).label().length());
        assertEquals("q1", asked.questions().getFirst().id());
        assertTrue(asked.questions().stream().allMatch(q -> q.kind() == WebForms.Kind.PARAGRAPH && !q.required()));
        assertTrue(WebForms.problemsWith(asked).isEmpty(), WebForms.problemsWith(asked).toString());
        assertEquals(List.of(saved.panelId(), saved.channelId(), saved.roleId(), saved.cooldownHours(), saved.onePerUser()),
                List.of(asked.panelId(), asked.channelId(), asked.roleId(), asked.cooldownHours(), asked.onePerUser()));

        // the bot's own check still applies to what the site sent: too long is refused, control characters go
        var checked = WebForms.check(WebForms.asSiteForm(saved, List.of("Name")), Map.of("q1", "Zez\u0007ima"));
        assertTrue(checked.ok());
        assertEquals("Zezima", checked.answers().getFirst().value());
        assertTrue(!WebForms.check(WebForms.asSiteForm(saved, List.of("Name")), Map.of("q1", "x".repeat(WebForms.MAX_ANSWER_LENGTH + 1))).ok());
    }
}
