package com.example.aiteamconsole;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a task + agent pair to the list of repository URLs that should be sent to the underlying provider.
 * Default behavior preserves legacy single-repo semantics: task URL if set, otherwise agent URL.
 */
@FunctionalInterface
public interface RepositoryResolver {
    List<String> resolve(AgentTask task, AgentProfile agent);

    /** Legacy single-repo resolution used when the application has not registered a tag-aware resolver. */
    RepositoryResolver DEFAULT = (task, agent) -> {
        List<String> result = new ArrayList<>();
        String taskUrl = task == null ? "" : task.repositoryUrl();
        if (taskUrl != null && !taskUrl.isBlank()) {
            result.add(GitHubRepoUrls.normalizeHttpsRepositoryUrl(taskUrl));
            return result;
        }
        String agentUrl = agent == null ? "" : agent.repositoryUrl();
        if (agentUrl != null && !agentUrl.isBlank()) {
            result.add(GitHubRepoUrls.normalizeHttpsRepositoryUrl(agentUrl));
        }
        return result;
    };
}
