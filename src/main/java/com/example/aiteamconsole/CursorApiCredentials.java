package com.example.aiteamconsole;

/**
 * Opt-in local storage of the Cursor API key. Persisted only when the user clicks "Save API key"
 * in Settings — never written automatically. Stored at {@code ~/.ai-team-console-java/cursor-api.json}.
 */
public record CursorApiCredentials(String apiKey) {
    public static CursorApiCredentials empty() {
        return new CursorApiCredentials("");
    }

    public CursorApiCredentials normalized() {
        return new CursorApiCredentials(apiKey == null ? "" : apiKey.strip());
    }
}
