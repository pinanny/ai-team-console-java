package com.example.aiteamconsole.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainViewModelMergePlanDescriptionTest {

    @Test
    void appendsSectionWhenNoPriorPlannerBlock() {
        String out = MainViewModel.mergeImplementationPlanIntoDescription(
                "Refactor cache logic", "## PLAN_START\n## FILES_TO_TOUCH\n- EDIT Foo.java\n## PLAN_END");
        assertTrue(out.startsWith("Refactor cache logic"), out);
        assertTrue(out.contains("## Implementation plan"), out);
        assertTrue(out.contains("## PLAN_START"), out);
        assertTrue(out.contains("- EDIT Foo.java"), out);
    }

    @Test
    void replacesExistingPlannerBlockOnRerun() {
        String first = MainViewModel.mergeImplementationPlanIntoDescription(
                "Idea",
                "## PLAN_START\n- v1\n## PLAN_END");
        String second = MainViewModel.mergeImplementationPlanIntoDescription(
                first,
                "## PLAN_START\n- v2\n## PLAN_END");
        assertTrue(second.contains("- v2"), second);
        assertFalse(second.contains("- v1"), second);
        // Plan heading appears exactly once after a rerun.
        int firstIdx = second.indexOf("## Implementation plan");
        int lastIdx = second.lastIndexOf("## Implementation plan");
        assertTrue(firstIdx >= 0 && firstIdx == lastIdx, "plan heading duplicated: " + second);
    }

    @Test
    void mergedDescriptionRoundTripsThroughExtractor() {
        // The critical contract: after merge, AgentTaskPrompts.extractImplementationPlan
        // must still be able to find the PLAN_START/END block — that's how the executor
        // discovers a plan on the next Ollama run.
        String description = MainViewModel.mergeImplementationPlanIntoDescription(
                "background",
                "## PLAN_START\n## FILES_TO_TOUCH\n- EDIT A.java\n## PLAN_END");
        String extracted = com.example.aiteamconsole.AgentTaskPrompts.extractImplementationPlan(description);
        assertTrue(extracted.contains("- EDIT A.java"), extracted);
    }
}
