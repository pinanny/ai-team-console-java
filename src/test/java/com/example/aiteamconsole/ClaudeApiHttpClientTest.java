package com.example.aiteamconsole;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeApiHttpClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsExpectedJsonShapeAndParsesTextBlock() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(exchange.getRequestHeaders().getFirst("x-api-key").contains("sk-test"));
            assertEquals("2023-06-01", exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] response = """
                    {"content":[{"type":"text","text":"hello from claude"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String port = String.valueOf(server.getAddress().getPort());
        ClaudeApiHttpClient client = new ClaudeApiHttpClient("http://127.0.0.1:" + port);
        String text = client.complete("sk-test", "claude-sonnet-4-6", "sys", "user task", 8192);

        assertEquals("hello from claude", text);
        String b = body.get();
        assertTrue(b.contains("\"model\":\"claude-sonnet-4-6\""));
        assertTrue(b.contains("\"max_tokens\":8192"));
        assertTrue(b.contains("\"system\":\"sys\""));
        assertTrue(b.contains("\"messages\""));
        assertTrue(b.contains("\"role\":\"user\""));
        assertTrue(b.contains("\"content\":\"user task\""));
    }

    @Test
    void maps401ToInvalidKeyMessage() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/messages", exchange -> {
            exchange.sendResponseHeaders(401, 0);
            exchange.close();
        });
        server.start();
        String port = String.valueOf(server.getAddress().getPort());
        ClaudeApiHttpClient client = new ClaudeApiHttpClient("http://127.0.0.1:" + port);
        AgentProviderException ex = assertThrows(AgentProviderException.class,
                () -> client.complete("bad", "m", "s", "u", 100));
        assertTrue(ex.getMessage().toLowerCase().contains("invalid"));
    }
}
