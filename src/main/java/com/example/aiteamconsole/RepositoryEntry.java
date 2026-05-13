package com.example.aiteamconsole;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * A repository the user has registered in the app. Its display name is also the tag
 * used by tasks and agents to resolve repository URLs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RepositoryEntry(
        UUID id,
        String url,
        String displayName,
        String defaultBranch,
        Instant createdAt
) {
    public RepositoryEntry {
        url = GitHubRepoUrls.normalizeHttpsRepositoryUrl(url == null ? "" : url);
        displayName = displayName == null || displayName.isBlank() ? deriveNameFromUrl(url) : displayName.strip();
        defaultBranch = defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch.strip();
    }

    public static RepositoryEntry create(String url, String displayName, String defaultBranch) {
        return new RepositoryEntry(
                UUID.randomUUID(),
                GitHubRepoUrls.normalizeHttpsRepositoryUrl(url == null ? "" : url),
                displayName == null ? "" : displayName.strip(),
                defaultBranch == null ? "main" : defaultBranch.strip(),
                Instant.now()
        );
    }

    public RepositoryEntry withFields(String nextUrl, String nextDisplayName, String nextDefaultBranch) {
        return new RepositoryEntry(id, nextUrl, nextDisplayName, nextDefaultBranch, createdAt);
    }

    public RepositoryEntry withDisplayName(String nextDisplayName) {
        return new RepositoryEntry(id, url, nextDisplayName == null ? "" : nextDisplayName.strip(), defaultBranch, createdAt);
    }

    public String label() {
        return displayName == null || displayName.isBlank() ? url : displayName;
    }

    public String tag() {
        return normalizeTag(label());
    }

    /**
     * Parses a free-form comma/space separated tag string into a normalized lowercase list with stable order.
     */
    public static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return normalizeTags(List.of(raw.split("[,\\s]+")));
    }

    /**
     * Joins normalized tags back into the form used by the input field.
     */
    public static String formatTags(List<String> tags) {
        return tags == null ? "" : String.join(", ", tags);
    }

    private static String normalizeTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.strip().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String deriveNameFromUrl(String url) {
        return GitHubRepoUrls.parseSlug(url)
                .map(slug -> slug.repo())
                .orElse("");
    }

    private static List<String> normalizeTags(List<String> raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String tag : raw) {
            if (tag == null) {
                continue;
            }
            String t = tag.strip().toLowerCase();
            if (!t.isEmpty()) {
                ordered.add(t);
            }
        }
        return new ArrayList<>(ordered);
    }
}
