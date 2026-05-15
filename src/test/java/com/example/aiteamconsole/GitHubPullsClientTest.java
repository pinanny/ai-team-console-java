package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

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
    void fetchPullRequestSnapshot_readsMergedAndBaseRef() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/acme/widgets/pulls/42", exchange -> {
            byte[] response = """
                    {"merged":true,"base":{"ref":"main"},"number":42}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String base = "http://localhost:" + server.getAddress().getPort();
        GitHubPullsClient client = new GitHubPullsClient(HttpClient.newHttpClient(), new ObjectMapper(), base);
        GitHubPullsClient.PullRequestSnapshot snap = client.fetchPullRequestSnapshot("tok", "acme", "widgets", 42);
        assertTrue(snap.merged());
        assertEquals("main", snap.baseRef());
        assertTrue(client.isPullRequestMerged("tok", "acme", "widgets", 42));
    }

    @Test
    void fetchPullRequestSnapshot_mergedIntoNonMainBranch() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/acme/widgets/pulls/7", exchange -> {
            byte[] response = """
                    {"merged":true,"base":{"ref":"develop"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String base = "http://localhost:" + server.getAddress().getPort();
        GitHubPullsClient client = new GitHubPullsClient(HttpClient.newHttpClient(), new ObjectMapper(), base);
        GitHubPullsClient.PullRequestSnapshot snap = client.fetchPullRequestSnapshot("tok", "acme", "widgets", 7);
        assertTrue(snap.merged());
        assertEquals("develop", snap.baseRef());
        assertTrue(client.isPullRequestMerged("tok", "acme", "widgets", 7));
    }

    @Test
    void fetchPullRequestSnapshot_openPullRequest() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/o/r/pulls/1", exchange -> {
            byte[] response = """
                    {"merged":false,"base":{"ref":"main"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String base = "http://localhost:" + server.getAddress().getPort();
        GitHubPullsClient client = new GitHubPullsClient(HttpClient.newHttpClient(), new ObjectMapper(), base);
        GitHubPullsClient.PullRequestSnapshot snap = client.fetchPullRequestSnapshot("tok", "o", "r", 1);
        assertFalse(snap.merged());
        assertEquals("main", snap.baseRef());
        assertFalse(client.isPullRequestMerged("tok", "o", "r", 1));
    }

    @Test
    void fetchPullRequestSnapshot_notFoundTreatsAsUnmerged() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/o/r/pulls/99", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        String base = "http://localhost:" + server.getAddress().getPort();
        GitHubPullsClient client = new GitHubPullsClient(HttpClient.newHttpClient(), new ObjectMapper(), base);
        GitHubPullsClient.PullRequestSnapshot snap = client.fetchPullRequestSnapshot("tok", "o", "r", 99);
        assertFalse(snap.merged());
        assertEquals("", snap.baseRef());
        assertFalse(client.isPullRequestMerged("tok", "o", "r", 99));
    }
}
