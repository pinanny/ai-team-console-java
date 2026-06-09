package com.example.aiteamconsole;

public record AppSettings(
        String cursorApiKey,
        String cursorBaseUrl,
        /**
         * Optional Cursor Cloud Agents model ID to send in the {@code model.id} field of
         * {@code POST /v1/agents}. When blank, the field is omitted entirely and Cursor uses
         * its own default model. Set this if Cursor requires a specific model ID
         * (e.g. {@code "claude-sonnet-4-5"}, {@code "gpt-4o"}).
         *
         * <p>Configurable in Settings → Cursor → Model ID.
         * Can also be set via env {@code CURSOR_CLOUD_MODEL_ID}.
         */
        String cursorCloudModelId
) {
    public static AppSettings fromEnvironment() {
        return new AppSettings(
                System.getenv().getOrDefault("CURSOR_API_KEY", ""),
                System.getenv().getOrDefault("CURSOR_API_BASE_URL", "https://api.cursor.com"),
                System.getenv().getOrDefault("CURSOR_CLOUD_MODEL_ID", "")
        ).normalized();
    }

    /**
     * Replaces API key; keeps other fields from this instance.
     */
    public AppSettings withApiKey(String key) {
        return new AppSettings(key == null ? "" : key, cursorBaseUrl, cursorCloudModelId);
    }

    public AppSettings normalized() {
        String baseUrl = cursorBaseUrl == null || cursorBaseUrl.isBlank()
                ? "https://api.cursor.com"
                : cursorBaseUrl.strip();
        String modelId = cursorCloudModelId == null ? "" : cursorCloudModelId.strip();
        return new AppSettings(nullToBlank(cursorApiKey).strip(), baseUrl, modelId);
    }

    /** Returns true if a specific model ID should be sent in the Cursor API request. */
    public boolean hasCursorModelId() {
        return cursorCloudModelId != null && !cursorCloudModelId.isBlank();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
