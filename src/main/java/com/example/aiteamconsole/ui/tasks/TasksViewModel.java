package com.example.aiteamconsole.ui.tasks;

import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.ProviderRegistry;
import com.example.aiteamconsole.TaskStatus;
import com.example.aiteamconsole.ui.MainViewModel;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.UUID;
import java.util.function.Supplier;

public final class TasksViewModel {

    private final MainViewModel main;
    private final ProviderRegistry providerRegistry;
    private final Supplier<com.example.aiteamconsole.AppSettings> settingsSupplier;

    public final ObservableLists lists;

    public final StringProperty formTitle = new SimpleStringProperty("");
    public final StringProperty formDescription = new SimpleStringProperty("");
    public final ObjectProperty<AgentProfile> formAgent = new SimpleObjectProperty<>();
    public final ObjectProperty<TaskStatus> formStatus = new SimpleObjectProperty<>();
    public final ObjectProperty<AgentTask> selected = new SimpleObjectProperty<>();

    /** Mirrors legacy UI: after Edit, show save/start; after Create task, show create only. */
    public final BooleanProperty taskFormIsEditMode = new SimpleBooleanProperty(false);

    public TasksViewModel(
            MainViewModel main,
            ProviderRegistry providerRegistry,
            Supplier<com.example.aiteamconsole.AppSettings> settingsSupplier
    ) {
        this.main = main;
        this.providerRegistry = providerRegistry;
        this.settingsSupplier = settingsSupplier;
        this.lists = new ObservableLists(main.agentProfiles, main.agentTasks, main.agentRuns);
    }

    public record ObservableLists(
            javafx.collections.ObservableList<AgentProfile> profiles,
            javafx.collections.ObservableList<AgentTask> tasks,
            javafx.collections.ObservableList<com.example.aiteamconsole.AgentRun> runs
    ) {
    }

    public void saveTask(
            AgentTask tableSelected,
            String normalizedPrefixFromUi,
            java.util.List<String> repositoryTagsFromUi,
            String branchFromUi,
            boolean assignDeveloper,
            UUID assignedAgentIdResolved
    ) {
        main.saveTaskFromInputs(
                tableSelected,
                normalizedPrefixFromUi,
                formTitle.get(),
                formDescription.get(),
                repositoryTagsFromUi,
                branchFromUi,
                assignDeveloper,
                assignedAgentIdResolved
        );
    }

    public void saveAndStartTask(
            AgentTask tableSelected,
            String normalizedPrefixFromUi,
            java.util.List<String> repositoryTagsFromUi,
            String branchFromUi,
            boolean assignDeveloper,
            UUID assignedAgentIdResolved
    ) {
        AgentTask saved = main.saveTaskFromInputsReturningSaved(
                tableSelected,
                normalizedPrefixFromUi,
                formTitle.get(),
                formDescription.get(),
                repositoryTagsFromUi,
                branchFromUi,
                assignDeveloper,
                assignedAgentIdResolved
        );
        if (saved != null) {
            main.startTask(saved);
        }
    }

    public void deleteTask(AgentTask task) {
        main.deleteTask(task);
    }

    public void clearForm() {
        selected.set(null);
        formTitle.set("");
        formDescription.set("");
        formAgent.set(null);
        formStatus.set(null);
        taskFormIsEditMode.set(false);
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
