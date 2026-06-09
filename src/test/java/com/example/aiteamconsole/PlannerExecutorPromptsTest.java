package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the planner / executor / verifier prompt builders and parsers.
 * These are the heart of the token-optimization pipeline — covering them keeps the
 * contract stable as the role-prompt skill evolves.
 */
class PlannerExecutorPromptsTest {

    private static AgentProfile plannerAgent() {
        return new AgentProfile(
                UUID.randomUUID(),
                "Planner",
                AgentRole.IMPLEMENTATION_PLANNER,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/example/repo",
                "main",
                "cursor",
                false,
                Instant.now(),
                List.of()
        );
    }

    private static AgentTask sampleTask() {
        return AgentTask.create(
                "BE-TASK",
                42,
                "Add cache to user lookup",
                "We need a 1-minute TTL cache on getUserById.",
                "",
                "main",
                null
        );
    }

    // ── buildImplementationPlannerPrompt ──────────────────────────────────────

    @Test
    void plannerPromptContainsRoleProfileAndPlanMarkers() {
        AgentProfile planner = plannerAgent();
        AgentTask task = sampleTask();
        String prompt = AgentTaskPrompts.buildImplementationPlannerPrompt(planner, task, null);

        assertTrue(prompt.contains("Implementation Planner"), prompt);
        assertTrue(prompt.contains("PLAN_START"), prompt);
        assertTrue(prompt.contains("PLAN_END"), prompt);
        assertTrue(prompt.contains(task.title()), prompt);
        assertTrue(prompt.contains(task.taskKey()), prompt);
        // The planner must be told NOT to push branches/PRs.
        assertTrue(prompt.contains("Do not narrate"), prompt);
    }

    @Test
    void plannerPromptInjectsProjectMemoryWhenAvailable() {
        AgentProfile planner = plannerAgent();
        AgentTask task = sampleTask();
        ProjectMemorySnapshot memory = ProjectMemorySnapshot.ofMultiLevel(
                "https://github.com/example/repo",
                "BRIEF: tiny",
                "CORE: pkg com.example.foo handles caching",
                "EXTENDED: very detailed dive into caching layers"
        );
        String prompt = AgentTaskPrompts.buildImplementationPlannerPrompt(planner, task, memory);
        // Planner gets the extendedOrCore tier per pickContextForRole.
        assertTrue(prompt.contains("EXTENDED: very detailed dive"), prompt);
        assertTrue(prompt.contains("Pre-analyzed project context"), prompt);
    }

    @Test
    void plannerPromptOmitsMemorySectionWhenSnapshotEmpty() {
        AgentProfile planner = plannerAgent();
        AgentTask task = sampleTask();
        String prompt = AgentTaskPrompts.buildImplementationPlannerPrompt(planner, task,
                ProjectMemorySnapshot.empty("https://github.com/example/repo"));
        assertFalse(prompt.contains("Pre-analyzed project context"), prompt);
    }

    // ── extractImplementationPlan ─────────────────────────────────────────────

    @Test
    void extractsPlanBodyBetweenMarkers() {
        String output = """
                Some preamble the model might emit.

                ## PLAN_START
                ## FILES_TO_TOUCH
                - EDIT src/main/java/Foo.java
                ## PLAN_END

                Trailing chatter that should be ignored.
                """;
        String plan = AgentTaskPrompts.extractImplementationPlan(output);
        assertTrue(plan.contains("## FILES_TO_TOUCH"), plan);
        assertTrue(plan.contains("- EDIT src/main/java/Foo.java"), plan);
        assertFalse(plan.contains("preamble"), plan);
        assertFalse(plan.contains("Trailing chatter"), plan);
    }

    @Test
    void extractEmptyOnMissingMarkers() {
        assertEquals("", AgentTaskPrompts.extractImplementationPlan(""));
        assertEquals("", AgentTaskPrompts.extractImplementationPlan(null));
        assertEquals("", AgentTaskPrompts.extractImplementationPlan("just a plain summary, no markers"));
        // PLAN_END before PLAN_START is malformed
        assertEquals("", AgentTaskPrompts.extractImplementationPlan("## PLAN_END\n## PLAN_START\n"));
    }

