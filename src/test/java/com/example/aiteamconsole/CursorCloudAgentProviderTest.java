package com.example.aiteamconsole;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorCloudAgentProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void startsCursorCloudAgentThroughRestApi() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/agents", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"agent":{"id":"bc-test-agent"},"run":{"id":"run-test"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CursorCloudAgentProvider provider = new CursorCloudAgentProvider();
        AgentProfile agent = new AgentProfile(
                UUID.randomUUID(),
                "Backend Agent",
                AgentRole.BACKEND_ENGINEER,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/example/repo",
                "main",
                "cursor",
                true,
                java.time.Instant.now(),
                java.util.List.of()
        );
        AgentTask task = AgentTask.create(
                "BE-TASK",
                1,
                "Add cache tests",
                "Write focused tests for cache behavior.",
                "",
                "main",
                agent.id()
        );

        AgentRun run = provider.startTask(
                agent,
                task,
                new AppSettings("cursor_test_key", "http://localhost:" + server.getAddress().getPort(), "")
        );

        assertEquals("bc-test-agent", run.externalAgentId());
        assertEquals("run-test", run.externalRunId());
        assertEquals("cursor/be-task01-add-cache-tests", run.expectedHeadBranch());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("cursor_test_key:".getBytes(StandardCharsets.UTF_8)), auth.get());
        assertTrue(body.get().contains("\"autoCreatePR\":true"));
        assertTrue(body.get().contains("\"branchName\":\"cursor/be-task01-add-cache-tests\""));
        assertTrue(body.get().contains("\"url\":\"https://github.com/example/repo\""));
        assertTrue(body.get().contains("Write focused tests"));
        assertTrue(body.get().contains("Role-specific operating profile"));
        assertTrue(body.get().contains("backend specialist focused on correctness"));
        assertTrue(body.get().contains("Verification protocol"));
        assertTrue(body.get().contains("ROLE-VERIFY: Backend Engineer | TASK: BE-TASK01"));
        assertTrue(body.get().contains("Pull request title (mandatory"));
        assertTrue(body.get().contains("BE-TASK01 Add cache tests"));
        assertTrue(body.get().contains("Pull request description (mandatory"));
        assertTrue(body.get().contains("composer-2"));
    }

    @Test
    void productAnalystRunRequestsAutoCreatePrFalse() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/agents", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"agent":{"id":"pa-agent"},"run":{"id":"pa-run"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CursorCloudAgentProvider provider = new CursorCloudAgentProvider();
        AgentProfile pa = new AgentProfile(
                UUID.randomUUID(),
                "PA",
                AgentRole.PRODUCT_ANALYST,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/example/repo",
                "main",
                "cursor",
                true,
                java.time.Instant.now(),
                java.util.List.of()
        );
        AgentTask task = AgentTask.create(
                "PA-TASK",
                1,
                "Idea",
                "Rough idea.",
                "",
                "main",
                pa.id()
        );

        provider.startTask(
                pa,
                task,
                new AppSettings("cursor_test_key", "http://localhost:" + server.getAddress().getPort(), "")
        );

        assertTrue(body.get().contains("\"autoCreatePR\":false"), body.get());
    }

    @Test
    void postPullRequestReviewHintsDisableAutoPrAndSkipReviewerRequest() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/agents", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"agent":{"id":"rev-agent"},"run":{"id":"rev-run"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CursorCloudAgentProvider provider = new CursorCloudAgentProvider();
        AgentProfile reviewer = new AgentProfile(
                UUID.randomUUID(),
                "Reviewer",
                AgentRole.CODE_REVIEWER,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/example/repo",
                "main",
                "cursor",
                true,
                java.time.Instant.now(),
                java.util.List.of()
        );
        AgentTask task = AgentTask.create(
                "BE-TASK",
                1,
                "Add cache tests",
                "Write focused tests for cache behavior.",
                "",
                "main",
                reviewer.id()
        );
        String pr = "https://github.com/example/repo/pull/42";
        TaskLaunchHints hints = TaskLaunchHints.postPullRequestCodeReview(pr, "cursor/be-task01-add-cache-tests");
        provider.startTask(
                reviewer,
                task,
                new AppSettings("cursor_test_key", "http://localhost:" + server.getAddress().getPort(), ""),
                hints
        );

        String b = body.get();
        assertTrue(b.contains("\"autoCreatePR\":false"), b);
        assertTrue(b.contains("\"skipReviewerRequest\":true"), b);
        assertTrue(b.contains(pr), b);
        assertTrue(b.contains("Post-PR code review"), b);
    }

    @Test
    void refreshRunAppendsCursorActivitySummary() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/agents/bc-test-agent/runs/run-test", exchange -> {
            byte[] response = """
                    {
                      "status": "RUNNING",
                      "currentStep": "Installing dependencies",
                      "message": "Agent is preparing the workspace",
                      "summary": "Setup is in progress"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CursorCloudAgentProvider provider = new CursorCloudAgentProvider();
        AgentRun run = AgentRun.started(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ProviderType.CURSOR_CLOUD,
                "bc-test-agent",
                "run-test",
                "cursor/be-task01-add-cache-tests"
        );

        AgentRun refreshed = provider.refreshRun(
                run,
                new AppSettings("cursor_test_key", "http://localhost:" + server.getAddress().getPort(), "")
        );

        String lastLog = refreshed.logs().getLast().message();
        assertEquals(RunStatus.RUNNING, refreshed.status());
        assertTrue(lastLog.contains("Cursor run status: RUNNING"));
        assertTrue(lastLog.contains("Installing dependencies"));
        assertTrue(lastLog.contains("Agent is preparing the workspace"));
        assertTrue(lastLog.contains("Setup is in progress"));
    }

    @Test
    void refreshRunUsesBranchNameFromApiWhenPresent() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/agents/bc-test-agent/runs/run-test", exchange -> {
            byte[] response = """
                    {
                      "status": "FINISHED",
                      "branchName": "cursor/cloud-actual-branch",
                      "summary": "Done"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CursorCloudAgentProvider provider = new CursorCloudAgentProvider();
        AgentRun run = AgentRun.started(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ProviderType.CURSOR_CLOUD,
                "bc-test-agent",
                "run-test",
                "cursor/wrong-guess-branch"
        );

        AgentRun refreshed = provider.refreshRun(
                run,
                new AppSettings("cursor_test_key", "http://localhost:" + server.getAddress().getPort(), "")
        );

        assertEquals(RunStatus.FINISHED, refreshed.status());
        assertEquals("cursor/cloud-actual-branch", refreshed.expectedHeadBranch());
    }

    @Test
    void refreshRunDetectsVerificationTokenInSummaryExactlyOnce() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/agents/bc-test-agent/runs/run-test", exchange -> {
            byte[] response = """
                    {
                      "status": "FINISHED",
                      "summary": "Done. ROLE-VERIFY: Backend Engineer | TASK: BE-TASK01\\nMore notes."
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CursorCloudAgentProvider provider = new CursorCloudAgentProvider();
        AgentRun run = AgentRun.started(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ProviderType.CURSOR_CLOUD,
                "bc-test-agent",
                "run-test",
                "cursor/be-task01"
        );
        AppSettings settings = new AppSettings(
                "cursor_test_key",
                "http://localhost:" + server.getAddress().getPort(),
                ""
        );

        AgentRun first = provider.refreshRun(run, settings);
        AgentRun second = provider.refreshRun(first, settings);

        long verifyLogs = second.logs().stream()
                .filter(e -> e.message().startsWith("Role profile verified by Cloud Agent: "))
                .count();
        assertEquals(1L, verifyLogs, "Verification log must be appended exactly once across polls");
        assertTrue(second.logs().stream()
                .anyMatch(e -> e.message().contains("ROLE-VERIFY: Backend Engineer | TASK: BE-TASK01")));
    }

    @Test
    void refreshRunExtractsPullRequestUrlEmbeddedInSummary() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/agents/bc-test-agent/runs/run-test", exchange -> {
            byte[] response = """
                    {
                      "status": "FINISHED",
                      "summary": "Done. Review: https://github.com/acme/sample-repo/pull/42 thanks."
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CursorCloudAgentProvider provider = new CursorCloudAgentProvider();
        AgentRun run = AgentRun.started(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ProviderType.CURSOR_CLOUD,
                "bc-test-agent",
                "run-test",
                "cursor/feature"
        );

        AgentRun refreshed = provider.refreshRun(
                run,
                new AppSettings("cursor_test_key", "http://localhost:" + server.getAddress().getPort(), "")
        );

        assertEquals("https://github.com/acme/sample-repo/pull/42", refreshed.pullRequestUrl());
        assertEquals(RunStatus.FINISHED, refreshed.status());
    }

    @Test
    void promptTextLoggingDefaultsOff() {
        assertEquals(false, CursorCloudAgentProvider.isPromptTextLoggingEnabled(),
                "Full prompt logging must be off unless AI_TEAM_LOG_PROMPT_TEXT is set to a truthy value");
    }
}
