package com.example.aiteamconsole;

public record ClaudeApiSettings(
        String apiKey,
        String model,
        String baseUrl
) {
    public static ClaudeApiSettings defaults() {
        return new ClaudeApiSettings(
                System.getenv().getOrDefault("ANTHROPIC_API_KEY", ""),
                "claude-sonnet-4-6",
                "https://api.anthropic.com"
        );
    }

    public ClaudeApiSettings withApiKey(String key) {
        return new ClaudeApiSettings(key == null ? "" : key.strip(), model, baseUrl);
    }

    public ClaudeApiSettings withModel(String m) {
        return new ClaudeApiSettings(apiKey, m == null ? model : m.strip(), baseUrl);
    }

    public ClaudeApiSettings withBaseUrl(String u) {
        return new ClaudeApiSettings(apiKey, model, u == null ? baseUrl : u.strip());
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
