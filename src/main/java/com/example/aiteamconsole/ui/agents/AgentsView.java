package com.example.aiteamconsole.ui.agents;

import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentRole;
import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.ProviderType;
import com.example.aiteamconsole.RepositoryEntry;
import com.example.aiteamconsole.TagPicker;
import com.example.aiteamconsole.ui.FxTableHelpers;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.SortedList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class AgentsView {

    private final AgentsViewModel vm;

    public AgentsView(AgentsViewModel vm) {
        this.vm = vm;
    }

    public Node buildView() {
        TableView<AgentProfile> table = new TableView<>();
        FxTableHelpers.attachSortedItems(table, vm.profiles);

        table.getColumns().add(FxTableHelpers.textColumn("Name", AgentProfile::name, 180));
        table.getColumns().add(FxTableHelpers.textColumn("Role", agent -> agent.role().label(), 160));
        table.getColumns().add(FxTableHelpers.textColumn("Provider", agent -> agent.provider().toString(), 160));
        table.getColumns().add(FxTableHelpers.textColumn("Status", agent -> vm.isAgentFree(agent.id()) ? "Active" : "Working", 100));
        table.getColumns().add(FxTableHelpers.textColumn("Repository tags", agent -> RepositoryEntry.formatTags(agent.repositoryTags()), 220));
        table.getColumns().add(FxTableHelpers.textColumn("Branch prefix", AgentProfile::branchPrefix, 180));

        vm.main().agentRuns.addListener((ListChangeListener<AgentRun>) c -> table.refresh());

        TextField name = new TextField();
        name.setPromptText("Backend Agent");
        name.textProperty().bindBidirectional(vm.formName);

        ComboBox<AgentRole> role = new ComboBox<>(FXCollections.observableArrayList(AgentRole.values()));
        role.valueProperty().bindBidirectional(vm.formRole);

        ComboBox<ProviderType> providerPick = new ComboBox<>(FXCollections.observableArrayList(ProviderType.values()));
        providerPick.valueProperty().bindBidirectional(vm.formProvider);

        TagPicker agentTags = new TagPicker(vm::knownTags);
        vm.repositories.addListener((ListChangeListener<RepositoryEntry>) change -> agentTags.refreshAvailable());

        Label branchPreview = new Label();
        branchPreview.setStyle("-fx-text-fill: #666;");
        Runnable refreshBranchPreview = () ->
                branchPreview.setText(AgentProfile.autoBranchPrefix(vm.formName.get() == null ? "" : vm.formName.get()));
        vm.formName.addListener((obs, oldValue, newValue) -> refreshBranchPreview.run());
        refreshBranchPreview.run();

        table.getColumns().add(agentActionsColumn(
                agent -> {
                    table.getSelectionModel().select(agent);
                    vm.loadSelectionIntoForm(agent);
                    agentTags.setSelected(agent.repositoryTags());
                    branchPreview.setText(agent.branchPrefix());
                },
                agent -> vm.deleteAgent(agent)
        ));

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            if (newSel != null) {
                vm.loadSelectionIntoForm(newSel);
                agentTags.setSelected(newSel.repositoryTags());
                branchPreview.setText(newSel.branchPrefix());
            }
        });

        Button create = new Button("Create Agent");
        create.setOnAction(event -> {
            vm.selected.set(null);
            vm.saveAgent(agentTags.selected());
        });

        Button saveSelected = new Button("Save Selected Agent");
        saveSelected.setOnAction(event -> {
            AgentProfile sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) {
                vm.main().dialogs().showError("Select an agent first.");
                return;
            }
            vm.selected.set(sel);
            vm.saveAgent(agentTags.selected());
        });

        Button clear = new Button("Clear");
        clear.setOnAction(event -> {
            vm.clearForm();
            agentTags.setSelected(java.util.List.of());
            table.getSelectionModel().clearSelection();
            refreshBranchPreview.run();
        });

        Button delete = new Button("Delete Selected");
        delete.setOnAction(event -> {
            AgentProfile sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) {
                vm.deleteAgent(sel);
            }
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Name"), name, new Label("Role"), role);
        form.addRow(1, new Label("Provider"), providerPick);
        GridPane.setColumnSpan(providerPick, 3);
        form.addRow(2, new Label("Repository tags"), agentTags.node());
        GridPane.setColumnSpan(agentTags.node(), 3);
        form.addRow(3, new Label("Branch prefix (auto)"), branchPreview, create, saveSelected);
        form.addRow(4, clear, delete);

        FxTableHelpers.autoSizeColumnsToContent(table);
        return FxTableHelpers.padded(new VBox(10, table, new Separator(), form));
    }

    private static TableColumn<AgentProfile, Void> agentActionsColumn(
            java.util.function.Consumer<AgentProfile> editAction,
            java.util.function.Consumer<AgentProfile> deleteAction
    ) {
        TableColumn<AgentProfile, Void> column = new TableColumn<>("Actions");
        column.setMinWidth(170);
        column.setPrefWidth(170);
        column.setUserData("fixed-width");
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Button edit = new Button("Edit");
            private final Button delete = new Button("Delete");
            private final HBox buttons;

            {
                edit.setMinWidth(64);
                delete.setMinWidth(72);
                buttons = new HBox(6, edit, delete);
                edit.setOnAction(event -> editAction.accept(getTableView().getItems().get(getIndex())));
                delete.setOnAction(event -> deleteAction.accept(getTableView().getItems().get(getIndex())));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : buttons);
            }
        });
        return column;
    }
}
