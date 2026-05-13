package com.example.aiteamconsole;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.collections.ListChangeListener;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AiTeamConsoleApplication extends Application {

    private record RoleInference(AgentRole role, String rationale) {
    }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static final Logger LOG = AppLogging.get(AiTeamConsoleApplication.class);

    private final StateStore stateStore = StateStore.defaultStore();
    private final ProviderRegistry providerRegistry = new ProviderRegistry();
    private final CursorApiStore cursorApiStore = CursorApiStore.defaultStore();
    private final ClaudeApiStore claudeApiStore = ClaudeApiStore.defaultStore();
    /** Session-only Claude API key (never persisted; see {@link ClaudeApiStore}). */
    private String claudeSessionApiKey = "";

    private ObservableList<AgentProfile> agents;
    private ObservableList<AgentTask> tasks;
    private ObservableList<AgentRun> runs;
    /** Backing list for Runs table; predicate re-applied when tasks change. */
    private FilteredList<AgentRun> runsFiltered;
    /** "All" or {@link TaskStatus#name()}. */
    private String runsTaskStatusFilter = "All";
    private ObservableList<RepositoryEntry> repositories;

    private PasswordField cursorApiKeyField;
    private TextArea runLogArea;
    private TableView<AgentRun> runsTable;
    private Timeline runStatusPollTimeline;
    /** Polls GitHub for merged PRs linked to tasks in {@link TaskStatus#WAITING_REVIEW}. */
    private Timeline prMergeWatchTimeline;
    private final AtomicBoolean runPollCycleInFlight = new AtomicBoolean(false);
    /** Avoids duplicate GitHub fallback PR attempts when the user refreshes a finished run while a request is in flight. */
    private final Set<UUID> githubFallbackPrInFlight = ConcurrentHashMap.newKeySet();
    /** After FINISHED, polling stops; a few delayed GETs can still pick up {@code pullRequestUrl} if Cursor fills it late. */
    private static final int MAX_FINISHED_PR_FOLLOW_UP_POLLS = 3;
    private final ConcurrentHashMap<UUID, Integer> finishedRunFollowUpPollCount = new ConcurrentHashMap<>();

    private Stage primaryStage;
    /** Tasks form: after Edit, show save/start; after Create task, show create only. */
    private final BooleanProperty taskFormIsEditMode = new SimpleBooleanProperty(false);
    private final GitHubJsonStore githubStore = GitHubJsonStore.defaultStore();
    private final GitHubDeviceFlowService githubDeviceFlow = new GitHubDeviceFlowService();
    private final GitHubReposClient githubReposClient = new GitHubReposClient();
    private final GitHubPullsClient githubPullsClient = new GitHubPullsClient();
    private final OllamaRuntimeSettings.Store ollamaSettingsStore = OllamaRuntimeSettings.Store.defaultStore();
    private TextField githubClientIdField;

    public static void main(String[] args) {
        AppLogging.init();
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        AppState state = stateStore.load();
        agents = FXCollections.observableArrayList(state.agents());
        tasks = FXCollections.observableArrayList(state.tasks());
        runs = FXCollections.observableArrayList(state.runs());
        repositories = FXCollections.observableArrayList(state.repositories());

        providerRegistry.cursorCloudProvider().setRepositoryResolver(this::resolveRepositoryUrlsForTask);
        providerRegistry.ollamaProvider().setRepositoryResolver(this::resolveRepositoryUrlsForTask);
        providerRegistry.claudeApiProvider().setRepositoryResolver(this::resolveRepositoryUrlsForTask);
        syncLocalAgentRuntimeSettings();

        primaryStage = stage;

        initCursorCredentialsFields();

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setTop(buildSettingsPane());

        TabPane tabs = new TabPane();
        tabs.getTabs().add(new Tab("Agents", buildAgentsPane()));
        tabs.getTabs().add(new Tab("Tasks", buildTasksPane()));
        tabs.getTabs().add(new Tab("Runs", buildRunsPane()));
        tabs.getTabs().forEach(tab -> tab.setClosable(false));
        root.setCenter(tabs);

        stage.setTitle("AI Team Console");
        stage.setScene(new Scene(root, 1120, 760));
        stage.show();
        startRunStatusPolling();
        startPrMergeWatching();
    }

    @Override
    public void stop() throws Exception {
        if (runStatusPollTimeline != null) {
            runStatusPollTimeline.stop();
        }
        if (prMergeWatchTimeline != null) {
            prMergeWatchTimeline.stop();
        }
        super.stop();
    }

    private static final Duration RUN_POLL_INTERVAL = Duration.seconds(8);
    private static final Duration PR_MERGE_WATCH_INTERVAL = Duration.minutes(3);

    /**
     * Periodically checks tasks in {@link TaskStatus#WAITING_REVIEW}: if the linked GitHub pull request is merged,
     * marks the task {@link TaskStatus#DONE} and appends a line to the run log. Requires GitHub sign-in.
     */
    private void startPrMergeWatching() {
        if (prMergeWatchTimeline != null) {
            prMergeWatchTimeline.stop();
        }
        Platform.runLater(() -> {
            checkWaitingReviewTasksForMergedPrs();
            prMergeWatchTimeline = new Timeline(new KeyFrame(PR_MERGE_WATCH_INTERVAL, e -> checkWaitingReviewTasksForMergedPrs()));
            prMergeWatchTimeline.setCycleCount(Timeline.INDEFINITE);
            prMergeWatchTimeline.play();
        });
    }

    private void checkWaitingReviewTasksForMergedPrs() {
        Optional<GitHubSession> sessionOpt = githubStore.loadSession();
        if (sessionOpt.isEmpty()) {
            return;
        }
        List<AgentTask> waiting = tasks.stream().filter(t -> t.status() == TaskStatus.WAITING_REVIEW).toList();
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
                    Platform.runLater(() -> markTaskDoneIfStillWaitingReview(task.id(), run.id(), prUrl));
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
        return runs.stream()
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
        for (int i = 0; i < runs.size(); i++) {
            AgentRun r = runs.get(i);
            if (r.id().equals(runId)) {
                AgentRun updated = r.appendLog("PR merge watch: merged on GitHub (" + prUrl + ") — task marked Done.");
                runs.set(i, updated);
                refreshRunLogIfThisRunSelected(runId, updated);
                break;
            }
        }
        saveState();
    }

    /**
     * Polls Cursor for every non-terminal run on an interval. Sequential requests reduce burst rate-limit risk.
     */
    private void startRunStatusPolling() {
        if (runStatusPollTimeline != null) {
            runStatusPollTimeline.stop();
        }
        Platform.runLater(() -> {
            pollNonTerminalRunsOnce();
            runStatusPollTimeline = new Timeline(new KeyFrame(RUN_POLL_INTERVAL, e -> pollNonTerminalRunsOnce()));
            runStatusPollTimeline.setCycleCount(Timeline.INDEFINITE);
            runStatusPollTimeline.play();
        });
    }

    private void pollNonTerminalRunsOnce() {
        if (!runPollCycleInFlight.compareAndSet(false, true)) {
            return;
        }
        List<UUID> activeIds = runs.stream()
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
        Optional<AgentRun> current = runs.stream().filter(r -> r.id().equals(id)).findFirst();
        if (current.isEmpty() || current.get().status().terminal()) {
            pollNonTerminalRunsFromIndex(activeIds, index + 1);
            return;
        }
        AgentRun toPoll = current.get();
        CompletableFuture.supplyAsync(() -> {
            try {
                syncLocalAgentRuntimeSettings();
                return providerRegistry.providerFor(toPoll.provider()).refreshRun(toPoll, currentSettings());
            } catch (AgentProviderException e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((run, error) -> Platform.runLater(() -> {
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
        AgentRun latest = runs.stream().filter(r -> r.id().equals(run.id())).findFirst().orElse(run);
        refreshRunLogIfThisRunSelected(run.id(), latest);
        saveState();
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
        PauseTransition pause = new PauseTransition(Duration.seconds(7));
        pause.setOnFinished(ev -> {
            Optional<AgentRun> opt = runs.stream().filter(r -> r.id().equals(runId)).findFirst();
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
        });
        pause.play();
    }

    private void initCursorCredentialsFields() {
        AppSettings defaults = AppSettings.fromEnvironment();
        CursorApiCredentials storedCursorApi = cursorApiStore.load();
        cursorApiKeyField = new PasswordField();
        cursorApiKeyField.setPromptText("cursor_...");
        cursorApiKeyField.setText(
                !storedCursorApi.apiKey().isBlank() ? storedCursorApi.apiKey() : defaults.cursorApiKey()
        );
    }

    private VBox buildSettingsPane() {
        Button cursorSettingsBtn = new Button("Cursor settings…");
        cursorSettingsBtn.setOnAction(event -> openCursorSettingsWindow());

        Button ollamaSettingsBtn = new Button("Ollama settings…");
        ollamaSettingsBtn.setOnAction(event -> openOllamaSettingsWindow());

        Button claudeApiSettingsBtn = new Button("Claude API settings…");
        claudeApiSettingsBtn.setOnAction(event -> openClaudeApiSettingsWindow());

        Button githubSettingsBtn = new Button("GitHub settings…");
        githubSettingsBtn.setOnAction(event -> openGithubSettingsWindow());

        Button pixelOfficeBtn = new Button("Pixel Office…");
        pixelOfficeBtn.setOnAction(event -> openPixelOfficeWindow());

        HBox buttonsRow = new HBox(8, cursorSettingsBtn, ollamaSettingsBtn, claudeApiSettingsBtn, githubSettingsBtn, pixelOfficeBtn);
        return new VBox(6, buttonsRow);
    }

    private com.example.aiteamconsole.pixel.PixelOfficeStage pixelOfficeStage;

    private void openPixelOfficeWindow() {
        if (pixelOfficeStage != null && pixelOfficeStage.isShowing()) {
            pixelOfficeStage.toFront();
            return;
        }
        pixelOfficeStage = new com.example.aiteamconsole.pixel.PixelOfficeStage(
                primaryStage, agents, tasks, runs
        );
        pixelOfficeStage.show();
    }

    private void openCursorSettingsWindow() {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.setTitle("Cursor settings");

        Button saveCursorApiKey = new Button("Save API key");
        saveCursorApiKey.setOnAction(event -> {
            String key = cursorApiKeyField.getText() == null ? "" : cursorApiKeyField.getText().strip();
            if (key.isBlank()) {
                showError("Enter a Cursor API key first.");
                return;
            }
            cursorApiStore.save(new CursorApiCredentials(key));
            showInfo("Saved Cursor API key to %s. Use \"Forget\" to delete it.".formatted(cursorApiStore.filePath()));
        });
        Button forgetCursorApiKey = new Button("Forget");
        forgetCursorApiKey.setOnAction(event -> {
            cursorApiStore.clear();
            cursorApiKeyField.clear();
            showInfo("Removed local Cursor API key file (env CURSOR_API_KEY still applies if set).");
        });

        HBox apiKeyRow = new HBox(8, new Label("Cursor API key"), cursorApiKeyField, saveCursorApiKey, forgetCursorApiKey);
        HBox.setHgrow(cursorApiKeyField, Priority.ALWAYS);

        TextField verifyRepoUrl = new TextField();
        verifyRepoUrl.setPromptText("Optional: repo URL to check (else first agent repo)");
        Button verifyRepo = new Button("Verify repo access");
        verifyRepo.setOnAction(event -> {
            String url = verifyRepoUrl.getText().strip();
            if (url.isBlank()) {
                url = repositories.stream()
                        .map(RepositoryEntry::url)
                        .filter(u -> u != null && !u.isBlank())
                        .findFirst()
                        .orElse("");
            }
            if (url.isBlank()) {
                showError("Enter a repository URL or add a repository in GitHub settings.");
                return;
            }
            String urlToVerify = url;
            CompletableFuture.runAsync(() -> {
                try {
                    List<String> listed = providerRegistry.cursorCloudProvider().listAccessibleRepositoryUrls(currentSettings());
                    String normalized = GitHubRepoUrls.normalizeHttpsRepositoryUrl(urlToVerify);
                    String target = normalized.toLowerCase();
                    boolean ok = listed.stream()
                            .map(GitHubRepoUrls::normalizeHttpsRepositoryUrl)
                            .map(String::toLowerCase)
                            .anyMatch(u -> u.equals(target));
                    String sample = String.join("\n", listed.stream().limit(40).toList());
                    Platform.runLater(() -> {
                        Alert alert = new Alert(ok ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
                        alert.setTitle("AI Team Console");
                        alert.setHeaderText(ok ? "Repository is visible to Cloud Agents" : "Repository not in Cursor /v1/repositories list");
                        alert.setContentText(
                                (ok
                                        ? "Cursor listed this URL (normalized): %s.%n%nRepos returned: %d.%n%nGET /v1/repositories is rate-limited (about 1/min)."
                                        : "Your repo (normalized): %s%n%nCursor returned %d repos for this API key; yours was not among them. Cloud Agents cannot verify branches until the Cursor GitHub App can access this repo.%n%nGET /v1/repositories is rate-limited (about 1/min).")
                                        .formatted(normalized, listed.size())
                        );
                        TextArea area = new TextArea(
                                sample.isBlank()
                                        ? "(no repos in response)"
                                        : "First repos from API (up to 40):\n" + sample
                        );
                        area.setEditable(false);
                        area.setWrapText(false);
                        area.setPrefRowCount(12);
                        ScrollPane scroll = new ScrollPane(area);
                        scroll.setFitToWidth(true);
                        scroll.setPrefViewportHeight(200);
                        alert.getDialogPane().setExpandableContent(scroll);
                        alert.getDialogPane().setExpanded(!ok);
                        alert.getDialogPane().setPrefWidth(640);
                        alert.showAndWait();
                    });
                } catch (AgentProviderException e) {
                    Platform.runLater(() -> showError(e.getMessage()));
                }
            });
        });
        HBox verifyRow = new HBox(8, new Label("GitHub check"), verifyRepoUrl, verifyRepo);
        HBox.setHgrow(verifyRepoUrl, Priority.ALWAYS);

        Label modelNote = new Label(
                "Cloud Agents always use model Composer 2 (API id: " + CursorCloudAgentProvider.CURSOR_CLOUD_MODEL_ID + "). "
                        + "Optional override of API base URL: set env CURSOR_API_BASE_URL (default https://api.cursor.com).");
        modelNote.setWrapText(true);
        modelNote.setStyle("-fx-text-fill: #666;");

        Label stateNote = new Label(
                "State file: %s. API keys are not stored there.".formatted(stateStore.stateFile()));
        stateNote.setWrapText(true);
        stateNote.setStyle("-fx-text-fill: #666;");

        Label logNote = new Label(
                "Logs: %s (no secrets; prompts logged as length only)".formatted(AppLogging.logDirectory()));
        logNote.setWrapText(true);
        logNote.setStyle("-fx-text-fill: #666;");

        VBox content = new VBox(12,
                new Label("Cursor Cloud API"),
                apiKeyRow,
                new Separator(),
                new Label("Repository visibility (Cursor lists accessible repos)"),
                verifyRow,
                modelNote,
                new Separator(),
                new Label("Local data"),
                stateNote,
                logNote
        );
        content.setPadding(new Insets(16));

        Scene scene = new Scene(content, 720, 500);
        dlg.setScene(scene);
        dlg.show();
    }

    private void openOllamaSettingsWindow() {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.setTitle("Ollama settings");

        OllamaRuntimeSettings cur = ollamaSettingsStore.load();
        TextField baseUrl = new TextField(cur.ollamaBaseUrl());
        TextField model = new TextField(cur.ollamaModel());
        TextField embedModel = new TextField(cur.embeddingModel());
        CheckBox qEn = new CheckBox("Enable Qdrant RAG for Ollama runs");
        qEn.setSelected(cur.qdrantEnabled());
        TextField qUrl = new TextField(cur.qdrantBaseUrl());
        TextField qCol = new TextField(cur.qdrantCollection());
        TextField dim = new TextField(String.valueOf(cur.embeddingDimensions()));
        TextField maxFiles = new TextField(String.valueOf(cur.ragMaxFiles()));
        TextField chunk = new TextField(String.valueOf(cur.ragChunkChars()));

        Button save = new Button("Save");
        save.setOnAction(e -> {
            try {
                OllamaRuntimeSettings next = new OllamaRuntimeSettings(
                        baseUrl.getText(),
                        model.getText(),
                        embedModel.getText(),
                        qEn.isSelected(),
                        qUrl.getText(),
                        qCol.getText(),
                        Integer.parseInt(dim.getText().strip()),
                        Integer.parseInt(maxFiles.getText().strip()),
                        Integer.parseInt(chunk.getText().strip())
                ).normalized();
                ollamaSettingsStore.save(next);
                syncLocalAgentRuntimeSettings();
                showInfo("Saved Ollama settings to %s.".formatted(ollamaSettingsStore.filePath()));
                dlg.close();
            } catch (NumberFormatException ex) {
                showError("Embedding dimensions / RAG numbers must be integers.");
            }
        });

        Label hint = new Label(
                "Ollama runs clone GitHub on this machine (git on PATH), call your model, apply a unified diff, commit, and push. "
                        + "Sign in via GitHub settings. Optional Qdrant at default http://127.0.0.1:6333 uses Ollama embeddings for retrieval. "
                        + "Chat model must match `ollama list` exactly (e.g. llama3.2:3b, not bare llama3.2). "
                        + "Default chat model can be overridden with env OLLAMA_CHAT_MODEL.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #666;");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        int r = 0;
        grid.addRow(r++, new Label("Ollama base URL"), baseUrl);
        grid.addRow(r++, new Label("Chat model"), model);
        grid.addRow(r++, new Label("Embedding model"), embedModel);
        grid.addRow(r++, qEn, new Label(""));
        grid.addRow(r++, new Label("Qdrant base URL"), qUrl);
        grid.addRow(r++, new Label("Qdrant collection (unused; per-run collection)"), qCol);
        grid.addRow(r++, new Label("Embedding dimensions"), dim);
        grid.addRow(r++, new Label("RAG max files"), maxFiles);
        grid.addRow(r++, new Label("RAG chunk chars"), chunk);
        GridPane.setColumnSpan(baseUrl, 3);
        GridPane.setColumnSpan(model, 3);
        GridPane.setColumnSpan(embedModel, 3);
        GridPane.setColumnSpan(qUrl, 3);
        GridPane.setColumnSpan(qCol, 3);
        GridPane.setColumnSpan(dim, 3);
        GridPane.setColumnSpan(maxFiles, 3);
        GridPane.setColumnSpan(chunk, 3);

        VBox root = new VBox(12, hint, grid, save);
        root.setPadding(new Insets(16));
        dlg.setScene(new Scene(root, 560, 480));
        dlg.show();
    }

    private void openClaudeApiSettingsWindow() {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.setTitle("Claude API Settings");

        ClaudeApiSettings cur = claudeApiStore.load();
        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setPromptText("sk-ant-…");
        if (!claudeSessionApiKey.isBlank()) {
            apiKeyField.setText(claudeSessionApiKey);
        }

        ComboBox<String> modelPick = new ComboBox<>(FXCollections.observableArrayList(
                "claude-haiku-4-5",
                "claude-sonnet-4-6",
                "claude-opus-4-6"));
        modelPick.setEditable(true);
        String m = cur.model() == null || cur.model().isBlank() ? "claude-sonnet-4-6" : cur.model();
        modelPick.getSelectionModel().select(m);
        if (!modelPick.getItems().contains(m)) {
            modelPick.getItems().add(0, m);
            modelPick.getSelectionModel().select(0);
        }

        TextField baseUrlField = new TextField(cur.baseUrl() == null || cur.baseUrl().isBlank()
                ? "https://api.anthropic.com"
                : cur.baseUrl());

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> dlg.close());

        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            String modelText = modelPick.getEditor().getText();
            if (modelText == null || modelText.isBlank()) {
                modelText = modelPick.getSelectionModel().getSelectedItem();
            }
            ClaudeApiSettings nextDisk = new ClaudeApiSettings("", modelText == null ? "" : modelText.strip(),
                    baseUrlField.getText() == null ? "" : baseUrlField.getText().strip());
            claudeApiStore.save(nextDisk);
            claudeSessionApiKey = apiKeyField.getText() == null ? "" : apiKeyField.getText().strip();
            syncLocalAgentRuntimeSettings();
            showInfo("Saved Claude model and base URL to %s. API key is kept in memory for this session only."
                    .formatted(claudeApiStore.filePath()));
            dlg.close();
        });

        Label hint = new Label(
                "Model presets: Haiku (fast & cheap), Sonnet (balanced), Opus (most capable). "
                        + "The API key is never written to disk — use ANTHROPIC_API_KEY or enter it here each session.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #666;");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        int r = 0;
        grid.addRow(r++, new Label("API Key"), apiKeyField);
        grid.addRow(r++, new Label("Model"), modelPick);
        grid.addRow(r++, new Label("Base URL"), baseUrlField);
        GridPane.setColumnSpan(apiKeyField, 2);
        GridPane.setColumnSpan(modelPick, 2);
        GridPane.setColumnSpan(baseUrlField, 2);

        HBox actions = new HBox(8, cancel, save);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(12, hint, grid, actions);
        root.setPadding(new Insets(16));
        dlg.setScene(new Scene(root, 480, 320));
        dlg.show();
    }

    /**
     * Builds the GitHub OAuth block (client id, sign-in / sign-out, status). Used in the GitHub settings window;
     * initializes {@link #githubClientIdField}.
     */
    private VBox buildGithubBlock() {
        GitHubOAuthAppSettings ghStored = githubStore.loadOAuthAppSettings();
        String ghClientFromEnv = System.getenv().getOrDefault("GITHUB_OAUTH_CLIENT_ID", "");
        githubClientIdField = new TextField();
        githubClientIdField.setPromptText("OAuth App Client ID (enable Device flow on GitHub)");
        githubClientIdField.setText(!ghStored.clientId().isBlank() ? ghStored.clientId() : ghClientFromEnv);

        Button saveGhClientId = new Button("Save Client ID");
        saveGhClientId.setOnAction(event -> {
            githubStore.saveOAuthAppSettings(new GitHubOAuthAppSettings(githubClientIdField.getText()));
            showInfo("Saved GitHub OAuth Client ID under your profile folder (this is public; not the client secret).");
        });

        Label localStatus = new Label();
        localStatus.setWrapText(true);
        localStatus.setText(formatGithubSessionStatusLine());

        Button ghSignIn = new Button("Sign in with GitHub");
        ghSignIn.setOnAction(event -> {
            String cid = githubClientIdField.getText().strip();
            if (cid.isBlank()) {
                showError("Enter a GitHub OAuth App Client ID (Developer settings → OAuth Apps), or set GITHUB_OAUTH_CLIENT_ID.");
                return;
            }
            githubStore.saveOAuthAppSettings(new GitHubOAuthAppSettings(cid));
            startGitHubDeviceFlow(cid);
        });

        Button ghSignOut = new Button("Sign out GitHub");
        ghSignOut.setOnAction(event -> {
            githubStore.clearSession();
            localStatus.setText(formatGithubSessionStatusLine());
            LOG.info("GitHub session cleared for this OS user");
        });

        Button reimport = new Button("Import my repositories now");
        reimport.setOnAction(event -> githubStore.loadSession().ifPresentOrElse(
                session -> importGithubReposIntoTable(session.accessToken(), true),
                () -> showError("Sign in with GitHub first.")
        ));

        HBox ghClientRow = new HBox(8, new Label("GitHub OAuth Client ID"), githubClientIdField, saveGhClientId);
        HBox.setHgrow(githubClientIdField, Priority.ALWAYS);
        HBox ghBtnRow = new HBox(8, ghSignIn, ghSignOut, reimport);
        return new VBox(6,
                new Label("GitHub account (per OS user; token in ~/.ai-team-console-java/). "
                        + "After sign-in your repositories are imported automatically; edit repository names/default branches below. "
                        + "Cursor Cloud Agents still need GitHub linked in Cursor."),
                ghClientRow,
                localStatus,
                ghBtnRow
        );
    }

    /**
     * Opens GitHub account settings and the repository registry in a dedicated window.
     */
    private void openGithubSettingsWindow() {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.setTitle("GitHub settings");

        VBox content = new VBox(12, buildGithubBlock(), new Separator(), buildRepositoriesBlock());
        content.setPadding(new Insets(16));
        VBox.setVgrow(content, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);

        Scene scene = new Scene(scroll, 900, 640);
        dlg.setScene(scene);
        dlg.show();
    }

    private VBox buildRepositoriesBlock() {
        TableView<RepositoryEntry> table = new TableView<>(repositories);
        table.setPrefHeight(160);
        table.getColumns().add(textColumn("Name / tag", RepositoryEntry::label, 180));
        table.getColumns().add(textColumn("URL", RepositoryEntry::url, 320));
        table.getColumns().add(textColumn("Default branch", RepositoryEntry::defaultBranch, 140));

        TextField urlField = new TextField();
        urlField.setPromptText("https://github.com/org/repo");
        TextField displayField = new TextField();
        displayField.setPromptText("Repository tag/name, e.g. backend");
        TextField defaultBranchField = new TextField("main");
        defaultBranchField.setPromptText("main");

        Button add = new Button("Add repository");
        add.setOnAction(event -> {
            String url = urlField.getText() == null ? "" : urlField.getText().strip();
            if (url.isBlank()) {
                showError("Repository URL is required.");
                return;
            }
            RepositoryEntry entry = RepositoryEntry.create(url, displayField.getText(), defaultBranchField.getText());
            if (entry.url().isBlank()) {
                showError("Could not parse a GitHub HTTPS URL from: " + url);
                return;
            }
            repositories.add(entry);
            saveState();
            urlField.clear();
            displayField.clear();
            defaultBranchField.setText("main");
        });

        Button save = new Button("Save selected");
        save.setOnAction(event -> {
            RepositoryEntry selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select a repository in the table first.");
                return;
            }
            RepositoryEntry updated = selected.withFields(urlField.getText(), displayField.getText(), defaultBranchField.getText());
            replaceRepository(updated);
            saveState();
        });

        Button remove = new Button("Remove selected");
        remove.setOnAction(event -> {
            RepositoryEntry selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select a repository in the table first.");
                return;
            }
            repositories.removeIf(r -> r.id().equals(selected.id()));
            saveState();
        });

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldRepo, newRepo) -> {
            if (newRepo == null) {
                return;
            }
            urlField.setText(newRepo.url());
            displayField.setText(newRepo.displayName());
            defaultBranchField.setText(newRepo.defaultBranch());
        });

        HBox fieldsRow = new HBox(8,
                new Label("URL"), urlField,
                new Label("Name"), displayField,
                new Label("Default branch"), defaultBranchField
        );
        HBox.setHgrow(urlField, Priority.ALWAYS);
        HBox.setHgrow(displayField, Priority.ALWAYS);
        HBox.setHgrow(defaultBranchField, Priority.ALWAYS);
        HBox btnRow = new HBox(8, add, save, remove);

        autoSizeColumnsToContent(table);
        Label intro = new Label("Repositories. The Name field is the repository tag used by agents and tasks. "
                + "Default branch is main unless changed.");
        intro.setWrapText(true);
        return new VBox(6, intro, table, fieldsRow, btnRow);
    }

    private void replaceRepository(RepositoryEntry updated) {
        for (int i = 0; i < repositories.size(); i++) {
            if (repositories.get(i).id().equals(updated.id())) {
                repositories.set(i, updated);
                return;
            }
        }
    }

    private VBox buildAgentsPane() {
        TableView<AgentProfile> table = new TableView<>(agents);
        table.getColumns().add(textColumn("Name", AgentProfile::name, 180));
        table.getColumns().add(textColumn("Role", agent -> agent.role().label(), 160));
        table.getColumns().add(textColumn("Provider", agent -> agent.provider().toString(), 160));
        table.getColumns().add(textColumn("Status", agent -> isAgentFree(agent.id()) ? "Active" : "Working", 100));
        table.getColumns().add(textColumn("Repository tags", agent -> RepositoryEntry.formatTags(agent.repositoryTags()), 220));
        table.getColumns().add(textColumn("Branch prefix", AgentProfile::branchPrefix, 180));
        runs.addListener((ListChangeListener<AgentRun>) c -> table.refresh());

        TextField name = new TextField();
        name.setPromptText("Backend Agent");
        ComboBox<AgentRole> role = new ComboBox<>(FXCollections.observableArrayList(AgentRole.values()));
        role.getSelectionModel().select(AgentRole.BACKEND_ENGINEER);
        ComboBox<ProviderType> providerPick = new ComboBox<>(FXCollections.observableArrayList(ProviderType.values()));
        providerPick.getSelectionModel().select(ProviderType.CURSOR_CLOUD);
        TagPicker agentTags = new TagPicker(this::knownTags);
        repositories.addListener((ListChangeListener<RepositoryEntry>) change -> agentTags.refreshAvailable());

        Label branchPreview = new Label(AgentProfile.autoBranchPrefix(""));
        branchPreview.setStyle("-fx-text-fill: #666;");
        name.textProperty().addListener((obs, oldValue, newValue) ->
                branchPreview.setText(AgentProfile.autoBranchPrefix(newValue == null ? "" : newValue)));

        table.getColumns().add(agentActionsColumn(
                agent -> loadAgentIntoForm(agent, name, role, providerPick, agentTags, branchPreview),
                this::deleteAgent
        ));

        Button add = new Button("Create Agent");
        add.setOnAction(event -> {
            if (name.getText().isBlank()) {
                showError("Agent name is required.");
                return;
            }
            AgentProfile agent = AgentProfile.create(
                    name.getText(),
                    role.getValue(),
                    providerPick.getValue(),
                    "",
                    "",
                    "",
                    true,
                    agentTags.selected()
            );
            agents.add(agent);
            saveState();
            name.clear();
            agentTags.setSelected(List.of());
        });

        Button saveSelected = new Button("Save Selected Agent");
        saveSelected.setOnAction(event -> {
            AgentProfile selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select an agent first.");
                return;
            }
            if (name.getText().isBlank()) {
                showError("Agent name is required.");
                return;
            }
            replaceAgent(selected.withEditableFields(name.getText(), role.getValue(), providerPick.getValue(), agentTags.selected()));
            saveState();
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Name"), name, new Label("Role"), role);
        form.addRow(1, new Label("Provider"), providerPick);
        GridPane.setColumnSpan(providerPick, 3);
        form.addRow(2, new Label("Repository tags"), agentTags.node());
        GridPane.setColumnSpan(agentTags.node(), 3);
        form.addRow(3, new Label("Branch prefix (auto)"), branchPreview, add, saveSelected);

        autoSizeColumnsToContent(table);
        return padded(new VBox(10, table, new Separator(), form));
    }

    private VBox buildTasksPane() {
        TableView<AgentTask> table = new TableView<>(tasks);
        table.getColumns().add(textColumn("Key", AgentTask::taskKey, 110));
        table.getColumns().add(textColumn("Title", AgentTask::title, 240));
        table.getColumns().add(textColumn("Status", task -> task.status().name(), 120));
        table.getColumns().add(textColumn("Role", task -> roleForTaskPrefix(task.taskPrefix()).map(AgentRole::label).orElse("Any"), 150));
        table.getColumns().add(textColumn("Agent", task -> agentName(task.assignedAgentId()), 180));
        table.getColumns().add(textColumn("Tags", task -> RepositoryEntry.formatTags(task.repositoryTags()), 160));
        table.getColumns().add(textColumn("Started", task -> formatOptionalTime(task.startedAt()), 160));
        table.getColumns().add(textColumn("Ended", task -> formatOptionalTime(task.endedAt()), 160));

        ComboBox<String> prefix = new ComboBox<>(FXCollections.observableArrayList("BE-TASK", "FE-TASK", "QA-TASK", "REV-TASK", "DEVOPS-TASK"));
        prefix.setEditable(true);
        prefix.getSelectionModel().select("BE-TASK");

        TextField title = new TextField();
        title.setPromptText("Add TTL support to cache");
        TextArea description = new TextArea();
        description.setPromptText("Acceptance criteria, constraints, test expectations...");
        description.setPrefRowCount(5);

        TagSelectionControl tagPicker = new TaskRepositoryTagPicker(repositories);

        ComboBox<String> branchName = new ComboBox<>();
        branchName.setEditable(true);
        branchName.setPromptText("Choose branch...");
        branchName.setDisable(true);
        branchName.getItems().addAll("main");
        CheckBox specifyBranch = new CheckBox("Specify branch");
        Button refreshBranches = new Button("Refresh branches");
        refreshBranches.setDisable(true);
        refreshBranches.setOnAction(event -> refreshBranchOptions(branchName, tagPicker.selected()));
        specifyBranch.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            branchName.setDisable(!isSelected);
            refreshBranches.setDisable(!isSelected);
            if (isSelected) {
                refreshBranchOptions(branchName, tagPicker.selected());
            } else {
                branchName.getSelectionModel().clearSelection();
                branchName.getEditor().clear();
            }
        });

        ComboBox<AgentProfile> agentSelector = new ComboBox<>(agents);
        agentSelector.setConverter(agentConverter());
        CheckBox assignDeveloper = new CheckBox("Assign developer");
        agentSelector.setDisable(true);
        assignDeveloper.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            agentSelector.setDisable(!isSelected);
            if (!isSelected) {
                agentSelector.getSelectionModel().clearSelection();
            }
        });

        table.getColumns().add(taskActionsColumn(
                task -> loadTaskIntoForm(task, prefix, title, description, tagPicker, specifyBranch, branchName, assignDeveloper, agentSelector),
                this::deleteTask,
                this::startTask,
                t -> t.status() == TaskStatus.DRAFT && !hasActiveRunForTask(t.id())
        ));

        Button create = new Button("Create task");
        create.setOnAction(event -> {
            if (title.getText().isBlank()) {
                showError("Task title is required.");
                return;
            }
            String normalizedPrefix = normalizeTaskPrefix(prefix.getEditor().getText());
            int generatedNumber = nextTaskNumber(normalizedPrefix);
            UUID selectedAgentId;
            try {
                selectedAgentId = assignedAgentId(assignDeveloper, agentSelector);
            } catch (IllegalArgumentException e) {
                showError(e.getMessage());
                return;
            }
            AgentTask task = AgentTask.create(
                    normalizedPrefix,
                    generatedNumber,
                    title.getText(),
                    description.getText(),
                    "",
                    branchValue(specifyBranch, branchName),
                    selectedAgentId,
                    tagPicker.selected()
            );
            tasks.add(task);
            saveState();
            title.clear();
            description.clear();
            tagPicker.setSelected(List.of());
            branchName.getEditor().clear();
            specifyBranch.setSelected(false);
            assignDeveloper.setSelected(false);
            taskFormIsEditMode.set(false);
        });

        Button saveChanges = new Button("Save changes");
        saveChanges.setOnAction(event -> {
            AgentTask selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select a task first.");
                return;
            }
            if (!canEditTask(selected)) {
                showError("Only tasks that are not in progress can be edited.");
                return;
            }
            if (title.getText().isBlank()) {
                showError("Task title is required.");
                return;
            }
            UUID selectedAgentId;
            try {
                selectedAgentId = assignedAgentId(assignDeveloper, agentSelector);
            } catch (IllegalArgumentException e) {
                showError(e.getMessage());
                return;
            }
            AgentTask updated = selected.withEditableFields(
                    normalizeTaskPrefix(prefix.getEditor().getText()),
                    title.getText(),
                    description.getText(),
                    selected.repositoryUrl(),
                    branchValue(specifyBranch, branchName),
                    selectedAgentId,
                    tagPicker.selected()
            );
            replaceTask(updated);
            saveState();
        });

        Button start = new Button("Save&Start");
        start.setOnAction(event -> {
            AgentTask selected = table.getSelectionModel().getSelectedItem();
            AgentTask saved = saveTaskFromFormOrCreate(selected, prefix, title, description, tagPicker, specifyBranch, branchName, assignDeveloper, agentSelector);
            if (saved == null) {
                return;
            }
            startTask(saved);
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Prefix"), prefix, assignDeveloper, agentSelector);
        form.addRow(1, new Label("Title"), title, specifyBranch, branchName, refreshBranches);
        form.addRow(2, new Label("Repositories"), tagPicker.node());
        GridPane.setColumnSpan(tagPicker.node(), 4);
        Button importDescriptionMd = new Button("Import description from .md file…");
        importDescriptionMd.setOnAction(e -> importMarkdownAsTaskDescription(description));
        VBox descriptionBox = new VBox(6, importDescriptionMd, description);
        VBox.setVgrow(description, Priority.ALWAYS);
        form.add(new Label("Description"), 0, 3);
        form.add(descriptionBox, 1, 3, 4, 1);
        form.addRow(4, create, saveChanges, start);
        GridPane.setHgrow(title, Priority.ALWAYS);
        GridPane.setHgrow(descriptionBox, Priority.ALWAYS);

        Runnable refreshTaskFormActionButtons = () -> {
            boolean edit = taskFormIsEditMode.get();
            create.setVisible(!edit);
            create.setManaged(!edit);
            saveChanges.setVisible(edit);
            saveChanges.setManaged(edit);
            start.setVisible(edit);
            start.setManaged(edit);
        };
        taskFormIsEditMode.addListener((obs, was, isNow) -> refreshTaskFormActionButtons.run());
        refreshTaskFormActionButtons.run();

        autoSizeColumnsToContent(table);
        return padded(new VBox(10, table, new Separator(), form));
    }

    private static String branchValue(CheckBox specifyBranch, ComboBox<String> branchName) {
        if (!specifyBranch.isSelected()) {
            return "";
        }
        String value = branchName.getEditor().getText();
        if (value == null || value.isBlank()) {
            value = branchName.getValue();
        }
        return value == null ? "" : value.strip();
    }

    private UUID assignedAgentId(CheckBox assignDeveloper, ComboBox<AgentProfile> agentSelector) {
        if (!assignDeveloper.isSelected()) {
            return null;
        }
        AgentProfile selectedAgent = agentSelector.getValue();
        if (selectedAgent == null) {
            throw new IllegalArgumentException("Choose an agent or uncheck Assign developer.");
        }
        return selectedAgent.id();
    }

    private AgentTask saveTaskFromFormOrCreate(
            AgentTask selected,
            ComboBox<String> prefix,
            TextField title,
            TextArea description,
            TagSelectionControl tagPicker,
            CheckBox specifyBranch,
            ComboBox<String> branchName,
            CheckBox assignDeveloper,
            ComboBox<AgentProfile> agentSelector
    ) {
        if (title.getText().isBlank()) {
            showError("Task title is required.");
            return null;
        }
        UUID nextAssignedAgentId;
        try {
            nextAssignedAgentId = assignedAgentId(assignDeveloper, agentSelector);
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return null;
        }
        if (selected != null && !canEditTask(selected)) {
            showError("Only tasks that are not in progress can be edited.");
            return null;
        }
        String normalizedPrefix = normalizeTaskPrefix(prefix.getEditor().getText());
        AgentTask saved;
        if (selected == null) {
            saved = AgentTask.create(
                    normalizedPrefix,
                    nextTaskNumber(normalizedPrefix),
                    title.getText(),
                    description.getText(),
                    "",
                    branchValue(specifyBranch, branchName),
                    nextAssignedAgentId,
                    tagPicker.selected()
            );
            tasks.add(saved);
        } else {
            saved = selected.withEditableFields(
                    normalizedPrefix,
                    title.getText(),
                    description.getText(),
                    selected.repositoryUrl(),
                    branchValue(specifyBranch, branchName),
                    nextAssignedAgentId,
                    tagPicker.selected()
            );
            replaceTask(saved);
        }
        saveState();
        return saved;
    }

    private void refreshBranchOptions(ComboBox<String> branchName, List<String> selectedTags) {
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
            showError("Pick a repository tag that matches a repository before refreshing branches.");
            return;
        }
        String targetRepo = seedUrls.get(0);
        Optional<GitHubSession> sessionOpt = githubStore.loadSession();
        if (sessionOpt.isEmpty()) {
            showError("Sign in with GitHub (GitHub settings) to fetch branches from " + targetRepo + ".");
            return;
        }
        String token = sessionOpt.get().accessToken();
        CompletableFuture.runAsync(() -> {
            try {
                List<String> branches = githubReposClient.listBranches(token, targetRepo);
                Platform.runLater(() -> {
                    String current = branchName.getEditor().getText();
                    branchName.getItems().setAll(branches);
                    if (current != null && !current.isBlank()) {
                        branchName.getEditor().setText(current);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> showError("Could not load branches for " + targetRepo + ": " + ex.getMessage()));
            }
        });
    }

    private VBox buildRunsPane() {
        runsFiltered = new FilteredList<>(runs, r -> matchesRunsTaskStatusFilter(r));
        SortedList<AgentRun> runsSorted = new SortedList<>(runsFiltered);
        runsTable = new TableView<>(runsSorted);
        runsSorted.comparatorProperty().bind(runsTable.comparatorProperty());
        TableView<AgentRun> table = runsTable;
        TableColumn<AgentRun, Instant> startedAtCol = runStartedAtColumn();
        table.getColumns().add(startedAtCol);
        table.getColumns().add(runStatusColumn());
        table.getColumns().add(runProviderColumn());
        TableColumn<AgentRun, String> taskCol = textColumn("Task", run -> taskTitle(run.taskId()), 240);
        taskCol.setComparator(String.CASE_INSENSITIVE_ORDER);
        table.getColumns().add(taskCol);
        TableColumn<AgentRun, String> agentCol = textColumn("Agent", run -> agentName(run.agentProfileId()), 180);
        agentCol.setComparator(String.CASE_INSENSITIVE_ORDER);
        table.getColumns().add(agentCol);
        table.getColumns().add(runCursorInfoColumn());
        table.getColumns().add(pullRequestLinkColumn());

        table.setFixedCellSize(54);

        tasks.addListener((ListChangeListener<AgentTask>) c -> refreshRunsFilteredPredicate());

        runLogArea = new TextArea();
        runLogArea.setEditable(false);
        runLogArea.setPrefRowCount(12);
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldRun, newRun) -> onRunsTableSelectionChanged(newRun));

        Label pollHint = new Label(
                "Active runs (Creating / Running / Unknown) refresh automatically about every 8 seconds. "
                        + "Select a run in the table to show its log below; the log updates when that run is refreshed. "
                        + "For a run in ERROR or CANCELLED, use «Restart run» to queue the same task again with the same agent.");
        pollHint.setWrapText(true);
        pollHint.setStyle("-fx-text-fill: #555;");

        Button refresh = new Button("Refresh Status");
        refresh.setOnAction(event -> {
            AgentRun selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select a run first.");
                return;
            }
            refreshRun(selected);
        });

        Button cancel = new Button("Cancel Run");
        cancel.setOnAction(event -> {
            AgentRun selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select a run first.");
                return;
            }
            cancelRun(selected);
        });

        Button restartRun = new Button("Restart run");
        restartRun.setOnAction(event -> {
            AgentRun selected = table.getSelectionModel().getSelectedItem();
            restartFailedRun(selected);
        });

        ObservableList<String> statusOptions = FXCollections.observableArrayList("All");
        for (TaskStatus ts : TaskStatus.values()) {
            statusOptions.add(ts.name());
        }
        ComboBox<String> taskStatusFilterCombo = new ComboBox<>(statusOptions);
        taskStatusFilterCombo.setValue("All");
        taskStatusFilterCombo.setPrefWidth(200);
        taskStatusFilterCombo.setOnAction(e -> {
            String v = taskStatusFilterCombo.getValue();
            runsTaskStatusFilter = v == null || v.isBlank() ? "All" : v;
            refreshRunsFilteredPredicate();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topBar = new HBox(8, new Label("Task status:"), taskStatusFilterCombo, spacer, refresh, cancel, restartRun);
        topBar.setAlignment(Pos.CENTER_LEFT);

        table.getSortOrder().setAll(startedAtCol);
        startedAtCol.setSortType(TableColumn.SortType.DESCENDING);

        autoSizeColumnsToContent(table);
        return padded(new VBox(10, topBar, table, pollHint, new Label("Run log (selected run)"), runLogArea));
    }

    private boolean matchesRunsTaskStatusFilter(AgentRun r) {
        if ("All".equals(runsTaskStatusFilter)) {
            return true;
        }
        return findTask(r.taskId())
                .map(t -> t.status().name().equals(runsTaskStatusFilter))
                .orElse(false);
    }

    private void refreshRunsFilteredPredicate() {
        if (runsFiltered == null) {
            return;
        }
        Predicate<AgentRun> p = this::matchesRunsTaskStatusFilter;
        runsFiltered.setPredicate(null);
        runsFiltered.setPredicate(p);
    }

    private TableColumn<AgentRun, RunStatus> runStatusColumn() {
        TableColumn<AgentRun, RunStatus> col = new TableColumn<>("Status");
        col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().status()));
        col.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(RunStatus item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        col.setPrefWidth(110);
        return col;
    }

    private TableColumn<AgentRun, ProviderType> runProviderColumn() {
        TableColumn<AgentRun, ProviderType> col = new TableColumn<>("Provider");
        col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().provider()));
        col.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(ProviderType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });
        col.setPrefWidth(140);
        return col;
    }

    private TableColumn<AgentRun, Instant> runStartedAtColumn() {
        TableColumn<AgentRun, Instant> col = new TableColumn<>("Started");
        col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().startedAt()));
        col.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(Instant item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : formatOptionalTime(item));
            }
        });
        col.setPrefWidth(170);
        col.setComparator(Comparator.nullsLast(Comparator.naturalOrder()));
        return col;
    }

    private void startTask(AgentTask selected) {
        AgentTask working = selected;
        if (selected.assignedAgentId() == null) {
            Optional<RoleInference> inference = inferLaunchRoleForUnassignedTask(selected);
            if (inference.isPresent()) {
                RoleInference ri = inference.get();
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Confirm task type");
                confirm.setHeaderText("This task has no developer assigned.");
                confirm.setContentText(
                        ri.rationale()
                                + "\n\nSuggested role: " + ri.role().label()
                                + "\n\nAssign the first available agent with this role whose repository tags match the task, "
                                + "then start the Cursor run?");
                Optional<ButtonType> answer = confirm.showAndWait();
                if (answer.isEmpty() || answer.get() != ButtonType.OK) {
                    return;
                }
                Optional<AgentProfile> pick = firstFreeAgentForRoleAndTaskTags(ri.role(), working);
                if (pick.isEmpty()) {
                    showError("No available " + ri.role().label() + " matches this task's repository tags. "
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
                saveState();
            }
        }

        AgentProfile agent = resolveAgentForStart(working).orElse(null);
        if (agent == null) {
            showError("No available agent matches this task. Assign a developer, or create a free agent with a matching role and repository tag.");
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
        saveState();

        launchProviderRun(agent, queuedTask);
    }

    /**
     * Starts a provider run for an already-queued task (used by {@link #startTask} and {@link #restartFailedRun}).
     */
    private void launchProviderRun(AgentProfile agent, AgentTask queuedTask) {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        syncLocalAgentRuntimeSettings();
                        return providerRegistry.providerFor(agent.provider()).startTask(agent, queuedTask, currentSettings());
                    } catch (AgentProviderException e) {
                        throw new RuntimeException(e);
                    }
                })
                .whenComplete((run, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        replaceTask(queuedTask.withStatus(TaskStatus.FAILED));
                        Throwable cause = error.getCause();
                        LOG.log(Level.WARNING,
                                "startTask failed taskId=" + queuedTask.id() + " key=" + queuedTask.taskKey(),
                                cause != null ? cause : error);
                        showError(cause == null ? error.getMessage() : cause.getMessage());
                    } else {
                        runs.add(run);
                        replaceTask(queuedTask.withStatus(TaskStatus.RUNNING));
                    }
                    saveState();
                }));
    }

    /**
     * Re-queues the same task with the same agent as a failed/cancelled run and starts a new provider run.
     */
    private void restartFailedRun(AgentRun selected) {
        if (selected == null) {
            showError("Select a run first.");
            return;
        }
        if (selected.status() != RunStatus.ERROR && selected.status() != RunStatus.CANCELLED) {
            showError("Only runs in ERROR or CANCELLED state can be restarted.");
            return;
        }
        AgentTask task = findTask(selected.taskId()).orElse(null);
        if (task == null) {
            showError("Task for this run no longer exists.");
            return;
        }
        AgentProfile agent = findAgent(selected.agentProfileId()).orElse(null);
        if (agent == null) {
            showError("Agent profile for this run no longer exists.");
            return;
        }
        if (!isAgentFree(agent.id())) {
            showError("That agent already has an active run. Cancel or wait for it to finish before restarting.");
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
        saveState();
        launchProviderRun(agent, queuedTask);
    }

    private Optional<AgentProfile> resolveAgentForStart(AgentTask task) {
        Optional<AgentProfile> assigned = findAgent(task.assignedAgentId());
        if (assigned.isPresent()) {
            return assigned;
        }
        AgentRole desiredRole = roleForTaskPrefix(task.taskPrefix()).orElse(null);
        return agents.stream()
                .filter(agent -> desiredRole == null || agent.role() == desiredRole)
                .filter(agent -> isAgentFree(agent.id()))
                .filter(agent -> matchesRepositoryTags(agent, task.repositoryTags()))
                .findFirst();
    }

    /**
     * Suggests a role from the task prefix (BE/FE/…) or, if unknown, from repository-tag overlap with registered agents.
     */
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
        List<AgentProfile> overlap = agents.stream()
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
        return agents.stream()
                .filter(a -> a.role() == role)
                .filter(a -> isAgentFree(a.id()))
                .filter(a -> matchesRepositoryTags(a, task.repositoryTags()))
                .findFirst();
    }

    private boolean isAgentFree(UUID agentId) {
        return runs.stream()
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

    private static Optional<AgentRole> roleForTaskPrefix(String taskPrefix) {
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

    private void refreshRun(AgentRun selected) {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        syncLocalAgentRuntimeSettings();
                        return providerRegistry.providerFor(selected.provider()).refreshRun(selected, currentSettings());
                    } catch (AgentProviderException e) {
                        throw new RuntimeException(e);
                    }
                })
                .whenComplete((run, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        showError(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                        return;
                    }
                    applyRefreshSuccess(run);
                }));
    }

    private void cancelRun(AgentRun selected) {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        syncLocalAgentRuntimeSettings();
                        return providerRegistry.providerFor(selected.provider()).cancelRun(selected, currentSettings());
                    } catch (AgentProviderException e) {
                        throw new RuntimeException(e);
                    }
                })
                .whenComplete((run, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        showError(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                        return;
                    }
                    replaceRun(run);
                    updateTaskFromRun(run);
                    refreshRunLogIfThisRunSelected(run.id(), run);
                    saveState();
                }));
    }

    private void onRunsTableSelectionChanged(AgentRun selectedFromTable) {
        if (selectedFromTable == null) {
            renderRunLogs(null);
            return;
        }
        runs.stream()
                .filter(r -> r.id().equals(selectedFromTable.id()))
                .findFirst()
                .ifPresentOrElse(this::renderRunLogs, () -> renderRunLogs(selectedFromTable));
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

    /**
     * When Cursor finishes without a PR link, create one via GitHub (user must be signed in; OAuth scope {@code repo}).
     */
    private void maybeCreateGithubPullRequestIfNeeded(AgentRun run) {
        if (run.status() != RunStatus.FINISHED) {
            return;
        }
        if (run.pullRequestUrl() != null && !run.pullRequestUrl().isBlank()) {
            return;
        }
        if (run.provider() != ProviderType.CURSOR_CLOUD && run.provider() != ProviderType.OLLAMA
                && run.provider() != ProviderType.CLAUDE_API) {
            appendPrAutomationLog(run.id(),
                    "Skipped opening a PR here: only Cursor Cloud, Ollama, and Claude API runs use GitHub PR automation in this app.");
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
        }).whenComplete((prUrl, asyncError) -> Platform.runLater(() -> {
            githubFallbackPrInFlight.remove(runId);
            Optional<AgentRun> currentOpt = runs.stream().filter(r -> r.id().equals(runId)).findFirst();
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
                saveState();
                refreshRunLogIfThisRunSelected(runId, updated);
                return;
            }
            AgentRun updated = current.withStatus(RunStatus.FINISHED, current.resultSummary(), prUrl)
                    .appendLog("PR automation: Created pull request via GitHub API: " + prUrl);
            replaceRun(updated);
            updateTaskFromRun(updated);
            saveState();
            refreshRunLogIfThisRunSelected(runId, updated);
        }));
    }

    /**
     * Appends a user-visible line to the run log (Settings → run table → Run log). Deduplicates identical consecutive lines.
     */
    /**
     * Body for PRs created via GitHub API when Cursor did not return {@code pullRequestUrl}: agent outcome first,
     * original task text as context.
     */
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
        for (int i = 0; i < runs.size(); i++) {
            AgentRun r = runs.get(i);
            if (!r.id().equals(runId)) {
                continue;
            }
            List<RunLogEntry> logs = r.logs();
            if (!logs.isEmpty() && message.equals(logs.getLast().message())) {
                return;
            }
            AgentRun updated = r.appendLog(message);
            runs.set(i, updated);
            saveState();
            refreshRunLogIfThisRunSelected(runId, updated);
            return;
        }
    }

    private void refreshRunLogIfThisRunSelected(UUID runId, AgentRun updated) {
        if (runsTable == null) {
            return;
        }
        AgentRun selected = runsTable.getSelectionModel().getSelectedItem();
        if (selected != null && selected.id().equals(runId)) {
            renderRunLogs(updated);
        }
    }

    private boolean canEditTask(AgentTask task) {
        return task.status() == TaskStatus.DRAFT
                || task.status() == TaskStatus.FAILED
                || task.status() == TaskStatus.CANCELLED
                || task.status() == TaskStatus.DONE
                || task.status() == TaskStatus.WAITING_REVIEW
                || isOrphanedPreRunTask(task);
    }

    private void loadTaskIntoForm(
            AgentTask selected,
            ComboBox<String> prefix,
            TextField title,
            TextArea description,
            TagSelectionControl tagPicker,
            CheckBox specifyBranch,
            ComboBox<String> branchName,
            CheckBox assignDeveloper,
            ComboBox<AgentProfile> agentSelector
    ) {
        if (!canEditTask(selected)) {
            showError("Only tasks that are not in progress can be edited.");
            return;
        }
        prefix.getEditor().setText(selected.taskPrefix());
        title.setText(selected.title());
        description.setText(selected.description());
        tagPicker.setSelected(selected.repositoryTags());
        if (selected.startingRef() != null && !selected.startingRef().isBlank()) {
            specifyBranch.setSelected(true);
            branchName.getEditor().setText(selected.startingRef());
        } else {
            specifyBranch.setSelected(false);
            branchName.getEditor().clear();
        }
        Optional<AgentProfile> agent = findAgent(selected.assignedAgentId());
        assignDeveloper.setSelected(agent.isPresent());
        agent.ifPresent(agentSelector::setValue);
        if (agent.isEmpty()) {
            agentSelector.getSelectionModel().clearSelection();
        }
        taskFormIsEditMode.set(true);
    }

    private void loadAgentIntoForm(
            AgentProfile selected,
            TextField name,
            ComboBox<AgentRole> role,
            ComboBox<ProviderType> providerPick,
            TagPicker agentTags,
            Label branchPreview
    ) {
        name.setText(selected.name());
        role.getSelectionModel().select(selected.role());
        providerPick.getSelectionModel().select(selected.provider());
        agentTags.setSelected(selected.repositoryTags());
        branchPreview.setText(selected.branchPrefix());
    }

    private void deleteAgent(AgentProfile selected) {
        if (runs.stream().anyMatch(run -> run.agentProfileId().equals(selected.id()) && !run.status().terminal())) {
            showError("An agent with an active run cannot be deleted. Cancel or wait for the run first.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete agent");
        confirm.setHeaderText("Delete %s?".formatted(selected.name()));
        confirm.setContentText("Existing tasks assigned to this agent will become unassigned. Historical runs stay visible.");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }
        agents.removeIf(agent -> agent.id().equals(selected.id()));
        for (AgentTask task : new ArrayList<>(tasks)) {
            if (selected.id().equals(task.assignedAgentId())) {
                replaceTask(task.withEditableFields(
                        task.taskPrefix(),
                        task.title(),
                        task.description(),
                        task.repositoryUrl(),
                        task.startingRef(),
                        null,
                        task.repositoryTags()
                ));
            }
        }
        saveState();
    }

    private void deleteTask(AgentTask selected) {
        if (hasActiveRunForTask(selected.id())) {
            showError("A task with an active run cannot be deleted. Cancel the run first.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete task");
        confirm.setHeaderText("Delete %s?".formatted(selected.displayTitle()));
        confirm.setContentText("This will also remove local run records for this task. It will not delete remote Cursor runs, pushed branches, or PRs.");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }
        tasks.removeIf(task -> task.id().equals(selected.id()));
        runs.removeIf(run -> run.taskId().equals(selected.id()));
        saveState();
    }

    private boolean isOrphanedPreRunTask(AgentTask task) {
        return (task.status() == TaskStatus.RUNNING || task.status() == TaskStatus.QUEUED) && !hasRunForTask(task.id());
    }

    private boolean hasRunForTask(UUID taskId) {
        return runs.stream().anyMatch(run -> run.taskId().equals(taskId));
    }

    private boolean hasActiveRunForTask(UUID taskId) {
        return runs.stream()
                .filter(run -> run.taskId().equals(taskId))
                .anyMatch(run -> !run.status().terminal());
    }

    private void syncLocalAgentRuntimeSettings() {
        syncOllamaRuntimeSettings();
        syncClaudeRuntimeSettings();
    }

    private void syncClaudeRuntimeSettings() {
        providerRegistry.claudeApiProvider().setClaudeSettings(currentClaudeSettings());
        providerRegistry.claudeApiProvider().setRuntimeSettings(ollamaSettingsStore.load());
    }

    private ClaudeApiSettings currentClaudeSettings() {
        ClaudeApiSettings disk = claudeApiStore.load();
        String key = claudeSessionApiKey.isBlank() ? ClaudeApiSettings.defaults().apiKey() : claudeSessionApiKey;
        return new ClaudeApiSettings(key, disk.model(), disk.baseUrl());
    }

    private void syncOllamaRuntimeSettings() {
        providerRegistry.ollamaProvider().setRuntimeSettings(ollamaSettingsStore.load());
    }

    private AppSettings currentSettings() {
        String typedKey = cursorApiKeyField.getText() == null ? "" : cursorApiKeyField.getText();
        return AppSettings.fromEnvironment().withApiKey(typedKey).normalized();
    }

    private void renderRunLogs(AgentRun run) {
        if (run == null) {
            runLogArea.clear();
            return;
        }
        StringBuilder builder = new StringBuilder();
        List<RunLogEntry> sortedLogs = run.logs().stream()
                .sorted(Comparator.comparing(RunLogEntry::timestamp))
                .toList();
        for (RunLogEntry log : sortedLogs) {
            builder.append(TIME_FORMAT.format(log.timestamp())).append("  ").append(log.message()).append('\n');
        }
        if (run.resultSummary() != null && !run.resultSummary().isBlank()) {
            builder.append("\nResult:\n").append(run.resultSummary());
        }
        runLogArea.setText(builder.toString());
    }

    private Optional<AgentProfile> findAgent(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return agents.stream().filter(agent -> agent.id().equals(id)).findFirst();
    }

    private Optional<AgentTask> findTask(UUID id) {
        return tasks.stream().filter(task -> task.id().equals(id)).findFirst();
    }

    private void replaceTask(AgentTask updated) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(updated.id())) {
                tasks.set(i, updated);
                return;
            }
        }
    }

    private void replaceAgent(AgentProfile updated) {
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i).id().equals(updated.id())) {
                agents.set(i, updated);
                return;
            }
        }
    }

    private void replaceRun(AgentRun updated) {
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).id().equals(updated.id())) {
                runs.set(i, updated);
                return;
            }
        }
    }

    private String agentName(UUID id) {
        if (id == null) {
            return "Unassigned";
        }
        return findAgent(id).map(AgentProfile::name).orElse("Unknown agent");
    }

    private String taskTitle(UUID id) {
        return findTask(id).map(AgentTask::displayTitle).orElse("Unknown task");
    }

    private String nextTaskKey(String rawPrefix) {
        String prefix = normalizeTaskPrefix(rawPrefix);
        return "%s%02d".formatted(prefix, nextTaskNumber(prefix));
    }

    private int nextTaskNumber(String normalizedPrefix) {
        return tasks.stream()
                .filter(task -> normalizeTaskPrefix(task.taskPrefix()).equals(normalizedPrefix))
                .mapToInt(AgentTask::taskNumber)
                .max()
                .orElse(0) + 1;
    }

    private static String normalizeTaskPrefix(String rawPrefix) {
        if (rawPrefix == null || rawPrefix.isBlank()) {
            return "TASK";
        }
        return rawPrefix.strip().toUpperCase().replaceAll("[^A-Z0-9-]", "-");
    }

    private void saveState() {
        stateStore.save(new AppState(
                List.copyOf(agents),
                List.copyOf(tasks),
                List.copyOf(runs),
                List.copyOf(repositories)
        ));
    }

    /**
     * Resolves the repository URLs for a task: first by task tags (looked up in {@link #repositories}),
     * then falling back to the task's explicit URL field, then to the agent's. Duplicates are removed.
     */
    private List<String> resolveRepositoryUrlsForTask(AgentTask task, AgentProfile agent) {
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

    /**
     * Aggregated set of tags across all registered repositories, used to populate agent repository tags.
     */
    private List<String> knownTags() {
        java.util.TreeSet<String> all = new java.util.TreeSet<>();
        for (RepositoryEntry repo : repositories) {
            String tag = repo.tag();
            if (!tag.isBlank()) {
                all.add(tag);
            }
        }
        return new ArrayList<>(all);
    }

    private String formatGithubSessionStatusLine() {
        return githubStore.loadSession()
                .map(session -> "Signed in to GitHub as @" + session.login()
                        + " (scopes: " + session.scope() + ", saved " + TIME_FORMAT.format(session.savedAt()) + ")")
                .orElse("GitHub: not signed in.");
    }

    private void startGitHubDeviceFlow(String clientId) {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.setTitle("GitHub sign-in");

        Label headline = new Label("Connecting to GitHub…");
        TextField userCode = new TextField();
        userCode.setEditable(false);
        userCode.setFocusTraversable(true);
        userCode.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-alignment: center;");
        userCode.setPromptText("user code");
        Button copyCode = new Button("Copy code");
        copyCode.setDisable(true);
        copyCode.setOnAction(ev -> {
            String value = userCode.getText();
            if (value == null || value.isBlank()) {
                return;
            }
            Clipboard.getSystemClipboard().setContent(java.util.Map.of(DataFormat.PLAIN_TEXT, value));
            copyCode.setText("Copied");
            copyCode.setDisable(true);
            CompletableFuture.delayedExecutor(1200, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() ->
                    Platform.runLater(() -> {
                        copyCode.setText("Copy code");
                        copyCode.setDisable(false);
                    }));
        });
        HBox codeRow = new HBox(8, userCode, copyCode);
        HBox.setHgrow(userCode, Priority.ALWAYS);

        Label sub = new Label();
        sub.setWrapText(true);
        Button open = new Button("Open GitHub");
        open.setDisable(true);
        ProgressIndicator pi = new ProgressIndicator();

        VBox root = new VBox(12, headline, codeRow, sub, open, pi);
        root.setPadding(new Insets(16));
        dlg.setScene(new Scene(root, 460, 300));
        dlg.show();

        CompletableFuture.runAsync(() -> {
            try {
                GitHubDeviceFlowService.DeviceAuthorizationStart dev = githubDeviceFlow.requestDeviceCode(clientId);
                Platform.runLater(() -> {
                    headline.setText("Enter this code on GitHub:");
                    userCode.setText(dev.userCode());
                    userCode.selectAll();
                    copyCode.setDisable(false);
                    sub.setText("Then authorize this application at " + dev.verificationUri() + ".");
                    open.setDisable(false);
                    open.setOnAction(ev -> browseUri(dev.verificationUri()));
                });
                GitHubSession session = githubDeviceFlow.pollUntilAuthorized(clientId, dev);
                Platform.runLater(() -> {
                    githubStore.saveSession(session);
                    dlg.close();
                    LOG.info("GitHub sign-in completed for user @" + session.login());
                    showInfo("Signed in to GitHub as @" + session.login() + ". Importing your repositories…");
                });
                importGithubReposIntoTable(session.accessToken(), false);
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    dlg.close();
                    showError(ex.getMessage());
                });
            }
        });
    }

    /**
     * Pulls the user's repositories from the GitHub REST API and adds any that are not already in the registry.
     * Existing entries (including their tags) are preserved. Runs on a background thread.
     *
     * @param showSummary when {@code true}, surface a popup with the count; sign-in flow uses {@code false} for a quiet
     *                    import behind the success dialog.
     */
    private void importGithubReposIntoTable(String accessToken, boolean showSummary) {
        CompletableFuture.runAsync(() -> {
            try {
                List<String> urls = githubReposClient.listHttpsRepoUrls(accessToken);
                Platform.runLater(() -> {
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
                    saveState();
                    if (showSummary) {
                        showInfo("GitHub repositories imported. Added: " + added
                                + ", already present: " + (urls.size() - added) + ".");
                    } else if (added > 0) {
                        LOG.info("Imported " + added + " new repositories from GitHub after sign-in.");
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> showError("Could not import GitHub repositories: " + ex.getMessage()));
            }
        });
    }

    private static void browseUri(String uri) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(URI.create(uri));
            }
        } catch (Exception ignored) {
        }
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("AI Team Console");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showRepoListDialog(String title, List<String> repos) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("AI Team Console");
        alert.setHeaderText(title);
        alert.setContentText(repos.isEmpty() ? "(no repositories)" : repos.size() + " HTTPS URLs:");
        if (!repos.isEmpty()) {
            TextArea area = new TextArea(String.join("\n", repos));
            area.setEditable(false);
            area.setWrapText(false);
            area.setPrefRowCount(16);
            ScrollPane scroll = new ScrollPane(area);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(300);
            alert.getDialogPane().setExpandableContent(scroll);
            alert.getDialogPane().setExpanded(true);
        }
        alert.getDialogPane().setPrefWidth(620);
        alert.showAndWait();
    }

    /**
     * Loads UTF-8 Markdown from disk into the task description field (create or edit).
     */
    private void importMarkdownAsTaskDescription(TextArea description) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Markdown file");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown", "*.md", "*.markdown"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(primaryStage);
        if (file == null) {
            return;
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            description.setText(text);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to read markdown for task description", ex);
            showError("Could not read file: " + ex.getMessage());
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("AI Team Console");
        alert.setHeaderText("Action failed");
        String full = message == null ? "" : message;
        if (full.length() > 480) {
            alert.setContentText(full.substring(0, 480) + "…\n\nFull text is in «Details» below.");
            TextArea area = new TextArea(full);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(18);
            ScrollPane scroll = new ScrollPane(area);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(280);
            alert.getDialogPane().setExpandableContent(scroll);
            alert.getDialogPane().setExpanded(true);
        } else {
            alert.setContentText(full);
        }
        alert.getDialogPane().setPrefWidth(560);
        alert.showAndWait();
    }

    private void openExternalUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String u = url.strip();
        try {
            getHostServices().showDocument(u);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not open URL: " + u, e);
            showError("Could not open URL: " + e.getMessage());
        }
    }

    private static String runCursorInfoSummary(AgentRun run) {
        String agent = run.externalAgentId() == null ? "" : run.externalAgentId().strip();
        String runId = run.externalRunId() == null ? "" : run.externalRunId().strip();
        return "Cursor Agent: %s%nCursor Run: %s".formatted(
                agent.isEmpty() ? "—" : agent,
                runId.isEmpty() ? "—" : runId
        );
    }

    private TableColumn<AgentRun, String> runCursorInfoColumn() {
        TableColumn<AgentRun, String> col = new TableColumn<>("Info");
        col.setCellValueFactory(cd -> new SimpleStringProperty(runCursorInfoSummary(cd.getValue())));
        col.setPrefWidth(280);
        col.setMinWidth(200);
        col.setUserData("fixed-width");
        col.setCellFactory(ignored -> new TableCell<>() {
            private final Label label = new Label();

            {
                label.setWrapText(true);
                label.setStyle("-fx-font-family: monospace;");
                label.setMaxWidth(360);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                label.setText(item);
                setGraphic(label);
            }
        });
        col.setComparator(String.CASE_INSENSITIVE_ORDER);
        return col;
    }

    private TableColumn<AgentRun, String> pullRequestLinkColumn() {
        TableColumn<AgentRun, String> col = new TableColumn<>("PR");
        col.setCellValueFactory(cd -> {
            String url = cd.getValue().pullRequestUrl();
            return new SimpleStringProperty(url == null ? "" : url);
        });
        col.setPrefWidth(130);
        col.setMinWidth(100);
        col.setUserData("fixed-width");
        col.setCellFactory(ignored -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink("Open PR");

            {
                link.setOnAction(e -> openExternalUrl(getItem()));
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                if (item == null || item.isBlank()) {
                    Label dash = new Label("—");
                    dash.setStyle("-fx-text-fill: #888;");
                    setGraphic(dash);
                    return;
                }
                link.setTooltip(new Tooltip(item));
                setGraphic(link);
            }
        });
        col.setComparator(Comparator.comparing((String u) -> u == null || u.isBlank()).thenComparing(String.CASE_INSENSITIVE_ORDER));
        return col;
    }

    private static <T> TableColumn<T, String> textColumn(String title, java.util.function.Function<T, String> extractor, int width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(value -> new SimpleStringProperty(extractor.apply(value.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static String formatOptionalTime(java.time.Instant instant) {
        return instant == null ? "—" : TIME_FORMAT.format(instant);
    }

    /**
     * Sizes columns to the wider of (header text, widest cell text), with padding. Recomputes when items change.
     * Capped so a single long row cannot blow the table layout.
     */
    private static <T> void autoSizeColumnsToContent(TableView<T> table) {
        Runnable resize = () -> {
            for (TableColumn<T, ?> column : table.getColumns()) {
                if ("fixed-width".equals(column.getUserData())) {
                    continue;
                }
                double max = textWidth(column.getText()) + 28;
                for (T row : table.getItems()) {
                    Object cellValue = column.getCellData(row);
                    String text = cellValue == null ? "" : cellValue.toString();
                    max = Math.max(max, textWidth(text) + 28);
                }
                double clamped = Math.min(Math.max(max, 60), 520);
                column.setPrefWidth(clamped);
            }
        };
        table.getItems().addListener((ListChangeListener<T>) change -> resize.run());
        resize.run();
    }

    private static double textWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return new Text(text).getLayoutBounds().getWidth();
    }

    private static TableColumn<AgentTask, Void> taskActionsColumn(
            Consumer<AgentTask> editAction,
            Consumer<AgentTask> deleteAction,
            Consumer<AgentTask> runAction,
            Predicate<AgentTask> draftRunEnabled
    ) {
        TableColumn<AgentTask, Void> column = new TableColumn<>("Actions");
        column.setMinWidth(248);
        column.setPrefWidth(260);
        column.setUserData("fixed-width");
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Button run = new Button("Run");
            private final Button edit = new Button("Edit");
            private final Button delete = new Button("Delete");
            private final HBox buttons;

            {
                run.setMinWidth(48);
                edit.setMinWidth(56);
                delete.setMinWidth(64);
                buttons = new HBox(6, run, edit, delete);
                run.setOnAction(event -> runAction.accept(getTableView().getItems().get(getIndex())));
                edit.setOnAction(event -> editAction.accept(getTableView().getItems().get(getIndex())));
                delete.setOnAction(event -> deleteAction.accept(getTableView().getItems().get(getIndex())));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                AgentTask task = getTableView().getItems().get(getIndex());
                boolean draft = task.status() == TaskStatus.DRAFT;
                run.setVisible(draft);
                run.setManaged(draft);
                run.setDisable(!draftRunEnabled.test(task));
                setGraphic(buttons);
            }
        });
        return column;
    }

    private static TableColumn<AgentProfile, Void> agentActionsColumn(Consumer<AgentProfile> editAction, Consumer<AgentProfile> deleteAction) {
        TableColumn<AgentProfile, Void> column = new TableColumn<>("Actions");
        column.setMinWidth(170);
        column.setPrefWidth(170);
        column.setUserData("fixed-width");
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Button edit = new Button("Edit");
            private final Button delete = new Button("Delete");
            private final HBox buttons;

            {
                edit.setMinWidth(64);
                delete.setMinWidth(72);
                buttons = new HBox(6, edit, delete);
                edit.setOnAction(event -> editAction.accept(getTableView().getItems().get(getIndex())));
                delete.setOnAction(event -> deleteAction.accept(getTableView().getItems().get(getIndex())));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : buttons);
            }
        });
        return column;
    }

    private static VBox padded(VBox content) {
        content.setPadding(new Insets(12));
        VBox.setVgrow(content.getChildren().getFirst(), Priority.ALWAYS);
        return content;
    }

    private static StringConverter<AgentProfile> agentConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(AgentProfile agent) {
                return agent == null ? "" : "%s (%s)".formatted(agent.name(), agent.role().label());
            }

            @Override
            public AgentProfile fromString(String string) {
                return null;
            }
        };
    }
}
