package com.example.aiteamconsole;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record AppState(
        List<AgentProfile> agents,
        List<AgentTask> tasks,
        List<AgentRun> runs,
        List<RepositoryEntry> repositories,
        List<Workspace> workspaces,
        UUID activeWorkspaceId
) {
    public AppState {
        agents = agents == null ? new ArrayList<>() : agents;
        tasks = tasks == null ? new ArrayList<>() : tasks;
        runs = runs == null ? new ArrayList<>() : runs;
        repositories = repositories == null ? new ArrayList<>() : repositories;
        workspaces = workspaces == null ? new ArrayList<>() : workspaces;
    }

    public static AppState empty() {
        return new AppState(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
    }
}
