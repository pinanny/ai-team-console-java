package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubPullsClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchMergeState_mergedIntoMain() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/o/r/pulls/9", exchange -> {
            byte[] response = """
                    {"merged":true,"base":{"ref":"main"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        GitHubPullsClient client = new GitHubPullsClient(HttpClient.newHttpClient(), new ObjectMapper(), api);
        Optional<GitHubPullsClient.PullRequestMergeState> s = client.fetchPullRequestMergeState("tok", "o", "r", 9);
        assertTrue(s.isPresent());
        assertTrue(s.get().merged());
        assertEquals("main", s.get().baseRef());
    }

    @Test
    void fetchMergeState_mergedButNotToMain() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/o/r/pulls/3", exchange -> {
            byte[] response = """
                    {"merged":true,"base":{"ref":"develop"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        GitHubPullsClient client = new GitHubPullsClient(HttpClient.newHttpClient(), new ObjectMapper(), api);
        Optional<GitHubPullsClient.PullRequestMergeState> s = client.fetchPullRequestMergeState("tok", "o", "r", 3);
        assertTrue(s.isPresent());
        assertTrue(s.get().merged());
        assertEquals("develop", s.get().baseRef());
    }

    @Test
    void fetchMergeState_openPr() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/o/r/pulls/2", exchange -> {
            byte[] response = """
                    {"merged":false,"base":{"ref":"main"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        GitHubPullsClient client = new GitHubPullsClient(HttpClient.newHttpClient(), new ObjectMapper(), api);
        Optional<GitHubPullsClient.PullRequestMergeState> s = client.fetchPullRequestMergeState("tok", "o", "r", 2);
        assertTrue(s.isPresent());
        assertFalse(s.get().merged());
        assertEquals("main", s.get().baseRef());
    }

    @Test
    void fetchMergeState_notFound() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/o/r/pulls/99", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        GitHubPullsClient client = new GitHubPullsClient(HttpClient.newHttpClient(), new ObjectMapper(), api);
        assertTrue(client.fetchPullRequestMergeState("tok", "o", "r", 99).isEmpty());
        assertFalse(client.isPullRequestMerged("tok", "o", "r", 99));
    }

    @Test
    void isPullRequestMerged_delegatesToFetch() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/x/y/pulls/1", exchange -> {
            byte[] response = """
                    {"merged":true,"base":{"ref":"main"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        GitHubPullsClient client = new GitHubPullsClient(HttpClient.newHttpClient(), new ObjectMapper(), api);
        assertTrue(client.isPullRequestMerged("t", "x", "y", 1));
    }
}
