package com.example.aiteamconsole;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record AgentRun(
        UUID id,
        UUID taskId,
        UUID agentProfileId,
        ProviderType provider,
        String externalAgentId,
        String externalRunId,
        RunStatus status,
        String pullRequestUrl,
        String resultSummary,
        Instant startedAt,
        Instant completedAt,
        String expectedHeadBranch,
        List<RunLogEntry> logs
) {
    public static AgentRun started(
            UUID taskId,
            UUID agentProfileId,
            ProviderType provider,
            String externalAgentId,
            String externalRunId,
            String expectedHeadBranch
    ) {
        String branch = expectedHeadBranch == null ? "" : expectedHeadBranch.strip();
        return new AgentRun(
                UUID.randomUUID(),
                taskId,
                agentProfileId,
                provider,
                externalAgentId,
                externalRunId,
                RunStatus.RUNNING,
                "",
                "",
                Instant.now(),
                null,
                branch,
                new ArrayList<>()
        ).appendLog("Started provider run %s/%s".formatted(externalAgentId, externalRunId));
    }

    public AgentRun withStatus(RunStatus nextStatus, String summary, String pullRequestUrl) {
        Instant completed = nextStatus.terminal() ? Instant.now() : completedAt;
        return new AgentRun(
                id,
                taskId,
                agentProfileId,
                provider,
                externalAgentId,
                externalRunId,
                nextStatus,
                pullRequestUrl == null ? this.pullRequestUrl : pullRequestUrl,
                summary == null ? resultSummary : summary,
                startedAt,
                completed,
                expectedHeadBranch,
                logs
        );
    }

    /**
     * Updates the branch name used for GitHub fallback PR creation when the Cursor API reports a different head.
     */
    public AgentRun withExpectedHeadBranch(String branch) {
        String next = branch == null ? "" : branch.strip();
        String prev = expectedHeadBranch == null ? "" : expectedHeadBranch.strip();
        if (next.equals(prev)) {
            return this;
        }
        return new AgentRun(
                id,
                taskId,
                agentProfileId,
                provider,
                externalAgentId,
                externalRunId,
                status,
                pullRequestUrl,
                resultSummary,
                startedAt,
                completedAt,
                next,
                logs
        );
    }

    public AgentRun appendLog(String message) {
        List<RunLogEntry> nextLogs = new ArrayList<>(logs == null ? List.of() : logs);
        nextLogs.add(RunLogEntry.now(message));
        return new AgentRun(
                id,
                taskId,
                agentProfileId,
                provider,
                externalAgentId,
                externalRunId,
                status,
                pullRequestUrl,
                resultSummary,
                startedAt,
                completedAt,
                expectedHeadBranch,
                nextLogs
        );
    }

    /** Replaces log lines (e.g. when refreshing a local Ollama run with accumulated worker logs). */
    public AgentRun withLogs(List<RunLogEntry> nextLogs) {
        return new AgentRun(
                id,
                taskId,
                agentProfileId,
                provider,
                externalAgentId,
                externalRunId,
                status,
                pullRequestUrl,
                resultSummary,
                startedAt,
                completedAt,
                expectedHeadBranch,
                nextLogs == null ? new ArrayList<>() : new ArrayList<>(nextLogs)
        );
    }
}
