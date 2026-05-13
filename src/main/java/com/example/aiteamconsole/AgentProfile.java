package com.example.aiteamconsole;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public record AgentProfile(
        UUID id,
        String name,
        AgentRole role,
        ProviderType provider,
        String repositoryUrl,
        String startingRef,
        String branchPrefix,
        boolean autoCreatePr,
        Instant createdAt,
        List<String> repositoryTags
) {
    public AgentProfile {
        repositoryTags = normalizeTags(repositoryTags);
    }

    public static AgentProfile create(
            String name,
            AgentRole role,
            ProviderType provider,
            String repositoryUrl,
            String startingRef,
            String branchPrefix,
            boolean autoCreatePr
    ) {
        String resolvedPrefix = branchPrefix == null || branchPrefix.isBlank()
                ? autoBranchPrefix(name)
                : branchPrefix.strip();
        return new AgentProfile(
                UUID.randomUUID(),
                name,
                role,
                provider,
                repositoryUrl,
                startingRef == null ? "" : startingRef.strip(),
                resolvedPrefix,
                autoCreatePr,
                Instant.now(),
                List.of()
        );
    }

    public static AgentProfile create(
            String name,
            AgentRole role,
            ProviderType provider,
            String repositoryUrl,
            String startingRef,
            String branchPrefix,
            boolean autoCreatePr,
            List<String> repositoryTags
    ) {
        String resolvedPrefix = branchPrefix == null || branchPrefix.isBlank()
                ? autoBranchPrefix(name)
                : branchPrefix.strip();
        return new AgentProfile(
                UUID.randomUUID(),
                name,
                role,
                provider,
                repositoryUrl,
                startingRef == null ? "" : startingRef.strip(),
                resolvedPrefix,
                autoCreatePr,
                Instant.now(),
                normalizeTags(repositoryTags)
        );
    }

    public AgentProfile withEditableFields(String nextName, AgentRole nextRole, List<String> nextRepositoryTags) {
        return withEditableFields(nextName, nextRole, provider, nextRepositoryTags);
    }

    public AgentProfile withEditableFields(
            String nextName,
            AgentRole nextRole,
            ProviderType nextProvider,
            List<String> nextRepositoryTags
    ) {
        return new AgentProfile(
                id,
                nextName,
                nextRole,
                nextProvider,
                repositoryUrl,
                startingRef,
                branchPrefix,
                autoCreatePr,
                createdAt,
                normalizeTags(nextRepositoryTags)
        );
    }

    /**
     * Derives a git branch prefix from the agent name, prefixed with {@code ia-agent-}, slug-safe.
     */
    public static String autoBranchPrefix(String name) {
        if (name == null || name.isBlank()) {
            return "ia-agent";
        }
        String slug = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "ia-agent" : "ia-agent-" + slug;
    }

    private static List<String> normalizeTags(List<String> raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String tag : raw) {
            if (tag == null) {
                continue;
            }
            String t = tag.strip().toLowerCase();
            if (!t.isEmpty()) {
                ordered.add(t);
            }
        }
        return new ArrayList<>(ordered);
    }
}
