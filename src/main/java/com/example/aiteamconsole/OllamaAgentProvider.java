package com.example.aiteamconsole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Local pipeline: clone with GitHub OAuth token → Ollama chat → {@code git apply} → commit/push.
 * Requires {@code git} on PATH, signed-in GitHub session in this app, running Ollama, and optional Qdrant.
 */
public final class OllamaAgentProvider implements AgentProvider {

    private static final Logger LOG = AppLogging.get(OllamaAgentProvider.class);

    private final OllamaHttpClient ollamaHttp;
    private final QdrantRestClient qdrant;
    private final GitHubJsonStore githubStore;
    private final Path workspaceRoot;
    private volatile RepositoryResolver repositoryResolver = RepositoryResolver.DEFAULT;
    private volatile OllamaRuntimeSettings runtimeSettings = OllamaRuntimeSettings.defaults();
    private volatile ProjectMemoryStore projectMemoryStore = ProjectMemoryStore.defaultStore();

    private final ConcurrentHashMap<UUID, OllamaJob> jobs = new ConcurrentHashMap<>();

    public OllamaAgentProvider(GitHubJsonStore githubStore, Path workspaceRoot) {
        this(new OllamaHttpClient(), new QdrantRestClient(), githubStore, workspaceRoot);
    }

    OllamaAgentProvider(OllamaHttpClient ollamaHttp, QdrantRestClient qdrant, GitHubJsonStore githubStore, Path workspaceRoot) {
        this.ollamaHttp = ollamaHttp;
        this.qdrant = qdrant;
        this.githubStore = githubStore;
        this.workspaceRoot = workspaceRoot;
    }

    public void setRepositoryResolver(RepositoryResolver resolver) {
        this.repositoryResolver = resolver == null ? RepositoryResolver.DEFAULT : resolver;
    }

    public void setRuntimeSettings(OllamaRuntimeSettings settings) {
        this.runtimeSettings = settings == null ? OllamaRuntimeSettings.defaults() : settings.normalized();
    }

    /**
     * Overrides the project memory store (e.g. for a tenant-specific path or in tests).
     * Defaults to {@link ProjectMemoryStore#defaultStore()}.
     */
    public void setProjectMemoryStore(ProjectMemoryStore store) {
        this.projectMemoryStore = store == null ? ProjectMemoryStore.defaultStore() : store;
    }

    /** Exposes the HTTP client for UI-level operations (model list, pull). */
    public OllamaHttpClient ollamaHttp() {
        return ollamaHttp;
    }

