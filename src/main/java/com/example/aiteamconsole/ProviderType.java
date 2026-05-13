package com.example.aiteamconsole;

public enum ProviderType {
    CURSOR_CLOUD("Cursor Cloud Agents"),
    /** Local model via Ollama HTTP API; git clone/commit/push runs on this machine (requires {@code git} and GitHub sign-in). */
    OLLAMA("Ollama (local)"),
    CLAUDE_API("Claude (Anthropic API)");

    private final String label;

    ProviderType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
