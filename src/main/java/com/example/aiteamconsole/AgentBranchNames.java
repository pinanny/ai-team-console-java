package com.example.aiteamconsole;

import java.util.Locale;

/**
 * Branch name the app requests for a task (Cursor API {@code branchName}, agent prompt, local git, and PR head).
 */
public final class AgentBranchNames {
    /** Cursor / git are happiest with short, lowercase, slug-like branch names. */
    private static final int CURSOR_BRANCH_MAX_CHARS = 244;

    private AgentBranchNames() {
    }

    /**
     * Canonical branch name for this agent/task: same string for Cursor {@code branchName}, Ollama {@code git} head,
     * {@link AgentRun#expectedHeadBranch}, and GitHub PR head candidates. Uses {@link #forTask} then
     * {@link #sanitizeForCursorApiBranch}; if sanitization yields empty, falls back to stripped {@code forTask} output.
     *
     * @param requestedRaw human-readable branch from {@link #forTask} (may contain spaces / mixed case)
     * @param effectiveForGitAndApi value passed to APIs and {@code git push}
     */
    public record HeadBranchResolution(String requestedRaw, String effectiveForGitAndApi) {
        public String mismatchNoteForLogs() {
            return effectiveForGitAndApi.equals(requestedRaw) ? "" : " (requested slug: " + requestedRaw + ")";
        }
    }

    /**
     * Resolves the head branch name once so all providers and PR automation stay aligned.
     */
    public static HeadBranchResolution resolveHeadBranch(AgentProfile agent, AgentTask task) {
        String requestedRaw = forTask(agent, task);
        String sanitized = sanitizeForCursorApiBranch(requestedRaw);
        String effective = sanitized.isBlank() ? requestedRaw.strip() : sanitized;
        return new HeadBranchResolution(requestedRaw, effective);
    }

    /**
     * Normalizes a branch name for {@code POST /v1/agents} {@code branchName} and matching {@code git push}.
     * Cursor sometimes rejects names with spaces, control characters, odd Unicode, or excessive length.
     */
    public static String sanitizeForCursorApiBranch(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return "";
        }
        s = s.toLowerCase(Locale.ROOT);
        s = s.replaceAll("\\s+", "-");
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '-' || c == '_') {
                out.append(c);
            } else if (c == '.') {
                out.append(c);
            } else {
                out.append('-');
            }
        }
        String folded = out.toString();
        folded = folded.replace("..", ".");
        folded = folded.replaceAll("/+", "/");
        folded = folded.replaceAll("/-+", "/");
        folded = folded.replaceAll("-+/", "/");
        folded = folded.replaceAll("-{2,}", "-");
        while (folded.startsWith("-") || folded.startsWith("/") || folded.startsWith(".")) {
            folded = folded.substring(1);
        }
        while (!folded.isEmpty() && (folded.endsWith("-") || folded.endsWith("/"))) {
            folded = folded.substring(0, folded.length() - 1);
        }
        if (folded.endsWith(".lock")) {
            folded = folded.substring(0, folded.length() - ".lock".length()) + "-work";
        }
        if (folded.length() > CURSOR_BRANCH_MAX_CHARS) {
            String hash = Integer.toHexString(raw.hashCode());
            String marker = "-t" + hash + "-";
            int keep = CURSOR_BRANCH_MAX_CHARS - marker.length();
            if (keep < 24) {
                keep = 24;
            }
            folded = folded.substring(0, Math.min(keep, folded.length())) + marker;
            if (folded.length() > CURSOR_BRANCH_MAX_CHARS) {
                folded = folded.substring(0, CURSOR_BRANCH_MAX_CHARS);
            }
        }
        return folded;
    }

    public static String forTask(AgentProfile agent, AgentTask task) {
        String titleSlug = task.title().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        String keySlug = task.taskKey().isBlank()
                ? task.id().toString().substring(0, 8)
                : task.taskKey().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        String prefix = agent.branchPrefix() == null || agent.branchPrefix().isBlank()
                ? "cursor"
                : agent.branchPrefix().strip();
        if (titleSlug.isBlank()) {
            return "%s/%s".formatted(prefix, keySlug);
        }
        return "%s/%s-%s".formatted(prefix, keySlug, titleSlug);
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.strip();
        }
        return fallback == null ? "" : fallback.strip();
    }

    /**
     * Short branch name for GitHub pull request {@code base} parameter.
     * Empty return means caller should resolve the repository default branch.
     */
    public static String baseBranchForPullRequest(String taskStartingRef, String agentStartingRef) {
        String ref = firstNonBlank(taskStartingRef, agentStartingRef);
        if (ref.isBlank()) {
            return "";
        }
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        if (ref.startsWith("refs/")) {
            return "";
        }
        if (ref.matches("[0-9a-fA-F]{7,40}")) {
            return "";
        }
        return ref;
    }
}
