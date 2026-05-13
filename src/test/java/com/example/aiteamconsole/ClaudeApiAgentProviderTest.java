package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeApiAgentProviderTest {

    @Test
    void startTaskWithDiffFinishesSuccessfully(@TempDir Path tmp) throws Exception {
        Path template = initBareWithClone(tmp);
        Path ghDir = tmp.resolve("gh");
        Files.createDirectories(ghDir);
        GitHubJsonStore store = new GitHubJsonStore(ghDir);
        store.saveSession(new GitHubSession("fake-token", "testuser", "repo", Instant.now()));

        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        AgentProfile agent = new AgentProfile(
                agentId,
                "Test agent",
                AgentRole.BACKEND_ENGINEER,
                ProviderType.CLAUDE_API,
                "https://github.com/o/r",
                "main",
                "test",
                false,
                now,
                List.of()
        );
        AgentTask task = new AgentTask(
                taskId,
                "BE",
                9,
                "patch readme",
                "desc",
                "https://github.com/o/r",
                "main",
                agentId,
                TaskStatus.QUEUED,
                now,
                now,
                null,
                null,
                List.of()
        );

        Path ws = tmp.resolve("ws");
        ClaudeApiAgentProvider provider = new ClaudeApiAgentProvider(store, ws);
        provider.setClaudeSettings(new ClaudeApiSettings("sk-test", "claude-sonnet-4-6", "https://api.anthropic.com"));
        provider.setHttpClientForTests(new ClaudeApiHttpClient("unused") {
            @Override
            public String complete(
                    String apiKey,
                    String model,
                    String systemPrompt,
                    String userMessage,
                    int maxTokens
            ) {
                return """
                        <<<DIFF>>>
                        diff --git a/README.md b/README.md
                        --- a/README.md
                        +++ b/README.md
                        @@ -1 +1 @@
                        -hello
                        +hello patched
                        <<<END_DIFF>>>
                        """;
            }
        });

        provider.setWorkspacePreparerForTests((workDir, slug, session, branchName, baseBranch, log) -> {
            copyDirectory(template, workDir);
            LocalGitOperations.runGit(workDir, "checkout", baseBranch);
            LocalGitOperations.runGit(workDir, "checkout", "-b", branchName);
        });

        AgentRun run = provider.startTask(agent, task, AppSettings.fromEnvironment());
        AgentRun updated = awaitTerminal(provider, run);
        provider.clearTestHooks();

        assertEquals(RunStatus.FINISHED, updated.status());
        assertTrue(updated.resultSummary().toLowerCase().contains("claude api"));
    }

    @Test
    void startTaskWithNoPatchFinishesSuccessfully(@TempDir Path tmp) throws Exception {
        Path template = initBareWithClone(tmp);
        Path ghDir = tmp.resolve("gh2");
        Files.createDirectories(ghDir);
        GitHubJsonStore store = new GitHubJsonStore(ghDir);
        store.saveSession(new GitHubSession("fake-token", "testuser", "repo", Instant.now()));

        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        AgentProfile agent = new AgentProfile(
                agentId,
                "Test agent",
                AgentRole.BACKEND_ENGINEER,
                ProviderType.CLAUDE_API,
                "https://github.com/o/r",
                "main",
                "test",
                false,
                now,
                List.of()
        );
        AgentTask task = new AgentTask(
                taskId,
                "BE",
                10,
                "noop",
                "desc",
                "https://github.com/o/r",
                "main",
                agentId,
                TaskStatus.QUEUED,
                now,
                now,
                null,
                null,
                List.of()
        );

        Path ws = tmp.resolve("ws2");
        ClaudeApiAgentProvider provider = new ClaudeApiAgentProvider(store, ws);
        provider.setClaudeSettings(new ClaudeApiSettings("sk-test", "claude-sonnet-4-6", "https://api.anthropic.com"));
        provider.setHttpClientForTests(new ClaudeApiHttpClient("unused") {
            @Override
            public String complete(
                    String apiKey,
                    String model,
                    String systemPrompt,
                    String userMessage,
                    int maxTokens
            ) {
                return "NO_PATCH";
            }
        });
        provider.setWorkspacePreparerForTests((workDir, slug, session, branchName, baseBranch, log) -> {
            copyDirectory(template, workDir);
            LocalGitOperations.runGit(workDir, "checkout", baseBranch);
            LocalGitOperations.runGit(workDir, "checkout", "-b", branchName);
        });

        AgentRun run = provider.startTask(agent, task, AppSettings.fromEnvironment());
        AgentRun updated = awaitTerminal(provider, run);
        provider.clearTestHooks();

        assertEquals(RunStatus.FINISHED, updated.status());
        assertTrue(updated.resultSummary().toLowerCase().contains("no code changes"));
    }

    private static AgentRun awaitTerminal(ClaudeApiAgentProvider provider, AgentRun run) throws Exception {
        AgentRun cur = run;
        for (int i = 0; i < 600; i++) {
            if (cur.status().terminal()) {
                return cur;
            }
            Thread.sleep(50);
            cur = provider.refreshRun(cur, AppSettings.fromEnvironment());
        }
        throw new AssertionError("run did not finish");
    }

    private static Path initBareWithClone(Path tmp) throws Exception {
        Path bare = tmp.resolve("remote.git");
        Files.createDirectories(bare);
        runGit(bare, "init", "--bare");
        Path cloneParent = tmp.resolve("cloneParent");
        Files.createDirectories(cloneParent);
        runGit(cloneParent, "clone", bare.toAbsolutePath().toString(), "work");
        Path work = cloneParent.resolve("work");
        Files.writeString(work.resolve("README.md"), "hello\n");
        runGit(work, "config", "user.email", "t@t");
        runGit(work, "config", "user.name", "t");
        runGit(work, "add", "README.md");
        runGit(work, "commit", "-m", "init");
        runGit(work, "branch", "-M", "main");
        runGit(work, "push", "-u", "origin", "main");
        return work;
    }

    private static void runGit(Path cwd, String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(gitCmd(args));
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(3, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("git timeout: " + String.join(" ", args));
        }
        if (p.exitValue() != 0) {
            throw new IOException("git failed: " + String.join(" ", args) + "\n" + out);
        }
    }

    private static List<String> gitCmd(String... args) {
        List<String> c = new java.util.ArrayList<>();
        c.add("git");
        for (String a : args) {
            c.add(a);
        }
        return c;
    }

    private static void copyDirectory(Path src, Path dest) throws IOException {
        try (var walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path rel = src.relativize(p);
                Path target = dest.resolve(rel).normalize();
                if (!target.startsWith(dest)) {
                    throw new IOException("unsafe path");
                }
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
