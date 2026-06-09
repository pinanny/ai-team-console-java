package com.example.aiteamconsole.ui.tasks;

import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.ProviderRegistry;
import com.example.aiteamconsole.TaskFlowWave;
import com.example.aiteamconsole.Workspace;
import com.example.aiteamconsole.ui.MainViewModel;

import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class TasksViewModel {

    private final MainViewModel main;
    private final ProviderRegistry providerRegistry;
    private final Supplier<com.example.aiteamconsole.AppSettings> settingsSupplier;

    public final ObservableLists lists;

    public TasksViewModel(
            MainViewModel main,
            ProviderRegistry providerRegistry,
            Supplier<com.example.aiteamconsole.AppSettings> settingsSupplier
    ) {
        this.main = main;
        this.providerRegistry = providerRegistry;
        this.settingsSupplier = settingsSupplier;
        FilteredList<AgentTask> visibleTasks = new FilteredList<>(main.agentTasks, main::taskMatchesActiveWorkspace);
        Runnable bumpTaskFilter = () -> {
            visibleTasks.setPredicate(null);
            visibleTasks.setPredicate(main::taskMatchesActiveWorkspace);
        };
        main.activeWorkspaceIdProperty().addListener((obs, o, n) -> bumpTaskFilter.run());
        main.workspaces.addListener((ListChangeListener<Workspace>) c -> bumpTaskFilter.run());
        main.repositories.addListener((ListChangeListener<com.example.aiteamconsole.RepositoryEntry>) c -> bumpTaskFilter.run());
        this.lists = new ObservableLists(main.agentProfiles, visibleTasks, main.agentRuns);
    }

    public record ObservableLists(
            javafx.collections.ObservableList<AgentProfile> profiles,
            javafx.collections.ObservableList<AgentTask> tasks,
            javafx.collections.ObservableList<com.example.aiteamconsole.AgentRun> runs
    ) {
    }

    /**
     * @return saved task, or {@code null} if validation failed
     */
    public AgentTask saveTaskReturningSaved(
            AgentTask tableSelected,
            String normalizedPrefixFromUi,
            String title,
            String description,
            List<String> repositoryTagsFromUi,
            String branchFromUi,
            boolean assignDeveloper,
            UUID assignedAgentIdResolved,
            List<TaskFlowWave> developmentFlow,
            int developmentFlowWaveIndex
    ) {
        return main.saveTaskFromInputsReturningSaved(
                tableSelected,
                normalizedPrefixFromUi,
                title,
                description,
                repositoryTagsFromUi,
                branchFromUi,
                assignDeveloper,
                assignedAgentIdResolved,
                developmentFlow,
                developmentFlowWaveIndex
        );
    }

    /**
     * @return {@code true} if save succeeded and any intended start step completed (or was skipped per PA rule)
     */
    public boolean saveAndStartTask(
            AgentTask tableSelected,
            String normalizedPrefixFromUi,
            String title,
            String description,
            List<String> repositoryTagsFromUi,
            String branchFromUi,
            boolean assignDeveloper,
            UUID assignedAgentIdResolved,
            List<TaskFlowWave> developmentFlow,
            int developmentFlowWaveIndex
    ) {
        boolean isNewTask = tableSelected == null;
        AgentTask saved = main.saveTaskFromInputsReturningSaved(
                tableSelected,
                normalizedPrefixFromUi,
                title,
                description,
                repositoryTagsFromUi,
                branchFromUi,
                assignDeveloper,
                assignedAgentIdResolved,
                developmentFlow,
                developmentFlowWaveIndex
        );
        if (saved == null) {
            return false;
        }
        if (isNewTask && main.hasProductAnalystAgent()) {
            return true;
        }
        main.startTask(saved);
        return true;
    }

    public void deleteTask(AgentTask task) {
        main.deleteTask(task);
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
