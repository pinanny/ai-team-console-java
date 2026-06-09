package com.example.aiteamconsole;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Profile of a single AI agent registered in the console.
 *
 * <p>Jackson deserialization note: {@code @JsonIgnoreProperties(ignoreUnknown = true)} ensures
 * that old persisted JSON without newer fields (e.g. {@code ollamaModel}) can still be loaded.
 * Missing fields default to {@code null} and are normalized to safe defaults in the compact constructor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        List<String> repositoryTags,
        /**
         * Ollama model override for this agent (e.g. {@code "qwen2.5-coder:7b"}).
         * Empty string means "use the global model from Ollama settings".
         * Only relevant when {@link #provider()} is {@link ProviderType#OLLAMA}.
         */
        String ollamaModel
) {
    public AgentProfile {
        repositoryTags = normalizeTags(repositoryTags);
        ollamaModel = ollamaModel == null ? "" : ollamaModel.strip();
    }

    /** Returns the effective Ollama model: the agent's override if set, otherwise empty (caller uses global). */
    public String effectiveOllamaModel() {
        return ollamaModel == null ? "" : ollamaModel.strip();
    }

    public static AgentProfile create(
            String name, AgentRole role, ProviderType provider,
            String repositoryUrl, String startingRef, String branchPrefix, boolean autoCreatePr
    ) {
        return create(name, role, provider, repositoryUrl, startingRef, branchPrefix, autoCreatePr, List.of(), "");
    }

    public static AgentProfile create(
            String name, AgentRole role, ProviderType provider,
            String repositoryUrl, String startingRef, String branchPrefix,
            boolean autoCreatePr, List<String> repositoryTags
    ) {
        return create(name, role, provider, repositoryUrl, startingRef, branchPrefix, autoCreatePr, repositoryTags, "");
    }

    public static AgentProfile create(
            String name, AgentRole role, ProviderType provider,
            String repositoryUrl, String startingRef, String branchPrefix,
            boolean autoCreatePr, List<String> repositoryTags, String ollamaModel
    ) {
        String resolvedPrefix = branchPrefix == null || branchPrefix.isBlank()
                ? autoBranchPrefix(name)
                : branchPrefix.strip();
        return new AgentProfile(
                UUID.randomUUID(), name, role, provider,
                repositoryUrl, startingRef == null ? "" : startingRef.strip(),
                resolvedPrefix, autoCreatePr, Instant.now(),
                normalizeTags(repositoryTags), ollamaModel == null ? "" : ollamaModel.strip()
        );
    }

    public AgentProfile withEditableFields(String nextName, AgentRole nextRole, List<String> nextRepositoryTags) {
        return withEditableFields(nextName, nextRole, provider, nextRepositoryTags, ollamaModel);
    }

    public AgentProfile withEditableFields(
            String nextName, AgentRole nextRole, ProviderType nextProvider, List<String> nextRepositoryTags
    ) {
        return withEditableFields(nextName, nextRole, nextProvider, nextRepositoryTags, ollamaModel);
    }

    public AgentProfile withEditableFields(
            String nextName, AgentRole nextRole, ProviderType nextProvider,
            List<String> nextRepositoryTags, String nextOllamaModel
    ) {
        return new AgentProfile(
                id, nextName, nextRole, nextProvider,
                repositoryUrl, startingRef, branchPrefix, autoCreatePr, createdAt,
                normalizeTags(nextRepositoryTags),
                nextOllamaModel == null ? "" : nextOllamaModel.strip()
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