    // ── extractVerificationChecklist ──────────────────────────────────────────

    @Test
    void extractsChecklistSectionFromPlanBody() {
        String plan = """
                ## FILES_TO_TOUCH
                - EDIT Foo.java
                ## VERIFICATION_CHECKLIST
                1. Foo.java contains validate()
                2. mvn -q test exits 0
                ## PLAN_END
                """;
        String checklist = AgentTaskPrompts.extractVerificationChecklist(plan);
        assertTrue(checklist.contains("Foo.java contains validate"), checklist);
        assertTrue(checklist.contains("mvn -q test exits 0"), checklist);
        assertFalse(checklist.contains("FILES_TO_TOUCH"), checklist);
        // The next ## terminates the section; PLAN_END marker itself must not leak in.
        assertFalse(checklist.contains("PLAN_END"), checklist);
    }

    @Test
    void extractChecklistEmptyWhenSectionMissing() {
        assertEquals("", AgentTaskPrompts.extractVerificationChecklist("## FILES_TO_TOUCH\n- x"));
        assertEquals("", AgentTaskPrompts.extractVerificationChecklist(""));
        assertEquals("", AgentTaskPrompts.extractVerificationChecklist(null));
    }

    // ── buildOllamaExecutorPromptFromPlan ─────────────────────────────────────

    @Test
    void executorPromptIsPlanCentricAndOmitsLayoutQdrant() {
        AgentTask task = sampleTask();
        String plan = """
                ## FILES_TO_TOUCH
                - EDIT Foo.java
                ## PER_FILE_CHANGES
                ### Foo.java
                ACTION: EDIT
                """;
        String prompt = AgentTaskPrompts.buildOllamaExecutorPromptFromPlan(task, plan);
        assertTrue(prompt.contains("=== PLAN ==="), prompt);
        assertTrue(prompt.contains("FILES_TO_TOUCH"), prompt);
        assertTrue(prompt.contains("plan below is a CONTRACT"), prompt);
        // Hard requirement: executor prompt must NOT include the heavy classic-mode blocks.
        assertFalse(prompt.contains("Repository layout"), prompt);
        assertFalse(prompt.contains("Relevant code excerpts (retrieval)"), prompt);
    }

    // ── buildOllamaVerifierPromptFromPlan ─────────────────────────────────────

    @Test
    void verifierPromptContainsChecklistAndDiff() {
        AgentTask task = sampleTask();
        String plan = """
                ## VERIFICATION_CHECKLIST
                1. Foo.java contains validate
                """;
        String diff = "diff --git a/Foo.java b/Foo.java\n--- a/Foo.java\n+++ b/Foo.java\n";
        String prompt = AgentTaskPrompts.buildOllamaVerifierPromptFromPlan(task, plan, diff);
        assertTrue(prompt.contains("Foo.java contains validate"), prompt);
        assertTrue(prompt.contains("diff --git a/Foo.java"), prompt);
        assertTrue(prompt.contains("\"verdict\""), prompt);
    }

    @Test
    void verifierPromptHandlesMissingChecklist() {
        AgentTask task = sampleTask();
        String prompt = AgentTaskPrompts.buildOllamaVerifierPromptFromPlan(task, "## FILES_TO_TOUCH\n- x", "");
        assertTrue(prompt.contains("plan has no VERIFICATION_CHECKLIST section"), prompt);
        assertTrue(prompt.contains("diff was empty"), prompt);
    }

    @Test
    void verifierPromptTruncatesLargeDiff() {
        AgentTask task = sampleTask();
        String hugeDiff = "diff --git a/Foo.java b/Foo.java\n"
                + "x".repeat(40_000);
        String prompt = AgentTaskPrompts.buildOllamaVerifierPromptFromPlan(task, "## VERIFICATION_CHECKLIST\n1. x", hugeDiff);
        assertTrue(prompt.contains("(truncated)"), "expected diff truncation marker");
        // The 40 000 'x' block must not survive in full.
        assertTrue(prompt.length() < 30_000, "verifier prompt unexpectedly long: " + prompt.length());
    }
}
