package com.example.aiteamconsole.ui.tasks;

import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.DevelopmentFlowParser;
import com.example.aiteamconsole.TaskAssigneeHistoryEntry;
import com.example.aiteamconsole.TaskFlowWave;
import com.example.aiteamconsole.TaskStatus;
import com.example.aiteamconsole.ui.FxTableHelpers;
import com.example.aiteamconsole.ui.MainViewModel;
import com.example.aiteamconsole.ui.PullRequestLinkLabels;
import com.example.aiteamconsole.ui.RegistryRepositoryPicker;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Modal task create / edit form (previously inline under the tasks table).
 */
public final class TaskFormDialog {

    private TaskFormDialog() {
    }

    /**
     * @param editOrNull {@code null} for create, otherwise task to edit (must pass {@link MainViewModel#canEditTask}).
     * @param afterSuccess run after a successful save (e.g. clear table selection).
     */
    public static void open(TasksViewModel vm, Window owner, AgentTask editOrNull, Runnable afterSuccess) {
        MainViewModel main = vm.main();
        if (editOrNull != null && !main.canEditTask(editOrNull)) {
            main.dialogs().showError("Only tasks that are not in progress can be edited.");
            return;
        }
        boolean edit = editOrNull != null;

        Stage dlg = new Stage();
        dlg.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            dlg.initOwner(owner);
        }
        String titleText = !edit
                ? "Create task"
                : (editOrNull.status() == TaskStatus.SPEC_REVIEW ? "Review specification" : "Edit task");
        dlg.setTitle(titleText);
        dlg.setMinWidth(640);
        dlg.setMinHeight(480);

        ComboBox<String> prefix = new ComboBox<>(FXCollections.observableArrayList(
                "PA-TASK", "BE-TASK", "FE-TASK", "QA-TASK", "REV-TASK", "DEVOPS-TASK"));
        prefix.setEditable(true);
        prefix.getSelectionModel().select("BE-TASK");

        TextField title = new TextField();
        title.setPromptText("Add TTL support to cache");

        TextArea description = new TextArea();
        description.setPromptText("Acceptance criteria, constraints, test expectations...");
        description.setPrefRowCount(5);

        TextArea developmentPipeline = new TextArea();
        developmentPipeline.setPromptText(
                "Optional. One line per wave; comma = parallel in same wave.\nExample:\nBE\nFE, QA");
        developmentPipeline.setPrefRowCount(3);

        RegistryRepositoryPicker repoPicker = new RegistryRepositoryPicker(
                main.repositoriesInActiveWorkspace,
                () -> main.getActiveWorkspaceId() == null
                        ? "Repositories (full registry; none checked = all in scope):"
                        : "Repositories in this workspace (none checked = all in this workspace):");

