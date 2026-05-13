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

    @Override
    public AgentRun startTask(AgentProfile agent, AgentTask task, AppSettings ignored) throws AgentProviderException {
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
        ).appendLog("Ollama base: " + cfg.ollamaBaseUrl() + ", model: " + cfg.ollamaModel())
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

            String layout = PatchParseUtil.summarizeRepository(repoRoot, 400, 120);
            String qdrantCtx = "";
            if (cfg.qdrantEnabled() && cfg.qdrantBaseUrl() != null && !cfg.qdrantBaseUrl().isBlank()) {
                job.log("Indexing codebase into Qdrant collection " + qCollection + "…");
                qdrantCtx = buildQdrantContext(repoRoot, cfg, qCollection, task);
            }

            String userPrompt = AgentTaskPrompts.buildOllamaCoderUserPrompt(agent, task, baseBranch, branchName, layout, qdrantCtx);
            String system = "You output only a valid git unified diff applicable at repo root, or the line NO_PATCH.";

            job.log("Calling Ollama…");
            String raw = ollamaHttp.chat(cfg.ollamaBaseUrl(), cfg.ollamaModel(), system, userPrompt);
            job.log("Model response length: " + raw.length() + " chars");

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

        void log(String m) {
            extraLogs.add(RunLogEntry.now(m));
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
            return base.withLogs(merged).withStatus(st, sum, prUrl == null ? "" : prUrl);
        }
    }
}
