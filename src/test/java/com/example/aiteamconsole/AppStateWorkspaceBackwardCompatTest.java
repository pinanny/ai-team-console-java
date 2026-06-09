package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppStateWorkspaceBackwardCompatTest {

    @Test
    void legacyStateJsonWithoutWorkspacesLoads() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = """
                {
                  "agents": [],
                  "tasks": [],
                  "runs": [],
                  "repositories": []
                }
                """;
        AppState state = mapper.readValue(json, AppState.class);
        assertTrue(state.workspaces().isEmpty());
        assertNull(state.activeWorkspaceId());
    }
}
