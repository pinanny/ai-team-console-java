package com.example.aiteamconsole.ui;

import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.ProjectMemorySnapshot;
import com.example.aiteamconsole.RepositoryEntry;
import com.example.aiteamconsole.TaskStatus;
import com.example.aiteamconsole.Workspace;
import com.example.aiteamconsole.ui.settings.ConsoleSettingsWindows;

import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;

/**
 * Per-repository domain (project memory) status and one-click re-analysis.
 */
public final class DomainMemoryView {

    private final MainViewModel main;

    public DomainMemoryView(MainViewModel main) {
        this.main = main;
    }

    public Node buildView() {
        FilteredList<RepositoryEntry> visibleRepos = new FilteredList<>(main.repositories, main::repositoryIncludedInActiveWorkspace);
        Runnable bumpRepoFilter = () -> {
            visibleRepos.setPredicate(null);
            visibleRepos.setPredicate(main::repositoryIncludedInActiveWorkspace);
        };
        main.activeWorkspaceIdProperty().addListener((obs, o, n) -> bumpRepoFilter.run());
        main.workspaces.addListener((ListChangeListener<Workspace>) c -> bumpRepoFilter.run());
        main.repositories.addListener((ListChangeListener<RepositoryEntry>) c -> bumpRepoFilter.run());

        TableView<RepositoryEntry> table = new TableView<>(visibleRepos);

        table.getColumns().add(FxTableHelpers.textColumn("Repository", RepositoryEntry::label, 160));
        table.getColumns().add(FxTableHelpers.textColumn("URL", r -> r.url(), 260));
        table.getColumns().add(FxTableHelpers.textColumn("Memory", this::memorySummaryCell, 120));
        table.getColumns().add(FxTableHelpers.textColumn("Last saved", this::lastSavedCell, 160));
        table.getColumns().add(FxTableHelpers.textColumn("Latest MEM task", this::memTaskCell, 200));

        TableColumn<RepositoryEntry, Void> actions = new TableColumn<>("Actions");
        actions.setMinWidth(120);
        actions.setPrefWidth(140);
        actions.setUserData("fixed-width");
        actions.setCellFactory(ignored -> new TableCell<>() {
            private final Button analyze = new Button("Analyze");

            {
                analyze.setOnAction(e -> {
                    RepositoryEntry repo = getTableView().getItems().get(getIndex());
                    main.analyzeProjectMemoryWithOverwriteConfirm(repo);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                setGraphic(analyze);
            }
        });
        table.getColumns().add(actions);

        Runnable refresh = table::refresh;
        main.repositories.addListener((ListChangeListener<RepositoryEntry>) c -> refresh.run());
        main.agentTasks.addListener((ListChangeListener<AgentTask>) c -> refresh.run());

        Label hint = new Label(
                "Snapshots are stored under " + main.projectMemoryStorageDir()
                        + " (one JSON file per repo). After «Analyze», follow the MEM… task on the Tasks and Runs tabs; "
                        + "when it finishes, context is injected into new agent runs for that repository.\n"
                        + "Choose a workspace in the top bar to show only that product’s repositories.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #555;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button openSettings = new Button("Settings → GitHub…");
        openSettings.setOnAction(e -> ConsoleSettingsWindows.openGitHub(main, main.primaryStage()));
        HBox top = new HBox(8, openSettings, spacer);
        top.setAlignment(Pos.CENTER_LEFT);

        FxTableHelpers.autoSizeColumnsToContent(table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return FxTableHelpers.padded(new VBox(12, top, table, new Separator(), hint));
    }

    private String memorySummaryCell(RepositoryEntry repo) {
        return main.projectMemorySnapshotForRepo(repo)
                .filter(ProjectMemorySnapshot::hasContext)
                .map(s -> "Yes (" + s.coreContext().length() + " chars)")
                .orElse("No");
    }

    private String lastSavedCell(RepositoryEntry repo) {
        return main.projectMemorySnapshotForRepo(repo)
                .map(ProjectMemorySnapshot::lastUpdatedAt)
                .filter(u -> u != null && !Instant.EPOCH.equals(u))
                .map(FxTableHelpers::formatOptionalTime)
                .orElse("—");
    }

    private String memTaskCell(RepositoryEntry repo) {
        return main.latestMemDomainTaskForRepo(repo)
                .map(DomainMemoryView::formatMemTask)
                .orElse("—");
    }

    private static String formatMemTask(AgentTask t) {
        String key = t.taskKey();
        if (key == null || key.isBlank()) {
            key = t.title();
        }
        TaskStatus st = t.status();
        return key + " · " + (st == null ? "?" : st.name());
    }
}
