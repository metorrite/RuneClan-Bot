package com.runeclans.bot.internal;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code GET /internal/health}, the website status page's bot check. */
class HealthRouteTest {
    private static HttpResponse<String> call(String method, String path) throws Exception {
        HealthRoute route = new HealthRoute();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(route.prefix(), route::handle);
        server.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getAnswersOk() throws Exception {
        HttpResponse<String> r = call("GET", "/internal/health");
        assertEquals(200, r.statusCode());
        assertEquals("{\"status\":\"ok\"}", r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void otherMethodsAndLongerPathsAreRefused() throws Exception {
        assertEquals(405, call("POST", "/internal/health").statusCode());
        assertEquals(404, call("GET", "/internal/healthx").statusCode());
        assertEquals(404, call("GET", "/internal/health/deep").statusCode());
    }

    @Test
    void needsTheInternalSecret() {
        HealthRoute route = new HealthRoute();
        com.sun.net.httpserver.HttpExchange withSecret = exchange("s".repeat(32));
        com.sun.net.httpserver.HttpExchange wrong = exchange("x".repeat(32));
        com.sun.net.httpserver.HttpExchange none = exchange(null);
        assertTrue(route.authorized(withSecret, "s".repeat(32)));
        assertFalse(route.authorized(wrong, "s".repeat(32)));
        assertFalse(route.authorized(none, "s".repeat(32)));
    }

    @Test
    void passedOnToTheGatewayInTheTwoProcessRun() {
        assertFalse(GatewayForward.local(HealthRoute.PATH));
    }

    private static com.sun.net.httpserver.HttpExchange exchange(String secret) {
        com.sun.net.httpserver.HttpExchange exchange = mock(com.sun.net.httpserver.HttpExchange.class);
        com.sun.net.httpserver.Headers headers = new com.sun.net.httpserver.Headers();
        if (secret != null) headers.add("X-Internal-Secret", secret);
        when(exchange.getRequestHeaders()).thenReturn(headers);
        return exchange;
    }
}
