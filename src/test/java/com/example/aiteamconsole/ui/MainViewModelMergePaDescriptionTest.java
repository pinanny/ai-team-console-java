package com.example.aiteamconsole.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MainViewModelMergePaDescriptionTest {

    @Test
    void appendsSectionWhenNoPriorAnalystBlock() {
        String out = MainViewModel.mergeProductAnalystOutputIntoDescription("Refactoring", "- Problem: X\n- AC: Y");
        assertTrue(out.startsWith("Refactoring"));
        assertTrue(out.contains("## Product Analyst output"));
        assertTrue(out.contains("- Problem: X"));
    }

    @Test
    void replacesExistingAnalystBlockOnRerun() {
        String first = MainViewModel.mergeProductAnalystOutputIntoDescription("Idea", "Version A");
        String second = MainViewModel.mergeProductAnalystOutputIntoDescription(first, "Version B");
        assertTrue(second.contains("Version B"));
        assertTrue(!second.contains("Version A"));
    }
}
