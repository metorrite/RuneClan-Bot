package com.runeclans.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code GET /internal/health}: the website's status page check that the bot is answering. It needs the internal API secret like every
 * other internal route, says only {@code {"status":"ok"}} and reads nothing. In the two-process run it is passed on to the gateway process
 * (it is not in {@link GatewayForward#LOCAL}), so a 200 means the whole chain answers.
 */
@BService
public class HealthRoute implements InternalRouteModule {
    static final String PATH = "/internal/health";

    /** The exact path: the server's prefix match also hands over {@code /internal/healthx}, which {@link #handle} refuses. */
    @Override
    public String prefix() {
        return PATH;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        boolean known = PATH.equals(exchange.getRequestURI().getPath());
        boolean get = "GET".equals(exchange.getRequestMethod());
        int status = !known ? 404 : get ? 200 : 405;
        String body = status == 200 ? "{\"status\":\"ok\"}" : status == 404 ? "{\"error\":\"Not found\"}" : "{\"error\":\"Method not allowed\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (status == 405) exchange.getResponseHeaders().set("Allow", "GET");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
