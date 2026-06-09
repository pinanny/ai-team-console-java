package com.example.aiteamconsole;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentTask(
        UUID id,
        String taskPrefix,
        int taskNumber,
        String title,
        String description,
        String repositoryUrl,
        String startingRef,
        UUID assignedAgentId,
        TaskStatus status,
        Instant createdAt,
        Instant updatedAt,
        /** Last time the task transitioned into RUNNING. Null until the first run starts. */
        Instant startedAt,
        /** Last time the task transitioned into DONE. Cleared when re-running. */
        Instant endedAt,
        /** Tags chosen for this task. Resolved at run time to repository URLs via the repository registry. */
        List<String> repositoryTags,
        /** Assignee / executor timeline (newest events appended at the end). */
        List<TaskAssigneeHistoryEntry> assigneeHistory,
        /**
         * Ordered implementation pipeline: each wave may list multiple roles to run in parallel.
         * Empty = legacy routing (task prefix + optional pinned assignee).
         */
        List<TaskFlowWave> developmentFlow,
        /** Index of the wave to run next (0-based). Ignored when {@link #developmentFlow} is empty. */
        int developmentFlowWaveIndex
) {
    public AgentTask {
        repositoryTags = normalizeTags(repositoryTags);
        assigneeHistory = normalizeAssigneeHistory(assigneeHistory);
        developmentFlow = normalizeDevelopmentFlow(developmentFlow);
        if (developmentFlowWaveIndex < 0) {
            developmentFlowWaveIndex = 0;
        }
        if (developmentFlow.isEmpty()) {
            developmentFlowWaveIndex = 0;
        } else if (developmentFlowWaveIndex > developmentFlow.size()) {
            developmentFlowWaveIndex = developmentFlow.size();
        }
    }

    public static AgentTask create(
            String taskPrefix,
            int taskNumber,
            String title,
            String description,
            String repositoryUrl,
            String startingRef,
            UUID assignedAgentId,
            List<String> repositoryTags
    ) {
        Instant now = Instant.now();
        return new AgentTask(
                UUID.randomUUID(),
                normalizePrefix(taskPrefix),
                taskNumber,
                title,
                description,
                repositoryUrl,
                startingRef == null ? "" : startingRef.strip(),
                assignedAgentId,
                TaskStatus.DRAFT,
                now,
                now,
                null,
                null,
                normalizeTags(repositoryTags),
                List.of(),
                List.of(),
                0
        );
    }

    /**
     * Legacy single-repo factory kept for tests and any older callers; the value lands in {@link #repositoryUrl}.
     */
    public static AgentTask create(
            String taskPrefix,
            int taskNumber,
            String title,
            String description,
            String repositoryUrl,
            String startingRef,
            UUID assignedAgentId
    ) {
        return create(taskPrefix, taskNumber, title, description, repositoryUrl, startingRef, assignedAgentId, List.of());
    }

    public String taskKey() {
        if (taskPrefix == null || taskPrefix.isBlank() || taskNumber <= 0) {
            return "";
        }
        return "%s%02d".formatted(normalizePrefix(taskPrefix), taskNumber);
    }

    public String displayTitle() {
        String key = taskKey();
        return key.isBlank() ? title : key + " " + title;
    }

    public AgentTask withStatus(TaskStatus nextStatus) {
        Instant now = Instant.now();
        Instant nextStartedAt = startedAt;
        Instant nextEndedAt = endedAt;
        if (nextStatus == TaskStatus.RUNNING && status != TaskStatus.RUNNING) {
            nextStartedAt = now;
            nextEndedAt = null;
        } else if (nextStatus == TaskStatus.DONE) {
            nextEndedAt = now;
        }
        return new AgentTask(
                id,
                taskPrefix,
                taskNumber,
                title,
                description,
                repositoryUrl,
                startingRef,
                assignedAgentId,
                nextStatus,
                createdAt,
                now,
                nextStartedAt,
                nextEndedAt,
                repositoryTags,
                assigneeHistory,
                developmentFlow,
                developmentFlowWaveIndex
        );
    }

    public AgentTask withEditableFields(
            String nextTaskPrefix,
            String nextTitle,
            String nextDescription,
            String nextRepositoryUrl,
            String nextStartingRef,
            UUID nextAssignedAgentId,
            List<String> nextRepositoryTags
    ) {
        return withEditableFields(
                nextTaskPrefix,
                nextTitle,
                nextDescription,
                nextRepositoryUrl,
                nextStartingRef,
                nextAssignedAgentId,
                nextRepositoryTags,
                null,
                null
        );
    }

    public AgentTask withEditableFields(
            String nextTaskPrefix,
            String nextTitle,
            String nextDescription,
            String nextRepositoryUrl,
            String nextStartingRef,
            UUID nextAssignedAgentId,
            List<String> nextRepositoryTags,
            List<TaskFlowWave> nextDevelopmentFlowOrNull,
            Integer nextDevelopmentFlowWaveIndexOrNull
    ) {
        List<TaskFlowWave> nextFlow = nextDevelopmentFlowOrNull != null
                ? normalizeDevelopmentFlow(nextDevelopmentFlowOrNull)
                : developmentFlow;
        int nextWave = nextDevelopmentFlowWaveIndexOrNull != null
                ? nextDevelopmentFlowWaveIndexOrNull
                : developmentFlowWaveIndex;
        if (nextFlow.isEmpty()) {
            nextWave = 0;
        } else if (nextWave < 0) {
            nextWave = 0;
        } else if (nextWave > nextFlow.size()) {
            nextWave = nextFlow.size();
        }
        return new AgentTask(
                id,
                normalizePrefix(nextTaskPrefix),
                taskNumber,
                nextTitle,
                nextDescription,
                nextRepositoryUrl,
                nextStartingRef == null ? "" : nextStartingRef.strip(),
                nextAssignedAgentId,
                status,
                createdAt,
                Instant.now(),
                startedAt,
                endedAt,
                normalizeTags(nextRepositoryTags),
                assigneeHistory,
                nextFlow,
                nextWave
        );
    }

    public AgentTask withDevelopmentFlowWaveIndex(int nextWaveIndex) {
        int w = nextWaveIndex;
        if (developmentFlow.isEmpty()) {
            w = 0;
        } else if (w < 0) {
            w = 0;
        } else if (w > developmentFlow.size()) {
            w = developmentFlow.size();
        }
        return new AgentTask(
                id,
                taskPrefix,
                taskNumber,
                title,
                description,
                repositoryUrl,
                startingRef,
                assignedAgentId,
                status,
                createdAt,
                updatedAt,
                startedAt,
                endedAt,
                repositoryTags,
                assigneeHistory,
                developmentFlow,
                w
        );
    }

    public AgentTask withAssigneeHistoryEntryAppended(TaskAssigneeHistoryEntry entry) {
        if (entry == null) {
            return this;
        }
        List<TaskAssigneeHistoryEntry> next = new ArrayList<>(assigneeHistory);
        next.add(entry);
        return new AgentTask(
                id,
                taskPrefix,
                taskNumber,
                title,
                description,
                repositoryUrl,
                startingRef,
                assignedAgentId,
                status,
                createdAt,
                updatedAt,
                startedAt,
                endedAt,
                repositoryTags,
                next,
                developmentFlow,
                developmentFlowWaveIndex
        );
    }

    private static List<TaskFlowWave> normalizeDevelopmentFlow(List<TaskFlowWave> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return List.copyOf(raw);
    }

    private static List<TaskAssigneeHistoryEntry> normalizeAssigneeHistory(List<TaskAssigneeHistoryEntry> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(raw);
    }

    private static String normalizePrefix(String taskPrefix) {
        if (taskPrefix == null || taskPrefix.isBlank()) {
            return "TASK";
        }
        return taskPrefix.strip().toUpperCase().replaceAll("[^A-Z0-9-]", "-");
    }

    private static List<String> normalizeTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String t : raw) {
            if (t == null) {
                continue;
            }
            String norm = t.strip().toLowerCase();
            if (!norm.isEmpty()) {
                ordered.add(norm);
            }
        }
        return new ArrayList<>(ordered);
    }
}
