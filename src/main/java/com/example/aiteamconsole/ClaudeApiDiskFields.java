package com.example.aiteamconsole;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Serializable subset persisted to {@code claude-settings.json} (never contains the API key).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
final class ClaudeApiDiskFields {
    public String model;
    public String baseUrl;

    ClaudeApiDiskFields() {
    }

    ClaudeApiDiskFields(String model, String baseUrl) {
        this.model = model;
        this.baseUrl = baseUrl;
    }
}
