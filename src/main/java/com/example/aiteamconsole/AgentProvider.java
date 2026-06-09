package com.example.aiteamconsole;

public interface AgentProvider {
    default AgentRun startTask(AgentProfile agent, AgentTask task, AppSettings settings) throws AgentProviderException {
        return startTask(agent, task, settings, TaskLaunchHints.none());
    }

    AgentRun startTask(AgentProfile agent, AgentTask task, AppSettings settings, TaskLaunchHints hints)
            throws AgentProviderException;

    AgentRun refreshRun(AgentRun run, AppSettings settings) throws AgentProviderException;

    AgentRun cancelRun(AgentRun run, AppSettings settings) throws AgentProviderException;
}
