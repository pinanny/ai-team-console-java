package com.example.aiteamconsole.ui;

import com.example.aiteamconsole.AgentBranchNames;
import com.example.aiteamconsole.AgentRole;
import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentProviderException;
import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.AgentTaskPrompts;
import com.example.aiteamconsole.AppLogging;
import com.example.aiteamconsole.AppState;
import com.example.aiteamconsole.GitHubDeviceFlowService;
import com.example.aiteamconsole.GitHubJsonStore;
import com.example.aiteamconsole.GitHubPullsClient;
import com.example.aiteamconsole.GitHubRepoUrls;
import com.example.aiteamconsole.GitHubReposClient;
import com.example.aiteamconsole.GitHubSession;
import com.example.aiteamconsole.CursorCloudAgentProvider;
import com.example.aiteamconsole.OllamaRuntimeSettings;
import com.example.aiteamconsole.ProjectMemorySnapshot;
import com.example.aiteamconsole.ProjectMemoryStore;
import com.example.aiteamconsole.ProviderRegistry;
import com.example.aiteamconsole.ProviderType;
import com.example.aiteamconsole.RepositoryEntry;
import com.example.aiteamconsole.RunLogEntry;
import com.example.aiteamconsole.RunStatus;
import com.example.aiteamconsole.StateStore;
import com.example.aiteamconsole.TaskAssigneeHistoryEntry;
import com.example.aiteamconsole.TaskFlowWave;
import com.example.aiteamconsole.TaskLaunchHints;
import com.example.aiteamconsole.TaskStatus;
import com.example.aiteamconsole.Workspace;
import com.example.aiteamconsole.ui.agents.AgentsViewModel;
import com.example.aiteamconsole.ui.runs.RunsViewModel;
import com.example.aiteamconsole.ui.settings.SettingsViewModel;
import com.example.aiteamconsole.ui.tasks.TasksViewModel;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;
import java.time.Instant;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    public final ObservableList<Workspace> workspaces = FXCollections.observableArrayList();
    /** Repos registered for the current workspace only; when no workspace selected, same as {@link #repositories}. */
    public final FilteredList<RepositoryEntry> repositoriesInActiveWorkspace;

    private final ObjectProperty<UUID> activeWorkspaceId = new SimpleObjectProperty<>(null);
    private boolean suppressWorkspacePersistence;

    public final AgentsViewModel agents;
    public TasksViewModel tasks;
    public RunsViewModel runs;
    public SettingsViewModel settings;

    private final AtomicBoolean runPollCycleInFlight = new AtomicBoolean(false);
    private final Set<UUID> githubFallbackPrInFlight = ConcurrentHashMap.newKeySet();
    private static final int MAX_FINISHED_PR_FOLLOW_UP_POLLS = 3;
    private final ConcurrentHashMap<UUID, Integer> finishedRunFollowUpPollCount = new ConcurrentHashMap<>();
    /** Dedupes auto post-PR Cursor reviewer launches per task + PR URL. */
    private final Set<String> postPrCursorReviewScheduled = ConcurrentHashMap.newKeySet();

    private final AtomicInteger connectivityCheckEpoch = new AtomicInteger(0);
    private final BooleanProperty cursorConnectivityChecked = new SimpleBooleanProperty(false);
    private final BooleanProperty cursorConnectivityOk = new SimpleBooleanProperty(false);
    private final StringProperty cursorConnectivityDetail = new SimpleStringProperty("");
    private final BooleanProperty githubConnectivityChecked = new SimpleBooleanProperty(false);
    private final BooleanProperty githubConnectivityOk = new SimpleBooleanProperty(false);
    private final StringProperty githubConnectivityDetail = new SimpleStringProperty("");

    public MainViewModel(StateStore stateStore, ProviderRegistry providerRegistry, GitHubDeviceFlowService gitHubDeviceFlow) {
        this.stateStore = stateStore;
        this.providerRegistry = providerRegistry;
        this.gitHubDeviceFlow = gitHubDeviceFlow;
        this.repositoriesInActiveWorkspace = new FilteredList<>(repositories, this::repositoryIncludedInActiveWorkspace);
        this.agents = new AgentsViewModel(this);
        activeWorkspaceId.addListener((obs, o, n) -> {
            refreshWorkspaceFilters();
            if (!suppressWorkspacePersistence) {
                save();
            }
        });
        workspaces.addListener((ListChangeListener<Workspace>) c -> refreshWorkspaceFilters());
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

        suppressWorkspacePersistence = true;
        try {
            workspaces.setAll(state.workspaces());
            activeWorkspaceId.set(state.activeWorkspaceId());
            validateActiveWorkspaceSelection();
            refreshWorkspaceFilters();
        } finally {
            suppressWorkspacePersistence = false;
        }

        providerRegistry.cursorCloudProvider().setRepositoryResolver(this::resolveRepositoryUrlsForTask);
        providerRegistry.ollamaProvider().setRepositoryResolver(this::resolveRepositoryUrlsForTask);
        syncOllamaRuntimeSettings();

        settings.reloadCursorFieldsFromStore();
        settings.reloadGithubSessionFromStore();

        CompletableFuture.runAsync(this::checkWaitingReviewTasksForMergedPrs);
        lastPrMergeWatchNanos = System.nanoTime();
    }

    public void pollActiveRuns() {
        pollNonTerminalRunsOnce();

        long now = System.nanoTime();
        if (now - lastPrMergeWatchNanos >= PR_MERGE_WATCH_NANOS) {
            lastPrMergeWatchNanos = now;
            CompletableFuture.runAsync(this::checkWaitingReviewTasksForMergedPrs);
        }
    }

    public void save() {
        stateStore.save(new AppState(
                List.copyOf(agentProfiles),
                List.copyOf(agentTasks),
                List.copyOf(agentRuns),
                List.copyOf(repositories),
                List.copyOf(workspaces),
                activeWorkspaceId.get()
        ));
    }

    private void refreshWorkspaceFilters() {
        repositoriesInActiveWorkspace.setPredicate(null);
        repositoriesInActiveWorkspace.setPredicate(this::repositoryIncludedInActiveWorkspace);
    }

    private void validateActiveWorkspaceSelection() {
        UUID id = activeWorkspaceId.get();
        if (id != null && findWorkspace(id).isEmpty()) {
            activeWorkspaceId.set(null);
        }
    }

    public ObjectProperty<UUID> activeWorkspaceIdProperty() {
        return activeWorkspaceId;
    }

    public UUID getActiveWorkspaceId() {
        return activeWorkspaceId.get();
    }

    public void setActiveWorkspaceId(UUID id) {
        activeWorkspaceId.set(id);
    }

    public Optional<Workspace> findWorkspace(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return workspaces.stream().filter(w -> w.id().equals(id)).findFirst();
    }

    public Optional<RepositoryEntry> findRepositoryEntry(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repositories.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /**
     * Repository row visible in Domain memory / task picker for the current workspace.
     */
    public boolean repositoryIncludedInActiveWorkspace(RepositoryEntry r) {
        UUID wid = activeWorkspaceId.get();
        if (wid == null || r == null) {
            return true;
        }
        Workspace ws = findWorkspace(wid).orElse(null);
        if (ws == null) {
            return true;
        }
        return ws.repositoryIds().contains(r.id());
    }

    /**
     * Task row visible on Tasks / Runs / Pixel kanban when a workspace is focused.
     * Tasks with no repository tags only appear when no workspace is selected.
     */
    public boolean taskMatchesActiveWorkspace(AgentTask task) {
        UUID wid = activeWorkspaceId.get();
        if (wid == null || task == null) {
            return true;
        }
        Workspace ws = findWorkspace(wid).orElse(null);
        if (ws == null) {
            return true;
        }
        Set<String> wsTags = tagsForWorkspace(ws);
        if (wsTags.isEmpty()) {
            return false;
        }
        List<String> tags = task.repositoryTags();
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (String t : tags) {
            if (wsTags.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> tagsForWorkspace(Workspace ws) {
        Set<String> tags = new HashSet<>();
        for (UUID rid : ws.repositoryIds()) {
            findRepositoryEntry(rid).map(RepositoryEntry::tag).filter(s -> !s.isBlank()).ifPresent(tags::add);
        }
        return tags;
    }

    /** Tags of repositories assigned to the active workspace; empty when no workspace is selected. */
    public Set<String> repositoryTagsInActiveWorkspace() {
        UUID wid = activeWorkspaceId.get();
        if (wid == null) {
            return Set.of();
        }
        return findWorkspace(wid).map(this::tagsForWorkspace).orElse(Set.of());
    }

    /**
     * When a workspace is focused, drops agent/task tags that are not registered on that workspace.
     */
    public List<String> filterRepositoryTagsToActiveWorkspace(List<String> tags) {
        if (tags == null || tags.isEmpty() || activeWorkspaceId.get() == null) {
            return tags == null ? List.of() : List.copyOf(tags);
        }
        Set<String> allowed = repositoryTagsInActiveWorkspace();
        return tags.stream().filter(allowed::contains).toList();
    }

    /** Registry rows used for URL resolution: whole registry, or only the active workspace when one is selected. */
    private List<RepositoryEntry> repositoryEntriesInScope() {
        if (activeWorkspaceId.get() == null) {
            return repositories;
        }
        return repositories.stream().filter(this::repositoryIncludedInActiveWorkspace).toList();
    }

    public void addWorkspace(Workspace w) {
        workspaces.add(w);
        save();
    }

    public void replaceWorkspace(Workspace updated) {
        for (int i = 0; i < workspaces.size(); i++) {
            if (workspaces.get(i).id().equals(updated.id())) {
                workspaces.set(i, updated);
                save();
                return;
            }
        }
    }

    public void removeWorkspace(UUID workspaceId) {
        if (workspaceId == null) {
            return;
        }
        workspaces.removeIf(w -> w.id().equals(workspaceId));
        if (Objects.equals(activeWorkspaceId.get(), workspaceId)) {
            suppressWorkspacePersistence = true;
            try {
                activeWorkspaceId.set(null);
            } finally {
                suppressWorkspacePersistence = false;
            }
        }
        save();
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

    /**
     * Cursor may still return a PR URL when auto-create PR is on; Product Analyst outcomes are
     * specification-only ({@link TaskStatus#SPEC_REVIEW}), not a merge workflow — strip the link
     * so runs UI and GitHub automation do not treat this as a code review handoff.
     */
    private AgentRun scrubProductAnalystPullRequestLink(AgentRun run) {
        Optional<AgentProfile> agent = findAgent(run.agentProfileId());
        if (agent.isEmpty() || agent.get().role() != AgentRole.PRODUCT_ANALYST) {
            return run;
        }
        String pr = run.pullRequestUrl() == null ? "" : run.pullRequestUrl().strip();
        if (pr.isEmpty()) {
            return run;
        }
        return run.withStatus(run.status(), run.resultSummary(), "")
                .appendLog(
                        "Product Analyst: PR URL from the provider was cleared here. "
                                + "This run is specification-only — the task is SPEC_REVIEW until you Accept spec on the Tasks tab; it is not a PR/code-review handoff.");
    }

    private void applyRefreshSuccess(AgentRun run) {
        AgentRun scrubbed = scrubProductAnalystPullRequestLink(run);
        AgentRun before = agentRuns.stream().filter(r -> r.id().equals(scrubbed.id())).findFirst().orElse(null);
        replaceRun(scrubbed);
        updateTaskFromRun(scrubbed);
        maybeCreateGithubPullRequestIfNeeded(scrubbed);
        maybeQueuePostPrCursorReview(before, scrubbed);
        AgentRun latest = agentRuns.stream().filter(r -> r.id().equals(scrubbed.id())).findFirst().orElse(scrubbed);
        notifyRunsLogRefresh(scrubbed.id(), latest);
        save();
        scheduleFinishedRunFollowUpPollIfNeeded(latest);
    }

    private void scheduleFinishedRunFollowUpPollIfNeeded(AgentRun run) {
        if (run.provider() != ProviderType.CURSOR_CLOUD) {
            return;
        }
        Optional<AgentProfile> agent = findAgent(run.agentProfileId());
        if (agent.isPresent() && agent.get().role() == AgentRole.PRODUCT_ANALYST) {
            finishedRunFollowUpPollCount.remove(run.id());
            return;
        }
        // Planner runs never open a PR — no point polling for one.
        if (agent.isPresent() && agent.get().role() == AgentRole.IMPLEMENTATION_PLANNER) {
            finishedRunFollowUpPollCount.remove(run.id());
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
        AgentTask working = findTask(selected.id()).orElse(selected);
        if (working.status() == TaskStatus.SPEC_REVIEW) {
            dialogs().showError(
                    "This task is waiting for you to accept the Product Analyst specification. "
                            + "Edit the task if needed, then click Accept spec in the task list.");
            return;
        }
        if (working.status() == TaskStatus.QUEUED || working.status() == TaskStatus.RUNNING) {
            return;
        }
        if (!isProductAnalystStageDone(working) && hasProductAnalystAgent()) {
            Optional<AgentProfile> pa = firstFreeProductAnalyst();
            if (pa.isEmpty()) {
                dialogs().showError(
                        "No free Product Analyst agent is available. Create one, or wait for a running PA run to finish, "
                                + "then click Run again. The task stays in " + working.status().name() + ".");
                return;
            }
            launchProductAnalystForTask(working, pa.get());
            return;
        }
        if (!working.developmentFlow().isEmpty()) {
            int w = working.developmentFlowWaveIndex();
            if (w < 0 || w >= working.developmentFlow().size()) {
                dialogs().showError("Development pipeline step is out of range. Edit the task to fix the pipeline.");
                return;
            }
            startDevelopmentFlowWave(working, w);
            return;
        }
        if (working.assignedAgentId() != null) {
            Optional<AgentProfile> pinned = findAgent(working.assignedAgentId());
            if (pinned.isEmpty()) {
                dialogs().showError(
                        "The assigned agent profile no longer exists. Click Assign to pick another agent.");
                return;
            }
            if (!isAgentFree(pinned.get().id())) {
                dialogs().showError("Assigned agent «%s» already has an active run. Wait for it to finish or use Assign to choose another profile."
                        .formatted(pinned.get().name()));
                return;
            }
            queueImplementationRun(working, pinned.get());
            return;
        }
        Optional<AgentProfile> inferredPick = Optional.empty();
        Optional<RoleInference> inference = inferLaunchRoleForUnassignedTask(working);
        if (inference.isPresent()) {
            RoleInference ri = inference.get();
            boolean ok = dialogs().confirm(
                    "Confirm task type",
                    "Next run will pick a free agent matching the suggested role.",
                    ri.rationale()
                            + "\n\nSuggested role: " + ri.role().label()
                            + "\n\nStart with the first available agent in this role?");
            if (!ok) {
                return;
            }
            Optional<AgentProfile> pick = firstFreeAgentForRole(ri.role());
            if (pick.isEmpty()) {
                dialogs().showError("No available " + ri.role().label() + " is free right now. "
                        + "Add another agent with this role or wait for a run to finish.");
                return;
            }
            inferredPick = pick;
        }

        AgentProfile agent = inferredPick.orElseGet(() -> resolveAgentForStart(working).orElse(null));
        if (agent == null) {
            dialogs().showError("No available agent matches this task. Create another profile with this role or wait for runs to finish.");
            return;
        }
        queueImplementationRun(working, agent);
    }

    private void startDevelopmentFlowWave(AgentTask task, int waveIndex) {
        AgentTask cur = findTask(task.id()).orElse(task);
        TaskFlowWave wave = cur.developmentFlow().get(waveIndex);
        List<AgentProfile> picks = new ArrayList<>();
        for (AgentRole role : wave.parallelRoles()) {
            Optional<AgentProfile> pick = firstFreeAgentForRole(role);
            if (pick.isEmpty()) {
                dialogs().showError("Pipeline wave needs a free %s — none available right now.".formatted(role.label()));
                return;
            }
            picks.add(pick.get());
        }
        AgentTask prepared = applyDefaultBranchToTask(cur, picks.getFirst());
        AgentTask queued = prepared.withStatus(TaskStatus.QUEUED);
        replaceTask(queued);
        save();
        for (AgentProfile agent : picks) {
            launchProviderRun(agent, queued, waveIndex);
        }
    }

    private AgentTask applyDefaultBranchToTask(AgentTask taskForRun, AgentProfile agent) {
        if (taskForRun.startingRef() == null || taskForRun.startingRef().isBlank()) {
            String defaultBranch = defaultBranchForTask(taskForRun, agent);
            if (!defaultBranch.isBlank()) {
                return taskForRun.withEditableFields(
                        taskForRun.taskPrefix(),
                        taskForRun.title(),
                        taskForRun.description(),
                        taskForRun.repositoryUrl(),
                        defaultBranch,
                        taskForRun.assignedAgentId(),
                        taskForRun.repositoryTags(),
                        null,
                        null
                );
            }
        }
        return taskForRun;
    }

    private void queueImplementationRun(AgentTask working, AgentProfile agent) {
        AgentTask taskForRun = applyDefaultBranchToTask(working, agent);
        AgentTask queuedTask = taskForRun.withStatus(TaskStatus.QUEUED);
        replaceTask(queuedTask);
        save();
        launchProviderRun(agent, queuedTask, null, TaskLaunchHints.none());
    }

    private void launchProviderRun(AgentProfile agent, AgentTask queuedTask) {
        launchProviderRun(agent, queuedTask, null, TaskLaunchHints.none());
    }

    private void launchProviderRun(AgentProfile agent, AgentTask queuedTask, Integer developmentFlowWaveIndex) {
        launchProviderRun(agent, queuedTask, developmentFlowWaveIndex, TaskLaunchHints.none());
    }

    private void launchProviderRun(
            AgentProfile agent,
            AgentTask queuedTask,
            Integer developmentFlowWaveIndex,
            TaskLaunchHints hints
    ) {
        TaskLaunchHints h = hints == null ? TaskLaunchHints.none() : hints;
        CompletableFuture.supplyAsync(() -> {
            try {
                syncOllamaRuntimeSettings();
                return providerRegistry.providerFor(agent.provider()).startTask(agent, queuedTask, settings.toAppSettings(), h);
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
                AgentRun stored = developmentFlowWaveIndex == null ? run : run.withDevelopmentFlowWaveIndex(developmentFlowWaveIndex);
                agentRuns.add(stored);
                AgentTask base = findTask(queuedTask.id()).orElse(queuedTask);
                String startedReason = "Run started (%s — %s)"
                        .formatted(agent.role().label(), agent.name() == null || agent.name().isBlank() ? "agent" : agent.name());
                AgentTask running = base.withStatus(TaskStatus.RUNNING)
                        .withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(Instant.now(), agent.id(), startedReason));
                replaceTask(running);
            }
            save();
        }));
    }

    /**
     * When an implementation run first gets a GitHub PR URL, queue a Cursor Cloud Code Reviewer to leave feedback on that PR.
     */
    private void maybeQueuePostPrCursorReview(AgentRun before, AgentRun after) {
        if (after == null || after.status() != RunStatus.FINISHED) {
            return;
        }
        String prUrl = after.pullRequestUrl() == null ? "" : after.pullRequestUrl().strip();
        if (prUrl.isEmpty()) {
            return;
        }
        if (before != null) {
            String prev = before.pullRequestUrl() == null ? "" : before.pullRequestUrl().strip();
            if (!prev.isEmpty()) {
                return;
            }
        }
        Optional<AgentProfile> implAgent = findAgent(after.agentProfileId());
        if (implAgent.isEmpty()) {
            return;
        }
        AgentRole role = implAgent.get().role();
        if (role == AgentRole.PRODUCT_ANALYST || role == AgentRole.CODE_REVIEWER || role == AgentRole.PROJECT_MEMORY) {
            return;
        }
        String claimKey = after.taskId() + "|" + prUrl.toLowerCase(Locale.ROOT);
        if (!postPrCursorReviewScheduled.add(claimKey)) {
            return;
        }
        Optional<AgentProfile> reviewer = firstFreeAgentForRole(AgentRole.CODE_REVIEWER);
        if (reviewer.isEmpty() || reviewer.get().provider() != ProviderType.CURSOR_CLOUD) {
            postPrCursorReviewScheduled.remove(claimKey);
            appendPrAutomationLog(after.id(),
                    "Post-PR code review skipped: need a free Code Reviewer on Cursor Cloud (auto review is not started for Ollama-only reviewer profiles).");
            return;
        }
        AgentTask task = findTask(after.taskId()).orElse(null);
        if (task == null) {
            postPrCursorReviewScheduled.remove(claimKey);
            return;
        }
        if (hasActiveRunForTask(task.id())) {
            postPrCursorReviewScheduled.remove(claimKey);
            return;
        }
        queuePostPullRequestCursorReview(task, after, reviewer.get(), prUrl);
    }

    private void queuePostPullRequestCursorReview(AgentTask task, AgentRun implementationRun, AgentProfile reviewer, String prUrl) {
        AgentTask prepared = applyDefaultBranchToTask(task, reviewer);
        AgentTask queued = prepared.withStatus(TaskStatus.QUEUED)
                .withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(
                        Instant.now(),
                        reviewer.id(),
                        "Auto post-PR review queued for " + prUrl));
        replaceTask(queued);
        save();
        String head = implementationRun.expectedHeadBranch() == null ? "" : implementationRun.expectedHeadBranch().strip();
        TaskLaunchHints hints = TaskLaunchHints.postPullRequestCodeReview(prUrl, head.isBlank() ? null : head);
        launchProviderRun(reviewer, queued, null, hints);
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
        AgentTask taskForRun = task;
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
                        taskForRun.repositoryTags(),
                        null,
                        null
                );
                replaceTask(taskForRun);
            }
        }
        AgentTask queuedTask = taskForRun.withStatus(TaskStatus.QUEUED);
        replaceTask(queuedTask);
        save();
        launchProviderRun(agent, queuedTask, selected.developmentFlowWaveIndex());
    }

    /**
     * Picks a free agent for the implementation run from the task prefix → role mapping when no assignee is pinned.
     * When {@link AgentTask#assignedAgentId()} is set, {@link #startTask} uses that profile instead (if free).
     */
    private Optional<AgentProfile> resolveAgentForStart(AgentTask task) {
        AgentRole desiredRole = roleForTaskPrefix(task.taskPrefix()).orElse(null);
        return agentProfiles.stream()
                .filter(agent -> desiredRole == null || agent.role() == desiredRole)
                .filter(agent -> isAgentFree(agent.id()))
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
        return Optional.empty();
    }

    private Optional<AgentProfile> firstFreeAgentForRole(AgentRole role) {
        return agentProfiles.stream()
                .filter(a -> a.role() == role)
                .filter(a -> isAgentFree(a.id()))
                .findFirst();
    }

    private Optional<AgentProfile> firstFreeProductAnalyst() {
        return firstFreeAgentForRole(AgentRole.PRODUCT_ANALYST);
    }

    /**
     * Visible to the UI layer so it can decide whether to skip an explicit dev-side {@code startTask}
     * after creation: when a PA agent exists, every new task is routed through analysis first
     * (or stays DRAFT until a PA frees up) and must not be double-started on the dev path.
     */
    public boolean hasProductAnalystAgent() {
        return agentProfiles.stream().anyMatch(a -> a.role() == AgentRole.PRODUCT_ANALYST);
    }

    // -------------------------------------------------------------------------
    // Project memory onboarding
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if a project memory snapshot already exists for {@code repo}.
     * The UI can use this to show a "last analyzed" badge next to the button.
     */
    public boolean hasProjectMemory(RepositoryEntry repo) {
        return repo != null && ProjectMemoryStore.defaultStore().exists(repo.url());
    }

    /** Directory where per-repo memory JSON files are written (for UI hints). */
    public Path projectMemoryStorageDir() {
        return ProjectMemoryStore.defaultStore().storageDir();
    }

    /**
     * Loads the saved snapshot for {@code repo}, if present.
     */
    public Optional<ProjectMemorySnapshot> projectMemorySnapshotForRepo(RepositoryEntry repo) {
        if (repo == null || repo.url().isBlank()) {
            return Optional.empty();
        }
        return ProjectMemoryStore.defaultStore().load(repo.url());
    }

    /**
     * Most recently updated {@code MEM…} onboarding task for this repository (by normalized URL), any status.
     */
    public Optional<AgentTask> latestMemDomainTaskForRepo(RepositoryEntry repo) {
        if (repo == null || repo.url().isBlank()) {
            return Optional.empty();
        }
        String norm = GitHubRepoUrls.normalizeHttpsRepositoryUrl(repo.url()).toLowerCase();
        if (norm.isBlank()) {
            return Optional.empty();
        }
        return agentTasks.stream()
                .filter(t -> normalizeTaskPrefix(t.taskPrefix()).equals("MEM"))
                .filter(t -> {
                    String u = GitHubRepoUrls.normalizeHttpsRepositoryUrl(
                            t.repositoryUrl() == null ? "" : t.repositoryUrl());
                    return !u.isBlank() && u.toLowerCase().equals(norm);
                })
                .max(Comparator.comparing(AgentTask::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /**
     * Starts domain memory analysis; if a snapshot already exists, asks once for overwrite confirmation.
     */
    public void analyzeProjectMemoryWithOverwriteConfirm(RepositoryEntry repo) {
        if (repo == null || repo.url().isBlank()) {
            dialogs().showError("No repository selected.");
            return;
        }
        if (hasProjectMemory(repo)) {
            boolean ok = dialogs().confirm(
                    "Re-analyze domain?",
                    "Project memory already exists for " + repo.label(),
                    "Running again will overwrite the existing snapshot.\n"
                            + "Continue?");
            if (!ok) {
                return;
            }
        }
        analyzeProjectMemory(repo);
    }

    /**
     * Creates an ephemeral {@link AgentRole#PROJECT_MEMORY} agent (not saved to {@link #agentProfiles}),
     * creates a MEM-prefixed task, queues it, and launches the provider run.
     *
     * <p>The run will:
     * <ol>
     *   <li>Send {@link AgentTaskPrompts#buildProjectMemoryOnboardingPrompt} to the Cursor Cloud provider.</li>
     *   <li>On completion, auto-extract the structured markdown and save it to {@link ProjectMemoryStore}
     *       via {@code CursorCloudAgentProvider.maybeSaveProjectMemory}.</li>
     *   <li>All subsequent agent runs for this repo will inject the saved context automatically.</li>
     * </ol>
     *
     * <p>No {@code AgentProfile} is added to the team — this is a one-shot system operation.
     * If no repositories are configured, shows an error dialog and returns.
     *
     * @param repo the repository to analyze; must be non-null and have a valid URL
     */
    public void analyzeProjectMemory(RepositoryEntry repo) {
        if (repo == null || repo.url().isBlank()) {
            dialogs().showError("No repository selected. Add a repository in Settings first.");
            return;
        }

        // Ephemeral agent — not added to agentProfiles, exists only for this run
        AgentProfile ephemeralAgent = AgentProfile.create(
                "Domain Analyzer",
                AgentRole.PROJECT_MEMORY,
                ProviderType.CURSOR_CLOUD,
                repo.url(),
                repo.defaultBranch(),
                "memory-analysis",
                false   // never creates a PR
        );

        // Task is real — saved to state so the user can track progress and view logs
        int taskNum = nextTaskNumber("MEM");
        AgentTask task = AgentTask.create(
                "MEM",
                taskNum,
                "Analyze domain: " + repo.label(),
                "Automated project memory onboarding.\n\n"
                        + "Analyzes the repository structure, architecture, tech stack, and domain concepts. "
                        + "Output is saved as project memory and injected into all future agent runs for this repository, "
                        + "replacing the need for agents to re-read the entire codebase on each task.",
                repo.url(),
                repo.defaultBranch(),
                ephemeralAgent.id(),
                List.of(repo.displayName())
        );

        agentTasks.add(task);
        AgentTask queued = task.withStatus(TaskStatus.QUEUED);
        replaceTask(queued);
        save();

        LOG.info("analyzeProjectMemory: launching MEM task " + queued.taskKey()
                + " for repo=" + repo.url());

        launchProviderRun(ephemeralAgent, queued);
    }

    /**
     * True if this task already has a successful Product Analyst run on record, i.e. the upstream
     * analysis stage is complete and BE/FE/QA agents can proceed. Used by {@link #startTask} to
     * route any earlier state through PA first regardless of the task prefix.
     */
    public static boolean isProductAnalystStageDone(
            AgentTask task,
            Iterable<AgentRun> runs,
            Iterable<AgentProfile> profiles
    ) {
        for (AgentRun r : runs) {
            if (!r.taskId().equals(task.id()) || r.status() != RunStatus.FINISHED) {
                continue;
            }
            for (AgentProfile p : profiles) {
                if (p.id().equals(r.agentProfileId()) && p.role() == AgentRole.PRODUCT_ANALYST) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isProductAnalystStageDone(AgentTask task) {
        return isProductAnalystStageDone(task, agentRuns, agentProfiles);
    }

    /**
     * On task creation, sends the task to a free Product Analyst right away.
     * Returns the updated (now QUEUED) task if PA was launched, otherwise {@code null}.
     */
    private AgentTask autoLaunchProductAnalystOnCreate(AgentTask freshTask) {
        if (!hasProductAnalystAgent()) {
            return null;
        }
        Optional<AgentProfile> pa = firstFreeProductAnalyst();
        if (pa.isEmpty()) {
            dialogs().showInfo(
                    "Task created. No free Product Analyst right now — it will stay in DRAFT. "
                            + "Click Run when a PA is available to route it through analysis first.");
            return null;
        }
        launchProductAnalystForTask(freshTask, pa.get());
        return findTask(freshTask.id()).orElse(freshTask);
    }

    private void launchProductAnalystForTask(AgentTask task, AgentProfile pa) {
        AgentTask taskForRun = task;
        String ref = task.startingRef() == null ? "" : task.startingRef().strip();
        if (ref.isBlank()) {
            String defaultBranch = defaultBranchForTask(task, pa);
            if (!defaultBranch.isBlank()) {
                ref = defaultBranch;
            }
        }
        taskForRun = task.withEditableFields(
                task.taskPrefix(),
                task.title(),
                task.description(),
                task.repositoryUrl(),
                ref,
                pa.id(),
                task.repositoryTags(),
                null,
                null
        );
        AgentTask queuedTask = taskForRun.withStatus(TaskStatus.QUEUED);
        replaceTask(queuedTask);
        save();
        launchProviderRun(pa, queuedTask);
    }

    private boolean isAgentFree(UUID agentId) {
        return agentRuns.stream()
                .filter(run -> run.agentProfileId().equals(agentId))
                .noneMatch(run -> !run.status().terminal());
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
        if (normalized.startsWith("PA-TASK")) {
            return Optional.of(AgentRole.PRODUCT_ANALYST);
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
            if (run.status() != RunStatus.FINISHED) {
                TaskStatus taskStatus = switch (run.status()) {
                    case FINISHED -> throw new IllegalStateException();
                    case CANCELLED -> TaskStatus.CANCELLED;
                    case ERROR -> TaskStatus.FAILED;
                    case CREATING, RUNNING, UNKNOWN -> TaskStatus.RUNNING;
                };
                AgentTask next = task.withStatus(taskStatus);
                if (run.status() == RunStatus.CANCELLED && task.status() != TaskStatus.CANCELLED) {
                    next = next.withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(
                            Instant.now(), run.agentProfileId(), "Run cancelled"));
                } else if (run.status() == RunStatus.ERROR && task.status() != TaskStatus.FAILED) {
                    next = next.withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(
                            Instant.now(), run.agentProfileId(), "Run failed"));
                }
                replaceTask(next);
                return;
            }
            if (handleDevelopmentFlowRunFinished(task, run)) {
                return;
            }
            TaskStatus prev = task.status();
            TaskStatus nextStatus = taskStatusAfterFinishedRun(task, run);
            AgentTask withStatus = task.withStatus(nextStatus);
            AgentTask updated = applyProductAnalystSummaryToTaskIfNeeded(task, withStatus, run);
            updated = applyImplementationPlanToTaskIfNeeded(task, updated, run);
            if (updated.status() == TaskStatus.SPEC_REVIEW) {
                updated = updated.withEditableFields(
                        updated.taskPrefix(),
                        updated.title(),
                        updated.description(),
                        updated.repositoryUrl(),
                        updated.startingRef(),
                        null,
                        updated.repositoryTags(),
                        null,
                        null
                );
                if (prev != TaskStatus.SPEC_REVIEW) {
                    updated = updated.withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(
                            Instant.now(),
                            null,
                            "Product Analyst finished — ticket in spec review (assignee cleared)"));
                }
            } else if (prev != nextStatus) {
                updated = updated.withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(
                        Instant.now(),
                        run.agentProfileId(),
                        "Run finished — %s".formatted(nextStatus.name())));
            }
            replaceTask(updated);
            // Auto-refresh project memory after N completed tasks for this repository
            if (nextStatus == TaskStatus.DONE) {
                maybeAutoRefreshProjectMemory(updated);
            }
        });
    }

    /**
     * Default number of DONE tasks per repository that triggers an automatic project memory re-analysis.
     * Keeps the snapshot fresh as the codebase evolves, without requiring manual re-runs.
     */
    private static final int PROJECT_MEMORY_AUTO_REFRESH_TASK_THRESHOLD = 10;

    /**
     * After a task is marked DONE, checks whether the project memory snapshot for its repository
     * is stale (i.e. {@value #PROJECT_MEMORY_AUTO_REFRESH_TASK_THRESHOLD} or more tasks have
     * completed since the last analysis). If so, launches a new onboarding run automatically.
     *
     * <p>Only triggers when:
     * <ul>
     *   <li>The task has a non-blank repository URL.</li>
     *   <li>A memory snapshot already exists (first analysis must be triggered manually via
     *       "Analyze domain").</li>
     *   <li>No PROJECT_MEMORY onboarding run is currently active for the same repo.</li>
     * </ul>
     */
    private void maybeAutoRefreshProjectMemory(AgentTask completedTask) {
        String repoUrl = completedTask.repositoryUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            return;
        }
        ProjectMemoryStore store = ProjectMemoryStore.defaultStore();
        var snapshot = store.load(repoUrl);
        if (snapshot.isEmpty()) {
            return; // no snapshot yet — user must trigger first analysis manually
        }
        java.time.Instant lastUpdated = snapshot.get().lastUpdatedAt();

        // Count DONE tasks for this repo completed after the last memory update
        long doneSinceLastUpdate = agentTasks.stream()
                .filter(t -> t.status() == TaskStatus.DONE)
                .filter(t -> repoUrl.equalsIgnoreCase(t.repositoryUrl()))
                .filter(t -> t.endedAt() != null && t.endedAt().isAfter(lastUpdated))
                .count();

        if (doneSinceLastUpdate < PROJECT_MEMORY_AUTO_REFRESH_TASK_THRESHOLD) {
            return;
        }

        // Don't trigger if an onboarding MEM run is already in progress
        boolean memRunActive = agentRuns.stream()
                .filter(r -> !r.status().terminal())
                .anyMatch(r -> {
                    for (var entry : r.logs()) {
                        if (entry.message().startsWith(CursorCloudAgentProvider.MEMORY_ONBOARDING_LOG_PREFIX)) {
                            return true;
                        }
                    }
                    return false;
                });
        if (memRunActive) {
            return;
        }

        LOG.info("Auto-refresh project memory: %d DONE tasks since last snapshot (%s) for repo=%s"
                .formatted(doneSinceLastUpdate, lastUpdated, repoUrl));

        // Find the matching RepositoryEntry to pass to analyzeProjectMemory
        repositories.stream()
                .filter(r -> repoUrl.equalsIgnoreCase(r.url()))
                .findFirst()
                .ifPresent(repo -> {
                    dialogs().showInfo(
                            "Project memory auto-refresh triggered for " + repo.label() + ".\n\n"
                                    + doneSinceLastUpdate + " tasks have been completed since the last analysis.\n"
                                    + "A new MEM task has been queued to update the project snapshot.");
                    analyzeProjectMemory(repo);
                });
    }

    private boolean handleDevelopmentFlowRunFinished(AgentTask task, AgentRun run) {
        if (run.status() != RunStatus.FINISHED) {
            return false;
        }
        if (task.developmentFlow().isEmpty() || run.developmentFlowWaveIndex() == null) {
            return false;
        }
        int w = run.developmentFlowWaveIndex();
        if (w < 0 || w >= task.developmentFlow().size()) {
            return false;
        }
        List<AgentRun> waveRuns = agentRuns.stream()
                .filter(r -> r.taskId().equals(task.id()) && Objects.equals(r.developmentFlowWaveIndex(), w))
                .toList();
        boolean allTerminal = waveRuns.stream().allMatch(r -> r.status().terminal());
        if (!allTerminal) {
            AgentTask bumped = task.withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(
                    Instant.now(),
                    run.agentProfileId(),
                    "Pipeline: branch finished (wave %d still running)".formatted(w + 1)));
            replaceTask(bumped);
            save();
            return true;
        }
        boolean anyFail = waveRuns.stream()
                .anyMatch(r -> r.status() == RunStatus.ERROR || r.status() == RunStatus.CANCELLED);
        if (anyFail) {
            AgentTask failed = task.withStatus(TaskStatus.FAILED)
                    .withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(
                            Instant.now(),
                            run.agentProfileId(),
                            "Development pipeline stopped: a run in wave %d failed.".formatted(w + 1)));
            replaceTask(failed);
            save();
            return true;
        }
        int nextWave = w + 1;
        if (nextWave < task.developmentFlow().size()) {
            AgentTask advanced = task.withDevelopmentFlowWaveIndex(nextWave)
                    .withStatus(TaskStatus.OPEN)
                    .withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(
                            Instant.now(),
                            null,
                            "Pipeline: wave %d finished — starting wave %d".formatted(w + 1, nextWave + 1)));
            replaceTask(advanced);
            save();
            AgentTask toStart = findTask(task.id()).orElse(advanced);
            fxRunner(() -> startDevelopmentFlowWave(toStart, nextWave));
            return true;
        }
        TaskStatus nextStatus = taskStatusAfterFinishedRun(task, run);
        AgentTask done = task.withStatus(nextStatus)
                .withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(
                        Instant.now(),
                        run.agentProfileId(),
                        "Pipeline finished — last wave (%s)".formatted(nextStatus.name())));
        replaceTask(done);
        save();
        return true;
    }

    /**
     * When a Product Analyst run finishes, merge {@link AgentRun#resultSummary()} into the task description
     * so Edit shows the refined spec without manual copy-paste.
     */
    private AgentTask applyProductAnalystSummaryToTaskIfNeeded(AgentTask taskBefore, AgentTask withStatusApplied, AgentRun run) {
        Optional<AgentProfile> agent = findAgent(run.agentProfileId());
        if (agent.isEmpty() || agent.get().role() != AgentRole.PRODUCT_ANALYST) {
            return withStatusApplied;
        }
        String summary = run.resultSummary() == null ? "" : run.resultSummary().strip();
        if (summary.isBlank()) {
            return withStatusApplied;
        }
        String capped = summary.length() > MAX_PA_SUMMARY_CHARS_IN_TASK
                ? summary.substring(0, MAX_PA_SUMMARY_CHARS_IN_TASK) + "\n\n[Product Analyst summary truncated by AI Team Console]"
                : summary;
        String merged = mergeProductAnalystOutputIntoDescription(taskBefore.description(), capped);
        return withStatusApplied.withEditableFields(
                withStatusApplied.taskPrefix(),
                withStatusApplied.title(),
                merged,
                withStatusApplied.repositoryUrl(),
                withStatusApplied.startingRef(),
                withStatusApplied.assignedAgentId(),
                withStatusApplied.repositoryTags(),
                null,
                null
        );
    }

    private static final int MAX_PA_SUMMARY_CHARS_IN_TASK = 120_000;
    private static final String PA_OUTPUT_HEADING = "## Product Analyst output";
    private static final String PLAN_OUTPUT_HEADING = "## Implementation plan";

    /**
     * Appends or replaces the analyst block so re-running PA updates the same section instead of duplicating.
     */
    static String mergeProductAnalystOutputIntoDescription(String originalDescription, String analystSummary) {
        String orig = originalDescription == null ? "" : originalDescription;
        String body = PA_OUTPUT_HEADING + "\n\n" + analystSummary.strip();
        int idx = orig.indexOf(PA_OUTPUT_HEADING);
        if (idx >= 0) {
            String head = orig.substring(0, idx).stripTrailing();
            if (head.isEmpty()) {
                return body;
            }
            return head + "\n\n---\n\n" + body;
        }
        String stripped = orig.strip();
        if (stripped.isEmpty()) {
            return body;
        }
        return stripped + "\n\n---\n\n" + body;
    }

    /**
     * Merges an Implementation Planner output (the PLAN_START…PLAN_END block) into the
     * task description under a stable {@link #PLAN_OUTPUT_HEADING} so:
     *
     * <ul>
     *   <li>Re-running the planner replaces the same block instead of duplicating it.</li>
     *   <li>The downstream Ollama executor can extract the block via
     *       {@link AgentTaskPrompts#extractImplementationPlan(String)} without
     *       depending on the heading text.</li>
     * </ul>
     *
     * The merged body preserves the PLAN_START/END markers verbatim — they are the
     * contract the executor parses.
     */
    static String mergeImplementationPlanIntoDescription(String originalDescription, String plannerSummary) {
        String orig = originalDescription == null ? "" : originalDescription;
        String body = PLAN_OUTPUT_HEADING + "\n\n" + plannerSummary.strip();
        int idx = orig.indexOf(PLAN_OUTPUT_HEADING);
        if (idx >= 0) {
            String head = orig.substring(0, idx).stripTrailing();
            if (head.isEmpty()) {
                return body;
            }
            return head + "\n\n---\n\n" + body;
        }
        String stripped = orig.strip();
        if (stripped.isEmpty()) {
            return body;
        }
        return stripped + "\n\n---\n\n" + body;
    }

    /**
     * Implementation Planner is a text-only run (like Product Analyst). After it finishes,
     * extract the PLAN_START…PLAN_END block from the run summary and merge it into the
     * task description under {@link #PLAN_OUTPUT_HEADING}, so downstream Ollama executor
     * runs can find it without any extra plumbing.
     *
     * <p>The task status is left untouched (the planner is expected to run from OPEN and
     * the next agent — Ollama executor — also picks the task up from OPEN).
     */
    private AgentTask applyImplementationPlanToTaskIfNeeded(AgentTask taskBefore, AgentTask current, AgentRun run) {
        Optional<AgentProfile> agent = findAgent(run.agentProfileId());
        if (agent.isEmpty() || agent.get().role() != AgentRole.IMPLEMENTATION_PLANNER) {
            return current;
        }
        String summary = run.resultSummary() == null ? "" : run.resultSummary().strip();
        if (summary.isBlank()) {
            return current;
        }
        // Extract just the plan block; if markers are missing, persist the raw summary so
        // operators can still inspect it (they can re-run the planner with stricter framing).
        String planBody = AgentTaskPrompts.extractImplementationPlan(summary);
        String wrapped;
        if (planBody.isBlank()) {
            wrapped = "_(planner output had no PLAN_START/PLAN_END markers — raw summary below)_\n\n" + summary;
        } else {
            // Re-emit the markers so AgentTaskPrompts.extractImplementationPlan can find them
            // later when the executor reads the description.
            wrapped = AgentTaskPrompts.PLAN_START_MARKER + "\n" + planBody + "\n" + AgentTaskPrompts.PLAN_END_MARKER;
        }
        if (wrapped.length() > MAX_PA_SUMMARY_CHARS_IN_TASK) {
            wrapped = wrapped.substring(0, MAX_PA_SUMMARY_CHARS_IN_TASK)
                    + "\n\n[Implementation plan truncated by AI Team Console]";
        }
        String merged = mergeImplementationPlanIntoDescription(taskBefore.description(), wrapped);
        return current.withEditableFields(
                current.taskPrefix(),
                current.title(),
                merged,
                current.repositoryUrl(),
                current.startingRef(),
                current.assignedAgentId(),
                current.repositoryTags(),
                null,
                null
        );
    }

    /**
     * Product Analyst runs refine the task spec; when they finish, the task moves to {@link TaskStatus#SPEC_REVIEW}
     * until an operator accepts. Other roles use DONE / WAITING_REVIEW depending on PR presence.
     */
    private TaskStatus taskStatusAfterFinishedRun(AgentTask task, AgentRun run) {
        Optional<AgentProfile> agent = findAgent(run.agentProfileId());
        if (agent.isPresent() && agent.get().role() == AgentRole.PRODUCT_ANALYST) {
            return TaskStatus.SPEC_REVIEW;
        }
        // Implementation Planner produces a text plan that gets merged into the description;
        // it does not finish the task. Keep whatever status the task already had so an Ollama
        // executor (or operator) can take it from there.
        if (agent.isPresent() && agent.get().role() == AgentRole.IMPLEMENTATION_PLANNER) {
            return task.status();
        }
        if (run.pullRequestUrl() == null || run.pullRequestUrl().isBlank()) {
            return TaskStatus.DONE;
        }
        return TaskStatus.WAITING_REVIEW;
    }

    private void maybeCreateGithubPullRequestIfNeeded(AgentRun run) {
        if (run.status() != RunStatus.FINISHED) {
            return;
        }
        Optional<AgentProfile> runAgent = findAgent(run.agentProfileId());
        if (runAgent.isPresent() && runAgent.get().role() == AgentRole.PRODUCT_ANALYST) {
            return;
        }
        if (runAgent.isPresent() && runAgent.get().role() == AgentRole.IMPLEMENTATION_PLANNER) {
            // Planner output is a text plan; no code change, no PR.
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
        AgentProfile agent = runAgent.orElse(null);
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
            String mergedPrUrl = prUrl == null ? "" : String.valueOf(prUrl);
            AgentRun updated = current.withStatus(RunStatus.FINISHED, current.resultSummary(), mergedPrUrl)
                    .appendLog("PR automation: Created pull request via GitHub API: " + mergedPrUrl);
            AgentRun beforePr = current;
            replaceRun(updated);
            updateTaskFromRun(updated);
            maybeQueuePostPrCursorReview(beforePr, updated);
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
        sb.append("\n\n---\n**Ticket:** ")
                .append(task.taskKey())
                .append(" — ")
                .append(task.displayTitle());
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
                || task.status() == TaskStatus.OPEN
                || task.status() == TaskStatus.SPEC_REVIEW
                || task.status() == TaskStatus.FAILED
                || task.status() == TaskStatus.CANCELLED
                || task.status() == TaskStatus.DONE
                || task.status() == TaskStatus.WAITING_REVIEW
                || isOrphanedPreRunTask(task);
    }

    /**
     * Confirms the Product Analyst output: moves {@link TaskStatus#SPEC_REVIEW} → {@link TaskStatus#OPEN}
     * so implementation agents can run.
     */
    public void acceptSpecAndOpen(AgentTask task) {
        AgentTask current = findTask(task.id()).orElse(task);
        if (current.status() != TaskStatus.SPEC_REVIEW) {
            dialogs().showError("Only tasks in SPEC_REVIEW (after a successful Product Analyst run) can be accepted here.");
            return;
        }
        AgentTask opened = current.withStatus(TaskStatus.OPEN);
        opened = opened.withEditableFields(
                opened.taskPrefix(),
                opened.title(),
                opened.description(),
                opened.repositoryUrl(),
                opened.startingRef(),
                null,
                opened.repositoryTags(),
                null,
                null);
        if (!opened.developmentFlow().isEmpty()) {
            opened = opened.withDevelopmentFlowWaveIndex(0);
        }
        opened = opened.withAssigneeHistoryEntryAppended(new TaskAssigneeHistoryEntry(
                Instant.now(),
                null,
                "Spec accepted — task open for implementation"));
        replaceTask(opened);
        save();
    }

    /**
     * Creates a DRAFT task for each selected open question from a Product Analyst spec.
     *
     * <p>Each task gets:
     * <ul>
     *   <li>Prefix {@code OQ} (Open Question) so it's easy to filter and identify.</li>
     *   <li>Title = the question text (cleaned of markdown).</li>
     *   <li>Description = the question text + a back-reference to the parent task.</li>
     *   <li>Same repository tags and branch as the parent task.</li>
     *   <li>Status DRAFT — no agent assigned, no auto-launch.</li>
     * </ul>
     *
     * @param parentTask      the SPEC_REVIEW task whose open questions are being converted
     * @param selectedQuestions the subset of questions the user checked in the dialog
     * @return number of tasks actually created
     */
    public int createTasksFromOpenQuestions(AgentTask parentTask, List<String> selectedQuestions) {
        if (selectedQuestions == null || selectedQuestions.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (String question : selectedQuestions) {
            // Truncate long questions to a reasonable title length
            String title = question.length() > 120
                    ? question.substring(0, 117).stripTrailing() + "…"
                    : question;
            String description = question + "\n\n"
                    + "---\n"
                    + "_Open question from: **" + parentTask.displayTitle() + "**_";
            int taskNum = nextTaskNumber("OQ");
            AgentTask draft = AgentTask.create(
                    "OQ",
                    taskNum,
                    title,
                    description,
                    parentTask.repositoryUrl(),
                    parentTask.startingRef(),
                    null,                       // no agent — stays DRAFT until assigned
                    parentTask.repositoryTags()
            );
            agentTasks.add(draft);
            created++;
            LOG.info("createTasksFromOpenQuestions: created OQ%02d from parent=%s"
                    .formatted(taskNum, parentTask.taskKey()));
        }
        if (created > 0) {
            save();
        }
        return created;
    }

    public void saveTaskFromInputs(
            AgentTask selected,
            String normalizedPrefix,
            String title,
            String description,
            List<String> repositoryTags,
            String branch,
            boolean assignDeveloper,
            UUID assignedAgentIdResolved,
            List<TaskFlowWave> developmentFlowFromUi,
            Integer developmentFlowWaveIndexFromUi
    ) {
        AgentTask saved = saveTaskCore(
                selected,
                normalizedPrefix,
                title,
                description,
                repositoryTags,
                branch,
                assignDeveloper,
                assignedAgentIdResolved,
                developmentFlowFromUi,
                developmentFlowWaveIndexFromUi);
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
            UUID assignedAgentIdResolved,
            List<TaskFlowWave> developmentFlowFromUi,
            Integer developmentFlowWaveIndexFromUi
    ) {
        AgentTask saved = saveTaskCore(
                selected,
                normalizedPrefix,
                title,
                description,
                repositoryTags,
                branch,
                assignDeveloper,
                assignedAgentIdResolved,
                developmentFlowFromUi,
                developmentFlowWaveIndexFromUi);
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
            UUID assignedAgentIdResolved,
            List<TaskFlowWave> developmentFlowFromUi,
            Integer developmentFlowWaveIndexFromUi
    ) {
        if (title == null || title.isBlank()) {
            dialogs().showError("Task title is required.");
            return null;
        }
        if (selected != null && !canEditTask(selected)) {
            dialogs().showError("Only tasks that are not in progress can be edited.");
            return null;
        }
        String normalized = normalizeTaskPrefix(normalizedPrefix);
        AgentTask saved;
        boolean isNewTask = selected == null;
        UUID nextAssignedAgentId = isNewTask ? null : assignedAgentIdResolved;
        List<String> tags = filterRepositoryTagsToActiveWorkspace(repositoryTags == null ? List.of() : repositoryTags);
        List<TaskFlowWave> nextFlow;
        Integer nextWaveBoxed;
        if (isNewTask) {
            nextFlow = developmentFlowFromUi == null ? List.of() : List.copyOf(developmentFlowFromUi);
            nextWaveBoxed = 0;
        } else {
            nextFlow = developmentFlowFromUi != null ? List.copyOf(developmentFlowFromUi) : null;
            nextWaveBoxed = developmentFlowWaveIndexFromUi;
        }
        if (isNewTask) {
            saved = AgentTask.create(
                    normalized,
                    nextTaskNumber(normalized),
                    title,
                    description,
                    "",
                    branch == null ? "" : branch,
                    nextAssignedAgentId,
                    tags
            );
            agentTasks.add(saved);
            if (!nextFlow.isEmpty()) {
                saved = saved.withEditableFields(
                        saved.taskPrefix(),
                        saved.title(),
                        saved.description(),
                        saved.repositoryUrl(),
                        saved.startingRef(),
                        saved.assignedAgentId(),
                        saved.repositoryTags(),
                        nextFlow,
                        0);
                replaceTask(saved);
            }
            AgentTask afterPaAutoLaunch = autoLaunchProductAnalystOnCreate(saved);
            if (afterPaAutoLaunch != null) {
                saved = afterPaAutoLaunch;
            }
        } else {
            saved = selected.withEditableFields(
                    normalized,
                    title,
                    description,
                    selected.repositoryUrl(),
                    branch == null ? "" : branch,
                    nextAssignedAgentId,
                    tags,
                    nextFlow,
                    nextWaveBoxed
            );
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

    /**
     * Loads branch names from the first repository in the registry.
     */
    public void refreshBranchesFromRegistry(Consumer<List<String>> onBranchesLoaded) {
        if (repositories.isEmpty()) {
            dialogs().showError("Add at least one repository in GitHub settings before refreshing branches.");
            return;
        }
        String targetRepo = GitHubRepoUrls.normalizeHttpsRepositoryUrl(repositories.getFirst().url());
        if (targetRepo.isBlank()) {
            dialogs().showError("The first repository in the list has no valid HTTPS URL.");
            return;
        }
        fetchBranchesForRepositoryUrl(targetRepo, onBranchesLoaded);
    }

    /**
     * Loads branches from the first registry repository whose {@link RepositoryEntry#tag()} is in
     * {@code repositoryTags}. If the list is empty or nothing matches, falls back to {@link #refreshBranchesFromRegistry}.
     */
    public void refreshBranchesForRepositoryTags(List<String> repositoryTags, Consumer<List<String>> onBranchesLoaded) {
        String targetRepo = "";
        if (repositoryTags != null && !repositoryTags.isEmpty()) {
            java.util.Set<String> want = new java.util.HashSet<>(repositoryTags);
            for (RepositoryEntry repo : repositories) {
                if (want.contains(repo.tag())) {
                    String u = GitHubRepoUrls.normalizeHttpsRepositoryUrl(repo.url());
                    if (!u.isBlank()) {
                        targetRepo = u;
                        break;
                    }
                }
            }
        }
        if (targetRepo.isBlank()) {
            refreshBranchesFromRegistry(onBranchesLoaded);
            return;
        }
        fetchBranchesForRepositoryUrl(targetRepo, onBranchesLoaded);
    }

    private void fetchBranchesForRepositoryUrl(String targetRepo, Consumer<List<String>> onBranchesLoaded) {
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
        List<RepositoryEntry> pool = repositoryEntriesInScope();
        java.util.LinkedHashSet<String> resolved = new java.util.LinkedHashSet<>();
        if (task != null && !task.repositoryTags().isEmpty()) {
            for (RepositoryEntry repo : pool) {
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
            for (RepositoryEntry repo : pool) {
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
        if (resolved.isEmpty()) {
            for (RepositoryEntry repo : pool) {
                String url = GitHubRepoUrls.normalizeHttpsRepositoryUrl(repo.url());
                if (!url.isBlank()) {
                    resolved.add(url);
                }
            }
        }
        return new ArrayList<>(resolved);
    }

    public String agentName(UUID id) {
        if (id == null) {
            return "Unassigned";
        }
        return findAgent(id).map(AgentProfile::name).orElse("Unknown agent");
    }

    /**
     * Label for Tasks / Pixel Office cards: executor on an active non-terminal run; otherwise backlog semantics
     * (Product Analyst routing, SPEC_REVIEW, or role inferred from prefix after analysis is complete).
     */
    public static String agentDisplayLabel(
            AgentTask task,
            Iterable<AgentRun> runs,
            Iterable<AgentProfile> profiles,
            boolean teamHasProductAnalyst
    ) {
        if (task == null) {
            return "—";
        }
        Optional<AgentRun> active = maxNonTerminalRunForTask(task.id(), runs);
        if (active.isPresent()) {
            return snapshotAgentName(active.get().agentProfileId(), profiles);
        }
        if (task.status() == TaskStatus.SPEC_REVIEW) {
            return "Spec review";
        }
        if (!isProductAnalystStageDone(task, runs, profiles) && teamHasProductAnalyst) {
            return AgentRole.PRODUCT_ANALYST.label();
        }
        if (task.assignedAgentId() != null) {
            String pinned = snapshotAgentName(task.assignedAgentId(), profiles);
            if (!"Unknown agent".equals(pinned)) {
                return pinned;
            }
        }
        return roleForTaskPrefix(task.taskPrefix()).map(AgentRole::label).orElse("Unassigned");
    }

    /**
     * @see #agentDisplayLabel(AgentTask, Iterable, Iterable, boolean)
     */
    public String agentDisplayForTask(AgentTask task) {
        boolean teamPa = hasProductAnalystAgent();
        return agentDisplayLabel(task, agentRuns, agentProfiles, teamPa);
    }

    /**
     * Pull request link for the task row: non-terminal run with a URL wins; otherwise latest finished run that has a PR.
     */
    public Optional<String> pullRequestUrlForTask(AgentTask task) {
        if (task == null) {
            return Optional.empty();
        }
        Optional<AgentRun> active = maxNonTerminalRunForTask(task.id(), agentRuns);
        if (active.isPresent()) {
            String u = active.get().pullRequestUrl();
            if (u != null && !u.isBlank()) {
                return Optional.of(u.strip());
            }
        }
        return latestFinishedRunWithPullRequest(task.id())
                .map(r -> r.pullRequestUrl() == null ? "" : r.pullRequestUrl().strip())
                .filter(s -> !s.isBlank());
    }

    private static Optional<AgentRun> maxNonTerminalRunForTask(UUID taskId, Iterable<AgentRun> runs) {
        Optional<AgentRun> best = Optional.empty();
        for (AgentRun r : runs) {
            if (!r.taskId().equals(taskId) || r.status().terminal()) {
                continue;
            }
            if (best.isEmpty()
                    || (r.startedAt() != null
                    && (best.get().startedAt() == null || r.startedAt().isAfter(best.get().startedAt())))) {
                best = Optional.of(r);
            }
        }
        return best;
    }

    private static String snapshotAgentName(UUID agentId, Iterable<AgentProfile> profiles) {
        if (agentId == null) {
            return "Unassigned";
        }
        for (AgentProfile p : profiles) {
            if (agentId.equals(p.id())) {
                String n = p.name();
                return n == null ? "Unknown agent" : n;
            }
        }
        return "Unknown agent";
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

    /**
     * Probes Cursor Cloud API ({@code GET /v1/repositories}) and GitHub ({@code GET /user}).
     * Updates JavaFX properties; optional startup dialog when anything fails.
     */
    public void runConnectivityChecksAsync(boolean showStartupWarningIfAnyFail) {
        int epoch = connectivityCheckEpoch.incrementAndGet();
        fxRunner(() -> {
            cursorConnectivityChecked.set(false);
            githubConnectivityChecked.set(false);
        });
        CompletableFuture.runAsync(() -> {
            boolean cursorOk = false;
            String cursorDetail = "";
            try {
                List<String> listed =
                        providerRegistry.cursorCloudProvider().listAccessibleRepositoryUrls(settings.toAppSettings());
                cursorOk = true;
                cursorDetail = "Connected (%d repos)".formatted(listed.size());
            } catch (AgentProviderException e) {
                cursorDetail = e.getMessage();
            } catch (Exception e) {
                cursorDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            boolean ghOk = false;
            String ghDetail = "";
            try {
                Optional<GitHubSession> sess = githubStore.loadSession();
                if (sess.isEmpty()) {
                    ghDetail = "Not signed in — Settings → GitHub…";
                } else {
                    githubReposClient.verifyToken(sess.get().accessToken());
                    ghOk = true;
                    ghDetail = "Signed in as @" + sess.get().login();
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                ghDetail = msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
            }

            boolean cOk = cursorOk;
            String cDet = cursorDetail;
            boolean gOk = ghOk;
            String gDet = ghDetail;
            fxRunner(() -> {
                if (epoch != connectivityCheckEpoch.get()) {
                    return;
                }
                cursorConnectivityOk.set(cOk);
                cursorConnectivityDetail.set(cDet);
                cursorConnectivityChecked.set(true);
                githubConnectivityOk.set(gOk);
                githubConnectivityDetail.set(gDet);
                githubConnectivityChecked.set(true);

                if (showStartupWarningIfAnyFail && (!cOk || !gOk)) {
                    StringBuilder sb = new StringBuilder();
                    if (!cOk) {
                        sb.append("Cursor Cloud: ").append(cDet)
                                .append("\nFix: Settings → Cursor… — API key or env CURSOR_API_KEY.\n\n");
                    }
                    if (!gOk) {
                        sb.append("GitHub: ").append(gDet)
                                .append("\nFix: Settings → GitHub… — Sign in with GitHub.\n");
                    }
                    dialogs().showWarning("Connection check", sb.toString().strip());
                }
            });
        });
    }

    public BooleanProperty cursorConnectivityCheckedProperty() {
        return cursorConnectivityChecked;
    }

    public BooleanProperty cursorConnectivityOkProperty() {
        return cursorConnectivityOk;
    }

    public StringProperty cursorConnectivityDetailProperty() {
        return cursorConnectivityDetail;
    }

    public BooleanProperty githubConnectivityCheckedProperty() {
        return githubConnectivityChecked;
    }

    public BooleanProperty githubConnectivityOkProperty() {
        return githubConnectivityOk;
    }

    public StringProperty githubConnectivityDetailProperty() {
        return githubConnectivityDetail;
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
