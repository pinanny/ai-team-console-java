package com.example.aiteamconsole;

import java.nio.file.Path;

public final class ProviderRegistry {
    private final CursorCloudAgentProvider cursorCloudAgentProvider;
    private final OllamaAgentProvider ollamaAgentProvider;
    private final ClaudeApiAgentProvider claudeApiAgentProvider;

    public ProviderRegistry() {
        this(
                new CursorCloudAgentProvider(),
                defaultOllamaProvider(),
                ClaudeApiAgentProvider.withDefaultWorkspace(GitHubJsonStore.defaultStore()));
    }

    ProviderRegistry(
            CursorCloudAgentProvider cursorCloudAgentProvider,
            OllamaAgentProvider ollamaAgentProvider,
            ClaudeApiAgentProvider claudeApiAgentProvider
    ) {
        this.cursorCloudAgentProvider = cursorCloudAgentProvider;
        this.ollamaAgentProvider = ollamaAgentProvider;
        this.claudeApiAgentProvider = claudeApiAgentProvider;
    }

    private static OllamaAgentProvider defaultOllamaProvider() {
        Path root = Path.of(System.getProperty("user.home"), ".ai-team-console-java", "ollama-workspaces");
        return new OllamaAgentProvider(GitHubJsonStore.defaultStore(), root);
    }

    public AgentProvider providerFor(ProviderType providerType) {
        return switch (providerType) {
            case CURSOR_CLOUD -> cursorCloudAgentProvider;
            case OLLAMA -> ollamaAgentProvider;
            case CLAUDE_API -> claudeApiAgentProvider;
        };
    }

    public CursorCloudAgentProvider cursorCloudProvider() {
        return cursorCloudAgentProvider;
    }

    public OllamaAgentProvider ollamaProvider() {
        return ollamaAgentProvider;
    }

    public ClaudeApiAgentProvider claudeApiProvider() {
        return claudeApiAgentProvider;
    }
}
