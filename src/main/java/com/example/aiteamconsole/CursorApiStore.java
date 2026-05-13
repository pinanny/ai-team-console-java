package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists the Cursor API key under {@code ~/.ai-team-console-java/cursor-api.json}.
 * The file is created only when the user explicitly clicks "Save API key" in Settings.
 */
public final class CursorApiStore {
    private static final String FILE_NAME = "cursor-api.json";

    private final ObjectMapper mapper;
    private final Path directory;

    public CursorApiStore(Path directory) {
        this.directory = directory;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static CursorApiStore defaultStore() {
        return new CursorApiStore(StateStore.defaultStore().stateFile().getParent());
    }

    public CursorApiCredentials load() {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return CursorApiCredentials.empty();
        }
        try {
            return mapper.readValue(file.toFile(), CursorApiCredentials.class).normalized();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + file, e);
        }
    }

    public void save(CursorApiCredentials credentials) {
        try {
            Files.createDirectories(directory);
            mapper.writeValue(directory.resolve(FILE_NAME).toFile(), credentials.normalized());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save Cursor API credentials", e);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(directory.resolve(FILE_NAME));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to remove Cursor API credentials", e);
        }
    }

    public Path filePath() {
        return directory.resolve(FILE_NAME);
    }
}
