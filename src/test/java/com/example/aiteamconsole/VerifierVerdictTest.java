package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifierVerdictTest {

    @Test
    void parsesCleanPass() {
        VerifierVerdict v = VerifierVerdict.parse("{\"verdict\":\"pass\",\"failed_items\":[],\"notes\":\"\"}");
        assertTrue(v.parsed());
        assertEquals("pass", v.verdict());
        assertEquals(List.of(), v.failedItems());
        assertEquals("", v.notes());
        assertFalse(v.failed());
    }

    @Test
    void parsesFailWithItemsAndNotes() {
        VerifierVerdict v = VerifierVerdict.parse("{\"verdict\":\"fail\",\"failed_items\":[1,3],\"notes\":\"missing import\"}");
        assertTrue(v.parsed());
        assertTrue(v.failed());
        assertEquals(List.of(1, 3), v.failedItems());
        assertEquals("missing import", v.notes());
    }

    @Test
    void toleratesMarkdownFences() {
        String raw = "```json\n{\"verdict\":\"pass\",\"failed_items\":[],\"notes\":\"\"}\n```";
        VerifierVerdict v = VerifierVerdict.parse(raw);
        assertTrue(v.parsed());
        assertEquals("pass", v.verdict());
    }

    @Test
    void toleratesLeadingProse() {
        String raw = "Here is my verdict: {\"verdict\":\"fail\",\"failed_items\":[2],\"notes\":\"x\"}";
        VerifierVerdict v = VerifierVerdict.parse(raw);
        assertTrue(v.parsed());
        assertTrue(v.failed());
        assertEquals(List.of(2), v.failedItems());
    }

    @Test
    void returnsUnparsedOnGarbage() {
        assertFalse(VerifierVerdict.parse("totally not JSON").parsed());
        assertFalse(VerifierVerdict.parse("").parsed());
        assertFalse(VerifierVerdict.parse(null).parsed());
        // Valid JSON but no verdict field → still unparsed (caller treats as ambiguous).
        assertFalse(VerifierVerdict.parse("{\"foo\":1}").parsed());
    }

    @Test
    void parsesFailedItemsGivenAsStrings() {
        // Small models sometimes emit "failed_items":["2","4"] instead of integers.
        VerifierVerdict v = VerifierVerdict.parse("{\"verdict\":\"fail\",\"failed_items\":[\"2\",\"4\"],\"notes\":\"\"}");
        assertTrue(v.parsed());
        assertEquals(List.of(2, 4), v.failedItems());
    }
}
