package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists GitHub OAuth session and OAuth app client id under ~/.ai-team-console-java/
 */
public final class GitHubJsonStore {
    private static final String SESSION_FILE = "github-session.json";
    private static final String OAUTH_APP_FILE = "github-oauth-app.json";

    private final ObjectMapper mapper;
    private final Path directory;

    public GitHubJsonStore(Path directory) {
        this.directory = directory;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static GitHubJsonStore defaultStore() {
        return new GitHubJsonStore(StateStore.defaultStore().stateFile().getParent());
    }

    public Optional<GitHubSession> loadSession() {
        Path file = directory.resolve(SESSION_FILE);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), GitHubSession.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + file, e);
        }
    }

    public void saveSession(GitHubSession session) {
        try {
            Files.createDirectories(directory);
            mapper.writeValue(directory.resolve(SESSION_FILE).toFile(), session);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save GitHub session", e);
        }
    }

    public void clearSession() {
        try {
            Files.deleteIfExists(directory.resolve(SESSION_FILE));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to remove GitHub session", e);
        }
    }

    public GitHubOAuthAppSettings loadOAuthAppSettings() {
        Path file = directory.resolve(OAUTH_APP_FILE);
        if (!Files.exists(file)) {
            return GitHubOAuthAppSettings.empty();
        }
        try {
            return mapper.readValue(file.toFile(), GitHubOAuthAppSettings.class).normalized();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + file, e);
        }
    }

    public void saveOAuthAppSettings(GitHubOAuthAppSettings settings) {
        try {
            Files.createDirectories(directory);
            mapper.writeValue(directory.resolve(OAUTH_APP_FILE).toFile(), settings.normalized());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save GitHub OAuth app settings", e);
        }
    }
}
