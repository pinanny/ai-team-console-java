package com.example.aiteamconsole;

public interface AgentProvider {
    AgentRun startTask(AgentProfile agent, AgentTask task, AppSettings settings) throws AgentProviderException;

    AgentRun refreshRun(AgentRun run, AppSettings settings) throws AgentProviderException;

    AgentRun cancelRun(AgentRun run, AppSettings settings) throws AgentProviderException;
}