    @Override
    public AgentRun startTask(AgentProfile agent, AgentTask task, AppSettings ignored, TaskLaunchHints hints)
            throws AgentProviderException {
        OllamaRuntimeSettings cfg = runtimeSettings;
        List<String> repos = repositoryResolver.resolve(task, agent);
        List<String> resolved = new ArrayList<>();
        for (String u : repos) {
            String n = GitHubRepoUrls.normalizeHttpsRepositoryUrl(u);
            if (!n.isBlank() && !resolved.contains(n)) {
                resolved.add(n);
            }
        }
        if (resolved.isEmpty()) {
            throw new AgentProviderException("At least one repository URL is required for Ollama runs. Tag a repository.");
        }
        String primary = resolved.getFirst();
        Optional<GitHubRepoUrls.Slug> slugOpt = GitHubRepoUrls.parseSlug(primary);
        if (slugOpt.isEmpty()) {
            throw new AgentProviderException("Expected https://github.com/owner/repo URL: " + primary);
        }
        Optional<GitHubSession> sessionOpt = githubStore.loadSession();
        if (sessionOpt.isEmpty()) {
            throw new AgentProviderException("GitHub sign-in is required for Ollama runs (clone/push). Open GitHub settings.");
        }
        GitHubSession sess = sessionOpt.get();
        String grantedScope = sess.scope() == null ? "" : sess.scope().toLowerCase();
        if (!hasRepoScope(grantedScope)) {
            String live = probeLiveScopes(sess.accessToken());
            if (!live.isBlank() && hasRepoScope(live.toLowerCase())) {
                GitHubSession refreshed = new GitHubSession(sess.accessToken(), sess.login(), live, sess.savedAt());
                try {
                    githubStore.saveSession(refreshed);
                } catch (RuntimeException re) {
                    LOG.log(Level.FINE, "could not persist refreshed session scope", re);
                }
            } else {
                LOG.info("Proceeding with Ollama run despite empty/insufficient X-OAuth-Scopes (token may be a GitHub App / fine-grained); will rely on the actual push result.");
            }
        }
        AgentBranchNames.HeadBranchResolution headBranch = AgentBranchNames.resolveHeadBranch(agent, task);
        final String branchName = headBranch.effectiveForGitAndApi();
        final String baseBranch = AgentBranchNames.baseBranchForPullRequest(task.startingRef(), agent.startingRef());
        if (baseBranch.isBlank()) {
            throw new AgentProviderException("Could not determine base branch for checkout.");
        }

        AgentRun run = AgentRun.started(
                task.id(),
                agent.id(),
                ProviderType.OLLAMA,
                "ollama",
                "local-" + UUID.randomUUID(),
                branchName
        ).appendLog("Ollama base: " + cfg.ollamaBaseUrl() + ", model: " + resolveModel(agent, cfg))
                .appendLog("Head branch (git/push): " + branchName + headBranch.mismatchNoteForLogs());

        OllamaJob job = new OllamaJob();
        jobs.put(run.id(), job);
        job.keepPrefixLines = run.logs().size();
        GitHubSession session = sessionOpt.get();
        job.future = CompletableFuture.runAsync(() -> executeAsync(run, agent, task, slugOpt.get(), session, branchName, baseBranch, cfg, job));
        return run;
    }

    @Override
    public AgentRun refreshRun(AgentRun run, AppSettings ignored) throws AgentProviderException {
        OllamaJob job = jobs.get(run.id());
        if (job == null) {
            return run;
        }
        AgentRun updated = job.materialize(run);
        if (updated.status().terminal()) {
            jobs.remove(run.id(), job);
        }
        return updated;
    }

    @Override
    public AgentRun cancelRun(AgentRun run, AppSettings ignored) throws AgentProviderException {
        OllamaJob job = jobs.remove(run.id());
        if (job != null && job.future != null) {
            job.future.cancel(true);
        }
        Path work = workspaceRoot.resolve(run.id().toString());
        try {
            LocalGitOperations.deleteRecursive(work);
        } catch (IOException e) {
            LOG.log(Level.FINE, "cleanup workspace", e);
        }
        return run.withStatus(RunStatus.CANCELLED, "Cancelled by user.", run.pullRequestUrl())
                .appendLog("Ollama run cancelled.");
    }

