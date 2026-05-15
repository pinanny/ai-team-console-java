package com.example.aiteamconsole.ui.settings;

import com.example.aiteamconsole.AppLogging;
import com.example.aiteamconsole.AppSettings;
import com.example.aiteamconsole.CursorApiCredentials;
import com.example.aiteamconsole.CursorApiStore;
import com.example.aiteamconsole.GitHubDeviceFlowService;
import com.example.aiteamconsole.GitHubJsonStore;
import com.example.aiteamconsole.GitHubOAuthAppSettings;
import com.example.aiteamconsole.GitHubSession;
import com.example.aiteamconsole.OllamaRuntimeSettings;
import com.example.aiteamconsole.ui.MainViewModel;
import com.example.aiteamconsole.ui.UiEnvironment;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class SettingsViewModel {

    private static final Logger LOG = AppLogging.get(SettingsViewModel.class);

    private final MainViewModel main;
    private final CursorApiStore cursorApiStore;
    private final GitHubJsonStore githubStore;
    private final UiEnvironment ui;

    public final StringProperty cursorApiKey = new SimpleStringProperty("");
    public final StringProperty cursorBaseUrl = new SimpleStringProperty("https://api.cursor.com");
    public final StringProperty ollamaBaseUrl = new SimpleStringProperty("http://localhost:11434");
    public final StringProperty ollamaModel = new SimpleStringProperty("llama3.2:3b");
    public final ObjectProperty<GitHubSession> githubSession = new SimpleObjectProperty<>();

    public SettingsViewModel(
            MainViewModel main,
            CursorApiStore cursorApiStore,
            GitHubJsonStore githubStore,
            UiEnvironment ui
    ) {
        this.main = main;
        this.cursorApiStore = cursorApiStore;
        this.githubStore = githubStore;
        this.ui = ui;
        reloadCursorFieldsFromStore();
        reloadGithubSessionFromStore();
        OllamaRuntimeSettings cur = OllamaRuntimeSettings.Store.defaultStore().load();
        ollamaBaseUrl.set(cur.ollamaBaseUrl());
        ollamaModel.set(cur.ollamaModel());
    }

    public void reloadCursorFieldsFromStore() {
        AppSettings defaults = AppSettings.fromEnvironment();
        CursorApiCredentials storedCursorApi = cursorApiStore.load();
        cursorApiKey.set(!storedCursorApi.apiKey().isBlank() ? storedCursorApi.apiKey() : defaults.cursorApiKey());
        cursorBaseUrl.set(defaults.cursorBaseUrl());
    }

    public void reloadGithubSessionFromStore() {
        githubSession.set(githubStore.loadSession().orElse(null));
    }

    public AppSettings toAppSettings() {
        String key = cursorApiKey.get() == null ? "" : cursorApiKey.get();
        String base = cursorBaseUrl.get() == null || cursorBaseUrl.get().isBlank()
                ? AppSettings.fromEnvironment().cursorBaseUrl()
                : cursorBaseUrl.get().strip();
        return new AppSettings(key, base).normalized();
    }

    public void saveCursorApiKeyToDisk() {
        String key = cursorApiKey.get() == null ? "" : cursorApiKey.get().strip();
        cursorApiStore.save(new CursorApiCredentials(key));
    }

    public void forgetCursorApiKeyOnDisk() {
        cursorApiStore.clear();
        cursorApiKey.set("");
    }

    public String cursorApiKeyFilePath() {
        return cursorApiStore.filePath().toString();
    }

    public void authenticateGitHub(GitHubDeviceFlowService service, String oauthClientId, GitHubAuthProgress progress) {
        String cid = oauthClientId == null ? "" : oauthClientId.strip();
        if (cid.isBlank()) {
            ui.dialogs().showError(
                    "Enter a GitHub OAuth App Client ID (Developer settings → OAuth Apps), or set GITHUB_OAUTH_CLIENT_ID.");
            return;
        }
        githubStore.saveOAuthAppSettings(new GitHubOAuthAppSettings(cid));
        CompletableFuture.runAsync(() -> {
            try {
                GitHubDeviceFlowService.DeviceAuthorizationStart dev = service.requestDeviceCode(cid);
                ui.fxRunner().accept(() -> progress.deviceCodeReady(dev.userCode(), dev.verificationUri()));
                GitHubSession session = service.pollUntilAuthorized(cid, dev);
                githubStore.saveSession(session);
                ui.fxRunner().accept(() -> {
                    githubSession.set(session);
                    LOG.info("GitHub sign-in completed for user @" + session.login());
                    progress.authorized(session);
                    main.schedulePullMergeLabelRefreshIfIdle();
                });
                main.importGithubReposIntoTable(session.accessToken(), false);
            } catch (Exception ex) {
                ui.fxRunner().accept(() -> progress.failed(ex.getMessage()));
            }
        });
    }

    public void signOutGitHub() {
        githubStore.clearSession();
        githubSession.set(null);
        main.onGithubSessionClearedForPullMergeLabels();
        LOG.info("GitHub session cleared for this OS user");
    }

    /** Progress callbacks for a GitHub device-flow attempt (implemented by the dialog view). */
    public interface GitHubAuthProgress {
        void deviceCodeReady(String userCode, String verificationUri);

        void authorized(GitHubSession session);

        void failed(String message);
    }
}
