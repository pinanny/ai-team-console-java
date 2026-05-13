package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists Claude model and base URL under {@code ~/.ai-team-console-java/claude-settings.json}.
 * The API key is never written to disk.
 */
public final class ClaudeApiStore {
    private static final Path DEFAULT_PATH = Path.of(
            System.getProperty("user.home"), ".ai-team-console-java", "claude-settings.json");

    private final ObjectMapper mapper;
    private final Path file;

    public ClaudeApiStore(Path path) {
        this.file = path;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static ClaudeApiStore defaultStore() {
        return new ClaudeApiStore(DEFAULT_PATH);
    }

    public ClaudeApiSettings load() {
        if (!Files.exists(file)) {
            return ClaudeApiSettings.defaults();
        }
        try {
            ClaudeApiDiskFields disk = mapper.readValue(file.toFile(), ClaudeApiDiskFields.class);
            ClaudeApiSettings d = ClaudeApiSettings.defaults();
            String model = disk.model == null || disk.model.isBlank() ? d.model() : disk.model.strip();
            String baseUrl = disk.baseUrl == null || disk.baseUrl.isBlank() ? d.baseUrl() : disk.baseUrl.strip();
            return new ClaudeApiSettings("", model, baseUrl);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + file, e);
        }
    }

    public void save(ClaudeApiSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings");
        }
        ClaudeApiSettings normalized = ClaudeApiSettings.defaults()
                .withModel(settings.model())
                .withBaseUrl(settings.baseUrl());
        ClaudeApiDiskFields disk = new ClaudeApiDiskFields(normalized.model(), normalized.baseUrl());
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writeValue(file.toFile(), disk);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save Claude API settings", e);
        }
    }

    public Path filePath() {
        return file;
    }
}
