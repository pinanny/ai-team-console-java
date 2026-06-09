package com.example.aiteamconsole.ui.agents;

import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentRole;
import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.OllamaHttpClient;
import com.example.aiteamconsole.OllamaModelCompatibility;
import com.example.aiteamconsole.ProviderType;
import com.example.aiteamconsole.RepositoryEntry;
import com.example.aiteamconsole.Workspace;
import com.example.aiteamconsole.ui.FxTableHelpers;
import com.example.aiteamconsole.ui.MainViewModel;
import com.example.aiteamconsole.ui.RegistryRepositoryPicker;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.util.Callback;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class AgentsView {

    private final AgentsViewModel vm;

    public AgentsView(AgentsViewModel vm) {
        this.vm = vm;
    }

    public Node buildView() {
        MainViewModel mainVm = vm.main();
        Label roleStatsLine = new Label();
        roleStatsLine.setWrapText(true);
        roleStatsLine.setStyle("-fx-text-fill: #333; -fx-font-size: 12px;");
        Runnable refreshRoleStats = () -> {
            EnumMap<AgentRole, Integer> counts = new EnumMap<>(AgentRole.class);
            for (AgentRole r : AgentRole.values()) {
                counts.put(r, 0);
            }
            for (AgentProfile p : vm.profiles) {
                counts.merge(p.role(), 1, Integer::sum);
            }
            StringBuilder sb = new StringBuilder("Agents by role: ");
            AgentRole[] roles = AgentRole.values();
            for (int i = 0; i < roles.length; i++) {
                if (i > 0) {
                    sb.append("  ·  ");
                }
                AgentRole r = roles[i];
                sb.append(r.label()).append(": ").append(counts.get(r));
            }
            sb.append("  —  Total: ").append(vm.profiles.size());
            roleStatsLine.setText(sb.toString());
        };
        vm.profiles.addListener((ListChangeListener<AgentProfile>) c -> refreshRoleStats.run());
        refreshRoleStats.run();

        TableView<AgentProfile> table = new TableView<>();
        FxTableHelpers.attachSortedItems(table, vm.profiles);

        table.getColumns().add(FxTableHelpers.textColumn("Name", AgentProfile::name, 180));
        table.getColumns().add(FxTableHelpers.textColumn("Role", agent -> agent.role().label(), 160));
        table.getColumns().add(FxTableHelpers.textColumn("Provider", agent -> agent.provider().toString(), 160));
        table.getColumns().add(FxTableHelpers.textColumn("Status", agent -> vm.isAgentFree(agent.id()) ? "Active" : "Working", 100));
        table.getColumns().add(FxTableHelpers.textColumn(
                "Repositories",
                agent -> agent.repositoryTags() == null || agent.repositoryTags().isEmpty()
                        ? "(all)"
                        : RepositoryEntry.formatTags(agent.repositoryTags()),
                200));
        table.getColumns().add(FxTableHelpers.textColumn("Branch prefix", AgentProfile::branchPrefix, 180));

        RegistryRepositoryPicker repoPicker = new RegistryRepositoryPicker(
                mainVm.repositoriesInActiveWorkspace,
                () -> mainVm.getActiveWorkspaceId() == null
                        ? "Repositories (full registry; none checked = all repositories):"
                        : "Repositories in this workspace (none checked = all repos in this workspace):");
        CheckBox limitRepositories = new CheckBox("Limit to specific repositories");
        limitRepositories.setTooltip(new javafx.scene.control.Tooltip(
                "When checked, the agent only uses repositories you select below. "
                        + "With a workspace selected in the top bar, only that workspace's repositories are listed. "
                        + "When unchecked, runs use all repositories in scope (workspace or full registry)."));
        VBox repositoryPickerBlock = new VBox(6, repoPicker.node());
        repositoryPickerBlock.managedProperty().bind(limitRepositories.selectedProperty());
        repositoryPickerBlock.visibleProperty().bind(limitRepositories.selectedProperty());

        Runnable applyRepositoryLimitFromSelection = () -> {
            repoPicker.refresh();
            AgentProfile sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) {
                limitRepositories.setSelected(false);
                repoPicker.setSelectedTags(List.of());
                return;
            }
            List<String> tags = mainVm.filterRepositoryTagsToActiveWorkspace(sel.repositoryTags());
            boolean limited = tags != null && !tags.isEmpty();
            limitRepositories.setSelected(limited);
            repoPicker.setSelectedTags(limited ? tags : List.of());
        };

        mainVm.agentRuns.addListener((ListChangeListener<AgentRun>) c -> table.refresh());
        Runnable syncRepoPickerToSelection = applyRepositoryLimitFromSelection;
        vm.repositories.addListener((ListChangeListener<RepositoryEntry>) c -> syncRepoPickerToSelection.run());
        mainVm.activeWorkspaceIdProperty().addListener((obs, o, n) -> syncRepoPickerToSelection.run());
        mainVm.workspaces.addListener((ListChangeListener<Workspace>) c -> syncRepoPickerToSelection.run());
        mainVm.repositoriesInActiveWorkspace.addListener((ListChangeListener<RepositoryEntry>) c -> syncRepoPickerToSelection.run());

        TextField name = new TextField();
        name.setPromptText("Backend Agent");
        name.textProperty().bindBidirectional(vm.formName);

        ComboBox<AgentRole> role = new ComboBox<>(FXCollections.observableArrayList(AgentRole.values()));
        role.valueProperty().bindBidirectional(vm.formRole);

        ComboBox<ProviderType> providerPick = new ComboBox<>(FXCollections.observableArrayList(ProviderType.values()));
        providerPick.valueProperty().bindBidirectional(vm.formProvider);

        Label branchPreview = new Label();
        branchPreview.setStyle("-fx-text-fill: #666;");
        Runnable refreshBranchPreview = () ->
                branchPreview.setText(AgentProfile.autoBranchPrefix(vm.formName.get() == null ? "" : vm.formName.get()));
        vm.formName.addListener((obs, oldValue, newValue) -> refreshBranchPreview.run());
        refreshBranchPreview.run();

        Button submit = new Button("Create");
        Runnable refreshSubmitButton = () -> {
            boolean updateMode = table.getSelectionModel().getSelectedItem() != null;
            submit.setText(updateMode ? "Update" : "Create");
        };
        submit.setOnAction(event -> {
            AgentProfile sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) {
                vm.selected.set(sel);
            } else {
                vm.selected.set(null);
            }
            AgentProfile before = vm.selected.get();
            vm.saveAgent(repositoryTagsForSave(limitRepositories, repoPicker));
            if (before == null) {
                table.getSelectionModel().clearSelection();
            }
        });

        ToggleButton showAgentForm = new ToggleButton("Create agent…");
        showAgentForm.selectedProperty().addListener((obs, o, n) -> {
            showAgentForm.setText(Boolean.TRUE.equals(n) ? "Hide agent form" : "Create agent…");
            if (Boolean.TRUE.equals(n) && table.getSelectionModel().getSelectedItem() == null) {
                vm.clearForm();
                limitRepositories.setSelected(false);
                repoPicker.setSelectedTags(List.of());
                refreshBranchPreview.run();
            }
            refreshSubmitButton.run();
        });

        table.getColumns().add(agentActionsColumn(
                agent -> {
                    showAgentForm.setSelected(true);
                    table.getSelectionModel().select(agent);
                    vm.loadSelectionIntoForm(agent);
                    applyRepositoryLimitFromSelection.run();
                    branchPreview.setText(agent.branchPrefix());
                    refreshSubmitButton.run();
                },
                agent -> vm.deleteAgent(agent)
        ));

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            if (newSel != null) {
                vm.loadSelectionIntoForm(newSel);
                branchPreview.setText(newSel.branchPrefix());
            }
            applyRepositoryLimitFromSelection.run();
            refreshSubmitButton.run();
        });

        name.setPrefWidth(132);
        name.setMaxWidth(160);
        role.setPrefWidth(168);
        role.setMaxWidth(220);
        providerPick.setPrefWidth(200);
        providerPick.setMinWidth(180);

        HBox identityRow = new HBox(4);
        identityRow.setAlignment(Pos.CENTER_LEFT);
        identityRow.getChildren().addAll(
                new Label("Name"), name,
                new Label("Role"), role,
                new Label("Provider"), providerPick);
        HBox.setHgrow(providerPick, Priority.ALWAYS);

        // ── Ollama model picker with compatibility indicators ────────────────────
        List<String> popularModels = List.of(
                "llama3.2:3b", "llama3.2:1b", "llama3.1:8b",
                "qwen2.5-coder:7b", "qwen2.5-coder:3b",
                "mistral:7b", "gemma2:9b", "gemma2:2b",
                "deepseek-coder-v2:16b", "phi4:14b", "phi3.5:3.8b"
        );

        // modelName → rich info (null entry = popular preset, not installed)
        Map<String, OllamaHttpClient.OllamaModelInfo> installedInfo = new java.util.LinkedHashMap<>();

        ComboBox<String> modelCombo = new ComboBox<>(FXCollections.observableArrayList(popularModels));
        modelCombo.setEditable(true);
        modelCombo.setPromptText("(global setting)");
        modelCombo.setPrefWidth(220);

        // Cell factory: shows  ✓/⚠/✗ icon + name + params for each row
        Callback<javafx.scene.control.ListView<String>, ListCell<String>> cellFactory = lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setTooltip(null); return; }
                OllamaHttpClient.OllamaModelInfo info = installedInfo.get(item);
                long diskBytes  = info != null ? info.diskBytes() : 0;
                String paramSz  = info != null ? info.parameterSize() : "";
                String quant    = info != null ? info.quantization() : "";
                OllamaModelCompatibility.Fit fit = OllamaModelCompatibility.evaluate(item, diskBytes);
                // Build display string
                StringBuilder sb = new StringBuilder();
                sb.append(fit.icon).append("  ").append(item);
                if (!paramSz.isBlank()) sb.append("  ·  ").append(paramSz);
                if (info != null && diskBytes > 0) sb.append("  ·  ").append(info.diskSizeLabel());
                else {
                    double req = OllamaModelCompatibility.requiredRamGb(item, 0);
                    if (req > 0) sb.append("  ·  ~%.0fGB".formatted(req));
                }
                if (info == null) sb.append("  (not installed)");
                setText(sb.toString());
                setStyle("-fx-text-fill: " + fit.color + ";");
                setTooltip(new Tooltip(OllamaModelCompatibility.buildTooltip(item, diskBytes, paramSz, quant)));
            }
        };
        modelCombo.setCellFactory(cellFactory);
        // Button cell (shows selected value)
        modelCombo.setButtonCell(cellFactory.call(null));

        // Bind to vm property
        modelCombo.valueProperty().addListener((obs, o, n) -> vm.formOllamaModel.set(n == null ? "" : n));
        vm.formOllamaModel.addListener((obs, o, n) -> {
            if (n != null && !n.equals(modelCombo.getValue())) {
                modelCombo.setValue(n.isBlank() ? null : n);
            }
        });

        // RAM label shown below the combo
        // Small hint under the combo (RAM legend is in the panel to the right)
        Label ramLabel = new Label("Leave blank to use the global model from Ollama settings.");
        ramLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 10px;");

        Button refreshModelList = new Button("↺");
        refreshModelList.setTooltip(new Tooltip("Load installed models from Ollama"));
        refreshModelList.setOnAction(e -> {
            refreshModelList.setDisable(true);
            String baseUrl = mainVm.settings.ollamaBaseUrl.get();
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://127.0.0.1:11434";
            final String url = baseUrl;
            OllamaHttpClient http = mainVm.providerRegistry().ollamaProvider().ollamaHttp();
            CompletableFuture.supplyAsync(() -> {
                try { return http.listLocalModelDetails(url); }
                catch (Exception ex) { throw new RuntimeException(ex.getMessage(), ex); }
            }).whenComplete((details, err) -> Platform.runLater(() -> {
                refreshModelList.setDisable(false);
                if (err != null) {
                    mainVm.dialogs().showError("Ollama unreachable: " + err.getMessage());
                    return;
                }
                installedInfo.clear();
                details.forEach(d -> installedInfo.put(d.name(), d));
                String current = modelCombo.getValue();
                List<String> combined = new ArrayList<>(installedInfo.keySet());
                for (String p : popularModels) if (!combined.contains(p)) combined.add(p);
                modelCombo.setItems(FXCollections.observableArrayList(combined));
                modelCombo.setValue(current);
            }));
        });

        Button installModel = new Button("⬇ Install");
        installModel.setTooltip(new Tooltip("Download this model via ollama pull"));
        installModel.setOnAction(e -> {
            String modelName = modelCombo.getValue();
            if (modelName == null || modelName.isBlank()) {
                mainVm.dialogs().showError("Select a model name first.");
                return;
            }
            // Warn if model is too large for this machine
            OllamaModelCompatibility.Fit fit =
                    OllamaModelCompatibility.evaluate(modelName, 0);
            if (fit == OllamaModelCompatibility.Fit.TOO_LARGE) {
                boolean proceed = mainVm.dialogs().confirm(
                        "Model may be too large",
                        modelName + " likely requires more RAM than available ("
                                + OllamaModelCompatibility.systemRamLabel() + ")",
                        "Downloading and running this model may cause swapping or crashes.\nContinue anyway?");
                if (!proceed) return;
            }
            String baseUrl = mainVm.settings.ollamaBaseUrl.get();
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://127.0.0.1:11434";
            final String url = baseUrl;
            installModel.setDisable(true);
            installModel.setText("Pulling…");
            OllamaHttpClient http = mainVm.providerRegistry().ollamaProvider().ollamaHttp();
            CompletableFuture.runAsync(() -> {
                try {
                    http.pullModel(url, modelName, line -> {});
                    Platform.runLater(() -> {
                        installModel.setDisable(false);
                        installModel.setText("⬇ Install");
                        refreshModelList.fire(); // refresh list to get updated size info
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        installModel.setDisable(false);
                        installModel.setText("⬇ Install");
                        mainVm.dialogs().showError("Pull failed: " + ex.getMessage());
                    });
                }
            });
        });

        // Show Install button only when selected model is NOT installed
        Runnable updateInstallVisibility = () -> {
            String sel = modelCombo.getValue();
            boolean notInstalled = sel != null && !sel.isBlank() && !installedInfo.containsKey(sel);
            installModel.setVisible(notInstalled);
            installModel.setManaged(notInstalled);
        };
        modelCombo.valueProperty().addListener((obs, o, n) -> updateInstallVisibility.run());

        HBox modelRow = new HBox(6, modelCombo, refreshModelList, installModel);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modelCombo, Priority.ALWAYS);

        // Colour legend panel — shown to the right of the model picker
        Label legendTitle = new Label("Compatibility");
        legendTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #555;");

        VBox legendBox = new VBox(4, legendTitle);
        legendBox.setStyle(
                "-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-radius: 4;"
                        + " -fx-background-radius: 4; -fx-padding: 8 10 8 10;");
        legendBox.setMinWidth(160);

        for (OllamaModelCompatibility.Fit fit : OllamaModelCompatibility.Fit.values()) {
            if (fit == OllamaModelCompatibility.Fit.UNKNOWN) continue; // skip ? row
            Label row = new Label(fit.icon + "  " + switch (fit) {
                case FITS      -> "Fits comfortably";
                case TIGHT     -> "Tight — may be slow";
                case TOO_LARGE -> "Too large for RAM";
                default        -> fit.description;
            });
            row.setStyle("-fx-text-fill: " + fit.color + "; -fx-font-size: 11px;");
            row.setTooltip(new Tooltip(fit.description));
            legendBox.getChildren().add(row);
        }

        // "Your RAM" row at bottom of legend
        Label yourRam = new Label("Your RAM: " + OllamaModelCompatibility.systemRamLabel());
        yourRam.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        legendBox.getChildren().add(new javafx.scene.control.Separator());
        legendBox.getChildren().add(yourRam);

        Label modelLabel = new Label("Ollama model");
        VBox modelPickerBlock = new VBox(3, new HBox(8, modelLabel, modelRow), ramLabel);
        HBox.setHgrow(modelRow, Priority.ALWAYS);

        HBox modelPickerRow = new HBox(12, modelPickerBlock, legendBox);
        HBox.setHgrow(modelPickerBlock, Priority.ALWAYS);

        // Visible only when Ollama is selected
        modelPickerRow.managedProperty().bind(modelPickerRow.visibleProperty());
        modelPickerRow.visibleProperty().bind(
                providerPick.valueProperty().isEqualTo(ProviderType.OLLAMA));

        // Auto-load model list when Ollama is selected
        providerPick.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == ProviderType.OLLAMA && installedInfo.isEmpty()) {
                refreshModelList.fire();
            }
        });
        // ────────────────────────────────────────────────────────────────────────

        HBox branchRow = new HBox(8);
        branchRow.setAlignment(Pos.CENTER_LEFT);
        refreshSubmitButton.run();
        branchRow.getChildren().addAll(new Label("Branch prefix (auto)"), branchPreview, submit);
        HBox.setHgrow(branchPreview, Priority.ALWAYS);

        VBox form = new VBox(8, identityRow, modelPickerRow, limitRepositories, repositoryPickerBlock, branchRow);

        VBox formBlock = new VBox(8, form);
        formBlock.managedProperty().bind(formBlock.visibleProperty());
        formBlock.visibleProperty().bind(showAgentForm.selectedProperty());

        Region toggleLeft = new Region();
        Region toggleRight = new Region();
        HBox.setHgrow(toggleLeft, Priority.ALWAYS);
        HBox.setHgrow(toggleRight, Priority.ALWAYS);
        HBox toggleRow = new HBox(toggleLeft, showAgentForm, toggleRight);
        toggleRow.setAlignment(Pos.CENTER);

        FxTableHelpers.autoSizeColumnsToContent(table);

        Separator belowTable = new Separator();
        VBox root = new VBox(10, roleStatsLine, table, belowTable, toggleRow, formBlock);
        root.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        return root;
    }

    private List<String> repositoryTagsForSave(CheckBox limitRepositories, RegistryRepositoryPicker repoPicker) {
        if (!limitRepositories.isSelected()) {
            return List.of();
        }
        return mainVm.filterRepositoryTagsToActiveWorkspace(repoPicker.getSelectedTags());
    }

    private static TableColumn<AgentProfile, Void> agentActionsColumn(
            java.util.function.Consumer<AgentProfile> editAction,
            java.util.function.Consumer<AgentProfile> deleteAction
    ) {
        TableColumn<AgentProfile, Void> column = new TableColumn<>("");
        column.setMinWidth(112);
        column.setPrefWidth(120);
        column.setMaxWidth(140);
        column.setUserData("fixed-width");
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Button edit = new Button("Edit");
            private final Button delete = new Button("Delete");
            private final HBox buttons;

            {
                edit.setTooltip(new javafx.scene.control.Tooltip("Edit agent profile"));
                delete.setTooltip(new javafx.scene.control.Tooltip("Delete agent"));
                buttons = new HBox(4, edit, delete);
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
