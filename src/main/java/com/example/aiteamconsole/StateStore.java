package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StateStore {
    private final ObjectMapper mapper;
    private final Path stateFile;

    public StateStore(Path stateFile) {
        this.stateFile = stateFile;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static StateStore defaultStore() {
        Path file = Path.of(System.getProperty("user.home"), ".ai-team-console-java", "state.json");
        return new StateStore(file);
    }

    public AppState load() {
        if (!Files.exists(stateFile)) {
            return AppState.empty();
        }
        try {
            return mapper.readValue(stateFile.toFile(), AppState.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load state from " + stateFile, e);
        }
    }

    public void save(AppState state) {
        try {
            Files.createDirectories(stateFile.getParent());
            mapper.writeValue(stateFile.toFile(), state);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save state to " + stateFile, e);
        }
    }

    public Path stateFile() {
        return stateFile;
    }
}
