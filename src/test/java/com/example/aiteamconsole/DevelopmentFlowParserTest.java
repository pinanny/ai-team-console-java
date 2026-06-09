package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentFlowParserTest {

    @Test
    void blankParsesToEmpty() {
        assertTrue(DevelopmentFlowParser.parse(null).isEmpty());
        assertTrue(DevelopmentFlowParser.parse("  \n\t").isEmpty());
    }

    @Test
    void oneRolePerLineIsSequentialWaves() {
        List<TaskFlowWave> waves = DevelopmentFlowParser.parse("BE\nFE\nQA");
        assertEquals(3, waves.size());
        assertEquals(List.of(AgentRole.BACKEND_ENGINEER), waves.get(0).parallelRoles());
        assertEquals(List.of(AgentRole.FRONTEND_ENGINEER), waves.get(1).parallelRoles());
        assertEquals(List.of(AgentRole.QA_ENGINEER), waves.get(2).parallelRoles());
    }

    @Test
    void commaMeansParallelInOneWave() {
        List<TaskFlowWave> waves = DevelopmentFlowParser.parse("FE, QA ,REV");
        assertEquals(1, waves.size());
        assertEquals(
                List.of(AgentRole.FRONTEND_ENGINEER, AgentRole.QA_ENGINEER, AgentRole.CODE_REVIEWER),
                waves.getFirst().parallelRoles());
    }

    @Test
    void memTokenMapsProjectMemory() {
        List<TaskFlowWave> waves = DevelopmentFlowParser.parse("MEM");
        assertEquals(List.of(AgentRole.PROJECT_MEMORY), waves.getFirst().parallelRoles());
    }

    @Test
    void planTokenMapsImplementationPlanner() {
        List<TaskFlowWave> waves = DevelopmentFlowParser.parse("PLAN");
        assertEquals(List.of(AgentRole.IMPLEMENTATION_PLANNER), waves.getFirst().parallelRoles());
    }

    @Test
    void planThenBeFormsTwoWavePipeline() {
        // The canonical token-optimization pipeline: expensive planner first, cheap executor second.
        List<TaskFlowWave> waves = DevelopmentFlowParser.parse("PLAN\nBE");
        assertEquals(2, waves.size());
        assertEquals(List.of(AgentRole.IMPLEMENTATION_PLANNER), waves.get(0).parallelRoles());
        assertEquals(List.of(AgentRole.BACKEND_ENGINEER), waves.get(1).parallelRoles());
        // Round-trip through format() preserves the PLAN token.
        assertEquals("PLAN\nBE", DevelopmentFlowParser.format(waves));
    }

    @Test
    void pmAliasStillProductAnalyst() {
        List<TaskFlowWave> waves = DevelopmentFlowParser.parse("PM");
        assertEquals(List.of(AgentRole.PRODUCT_ANALYST), waves.getFirst().parallelRoles());
    }

    @Test
    void unknownTokenThrows() {
        assertThrows(IllegalArgumentException.class, () -> DevelopmentFlowParser.parse("BE\nFOO"));
    }

    @Test
    void formatRoundTrip() {
        List<TaskFlowWave> waves = DevelopmentFlowParser.parse("BE\nDEVOPS, FE");
        String text = DevelopmentFlowParser.format(waves);
        assertEquals("BE\nDEVOPS, FE", text);
        assertEquals(waves, DevelopmentFlowParser.parse(text));
    }
}
