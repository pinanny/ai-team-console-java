package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists {@link ProjectMemorySnapshot} objects on disk, one file per repository.
 *
 * <p>Storage layout (default):
 * <pre>
 *   ~/.ai-team-console-java/
 *     project-memory/
 *       github.com__owner__repo.json   ← one snapshot per repo
 * </pre>
 *
 * <p>Usage pattern — one-time onboarding, then fast reads:
 * <ol>
 *   <li>On first project setup, dispatch a Product Analyst "onboarding" task that analyzes
 *       the codebase once and calls {@link #save(ProjectMemorySnapshot)}.</li>
 *   <li>On every subsequent agent task, call {@link #load(String)} to get the cached context
 *       and inject it into the prompt via {@link AgentTaskPrompts}.</li>
 * </ol>
 *
 * <p>This is intentionally provider-agnostic: the same store feeds Cursor Cloud Agents,
 * Ollama, and any future LLM provider through {@link AgentTaskPrompts}.
 */
public final class ProjectMemoryStore {

    private static final Logger LOG = AppLogging.get(ProjectMemoryStore.class);

    private final ObjectMapper mapper;
    private final Path storageDir;

    public ProjectMemoryStore(Path storageDir) {
        this.storageDir = storageDir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Default store location: {@code ~/.ai-team-console-java/project-memory/} */
    public static ProjectMemoryStore defaultStore() {
        Path dir = Path.of(System.getProperty("user.home"), ".ai-team-console-java", "project-memory");
        return new ProjectMemoryStore(dir);
    }

    /**
     * Loads the snapshot for the given repository URL.
     * Returns {@link Optional#empty()} if no snapshot exists yet.
     */
    public Optional<ProjectMemorySnapshot> load(String repoUrl) {
        Path file = fileFor(repoUrl);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            ProjectMemorySnapshot snapshot = mapper.readValue(file.toFile(), ProjectMemorySnapshot.class);
            LOG.info("ProjectMemoryStore: loaded snapshot for %s (updated %s, %d chars)"
                    .formatted(repoUrl, snapshot.lastUpdatedAt(), snapshot.coreContext().length()));
            return Optional.of(snapshot);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "ProjectMemoryStore: failed to read snapshot for " + repoUrl, e);
            return Optional.empty();
        }
    }

    /**
     * Saves (or overwrites) the snapshot for the repository referenced in the snapshot.
     */
    public void save(ProjectMemorySnapshot snapshot) {
        Path file = fileFor(snapshot.repoUrl());
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), snapshot);
            LOG.info("ProjectMemoryStore: saved snapshot for %s (%d chars, updated %s)"
                    .formatted(snapshot.repoUrl(), snapshot.coreContext().length(), snapshot.lastUpdatedAt()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save project memory snapshot to " + file, e);
        }
    }

    /**
     * Deletes the snapshot for the given repository URL (e.g. to force re-analysis).
     */
    public void delete(String repoUrl) {
        Path file = fileFor(repoUrl);
        try {
            Files.deleteIfExists(file);
            LOG.info("ProjectMemoryStore: deleted snapshot for " + repoUrl);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "ProjectMemoryStore: failed to delete snapshot for " + repoUrl, e);
        }
    }

    /**
     * Returns true if a snapshot exists for the given repository URL.
     */
    public boolean exists(String repoUrl) {
        return Files.exists(fileFor(repoUrl));
    }

    /** Returns the storage directory (useful for display in UI). */
    public Path storageDir() {
        return storageDir;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a repo URL like {@code https://github.com/owner/repo} to a safe filename:
     * {@code github.com__owner__repo.json}.
     * Double underscores replace slashes so the filename stays flat and readable.
     */
    private Path fileFor(String repoUrl) {
        String safe = slugFor(repoUrl);
        return storageDir.resolve(safe + ".json");
    }

    static String slugFor(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return "unknown-repo";
        }
        String url = repoUrl.strip().toLowerCase();
        // Strip protocol prefix
        url = url.replaceFirst("^https?://", "");
        // Strip trailing .git
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        // Strip trailing slash
        url = url.replaceAll("/+$", "");
        // Replace slashes with double underscore, keep alphanumeric, dots, dashes
        url = url.replace("/", "__").replaceAll("[^a-z0-9._\\-]", "_");
        return url;
    }
}
