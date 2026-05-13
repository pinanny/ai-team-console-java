package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchParseUtilTest {

    @Test
    void extractsDiffAfterGitHeader() {
        String out = "Here\n\ndiff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-old\n+new\n";
        String d = PatchParseUtil.extractUnifiedDiff(out);
        assertTrue(d.startsWith("diff --git"));
        assertTrue(d.contains("+new"));
    }

    @Test
    void extractsDiffFromFence() {
        String out = "text\n```diff\ndiff --git a/x b/x\n--- a/x\n+++ b/x\n@@\n-a\n+b\n```\n";
        String d = PatchParseUtil.extractUnifiedDiff(out);
        assertTrue(d.startsWith("diff --git"));
    }

    @Test
    void extractsDiffFromMarkerDelimiters() {
        String out = "prefix\n<<<DIFF>>>\ndiff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n<<<END_DIFF>>>\n";
        String d = PatchParseUtil.extractUnifiedDiff(out);
        assertTrue(d.startsWith("diff --git"));
        assertTrue(d.contains("+b"));
    }

    @Test
    void stripsTrailingProseAfterDiff() {
        String out = "diff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-old\n+new\n\nHope this helps!\n";
        String d = PatchParseUtil.extractUnifiedDiff(out);
        assertTrue(d.contains("+new"));
        assertFalse(d.contains("Hope this helps"));
    }

    @Test
    void extractsNewFileBlock() {
        String diff = "diff --git a/docs/NOTES.md b/docs/NOTES.md\n"
                + "new file mode 100644\n"
                + "--- /dev/null\n"
                + "+++ b/docs/NOTES.md\n"
                + "@@ -0,0 +1,3 @@\n"
                + "+hello\n"
                + "+world\n"
                + "+!\n";
        var blocks = PatchParseUtil.extractNewFileBlocks(diff);
        assertTrue(blocks.size() == 1);
        assertTrue(blocks.get(0).path().equals("docs/NOTES.md"));
        assertTrue(blocks.get(0).content().equals("hello\nworld\n!\n"));
    }

    @Test
    void ignoresExistingFileModification() {
        String diff = "diff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-x\n+y\n";
        var blocks = PatchParseUtil.extractNewFileBlocks(diff);
        assertTrue(blocks.isEmpty());
    }

    @Test
    void extractsAdditionsEvenWhenHeaderIsModification() {
        String diff = "diff --git a/src/main/resources/README.md b/src/main/resources/README.md\n"
                + "--- a/src/main/resources/README.md\n"
                + "+++ b/src/main/resources/README.md\n"
                + "@@ -0,0 +1,2 @@\n"
                + "+hello\n"
                + "+world\n";
        var blocks = PatchParseUtil.extractAdditionsByPath(diff);
        assertTrue(blocks.size() == 1);
        assertTrue(blocks.get(0).path().equals("src/main/resources/README.md"));
        assertTrue(blocks.get(0).content().equals("hello\nworld\n"));
    }

    @Test
    void normalizesCrLfInExtractedDiff() {
        String out = "diff --git a/x b/x\r\n--- a/x\r\n+++ b/x\r\n@@ -1 +1 @@\r\n-a\r\n+b\r\n";
        String d = PatchParseUtil.extractUnifiedDiff(out);
        assertFalse(d.contains("\r"));
        assertTrue(d.endsWith("\n"));
    }
}
