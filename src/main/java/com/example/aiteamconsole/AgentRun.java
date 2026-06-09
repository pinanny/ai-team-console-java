package com.example.aiteamconsole;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentRun(
        UUID id,
        UUID taskId,
        UUID agentProfileId,
        ProviderType provider,
        String externalAgentId,
        String externalRunId,
        RunStatus status,
        @JsonProperty("pullRequestUrl")
        @JsonAlias({"pull_request_url", "prUrl", "pr_url", "github_pull_request_url"})
        String pullRequestUrl,
        String resultSummary,
        Instant startedAt,
        Instant completedAt,
        String expectedHeadBranch,
        /** When set, ties this run to a wave in {@link AgentTask#developmentFlow()} for orchestration. */
        Integer developmentFlowWaveIndex,
        /**
         * Estimated or measured input tokens for this run.
         * For Cursor Cloud: estimated as {@code promptChars / 4}.
         * For Ollama: real {@code prompt_eval_count} from the model response.
         * Defaults to 0 when not set (older persisted runs, or run not yet complete).
         */
        int estimatedInputTokens,
        /**
         * Estimated or measured output tokens.
         * For Cursor Cloud: estimated as {@code resultSummaryChars / 4}.
         * For Ollama: real {@code eval_count} from the model response.
         */
        int estimatedOutputTokens,
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
        return started(taskId, agentProfileId, provider, externalAgentId, externalRunId, expectedHeadBranch, null);
    }

    public static AgentRun started(
            UUID taskId,
            UUID agentProfileId,
            ProviderType provider,
            String externalAgentId,
            String externalRunId,
            String expectedHeadBranch,
            Integer developmentFlowWaveIndex
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
                developmentFlowWaveIndex,
                0,
                0,
                new ArrayList<>()
        ).appendLog("Started provider run %s/%s".formatted(externalAgentId, externalRunId));
    }

    /**
     * @param pullRequestUrl {@code null} keeps the existing URL; any other value (including {@code ""}) replaces it.
     */
    public AgentRun withStatus(RunStatus nextStatus, String summary, String pullRequestUrl) {
        Instant completed = nextStatus.terminal() ? Instant.now() : completedAt;
        return new AgentRun(
                id, taskId, agentProfileId, provider,
                externalAgentId, externalRunId, nextStatus,
                pullRequestUrl == null ? this.pullRequestUrl : pullRequestUrl,
                summary == null ? resultSummary : summary,
                startedAt, completed, expectedHeadBranch,
                developmentFlowWaveIndex, estimatedInputTokens, estimatedOutputTokens, logs
        );
    }

    /**
     * Returns a copy with token metrics set. Call this after the run completes
     * so the final counts are persisted alongside the result.
     *
     * <p>For Cursor Cloud: {@code inputTokens = promptChars / 4}, {@code outputTokens = resultSummaryChars / 4}.
     * For Ollama: use real values from {@link OllamaHttpClient.OllamaChatResult}.
     */
    public AgentRun withTokens(int inputTokens, int outputTokens) {
        return new AgentRun(
                id, taskId, agentProfileId, provider,
                externalAgentId, externalRunId, status,
                pullRequestUrl, resultSummary, startedAt, completedAt,
                expectedHeadBranch, developmentFlowWaveIndex,
                Math.max(0, inputTokens), Math.max(0, outputTokens), logs
        );
    }

    /** Total estimated/measured tokens for this run (input + output). */
    public int totalTokens() {
        return estimatedInputTokens + estimatedOutputTokens;
    }

    /**
     * Duration in seconds from {@link #startedAt} to {@link #completedAt}.
     * Returns -1 if the run has not completed yet.
     */
    public long durationSeconds() {
        if (startedAt == null || completedAt == null) {
            return -1;
        }
        return completedAt.getEpochSecond() - startedAt.getEpochSecond();
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
                id, taskId, agentProfileId, provider,
                externalAgentId, externalRunId, status, pullRequestUrl, resultSummary,
                startedAt, completedAt, next, developmentFlowWaveIndex,
                estimatedInputTokens, estimatedOutputTokens, logs
        );
    }

    public AgentRun withDevelopmentFlowWaveIndex(Integer idx) {
        return new AgentRun(
                id, taskId, agentProfileId, provider,
                externalAgentId, externalRunId, status, pullRequestUrl, resultSummary,
                startedAt, completedAt, expectedHeadBranch, idx,
                estimatedInputTokens, estimatedOutputTokens, logs
        );
    }

    public AgentRun appendLog(String message) {
        List<RunLogEntry> nextLogs = new ArrayList<>(logs == null ? List.of() : logs);
        nextLogs.add(RunLogEntry.now(message));
        return new AgentRun(
                id, taskId, agentProfileId, provider,
                externalAgentId, externalRunId, status, pullRequestUrl, resultSummary,
                startedAt, completedAt, expectedHeadBranch, developmentFlowWaveIndex,
                estimatedInputTokens, estimatedOutputTokens, nextLogs
        );
    }

    /** Replaces log lines (e.g. when refreshing a local Ollama run with accumulated worker logs). */
    public AgentRun withLogs(List<RunLogEntry> nextLogs) {
        return new AgentRun(
                id, taskId, agentProfileId, provider,
                externalAgentId, externalRunId, status, pullRequestUrl, resultSummary,
                startedAt, completedAt, expectedHeadBranch, developmentFlowWaveIndex,
                estimatedInputTokens, estimatedOutputTokens,
                nextLogs == null ? new ArrayList<>() : new ArrayList<>(nextLogs)
        );
    }
}
