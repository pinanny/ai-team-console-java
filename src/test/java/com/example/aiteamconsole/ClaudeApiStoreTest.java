package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClaudeApiStoreTest {

    @Test
    void saveWritesOnlyModelAndBaseUrl(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("claude-settings.json");
        ClaudeApiStore store = new ClaudeApiStore(f);
        store.save(new ClaudeApiSettings("secret-key-never-on-disk", "claude-opus-4-6", "https://example.com"));
        String raw = Files.readString(f);
        assertFalse(raw.contains("secret"));
        assertFalse(raw.toLowerCase().contains("apikey"));
        ClaudeApiSettings loaded = store.load();
        assertEquals("", loaded.apiKey());
        assertEquals("claude-opus-4-6", loaded.model());
        assertEquals("https://example.com", loaded.baseUrl());
    }
}
