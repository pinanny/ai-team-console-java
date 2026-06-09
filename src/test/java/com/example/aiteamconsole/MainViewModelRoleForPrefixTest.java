package com.example.aiteamconsole;

import com.example.aiteamconsole.ui.MainViewModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainViewModelRoleForPrefixTest {

    @Test
    void paTaskPrefixMapsToProductAnalyst() {
        assertEquals(Optional.of(AgentRole.PRODUCT_ANALYST), MainViewModel.roleForTaskPrefix("PA-TASK"));
    }

    @Test
    void patchTaskPrefixDoesNotMapToProductAnalyst() {
        assertTrue(MainViewModel.roleForTaskPrefix("PATCH-TASK").isEmpty());
    }

    @Test
    void productAnalystPromptIncludesUpstreamAddendum() {
        AgentProfile agent = AgentProfile.create(
                "Spec Bot",
                AgentRole.PRODUCT_ANALYST,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/o/r",
                "main",
                "ia-agent-spec",
                false,
                List.of()
        );
        AgentTask task = AgentTask.create("PA-TASK", 1, "Fuzzy idea", "Make it better", "", "", agent.id(), List.of());
        String prompt = AgentTaskPrompts.buildCursorStyleTaskPrompt(agent, task, "main", "ia-agent-spec/pa-task01-fuzzy-idea");
        assertTrue(prompt.contains("Product Analyst run (upstream of BE / FE / QA)"));
        assertTrue(prompt.contains("Problem statement"));
        assertTrue(prompt.contains("Acceptance criteria"));
        assertTrue(prompt.contains("Prioritized scope"));
        assertTrue(prompt.contains("Open questions"));
        assertFalse(prompt.contains("prepare a pull request"), "PA prompt must not ask for PR workflow");
        assertFalse(prompt.contains("Pull request title (mandatory"), "PA prompt must not mandate PR title block");
        assertTrue(prompt.contains("specification-only run"));
    }

    @Test
    void backendPromptOmitsProductAnalystAddendum() {
        AgentProfile agent = AgentProfile.create(
                "BE Bot",
                AgentRole.BACKEND_ENGINEER,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/o/r",
                "main",
                "ia-agent-be",
                true,
                List.of()
        );
        AgentTask task = AgentTask.create("BE-TASK", 1, "API", "Do thing", "", "", agent.id(), List.of());
        String prompt = AgentTaskPrompts.buildCursorStyleTaskPrompt(agent, task, "main", "ia-agent-be/be-task01-api");
        assertFalse(prompt.contains("Product Analyst run (upstream of BE / FE / QA)"));
    }
}
