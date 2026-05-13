package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunStatusTest {
    @Test
    void mapsCursorStatuses() {
        assertEquals(RunStatus.CREATING, RunStatus.fromCursorStatus("CREATING"));
        assertEquals(RunStatus.RUNNING, RunStatus.fromCursorStatus("running"));
        assertEquals(RunStatus.FINISHED, RunStatus.fromCursorStatus("FINISHED"));
        assertEquals(RunStatus.CANCELLED, RunStatus.fromCursorStatus("canceled"));
        assertEquals(RunStatus.UNKNOWN, RunStatus.fromCursorStatus("something-new"));
    }

    @Test
    void identifiesTerminalStatuses() {
        assertTrue(RunStatus.FINISHED.terminal());
        assertTrue(RunStatus.CANCELLED.terminal());
        assertTrue(RunStatus.ERROR.terminal());
    }
}
