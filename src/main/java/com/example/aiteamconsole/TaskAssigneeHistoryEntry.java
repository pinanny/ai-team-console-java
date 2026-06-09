package com.example.aiteamconsole;

import java.time.Instant;
import java.util.UUID;

/**
 * One timeline row for assignee / executor activity on a task (Jira-style history).
 */
public record TaskAssigneeHistoryEntry(
        Instant at,
        /**
         * Agent tied to this event (executor or pinned assignee). {@code null} means nobody is
         * associated for this row (e.g. assignee cleared for spec review).
         */
        UUID assigneeAgentId,
        String reason
) {
    public TaskAssigneeHistoryEntry {
        if (reason == null) {
            reason = "";
        }
        reason = reason.strip();
    }
}
