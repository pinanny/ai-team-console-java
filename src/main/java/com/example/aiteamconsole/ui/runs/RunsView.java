package com.example.aiteamconsole.ui.runs;

import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.ProviderType;
import com.example.aiteamconsole.RunLogEntry;
import com.example.aiteamconsole.RunStatus;
import com.example.aiteamconsole.TaskStatus;
import com.example.aiteamconsole.ui.FxTableHelpers;
import com.example.aiteamconsole.ui.MainViewModel;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.Comparator;

public final class RunsView {

    private final RunsViewModel vm;

    public RunsView(RunsViewModel vm) {
        this.vm = vm;
    }

    public Node buildView() {
        MainViewModel main = vm.main();
        TableView<AgentRun> table = new TableView<>(vm.sorted);
        vm.sorted.comparatorProperty().bind(table.comparatorProperty());

        TableColumn<AgentRun, Instant> startedAtCol = runStartedAtColumn();
        table.getColumns().add(startedAtCol);
        table.getColumns().add(runStatusColumn());
        table.getColumns().add(runProviderColumn());
        TableColumn<AgentRun, String> taskCol = FxTableHelpers.textColumn("Task", run -> main.taskTitle(run.taskId()), 240);
        taskCol.setComparator(String.CASE_INSENSITIVE_ORDER);
        table.getColumns().add(taskCol);
        TableColumn<AgentRun, String> agentCol = FxTableHelpers.textColumn("Agent", run -> main.agentName(run.agentProfileId()), 180);
        agentCol.setComparator(String.CASE_INSENSITIVE_ORDER);
        table.getColumns().add(agentCol);
        table.getColumns().add(runCursorInfoColumn());
        table.getColumns().add(pullRequestLinkColumn());

        table.setFixedCellSize(54);

        vm.tasksBacking.addListener((ListChangeListener<com.example.aiteamconsole.AgentTask>) c -> vm.refreshFilteredPredicate());

        ListView<RunLogEntry> logList = new ListView<>(vm.selectedRunLogs);
        logList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(RunLogEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(FxTableHelpers.timeFormat().format(item.timestamp()) + "  " + item.message());
            }
        });

        Label resultSummary = new Label();
        resultSummary.setWrapText(true);
        resultSummary.setStyle("-fx-text-fill: #444;");

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldRun, newRun) -> {
            vm.selected.set(newRun);
            vm.refreshSelectedLogs();
            if (newRun == null) {
                resultSummary.setText("");
            } else {
                AgentRun latest = main.findRun(newRun.id()).orElse(newRun);
                String rs = latest.resultSummary();
                resultSummary.setText(rs == null || rs.isBlank() ? "" : "Result:\n" + rs);
            }
        });

        Label pollHint = new Label(
                "Active runs (Creating / Running / Unknown) refresh automatically about every 8 seconds. "
                        + "Select a run in the table to show its log below; the log updates when that run is refreshed. "
                        + "For a run in ERROR or CANCELLED, use «Restart run» to queue the same task again with the same agent.");
        pollHint.setWrapText(true);
        pollHint.setStyle("-fx-text-fill: #555;");

        Button refresh = new Button("Refresh Status");
        refresh.setOnAction(event -> vm.refreshRunStatus());

        Button cancel = new Button("Cancel Run");
        cancel.setOnAction(event -> vm.cancelRun());

        Button restartRun = new Button("Restart run");
        restartRun.setOnAction(event -> vm.restartRun());

        ComboBox<String> taskStatusFilterCombo = new ComboBox<>();
        taskStatusFilterCombo.getItems().add("All");
        for (TaskStatus ts : TaskStatus.values()) {
            taskStatusFilterCombo.getItems().add(ts.name());
        }
        taskStatusFilterCombo.setValue("All");
        taskStatusFilterCombo.setPrefWidth(200);
        taskStatusFilterCombo.setOnAction(e -> {
            String v = taskStatusFilterCombo.getValue();
            if (v == null || "All".equals(v)) {
                vm.taskStatusFilter.set(null);
            } else {
                vm.taskStatusFilter.set(TaskStatus.valueOf(v));
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topBar = new HBox(8, new Label("Task status:"), taskStatusFilterCombo, spacer, refresh, cancel, restartRun);
        topBar.setAlignment(Pos.CENTER_LEFT);

        table.getSortOrder().setAll(startedAtCol);
        startedAtCol.setSortType(TableColumn.SortType.DESCENDING);

        FxTableHelpers.autoSizeColumnsToContent(table);
        VBox logColumn = new VBox(6, new Label("Run log (selected run)"), logList, resultSummary);
        VBox.setVgrow(logList, Priority.ALWAYS);
        logList.setPrefHeight(200);
        return FxTableHelpers.padded(new VBox(10, topBar, table, pollHint, logColumn));
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
                setText(empty ? null : FxTableHelpers.formatOptionalTime(item));
            }
        });
        col.setPrefWidth(170);
        col.setComparator(Comparator.nullsLast(Comparator.naturalOrder()));
        return col;
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
                link.setOnAction(e -> vm.main().openUrl(getItem()));
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
}
