package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CursorCloudAgentProvider implements AgentProvider {

    /**
     * Legacy default model ID — kept for reference only.
     * The actual model sent to the API is now taken from {@link AppSettings#cursorCloudModelId()}.
     * When that field is blank the model field is omitted entirely (Cursor uses its account default).
     * @deprecated configure via Settings → Cursor → Model ID instead
     */
    @Deprecated
    public static final String CURSOR_CLOUD_MODEL_ID = "composer-2";

    private static final Logger LOG = AppLogging.get(CursorCloudAgentProvider.class);

    private static final Pattern VERIFY_TOKEN_PATTERN = Pattern.compile(
            Pattern.quote(AgentTaskPrompts.VERIFY_TOKEN_PREFIX) + "[^\\r\\n]+"
    );
    private static final String VERIFY_LOG_PREFIX = "Role profile verified by Cloud Agent: ";

    /**
     * Written to the run log when a PROJECT_MEMORY onboarding run starts.
     * Detected in {@link #refreshRun} so the provider can auto-save the snapshot after completion
     * without the caller needing to know it was a special run.
     */
    public static final String MEMORY_ONBOARDING_LOG_PREFIX = "[MEMORY-ONBOARDING] repo=";

    /** Written to the run log once memory has been successfully saved. Prevents duplicate saves. */
    private static final String MEMORY_SAVED_LOG = "[MEMORY-ONBOARDING] snapshot saved";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private volatile RepositoryResolver repositoryResolver = RepositoryResolver.DEFAULT;
    private volatile ProjectMemoryStore projectMemoryStore = ProjectMemoryStore.defaultStore();

    public CursorCloudAgentProvider() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    CursorCloudAgentProvider(HttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    /**
     * Allows the application to override the legacy single-repo resolver with a tag-aware lookup.
     */
    public void setRepositoryResolver(RepositoryResolver resolver) {
        this.repositoryResolver = resolver == null ? RepositoryResolver.DEFAULT : resolver;
    }

    /**
     * Overrides the project memory store (e.g. for a tenant-specific storage path or in tests).
     * Defaults to {@link ProjectMemoryStore#defaultStore()}.
     */
    public void setProjectMemoryStore(ProjectMemoryStore store) {
        this.projectMemoryStore = store == null ? ProjectMemoryStore.defaultStore() : store;
    }

    @Override
    public AgentRun startTask(AgentProfile agent, AgentTask task, AppSettings rawSettings, TaskLaunchHints hints) throws AgentProviderException {
        TaskLaunchHints h = hints == null ? TaskLaunchHints.none() : hints;
        AppSettings settings = requireApiKey(rawSettings);
        List<String> resolvedRepos = new ArrayList<>();
        for (String url : repositoryResolver.resolve(task, agent)) {
            String norm = GitHubRepoUrls.normalizeHttpsRepositoryUrl(url);
            if (!norm.isBlank() && !resolvedRepos.contains(norm)) {
                resolvedRepos.add(norm);
            }
        }
        if (resolvedRepos.isEmpty()) {
            throw new AgentProviderException("At least one repository URL is required for Cursor Cloud Agents. "
                    + "Tag a repository in Settings and pick its tag on the task.");
        }
        String primaryRepoUrl = resolvedRepos.get(0);

        String startingRef = firstNonBlank(task.startingRef(), agent.startingRef());
        AgentBranchNames.HeadBranchResolution headBranch = AgentBranchNames.resolveHeadBranch(agent, task);
        String requestedBranchName = headBranch.requestedRaw();
        String branchNameForApi = headBranch.effectiveForGitAndApi();
        String headOverride = h.cursorHeadBranchOverride();
        if (headOverride != null && !headOverride.isBlank()) {
            branchNameForApi = headOverride.strip();
            requestedBranchName = branchNameForApi;
            LOG.info("startTask using head branch override for Cursor API: %s".formatted(branchNameForApi));
        }
        if (!branchNameForApi.equals(requestedBranchName)) {
            LOG.info("startTask branchName sanitized for Cursor API: '%s' -> '%s'"
                    .formatted(requestedBranchName, branchNameForApi));
        }

        boolean reviewExistingPr = h.pullRequestReviewFocusUrl() != null && !h.pullRequestReviewFocusUrl().isBlank();

        LOG.info("startTask taskId=%s taskKey=%s title=%s agentId=%s repos=%s startingRef=%s branchName=%s"
                .formatted(task.id(), task.taskKey(), task.title(), agent.id(), resolvedRepos,
                        startingRef.isBlank() ? "(default)" : startingRef, branchNameForApi));

        AgentProviderException lastBranchVerifyFailure = null;
        JsonNode json = null;
        boolean startedWithoutBranchName = false;
        String resolvedStartingRef = "";

        for (String refAttempt : startingRefAttempts(startingRef)) {
            List<Map<String, Object>> repos = new ArrayList<>();
            for (String url : resolvedRepos) {
                Map<String, Object> repoConfig = new LinkedHashMap<>();
                repoConfig.put("url", url);
                if (!refAttempt.isBlank()) {
                    repoConfig.put("startingRef", refAttempt);
                }
                repos.add(repoConfig);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", Map.of("text", buildPrompt(agent, task, refAttempt, branchNameForApi, h)));
            body.put("repos", repos);
            if (!branchNameForApi.isBlank()) {
                body.put("branchName", branchNameForApi);
            }
            // Memory onboarding, PA spec runs, and Implementation Planner runs never create a PR —
            // they produce analysis/plan text only. Post-PR review runs target an existing PR;
            // never open another from this agent.
            boolean noCodeRoles = agent.role() == AgentRole.PRODUCT_ANALYST
                    || agent.role() == AgentRole.PROJECT_MEMORY
                    || agent.role() == AgentRole.IMPLEMENTATION_PLANNER
                    || reviewExistingPr;
            body.put("autoCreatePR", noCodeRoles ? false : agent.autoCreatePr());
            body.put("skipReviewerRequest", reviewExistingPr);
            // Only include model field if explicitly configured — omitting lets Cursor use its default.
            if (settings.hasCursorModelId()) {
                body.put("model", Map.of("id", settings.cursorCloudModelId()));
            }

            LOG.info("createAgent attempt startingRefAttempt=%s".formatted(refAttempt.isBlank() ? "(none)" : refAttempt));
            try {
                json = sendJson("POST", settings, "/v1/agents", body);
                resolvedStartingRef = refAttempt;
                break;
            } catch (AgentProviderException e) {
                if (isCursorBranchNameFieldRejected(e)) {
                    body.remove("branchName");
                    try {
                        json = sendJson("POST", settings, "/v1/agents", body);
                        startedWithoutBranchName = true;
                        resolvedStartingRef = refAttempt;
                        break;
                    } catch (AgentProviderException e2) {
                        if (isBranchVerifyFailure(e2)) {
                            lastBranchVerifyFailure = e2;
                            continue;
                        }
                        throw improveCursorError(e2, primaryRepoUrl, displayRef(refAttempt, startingRef));
                    }
                } else if (isBranchVerifyFailure(e)) {
                    lastBranchVerifyFailure = e;
                    continue;
                } else {
                    throw improveCursorError(e, primaryRepoUrl, displayRef(refAttempt, startingRef));
                }
            }
        }

        if (json == null) {
            throw improveCursorError(
                    lastBranchVerifyFailure != null
                            ? lastBranchVerifyFailure
                            : new AgentProviderException("Cursor API did not accept the agent request."),
                    primaryRepoUrl,
                    startingRef
            );
        }
        String externalAgentId = readText(json, "agent", "id");
        if (externalAgentId.isBlank()) {
            externalAgentId = readText(json, "id");
        }
        String externalRunId = readText(json, "run", "id");
        if (externalRunId.isBlank()) {
            externalRunId = readText(json, "latestRunId");
        }
        if (externalAgentId.isBlank() || externalRunId.isBlank()) {
            throw new AgentProviderException("Cursor response did not include agent.id and run.id: " + json);
        }

        LOG.info("startTask success cursorAgentId=%s cursorRunId=%s resolvedStartingRef=%s"
                .formatted(externalAgentId, externalRunId,
                        resolvedStartingRef.isBlank() ? "(default)" : resolvedStartingRef));

        // Estimate input tokens from the prompt we sent (chars / 4 ≈ tokens, ~10% error)
        String sentPrompt = buildPrompt(agent, task, resolvedStartingRef, branchNameForApi, h);
        int estimatedInputTokens = sentPrompt.length() / 4;

        AgentRun run = AgentRun.started(task.id(), agent.id(), ProviderType.CURSOR_CLOUD, externalAgentId, externalRunId, branchNameForApi)
                .withTokens(estimatedInputTokens, 0)   // output tokens updated when run finishes
                .appendLog("Repository URLs sent to Cursor: " + String.join(", ", resolvedRepos))
                .appendLog("Starting ref sent to Cursor: " + (resolvedStartingRef.isBlank() ? "(omitted, use repo default)" : resolvedStartingRef))
                .appendLog("Requested branch name: " + branchNameForApi + headBranch.mismatchNoteForLogs())
                .appendLog("~%d input tokens (estimated from prompt length)".formatted(estimatedInputTokens))
                .appendLog("Cursor Cloud Agent accepted the task.");
        if (startedWithoutBranchName) {
            run = run.appendLog("Cursor API rejected branchName; retried without the field. The required branch name remains in the agent prompt.");
        }
        // Mark memory onboarding runs so refreshRun can auto-save the snapshot on completion.
        if (agent.role() == AgentRole.PROJECT_MEMORY) {
            run = run.appendLog(MEMORY_ONBOARDING_LOG_PREFIX + primaryRepoUrl);
            LOG.info("startTask: PROJECT_MEMORY onboarding run started for repo=" + primaryRepoUrl);
        }
        return run;
    }

    @Override
    public AgentRun refreshRun(AgentRun run, AppSettings rawSettings) throws AgentProviderException {
        AppSettings settings = requireApiKey(rawSettings);
        JsonNode json = sendJson(
                "GET",
                settings,
                "/v1/agents/%s/runs/%s".formatted(encode(run.externalAgentId()), encode(run.externalRunId())),
                null
        );
        RunStatus status = RunStatus.fromCursorStatus(readText(json, "status"));
        String summary = firstNonBlank(readText(json, "result", "text"), readText(json, "result"), run.resultSummary());
        String fromApi = readPullRequestUrlFromRunJson(json);
        String prUrl = fromApi.isBlank() ? null : fromApi;
        String headFromApi = readHeadBranchFromRunJson(json);
        AgentRun runWithHead = run.withExpectedHeadBranch(
                firstNonBlank(headFromApi, run.expectedHeadBranch())
        );
        String activity = summarizeRunActivity(json, status);
        LOG.info("Cursor run activity agentId=%s runId=%s %s".formatted(
                run.externalAgentId(),
                run.externalRunId(),
                activity
        ));
        AgentRun refreshed = runWithHead.withStatus(status, summary, prUrl);
        // Update output token estimate when run reaches a terminal state
        if (status.terminal() && refreshed.estimatedOutputTokens() == 0 && !summary.isBlank()) {
            int outputEst = summary.length() / 4;
            refreshed = refreshed.withTokens(refreshed.estimatedInputTokens(), outputEst);
        }
        if (!lastLogMessage(refreshed).equals(activity)) {
            refreshed = refreshed.appendLog(activity);
        }
        refreshed = maybeAppendVerificationLog(refreshed, json);
        // Auto-save project memory snapshot when a PROJECT_MEMORY onboarding run completes.
        refreshed = maybeSaveProjectMemory(refreshed);
        return refreshed;
    }

    /**
     * If the Cloud Agent echoed the verification token in any summary-like field, record it once in the run log so the
     * operator can confirm the role-specific operating profile reached the model. Detection is idempotent across polls.
     */
    private static AgentRun maybeAppendVerificationLog(AgentRun run, JsonNode json) {
        String haystack = collectSummaryLikeText(json);
        if (haystack.isBlank()) {
            return run;
        }
        Matcher matcher = VERIFY_TOKEN_PATTERN.matcher(haystack);
        if (!matcher.find()) {
            return run;
        }
        String token = matcher.group().strip();
        String logLine = VERIFY_LOG_PREFIX + token;
        for (RunLogEntry entry : run.logs()) {
            if (logLine.equals(entry.message())) {
                return run;
            }
        }
        return run.appendLog(logLine);
    }

    /**
     * When a PROJECT_MEMORY onboarding run transitions to a terminal success status,
     * extracts the structured markdown from the result summary and persists it via
     * {@link ProjectMemoryStore}. Idempotent: skips if already saved (detects log marker).
     */
    private AgentRun maybeSaveProjectMemory(AgentRun run) {
        // Only act on terminal successful runs (FINISHED = success in Cursor API)
        if (run.status() != RunStatus.FINISHED) {
            return run;
        }
        // Check for the onboarding marker in logs
        String repoUrl = null;
        boolean alreadySaved = false;
        for (RunLogEntry entry : run.logs()) {
            if (entry.message().startsWith(MEMORY_ONBOARDING_LOG_PREFIX)) {
                repoUrl = entry.message().substring(MEMORY_ONBOARDING_LOG_PREFIX.length()).strip();
            }
            if (MEMORY_SAVED_LOG.equals(entry.message())) {
                alreadySaved = true;
            }
        }
        if (repoUrl == null || alreadySaved) {
            return run; // not an onboarding run, or already persisted
        }
        ProjectMemorySnapshot snapshot =
                AgentTaskPrompts.extractMultiLevelProjectMemory(repoUrl, run.resultSummary());
        if (!snapshot.hasContext()) {
            LOG.warning("PROJECT_MEMORY run completed but output contained no ## PROJECT_MEMORY_START marker. "
                    + "Memory not saved for repo=" + repoUrl);
            return run.appendLog("[MEMORY-ONBOARDING] WARNING: output did not contain PROJECT_MEMORY markers — snapshot not saved. "
                    + "Re-run the onboarding task.");
        }
        try {
            projectMemoryStore.save(snapshot);
            LOG.info("PROJECT_MEMORY snapshot saved for repo=" + repoUrl
                    + " (brief=" + snapshot.briefContext().length()
                    + " chars, core=" + snapshot.coreContext().length()
                    + " chars, extended=" + snapshot.extendedContext().length() + " chars)");
            return run.appendLog(MEMORY_SAVED_LOG);
        } catch (Exception e) {
            LOG.warning("Failed to save PROJECT_MEMORY snapshot for repo=" + repoUrl + ": " + e.getMessage());
            return run.appendLog("[MEMORY-ONBOARDING] ERROR saving snapshot: " + e.getMessage());
        }
    }

    private static String collectSummaryLikeText(JsonNode json) {
        return String.join("\n",
                readText(json, "summary"),
                readText(json, "result", "text"),
                readText(json, "result"),
                readText(json, "message"),
                readText(json, "lastMessage"),
                readText(json, "last_message")
        );
    }

    /**
     * Lists repository URLs the API key can use with Cloud Agents (via Cursor GitHub App). Rate limits apply.
     */
    public List<String> listAccessibleRepositoryUrls(AppSettings rawSettings) throws AgentProviderException {
        AppSettings settings = requireApiKey(rawSettings.normalized());
        JsonNode root = sendJson("GET", settings, "/v1/repositories", null);
        List<String> urls = new ArrayList<>();
        JsonNode items = root.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                String url = item.path("url").asText("");
                if (!url.isBlank()) {
                    urls.add(url);
                }
            }
        }
        LOG.info("GET /v1/repositories returned %d repository URLs for this API key".formatted(urls.size()));
        return urls;
    }

    public boolean isRepositoryAccessible(AppSettings rawSettings, String repoUrl) throws AgentProviderException {
        String normalized = GitHubRepoUrls.normalizeHttpsRepositoryUrl(repoUrl);
        if (normalized.isBlank()) {
            return false;
        }
        String target = normalized.toLowerCase();
        for (String listed : listAccessibleRepositoryUrls(rawSettings)) {
            if (GitHubRepoUrls.normalizeHttpsRepositoryUrl(listed).toLowerCase().equals(target)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public AgentRun cancelRun(AgentRun run, AppSettings rawSettings) throws AgentProviderException {
        AppSettings settings = requireApiKey(rawSettings);
        sendJson(
                "POST",
                settings,
                "/v1/agents/%s/runs/%s/cancel".formatted(encode(run.externalAgentId()), encode(run.externalRunId())),
                Map.of()
        );
        return run.withStatus(RunStatus.CANCELLED, run.resultSummary(), run.pullRequestUrl())
                .appendLog("Cancellation requested in Cursor.");
    }

    private JsonNode sendJson(String method, AppSettings settings, String path, Object body) throws AgentProviderException {
        if ("POST".equals(method) && "/v1/agents".equals(path) && body instanceof Map<?, ?> map) {
            LOG.info(summarizeCreateAgentRequest(map));
            if (isPromptTextLoggingEnabled()) {
                LOG.info(dumpFullPromptText(map));
            }
        } else {
            LOG.fine("%s %s".formatted(method, path));
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(settings.cursorBaseUrl() + path))
                    .header("Authorization", basicAuth(settings.cursorApiKey()))
                    .header("Accept", "application/json");

            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body == null ? Map.of() : body)));
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                LOG.warning("%s %s HTTP %d body=%s".formatted(method, path, code, AppLogging.truncate(responseBody, 8_000)));
                throw new AgentProviderException("Cursor API returned HTTP %d: %s".formatted(code, responseBody));
            }
            int bodyLen = responseBody == null ? 0 : responseBody.length();
            LOG.info("%s %s HTTP %d responseChars=%d".formatted(method, path, code, bodyLen));
            if (responseBody == null || responseBody.isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(responseBody);
        } catch (IOException e) {
            LOG.warning("Cursor API IO error: %s %s — %s".formatted(method, path, e.getMessage()));
            throw new AgentProviderException("Failed to call Cursor API.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warning("Cursor API interrupted: %s %s".formatted(method, path));
            throw new AgentProviderException("Cursor API call was interrupted.", e);
        }
    }

    /**
     * Opt-in full-prompt logging for debugging role-profile delivery. Off by default to match the privacy stance in README.
     * Enable with env {@code AI_TEAM_LOG_PROMPT_TEXT=1} (also accepts {@code true}/{@code yes}, case-insensitive).
     */
    static boolean isPromptTextLoggingEnabled() {
        String raw = System.getenv("AI_TEAM_LOG_PROMPT_TEXT");
        if (raw == null) {
            return false;
        }
        String v = raw.strip().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    private static String dumpFullPromptText(Map<?, ?> body) {
        Object prompt = body.get("prompt");
        String text = "";
        if (prompt instanceof Map<?, ?> pm && pm.get("text") instanceof String s) {
            text = s;
        }
        if (text.isBlank()) {
            return "POST /v1/agents full prompt: (empty)";
        }
        String safe = AppLogging.truncate(text, 50_000);
        return "POST /v1/agents full prompt (AI_TEAM_LOG_PROMPT_TEXT=on, length=%d chars):%n%s"
                .formatted(text.length(), safe);
    }

    private static String summarizeCreateAgentRequest(Map<?, ?> body) {
        try {
            Object reposObj = body.get("repos");
            String repoUrl = "";
            String startingRef = "(none)";
            if (reposObj instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> r) {
                repoUrl = String.valueOf(r.get("url"));
                Object sr = r.get("startingRef");
                if (sr != null) {
                    startingRef = String.valueOf(sr);
                }
            }
            Object prompt = body.get("prompt");
            int promptChars = 0;
            if (prompt instanceof Map<?, ?> pm) {
                Object t = pm.get("text");
                if (t instanceof String s) {
                    promptChars = s.length();
                }
            }
            return "POST /v1/agents repo=%s startingRef=%s branchName=%s autoCreatePR=%s model=%s promptChars=%d (prompt text not logged)"
                    .formatted(
                            repoUrl,
                            startingRef,
                            body.get("branchName"),
                            body.get("autoCreatePR"),
                            body.get("model"),
                            promptChars
                    );
        } catch (RuntimeException e) {
            return "POST /v1/agents (could not summarize: %s)".formatted(e.getMessage());
        }
    }

    private static String summarizeRunActivity(JsonNode json, RunStatus mappedStatus) {
        List<String> parts = new ArrayList<>();
        String rawStatus = firstNonBlank(
                readText(json, "status"),
                readText(json, "state"),
                readText(json, "phase")
        );
        parts.add("Cursor run status: " + mappedStatus + (rawStatus.isBlank() ? "" : " (raw: " + rawStatus + ")"));

        addPart(parts, "current", firstNonBlank(
                readText(json, "currentStep"),
                readText(json, "current_step"),
                readText(json, "stage"),
                readText(json, "step")
        ));
        addPart(parts, "message", firstNonBlank(
                readText(json, "message"),
                readText(json, "lastMessage"),
                readText(json, "last_message"),
                readText(json, "error", "message"),
                readText(json, "error")
        ));
        addPart(parts, "summary", firstNonBlank(
                readText(json, "summary"),
                readText(json, "result", "text"),
                readText(json, "result")
        ));
        addPart(parts, "pr", firstNonBlank(readPullRequestUrlFromRunJson(json)));

        List<String> activityLines = new ArrayList<>();
        collectActivityLines(json, "", activityLines, 10);
        if (!activityLines.isEmpty()) {
            parts.add("activity: " + String.join(" | ", activityLines));
        }

        return AppLogging.truncate(String.join("; ", parts), 1_500);
    }

    private static void addPart(List<String> parts, String label, String value) {
        String v = value == null ? "" : value.strip();
        if (!v.isBlank()) {
            parts.add(label + ": " + AppLogging.truncate(v.replaceAll("\\s+", " "), 300));
        }
    }

    private static void collectActivityLines(JsonNode node, String path, List<String> lines, int limit) {
        if (node == null || node.isNull() || lines.size() >= limit) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (lines.size() >= limit) {
                    return;
                }
                String key = entry.getKey();
                if (isSensitiveOrNoisyKey(key)) {
                    return;
                }
                String nextPath = path.isBlank() ? key : path + "." + key;
                JsonNode value = entry.getValue();
                if (value.isValueNode() && isActivityKey(key)) {
                    String text = value.asText("");
                    if (!text.isBlank()) {
                        addUnique(lines, nextPath + "=" + AppLogging.truncate(text.replaceAll("\\s+", " "), 220));
                    }
                    return;
                }
                collectActivityLines(value, nextPath, lines, limit);
            });
            return;
        }
        if (node.isArray()) {
            int i = 0;
            for (JsonNode item : node) {
                if (lines.size() >= limit || i >= 5) {
                    return;
                }
                collectActivityLines(item, path + "[" + i + "]", lines, limit);
                i++;
            }
        }
    }

    private static boolean isActivityKey(String key) {
        String k = key.toLowerCase();
        return k.equals("status")
                || k.equals("state")
                || k.equals("phase")
                || k.equals("title")
                || k.equals("name")
                || k.equals("summary")
                || k.equals("message")
                || k.equals("text")
                || k.equals("error")
                || k.equals("reason");
    }

    private static boolean isSensitiveOrNoisyKey(String key) {
        String k = key.toLowerCase();
        return k.contains("token")
                || k.contains("secret")
                || k.contains("authorization")
                || k.contains("apikey")
                || k.equals("key")
                || k.equals("prompt");
    }

    private static void addUnique(List<String> lines, String line) {
        if (!lines.contains(line)) {
            lines.add(line);
        }
    }

    private static String lastLogMessage(AgentRun run) {
        List<RunLogEntry> logs = run.logs();
        if (logs == null || logs.isEmpty()) {
            return "";
        }
        return logs.getLast().message();
    }

    private AppSettings requireApiKey(AppSettings rawSettings) throws AgentProviderException {
        AppSettings settings = rawSettings.normalized();
        if (settings.cursorApiKey().isBlank()) {
            throw new AgentProviderException("Cursor API key is required. Paste it in Settings or set CURSOR_API_KEY.");
        }
        return settings;
    }

    /**
     * Builds the prompt for a task run:
     * <ul>
     *   <li>For {@link AgentRole#PROJECT_MEMORY}: uses the one-time onboarding prompt — no role profile,
     *       no branch, just a structured analysis request. The agent is expected to return
     *       a markdown block wrapped in {@code ## PROJECT_MEMORY_START / ## PROJECT_MEMORY_END}.</li>
     *   <li>For all other roles: loads the pre-analyzed project memory from {@link ProjectMemoryStore}
     *       (if available) and injects it into the standard task prompt so agents skip full codebase
     *       re-analysis on each run.</li>
     * </ul>
     */
    private String buildPrompt(AgentProfile agent, AgentTask task, String refSentToCursor, String requiredBranchName, TaskLaunchHints hints) {
        if (agent.role() == AgentRole.PROJECT_MEMORY) {
            // Determine the primary repo URL for the onboarding prompt
            List<String> repos = repositoryResolver.resolve(task, agent);
            String primaryRepo = repos.isEmpty() ? task.repositoryUrl() : repos.get(0);
            return AgentTaskPrompts.buildProjectMemoryOnboardingPrompt(
                    GitHubRepoUrls.normalizeHttpsRepositoryUrl(primaryRepo)
            );
        }
        // For normal runs: inject pre-analyzed project memory to avoid full codebase re-analysis
        ProjectMemorySnapshot memory = resolveMemoryForTask(task, agent);
        String prFocus = hints == null ? null : hints.pullRequestReviewFocusUrl();
        return AgentTaskPrompts.buildCursorStyleTaskPrompt(agent, task, refSentToCursor, requiredBranchName, memory, prFocus);
    }

    /**
     * Loads the best available project memory snapshot for the given task.
     * Tries the task's own repo URL first, then falls back to the agent's default repo.
     * Returns {@code null} if no snapshot is found (prompt will work without it).
     */
    private ProjectMemorySnapshot resolveMemoryForTask(AgentTask task, AgentProfile agent) {
        List<String> repos = repositoryResolver.resolve(task, agent);
        for (String url : repos) {
            String norm = GitHubRepoUrls.normalizeHttpsRepositoryUrl(url);
            if (!norm.isBlank()) {
                var snapshot = projectMemoryStore.load(norm);
                if (snapshot.isPresent() && snapshot.get().hasContext()) {
                    LOG.info("Injecting project memory for repo=" + norm
                            + " (updated=" + snapshot.get().lastUpdatedAt() + ")");
                    return snapshot.get();
                }
            }
        }
        return null; // no memory yet — prompt works normally, agents will analyze from scratch
    }

    private static List<String> startingRefAttempts(String startingRef) {
        if (startingRef == null || startingRef.isBlank()) {
            return List.of("");
        }
        String ref = startingRef.strip();
        if (ref.startsWith("refs/")) {
            return List.of(ref);
        }
        if (ref.matches("[0-9a-fA-F]{7,40}")) {
            return List.of(ref);
        }
        if (!ref.contains("/")) {
            List<String> attempts = new ArrayList<>(2);
            attempts.add(ref);
            attempts.add("refs/heads/" + ref);
            return List.copyOf(attempts);
        }
        return List.of(ref);
    }

    private static boolean isBranchVerifyFailure(AgentProviderException e) {
        return e.getMessage().contains("Failed to verify existence of branch");
    }

    /**
     * Detects validation / unknown-field errors for {@code branchName} on {@code POST /v1/agents} so we can retry
     * without the field (branch still appears in the prompt).
     */
    private static boolean isCursorBranchNameFieldRejected(AgentProviderException e) {
        String m = e.getMessage();
        if (m == null) {
            return false;
        }
        String ml = m.toLowerCase();
        return ml.contains("branchname")
                || ml.contains("branch_name")
                || ml.contains("\"branchname\"");
    }

    private static String displayRef(String refAttempt, String logicalStartingRef) {
        if (refAttempt != null && !refAttempt.isBlank()) {
            return refAttempt;
        }
        if (logicalStartingRef != null && !logicalStartingRef.isBlank()) {
            return logicalStartingRef.strip();
        }
        return "(repository default branch)";
    }

    private static String basicAuth(String apiKey) {
        return "Basic " + Base64.getEncoder().encodeToString((apiKey + ":").getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final Pattern GITHUB_PR_URL_PATTERN = Pattern.compile(
            "https://github\\.com/[^\\s\"'<>]+?/pull/\\d+[^\\s\"'<>]*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Cursor run JSON shape varies by API version; collect likely locations for a PR link.
     */
    private static String readPullRequestUrlFromRunJson(JsonNode json) {
        return firstNonBlank(
                readText(json, "pullRequestUrl"),
                readText(json, "prUrl"),
                readText(json, "run", "pullRequestUrl"),
                readText(json, "data", "pullRequestUrl"),
                readText(json, "target", "pullRequestUrl"),
                readText(json, "pullRequest", "html_url"),
                readText(json, "pullRequest", "url"),
                readText(json, "pull_request", "html_url"),
                readText(json, "pull_request", "url"),
                readText(json, "result", "pullRequestUrl"),
                readText(json, "result", "prUrl"),
                readText(json, "output", "pullRequestUrl"),
                readText(json, "githubPullRequestUrl"),
                readText(json, "pr", "html_url"),
                scanJsonTreeForGithubPullUrl(json)
        );
    }

    /**
     * Last resort: any JSON string containing a GitHub {@code /pull/<n>} URL
     * (some API versions embed the link only in summary/activity text).
     */
    private static String scanJsonTreeForGithubPullUrl(JsonNode node) {
        return scanJsonTreeForGithubPullUrl(node, 0, 16);
    }

    private static String scanJsonTreeForGithubPullUrl(JsonNode node, int depth, int maxDepth) {
        if (node == null || node.isNull() || depth > maxDepth) {
            return "";
        }
        if (node.isTextual()) {
            Matcher m = GITHUB_PR_URL_PATTERN.matcher(node.asText());
            return m.find() ? m.group().strip() : "";
        }
        if (node.isArray()) {
            for (JsonNode el : node) {
                String u = scanJsonTreeForGithubPullUrl(el, depth + 1, maxDepth);
                if (!u.isBlank()) {
                    return u;
                }
            }
            return "";
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                String u = scanJsonTreeForGithubPullUrl(fields.next().getValue(), depth + 1, maxDepth);
                if (!u.isBlank()) {
                    return u;
                }
            }
        }
        return "";
    }

    /**
     * Best-effort: Cursor run JSON shape may vary; any non-blank value improves GitHub fallback PR {@code head}.
     */
    private static String readHeadBranchFromRunJson(JsonNode json) {
        return firstNonBlank(
                readText(json, "branchName"),
                readText(json, "branch"),
                readText(json, "headBranch"),
                readText(json, "head"),
                readText(json, "git", "branch"),
                readText(json, "source", "branch")
        );
    }

    private static String readText(JsonNode root, String... path) {
        JsonNode current = root;
        for (String part : path) {
            if (current == null || current.isMissingNode()) {
                return "";
            }
            current = current.path(part);
        }
        return current == null || current.isMissingNode() || current.isNull() ? "" : current.asText("");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static AgentProviderException improveCursorError(AgentProviderException original, String repositoryUrl, String startingRef) {
        if (original.getMessage().contains("Failed to verify existence of branch")) {
            String configured = startingRef == null || startingRef.isBlank() ? "(default branch)" : startingRef.strip();
            return new AgentProviderException("""
                    Cursor could not verify that the starting ref exists in this repository.

                    Starting ref in your task/agent settings: %s
                    (short names like main are also sent as refs/heads/main automatically.)
                    Repository: %s

                    This usually means the Cursor GitHub App cannot read this repo with your API key's account — not that the branch is missing on github.com.

                    What to do:
                    1) Cursor Dashboard: connect GitHub with the same user who owns this API key.
                    2) GitHub → Settings → Applications → Cursor → Repository access: include this repo (or the whole org).
                    3) In this app, use "Verify repo access" (calls GET /v1/repositories; limit ~1/min). If the repo is not listed, Cloud Agents cannot clone it.

                    Full API response:
                    %s
                    """.formatted(configured, repositoryUrl, original.getMessage()), original);
        }
        return original;
    }
}
