package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed verdict from the verifier pass that runs after a successful executor patch
 * (see {@link AgentTaskPrompts#buildOllamaVerifierPromptFromPlan}).
 *
 * <p>The verifier model is asked to output a single JSON object on one line, e.g.
 * <pre>{"verdict":"pass","failed_items":[],"notes":""}</pre>
 * Small models sometimes wrap that in markdown fences or add a stray sentence — this
 * parser tolerates that by scanning for the first <code>{</code> and last <code>}</code>
 * in the response. If parsing fails, {@link #parsed()} returns {@code false} and the
 * caller falls back to a "couldn't parse" log line.
 */
public record VerifierVerdict(
        boolean parsed,
        String verdict,
        List<Integer> failedItems,
        String notes
) {
    public VerifierVerdict {
        verdict = verdict == null ? "" : verdict.strip().toLowerCase();
        failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
        notes = notes == null ? "" : notes.strip();
    }

    public static VerifierVerdict unparsed() {
        return new VerifierVerdict(false, "", List.of(), "");
    }

    /** Convenience: a parsed-and-pass verdict (used in tests). */
    public static VerifierVerdict pass() {
        return new VerifierVerdict(true, "pass", List.of(), "");
    }

    /**
     * Parses the raw model response into a verdict, tolerating common small-model output
     * quirks (markdown fences, leading/trailing prose). Returns {@link #unparsed()} when
     * the response does not contain a usable JSON object.
     */
    public static VerifierVerdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return unparsed();
        }
        String trimmed = raw.strip();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            return unparsed();
        }
        String json = trimmed.substring(start, end + 1);
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            String verdict = root.path("verdict").asText("").strip().toLowerCase();
            if (verdict.isBlank()) {
                return unparsed();
            }
            List<Integer> failed = new ArrayList<>();
            JsonNode arr = root.path("failed_items");
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n.isInt()) {
                        failed.add(n.asInt());
                    } else if (n.isTextual()) {
                        try {
                            failed.add(Integer.parseInt(n.asText().strip()));
                        } catch (NumberFormatException ignored) {
                            // skip junk
                        }
                    }
                }
            }
            String notes = root.path("notes").asText("");
            return new VerifierVerdict(true, verdict, failed, notes);
        } catch (Exception e) {
            return unparsed();
        }
    }

    /** Convenience predicate: true if parsed and verdict is exactly "fail". */
    public boolean failed() {
        return parsed && "fail".equals(verdict);
    }
}
