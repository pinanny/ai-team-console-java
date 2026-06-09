package com.example.aiteamconsole;

import java.util.ArrayList;
import java.util.List;

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
     * Picks the smallest sufficient context tier from a {@link ProjectMemorySnapshot}
     * for the given agent role. This is the central token-saving heuristic:
     *
     * <ul>
     *   <li>{@link AgentRole#CODE_REVIEWER} / {@link AgentRole#QA_ENGINEER} —
     *       brief tier (these roles read code, they don't need full architecture).</li>
     *   <li>{@link AgentRole#BACKEND_ENGINEER} / {@link AgentRole#FRONTEND_ENGINEER}
     *       / {@link AgentRole#PRODUCT_ANALYST} / {@link AgentRole#DEVOPS_ENGINEER} —
     *       core tier (the standard working memory).</li>
     *   <li>{@link AgentRole#PROJECT_MEMORY} — extended tier (or empty when
     *       producing a new snapshot; this role isn't normally given context to read).</li>
     * </ul>
     *
     * <p>Each tier transparently falls back to {@code coreContext} when the requested
     * tier is empty (e.g. legacy snapshots without brief/extended fields), so callers
     * never need to handle the missing-tier case themselves.
     *
     * <p>Package-private for unit testing.
     */
    static String pickContextForRole(AgentRole role, ProjectMemorySnapshot memory) {
        if (memory == null || !memory.hasContext()) {
            return "";
        }
        return switch (role) {
            case CODE_REVIEWER, QA_ENGINEER -> memory.briefOrCore();
            case PROJECT_MEMORY -> memory.extendedOrCore();
            // Planner needs the richest context — its output is the contract every executor follows.
            case IMPLEMENTATION_PLANNER -> memory.extendedOrCore();
            case BACKEND_ENGINEER, FRONTEND_ENGINEER, PRODUCT_ANALYST, DEVOPS_ENGINEER -> memory.coreContext();
        };
    }

    /**
     * Same operating brief as Cursor Cloud Agents (role profile, task, branch, PR rules).
     * Delegates to the overload with no project memory (backwards-compatible).
     */
    public static String buildCursorStyleTaskPrompt(
            AgentProfile agent,
            AgentTask task,
            String refSentToCursor,
            String requiredBranchName
    ) {
        return buildCursorStyleTaskPrompt(agent, task, refSentToCursor, requiredBranchName, null, null);
    }

    /**
     * Same as {@link #buildCursorStyleTaskPrompt(AgentProfile, AgentTask, String, String)} but
     * optionally injects pre-analyzed project context from {@link ProjectMemorySnapshot}.
     *
     * <p>When {@code memory} is non-null and contains context, the prompt includes a
     * "Pre-analyzed project context" section with an explicit instruction NOT to re-read
     * the entire codebase — which is the primary source of token waste in multi-agent runs.
     *
     * <p>This method is provider-agnostic: it works the same way for Cursor Cloud Agents,
     * Ollama, and any other LLM provider, because the context is injected into the plain-text
     * prompt before it is handed to {@link AgentProvider}.
     */
    public static String buildCursorStyleTaskPrompt(
            AgentProfile agent,
            AgentTask task,
            String refSentToCursor,
            String requiredBranchName,
            ProjectMemorySnapshot memory
    ) {
        return buildCursorStyleTaskPrompt(agent, task, refSentToCursor, requiredBranchName, memory, null);
    }

    /**
     * @param pullRequestReviewFocusUrl when non-blank and the agent is a {@link AgentRole#CODE_REVIEWER},
     *                                   switches to a review-only brief targeting that PR (inline comments on GitHub).
     */
    public static String buildCursorStyleTaskPrompt(
            AgentProfile agent,
            AgentTask task,
            String refSentToCursor,
            String requiredBranchName,
            ProjectMemorySnapshot memory,
            String pullRequestReviewFocusUrl
    ) {
        String baseRef = refSentToCursor == null || refSentToCursor.isBlank()
                ? firstNonBlank(task.startingRef(), agent.startingRef(), "repository default branch")
                : refSentToCursor.strip();
        String roleProfile = RolePromptCatalog.defaultCatalog().promptFor(agent.role());
        String verify = verificationLine(agent, task);

        String core = agent.role() == AgentRole.PRODUCT_ANALYST
                ? buildProductAnalystCursorCore(agent, task, baseRef, requiredBranchName, roleProfile, verify)
                : buildImplementerCursorCore(agent, task, baseRef, requiredBranchName, roleProfile, verify, pullRequestReviewFocusUrl);

        // Inject pre-analyzed project memory so agents skip full codebase re-analysis.
        // This is the key token-saving mechanism: context generated once at onboarding,
        // reused on every subsequent task for every agent and every LLM provider.
        //
        // We pick the smallest tier sufficient for the agent's role so we don't waste
        // tokens injecting the full long-form analysis into runs that only need a sketch.
        if (memory != null && memory.hasContext()) {
            String contextForRole = pickContextForRole(agent.role(), memory);
            if (!contextForRole.isBlank()) {
                core += """

                        \n---\nPre-analyzed project context (generated during project onboarding — DO NOT re-read the entire codebase or run a full project analysis; use this summary as your starting point instead):
                        %s
                        ---
                        """.formatted(contextForRole.strip());
            }
        }

        if (agent.role() == AgentRole.PRODUCT_ANALYST) {
            return core + "\n\n" + productAnalystRunAddendum();
        }
        return core;
    }

    /**
     * Product Analyst runs are specification-only: the shared implementer template (branch + PR)
     * caused models to push branches and attempt PRs. This core deliberately omits PR/branch
     * delivery instructions; the console also sends {@code autoCreatePR=false} for PA.
     */
    private static String buildProductAnalystCursorCore(
            AgentProfile agent,
            AgentTask task,
            String baseRef,
            String requiredBranchName,
            String roleProfile,
            String verify
    ) {
        return """
                You are acting as %s.

                Role-specific operating profile:
                %s

                Task title:
                %s

                Task key:
                %s

                Base branch/ref (read-only context for where the product lives — not a mandate to ship code from this run):
                %s

                Branch name the console sent to the runtime (context only; do not treat it as a requirement to push commits or open a PR):
                %s

                Task description:
                %s

                Product Analyst — specification-only run (hard rules):
                - Your only required handoff is the structured final summary/result text that the console merges into the ticket (see addendum below). Do not rely on branches, PRs, or repo files as the handoff mechanism.
                - Do NOT create a delivery/task branch, do NOT push commits to origin, do NOT run gh pr create or similar, and do NOT instruct the operator to open a pull request for this Product Analyst outcome. Implementation PRs belong to Backend/Frontend/QA runs after the ticket is OPEN.
                - Do NOT add, edit, or delete tracked files in the repository (including docs under version control) unless the task description explicitly asks you to change the codebase.
                - You MAY read the repository minimally to ground facts; prefer citing paths only when they reduce ambiguity.

                Verification protocol (mandatory):
                Include this exact line, verbatim, on its own line in your final task summary so the operator can confirm your role-specific operating profile was active:
                %s
                """.formatted(
                agent.role().label(),
                roleProfile,
                task.title(),
                task.taskKey(),
                baseRef,
                requiredBranchName,
                task.description(),
                verify
        );
    }

    private static String buildImplementerCursorCore(
            AgentProfile agent,
            AgentTask task,
            String baseRef,
            String requiredBranchName,
            String roleProfile,
            String verify,
            String pullRequestReviewFocusUrl
    ) {
        if (agent.role() == AgentRole.CODE_REVIEWER
                && pullRequestReviewFocusUrl != null
                && !pullRequestReviewFocusUrl.isBlank()) {
            return buildExistingPullRequestReviewCore(
                    agent, task, baseRef, requiredBranchName, roleProfile, verify, pullRequestReviewFocusUrl.strip());
        }
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
     * Review-only run: an implementation agent already opened {@code pullRequestHtmlUrl}.
     * Goal is GitHub-visible feedback (inline review comments or PR conversation), not a second delivery PR.
     */
    private static String buildExistingPullRequestReviewCore(
            AgentProfile agent,
            AgentTask task,
            String baseRef,
            String implementationHeadBranch,
            String roleProfile,
            String verify,
            String pullRequestHtmlUrl
    ) {
        return """
                You are acting as %s.

                Role-specific operating profile:
                %s

                Task title:
                %s

                Task key:
                %s

                Base branch/ref (merge target for the PR):
                %s

                Implementation / PR head branch (check out this branch to see the same diff as the PR):
                %s

                Pull request to review (primary artifact for this run):
                %s

                Task description (context only):
                %s

                Post-PR code review — hard rules:
                - This run is ONLY about reviewing the existing pull request above. Do NOT create a new task branch for delivery, do NOT push implementation commits, and do NOT open a second pull request for the same ticket.
                - Use the GitHub UI and/or GitHub CLI (`gh pr view`, `gh pr diff`, `gh pr checkout`) against that PR URL so your feedback matches the real diff.
                - Publish review feedback on GitHub: prefer **inline review comments** on changed lines; if the environment cannot post inline comments, add **review comments** on the PR conversation or a single **summary review** — do not keep findings only inside this agent chat.
                - Be specific: file path, concern, severity (blocker / suggestion / nit), and a concrete fix or test idea.
                - If you truly cannot access GitHub from this environment, say so explicitly and paste a copy-paste ready review (markdown) for the author to post manually.

                Verification protocol (mandatory):
                Include this exact line, verbatim, on its own line in your final task summary so the operator can confirm your role-specific operating profile was active:
                %s
                """.formatted(
                agent.role().label(),
                roleProfile,
                task.title(),
                task.taskKey(),
                baseRef,
                implementationHeadBranch == null || implementationHeadBranch.isBlank()
                        ? "(use PR default / gh pr checkout from the PR URL)"
                        : implementationHeadBranch.strip(),
                pullRequestHtmlUrl,
                task.description(),
                verify
        );
    }

    private static String productAnalystRunAddendum() {
        return """
                Product Analyst run (upstream of BE / FE / QA):
                You run first on vague intake. Primary deliverable is a sharpened product spec in your final summary, not code or a pull request.
                The console disables auto-create PR for Product Analyst. Do not attempt a PR workflow for this run even if tooling offers it.

                Mandatory structure in the final task summary (use these headings so humans can paste into the task description):
                1. Problem statement — user pain, context, why now.
                2. Acceptance criteria — numbered, testable (Given/When/Then or observable outcomes).
                3. Prioritized scope — Must-have / Should-have / Later (deferrals explicit).
                4. Open questions — assumptions, dependencies, unresolved decisions.

                Handoff note for operators: when this run completes successfully, AI Team Console moves the task to status SPEC_REVIEW (merged summary in the description). Review or edit the ticket, then click Accept spec in the task list to set it OPEN for Backend/Frontend/QA runs. You may adjust the task prefix (e.g. PA-TASK → BE-TASK) before accepting.
                """;
    }

    /**
     * Local Ollama coder: must emit a single git unified diff (or {@code NO_PATCH}).
     * Backwards-compatible overload without project memory.
     */
    public static String buildOllamaCoderUserPrompt(
            AgentProfile agent,
            AgentTask task,
            String baseRef,
            String requiredBranchName,
            String repositoryLayoutSummary,
            String qdrantContext
    ) {
        return buildOllamaCoderUserPrompt(agent, task, baseRef, requiredBranchName, repositoryLayoutSummary, qdrantContext, null);
    }

    /**
     * Local Ollama coder: must emit a single git unified diff (or {@code NO_PATCH}).
     * Accepts an optional {@link ProjectMemorySnapshot} to inject pre-analyzed project context.
     */
    public static String buildOllamaCoderUserPrompt(
            AgentProfile agent,
            AgentTask task,
            String baseRef,
            String requiredBranchName,
            String repositoryLayoutSummary,
            String qdrantContext,
            ProjectMemorySnapshot memory
    ) {
        String core = buildCursorStyleTaskPrompt(agent, task, baseRef, requiredBranchName, memory);
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

    /**
     * Builds the prompt for a one-time "project memory onboarding" run.
     *
     * <p>Dispatch this to a Product Analyst (or a dedicated context agent) once per repository.
     * The agent analyzes the codebase and returns a structured markdown summary.
     * Save that summary via {@link ProjectMemoryStore#save(ProjectMemorySnapshot)} and inject it
     * into all future agent runs via
     * {@link #buildCursorStyleTaskPrompt(AgentProfile, AgentTask, String, String, ProjectMemorySnapshot)}.
     *
     * <p>Expected output format — the agent must return a markdown block with exactly these sections
     * so the console can detect and persist it automatically:
     * <pre>
     * ## PROJECT_MEMORY_START
     * ### Stack
     * ...
     * ### Architecture
     * ...
     * ### Key patterns &amp; conventions
     * ...
     * ### Domain glossary
     * ...
     * ## PROJECT_MEMORY_END
     * </pre>
     *
     * @param repoUrl the repository to analyze
     * @return a full prompt string ready to send to any LLM provider
     */
    public static String buildProjectMemoryOnboardingPrompt(String repoUrl) {
        return """
                You are a senior software architect performing a one-time project analysis.

                Repository: %s

                Your goal is to create a compact, reusable "project memory" that will be injected into
                every future AI agent task for this repository. This memory replaces the need for each
                agent to re-read the entire codebase — it is the single source of truth about the project.

                You will produce THREE separately-sized tiers of the same analysis. The console picks the
                cheapest sufficient tier for each role at runtime (review/QA → brief, implementer → core,
                deep refactor → extended), so each tier must stand on its own — do not assume a reader
                will see the others.

                Wrap your output in these exact markers, on their own lines:

                ## PROJECT_MEMORY_START

                ## BRIEF_START
                ... brief tier markdown (≤ 400 tokens) ...
                ## BRIEF_END

                ## CORE_START
                ... core tier markdown (≤ 1 500 tokens) ...
                ## CORE_END

                ## EXTENDED_START
                ... extended tier markdown (≤ 3 000 tokens) ...
                ## EXTENDED_END

                ## PROJECT_MEMORY_END

                Tier contents:

                ### BRIEF (≤ 400 tokens) — for code-review and QA runs
                One short paragraph with: primary language + build tool, monolith/services shape, top
                3–5 packages or modules and what they do (one phrase each). No glossary, no patterns.

                ### CORE (≤ 1 500 tokens) — the default working memory for implementers
                Use exactly these sub-sections:
                #### Stack
                - Programming language(s) and versions
                - Frameworks, major libraries, build tools
                - Infrastructure / deployment (if evident from config files)
                #### Architecture
                - High-level structure (monolith / modules / services)
                - Key packages or layers and what they do (2–3 lines each)
                - Entry points, main flows
                #### Key patterns & conventions
                - Coding conventions, naming rules
                - How new features are typically added (what files to touch)
                - Testing approach (unit / integration / e2e, test naming)
                - Important constraints or decisions to respect
                #### Domain glossary
                - 5–15 domain-specific terms used in the codebase with brief definitions

                ### EXTENDED (≤ 3 000 tokens) — full long-form, only used on demand
                Everything in CORE plus: per-module deep dive, notable algorithms, data models, build
                pipeline details, known tech debt or open questions. Cite concrete file paths.

                Rules (apply to all tiers):
                - Be concrete and specific — mention actual class names, packages, file paths
                - Do NOT include boilerplate, generic advice, or things obvious from the language/framework
                - Do NOT include secrets, credentials, or personal data
                - Prefer facts over opinions
                - If something is unclear from the code, say "unknown" rather than guessing
                - Each tier must be self-contained: a reader who sees only BRIEF must still understand
                  the project shape; a reader who sees only CORE must still be able to implement a feature.
                """.formatted(repoUrl);
    }

    /**
     * Extracts the project memory content from an agent's raw output.
     * Returns the text between {@code ## PROJECT_MEMORY_START} and {@code ## PROJECT_MEMORY_END},
     * or an empty string if the markers are not found.
     *
     * <p><b>Backwards-compatible.</b> When the onboarding agent follows the legacy single-tier
     * format (no inner {@code BRIEF/CORE/EXTENDED} markers), this method returns the full
     * inner block — which callers historically saved as {@code coreContext}.
     *
     * <p>When the agent follows the new three-tier format, the inner BRIEF/CORE/EXTENDED
     * markers are part of the returned text. To split them into tiers, call
     * {@link #extractMultiLevelProjectMemory(String, String)} instead, which returns a fully
     * populated {@link ProjectMemorySnapshot}.
     */
    public static String extractProjectMemoryFromOutput(String agentOutput) {
        if (agentOutput == null || agentOutput.isBlank()) {
            return "";
        }
        int start = agentOutput.indexOf("## PROJECT_MEMORY_START");
        int end = agentOutput.indexOf("## PROJECT_MEMORY_END");
        if (start < 0 || end < 0 || end <= start) {
            return "";
        }
        int contentStart = agentOutput.indexOf('\n', start);
        if (contentStart < 0 || contentStart >= end) {
            return "";
        }
        return agentOutput.substring(contentStart, end).strip();
    }

    /**
     * Parses a three-tier project-memory block (BRIEF / CORE / EXTENDED) out of an agent's
     * raw output into a fully populated {@link ProjectMemorySnapshot}.
     *
     * <p>Tier resolution:
     * <ul>
     *   <li>If the new three-tier markers ({@code ## BRIEF_START} / {@code ## CORE_START} /
     *       {@code ## EXTENDED_START}) are present, each tier is extracted independently.</li>
     *   <li>If only the legacy {@code ## PROJECT_MEMORY_START / END} envelope is present
     *       (without inner tier markers), the whole inner block is stored as
     *       {@code coreContext} and the other two tiers are left empty — i.e. the snapshot
     *       behaves exactly like a legacy snapshot, and {@link ProjectMemorySnapshot#briefOrCore()}
     *       / {@link ProjectMemorySnapshot#extendedOrCore()} transparently fall back.</li>
     *   <li>If neither is present, an empty snapshot for the repo is returned.</li>
     * </ul>
     *
     * @param repoUrl     repository the snapshot belongs to
     * @param agentOutput raw agent output (may contain prefatory chat, the envelope, and trailing chat)
     * @return a snapshot ready to persist via {@link ProjectMemoryStore#save(ProjectMemorySnapshot)}
     */
    public static ProjectMemorySnapshot extractMultiLevelProjectMemory(String repoUrl, String agentOutput) {
        String envelope = extractProjectMemoryFromOutput(agentOutput);
        if (envelope.isBlank()) {
            return ProjectMemorySnapshot.empty(repoUrl);
        }
        String brief = extractBetween(envelope, "## BRIEF_START", "## BRIEF_END");
        String core = extractBetween(envelope, "## CORE_START", "## CORE_END");
        String extended = extractBetween(envelope, "## EXTENDED_START", "## EXTENDED_END");
        // Legacy single-tier output: no inner markers at all — fall back to old behavior.
        if (brief.isBlank() && core.isBlank() && extended.isBlank()) {
            return ProjectMemorySnapshot.of(repoUrl, envelope);
        }
        return ProjectMemorySnapshot.ofMultiLevel(repoUrl, brief, core, extended);
    }

    /**
     * Returns the substring between {@code startMarker} and {@code endMarker} (exclusive),
     * trimmed. Returns an empty string if either marker is missing or out of order.
     */
    private static String extractBetween(String text, String startMarker, String endMarker) {
        int s = text.indexOf(startMarker);
        if (s < 0) {
            return "";
        }
        int e = text.indexOf(endMarker, s + startMarker.length());
        if (e < 0) {
            return "";
        }
        int contentStart = text.indexOf('\n', s);
        if (contentStart < 0 || contentStart >= e) {
            return "";
        }
        return text.substring(contentStart, e).strip();
    }

    /**
     * Extracts open questions from a Product Analyst task description.
     *
     * <p>Looks for the {@code ### 4. Open questions} heading (case-insensitive, number optional)
     * and collects every bullet line ({@code - …}) until the next {@code ###} heading or end of text.
     *
     * <p>Each returned string is the cleaned bullet text — markdown bold markers ({@code **…**}) are
     * stripped so the result is usable as a task title. Example input bullet:
     * <pre>- **Definition of "latest":** Assumption: latest stable …</pre>
     * Returns: {@code Definition of "latest": Assumption: latest stable …}
     *
     * @param taskDescription the full task description (may include PA output block)
     * @return ordered list of question texts; empty list if section not found or has no bullets
     */
    public static List<String> extractOpenQuestions(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return List.of();
        }
        // Find the Open questions heading — tolerate numbering variants and case
        int sectionStart = findOpenQuestionsHeading(taskDescription);
        if (sectionStart < 0) {
            return List.of();
        }
        // Advance past the heading line
        int lineEnd = taskDescription.indexOf('\n', sectionStart);
        if (lineEnd < 0) {
            return List.of();
        }
        // Collect until next ### heading or end of string
        int sectionEnd = findNextH3(taskDescription, lineEnd + 1);
        String body = sectionEnd < 0
                ? taskDescription.substring(lineEnd + 1)
                : taskDescription.substring(lineEnd + 1, sectionEnd);

        List<String> questions = new ArrayList<>();
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- ")) {
                String bullet = trimmed.substring(2).strip();
                String cleaned = stripMarkdownBold(bullet);
                if (!cleaned.isBlank()) {
                    questions.add(cleaned);
                }
            }
        }
        return List.copyOf(questions);
    }

    private static int findOpenQuestionsHeading(String text) {
        // Match lines like: "### 4. Open questions" or "### Open questions" (case-insensitive)
        String[] lines = text.split("\n");
        int pos = 0;
        for (String line : lines) {
            String t = line.strip().toLowerCase();
            if (t.startsWith("###") && t.contains("open question")) {
                return pos;
            }
            pos += line.length() + 1;
        }
        return -1;
    }

    private static int findNextH3(String text, int fromIndex) {
        int idx = fromIndex;
        while (idx < text.length()) {
            int nl = text.indexOf('\n', idx);
            if (nl < 0) {
                break;
            }
            String line = text.substring(idx, nl).strip();
            if (line.startsWith("###")) {
                return idx;
            }
            idx = nl + 1;
        }
        return -1;
    }

    /** Removes {@code **text**} and {@code *text*} markdown emphasis from a string. */
    private static String stripMarkdownBold(String text) {
        return text.replaceAll("\\*{1,2}([^*]+)\\*{1,2}", "$1").strip();
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

    // ====================================================================================
    // Planner / Executor split — token-optimization pipeline
    // ====================================================================================
    //
    // A run with AgentRole.IMPLEMENTATION_PLANNER produces a detailed plan wrapped in
    // ## PLAN_START / ## PLAN_END markers (see implementation-planner.md). A subsequent
    // local Ollama run consumes the plan as a contract and emits only a unified diff.
    // A second cheap Ollama pass verifies the diff against the plan's
    // VERIFICATION_CHECKLIST section.
    //
    // Token math (typical small feature):
    //   classic    : layout 5k + qdrant 3k + spec 1k = 9k input × 2 retries = 18k input
    //   planner    : plan 2k + executor instructions 0.5k = 2.5k input × 1 try = 2.5k input
    //   verifier   : plan checklist 0.5k + diff 1k = 1.5k input
    //   net saving : ~83% on the executor side, paid once by a richer planning run.
    // ====================================================================================

    /** Markers wrapping a planner output block. */
    public static final String PLAN_START_MARKER = "## PLAN_START";
    public static final String PLAN_END_MARKER = "## PLAN_END";

    /**
     * Builds the prompt for an {@link AgentRole#IMPLEMENTATION_PLANNER} run.
     *
     * <p>Reuses the existing role profile loaded from {@code implementation-planner.md}
     * (which defines the strict plan contract) and injects project memory at the
     * {@code extendedOrCore} tier so the planner has enough architectural grounding to
     * pick correct file paths and anchors.
     *
     * <p>This method intentionally <b>does not</b> include PR/branch instructions: a planner
     * run is text-only (like Product Analyst). The console should treat a planner run as
     * a SPEC_REVIEW-style stage and disable auto-PR for it.
     *
     * @param agent       the agent profile (must have role IMPLEMENTATION_PLANNER)
     * @param task        the task to plan (typically already in OPEN status with an accepted spec)
     * @param memory      pre-analyzed project memory; may be {@code null} or empty
     * @return a self-contained prompt string ready to send to any LLM provider
     */
    public static String buildImplementationPlannerPrompt(
            AgentProfile agent,
            AgentTask task,
            ProjectMemorySnapshot memory
    ) {
        String roleProfile = RolePromptCatalog.defaultCatalog().promptFor(agent.role());
        String verify = verificationLine(agent, task);
        String memorySection = "";
        if (memory != null && memory.hasContext()) {
            String contextForRole = pickContextForRole(agent.role(), memory);
            if (!contextForRole.isBlank()) {
                memorySection = """

                        ---
                        Pre-analyzed project context (use this to pick correct file paths, package names, anchors, and conventions — DO NOT re-read the entire codebase):
                        %s
                        ---
                        """.formatted(contextForRole.strip());
            }
        }
        return """
                You are acting as %s.

                Role-specific operating profile (this is the plan contract — follow it exactly):
                %s

                Task title:
                %s

                Task key:
                %s

                Task description (the accepted spec from Product Analyst — your input):
                %s
                %s
                Verification protocol (mandatory):
                Include this exact line, verbatim, on its own line inside your final summary so the operator can confirm your role-specific operating profile was active:
                %s

                Output rules (hard):
                - Your entire run summary must be exactly one block wrapped in %s … %s.
                - Do not narrate before or after the block. Do not push commits, branches, or PRs.
                - If you cannot produce a safe plan (missing anchors, unsafe contract change, task too big),
                  emit an UNSAFE block per the role profile and stop.
                """.formatted(
                agent.role().label(),
                roleProfile,
                task.title(),
                task.taskKey(),
                task.description(),
                memorySection,
                verify,
                PLAN_START_MARKER,
                PLAN_END_MARKER
        );
    }

    /**
     * Extracts the plan body between {@link #PLAN_START_MARKER} and {@link #PLAN_END_MARKER}
     * from a planner agent's raw output.
     *
     * @return the plan markdown (without the wrapping markers), or an empty string if
     *         the markers are absent / malformed.
     */
    public static String extractImplementationPlan(String agentOutput) {
        if (agentOutput == null || agentOutput.isBlank()) {
            return "";
        }
        int start = agentOutput.indexOf(PLAN_START_MARKER);
        int end = agentOutput.indexOf(PLAN_END_MARKER);
        if (start < 0 || end < 0 || end <= start) {
            return "";
        }
        int contentStart = agentOutput.indexOf('\n', start);
        if (contentStart < 0 || contentStart >= end) {
            return "";
        }
        return agentOutput.substring(contentStart, end).strip();
    }

    /**
     * Extracts just the {@code ## VERIFICATION_CHECKLIST} section from a plan body.
     * Used by {@link #buildOllamaVerifierPromptFromPlan(AgentTask, String, String)}
     * to keep the verifier prompt small.
     *
     * @return checklist text without the heading, or empty if the section is missing.
     */
    public static String extractVerificationChecklist(String planBody) {
        if (planBody == null || planBody.isBlank()) {
            return "";
        }
        String marker = "## VERIFICATION_CHECKLIST";
        int s = planBody.indexOf(marker);
        if (s < 0) {
            return "";
        }
        int contentStart = planBody.indexOf('\n', s);
        if (contentStart < 0) {
            return "";
        }
        // Next H2 (## …) terminates the section.
        int nextSection = -1;
        int scanFrom = contentStart + 1;
        while (scanFrom < planBody.length()) {
            int nl = planBody.indexOf('\n', scanFrom);
            String line = nl < 0 ? planBody.substring(scanFrom) : planBody.substring(scanFrom, nl);
            if (line.startsWith("## ")) {
                nextSection = scanFrom;
                break;
            }
            if (nl < 0) {
                break;
            }
            scanFrom = nl + 1;
        }
        String section = nextSection < 0
                ? planBody.substring(contentStart)
                : planBody.substring(contentStart, nextSection);
        return section.strip();
    }

    /**
     * Builds the Ollama executor prompt that turns a plan into a single unified diff.
     *
     * <p>This prompt is intentionally minimal — the plan itself is the contract. We drop
     * the heavy repository layout and Qdrant retrieval blocks that
     * {@link #buildOllamaCoderUserPrompt} historically required, because the planner has
     * already done that resolution work and embedded it into ANCHORs and SNIPPETs.
     *
     * <p>The companion system prompt for this call should be:
     * <pre>You are a code-rewrite executor. You output only a valid git unified diff applicable at repo root, or the line NO_PATCH. You never invent files or APIs not in the plan.</pre>
     *
     * @param task the task being executed (only key + title used, for traceability)
     * @param planBody the plan body extracted via {@link #extractImplementationPlan}
     * @return a prompt string ready to send to Ollama
     */
    public static String buildOllamaExecutorPromptFromPlan(AgentTask task, String planBody) {
        String safePlan = planBody == null ? "" : planBody.strip();
        return """
                You are executing a pre-approved implementation plan as a unified-diff patch.

                Task: %s — %s

                The plan below is a CONTRACT. Do not deviate.
                - Apply every PER_FILE_CHANGES block. Use the exact SNIPPET — do not paraphrase.
                - For EDIT blocks, locate the change site using the verbatim ANCHOR text.
                - Add IMPORTS_AND_DEPENDENCIES at the top of each EDITed file.
                - Do not touch any file or API in OUT_OF_SCOPE.
                - Do not create files that aren't in FILES_TO_TOUCH.
                - Do not refactor, rename, or "clean up" anything not in the plan.

                Output EXACTLY ONE of:
                (A) A single unified diff in git format starting with the bytes "diff --git ", OR
                (B) The single line: NO_PATCH (use this only if the plan literally requires no code change).

                Diff rules (rejected on any violation):
                - No markdown fences, no preface, no commentary.
                - Touch only paths listed in FILES_TO_TOUCH below.
                - For new files use the new-file header form:
                      diff --git a/<path> b/<path>
                      new file mode 100644
                      --- /dev/null
                      +++ b/<path>
                      @@ -0,0 +1,N @@
                  where N matches the number of "+" body lines exactly.
                - For modified files, hunk header counts must be exact:
                      @@ -<oldStart>,<oldCount> +<newStart>,<newCount> @@
                - Every body line starts with " ", "+", "-", or "\\". No tabs in column 1. No blank lines inside a hunk.
                - LF endings only.

                === PLAN ===
                %s
                === END PLAN ===
                """.formatted(
                task.taskKey(),
                task.title(),
                safePlan
        );
    }

    /**
     * Builds the verifier prompt that runs after Ollama applies a patch.
     *
     * <p>The verifier is a second, cheap Ollama pass: same model, much smaller prompt.
     * It receives the plan's verification checklist plus the actual diff, and outputs a
     * structured pass/fail report. The console parses the report and either accepts the
     * run, retries with an annotated plan, or surfaces the failure to the operator.
     *
     * <p>Companion system prompt:
     * <pre>You are a code-review verifier. You output exactly one JSON object on a single line. No prose.</pre>
     *
     * @param task     the task that was implemented (key + title only)
     * @param planBody the same plan body the executor consumed
     * @param diff     the unified diff Ollama produced and the console applied
     * @return a prompt that asks for a JSON verdict
     */
    public static String buildOllamaVerifierPromptFromPlan(AgentTask task, String planBody, String diff) {
        String checklist = extractVerificationChecklist(planBody);
        String safeDiff = diff == null ? "" : diff.strip();
        // Hard-cap the diff payload — verifier should evaluate a focused diff, not a refactor.
        if (safeDiff.length() > 24_000) {
            safeDiff = safeDiff.substring(0, 24_000) + "\n... (truncated)";
        }
        return """
                You are verifying that an applied patch satisfies a pre-approved plan.

                Task: %s — %s

                Plan's verification checklist (each item is yes/no answerable from the diff or a one-line command):
                %s

                Applied diff:
                %s

                Output EXACTLY one JSON object on a single line, no markdown, no prose:
                {"verdict":"pass"|"fail","failed_items":[<1-based indexes that failed>],"notes":"<one-sentence reason if any item failed, empty string otherwise>"}

                Rules:
                - "pass" only if every checklist item is satisfied.
                - "fail" if any item is unmet OR if the diff touches a file the plan's OUT_OF_SCOPE forbids OR if the diff is empty when the plan required a change.
                - Be conservative: when in doubt, fail and explain.
                """.formatted(
                task.taskKey(),
                task.title(),
                checklist.isBlank() ? "(plan has no VERIFICATION_CHECKLIST section)" : checklist,
                safeDiff.isBlank() ? "(diff was empty / NO_PATCH)" : safeDiff
        );
    }
}
