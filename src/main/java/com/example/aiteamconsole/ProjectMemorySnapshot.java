package com.example.aiteamconsole;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Immutable snapshot of pre-analyzed project context for one repository.
 * Stored by {@link ProjectMemoryStore} and injected into every agent prompt
 * so agents do NOT need to re-read the entire codebase on each task.
 *
 * <p>The snapshot is split into three detail tiers so the console can inject
 * the cheapest tier sufficient for a given role (token-saving optimization):
 *
 * <ul>
 *   <li>{@code briefContext}    – ultra-compact summary (~200–400 tokens).
 *                                 Suitable for review-only and QA runs where
 *                                 the agent does not write production code.</li>
 *   <li>{@code coreContext}     – default working memory (~1 000–1 500 tokens).
 *                                 Stack + Architecture + Key patterns. Used by
 *                                 implementing roles (Backend / Frontend / PA).</li>
 *   <li>{@code extendedContext} – full long-form analysis (≤ ~3 000 tokens).
 *                                 Used only on demand (deep refactor, onboarding
 *                                 reanalysis). Never injected by default.</li>
 * </ul>
 *
 * <p>Backwards compatibility: snapshots persisted by older builds only have
 * {@code coreContext}. When deserialized, {@code briefContext} and
 * {@code extendedContext} default to empty strings, and {@link #briefOrCore()}
 * and {@link #extendedOrCore()} transparently fall back to {@code coreContext}.
 * Existing on-disk JSON files keep working without migration.
 */
public record ProjectMemorySnapshot(
        String repoUrl,
        String briefContext,
        String coreContext,
        String extendedContext,
        Instant lastUpdatedAt
) {
    @JsonCreator
    public ProjectMemorySnapshot(
            @JsonProperty("repoUrl") String repoUrl,
            @JsonProperty("briefContext") String briefContext,
            @JsonProperty("coreContext") String coreContext,
            @JsonProperty("extendedContext") String extendedContext,
            @JsonProperty("lastUpdatedAt") Instant lastUpdatedAt
    ) {
        this.repoUrl = repoUrl == null ? "" : repoUrl.strip();
        this.briefContext = briefContext == null ? "" : briefContext;
        this.coreContext = coreContext == null ? "" : coreContext;
        this.extendedContext = extendedContext == null ? "" : extendedContext;
        this.lastUpdatedAt = lastUpdatedAt == null ? Instant.EPOCH : lastUpdatedAt;
    }

    /** Returns true if this snapshot contains any non-empty tier. */
    public boolean hasContext() {
        return !coreContext.isBlank() || !briefContext.isBlank() || !extendedContext.isBlank();
    }

    /**
     * Returns the brief tier if it exists, otherwise falls back to {@code coreContext}.
     * This is the cheapest text suitable to inject into review-only or QA runs.
     */
    public String briefOrCore() {
        return briefContext.isBlank() ? coreContext : briefContext;
    }

    /**
     * Returns the extended tier if it exists, otherwise falls back to {@code coreContext}.
     */
    public String extendedOrCore() {
        return extendedContext.isBlank() ? coreContext : extendedContext;
    }

    /** Creates an empty placeholder snapshot for a given repo URL. */
    public static ProjectMemorySnapshot empty(String repoUrl) {
        return new ProjectMemorySnapshot(repoUrl, "", "", "", Instant.EPOCH);
    }

    /**
     * Creates a snapshot with the given core context, timestamped now.
     * The brief and extended tiers are left empty (callers may add them via
     * {@link #withBrief(String)} / {@link #withExtended(String)} or by using
     * {@link #ofMultiLevel(String, String, String, String)}).
     */
    public static ProjectMemorySnapshot of(String repoUrl, String coreContext) {
        return new ProjectMemorySnapshot(repoUrl, "", coreContext, "", Instant.now());
    }

    /**
     * Creates a fully-populated snapshot with all three tiers, timestamped now.
     * Any {@code null} tier is normalized to an empty string by the canonical constructor.
     */
    public static ProjectMemorySnapshot ofMultiLevel(
            String repoUrl,
            String briefContext,
            String coreContext,
            String extendedContext
    ) {
        return new ProjectMemorySnapshot(
                repoUrl, briefContext, coreContext, extendedContext, Instant.now());
    }

    /** Returns a copy with the brief tier replaced. */
    public ProjectMemorySnapshot withBrief(String newBrief) {
        return new ProjectMemorySnapshot(
                repoUrl, newBrief == null ? "" : newBrief, coreContext, extendedContext, lastUpdatedAt);
    }

    /** Returns a copy with the extended tier replaced. */
    public ProjectMemorySnapshot withExtended(String newExtended) {
        return new ProjectMemorySnapshot(
                repoUrl, briefContext, coreContext, newExtended == null ? "" : newExtended, lastUpdatedAt);
    }
}
