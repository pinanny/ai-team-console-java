package com.example.aiteamconsole.ui.tasks;

import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.RepositoryEntry;
import com.example.aiteamconsole.TaskStatus;
import com.example.aiteamconsole.ui.FxTableHelpers;
import com.example.aiteamconsole.ui.MainViewModel;
import com.example.aiteamconsole.ui.PullRequestLinkLabels;

import javafx.collections.ListChangeListener;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.input.MouseButton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class TasksView {

    private final TasksViewModel vm;

    public TasksView(TasksViewModel vm) {
        this.vm = vm;
    }

    public Node buildView() {
        MainViewModel main = vm.main();
        TableView<AgentTask> table = new TableView<>(vm.lists.tasks());

        table.getColumns().add(FxTableHelpers.textColumn("Key", AgentTask::taskKey, 110));
        table.getColumns().add(FxTableHelpers.textColumn("Title", AgentTask::title, 240));
        table.getColumns().add(FxTableHelpers.textColumn("Status", task -> task.status().name(), 120));
        table.getColumns().add(FxTableHelpers.textColumn("Assigned", task -> main.agentName(task.assignedAgentId()), 200));
        table.getColumns().add(taskPullRequestColumn(main));

        vm.lists.runs().addListener((ListChangeListener<AgentRun>) c -> table.refresh());
        table.getColumns().add(FxTableHelpers.textColumn("Started", task -> FxTableHelpers.formatOptionalTime(task.startedAt()), 160));
        table.getColumns().add(FxTableHelpers.textColumn("Ended", task -> FxTableHelpers.formatOptionalTime(task.endedAt()), 160));

        Predicate<AgentTask> backlogRunEnabled = t ->
                (t.status() == TaskStatus.DRAFT || t.status() == TaskStatus.OPEN) && !main.hasActiveRunForTask(t.id());

        table.setRowFactory(tv -> {
            TableRow<AgentTask> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() != 2
                        || row.isEmpty()
                        || event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                Node target = event.getPickResult().getIntersectedNode();
                for (Node n = target; n != null && n != row; n = n.getParent()) {
                    if (n instanceof Button) {
                        return;
                    }
                }
                TaskFormDialog.open(vm, main.primaryStage(), row.getItem(), () -> {
                });
            });
            return row;
        });
        table.setTooltip(new Tooltip("Double-click a row to open the task (review spec, edit fields, save)."));

        table.getColumns().add(taskActionsColumn(
                task -> TaskFormDialog.open(vm, main.primaryStage(), task, () -> {
                }),
                task -> main.acceptSpecAndOpen(task),
                task -> OpenQuestionsDialog.open(main, main.primaryStage(), task),
                task -> vm.deleteTask(task),
                task -> TaskFormDialog.open(vm, main.primaryStage(), task, () -> {
                }),
                task -> main.startTask(task),
                backlogRunEnabled
        ));

        Button createTask = new Button("Create task");
        createTask.setOnAction(event -> TaskFormDialog.open(vm, main.primaryStage(), null, () ->
                table.getSelectionModel().clearSelection()));

        Button analyzeDomain = new Button("Analyze domain");
        analyzeDomain.setTooltip(new javafx.scene.control.Tooltip(
                "Analyze the repository once to build project memory.\n"
                        + "Saves architecture, stack, and domain context so agents skip\n"
                        + "full codebase re-analysis on every task (saves 80–90% tokens)."));
        analyzeDomain.setOnAction(event -> {
            List<RepositoryEntry> repos = new ArrayList<>(main.repositoriesInActiveWorkspace);
            if (repos.isEmpty()) {
                if (main.getActiveWorkspaceId() != null) {
                    main.dialogs().showError(
                            "No repositories in this workspace. Add repos under Cursor / GitHub settings, "
                                    + "then assign them in Manage workspaces…, or choose «All workspaces».");
                } else {
                    main.dialogs().showError(
                            "No repositories configured. Add a repository in GitHub / Cursor settings first.");
                }
                return;
            }
            RepositoryEntry chosen;
            if (repos.size() == 1) {
                chosen = repos.get(0);
            } else {
                // Multiple repos — ask the user which one to analyze
                List<String> labels = repos.stream()
                        .map(RepositoryEntry::label)
                        .toList();
                ChoiceDialog<String> picker = new ChoiceDialog<>(labels.get(0), labels);
                picker.setTitle("Analyze domain");
                picker.setHeaderText("Select a repository to analyze");
                picker.setContentText("Repository:");
                Optional<String> result = picker.showAndWait();
                if (result.isEmpty()) {
                    return; // user cancelled
                }
                String picked = result.get();
                chosen = repos.stream()
                        .filter(r -> r.label().equals(picked))
                        .findFirst()
                        .orElse(null);
                if (chosen == null) {
                    return;
                }
            }
            // Confirm if memory already exists — re-analyzing will overwrite it
            main.analyzeProjectMemoryWithOverwriteConfirm(chosen);
        });

        FxTableHelpers.autoSizeColumnsToContent(table);
        HBox toolbar = new HBox(8, createTask, analyzeDomain);
        Label scopeHint = new Label(
                "Use Workspace in the top bar to focus one product. Tasks appear if their repository tags overlap that workspace.");
        scopeHint.setWrapText(true);
        scopeHint.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");
        return FxTableHelpers.padded(new VBox(10, scopeHint, table, new Separator(), toolbar));
    }

    private static TableColumn<AgentTask, String> taskPullRequestColumn(MainViewModel main) {
        TableColumn<AgentTask, String> col = new TableColumn<>("PR");
        col.setMinWidth(72);
        col.setPrefWidth(88);
        col.setMaxWidth(120);
        col.setCellValueFactory(cd -> {
            String url = main.pullRequestUrlForTask(cd.getValue()).orElse("");
            return new SimpleStringProperty(url);
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
                link.setOnAction(e -> main.openUrl(url));
                setGraphic(link);
            }
        });
        col.setComparator(Comparator.comparing((String u) -> u == null || u.isBlank())
                .thenComparing(u -> PullRequestLinkLabels.compactLabel(u), String.CASE_INSENSITIVE_ORDER));
        return col;
    }

    private static TableColumn<AgentTask, Void> taskActionsColumn(
            java.util.function.Consumer<AgentTask> editAction,
            java.util.function.Consumer<AgentTask> acceptSpecAction,
            java.util.function.Consumer<AgentTask> createTicketsAction,
            java.util.function.Consumer<AgentTask> deleteAction,
            java.util.function.Consumer<AgentTask> assignAction,
            java.util.function.Consumer<AgentTask> runAction,
            Predicate<AgentTask> backlogRunEnabled
    ) {
        TableColumn<AgentTask, Void> column = new TableColumn<>("");
        column.setMinWidth(168);
        column.setPrefWidth(188);
        column.setMaxWidth(220);
        column.setUserData("fixed-width");
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Button assign = actionButton("Assign", "Pick implementation agent for Run");
            private final Button run = actionButton("Run", "Start agent run");
            private final Button acceptSpec = actionButton("Accept", "Accept Product Analyst spec and open task");
            private final Button createTickets = actionButton("OQ", "Create DRAFT tasks from open questions (OQ- prefix)");
            private final Button edit = actionButton("Edit", "Open task form");
            private final Button delete = actionButton("Delete", "Delete task and local runs");
            private final HBox buttons;

            {
                buttons = new HBox(4, run, assign, acceptSpec, createTickets, edit, delete);
                assign.setOnAction(event -> assignAction.accept(getTableView().getItems().get(getIndex())));
                run.setOnAction(event -> runAction.accept(getTableView().getItems().get(getIndex())));
                acceptSpec.setOnAction(event -> acceptSpecAction.accept(getTableView().getItems().get(getIndex())));
                createTickets.setOnAction(event -> createTicketsAction.accept(getTableView().getItems().get(getIndex())));
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
                boolean backlog = task.status() == TaskStatus.DRAFT || task.status() == TaskStatus.OPEN;
                boolean specReview = task.status() == TaskStatus.SPEC_REVIEW;
                boolean showAssign = backlog && task.status() == TaskStatus.OPEN && task.assignedAgentId() == null;
                boolean showRun = backlog && !showAssign;
                boolean canRun = backlogRunEnabled.test(task);
                assign.setVisible(showAssign);
                assign.setManaged(showAssign);
                assign.setDisable(!canRun);
                run.setVisible(showRun);
                run.setManaged(showRun);
                run.setDisable(!canRun);
                acceptSpec.setVisible(specReview);
                acceptSpec.setManaged(specReview);
                createTickets.setVisible(specReview);
                createTickets.setManaged(specReview);
                setGraphic(buttons);
            }
        });
        return column;
    }

    private static Button actionButton(String text, String tooltip) {
        Button b = new Button(text);
        b.setTooltip(new Tooltip(tooltip));
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setPrefWidth(Region.USE_COMPUTED_SIZE);
        b.setMaxWidth(Region.USE_PREF_SIZE);
        return b;
    }
}
