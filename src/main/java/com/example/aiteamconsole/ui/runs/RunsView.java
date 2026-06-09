package com.example.aiteamconsole.ui.runs;

import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.RunStatus;
import com.example.aiteamconsole.TaskStatus;
import com.example.aiteamconsole.ui.FxTableHelpers;
import com.example.aiteamconsole.ui.MainViewModel;
import com.example.aiteamconsole.ui.PullRequestLinkLabels;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
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
        TableColumn<AgentRun, String> taskCol = FxTableHelpers.textColumn("Task", run -> main.taskTitle(run.taskId()), 240);
        taskCol.setComparator(String.CASE_INSENSITIVE_ORDER);
        table.getColumns().add(taskCol);
        TableColumn<AgentRun, String> agentCol = FxTableHelpers.textColumn("Agent", run -> main.agentName(run.agentProfileId()), 80);
        agentCol.setComparator(String.CASE_INSENSITIVE_ORDER);
        table.getColumns().add(agentCol);
        table.getColumns().add(pullRequestLinkColumn());
        table.getColumns().add(durationColumn());
        table.getColumns().add(tokensColumn());

        table.setRowFactory(tv -> {
            TableRow<AgentRun> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() != 2
                        || row.isEmpty()
                        || event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                Node target = event.getPickResult().getIntersectedNode();
                for (Node n = target; n != null && n != row; n = n.getParent()) {
                    if (n instanceof Hyperlink) {
                        return;
                    }
                }
                RunInfoDialog.open(main, main.primaryStage(), row.getItem());
            });
            return row;
        });

        vm.tasksBacking.addListener((ListChangeListener<com.example.aiteamconsole.AgentTask>) c -> vm.refreshFilteredPredicate());

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldRun, newRun) -> {
            vm.selected.set(newRun);
            vm.refreshSelectedLogs();
        });

        Label pollHint = new Label(
                "Active runs (Creating / Running / Unknown) refresh automatically about every 8 seconds. "
                        + "Double-click a row for provider / external ids, branch, PR, and result summary. "
                        + "Select a run and open Logs for full line-by-line output. "
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

        Button logs = new Button("Logs");
        logs.setOnAction(event -> RunLogsDialog.open(vm, main.primaryStage()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topBar = new HBox(8, new Label("Task status:"), taskStatusFilterCombo, spacer, logs, refresh, cancel, restartRun);
        topBar.setAlignment(Pos.CENTER_LEFT);

        table.getSortOrder().setAll(startedAtCol);
        startedAtCol.setSortType(TableColumn.SortType.DESCENDING);

        FxTableHelpers.autoSizeColumnsToContent(table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return FxTableHelpers.padded(new VBox(10, topBar, table, pollHint));
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
        col.setComparator(Comparator.nullsLast(Comparator.naturalOrder()));
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
        col.setComparator(Comparator.nullsLast(Comparator.naturalOrder()));
        return col;
    }

    private TableColumn<AgentRun, String> pullRequestLinkColumn() {
        TableColumn<AgentRun, String> col = new TableColumn<>("PR");
        col.setMinWidth(72);
        col.setPrefWidth(88);
        col.setMaxWidth(120);
        col.setCellValueFactory(cd -> {
            String url = cd.getValue().pullRequestUrl();
            return new SimpleStringProperty(url == null ? "" : url.strip());
        });
        col.setCellFactory(ignored -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink();

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty) {
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                if (item == null || item.isBlank()) {
                    Label dash = new Label("—");
                    dash.setStyle("-fx-text-fill: #888;");
                    setGraphic(dash);
                    setTooltip(null);
                    return;
                }
                final String url = item.strip();
                link.setText(PullRequestLinkLabels.compactLabel(url));
                link.setTooltip(new Tooltip(PullRequestLinkLabels.tooltipForUrl(url).orElse(url)));
                link.setOnAction(e -> vm.main().openUrl(url));
                setGraphic(link);
            }
        });
        col.setComparator(Comparator.comparing((String u) -> u == null || u.isBlank())
                .thenComparing(u -> PullRequestLinkLabels.compactLabel(u), String.CASE_INSENSITIVE_ORDER));
        return col;
    }

    /** Duration column: shows "Xm Ys" for completed runs, "—" for running. */
    private TableColumn<AgentRun, String> durationColumn() {
        TableColumn<AgentRun, String> col = new TableColumn<>("Duration");
        col.setMinWidth(72);
        col.setPrefWidth(80);
        col.setMaxWidth(100);
        col.setCellValueFactory(cd -> {
            long secs = cd.getValue().durationSeconds();
            if (secs < 0) {
                return new SimpleStringProperty("—");
            }
            String text = secs < 60
                    ? secs + "s"
                    : (secs / 60) + "m " + (secs % 60) + "s";
            return new SimpleStringProperty(text);
        });
        col.setComparator(Comparator.comparingLong(s -> {
            if (s == null || s.equals("—")) return Long.MAX_VALUE;
            try {
                if (s.contains("m")) {
                    String[] parts = s.replace("s", "").split("m ");
                    return Long.parseLong(parts[0].trim()) * 60 + Long.parseLong(parts[1].trim());
                }
                return Long.parseLong(s.replace("s", "").trim());
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }));
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }

    /**
     * Token column: shows estimated (Cursor) or real (Ollama) total tokens.
     * "~" prefix indicates an estimate, no prefix = real count.
     */
    private TableColumn<AgentRun, String> tokensColumn() {
        TableColumn<AgentRun, String> col = new TableColumn<>("Tokens");
        col.setMinWidth(72);
        col.setPrefWidth(88);
        col.setMaxWidth(110);
        col.setCellValueFactory(cd -> {
            AgentRun run = cd.getValue();
            int total = run.totalTokens();
            if (total <= 0) {
                return new SimpleStringProperty("—");
            }
            // Cursor estimates (~) vs Ollama real counts
            boolean isEstimate = run.provider() == com.example.aiteamconsole.ProviderType.CURSOR_CLOUD;
            String prefix = isEstimate ? "~" : "";
            String text = total >= 1000
                    ? prefix + String.format("%.1fk", total / 1000.0)
                    : prefix + total;
            return new SimpleStringProperty(text);
        });
        col.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item);
                if (!item.equals("—")) {
                    AgentRun run = getTableView().getItems().get(getIndex());
                    int in = run.estimatedInputTokens();
                    int out = run.estimatedOutputTokens();
                    boolean est = run.provider() == com.example.aiteamconsole.ProviderType.CURSOR_CLOUD;
                    String tip = (est ? "Estimated" : "Measured") + " tokens\n"
                            + "Input:  " + in + "\n"
                            + "Output: " + out + "\n"
                            + "Total:  " + (in + out)
                            + (est ? "\n(~chars/4 approximation)" : "");
                    setTooltip(new Tooltip(tip));
                } else {
                    setTooltip(null);
                }
            }
        });
        col.setComparator(Comparator.comparingInt(s -> {
            if (s == null || s.equals("—")) return -1;
            try {
                String clean = s.replace("~", "").replace("k", "");
                double val = Double.parseDouble(clean);
                return (int)(s.contains("k") ? val * 1000 : val);
            } catch (NumberFormatException e) {
                return -1;
            }
        }));
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }
}
