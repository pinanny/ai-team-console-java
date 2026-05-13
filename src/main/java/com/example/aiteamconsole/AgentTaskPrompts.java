package com.example.aiteamconsole;

/**
 * Shared natural-language instructions for cloud and local agent runs.
 */
public final class AgentTaskPrompts {

    public static final String VERIFY_TOKEN_PREFIX = "ROLE-VERIFY:";

    private AgentTaskPrompts() {
    }

    public static String verificationLine(AgentProfile agent, AgentTask task) {
        return "%s %s | TASK: %s".formatted(
                VERIFY_TOKEN_PREFIX,
                agent.role().label(),
                task.taskKey()
        );
    }

    /**
     * Same operating brief as Cursor Cloud Agents (role profile, task, branch, PR rules).
     */
    public static String buildCursorStyleTaskPrompt(
            AgentProfile agent,
            AgentTask task,
            String refSentToCursor,
            String requiredBranchName
    ) {
        String baseRef = refSentToCursor == null || refSentToCursor.isBlank()
                ? firstNonBlank(task.startingRef(), agent.startingRef(), "repository default branch")
                : refSentToCursor.strip();
        String roleProfile = RolePromptCatalog.defaultCatalog().promptFor(agent.role());
        String verify = verificationLine(agent, task);
        String requiredPrTitle = task.displayTitle().strip();
        return """
                You are acting as %s.

                Role-specific operating profile:
                %s

                Task title:
                %s

                Task key:
                %s

                Base branch/ref for the task branch:
                %s

                Required branch name:
                %s

                Task description:
                %s

                Create the task branch from the base branch/ref, keep it rebased on that base branch/ref, make the smallest safe change, run relevant tests, and prepare a pull request if enabled.
                If the runtime gives you control over the git branch, use the required branch name exactly.

                Pull request title (mandatory when opening a PR):
                Use exactly this title (same spelling and spacing): %s

                Pull request description (mandatory when opening a PR):
                Write a detailed explanation of the work you performed: concrete changes (areas/files), commands and tests you ran with outcomes, assumptions, risks, and anything reviewers should verify.
                Do not use the task description alone as the PR body; it is background only.

                Verification protocol (mandatory):
                Include this exact line, verbatim, on its own line in your final task summary and at the end of the pull request description (after your detailed explanation) so the operator can confirm your role-specific operating profile was active:
                %s
                """.formatted(
                agent.role().label(),
                roleProfile,
                task.title(),
                task.taskKey(),
                baseRef,
                requiredBranchName,
                task.description(),
                requiredPrTitle,
                verify
        );
    }

    /**
     * Local Ollama coder: must emit a single git unified diff (or {@code NO_PATCH}).
     */
    public static String buildOllamaCoderUserPrompt(
            AgentProfile agent,
            AgentTask task,
            String baseRef,
            String requiredBranchName,
            String repositoryLayoutSummary,
            String qdrantContext
    ) {
        String core = buildCursorStyleTaskPrompt(agent, task, baseRef, requiredBranchName);
        StringBuilder sb = new StringBuilder();
        sb.append(core).append("\n\n---\n");
        sb.append("""
                LOCAL EXECUTION MODE (Ollama):
                You cannot run shell commands yourself. The operator will apply your patch with `git apply` from the repository root.
                Output EXACTLY ONE of:
                (A) A single unified diff in git format, OR
                (B) The single line: NO_PATCH

                Rules for the diff (read carefully — output is rejected if any rule is broken):
                1. Start the output with the literal bytes "diff --git ". No preface, no markdown fences, no commentary.
                2. End with the last line of the last hunk. No commentary after the diff.
                3. Touch only paths that already appear under "Repository layout" below. Do NOT invent files such as README.md if they are not listed. If the change requires a file that does not exist, treat it as a NEW FILE and use the new-file header form (see below).
                4. To create a NEW file, the block MUST look like:
                       diff --git a/<path> b/<path>
                       new file mode 100644
                       --- /dev/null
                       +++ b/<path>
                       @@ -0,0 +1,N @@
                       +line 1
                       +line 2
                       ...
                   where N is exactly the number of "+" lines that follow.
                5. To modify an EXISTING file, the block MUST look like:
                       diff --git a/<path> b/<path>
                       --- a/<path>
                       +++ b/<path>
                       @@ -<oldStart>,<oldCount> +<newStart>,<newCount> @@
                       (hunk body)
                   where <oldCount> equals the number of lines starting with " " (space) or "-" in the body and <newCount> equals the number of lines starting with " " (space) or "+". Get these counts right; otherwise the patch is rejected.
                6. Every body line MUST start with one of: " " (single space context), "+", "-", or "\\". No tabs in the first column. No blank lines inside a hunk.
                7. Use LF line endings only. No CRLF, no BOM.
                8. Keep the patch minimal: change as few lines as possible to satisfy the task.
                """);

        if (qdrantContext != null && !qdrantContext.isBlank()) {
            sb.append("\n\nRelevant code excerpts (retrieval):\n").append(qdrantContext.strip());
        }
        if (repositoryLayoutSummary != null && !repositoryLayoutSummary.isBlank()) {
            sb.append("\n\nRepository layout (truncated):\n").append(repositoryLayoutSummary.strip());
        }
        return sb.toString();
    }

    private static String firstNonBlank(String a, String b, String c) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        if (b != null && !b.isBlank()) {
            return b.strip();
        }
        return c == null ? "" : c.strip();
    }
}