    private void executeAsync(
            AgentRun run,
            AgentProfile agent,
            AgentTask task,
            GitHubRepoUrls.Slug slug,
            GitHubSession session,
            String branchName,
            String baseBranch,
            OllamaRuntimeSettings cfg,
            OllamaJob job
    ) {
        Path workDir = workspaceRoot.resolve(run.id().toString());
        String qCollection = "ai_team_run_" + run.id().toString().replace("-", "").substring(0, 12);
        try {
            Files.createDirectories(workspaceRoot);
            job.log("Workspace: " + workDir);

            String token = session.accessToken();
            String login = session.login() == null || session.login().isBlank() ? "oauth2" : session.login().strip();
            String cloneUrl = "https://%s:%s@github.com/%s/%s.git".formatted(login, token, slug.owner(), slug.repo());
            job.log("Cloning repository as " + login + "…");
            LocalGitOperations.cloneAuthenticated(workspaceRoot, cloneUrl, workDir.getFileName().toString());
            Path repoRoot = workDir;
            try {
                LocalGitOperations.runGit(repoRoot, "remote", "set-url", "--push", "origin", cloneUrl);
            } catch (IOException | InterruptedException e) {
                job.log("remote set-url note: " + e.getMessage());
            }

            job.log("Fetching and checking out base: " + baseBranch);
            try {
                LocalGitOperations.runGit(repoRoot, "fetch", "origin");
            } catch (IOException | InterruptedException e) {
                job.log("fetch note: " + e.getMessage());
            }
            try {
                LocalGitOperations.runGit(repoRoot, "checkout", baseBranch);
            } catch (IOException | InterruptedException e) {
                LocalGitOperations.runGit(repoRoot, "checkout", "-B", baseBranch, "origin/" + baseBranch);
            }
            LocalGitOperations.runGit(repoRoot, "checkout", "-b", branchName);

            // ──────────────────────────────────────────────────────────────
            // Planner / executor split detection.
            // If the task description contains a pre-approved plan (PLAN_START…PLAN_END),
            // we use the executor prompt — much smaller, no layout/qdrant bloat — and
            // run a verifier pass after the patch is applied. Otherwise: classic flow.
            // ──────────────────────────────────────────────────────────────
            String planBody = AgentTaskPrompts.extractImplementationPlan(task.description());
            boolean executorMode = !planBody.isBlank();

            String layout;
            String qdrantCtx;
            String userPrompt;
            String system;
            ProjectMemorySnapshot memory = loadMemoryForTask(task, agent);

            if (executorMode) {
                job.log("Pre-approved plan detected in task description (" + planBody.length()
                        + " chars). Switching to executor mode — skipping repository layout and Qdrant retrieval.");
                layout = "";
                qdrantCtx = "";
                userPrompt = AgentTaskPrompts.buildOllamaExecutorPromptFromPlan(task, planBody);
                system = "You are a code-rewrite executor. You output only a valid git unified diff applicable at repo root, or the line NO_PATCH. You never invent files, APIs, or refactors that are not in the plan.";
            } else {
                layout = PatchParseUtil.summarizeRepository(repoRoot, 400, 120);
                qdrantCtx = "";
                if (cfg.qdrantEnabled() && cfg.qdrantBaseUrl() != null && !cfg.qdrantBaseUrl().isBlank()) {
                    job.log("Indexing codebase into Qdrant collection " + qCollection + "…");
                    qdrantCtx = buildQdrantContext(repoRoot, cfg, qCollection, task);
                }
                // Load pre-analyzed project memory to avoid full codebase re-analysis on every run
                userPrompt = AgentTaskPrompts.buildOllamaCoderUserPrompt(agent, task, baseBranch, branchName, layout, qdrantCtx, memory);
                system = "You output only a valid git unified diff applicable at repo root, or the line NO_PATCH.";
            }

            job.log("Calling Ollama…");
            OllamaHttpClient.OllamaChatResult result = ollamaHttp.chatWithMetrics(
                    cfg.ollamaBaseUrl(), resolveModel(agent, cfg), system, userPrompt);
            job.addTokens(result);
            String raw = result.content();
            job.log("Model response length: " + raw.length() + " chars");
            job.log("Executor tokens: prompt=" + result.promptEvalCount() + ", completion=" + result.evalCount()
                    + (executorMode ? " (executor mode)" : " (classic mode)"));

            String diff = PatchParseUtil.extractUnifiedDiff(raw);
            if (diff.isBlank()) {
                job.summary = raw.length() > 8000 ? raw.substring(0, 8000) + "…" : raw;
                job.status = RunStatus.FINISHED;
                job.log("No patch applied (NO_PATCH or unparsable diff).");
                return;
            }
            job.log("Applying patch…");
            try {
                LocalGitOperations.writePatchAndApply(repoRoot, diff);
            } catch (IOException applyErr) {
                int created = applyNewFileFallback(repoRoot, diff, job);
                if (created > 0) {
                    job.log("git apply failed but " + created + " new file(s) were created from diff fallback. Continuing.");
                } else {
                    dumpFailedPatch(run.id(), raw, diff, applyErr.getMessage(), job);
                    throw applyErr;
                }
            }

            // Verifier pass — only meaningful in executor mode (a plan provides the checklist).
            if (executorMode) {
                runVerifierPass(cfg, task, planBody, diff, job);
            }
            String msg = "%s %s".formatted(task.taskKey(), task.title()).strip();
            if (msg.length() > 200) {
                msg = msg.substring(0, 200);
            }
            LocalGitOperations.runGit(repoRoot, "add", "-A");
            LocalGitOperations.runGit(repoRoot, "commit", "-m", msg);
            job.log("Pushing branch " + branchName + "…");
            try {
                LocalGitOperations.runGit(repoRoot, "push", "-u", "origin", branchName);
            } catch (IOException pushErr) {
                String detail = pushErr.getMessage() == null ? "" : pushErr.getMessage();
                if (detail.contains("403") || detail.toLowerCase().contains("permission") || detail.toLowerCase().contains("denied")) {
                    throw new IOException(
                            "git push denied (HTTP 403). Token is signed in as '" + login
                                    + "' with scope '" + session.scope() + "'. Likely causes: (1) the OAuth app was not granted access to "
                                    + slug.owner() + "/" + slug.repo()
                                    + " (open the repo's GitHub Settings → Integrations → Authorized OAuth Apps and grant access, or for orgs ask an admin to approve the OAuth app), "
                                    + "(2) the token was issued without write access — sign out and sign in again approving the 'repo' scope. Original error:\n"
                                    + detail,
                            pushErr
                    );
                }
                throw pushErr;
            }
            job.summary = "Ollama applied a patch, committed, and pushed branch `%s`. Open a PR from GitHub or use PR automation if enabled."
                    .formatted(branchName);
            job.status = RunStatus.FINISHED;
            job.log("Done.");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Ollama job failed", e);
            job.status = RunStatus.ERROR;
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            job.log("ERROR: " + job.error);
            job.summary = job.error;
        } finally {
            if (cfg.qdrantEnabled() && cfg.qdrantBaseUrl() != null && !cfg.qdrantBaseUrl().isBlank()) {
                qdrant.deleteCollection(cfg.qdrantBaseUrl(), qCollection);
            }
            try {
                LocalGitOperations.deleteRecursive(workDir);
            } catch (IOException e) {
                LOG.log(Level.FINE, "cleanup", e);
            }
        }
    }

