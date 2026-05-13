package com.example.aiteamconsole;

import com.example.aiteamconsole.pixel.PixelOfficeStage;
import com.example.aiteamconsole.ui.AlertUserDialogs;
import com.example.aiteamconsole.ui.MainViewModel;
import com.example.aiteamconsole.ui.UiEnvironment;
import com.example.aiteamconsole.ui.agents.AgentsView;
import com.example.aiteamconsole.ui.runs.RunsView;
import com.example.aiteamconsole.ui.settings.ConsoleSettingsWindows;
import com.example.aiteamconsole.ui.tasks.TasksView;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class AiTeamConsoleApplication extends Application {

    private Timeline poller;
    private Stage primaryStage;
    private PixelOfficeStage pixelOfficeStage;

    public static void main(String[] args) {
        AppLogging.init();
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        primaryStage = stage;

        StateStore store = StateStore.defaultStore();
        ProviderRegistry registry = new ProviderRegistry();
        GitHubDeviceFlowService gitHubDeviceFlow = new GitHubDeviceFlowService();

        MainViewModel mainVm = new MainViewModel(store, registry, gitHubDeviceFlow);
        mainVm.attachUi(new UiEnvironment(
                Platform::runLater,
                new AlertUserDialogs(),
                url -> openExternalUrl(url == null ? null : url.strip()),
                () -> stage
        ));
        mainVm.initialize();

        TabPane tabs = new TabPane(
                tab("Agents", new AgentsView(mainVm.agents).buildView()),
                tab("Tasks", new TasksView(mainVm.tasks).buildView()),
                tab("Runs", new RunsView(mainVm.runs).buildView())
        );
        tabs.getTabs().forEach(t -> t.setClosable(false));

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setTop(buildToolbar(mainVm));
        root.setCenter(tabs);

        poller = new Timeline(new KeyFrame(Duration.seconds(8), e -> mainVm.pollActiveRuns()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();

        stage.setScene(new Scene(root, 1120, 760));
        stage.setTitle("AI Team Console");
        stage.show();
    }

    private void openExternalUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            getHostServices().showDocument(url);
        } catch (Exception e) {
            AppLogging.get(AiTeamConsoleApplication.class).warning("Could not open URL: " + url + " — " + e.getMessage());
            Platform.runLater(() -> new AlertUserDialogs().showError("Could not open URL: " + e.getMessage()));
        }
    }

    @Override
    public void stop() throws Exception {
        if (poller != null) {
            poller.stop();
        }
        super.stop();
    }

    private static Tab tab(String label, Node content) {
        Tab t = new Tab(label, content);
        return t;
    }

    private HBox buildToolbar(MainViewModel mainVm) {
        Button cursorSettingsBtn = new Button("Cursor settings…");
        cursorSettingsBtn.setOnAction(event -> ConsoleSettingsWindows.openCursor(mainVm, primaryStage));

        Button ollamaSettingsBtn = new Button("Ollama settings…");
        ollamaSettingsBtn.setOnAction(event -> ConsoleSettingsWindows.openOllama(mainVm, primaryStage));

        Button githubSettingsBtn = new Button("GitHub settings…");
        githubSettingsBtn.setOnAction(event -> ConsoleSettingsWindows.openGitHub(mainVm, primaryStage));

        Button pixelOfficeBtn = new Button("Pixel Office…");
        pixelOfficeBtn.setOnAction(event -> openPixelOfficeWindow(mainVm));

        return new HBox(8, cursorSettingsBtn, ollamaSettingsBtn, githubSettingsBtn, pixelOfficeBtn);
    }

    private void openPixelOfficeWindow(MainViewModel mainVm) {
        if (pixelOfficeStage != null && pixelOfficeStage.isShowing()) {
            pixelOfficeStage.toFront();
            return;
        }
        pixelOfficeStage = new PixelOfficeStage(
                primaryStage, mainVm.agentProfiles, mainVm.agentTasks, mainVm.agentRuns
        );
        pixelOfficeStage.show();
    }
}
