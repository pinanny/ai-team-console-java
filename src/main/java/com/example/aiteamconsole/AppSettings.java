package com.example.aiteamconsole;

public record AppSettings(
        String cursorApiKey,
        String cursorBaseUrl
) {
    public static AppSettings fromEnvironment() {
        return new AppSettings(
                System.getenv().getOrDefault("CURSOR_API_KEY", ""),
                System.getenv().getOrDefault("CURSOR_API_BASE_URL", "https://api.cursor.com")
        ).normalized();
    }

    /**
     * Replaces API key (e.g. value typed in the UI); keeps {@link #cursorBaseUrl} from this instance.
     */
    public AppSettings withApiKey(String key) {
        return new AppSettings(key == null ? "" : key, cursorBaseUrl);
    }

    public AppSettings normalized() {
        String baseUrl = cursorBaseUrl == null || cursorBaseUrl.isBlank()
                ? "https://api.cursor.com"
                : cursorBaseUrl.strip();
        return new AppSettings(nullToBlank(cursorApiKey).strip(), baseUrl);
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