    /**
     * Runs a second, cheap Ollama pass that verifies the applied diff against the plan's
     * VERIFICATION_CHECKLIST. The verifier emits a single JSON line; we parse it best-effort
     * and append a {@code [VERIFIER]} log line. We never block the run — verifier failures
     * become a visible warning in the run log so the operator can decide what to do.
     *
     * <p>The verifier prompt is small (≈1–2 k tokens) so the cost is negligible compared
     * to the executor pass it follows.
     */
    private void runVerifierPass(
            OllamaRuntimeSettings cfg,
            AgentTask task,
            String planBody,
            String diff,
            OllamaJob job
    ) {
        job.log("Running verifier pass…");
        String verifierPrompt = AgentTaskPrompts.buildOllamaVerifierPromptFromPlan(task, planBody, diff);
        String verifierSystem = "You are a code-review verifier. You output exactly one JSON object on a single line. No prose.";
        try {
            OllamaHttpClient.OllamaChatResult vr = ollamaHttp.chatWithMetrics(
                    cfg.ollamaBaseUrl(), resolveModel(agent, cfg), verifierSystem, verifierPrompt);
            job.addTokens(vr);
            job.log("Verifier tokens: prompt=" + vr.promptEvalCount() + ", completion=" + vr.evalCount());
            VerifierVerdict verdict = VerifierVerdict.parse(vr.content());
            if (verdict.parsed()) {
                if ("pass".equalsIgnoreCase(verdict.verdict())) {
                    job.log("[VERIFIER] PASS — all checklist items satisfied.");
                } else {
                    job.log("[VERIFIER] FAIL — items: " + verdict.failedItems()
                            + (verdict.notes().isBlank() ? "" : " | " + verdict.notes()));
                    job.log("[VERIFIER] The patch was applied and pushed, but the operator should inspect "
                            + "the failed checklist items before merging.");
                }
            } else {
                String preview = vr.content().length() > 240
                        ? vr.content().substring(0, 240) + "…"
                        : vr.content();
                job.log("[VERIFIER] Could not parse JSON verdict from model. Raw: " + preview);
            }
        } catch (Exception e) {
            job.log("[VERIFIER] pass skipped — " + e.getMessage());
        }
    }