        ComboBox<String> branchName = new ComboBox<>();
        branchName.setEditable(true);
        branchName.setPromptText("Choose branch...");
        branchName.setDisable(true);
        branchName.getItems().addAll("main");
        CheckBox specifyBranch = new CheckBox("Specify branch");
        Button refreshBranches = new Button("Refresh branches");
        refreshBranches.setDisable(true);
        Runnable loadBranches = () -> main.refreshBranchesForRepositoryTags(repoPicker.getSelectedTags(), branches -> {
            String current = branchName.getEditor().getText();
            branchName.getItems().setAll(branches);
            if (current != null && !current.isBlank()) {
                branchName.getEditor().setText(current);
            }
        });
        refreshBranches.setOnAction(event -> loadBranches.run());
        specifyBranch.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            branchName.setDisable(!isSelected);
            refreshBranches.setDisable(!isSelected);
            if (isSelected) {
                loadBranches.run();
            } else {
                branchName.getSelectionModel().clearSelection();
                branchName.getEditor().clear();
            }
        });

        if (edit) {
            AgentTask selected = editOrNull;
            title.setText(selected.title());
            description.setText(selected.description());
            prefix.getEditor().setText(selected.taskPrefix());
            repoPicker.setSelectedTags(selected.repositoryTags());
            if (selected.startingRef() != null && !selected.startingRef().isBlank()) {
                specifyBranch.setSelected(true);
                branchName.getEditor().setText(selected.startingRef());
            } else {
                specifyBranch.setSelected(false);
                branchName.getEditor().clear();
            }
            developmentPipeline.setText(DevelopmentFlowParser.format(selected.developmentFlow()));
        }

        ComboBox<AgentProfile> assigneePick = edit ? createAssigneePicker(main, editOrNull) : null;

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> dlg.close());

        Button create = new Button("Create task");
        create.setVisible(!edit);
        create.setManaged(!edit);
        create.setOnAction(event -> {
            List<TaskFlowWave> flow = parsePipelineOrNull(main, developmentPipeline.getText());
            if (flow == null) {
                return;
            }
            AgentTask saved = vm.saveTaskReturningSaved(
                    null,
                    MainViewModel.normalizeTaskPrefix(prefix.getEditor().getText()),
                    title.getText(),
                    description.getText(),
                    repoPicker.getSelectedTags(),
                    branchValue(specifyBranch, branchName),
                    false,
                    null,
                    flow,
                    0
            );
            if (saved != null) {
                dlg.close();
                afterSuccess.run();
            }
        });

        Button saveChanges = new Button("Save changes");
        saveChanges.setVisible(edit);
        saveChanges.setManaged(edit);
        saveChanges.setOnAction(event -> {
            List<TaskFlowWave> flow = parsePipelineOrNull(main, developmentPipeline.getText());
            if (flow == null) {
                return;
            }
            int waveIdx = waveIndexAfterPipelineEdit(editOrNull, flow);
            AgentTask saved = vm.saveTaskReturningSaved(
                    editOrNull,
                    MainViewModel.normalizeTaskPrefix(prefix.getEditor().getText()),
                    title.getText(),
                    description.getText(),
                    repoPicker.getSelectedTags(),
                    branchValue(specifyBranch, branchName),
                    false,
                    selectedAssigneeId(assigneePick),
                    flow,
                    waveIdx
            );
            if (saved != null) {
                dlg.close();
                afterSuccess.run();
            }
        });

        Button saveAndStart = new Button("Save&Start");
        boolean paReviewGate = edit && editOrNull.status() == TaskStatus.SPEC_REVIEW;
        saveAndStart.setVisible(edit && !paReviewGate);
        saveAndStart.setManaged(edit && !paReviewGate);
        saveAndStart.setOnAction(event -> {
            List<TaskFlowWave> flow = parsePipelineOrNull(main, developmentPipeline.getText());
            if (flow == null) {
                return;
            }
            int waveIdx = waveIndexAfterPipelineEdit(editOrNull, flow);
            boolean ok = vm.saveAndStartTask(
                    editOrNull,
                    MainViewModel.normalizeTaskPrefix(prefix.getEditor().getText()),
                    title.getText(),
                    description.getText(),
                    repoPicker.getSelectedTags(),
                    branchValue(specifyBranch, branchName),
                    false,
                    selectedAssigneeId(assigneePick),
                    flow,
                    waveIdx
            );
            if (ok) {
                dlg.close();
                afterSuccess.run();
            }
        });

        create.setDefaultButton(!edit);
        saveChanges.setDefaultButton(edit && paReviewGate);
        saveAndStart.setDefaultButton(edit && !paReviewGate);

        HBox pullRequestCell = new HBox(8);
        Runnable refreshPullRequestRow = () -> {
            pullRequestCell.getChildren().clear();
            if (!edit) {
                return;
            }
            Optional<String> opt = main.pullRequestUrlForTask(editOrNull);
            if (opt.isEmpty() || opt.get().isBlank()) {
                Label dash = new Label("—");
                dash.setStyle("-fx-text-fill: #888;");
                pullRequestCell.getChildren().add(dash);
                return;
            }
            String url = opt.get().strip();
            Hyperlink link = new Hyperlink(PullRequestLinkLabels.compactLabel(url));
            link.setTooltip(new Tooltip(PullRequestLinkLabels.tooltipForUrl(url).orElse(url)));
            link.setOnAction(e -> main.openUrl(url));
            pullRequestCell.getChildren().add(link);
        };
        if (edit) {
            refreshPullRequestRow.run();
            main.agentRuns.addListener((ListChangeListener<AgentRun>) c -> refreshPullRequestRow.run());
        }

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        int row = 0;
        form.addRow(row++, new Label("Prefix"), prefix);
        if (edit) {
            form.addRow(row++, new Label("Implementation assignee"), assigneePick);
            GridPane.setColumnSpan(assigneePick, 4);
        }
        form.addRow(row++, new Label("Title"), title, specifyBranch, branchName, refreshBranches);
        if (edit) {
            form.addRow(row++, new Label("Pull request"), pullRequestCell);
            GridPane.setColumnSpan(pullRequestCell, 4);
        }
        int repoRow = row++;
        int descriptionRow = row++;
        int pipelineRow = row++;
        form.add(new Label("Repositories"), 0, repoRow);
        form.add(repoPicker.node(), 1, repoRow, 4, 1);
        GridPane.setColumnSpan(repoPicker.node(), 4);
        Button importDescriptionMd = new Button("Import description from .md file…");
        importDescriptionMd.setOnAction(e -> importMarkdownAsTaskDescription(main, dlg, description));
        VBox descriptionBox = new VBox(6, importDescriptionMd, description);
        VBox.setVgrow(description, Priority.ALWAYS);
        form.add(new Label("Description"), 0, descriptionRow);
        form.add(descriptionBox, 1, descriptionRow, 4, 1);
        GridPane.setColumnSpan(descriptionBox, 4);
        form.add(new Label("Development pipeline"), 0, pipelineRow);
        form.add(developmentPipeline, 1, pipelineRow, 4, 1);
        GridPane.setColumnSpan(developmentPipeline, 4);
        GridPane.setHgrow(developmentPipeline, Priority.ALWAYS);
        GridPane.setHgrow(title, Priority.ALWAYS);
        GridPane.setHgrow(descriptionBox, Priority.ALWAYS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, cancel, create, saveChanges, saveAndStart);
        actions.setPadding(new Insets(12, 0, 0, 0));

        VBox root = new VBox(10);
        root.setPadding(new Insets(16));
        root.getChildren().add(hintLabel(main.hasProductAnalystAgent()));
        root.getChildren().add(form);
        if (edit) {
            root.getChildren().add(assigneeHistorySection(main, editOrNull.id()));
        }
        root.getChildren().add(actions);
        VBox.setVgrow(form, Priority.ALWAYS);

        Scene scene = new Scene(root);
        dlg.setScene(scene);
        dlg.show();
    }

    private static List<TaskFlowWave> parsePipelineOrNull(MainViewModel main, String raw) {
        try {
            return DevelopmentFlowParser.parse(raw);
        } catch (IllegalArgumentException ex) {
            main.dialogs().showError(ex.getMessage());
            return null;
        }
    }

    /** If pipeline text changed vs stored task, next wave resets to 0 so ordering stays consistent. */
    private static int waveIndexAfterPipelineEdit(AgentTask editOrNull, List<TaskFlowWave> parsed) {
        if (editOrNull == null) {
            return 0;
        }
        return parsed.equals(editOrNull.developmentFlow())
                ? editOrNull.developmentFlowWaveIndex()
                : 0;
    }

    private static UUID selectedAssigneeId(ComboBox<AgentProfile> combo) {
        if (combo == null) {
            return null;
        }
        AgentProfile p = combo.getSelectionModel().getSelectedItem();
        return p == null ? null : p.id();
    }

    private static ComboBox<AgentProfile> createAssigneePicker(MainViewModel main, AgentTask task) {
        ObservableList<AgentProfile> items = FXCollections.observableArrayList();
        items.add(null);
        items.addAll(main.agentProfiles);
        ComboBox<AgentProfile> combo = new ComboBox<>(items);
        combo.setTooltip(new Tooltip(
                "Profile that runs the next implementation step when you click Run. "
                        + "Unassigned = first free agent matching the task prefix role."));
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(AgentProfile object) {
                if (object == null) {
                    return "Unassigned (pick by prefix on Run)";
                }
                return object.name() + " (" + object.role().label() + ")";
            }

            @Override
            public AgentProfile fromString(String string) {
                return null;
            }
        });
        if (task.assignedAgentId() != null) {
            main.findAgent(task.assignedAgentId()).ifPresentOrElse(
                    a -> combo.getSelectionModel().select(a),
                    () -> combo.getSelectionModel().selectFirst());
        } else {
            combo.getSelectionModel().selectFirst();
        }
        return combo;
    }

    private static javafx.scene.Node assigneeHistorySection(MainViewModel main, UUID taskId) {
        Label title = new Label("Assignee history");
        title.setStyle("-fx-font-weight: bold;");

        ObservableList<TaskAssigneeHistoryEntry> items = FXCollections.observableArrayList();
        TableView<TaskAssigneeHistoryEntry> table = new TableView<>(items);
        table.setPrefHeight(200);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<TaskAssigneeHistoryEntry, String> whenCol = new TableColumn<>("When");
        whenCol.setCellValueFactory(cd -> new SimpleStringProperty(
                FxTableHelpers.formatOptionalTime(cd.getValue().at())));

        TableColumn<TaskAssigneeHistoryEntry, String> whoCol = new TableColumn<>("Who");
        whoCol.setCellValueFactory(cd -> new SimpleStringProperty(
                main.agentName(cd.getValue().assigneeAgentId())));

        TableColumn<TaskAssigneeHistoryEntry, String> whatCol = new TableColumn<>("What");
        whatCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().reason()));

        table.getColumns().setAll(List.of(whenCol, whoCol, whatCol));

        Runnable refresh = () -> {
            items.clear();
            main.findTask(taskId).ifPresent(t -> {
                List<TaskAssigneeHistoryEntry> hist = t.assigneeHistory();
                for (int i = hist.size() - 1; i >= 0; i--) {
                    items.add(hist.get(i));
                }
            });
        };
        refresh.run();
        main.agentRuns.addListener((ListChangeListener<AgentRun>) c -> refresh.run());
        main.agentTasks.addListener((ListChangeListener<AgentTask>) c -> refresh.run());

        Label hint = new Label("Newest first — run starts, finishes, Product Analyst handoff, and spec accept.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #606060;");
        return new VBox(8, title, hint, table);
    }

    /** Short note: assignment is tied to lifecycle (PA first, executor from active runs). */
    private static javafx.scene.Node hintLabel(boolean hasPa) {
        String text = hasPa
                ? "New tasks queue for Product Analyst first. After Accept spec, pick an implementation assignee (or leave unassigned for prefix-based Run). "
                        + "Optional development pipeline field runs waves in order (comma = parallel in one wave); when set, it overrides prefix-based Run routing."
                : "No Product Analyst configured — Run starts implementation agents by prefix. "
                + "Assignee column shows the pinned profile when set. "
                + "Optional development pipeline: one line per wave, comma-separated roles for parallel work in the same wave.";
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: #606060;");
        return l;
    }

    private static void importMarkdownAsTaskDescription(MainViewModel main, Stage dialogOwner, TextArea description) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Markdown file");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown", "*.md", "*.markdown"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(dialogOwner);
        if (file == null) {
            return;
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            description.setText(text);
        } catch (IOException ex) {
            com.example.aiteamconsole.AppLogging.get(TaskFormDialog.class).log(Level.WARNING, "Failed to read markdown for task description", ex);
            main.dialogs().showError("Could not read file: " + ex.getMessage());
        }
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
}
