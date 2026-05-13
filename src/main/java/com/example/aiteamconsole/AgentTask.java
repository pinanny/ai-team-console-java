package com.example.aiteamconsole;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

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
        List<String> repositoryTags
) {
    public AgentTask {
        repositoryTags = normalizeTags(repositoryTags);
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
                normalizeTags(repositoryTags)
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
                repositoryTags
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
                normalizeTags(nextRepositoryTags)
        );
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