    /**
     * Returns the Ollama model to use for this run.
     * Uses the agent's per-profile override ({@link AgentProfile#effectiveOllamaModel()}) if set;
     * otherwise falls back to the global model from {@link OllamaRuntimeSettings#ollamaModel()}.
     */
    private static String resolveModel(AgentProfile agent, OllamaRuntimeSettings cfg) {
        String perAgent = agent.effectiveOllamaModel();
        return perAgent.isBlank() ? cfg.ollamaModel() : perAgent;
    }

    /**
     * Loads the best available project memory snapshot for the given task/agent combo.
     * Returns {@code null} if no snapshot exists yet — prompt works normally in that case.
     */
    private ProjectMemorySnapshot loadMemoryForTask(AgentTask task, AgentProfile agent) {
        List<String> repos = repositoryResolver.resolve(task, agent);
        for (String url : repos) {
            String norm = GitHubRepoUrls.normalizeHttpsRepositoryUrl(url);
            if (!norm.isBlank()) {
                var snapshot = projectMemoryStore.load(norm);
                if (snapshot.isPresent() && snapshot.get().hasContext()) {
                    LOG.info("Injecting project memory for Ollama run, repo=" + norm);
                    return snapshot.get();
                }
            }
        }
        return null;
    }

    private String buildQdrantContext(Path repoRoot, OllamaRuntimeSettings cfg, String collection, AgentTask task)
            throws Exception {
        qdrant.ensureCollection(cfg.qdrantBaseUrl(), collection, cfg.embeddingDimensions());
        List<QdrantRestClient.QdrantPoint> batch = new ArrayList<>();
        int[] counter = {0};
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(this::includeForRag)
                    .limit(cfg.ragMaxFiles())
                    .forEach(p -> {
                        try {
                            for (PatchParseUtil.TextChunk ch : PatchParseUtil.chunkFile(p, repoRoot, cfg.ragChunkChars())) {
                                if (counter[0] >= 400) {
                                    return;
                                }
                                String rel = ch.path().contains("#") ? ch.path().substring(0, ch.path().indexOf('#')) : ch.path();
                                try {
                                    float[] vec = ollamaHttp.embed(cfg.ollamaBaseUrl(), cfg.embeddingModel(), ch.text());
                                    batch.add(new QdrantRestClient.QdrantPoint(
                                            UUID.randomUUID(),
                                            vec,
                                            rel,
                                            ch.text()
                                    ));
                                    counter[0]++;
                                    if (batch.size() >= 32) {
                                        qdrant.upsertPoints(cfg.qdrantBaseUrl(), collection, List.copyOf(batch));
                                        batch.clear();
                                    }
                                } catch (Exception ex) {
                                    LOG.log(Level.FINE, "skip chunk " + rel, ex);
                                }
                            }
                        } catch (IOException e) {
                            LOG.log(Level.FINE, "skip file", e);
                        }
                    });
        }
        if (!batch.isEmpty()) {
            qdrant.upsertPoints(cfg.qdrantBaseUrl(), collection, batch);
        }
        String query = task.title() + "\n" + task.description();
        float[] qv = ollamaHttp.embed(cfg.ollamaBaseUrl(), cfg.embeddingModel(), query);
        List<String> hits = qdrant.search(cfg.qdrantBaseUrl(), collection, qv, 16);
        return String.join("\n---\n", hits);
    }

    private static boolean hasRepoScope(String scopesLower) {
        if (scopesLower == null || scopesLower.isBlank()) {
            return false;
        }
        for (String s : scopesLower.split("[,\\s]+")) {
            String t = s.trim();
            if (t.equals("repo") || t.equals("public_repo")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calls {@code GET https://api.github.com/user} and returns the {@code X-OAuth-Scopes} header, which is the
     * authoritative list of granted scopes for the token. Returns "" on failure.
     */
    private static String probeLiveScopes(String accessToken) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.github.com/user"))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return resp.headers().firstValue("X-OAuth-Scopes").orElse("");
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    private int applyNewFileFallback(Path repoRoot, String diff, OllamaJob job) {
        List<PatchParseUtil.NewFileBlock> blocks = PatchParseUtil.extractAdditionsByPath(diff);
        int ok = 0;
        for (PatchParseUtil.NewFileBlock b : blocks) {
            try {
                Path target = repoRoot.resolve(b.path()).normalize();
                if (!target.startsWith(repoRoot)) {
                    job.log("Skipping unsafe path in patch: " + b.path());
                    continue;
                }
                if (Files.exists(target)) {
                    job.log("Skipping fallback for existing file (would need real merge): " + b.path());
                    continue;
                }
                if (b.content() == null || b.content().isEmpty()) {
                    job.log("Skipping fallback for empty additions: " + b.path());
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(target, b.content());
                job.log("Created file from diff fallback: " + b.path());
                ok++;
            } catch (IOException e) {
                job.log("Could not create " + b.path() + ": " + e.getMessage());
            }
        }
        return ok;
    }

    private void dumpFailedPatch(UUID runId, String rawModelOutput, String preparedDiff, String error, OllamaJob job) {
        try {
            Path dir = AppLogging.logDirectory();
            Files.createDirectories(dir);
            Path patchFile = dir.resolve("ollama-failed-" + runId + ".patch");
            Path rawFile = dir.resolve("ollama-failed-" + runId + ".raw.txt");
            Files.writeString(patchFile, preparedDiff == null ? "" : preparedDiff);
            Files.writeString(rawFile, "ERROR: " + (error == null ? "" : error) + "\n\n---RAW MODEL OUTPUT---\n"
                    + (rawModelOutput == null ? "" : rawModelOutput));
            job.log("Saved failing patch: " + patchFile);
            job.log("Saved raw model output: " + rawFile);
        } catch (IOException e) {
            LOG.log(Level.FINE, "could not dump failed patch", e);
        }
    }

    private boolean includeForRag(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (n.startsWith(".")) {
            return false;
        }
        String s = p.toString().replace('\\', '/').toLowerCase();
        if (s.contains("/.git/") || s.contains("/node_modules/") || s.contains("/target/")) {
            return false;
        }
        return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".md") || n.endsWith(".json")
                || n.endsWith(".xml") || n.endsWith(".gradle") || n.endsWith(".properties")
                || n.endsWith(".ts") || n.endsWith(".tsx") || n.endsWith(".js") || n.endsWith(".py");
    }

    private static final class OllamaJob {
        volatile CompletableFuture<Void> future;
        volatile int keepPrefixLines = 2;
        final CopyOnWriteArrayList<RunLogEntry> extraLogs = new CopyOnWriteArrayList<>();
        volatile RunStatus status = RunStatus.RUNNING;
        volatile String summary = "";
        volatile String error = "";
        volatile String prUrl = "";
        /** Accumulated real token counts from Ollama model responses (all passes: executor + verifier). */
        volatile int totalPromptTokens = 0;
        volatile int totalCompletionTokens = 0;

        void log(String m) {
            extraLogs.add(RunLogEntry.now(m));
        }

        /** Adds token counts from one model call (thread-safe: only called from the job thread). */
        void addTokens(OllamaHttpClient.OllamaChatResult result) {
            totalPromptTokens += result.promptEvalCount();
            totalCompletionTokens += result.evalCount();
        }

        AgentRun materialize(AgentRun base) {
            List<RunLogEntry> merged = new ArrayList<>();
            List<RunLogEntry> baseLogs = base.logs() == null ? List.of() : base.logs();
            int keep = Math.min(Math.max(keepPrefixLines, 1), baseLogs.size());
            merged.addAll(baseLogs.subList(0, keep));
            merged.addAll(extraLogs);
            RunStatus st = status;
            String sum = summary;
            if (st == RunStatus.ERROR && (sum == null || sum.isBlank())) {
                sum = error;
            }
            if (st == RunStatus.RUNNING) {
                sum = base.resultSummary();
            }
            AgentRun updated = base.withLogs(merged).withStatus(st, sum, prUrl == null || prUrl.isBlank() ? null : prUrl.strip());
            if (totalPromptTokens > 0 || totalCompletionTokens > 0) {
                updated = updated.withTokens(totalPromptTokens, totalCompletionTokens);
            }
            return updated;
        }
    }
}
