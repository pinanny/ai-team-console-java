package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ensures older {@code state.json} without newer {@link AgentTask} fields still loads.
 */
class AppStateAssigneeHistoryBackwardCompatTest {

    @Test
    void taskWithoutAssigneeHistoryFieldLoadsWithEmptyHistory() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = """
                {
                  "agents": [],
                  "tasks": [{
                    "id": "550e8400-e29b-41d4-a716-446655440000",
                    "taskPrefix": "BE-TASK",
                    "taskNumber": 1,
                    "title": "t",
                    "description": "",
                    "repositoryUrl": "",
                    "startingRef": "",
                    "assignedAgentId": null,
                    "status": "DRAFT",
                    "createdAt": "2024-01-01T00:00:00Z",
                    "updatedAt": "2024-01-01T00:00:00Z",
                    "startedAt": null,
                    "endedAt": null,
                    "repositoryTags": []
                  }],
                  "runs": [],
                  "repositories": []
                }
                """;
        AppState state = mapper.readValue(json, AppState.class);
        assertTrue(state.tasks().getFirst().assigneeHistory().isEmpty());
    }

    @Test
    void taskWithoutDevelopmentFlowLoadsEmptyPipeline() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = """
                {
                  "agents": [],
                  "tasks": [{
                    "id": "550e8400-e29b-41d4-a716-446655440001",
                    "taskPrefix": "BE-TASK",
                    "taskNumber": 2,
                    "title": "t",
                    "description": "",
                    "repositoryUrl": "",
                    "startingRef": "",
                    "assignedAgentId": null,
                    "status": "DRAFT",
                    "createdAt": "2024-01-01T00:00:00Z",
                    "updatedAt": "2024-01-01T00:00:00Z",
                    "startedAt": null,
                    "endedAt": null,
                    "repositoryTags": [],
                    "assigneeHistory": []
                  }],
                  "runs": [],
                  "repositories": []
                }
                """;
        AppState state = mapper.readValue(json, AppState.class);
        AgentTask t = state.tasks().getFirst();
        assertTrue(t.developmentFlow().isEmpty());
        assertEquals(0, t.developmentFlowWaveIndex());
    }
}
