package com.example.aiteamconsole.ui.tasks;

import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.RepositoryEntry;
import com.example.aiteamconsole.TagSelectionControl;
import com.example.aiteamconsole.TaskRepositoryTagPicker;
import com.example.aiteamconsole.TaskStatus;
import com.example.aiteamconsole.ui.FxTableHelpers;
import com.example.aiteamconsole.ui.MainViewModel;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

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
        table.getColumns().add(FxTableHelpers.textColumn("Role", task -> MainViewModel.roleForTaskPrefix(task.taskPrefix()).map(com.example.aiteamconsole.AgentRole::label).orElse("Any"), 150));
        table.getColumns().add(FxTableHelpers.textColumn("Agent", task -> main.agentName(task.assignedAgentId()), 180));
        table.getColumns().add(FxTableHelpers.textColumn("Tags", task -> RepositoryEntry.formatTags(task.repositoryTags()), 160));
        table.getColumns().add(FxTableHelpers.textColumn("Started", task -> FxTableHelpers.formatOptionalTime(task.startedAt()), 160));
        table.getColumns().add(FxTableHelpers.textColumn("Ended", task -> FxTableHelpers.formatOptionalTime(task.endedAt()), 160));

        ComboBox<String> prefix = new ComboBox<>(FXCollections.observableArrayList("BE-TASK", "FE-TASK", "QA-TASK", "REV-TASK", "DEVOPS-TASK"));
        prefix.setEditable(true);
        prefix.getSelectionModel().select("BE-TASK");

        TextField title = new TextField();
        title.setPromptText("Add TTL support to cache");
        title.textProperty().bindBidirectional(vm.formTitle);

        TextArea description = new TextArea();
        description.setPromptText("Acceptance criteria, constraints, test expectations...");
        description.setPrefRowCount(5);
        description.textProperty().bindBidirectional(vm.formDescription);

        TagSelectionControl tagPicker = new TaskRepositoryTagPicker(main.repositories);

        ComboBox<String> branchName = new ComboBox<>();
        branchName.setEditable(true);
        branchName.setPromptText("Choose branch...");
        branchName.setDisable(true);
        branchName.getItems().addAll("main");
        CheckBox specifyBranch = new CheckBox("Specify branch");
        Button refreshBranches = new Button("Refresh branches");
        refreshBranches.setDisable(true);
        refreshBranches.setOnAction(event -> main.refreshBranchesForTags(tagPicker.selected(), branches -> {
            String current = branchName.getEditor().getText();
            branchName.getItems().setAll(branches);
            if (current != null && !current.isBlank()) {
                branchName.getEditor().setText(current);
            }
        }));
        specifyBranch.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            branchName.setDisable(!isSelected);
            refreshBranches.setDisable(!isSelected);
            if (isSelected) {
                main.refreshBranchesForTags(tagPicker.selected(), branches -> {
                    String current = branchName.getEditor().getText();
                    branchName.getItems().setAll(branches);
                    if (current != null && !current.isBlank()) {
                        branchName.getEditor().setText(current);
                    }
                });
            } else {
                branchName.getSelectionModel().clearSelection();
                branchName.getEditor().clear();
            }
        });

        ComboBox<AgentProfile> agentSelector = new ComboBox<>(vm.lists.profiles());
        agentSelector.setConverter(FxTableHelpers.agentConverter());
        CheckBox assignDeveloper = new CheckBox("Assign developer");
        agentSelector.setDisable(true);
        assignDeveloper.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            agentSelector.setDisable(!isSelected);
            if (!isSelected) {
                agentSelector.getSelectionModel().clearSelection();
            }
        });

        Predicate<AgentTask> draftRunEnabled = t -> t.status() == TaskStatus.DRAFT && !main.hasActiveRunForTask(t.id());

        table.getColumns().add(taskActionsColumn(
                task -> loadEditorsFromTask(task, prefix, tagPicker, specifyBranch, branchName, assignDeveloper, agentSelector),
                task -> vm.deleteTask(task),
                task -> main.startTask(task),
                draftRunEnabled
        ));

        Button create = new Button("Create task");
        create.setOnAction(event -> {
            UUID assignedId = resolveAssignedAgentId(assignDeveloper, agentSelector);
            if (assignDeveloper.isSelected() && assignedId == null) {
                return;
            }
            vm.saveTask(
                    null,
                    MainViewModel.normalizeTaskPrefix(prefix.getEditor().getText()),
                    tagPicker.selected(),
                    branchValue(specifyBranch, branchName),
                    assignDeveloper.isSelected(),
                    assignedId
            );
            title.clear();
            description.clear();
            tagPicker.setSelected(List.of());
            branchName.getEditor().clear();
            specifyBranch.setSelected(false);
            assignDeveloper.setSelected(false);
            vm.taskFormIsEditMode.set(false);
            table.getSelectionModel().clearSelection();
        });

        Button saveChanges = new Button("Save changes");
        saveChanges.setOnAction(event -> {
            AgentTask sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) {
                main.dialogs().showError("Select a task first.");
                return;
            }
            UUID assignedId = resolveAssignedAgentId(assignDeveloper, agentSelector);
            if (assignDeveloper.isSelected() && assignedId == null) {
                return;
            }
            vm.saveTask(
                    sel,
                    MainViewModel.normalizeTaskPrefix(prefix.getEditor().getText()),
                    tagPicker.selected(),
                    branchValue(specifyBranch, branchName),
                    assignDeveloper.isSelected(),
                    assignedId
            );
        });

        Button start = new Button("Save&Start");
        start.setOnAction(event -> {
            AgentTask sel = table.getSelectionModel().getSelectedItem();
            UUID assignedId = resolveAssignedAgentId(assignDeveloper, agentSelector);
            if (assignDeveloper.isSelected() && assignedId == null) {
                return;
            }
            vm.saveAndStartTask(
                    sel,
                    MainViewModel.normalizeTaskPrefix(prefix.getEditor().getText()),
                    tagPicker.selected(),
                    branchValue(specifyBranch, branchName),
                    assignDeveloper.isSelected(),
                    assignedId
            );
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
            boolean edit = vm.taskFormIsEditMode.get();
            create.setVisible(!edit);
            create.setManaged(!edit);
            saveChanges.setVisible(edit);
            saveChanges.setManaged(edit);
            start.setVisible(edit);
            start.setManaged(edit);
        };
        vm.taskFormIsEditMode.addListener((obs, was, isNow) -> refreshTaskFormActionButtons.run());
        refreshTaskFormActionButtons.run();

        FxTableHelpers.autoSizeColumnsToContent(table);
        return FxTableHelpers.padded(new VBox(10, table, new Separator(), form));
    }

    private void importMarkdownAsTaskDescription(TextArea description) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Markdown file");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown", "*.md", "*.markdown"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(vm.main().primaryStage());
        if (file == null) {
            return;
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            description.setText(text);
        } catch (IOException ex) {
            com.example.aiteamconsole.AppLogging.get(TasksView.class).log(Level.WARNING, "Failed to read markdown for task description", ex);
            vm.main().dialogs().showError("Could not read file: " + ex.getMessage());
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

    private UUID resolveAssignedAgentId(CheckBox assignDeveloper, ComboBox<AgentProfile> agentSelector) {
        if (!assignDeveloper.isSelected()) {
            return null;
        }
        AgentProfile selectedAgent = agentSelector.getValue();
        if (selectedAgent == null) {
            vm.main().dialogs().showError("Choose an agent or uncheck Assign developer.");
            return null;
        }
        return selectedAgent.id();
    }

    private void loadEditorsFromTask(
            AgentTask selected,
            ComboBox<String> prefix,
            TagSelectionControl tagPicker,
            CheckBox specifyBranch,
            ComboBox<String> branchName,
            CheckBox assignDeveloper,
            ComboBox<AgentProfile> agentSelector
    ) {
        MainViewModel main = vm.main();
        if (!main.canEditTask(selected)) {
            main.dialogs().showError("Only tasks that are not in progress can be edited.");
            return;
        }
        vm.selected.set(selected);
        vm.formTitle.set(selected.title());
        vm.formDescription.set(selected.description());
        prefix.getEditor().setText(selected.taskPrefix());
        tagPicker.setSelected(selected.repositoryTags());
        if (selected.startingRef() != null && !selected.startingRef().isBlank()) {
            specifyBranch.setSelected(true);
            branchName.getEditor().setText(selected.startingRef());
        } else {
            specifyBranch.setSelected(false);
            branchName.getEditor().clear();
        }
        Optional<AgentProfile> agent = main.findAgent(selected.assignedAgentId());
        assignDeveloper.setSelected(agent.isPresent());
        agent.ifPresent(agentSelector::setValue);
        if (agent.isEmpty()) {
            agentSelector.getSelectionModel().clearSelection();
        }
        vm.taskFormIsEditMode.set(true);
    }

    private static TableColumn<AgentTask, Void> taskActionsColumn(
            java.util.function.Consumer<AgentTask> editAction,
            java.util.function.Consumer<AgentTask> deleteAction,
            java.util.function.Consumer<AgentTask> runAction,
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
}
