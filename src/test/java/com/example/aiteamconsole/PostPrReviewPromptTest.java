package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PostPrReviewPromptTest {

    @Test
    void codeReviewerWithPrFocusGetsReviewOnlyBrief() {
        AgentProfile rev = new AgentProfile(
                UUID.randomUUID(),
                "Rev",
                AgentRole.CODE_REVIEWER,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/example/repo",
                "main",
                "cursor",
                true,
                Instant.now(),
                List.of()
        );
        AgentTask task = AgentTask.create(
                "BE-TASK",
                1,
                "Add cache",
                "Do the thing.",
                "",
                "main",
                rev.id()
        );
        String pr = "https://github.com/example/repo/pull/9";
        String prompt = AgentTaskPrompts.buildCursorStyleTaskPrompt(rev, task, "main", "cursor/be-task01-add-cache", null, pr);
        assertTrue(prompt.contains(pr), prompt);
        assertTrue(prompt.contains("inline review comments"), prompt);
        assertTrue(prompt.contains("Do NOT create a new task branch"), prompt);
    }
}
