package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBranchNamesTest {

    @Test
    void branchNameMatchesCursorProviderConvention() {
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
                "Do the thing.",
                "",
                "main",
                agent.id()
        );
        assertEquals("cursor/be-task01-add-cache-tests", AgentBranchNames.forTask(agent, task));
        assertEquals("main", AgentBranchNames.baseBranchForPullRequest(task.startingRef(), agent.startingRef()));
    }

    @Test
    void stripsRefsHeadsForPullRequestBase() {
        assertEquals("develop", AgentBranchNames.baseBranchForPullRequest("refs/heads/develop", ""));
    }

    @Test
    void sanitizeBranchLeavesSlugSafeNamesUnchanged() {
        assertEquals("cursor/be-task01-add-cache-tests",
                AgentBranchNames.sanitizeForCursorApiBranch("cursor/be-task01-add-cache-tests"));
    }

    @Test
    void sanitizeBranchNormalizesSpacesAndCase() {
        assertEquals("ia-agent/foo-bar",
                AgentBranchNames.sanitizeForCursorApiBranch("IA-Agent/ Foo  Bar"));
    }

    @Test
    void resolveHeadBranchAlignsWithSanitizeForTaskOutput() {
        AgentProfile agent = new AgentProfile(
                UUID.randomUUID(),
                "Ollama Agent",
                AgentRole.BACKEND_ENGINEER,
                ProviderType.OLLAMA,
                "https://github.com/example/repo",
                "main",
                "IA-Agent",
                true,
                java.time.Instant.now(),
                java.util.List.of()
        );
        AgentTask task = AgentTask.create(
                "BE-TASK",
                6,
                "Add README",
                "Add file",
                "",
                "main",
                agent.id()
        );
        String raw = AgentBranchNames.forTask(agent, task);
        String expected = AgentBranchNames.sanitizeForCursorApiBranch(raw);
        AgentBranchNames.HeadBranchResolution r = AgentBranchNames.resolveHeadBranch(agent, task);
        assertEquals(raw, r.requestedRaw());
        assertEquals(expected.isBlank() ? raw.strip() : expected, r.effectiveForGitAndApi());
        assertTrue(r.mismatchNoteForLogs().contains("requested slug"));
    }

    @Test
    void sanitizeBranchTruncatesVeryLongNames() {
        String raw = "cursor/" + "x".repeat(300);
        String s = AgentBranchNames.sanitizeForCursorApiBranch(raw);
        assertTrue(s.length() <= 244);
        assertTrue(s.startsWith("cursor/"));
        assertTrue(s.contains("-t"));
    }
}
