package com.example.aiteamconsole.ui.settings;

import com.example.aiteamconsole.AgentProviderException;
import com.example.aiteamconsole.AppLogging;
import com.example.aiteamconsole.CursorCloudAgentProvider;
import com.example.aiteamconsole.GitHubOAuthAppSettings;
import com.example.aiteamconsole.GitHubRepoUrls;
import com.example.aiteamconsole.OllamaRuntimeSettings;
import com.example.aiteamconsole.RepositoryEntry;
import com.example.aiteamconsole.ui.FxTableHelpers;
import com.example.aiteamconsole.ui.MainViewModel;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Modal settings windows (Cursor / Ollama / GitHub) extracted from the bootstrap {@link com.example.aiteamconsole.AiTeamConsoleApplication}.
 */
public final class ConsoleSettingsWindows {

    private ConsoleSettingsWindows() {
    }

    public static void openCursor(MainViewModel main, Stage owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.setTitle("Cursor settings");

        SettingsViewModel settings = main.settings;
        PasswordField cursorApiKeyField = new PasswordField();
        cursorApiKeyField.setPromptText("cursor_...");
        cursorApiKeyField.textProperty().bindBidirectional(settings.cursorApiKey);

        Button saveCursorApiKey = new Button("Save API key");
        saveCursorApiKey.setOnAction(event -> {
            String key = settings.cursorApiKey.get() == null ? "" : settings.cursorApiKey.get().strip();
            if (key.isBlank()) {
                main.dialogs().showError("Enter a Cursor API key first.");
                return;
            }
            settings.saveCursorApiKeyToDisk();
            main.dialogs().showInfo("Saved Cursor API key to %s. Use \"Forget\" to delete it.".formatted(settings.cursorApiKeyFilePath()));
        });
        Button forgetCursorApiKey = new Button("Forget");
        forgetCursorApiKey.setOnAction(event -> {
            settings.forgetCursorApiKeyOnDisk();
            main.dialogs().showInfo("Removed local Cursor API key file (env CURSOR_API_KEY still applies if set).");
        });

        HBox apiKeyRow = new HBox(8, new Label("Cursor API key"), cursorApiKeyField, saveCursorApiKey, forgetCursorApiKey);
        HBox.setHgrow(cursorApiKeyField, Priority.ALWAYS);

        TextField verifyRepoUrl = new TextField();
        verifyRepoUrl.setPromptText("Optional: repo URL to check (else first agent repo)");
        Button verifyRepo = new Button("Verify repo access");
        verifyRepo.setOnAction(event -> {
            String url = verifyRepoUrl.getText().strip();
            if (url.isBlank()) {
                url = main.repositories.stream()
                        .map(RepositoryEntry::url)
                        .filter(u -> u != null && !u.isBlank())
                        .findFirst()
                        .orElse("");
            }
            if (url.isBlank()) {
                main.dialogs().showError("Enter a repository URL or add a repository in GitHub settings.");
                return;
            }
            String urlToVerify = url;
            CompletableFuture.runAsync(() -> {
                try {
                    List<String> listed = main.providerRegistry().cursorCloudProvider().listAccessibleRepositoryUrls(settings.toAppSettings());
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
                    Platform.runLater(() -> main.dialogs().showError(e.getMessage()));
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
                "State file: %s. API keys are not stored there.".formatted(main.stateStore().stateFile()));
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

        dlg.setScene(new Scene(content, 720, 500));
        dlg.show();
    }

    public static void openOllama(MainViewModel main, Stage owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.setTitle("Ollama settings");

        OllamaRuntimeSettings.Store store = main.ollamaSettingsStore();
        OllamaRuntimeSettings cur = store.load();
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
                store.save(next);
                main.settings.ollamaBaseUrl.set(next.ollamaBaseUrl());
                main.settings.ollamaModel.set(next.ollamaModel());
                main.dialogs().showInfo("Saved Ollama settings to %s.".formatted(store.filePath()));
                dlg.close();
            } catch (NumberFormatException ex) {
                main.dialogs().showError("Embedding dimensions / RAG numbers must be integers.");
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

    public static void openGitHub(MainViewModel main, Stage owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.setTitle("GitHub settings");

        VBox content = new VBox(12, buildGithubBlock(main, owner), new Separator(), buildRepositoriesBlock(main));
        content.setPadding(new Insets(16));
        VBox.setVgrow(content, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);

        dlg.setScene(new Scene(scroll, 900, 640));
        dlg.show();
    }

    private static VBox buildGithubBlock(MainViewModel main, Stage owner) {
        SettingsViewModel settings = main.settings;
        GitHubOAuthAppSettings ghStored = main.githubStore().loadOAuthAppSettings();
        String ghClientFromEnv = System.getenv().getOrDefault("GITHUB_OAUTH_CLIENT_ID", "");
        TextField githubClientIdField = new TextField();
        githubClientIdField.setPromptText("OAuth App Client ID (enable Device flow on GitHub)");
        githubClientIdField.setText(!ghStored.clientId().isBlank() ? ghStored.clientId() : ghClientFromEnv);

        Button saveGhClientId = new Button("Save Client ID");
        saveGhClientId.setOnAction(event -> {
            main.githubStore().saveOAuthAppSettings(new GitHubOAuthAppSettings(githubClientIdField.getText()));
            main.dialogs().showInfo("Saved GitHub OAuth Client ID under your profile folder (this is public; not the client secret).");
        });

        Label localStatus = new Label();
        localStatus.setWrapText(true);
        Runnable refreshStatus = () -> localStatus.setText(formatGithubSessionStatusLine(main));
        refreshStatus.run();
        settings.githubSession.addListener((obs, o, n) -> refreshStatus.run());

        Button ghSignIn = new Button("Sign in with GitHub");
        ghSignIn.setOnAction(event -> {
            String cid = githubClientIdField.getText().strip();
            if (cid.isBlank()) {
                main.dialogs().showError("Enter a GitHub OAuth App Client ID (Developer settings → OAuth Apps), or set GITHUB_OAUTH_CLIENT_ID.");
                return;
            }
            main.githubStore().saveOAuthAppSettings(new GitHubOAuthAppSettings(cid));
            openGitHubDeviceFlowDialog(main, owner, cid);
        });

        Button ghSignOut = new Button("Sign out GitHub");
        ghSignOut.setOnAction(event -> {
            settings.signOutGitHub();
            refreshStatus.run();
        });

        Button reimport = new Button("Import my repositories now");
        reimport.setOnAction(event -> main.githubStore().loadSession().ifPresentOrElse(
                session -> main.importGithubReposIntoTable(session.accessToken(), true),
                () -> main.dialogs().showError("Sign in with GitHub first.")
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

    private static String formatGithubSessionStatusLine(MainViewModel main) {
        return main.githubStore().loadSession()
                .map(session -> "Signed in to GitHub as @" + session.login()
                        + " (scopes: " + session.scope() + ", saved " + FxTableHelpers.formatOptionalTime(session.savedAt()) + ")")
                .orElse("GitHub: not signed in.");
    }

    private static void openGitHubDeviceFlowDialog(MainViewModel main, Stage owner, String clientId) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
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
            CompletableFuture.delayedExecutor(1200, TimeUnit.MILLISECONDS).execute(() ->
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

        SettingsViewModel settings = main.settings;
        settings.authenticateGitHub(main.githubDeviceFlowService(), clientId, new SettingsViewModel.GitHubAuthProgress() {
            @Override
            public void deviceCodeReady(String code, String verificationUri) {
                headline.setText("Enter this code on GitHub:");
                userCode.setText(code);
                userCode.selectAll();
                copyCode.setDisable(false);
                sub.setText("Then authorize this application at " + verificationUri + ".");
                open.setDisable(false);
                open.setOnAction(ev -> browseUri(verificationUri, main));
            }

            @Override
            public void authorized(com.example.aiteamconsole.GitHubSession session) {
                dlg.close();
                main.dialogs().showInfo("Signed in to GitHub as @" + session.login() + ". Importing your repositories…");
            }

            @Override
            public void failed(String message) {
                dlg.close();
                main.dialogs().showError(message);
            }
        });
    }

    private static void browseUri(String uri, MainViewModel main) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(URI.create(uri));
            }
        } catch (Exception ignored) {
            main.openUrl(uri);
        }
    }

    private static VBox buildRepositoriesBlock(MainViewModel main) {
        TableView<RepositoryEntry> table = new TableView<>(main.repositories);
        table.setPrefHeight(160);
        table.getColumns().add(FxTableHelpers.textColumn("Name / tag", RepositoryEntry::label, 180));
        table.getColumns().add(FxTableHelpers.textColumn("URL", RepositoryEntry::url, 320));
        table.getColumns().add(FxTableHelpers.textColumn("Default branch", RepositoryEntry::defaultBranch, 140));

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
                main.dialogs().showError("Repository URL is required.");
                return;
            }
            RepositoryEntry entry = RepositoryEntry.create(url, displayField.getText(), defaultBranchField.getText());
            if (entry.url().isBlank()) {
                main.dialogs().showError("Could not parse a GitHub HTTPS URL from: " + url);
                return;
            }
            main.repositories.add(entry);
            main.save();
            urlField.clear();
            displayField.clear();
            defaultBranchField.setText("main");
        });

        Button save = new Button("Save selected");
        save.setOnAction(event -> {
            RepositoryEntry selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                main.dialogs().showError("Select a repository in the table first.");
                return;
            }
            RepositoryEntry updated = selected.withFields(urlField.getText(), displayField.getText(), defaultBranchField.getText());
            replaceRepository(main, updated);
            main.save();
        });

        Button remove = new Button("Remove selected");
        remove.setOnAction(event -> {
            RepositoryEntry selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                main.dialogs().showError("Select a repository in the table first.");
                return;
            }
            main.repositories.removeIf(r -> r.id().equals(selected.id()));
            main.save();
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

        FxTableHelpers.autoSizeColumnsToContent(table);
        Label intro = new Label("Repositories. The Name field is the repository tag used by agents and tasks. "
                + "Default branch is main unless changed.");
        intro.setWrapText(true);
        return new VBox(6, intro, table, fieldsRow, btnRow);
    }

    private static void replaceRepository(MainViewModel main, RepositoryEntry updated) {
        for (int i = 0; i < main.repositories.size(); i++) {
            if (main.repositories.get(i).id().equals(updated.id())) {
                main.repositories.set(i, updated);
                return;
            }
        }
    }
}
