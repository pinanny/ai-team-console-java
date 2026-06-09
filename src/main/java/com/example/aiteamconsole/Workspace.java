package com.example.aiteamconsole;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Groups registered {@linkplain RepositoryEntry repositories} so tasks/runs/memory views
 * can focus on one product while sharing the same agents and GitHub connection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Workspace(
        UUID id,
        String name,
        List<UUID> repositoryIds,
        Instant createdAt
) {
    public Workspace {
        name = name == null ? "" : name.strip();
        repositoryIds = repositoryIds == null ? new ArrayList<>() : new ArrayList<>(repositoryIds);
    }

    public static Workspace create(String name, List<UUID> repositoryIds) {
        return new Workspace(
                UUID.randomUUID(),
                name == null ? "" : name.strip(),
                repositoryIds == null ? List.of() : new ArrayList<>(repositoryIds),
                Instant.now()
        );
    }

    public Workspace withFields(String nextName, List<UUID> nextRepositoryIds) {
        return new Workspace(
                id,
                nextName,
                nextRepositoryIds == null ? List.of() : new ArrayList<>(nextRepositoryIds),
                createdAt
        );
    }
}
