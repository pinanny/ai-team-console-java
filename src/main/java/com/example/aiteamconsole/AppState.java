package com.example.aiteamconsole;

import java.util.ArrayList;
import java.util.List;

public record AppState(
        List<AgentProfile> agents,
        List<AgentTask> tasks,
        List<AgentRun> runs,
        List<RepositoryEntry> repositories
) {
    public AppState {
        agents = agents == null ? new ArrayList<>() : agents;
        tasks = tasks == null ? new ArrayList<>() : tasks;
        runs = runs == null ? new ArrayList<>() : runs;
        repositories = repositories == null ? new ArrayList<>() : repositories;
    }

    public static AppState empty() {
        return new AppState(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }
}
