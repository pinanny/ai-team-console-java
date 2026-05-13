package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskTest {
    private static AgentTask newDraft() {
        return AgentTask.create(
                "BE-TASK",
                1,
                "Add cache tests",
                "Write tests.",
                "https://github.com/example/repo",
                "main",
                UUID.randomUUID()
        );
    }

    @Test
    void createdTaskHasNoStartedOrEndedTimestamps() {
        AgentTask draft = newDraft();

        assertEquals(TaskStatus.DRAFT, draft.status());
        assertNull(draft.startedAt());
        assertNull(draft.endedAt());
    }

    @Test
    void transitionToRunningSetsStartedAndClearsEnded() {
        AgentTask draft = newDraft();

        AgentTask running = draft.withStatus(TaskStatus.RUNNING);

        assertEquals(TaskStatus.RUNNING, running.status());
        assertNotNull(running.startedAt());
        assertNull(running.endedAt());
    }

    @Test
    void transitionToDoneSetsEndedTimestamp() {
        AgentTask running = newDraft().withStatus(TaskStatus.RUNNING);

        AgentTask done = running.withStatus(TaskStatus.DONE);

        assertEquals(TaskStatus.DONE, done.status());
        assertEquals(running.startedAt(), done.startedAt(), "Started must be preserved when finishing");
        assertNotNull(done.endedAt());
    }

    @Test
    void reRunningDoneTaskResetsStartedAndClearsEnded() throws InterruptedException {
        AgentTask done = newDraft()
                .withStatus(TaskStatus.RUNNING)
                .withStatus(TaskStatus.DONE);
        Thread.sleep(5);

        AgentTask running = done.withStatus(TaskStatus.RUNNING);

        assertEquals(TaskStatus.RUNNING, running.status());
        assertNull(running.endedAt(), "Ended must be cleared on re-run");
        assertTrue(running.startedAt().isAfter(done.startedAt()),
                "Started must advance to the latest RUNNING transition");
    }

    @Test
    void withEditableFieldsKeepsStartedAndEnded() {
        UUID newAgent = UUID.randomUUID();
        AgentTask done = newDraft()
                .withStatus(TaskStatus.RUNNING)
                .withStatus(TaskStatus.DONE);

        AgentTask edited = done.withEditableFields(
                "BE-TASK",
                "Updated title",
                "Reassigned to a different agent",
                "https://github.com/example/repo",
                "main",
                newAgent,
                List.of("backend")
        );

        assertEquals("Updated title", edited.title());
        assertEquals(newAgent, edited.assignedAgentId());
        assertEquals(done.startedAt(), edited.startedAt());
        assertEquals(done.endedAt(), edited.endedAt());
        assertEquals(TaskStatus.DONE, edited.status());
        assertEquals(List.of("backend"), edited.repositoryTags());
    }

    @Test
    void createWithTagsNormalizesAndDeduplicates() {
        AgentTask task = AgentTask.create(
                "FE-TASK",
                1,
                "UI tweak",
                "",
                "",
                "",
                UUID.randomUUID(),
                List.of(" Frontend ", "frontend", "UI")
        );

        assertEquals(List.of("frontend", "ui"), task.repositoryTags());
    }
}
