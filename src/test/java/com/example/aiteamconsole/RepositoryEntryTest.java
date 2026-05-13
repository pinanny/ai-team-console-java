package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositoryEntryTest {
    @Test
    void parseTagsLowercasesTrimsAndDeduplicates() {
        assertEquals(List.of("backend", "frontend"),
                RepositoryEntry.parseTags(" Backend , frontend, backend "));
    }

    @Test
    void parseTagsHandlesWhitespaceAndCommasMixed() {
        assertEquals(List.of("api", "ui"), RepositoryEntry.parseTags("API\nUI"));
    }

    @Test
    void formatTagsJoinsWithCommas() {
        assertEquals("backend, frontend", RepositoryEntry.formatTags(List.of("backend", "frontend")));
    }

    @Test
    void createNormalizesUrlNameTagAndDefaultBranch() {
        RepositoryEntry entry = RepositoryEntry.create(
                "https://github.com/Org/Repo.git",
                "Main repo",
                ""
        );
        assertEquals("https://github.com/Org/Repo", entry.url());
        assertEquals("Main repo", entry.label());
        assertEquals("main-repo", entry.tag());
        assertEquals("main", entry.defaultBranch());
    }
}
