package com.example.aiteamconsole.ui.runs;

import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.ProviderRegistry;
import com.example.aiteamconsole.RunLogEntry;
import com.example.aiteamconsole.TaskStatus;
import com.example.aiteamconsole.Workspace;
import com.example.aiteamconsole.ui.MainViewModel;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;

import java.util.Comparator;
import java.util.UUID;
import java.util.function.Supplier;

public final class RunsViewModel {

    private final MainViewModel main;
    private final ProviderRegistry providerRegistry;
    private final Supplier<com.example.aiteamconsole.AppSettings> settingsSupplier;

    public final ObservableList<AgentRun> runsBacking;
    public final ObservableList<AgentTask> tasksBacking;

    public final FilteredList<AgentRun> filtered;
    public final SortedList<AgentRun> sorted;

    /** {@code null} means show all runs (legacy «All» filter). */
    public final ObjectProperty<TaskStatus> taskStatusFilter = new SimpleObjectProperty<>(null);
    public final ObjectProperty<AgentRun> selected = new SimpleObjectProperty<>();

    public final ObservableList<RunLogEntry> selectedRunLogs = FXCollections.observableArrayList();

    public RunsViewModel(
            MainViewModel main,
            ProviderRegistry providerRegistry,
            Supplier<com.example.aiteamconsole.AppSettings> settingsSupplier
    ) {
        this.main = main;
        this.providerRegistry = providerRegistry;
        this.settingsSupplier = settingsSupplier;
        this.runsBacking = main.agentRuns;
        this.tasksBacking = main.agentTasks;
        this.filtered = new FilteredList<>(runsBacking, this::matchesFilters);
        this.sorted = new SortedList<>(filtered);
        main.agentTasks.addListener((javafx.collections.ListChangeListener<AgentTask>) c -> refreshFilteredPredicate());
        taskStatusFilter.addListener((obs, o, n) -> refreshFilteredPredicate());
        main.activeWorkspaceIdProperty().addListener((obs, o, n) -> refreshFilteredPredicate());
        main.workspaces.addListener((ListChangeListener<Workspace>) c -> refreshFilteredPredicate());
        main.repositories.addListener((ListChangeListener<com.example.aiteamconsole.RepositoryEntry>) c -> refreshFilteredPredicate());
    }

    private boolean matchesFilters(AgentRun r) {
        return matchesTaskStatusFilter(r) && matchesWorkspaceFilter(r);
    }

    private boolean matchesWorkspaceFilter(AgentRun r) {
        return main.findTask(r.taskId()).map(main::taskMatchesActiveWorkspace).orElse(false);
    }

    private boolean matchesTaskStatusFilter(AgentRun r) {
        TaskStatus filter = taskStatusFilter.get();
        if (filter == null) {
            return true;
        }
        return main.findTask(r.taskId())
                .map(t -> t.status() == filter)
                .orElse(false);
    }

    public void refreshFilteredPredicate() {
        filtered.setPredicate(null);
        filtered.setPredicate(this::matchesFilters);
    }

    public void cancelRun() {
        AgentRun run = selected.get();
        if (run == null) {
            main.dialogs().showError("Select a run first.");
            return;
        }
        main.cancelRun(run);
    }

    public void refreshRunStatus() {
        AgentRun run = selected.get();
        if (run == null) {
            main.dialogs().showError("Select a run first.");
            return;
        }
        main.refreshRun(run);
    }

    public void restartRun() {
        main.restartFailedRun(selected.get());
    }

    public void refreshSelectedLogs() {
        AgentRun run = selected.get();
        selectedRunLogs.clear();
        if (run == null) {
            return;
        }
        AgentRun latest = main.findRun(run.id()).orElse(run);
        latest.logs().stream()
                .sorted(Comparator.comparing(RunLogEntry::timestamp))
                .forEach(selectedRunLogs::add);
    }

    public void onSelectedRunUpdated(UUID runId, AgentRun updated) {
        AgentRun sel = selected.get();
        if (sel != null && sel.id().equals(runId)) {
            refreshSelectedLogs();
        }
    }

    public ProviderRegistry providerRegistry() {
        return providerRegistry;
    }

    public com.example.aiteamconsole.AppSettings currentSettings() {
        return settingsSupplier.get();
    }

    public MainViewModel main() {
        return main;
    }
}
