package com.example.aiteamconsole.ui.agents;

import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentRole;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.ProviderType;
import com.example.aiteamconsole.RepositoryEntry;
import com.example.aiteamconsole.ui.MainViewModel;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AgentsViewModel {

    private final MainViewModel main;

    public final ObservableList<AgentProfile> profiles;
    public final ObservableList<RepositoryEntry> repositories;

    public final StringProperty formName = new SimpleStringProperty("");
    public final ObjectProperty<AgentRole> formRole = new SimpleObjectProperty<>(AgentRole.BACKEND_ENGINEER);
    public final ObjectProperty<ProviderType> formProvider = new SimpleObjectProperty<>(ProviderType.CURSOR_CLOUD);
    /** Ollama model override. Empty string = use global model from Ollama settings. */
    public final StringProperty formOllamaModel = new SimpleStringProperty("");

    public final ObjectProperty<AgentProfile> selected = new SimpleObjectProperty<>();

    public AgentsViewModel(MainViewModel main) {
        this.main = main;
        this.profiles = main.agentProfiles;
        this.repositories = main.repositories;
    }

    public void loadSelectionIntoForm(AgentProfile agent) {
        if (agent == null) {
            return;
        }
        selected.set(agent);
        formName.set(agent.name());
        formRole.set(agent.role());
        formProvider.set(agent.provider());
        formOllamaModel.set(agent.effectiveOllamaModel());
    }

    public MainViewModel main() {
        return main;
    }

    public void clearForm() {
        selected.set(null);
        formName.set("");
        formRole.set(AgentRole.BACKEND_ENGINEER);
        formProvider.set(ProviderType.CURSOR_CLOUD);
        formOllamaModel.set("");
    }

    /**
     * Creates a new profile when nothing is selected; otherwise updates the selected profile.
     *
     * @param repositoryTags normalized keys matching {@link RepositoryEntry#tag()} for chosen repos; empty = all repos in registry
     */
    public void saveAgent(List<String> repositoryTags) {
        String name = formName.get() == null ? "" : formName.get().strip();
        if (name.isBlank()) {
            main.dialogs().showError("Agent name is required.");
            return;
        }
        AgentRole role = formRole.get();
        ProviderType provider = formProvider.get();
        AgentProfile current = selected.get();
        List<String> tags = main.filterRepositoryTagsToActiveWorkspace(
                repositoryTags == null ? List.of() : repositoryTags);
        String ollamaModel = formOllamaModel.get() == null ? "" : formOllamaModel.get().strip();
        if (current == null) {
            AgentProfile agent = AgentProfile.create(
                    name, role, provider, "", "", "", true, tags, ollamaModel
            );
            profiles.add(agent);
            clearForm();
        } else {
            main.replaceAgent(current.withEditableFields(name, role, provider, tags, ollamaModel));
        }
        main.save();
    }

    public void deleteAgent(AgentProfile agent) {
        if (agent == null) {
            return;
        }
        if (main.agentRuns.stream().anyMatch(run -> run.agentProfileId().equals(agent.id()) && !run.status().terminal())) {
            main.dialogs().showError("An agent with an active run cannot be deleted. Cancel or wait for the run first.");
            return;
        }
        boolean ok = main.dialogs().confirm(
                "Delete agent",
                "Delete %s?".formatted(agent.name()),
                "Existing tasks assigned to this agent will become unassigned. Historical runs stay visible."
        );
        if (!ok) {
            return;
        }
        profiles.removeIf(a -> a.id().equals(agent.id()));
        for (AgentTask task : new ArrayList<>(main.agentTasks)) {
            if (agent.id().equals(task.assignedAgentId())) {
                main.replaceTask(task.withEditableFields(
                        task.taskPrefix(),
                        task.title(),
                        task.description(),
                        task.repositoryUrl(),
                        task.startingRef(),
                        null,
                        List.of()
                ));
            }
        }
        main.save();
    }

    public boolean isAgentFree(UUID agentId) {
        return main.agentRuns.stream()
                .filter(run -> run.agentProfileId().equals(agentId))
                .noneMatch(run -> !run.status().terminal());
    }
}
