package com.example.aiteamconsole.ui;

import com.example.aiteamconsole.AgentBranchNames;
import com.example.aiteamconsole.AgentRole;
import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentProviderException;
import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.AppLogging;
import com.example.aiteamconsole.AppSettings;
import com.example.aiteamconsole.AppState;
import com.example.aiteamconsole.GitHubDeviceFlowService;
import com.example.aiteamconsole.GitHubJsonStore;
import com.example.aiteamconsole.GitHubPullsClient;
import com.example.aiteamconsole.GitHubRepoUrls;
import com.example.aiteamconsole.GitHubReposClient;
import com.example.aiteamconsole.GitHubSession;
import com.example.aiteamconsole.OllamaRuntimeSettings;
import com.example.aiteamconsole.ProviderRegistry;
import com.example.aiteamconsole.ProviderType;
import com.example.aiteamconsole.RepositoryEntry;
import com.example.aiteamconsole.RunLogEntry;
import com.example.aiteamconsole.RunStatus;
import com.example.aiteamconsole.StateStore;
import com.example.aiteamconsole.TaskStatus;
import com.example.aiteamconsole.ui.agents.AgentsViewModel;
import com.example.aiteamconsole.ui.runs.RunsViewModel;
import com.example.aiteamconsole.ui.settings.SettingsViewModel;
import com.example.aiteamconsole.ui.tasks.TasksViewModel;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class MainViewModel {

    private record RoleInference(AgentRole role, String rationale) {
    }

    private static final Logger LOG = AppLogging.get(MainViewModel.class);

    private static final long PR_MERGE_WATCH_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(3);

    private final StateStore stateStore;
    private final ProviderRegistry providerRegistry;
    private final GitHubDeviceFlowService gitHubDeviceFlow;

    private final GitHubJsonStore githubStore = GitHubJsonStore.defaultStore();
    private final GitHubPullsClient githubPullsClient = new GitHubPullsClient();
    private final GitHubReposClient githubReposClient = new GitHubReposClient();
    private final OllamaRuntimeSettings.Store ollamaSettingsStore = OllamaRuntimeSettings.Store.defaultStore();
    private final ScheduledExecutorService followUpScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "finished-run-follow-up");
        t.setDaemon(true);
        return t;
    });

    private UiEnvironment ui;
    private long lastPrMergeWatchNanos;

    public final ObservableList<AgentProfile> agentProfiles = FXCollections.observableArrayList();
    public final ObservableList<AgentTask> agentTasks = FXCollections.observableArrayList();
    public final ObservableList<AgentRun> agentRuns = FXCollections.observableArrayList();
    public final ObservableList<RepositoryEntry> repositories = FXCollections.observableArrayList();

    public final AgentsViewModel agents;
    public TasksViewModel tasks;
    public RunsViewModel runs;
    public SettingsViewModel settings;

    private final AtomicBoolean runPollCycleInFlight = new AtomicBoolean(false);
    private final Set<UUID> githubFallbackPrInFlight = ConcurrentHashMap.newKeySet();
    private static final int MAX_FINISHED_PR_FOLLOW_UP_POLLS = 3;
    private final ConcurrentHashMap<UUID, Integer> finishedRunFollowUpPollCount = new ConcurrentHashMap<>();

    private enum PullMergeLabel {
        UNKNOWN,
        MERGED_INTO_MAIN,
        NOT_MERGED_INTO_MAIN
    }

    private final ConcurrentHashMap<String, PullMergeLabel> pullMergeLabelByKey = new ConcurrentHashMap<>();
    private final AtomicBoolean pullMergeLabelRefreshInFlight = new AtomicBoolean(false);
    private final SimpleIntegerProperty pullMergeLabelRevision = new SimpleIntegerProperty(0);

    public MainViewModel(StateStore stateStore, ProviderRegistry providerRegistry, GitHubDeviceFlowService gitHubDeviceFlow) {
        this.stateStore = stateStore;
        this.providerRegistry = providerRegistry;
        this.gitHubDeviceFlow = gitHubDeviceFlow;
        this.agents = new AgentsViewModel(this);
    }

    public void attachUi(UiEnvironment uiEnvironment) {
        this.ui = uiEnvironment;
        this.settings = new SettingsViewModel(
                this,
                com.example.aiteamconsole.CursorApiStore.defaultStore(),
                githubStore,
                uiEnvironment
        );
        this.tasks = new TasksViewModel(this, providerRegistry, () -> settings.toAppSettings());
        this.runs = new RunsViewModel(this, providerRegistry, () -> settings.toAppSettings());
    }

    private UiEnvironment ui() {
        if (ui == null) {
            throw new IllegalStateException("attachUi must be called before use");
        }
        return ui;
    }

    public UserDialogs dialogs() {
        return ui().dialogs();
    }

    public void fxRunner(Runnable r) {
        ui().fxRunner().accept(r);
    }

    public GitHubDeviceFlowService githubDeviceFlowService() {
        return gitHubDeviceFlow;
    }

    public void initialize() {
        AppState state = stateStore.load();
        agentProfiles.setAll(state.agents());
        agentTasks.setAll(state.tasks());
        agentRuns.setAll(state.runs());
        repositories.setAll(state.repositories());

        providerRegistry.cursorCloudProvider().setRepositoryResolver(this::resolveRepositoryUrlsForTask);
        providerRegistry.ollamaProvider().setRepositoryResolver(this::resolveRepositoryUrlsForTask);
        syncOllamaRuntimeSettings();

        settings.reloadCursorFieldsFromStore();
        settings.reloadGithubSessionFromStore();

        CompletableFuture.runAsync(this::checkWaitingReviewTasksForMergedPrs);
        lastPrMergeWatchNanos = System.nanoTime();
        schedulePullMergeLabelRefreshIfIdle();
    }

    public void pollActiveRuns() {
        pollNonTerminalRunsOnce();

        long now = System.nanoTime();
        if (now - lastPrMergeWatchNanos >= PR_MERGE_WATCH_NANOS) {
            lastPrMergeWatchNanos = now;
            CompletableFuture.runAsync(this::checkWaitingReviewTasksForMergedPrs);
        }
        schedulePullMergeLabelRefreshIfIdle();
    }

    public ReadOnlyIntegerProperty pullMergeLabelRevisionProperty() {
        return pullMergeLabelRevision;
    }

    /**
     * {@code true} when the latest successful GitHub read says the PR is merged and its {@code base.ref} is {@code main}.
     */
    public boolean showMergedIntoMainPrefixForPullRequestUrl(String htmlUrl) {
        if (htmlUrl == null || htmlUrl.isBlank()) {
            return false;
        }
        Optional<GitHubRepoUrls.PullRequestRef> ref = GitHubRepoUrls.parsePullRequestHtmlUrl(htmlUrl);
        if (ref.isEmpty()) {
            return false;
        }
        PullMergeLabel label = pullMergeLabelByKey.get(pullMergeCacheKey(ref.get()));
        return label == PullMergeLabel.MERGED_INTO_MAIN;
    }

    public void onGithubSessionClearedForPullMergeLabels() {
        pullMergeLabelByKey.clear();
        fxRunner(() -> pullMergeLabelRevision.set(pullMergeLabelRevision.get() + 1));
    }

    public void schedulePullMergeLabelRefreshIfIdle() {
        if (!pullMergeLabelRefreshInFlight.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                refreshPullMergeLabelsFromGitHub();
            } finally {
                pullMergeLabelRefreshInFlight.set(false);
            }
        });
    }

    private static String pullMergeCacheKey(GitHubRepoUrls.PullRequestRef ref) {
        return ref.owner() + "/" + ref.repo() + "#" + ref.number();
    }

    private void refreshPullMergeLabelsFromGitHub() {
        Optional<GitHubSession> sessionOpt = githubStore.loadSession();
        if (sessionOpt.isEmpty()) {
            pullMergeLabelByKey.clear();
            bumpPullMergeLabelRevision();
            return;
        }
        String token = sessionOpt.get().accessToken();
        LinkedHashMap<String, GitHubRepoUrls.PullRequestRef> unique = new LinkedHashMap<>();
        for (AgentRun run : agentRuns) {
            Optional<GitHubRepoUrls.PullRequestRef> pr = GitHubRepoUrls.parsePullRequestHtmlUrl(run.pullRequestUrl());
            if (pr.isPresent()) {
                GitHubRepoUrls.PullRequestRef r = pr.get();
                unique.putIfAbsent(pullMergeCacheKey(r), r);
            }
        }
        pullMergeLabelByKey.keySet().retainAll(unique.keySet());
        for (var entry : unique.entrySet()) {
            String key = entry.getKey();
            GitHubRepoUrls.PullRequestRef ref = entry.getValue();
            try {
                Optional<GitHubPullsClient.PullRequestMergeState> state =
                        githubPullsClient.fetchPullRequestMergeState(token, ref.owner(), ref.repo(), ref.number());
                PullMergeLabel label;
                if (state.isEmpty()) {
                    label = PullMergeLabel.NOT_MERGED_INTO_MAIN;
                } else {
                    GitHubPullsClient.PullRequestMergeState s = state.get();
                    label = (s.merged() && "main".equals(s.baseRef()))
                            ? PullMergeLabel.MERGED_INTO_MAIN
                            : PullMergeLabel.NOT_MERGED_INTO_MAIN;
                }
                pullMergeLabelByKey.put(key, label);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pullMergeLabelByKey.put(key, PullMergeLabel.UNKNOWN);
                bumpPullMergeLabelRevision();
                return;
            } catch (Exception e) {
                LOG.log(Level.FINE, "PR merge label: GitHub error for " + key, e);
                pullMergeLabelByKey.put(key, PullMergeLabel.UNKNOWN);
            }
        }
        bumpPullMergeLabelRevision();
    }

    private void bumpPullMergeLabelRevision() {
        fxRunner(() -> pullMergeLabelRevision.set(pullMergeLabelRevision.get() + 1));
    }

    public void save() {
        stateStore.save(new AppState(
                List.copyOf(agentProfiles),
                List.copyOf(agentTasks),
                List.copyOf(agentRuns),
                List.copyOf(repositories)
        ));
    }

    public Optional<AgentTask> findTask(UUID id) {
        return agentTasks.stream().filter(task -> task.id().equals(id)).findFirst();
    }

    public Optional<AgentProfile> findAgent(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return agentProfiles.stream().filter(agent -> agent.id().equals(id)).findFirst();
    }

    public Optional<AgentRun> findRun(UUID id) {
        return agentRuns.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    public void replaceTask(AgentTask updated) {
        for (int i = 0; i < agentTasks.size(); i++) {
            if (agentTasks.get(i).id().equals(updated.id())) {
                agentTasks.set(i, updated);
                return;
            }
        }
    }

    public void replaceAgent(AgentProfile updated) {
        for (int i = 0; i < agentProfiles.size(); i++) {
            if (agentProfiles.get(i).id().equals(updated.id())) {
                agentProfiles.set(i, updated);
                return;
            }
        }
    }

    public void replaceRun(AgentRun updated) {
        for (int i = 0; i < agentRuns.size(); i++) {
            if (agentRuns.get(i).id().equals(updated.id())) {
                agentRuns.set(i, updated);
                return;
            }
        }
    }

    public List<String> knownTags() {
        java.util.TreeSet<String> all = new java.util.TreeSet<>();
        for (RepositoryEntry repo : repositories) {
            String tag = repo.tag();
            if (!tag.isBlank()) {
                all.add(tag);
            }
        }
        return new ArrayList<>(all);
    }

    public void importGithubReposIntoTable(String accessToken, boolean showSummary) {
        CompletableFuture.runAsync(() -> {
            try {
                List<String> urls = githubReposClient.listHttpsRepoUrls(accessToken);
                fxRunner(() -> {
                    java.util.Set<String> existing = new java.util.HashSet<>();
                    for (RepositoryEntry repo : repositories) {
                        existing.add(GitHubRepoUrls.normalizeHttpsRepositoryUrl(repo.url()).toLowerCase());
                    }
                    int added = 0;
                    for (String url : urls) {
                        String norm = GitHubRepoUrls.normalizeHttpsRepositoryUrl(url);
                        if (norm.isBlank() || existing.contains(norm.toLowerCase())) {
                            continue;
                        }
                        repositories.add(RepositoryEntry.create(norm, "", "main"));
                        existing.add(norm.toLowerCase());
                        added++;
                    }
                    save();
                    if (showSummary) {
                        dialogs().showInfo("GitHub repositories imported. Added: " + added
                                + ", already present: " + (urls.size() - added) + ".");
                    } else if (added > 0) {
                        LOG.info("Imported " + added + " new repositories from GitHub after sign-in.");
                    }
                });
            } catch (Exception ex) {
                fxRunner(() -> dialogs().showError("Could not import GitHub repositories: " + ex.getMessage()));
            }
        });
    }

    private void checkWaitingReviewTasksForMergedPrs() {
        Optional<GitHubSession> sessionOpt = githubStore.loadSession();
        if (sessionOpt.isEmpty()) {
            return;
        }
        List<AgentTask> waiting = agentTasks.stream().filter(t -> t.status() == TaskStatus.WAITING_REVIEW).toList();
        if (waiting.isEmpty()) {
            return;
        }
        String token = sessionOpt.get().accessToken();
        CompletableFuture.runAsync(() -> {
            for (AgentTask task : waiting) {
                try {
                    Optional<AgentRun> runOpt = latestFinishedRunWithPullRequest(task.id());
                    if (runOpt.isEmpty()) {
                        continue;
                    }
                    AgentRun run = runOpt.get();
                    String prUrl = run.pullRequestUrl();
                    Optional<GitHubRepoUrls.PullRequestRef> refOpt = GitHubRepoUrls.parsePullRequestHtmlUrl(prUrl);
                    if (refOpt.isEmpty()) {
                        LOG.fine("PR merge watch: skip task " + task.id() + " — not a github.com/pull/N URL: " + prUrl);
                        continue;
                    }
                    GitHubRepoUrls.PullRequestRef pr = refOpt.get();
                    if (!githubPullsClient.isPullRequestMerged(token, pr.owner(), pr.repo(), pr.number())) {
                        continue;
                    }
                    fxRunner(() -> markTaskDoneIfStillWaitingReview(task.id(), run.id(), prUrl));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException e) {
                    LOG.log(Level.FINE, "PR merge watch: GitHub error for task " + task.id(), e);
                }
            }
        });
    }

    private Optional<AgentRun> latestFinishedRunWithPullRequest(UUID taskId) {
        return agentRuns.stream()
                .filter(r -> r.taskId().equals(taskId))
                .filter(r -> r.status() == RunStatus.FINISHED)
                .filter(r -> r.pullRequestUrl() != null && !r.pullRequestUrl().isBlank())
                .max(Comparator.comparing(AgentRun::completedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(AgentRun::startedAt, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private void markTaskDoneIfStillWaitingReview(UUID taskId, UUID runId, String prUrl) {
        Optional<AgentTask> taskOpt = findTask(taskId);
        if (taskOpt.isEmpty() || taskOpt.get().status() != TaskStatus.WAITING_REVIEW) {
            return;
        }
        replaceTask(taskOpt.get().withStatus(TaskStatus.DONE));
        for (int i = 0; i < agentRuns.size(); i++) {
            AgentRun r = agentRuns.get(i);
            if (r.id().equals(runId)) {
                AgentRun updated = r.appendLog("PR merge watch: merged on GitHub (" + prUrl + ") — task marked Done.");
                agentRuns.set(i, updated);
                notifyRunsLogRefresh(runId, updated);
                break;
            }
        }
        save();
    }

    private void pollNonTerminalRunsOnce() {
        if (!runPollCycleInFlight.compareAndSet(false, true)) {
            return;
        }
        List<UUID> activeIds = agentRuns.stream()
                .filter(run -> !run.status().terminal())
                .map(AgentRun::id)
                .toList();
        if (activeIds.isEmpty()) {
            runPollCycleInFlight.set(false);
            return;
        }
        pollNonTerminalRunsFromIndex(activeIds, 0);
    }

    private void pollNonTerminalRunsFromIndex(List<UUID> activeIds, int index) {
        if (index >= activeIds.size()) {
            runPollCycleInFlight.set(false);
            return;
        }
        UUID id = activeIds.get(index);
        Optional<AgentRun> current = agentRuns.stream().filter(r -> r.id().equals(id)).findFirst();
        if (current.isEmpty() || current.get().status().terminal()) {
            pollNonTerminalRunsFromIndex(activeIds, index + 1);
            return;
        }
        AgentRun toPoll = current.get();
        CompletableFuture.supplyAsync(() -> {
            try {
                syncOllamaRuntimeSettings();
                return providerRegistry.providerFor(toPoll.provider()).refreshRun(toPoll, settings.toAppSettings());
            } catch (AgentProviderException e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((run, error) -> fxRunner(() -> {
            try {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    LOG.log(Level.FINE, "Auto-refresh run " + id, cause);
                    return;
                }
                applyRefreshSuccess(run);
            } finally {
                pollNonTerminalRunsFromIndex(activeIds, index + 1);
            }
        }));
    }

    private void applyRefreshSuccess(AgentRun run) {
        replaceRun(run);
        updateTaskFromRun(run);
        maybeCreateGithubPullRequestIfNeeded(run);
        AgentRun latest = agentRuns.stream().filter(r -> r.id().equals(run.id())).findFirst().orElse(run);
        notifyRunsLogRefresh(run.id(), latest);
        save();
        scheduleFinishedRunFollowUpPollIfNeeded(latest);
    }

    private void scheduleFinishedRunFollowUpPollIfNeeded(AgentRun run) {
        if (run.provider() != ProviderType.CURSOR_CLOUD) {
            return;
        }
        if (run.pullRequestUrl() != null && !run.pullRequestUrl().isBlank()) {
            finishedRunFollowUpPollCount.remove(run.id());
            return;
        }
        if (run.status() != RunStatus.FINISHED) {
            return;
        }
        int attempt = finishedRunFollowUpPollCount.merge(run.id(), 1, Integer::sum);
        if (attempt > MAX_FINISHED_PR_FOLLOW_UP_POLLS) {
            return;
        }
        UUID runId = run.id();
        followUpScheduler.schedule(() -> fxRunner(() -> {
            Optional<AgentRun> opt = agentRuns.stream().filter(r -> r.id().equals(runId)).findFirst();
            if (opt.isEmpty()) {
                finishedRunFollowUpPollCount.remove(runId);
                return;
            }
            AgentRun current = opt.get();
            if (current.status() != RunStatus.FINISHED) {
                finishedRunFollowUpPollCount.remove(runId);
                return;
            }
            if (current.pullRequestUrl() != null && !current.pullRequestUrl().isBlank()) {
                finishedRunFollowUpPollCount.remove(runId);
                return;
            }
            refreshRun(current);
        }), 7, TimeUnit.SECONDS);
    }

    private void syncOllamaRuntimeSettings() {
        providerRegistry.ollamaProvider().setRuntimeSettings(ollamaSettingsStore.load());
    }

    private void notifyRunsLogRefresh(UUID runId, AgentRun updated) {
        if (runs != null) {
            runs.onSelectedRunUpdated(runId, updated);
        }
    }

    public void refreshRun(AgentRun selected) {
        CompletableFuture.supplyAsync(() -> {
            try {
                syncOllamaRuntimeSettings();
                return providerRegistry.providerFor(selected.provider()).refreshRun(selected, settings.toAppSettings());
            } catch (AgentProviderException e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((run, error) -> fxRunner(() -> {
            if (error != null) {
                dialogs().showError(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                return;
            }
            applyRefreshSuccess(run);
        }));
    }

    public void cancelRun(AgentRun selected) {
        CompletableFuture.supplyAsync(() -> {
            try {
                syncOllamaRuntimeSettings();
                return providerRegistry.providerFor(selected.provider()).cancelRun(selected, settings.toAppSettings());
            } catch (AgentProviderException e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((run, error) -> fxRunner(() -> {
            if (error != null) {
                dialogs().showError(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                return;
            }
            replaceRun(run);
            updateTaskFromRun(run);
            notifyRunsLogRefresh(run.id(), run);
            save();
        }));
    }

    public void startTask(AgentTask selected) {
        AgentTask working = selected;
        if (selected.assignedAgentId() == null) {
            Optional<RoleInference> inference = inferLaunchRoleForUnassignedTask(selected);
            if (inference.isPresent()) {
                RoleInference ri = inference.get();
                boolean ok = dialogs().confirm(
                        "Confirm task type",
                        "This task has no developer assigned.",
                        ri.rationale()
                                + "\n\nSuggested role: " + ri.role().label()
                                + "\n\nAssign the first available agent with this role whose repository tags match the task, "
                                + "then start the Cursor run?");
                if (!ok) {
                    return;
                }
                Optional<AgentProfile> pick = firstFreeAgentForRoleAndTaskTags(ri.role(), working);
                if (pick.isEmpty()) {
                    dialogs().showError("No available " + ri.role().label() + " matches this task's repository tags. "
                            + "Add tags to an agent or adjust the task.");
                    return;
                }
                working = working.withEditableFields(
                        working.taskPrefix(),
                        working.title(),
                        working.description(),
                        working.repositoryUrl(),
                        working.startingRef(),
                        pick.get().id(),
                        working.repositoryTags()
                );
                replaceTask(working);
                save();
            }
        }

        AgentProfile agent = resolveAgentForStart(working).orElse(null);
        if (agent == null) {
            dialogs().showError("No available agent matches this task. Assign a developer, or create a free agent with a matching role and repository tag.");
            return;
        }
        AgentTask taskForRun = working.assignedAgentId() == null
                ? working.withEditableFields(
                working.taskPrefix(),
                working.title(),
                working.description(),
                working.repositoryUrl(),
                working.startingRef(),
                agent.id(),
                working.repositoryTags()
        )
                : working;
        if (taskForRun.startingRef() == null || taskForRun.startingRef().isBlank()) {
            String defaultBranch = defaultBranchForTask(taskForRun, agent);
            if (!defaultBranch.isBlank()) {
                taskForRun = taskForRun.withEditableFields(
                        taskForRun.taskPrefix(),
                        taskForRun.title(),
                        taskForRun.description(),
                        taskForRun.repositoryUrl(),
                        defaultBranch,
                        taskForRun.assignedAgentId(),
                        taskForRun.repositoryTags()
                );
            }
        }
        AgentTask queuedTask = taskForRun.withStatus(TaskStatus.QUEUED);
        replaceTask(queuedTask);
        save();

        launchProviderRun(agent, queuedTask);
    }

    private void launchProviderRun(AgentProfile agent, AgentTask queuedTask) {
        CompletableFuture.supplyAsync(() -> {
            try {
                syncOllamaRuntimeSettings();
                return providerRegistry.providerFor(agent.provider()).startTask(agent, queuedTask, settings.toAppSettings());
            } catch (AgentProviderException e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((run, error) -> fxRunner(() -> {
            if (error != null) {
                replaceTask(queuedTask.withStatus(TaskStatus.FAILED));
                Throwable cause = error.getCause();
                LOG.log(Level.WARNING,
                        "startTask failed taskId=" + queuedTask.id() + " key=" + queuedTask.taskKey(),
                        cause != null ? cause : error);
                dialogs().showError(cause == null ? error.getMessage() : cause.getMessage());
            } else {
                agentRuns.add(run);
                replaceTask(queuedTask.withStatus(TaskStatus.RUNNING));
            }
            save();
        }));
    }

    public void restartFailedRun(AgentRun selected) {
        if (selected == null) {
            dialogs().showError("Select a run first.");
            return;
        }
        if (selected.status() != RunStatus.ERROR && selected.status() != RunStatus.CANCELLED) {
            dialogs().showError("Only runs in ERROR or CANCELLED state can be restarted.");
            return;
        }
        AgentTask task = findTask(selected.taskId()).orElse(null);
        if (task == null) {
            dialogs().showError("Task for this run no longer exists.");
            return;
        }
        AgentProfile agent = findAgent(selected.agentProfileId()).orElse(null);
        if (agent == null) {
            dialogs().showError("Agent profile for this run no longer exists.");
            return;
        }
        if (!isAgentFree(agent.id())) {
            dialogs().showError("That agent already has an active run. Cancel or wait for it to finish before restarting.");
            return;
        }
        AgentTask taskForRun = task.assignedAgentId() != null && task.assignedAgentId().equals(selected.agentProfileId())
                ? task
                : task.withEditableFields(
                        task.taskPrefix(),
                        task.title(),
                        task.description(),
                        task.repositoryUrl(),
                        task.startingRef(),
                        selected.agentProfileId(),
                        task.repositoryTags()
                );
        if (taskForRun != task) {
            replaceTask(taskForRun);
        }
        if (taskForRun.startingRef() == null || taskForRun.startingRef().isBlank()) {
            String defaultBranch = defaultBranchForTask(taskForRun, agent);
            if (!defaultBranch.isBlank()) {
                taskForRun = taskForRun.withEditableFields(
                        taskForRun.taskPrefix(),
                        taskForRun.title(),
                        taskForRun.description(),
                        taskForRun.repositoryUrl(),
                        defaultBranch,
                        taskForRun.assignedAgentId(),
                        taskForRun.repositoryTags()
                );
                replaceTask(taskForRun);
            }
        }
        AgentTask queuedTask = taskForRun.withStatus(TaskStatus.QUEUED);
        replaceTask(queuedTask);
        save();
        launchProviderRun(agent, queuedTask);
    }

    private Optional<AgentProfile> resolveAgentForStart(AgentTask task) {
        Optional<AgentProfile> assigned = findAgent(task.assignedAgentId());
        if (assigned.isPresent()) {
            return assigned;
        }
        AgentRole desiredRole = roleForTaskPrefix(task.taskPrefix()).orElse(null);
        return agentProfiles.stream()
                .filter(agent -> desiredRole == null || agent.role() == desiredRole)
                .filter(agent -> isAgentFree(agent.id()))
                .filter(agent -> matchesRepositoryTags(agent, task.repositoryTags()))
                .findFirst();
    }

    private Optional<RoleInference> inferLaunchRoleForUnassignedTask(AgentTask task) {
        Optional<AgentRole> fromPrefix = roleForTaskPrefix(task.taskPrefix());
        if (fromPrefix.isPresent()) {
            String key = task.taskKey();
            String rationale = key.isBlank()
                    ? "The task prefix " + normalizeTaskPrefix(task.taskPrefix()) + " is associated with this role."
                    : "The task key " + key + " uses prefix " + normalizeTaskPrefix(task.taskPrefix()) + ", which maps to this role.";
            return Optional.of(new RoleInference(fromPrefix.get(), rationale));
        }
        Optional<AgentRole> fromTags = inferRoleFromRepositoryTagOverlap(task);
        if (fromTags.isPresent()) {
            String tags = RepositoryEntry.formatTags(task.repositoryTags());
            return Optional.of(new RoleInference(fromTags.get(),
                    "Repository tags (" + tags + ") overlap one or more agents; the most common role among those agents is below."));
        }
        return Optional.empty();
    }

    private Optional<AgentRole> inferRoleFromRepositoryTagOverlap(AgentTask task) {
        List<String> taskTags = task.repositoryTags();
        if (taskTags == null || taskTags.isEmpty()) {
            return Optional.empty();
        }
        List<AgentProfile> overlap = agentProfiles.stream()
                .filter(a -> a.repositoryTags() != null && !a.repositoryTags().isEmpty())
                .filter(a -> matchesRepositoryTags(a, taskTags))
                .toList();
        if (overlap.isEmpty()) {
            return Optional.empty();
        }
        return overlap.stream()
                .collect(Collectors.groupingBy(AgentProfile::role, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.<AgentRole, Long>comparingByValue().thenComparing(e -> e.getKey().name()))
                .map(Map.Entry::getKey);
    }

    private Optional<AgentProfile> firstFreeAgentForRoleAndTaskTags(AgentRole role, AgentTask task) {
        return agentProfiles.stream()
                .filter(a -> a.role() == role)
                .filter(a -> isAgentFree(a.id()))
                .filter(a -> matchesRepositoryTags(a, task.repositoryTags()))
                .findFirst();
    }

    private boolean isAgentFree(UUID agentId) {
        return agentRuns.stream()
                .filter(run -> run.agentProfileId().equals(agentId))
                .noneMatch(run -> !run.status().terminal());
    }

    private static boolean matchesRepositoryTags(AgentProfile agent, List<String> taskTags) {
        if (taskTags == null || taskTags.isEmpty()) {
            return true;
        }
        if (agent.repositoryTags() == null || agent.repositoryTags().isEmpty()) {
            return false;
        }
        for (String tag : taskTags) {
            if (agent.repositoryTags().contains(tag)) {
                return true;
            }
        }
        return false;
    }

    public static Optional<AgentRole> roleForTaskPrefix(String taskPrefix) {
        String normalized = normalizeTaskPrefix(taskPrefix);
        if (normalized.startsWith("BE")) {
            return Optional.of(AgentRole.BACKEND_ENGINEER);
        }
        if (normalized.startsWith("FE")) {
            return Optional.of(AgentRole.FRONTEND_ENGINEER);
        }
        if (normalized.startsWith("QA")) {
            return Optional.of(AgentRole.QA_ENGINEER);
        }
        if (normalized.startsWith("REV")) {
            return Optional.of(AgentRole.CODE_REVIEWER);
        }
        if (normalized.startsWith("DEVOPS")) {
            return Optional.of(AgentRole.DEVOPS_ENGINEER);
        }
        return Optional.empty();
    }

    private String defaultBranchForTask(AgentTask task, AgentProfile agent) {
        List<String> tags = task.repositoryTags() == null || task.repositoryTags().isEmpty()
                ? agent.repositoryTags()
                : task.repositoryTags();
        if (tags != null) {
            for (String tag : tags) {
                for (RepositoryEntry repo : repositories) {
                    if (repo.tag().equals(tag) && repo.defaultBranch() != null && !repo.defaultBranch().isBlank()) {
                        return repo.defaultBranch();
                    }
                }
            }
        }
        return "main";
    }

    private void updateTaskFromRun(AgentRun run) {
        findTask(run.taskId()).ifPresent(task -> {
            TaskStatus taskStatus = switch (run.status()) {
                case FINISHED -> run.pullRequestUrl() == null || run.pullRequestUrl().isBlank()
                        ? TaskStatus.DONE
                        : TaskStatus.WAITING_REVIEW;
                case CANCELLED -> TaskStatus.CANCELLED;
                case ERROR -> TaskStatus.FAILED;
                case CREATING, RUNNING, UNKNOWN -> TaskStatus.RUNNING;
            };
            replaceTask(task.withStatus(taskStatus));
        });
    }

    private void maybeCreateGithubPullRequestIfNeeded(AgentRun run) {
        if (run.status() != RunStatus.FINISHED) {
            return;
        }
        if (run.pullRequestUrl() != null && !run.pullRequestUrl().isBlank()) {
            return;
        }
        if (run.provider() != ProviderType.CURSOR_CLOUD && run.provider() != ProviderType.OLLAMA) {
            appendPrAutomationLog(run.id(),
                    "Skipped opening a PR here: only Cursor Cloud and Ollama runs use GitHub PR automation in this app.");
            return;
        }
        AgentProfile agent = findAgent(run.agentProfileId()).orElse(null);
        if (agent == null) {
            appendPrAutomationLog(run.id(), "Skipped opening a PR: agent profile was removed.");
            return;
        }
        Optional<GitHubSession> sessionOpt = githubStore.loadSession();
        if (sessionOpt.isEmpty()) {
            LOG.info("Finished run without PR URL; enable GitHub sign-in to open a PR automatically via github.com API.");
            appendPrAutomationLog(run.id(),
                    "Run finished without a pullRequestUrl. Sign in with GitHub in GitHub settings (OAuth scope repo) "
                            + "so this app can open a PR via the GitHub API when the provider does not return a link.");
            return;
        }
        AgentTask task = findTask(run.taskId()).orElse(null);
        if (task == null) {
            appendPrAutomationLog(run.id(), "Skipped opening a PR: task was removed from local state.");
            return;
        }
        List<String> resolvedRepos = resolveRepositoryUrlsForTask(task, agent);
        String repoUrl = resolvedRepos.isEmpty() ? "" : resolvedRepos.getFirst();
        Optional<GitHubRepoUrls.Slug> slugOpt = GitHubRepoUrls.parseSlug(repoUrl);
        if (slugOpt.isEmpty()) {
            LOG.warning("Fallback PR skipped: expected https://github.com/owner/repo, got: " + repoUrl);
            appendPrAutomationLog(run.id(),
                    "Skipped opening a PR: repository URL must be https://github.com/owner/repo (got: " + repoUrl + ").");
            return;
        }
        GitHubSession session = sessionOpt.get();
        String headFromRun = run.expectedHeadBranch() == null ? "" : run.expectedHeadBranch().strip();
        AgentBranchNames.HeadBranchResolution headRes = AgentBranchNames.resolveHeadBranch(agent, task);
        String effective = headRes.effectiveForGitAndApi();
        String requestedRaw = headRes.requestedRaw();
        List<String> headCandidates = new ArrayList<>();
        if (!headFromRun.isBlank()) {
            headCandidates.add(headFromRun);
        }
        if (!effective.isBlank() && !effective.equals(headFromRun)) {
            headCandidates.add(effective);
        }
        if (!requestedRaw.isBlank() && !headCandidates.contains(requestedRaw)) {
            headCandidates.add(requestedRaw);
        }
        if (headCandidates.isEmpty()) {
            appendPrAutomationLog(run.id(), "Skipped opening a PR: could not determine head branch name.");
            return;
        }
        String baseBranch = AgentBranchNames.baseBranchForPullRequest(task.startingRef(), agent.startingRef());
        GitHubRepoUrls.Slug slug = slugOpt.get();
        String accessToken = session.accessToken();
        String ghLogin = session.login() == null ? "" : session.login().strip();
        UUID runId = run.id();
        if (!githubFallbackPrInFlight.add(runId)) {
            return;
        }
        appendPrAutomationLog(runId,
                "Requesting pull request on GitHub: " + slug.owner() + "/" + slug.repo() + " base=\"" + baseBranch
                        + "\" heads to try: " + String.join(", ", headCandidates) + " (with retries if the branch is not on GitHub yet).");
        CompletableFuture.supplyAsync(() -> {
            try {
                return githubPullsClient.createPullWithHeadCandidatesAndBackoff(
                        accessToken,
                        slug.owner(),
                        slug.repo(),
                        task.displayTitle(),
                        githubFallbackPrBody(run, task),
                        headCandidates,
                        baseBranch,
                        ghLogin,
                        8
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((prUrl, asyncError) -> fxRunner(() -> {
            githubFallbackPrInFlight.remove(runId);
            Optional<AgentRun> currentOpt = agentRuns.stream().filter(r -> r.id().equals(runId)).findFirst();
            if (currentOpt.isEmpty()) {
                return;
            }
            AgentRun current = currentOpt.get();
            if (current.status() != RunStatus.FINISHED) {
                return;
            }
            if (current.pullRequestUrl() != null && !current.pullRequestUrl().isBlank()) {
                return;
            }
            if (asyncError != null) {
                Throwable root = asyncError;
                if (root instanceof RuntimeException re && re.getCause() != null) {
                    root = re.getCause();
                }
                String msg = root.getMessage() == null ? root.toString() : root.getMessage();
                String hint = "";
                if (msg.toLowerCase().contains("not accessible by integration")) {
                    hint = " Hint: GitHub blocked this token for this repo — grant the OAuth app access to the org/repo "
                            + "(GitHub → Settings → Applications → Authorized OAuth Apps → your app → Organization access / "
                            + "enable SSO if the org uses SAML), or sign in with an account that has write access to the repo.";
                }
                AgentRun updated = current.appendLog("PR automation: GitHub API error while creating PR: " + msg + hint);
                replaceRun(updated);
                save();
                notifyRunsLogRefresh(runId, updated);
                return;
            }
            AgentRun updated = current.withStatus(RunStatus.FINISHED, current.resultSummary(), prUrl)
                    .appendLog("PR automation: Created pull request via GitHub API: " + prUrl);
            replaceRun(updated);
            updateTaskFromRun(updated);
            save();
            notifyRunsLogRefresh(runId, updated);
        }));
    }

    private static String githubFallbackPrBody(AgentRun run, AgentTask task) {
        String summary = run.resultSummary() == null ? "" : run.resultSummary().strip();
        StringBuilder sb = new StringBuilder();
        sb.append("## What the agent did\n\n");
        if (summary.isBlank()) {
            sb.append("(No agent summary was returned for this run.)");
        } else {
            sb.append(summary);
        }
        String original = task.description() == null ? "" : task.description().strip();
        if (!original.isBlank()) {
            sb.append("\n\n## Original task\n\n").append(original);
        }
        return sb.toString();
    }

    private void appendPrAutomationLog(UUID runId, String detail) {
        String message = detail.startsWith("PR automation:") ? detail : "PR automation: " + detail;
        for (int i = 0; i < agentRuns.size(); i++) {
            AgentRun r = agentRuns.get(i);
            if (!r.id().equals(runId)) {
                continue;
            }
            List<RunLogEntry> logs = r.logs();
            if (!logs.isEmpty() && message.equals(logs.getLast().message())) {
                return;
            }
            AgentRun updated = r.appendLog(message);
            agentRuns.set(i, updated);
            save();
            notifyRunsLogRefresh(runId, updated);
            return;
        }
    }

    public boolean canEditTask(AgentTask task) {
        return task.status() == TaskStatus.DRAFT
                || task.status() == TaskStatus.FAILED
                || task.status() == TaskStatus.CANCELLED
                || task.status() == TaskStatus.DONE
                || task.status() == TaskStatus.WAITING_REVIEW
                || isOrphanedPreRunTask(task);
    }

    public void saveTaskFromInputs(
            AgentTask selected,
            String normalizedPrefix,
            String title,
            String description,
            List<String> repositoryTags,
            String branch,
            boolean assignDeveloper,
            UUID assignedAgentIdResolved
    ) {
        AgentTask saved = saveTaskCore(selected, normalizedPrefix, title, description, repositoryTags, branch, assignDeveloper, assignedAgentIdResolved);
        if (saved != null) {
            save();
        }
    }

    public AgentTask saveTaskFromInputsReturningSaved(
            AgentTask selected,
            String normalizedPrefix,
            String title,
            String description,
            List<String> repositoryTags,
            String branch,
            boolean assignDeveloper,
            UUID assignedAgentIdResolved
    ) {
        AgentTask saved = saveTaskCore(selected, normalizedPrefix, title, description, repositoryTags, branch, assignDeveloper, assignedAgentIdResolved);
        if (saved != null) {
            save();
        }
        return saved;
    }

    private AgentTask saveTaskCore(
            AgentTask selected,
            String normalizedPrefix,
            String title,
            String description,
            List<String> repositoryTags,
            String branch,
            boolean assignDeveloper,
            UUID assignedAgentIdResolved
    ) {
        if (title == null || title.isBlank()) {
            dialogs().showError("Task title is required.");
            return null;
        }
        UUID nextAssignedAgentId = assignDeveloper ? assignedAgentIdResolved : null;
        if (assignDeveloper && nextAssignedAgentId == null) {
            dialogs().showError("Choose an agent or uncheck Assign developer.");
            return null;
        }
        if (selected != null && !canEditTask(selected)) {
            dialogs().showError("Only tasks that are not in progress can be edited.");
            return null;
        }
        String normalized = normalizeTaskPrefix(normalizedPrefix);
        AgentTask saved;
        if (selected == null) {
            saved = AgentTask.create(
                    normalized,
                    nextTaskNumber(normalized),
                    title,
                    description,
                    "",
                    branch == null ? "" : branch,
                    nextAssignedAgentId,
                    repositoryTags == null ? List.of() : repositoryTags
            );
            agentTasks.add(saved);
        } else {
            saved = selected.withEditableFields(
                    normalized,
                    title,
                    description,
                    selected.repositoryUrl(),
                    branch == null ? "" : branch,
                    nextAssignedAgentId,
                    repositoryTags == null ? List.of() : repositoryTags
            );
            replaceTask(saved);
        }
        return saved;
    }

    public void deleteTask(AgentTask selected) {
        if (hasActiveRunForTask(selected.id())) {
            dialogs().showError("A task with an active run cannot be deleted. Cancel the run first.");
            return;
        }
        boolean ok = dialogs().confirm(
                "Delete task",
                "Delete %s?".formatted(selected.displayTitle()),
                "This will also remove local run records for this task. It will not delete remote Cursor runs, pushed branches, or PRs."
        );
        if (!ok) {
            return;
        }
        agentTasks.removeIf(task -> task.id().equals(selected.id()));
        agentRuns.removeIf(run -> run.taskId().equals(selected.id()));
        save();
    }

    private boolean isOrphanedPreRunTask(AgentTask task) {
        return (task.status() == TaskStatus.RUNNING || task.status() == TaskStatus.QUEUED) && !hasRunForTask(task.id());
    }

    private boolean hasRunForTask(UUID taskId) {
        return agentRuns.stream().anyMatch(run -> run.taskId().equals(taskId));
    }

    public boolean hasActiveRunForTask(UUID taskId) {
        return agentRuns.stream()
                .filter(run -> run.taskId().equals(taskId))
                .anyMatch(run -> !run.status().terminal());
    }

    public void refreshBranchesForTags(List<String> selectedTags, Consumer<List<String>> onBranchesLoaded) {
        List<String> seedUrls = new ArrayList<>();
        for (RepositoryEntry repo : repositories) {
            for (String tag : selectedTags) {
                if (repo.tag().equals(tag)) {
                    seedUrls.add(repo.url());
                    break;
                }
            }
        }
        if (seedUrls.isEmpty()) {
            dialogs().showError("Pick a repository tag that matches a repository before refreshing branches.");
            return;
        }
        String targetRepo = seedUrls.getFirst();
        Optional<GitHubSession> sessionOpt = githubStore.loadSession();
        if (sessionOpt.isEmpty()) {
            dialogs().showError("Sign in with GitHub (GitHub settings) to fetch branches from " + targetRepo + ".");
            return;
        }
        String token = sessionOpt.get().accessToken();
        CompletableFuture.runAsync(() -> {
            try {
                List<String> branches = githubReposClient.listBranches(token, targetRepo);
                fxRunner(() -> onBranchesLoaded.accept(branches));
            } catch (Exception ex) {
                fxRunner(() -> dialogs().showError("Could not load branches for " + targetRepo + ": " + ex.getMessage()));
            }
        });
    }

    public List<String> resolveRepositoryUrlsForTask(AgentTask task, AgentProfile agent) {
        java.util.LinkedHashSet<String> resolved = new java.util.LinkedHashSet<>();
        if (task != null && !task.repositoryTags().isEmpty()) {
            for (RepositoryEntry repo : repositories) {
                for (String tag : task.repositoryTags()) {
                    if (repo.tag().equals(tag)) {
                        String url = GitHubRepoUrls.normalizeHttpsRepositoryUrl(repo.url());
                        if (!url.isBlank()) {
                            resolved.add(url);
                        }
                    }
                }
            }
        }
        if (resolved.isEmpty() && task != null && task.repositoryUrl() != null && !task.repositoryUrl().isBlank()) {
            resolved.add(GitHubRepoUrls.normalizeHttpsRepositoryUrl(task.repositoryUrl()));
        }
        if (resolved.isEmpty() && agent != null && agent.repositoryTags() != null && !agent.repositoryTags().isEmpty()) {
            for (RepositoryEntry repo : repositories) {
                for (String tag : agent.repositoryTags()) {
                    if (repo.tag().equals(tag)) {
                        String url = GitHubRepoUrls.normalizeHttpsRepositoryUrl(repo.url());
                        if (!url.isBlank()) {
                            resolved.add(url);
                        }
                    }
                }
            }
        }
        if (resolved.isEmpty() && agent != null && agent.repositoryUrl() != null && !agent.repositoryUrl().isBlank()) {
            resolved.add(GitHubRepoUrls.normalizeHttpsRepositoryUrl(agent.repositoryUrl()));
        }
        return new ArrayList<>(resolved);
    }

    public String agentName(UUID id) {
        if (id == null) {
            return "Unassigned";
        }
        return findAgent(id).map(AgentProfile::name).orElse("Unknown agent");
    }

    public String taskTitle(UUID id) {
        return findTask(id).map(AgentTask::displayTitle).orElse("Unknown task");
    }

    public int nextTaskNumber(String normalizedPrefix) {
        return agentTasks.stream()
                .filter(task -> normalizeTaskPrefix(task.taskPrefix()).equals(normalizedPrefix))
                .mapToInt(AgentTask::taskNumber)
                .max()
                .orElse(0) + 1;
    }

    public static String normalizeTaskPrefix(String rawPrefix) {
        if (rawPrefix == null || rawPrefix.isBlank()) {
            return "TASK";
        }
        return rawPrefix.strip().toUpperCase().replaceAll("[^A-Z0-9-]", "-");
    }

    public StateStore stateStore() {
        return stateStore;
    }

    public ProviderRegistry providerRegistry() {
        return providerRegistry;
    }

    public GitHubReposClient githubReposClient() {
        return githubReposClient;
    }

    public GitHubJsonStore githubStore() {
        return githubStore;
    }

    public OllamaRuntimeSettings.Store ollamaSettingsStore() {
        return ollamaSettingsStore;
    }

    public void openUrl(String url) {
        ui().openUrl().accept(url);
    }

    public javafx.stage.Stage primaryStage() {
        return ui().primaryStage().get();
    }
}
