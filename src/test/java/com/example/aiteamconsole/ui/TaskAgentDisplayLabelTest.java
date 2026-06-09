package com.example.aiteamconsole.ui;

import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.AgentRole;
import com.example.aiteamconsole.ProviderType;
import com.example.aiteamconsole.RunStatus;
import com.example.aiteamconsole.TaskStatus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskAgentDisplayLabelTest {

    @Test
    void backlogBeforePaShowsAnalystRoleWhenTeamHasPa() {
        AgentTask task = AgentTask.create("BE-TASK", 1, "Idea", "Text", "", "", null, List.of());
        AgentProfile pa = AgentProfile.create(
                "Spec",
                AgentRole.PRODUCT_ANALYST,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/o/r",
                "main",
                "pref",
                false,
                List.of());

        assertEquals(
                AgentRole.PRODUCT_ANALYST.label(),
                MainViewModel.agentDisplayLabel(task, List.of(), List.of(pa), true));

        assertEquals(
                AgentRole.BACKEND_ENGINEER.label(),
                MainViewModel.agentDisplayLabel(task, List.of(), List.of(pa), false));
    }

    @Test
    void specReviewUsesFixedLabel() {
        AgentProfile pa = AgentProfile.create(
                "Spec",
                AgentRole.PRODUCT_ANALYST,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/o/r",
                "main",
                "pref",
                false,
                List.of());

        AgentTask task = AgentTask.create("BE-TASK", 1, "Spec work", "", "", "", null, List.of())
                .withStatus(TaskStatus.SPEC_REVIEW);

        assertEquals("Spec review", MainViewModel.agentDisplayLabel(task, List.of(), List.of(pa), true));
    }

    @Test
    void nonTerminalRunUsesExecutorNameOverAssigneePin() {
        AgentProfile executor = AgentProfile.create(
                "DevBot",
                AgentRole.BACKEND_ENGINEER,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/o/r",
                "main",
                "pref",
                true,
                List.of());
        AgentTask task = AgentTask.create("BE-TASK", 9, "Build", "", "", "", executor.id(), List.of());

        AgentRun active = AgentRun.started(
                task.id(),
                executor.id(),
                ProviderType.CURSOR_CLOUD,
                "ext-ag",
                "ext-run",
                "main");

        assertEquals(
                "DevBot",
                MainViewModel.agentDisplayLabel(task, List.of(active), List.of(executor), true));
    }

    @Test
    void finishedPaThenOpenShowsRoleFromPrefix() {
        AgentProfile pa = AgentProfile.create(
                "Analyst",
                AgentRole.PRODUCT_ANALYST,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/o/r",
                "main",
                "p",
                false,
                List.of());
        AgentProfile be = AgentProfile.create(
                "BE",
                AgentRole.BACKEND_ENGINEER,
                ProviderType.CURSOR_CLOUD,
                "https://github.com/o/r",
                "main",
                "b",
                false,
                List.of());

        AgentTask draft = AgentTask.create("BE-TASK", 2, "", "", "", "", null, List.of());
        AgentRun finishedPa = AgentRun.started(draft.id(), pa.id(), ProviderType.CURSOR_CLOUD, "a", "r", "")
                .withStatus(RunStatus.FINISHED, "summary", "");

        AgentTask open = draft.withStatus(TaskStatus.OPEN);

        assertEquals(
                AgentRole.BACKEND_ENGINEER.label(),
                MainViewModel.agentDisplayLabel(open, List.of(finishedPa), List.of(pa, be), true));
    }
}
