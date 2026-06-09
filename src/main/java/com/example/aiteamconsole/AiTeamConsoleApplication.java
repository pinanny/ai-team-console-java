package com.example.aiteamconsole;

import com.example.aiteamconsole.pixel.PixelOfficeStage;
import com.example.aiteamconsole.ui.AlertUserDialogs;
import com.example.aiteamconsole.ui.DomainMemoryView;
import com.example.aiteamconsole.ui.MainViewModel;
import com.example.aiteamconsole.ui.UiEnvironment;
import com.example.aiteamconsole.ui.agents.AgentsView;
import com.example.aiteamconsole.ui.runs.RunsView;
import com.example.aiteamconsole.ui.settings.ConsoleSettingsWindows;
import com.example.aiteamconsole.ui.tasks.TasksView;

import com.example.aiteamconsole.ui.WorkspaceManagerDialog;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AiTeamConsoleApplication extends Application {

    private Timeline poller;
    private Stage primaryStage;
    private PixelOfficeStage pixelOfficeStage;
    private boolean workspaceSelectorSync;

    private record WorkspacePick(UUID id, String label) {
    }

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
                tab("Runs", new RunsView(mainVm.runs).buildView()),
                tab("Domain memory", new DomainMemoryView(mainVm).buildView())
        );
        tabs.getTabs().forEach(t -> t.setClosable(false));

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        Node toolbar = buildToolbar(mainVm);
        BorderPane.setMargin(toolbar, new Insets(0, 0, 12, 0));
        root.setTop(toolbar);
        root.setCenter(tabs);

        poller = new Timeline(new KeyFrame(Duration.seconds(8), e -> mainVm.pollActiveRuns()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();

        stage.setScene(new Scene(root, 1120, 760));
        stage.setTitle("AI Team Console");
        stage.show();
        Platform.runLater(() -> mainVm.runConnectivityChecksAsync(true));
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
        Label wsLabel = new Label("Workspace:");
        ComboBox<WorkspacePick> wsCombo = new ComboBox<>();
        wsCombo.setMinWidth(240);
        wsCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(WorkspacePick object) {
                return object == null ? "" : object.label();
            }

            @Override
            public WorkspacePick fromString(String string) {
                return null;
            }
        });

        Runnable refillWorkspaceCombo = () -> {
            workspaceSelectorSync = true;
            try {
                List<WorkspacePick> picks = new ArrayList<>();
                picks.add(new WorkspacePick(null, "All workspaces (every project)"));
                for (Workspace w : mainVm.workspaces) {
                    String nm = w.name() == null || w.name().isBlank() ? "(unnamed workspace)" : w.name();
                    picks.add(new WorkspacePick(w.id(), nm));
                }
                wsCombo.setItems(FXCollections.observableArrayList(picks));
                UUID active = mainVm.getActiveWorkspaceId();
                wsCombo.getItems().stream()
                        .filter(p -> Objects.equals(p.id(), active))
                        .findFirst()
                        .ifPresentOrElse(wsCombo::setValue, () -> wsCombo.getSelectionModel().selectFirst());
            } finally {
                workspaceSelectorSync = false;
            }
        };

        wsCombo.setOnAction(e -> {
            if (workspaceSelectorSync) {
                return;
            }
            WorkspacePick p = wsCombo.getValue();
            mainVm.setActiveWorkspaceId(p == null ? null : p.id());
        });

        mainVm.workspaces.addListener((ListChangeListener<Workspace>) c -> refillWorkspaceCombo.run());
        mainVm.activeWorkspaceIdProperty().addListener((obs, o, n) -> refillWorkspaceCombo.run());
        refillWorkspaceCombo.run();

        Button manageWsBtn = new Button("Manage workspaces…");
        manageWsBtn.setOnAction(e -> WorkspaceManagerDialog.open(mainVm, primaryStage));

        MenuItem cursorSettingsItem = new MenuItem("Cursor…");
        cursorSettingsItem.setOnAction(event -> ConsoleSettingsWindows.openCursor(mainVm, primaryStage));
        MenuItem ollamaSettingsItem = new MenuItem("Ollama…");
        ollamaSettingsItem.setOnAction(event -> ConsoleSettingsWindows.openOllama(mainVm, primaryStage));
        MenuItem githubSettingsItem = new MenuItem("GitHub…");
        githubSettingsItem.setOnAction(event -> ConsoleSettingsWindows.openGitHub(mainVm, primaryStage));
        MenuItem pixelOfficeItem = new MenuItem("Pixel Office…");
        pixelOfficeItem.setOnAction(event -> openPixelOfficeWindow(mainVm));
        MenuButton settingsBtn = new MenuButton("Settings");
        settingsBtn.getItems().addAll(
                cursorSettingsItem,
                ollamaSettingsItem,
                githubSettingsItem,
                new SeparatorMenuItem(),
                pixelOfficeItem);

        Separator sep = new Separator(Orientation.VERTICAL);

        Circle cursorLed = new Circle(5);
        Circle githubLed = new Circle(5);
        cursorLed.setStroke(Color.web("#444"));
        githubLed.setStroke(Color.web("#444"));
        Label cursorConnLbl = new Label("Cursor");
        Label githubConnLbl = new Label("GitHub");
        cursorConnLbl.setStyle("-fx-cursor: hand;");
        githubConnLbl.setStyle("-fx-cursor: hand;");
        Runnable openCursorSettings = () -> ConsoleSettingsWindows.openCursor(mainVm, primaryStage);
        Runnable openGithubSettings = () -> ConsoleSettingsWindows.openGitHub(mainVm, primaryStage);
        cursorConnLbl.setOnMouseClicked(e -> openCursorSettings.run());
        githubConnLbl.setOnMouseClicked(e -> openGithubSettings.run());
        cursorLed.setOnMouseClicked(e -> openCursorSettings.run());
        githubLed.setOnMouseClicked(e -> openGithubSettings.run());
        cursorLed.setStyle("-fx-cursor: hand;");
        githubLed.setStyle("-fx-cursor: hand;");

        Tooltip cursorTip = new Tooltip();
        Tooltip githubTip = new Tooltip();
        Tooltip.install(cursorLed, cursorTip);
        Tooltip.install(githubLed, githubTip);
        Tooltip.install(cursorConnLbl, cursorTip);
        Tooltip.install(githubConnLbl, githubTip);

        Runnable refreshConnLeds = () -> {
            if (!mainVm.cursorConnectivityCheckedProperty().get()) {
                cursorLed.setFill(Color.GRAY);
                cursorTip.setText("Cursor: checking…\nClick to open Cursor settings.");
            } else {
                cursorLed.setFill(mainVm.cursorConnectivityOkProperty().get() ? Color.LIMEGREEN : Color.RED);
                cursorTip.setText("Cursor: " + mainVm.cursorConnectivityDetailProperty().get()
                        + "\nClick to open Cursor settings.");
            }
            if (!mainVm.githubConnectivityCheckedProperty().get()) {
                githubLed.setFill(Color.GRAY);
                githubTip.setText("GitHub: checking…\nClick to open GitHub settings.");
            } else {
                githubLed.setFill(mainVm.githubConnectivityOkProperty().get() ? Color.LIMEGREEN : Color.RED);
                githubTip.setText("GitHub: " + mainVm.githubConnectivityDetailProperty().get()
                        + "\nClick to open GitHub settings.");
            }
        };
        mainVm.cursorConnectivityCheckedProperty().addListener((o, a, b) -> refreshConnLeds.run());
        mainVm.cursorConnectivityOkProperty().addListener((o, a, b) -> refreshConnLeds.run());
        mainVm.cursorConnectivityDetailProperty().addListener((o, a, b) -> refreshConnLeds.run());
        mainVm.githubConnectivityCheckedProperty().addListener((o, a, b) -> refreshConnLeds.run());
        mainVm.githubConnectivityOkProperty().addListener((o, a, b) -> refreshConnLeds.run());
        mainVm.githubConnectivityDetailProperty().addListener((o, a, b) -> refreshConnLeds.run());
        refreshConnLeds.run();

        Separator settingsSep = new Separator(Orientation.VERTICAL);
        HBox leftBar = new HBox(8, wsLabel, wsCombo, manageWsBtn, sep);
        leftBar.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox rightBar = new HBox(8, cursorLed, cursorConnLbl, githubLed, githubConnLbl, settingsSep, settingsBtn);
        rightBar.setAlignment(Pos.CENTER_RIGHT);

        return new HBox(8, leftBar, spacer, rightBar);
    }

    private void openPixelOfficeWindow(MainViewModel mainVm) {
        if (pixelOfficeStage != null && pixelOfficeStage.isShowing()) {
            pixelOfficeStage.toFront();
            return;
        }
        pixelOfficeStage = new PixelOfficeStage(primaryStage, mainVm);
        pixelOfficeStage.show();
    }
}
