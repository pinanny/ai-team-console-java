package com.example.aiteamconsole;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Multi-select for task repository tags: only registered repositories, no free typing.
 * Dropdown lists repositories not yet chosen; it hides when every repository is selected.
 * Selected items appear as removable chips to the right of the dropdown.
 */
public final class TaskRepositoryTagPicker implements TagSelectionControl {
    private final ObservableList<RepositoryEntry> repositories;
    private final ObservableList<String> selectedTags = FXCollections.observableArrayList();
    private final ComboBox<RepositoryEntry> combo = new ComboBox<>();
    private final FlowPane chips = new FlowPane(6, 6);
    private final HBox row;

    public TaskRepositoryTagPicker(ObservableList<RepositoryEntry> repositories) {
        this.repositories = repositories;
        combo.setPromptText("Choose repository…");
        combo.setCellFactory(lv -> repositoryLabelCell());
        combo.setButtonCell(repositoryLabelCell());
        combo.setOnAction(event -> {
            RepositoryEntry chosen = combo.getValue();
            if (chosen != null) {
                String tag = chosen.tag();
                if (!tag.isBlank() && !selectedTags.contains(tag)) {
                    selectedTags.add(tag);
                }
            }
            combo.setValue(null);
            syncFromRepositories();
            renderChips();
        });

        chips.setPrefWrapLength(480);
        HBox.setHgrow(chips, Priority.ALWAYS);

        ListChangeListener<RepositoryEntry> reposListener = c -> {
            syncFromRepositories();
            renderChips();
        };
        repositories.addListener(reposListener);

        row = new HBox(8, combo, chips);
        syncFromRepositories();
        renderChips();
    }

    private static ListCell<RepositoryEntry> repositoryLabelCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(RepositoryEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        };
    }

    @Override
    public Node node() {
        return row;
    }

    @Override
    public List<String> selected() {
        return new ArrayList<>(selectedTags);
    }

    @Override
    public void setSelected(List<String> tags) {
        selectedTags.clear();
        if (tags == null) {
            syncFromRepositories();
            renderChips();
            return;
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String t : tags) {
            if (t == null) {
                continue;
            }
            String norm = t.strip().toLowerCase();
            if (!norm.isEmpty()) {
                ordered.add(norm);
            }
        }
        selectedTags.addAll(ordered);
        syncFromRepositories();
        renderChips();
    }

    @Override
    public void refreshAvailable() {
        syncFromRepositories();
        renderChips();
    }

    /**
     * Drops selections that no longer match a repository tag, refreshes combo items,
     * and hides the combo when there is nothing left to pick.
     */
    private void syncFromRepositories() {
        selectedTags.removeIf(tag -> repositories.stream().noneMatch(r -> r.tag().equals(tag)));

        List<RepositoryEntry> remaining = new ArrayList<>();
        for (RepositoryEntry repo : repositories) {
            String tag = repo.tag();
            if (!tag.isBlank() && !selectedTags.contains(tag)) {
                remaining.add(repo);
            }
        }
        combo.getItems().setAll(remaining);
        RepositoryEntry current = combo.getValue();
        if (current != null && remaining.stream().noneMatch(r -> r.id().equals(current.id()))) {
            combo.setValue(null);
        }
        boolean showCombo = !remaining.isEmpty();
        combo.setVisible(showCombo);
        combo.setManaged(showCombo);
    }

    private void renderChips() {
        chips.getChildren().clear();
        for (String tag : selectedTags) {
            Button remove = new Button("×");
            remove.setStyle("-fx-padding: 0 6 0 6;");
            remove.setOnAction(event -> {
                selectedTags.remove(tag);
                syncFromRepositories();
                renderChips();
            });
            String chipText = repositories.stream()
                    .filter(r -> r.tag().equals(tag))
                    .findFirst()
                    .map(RepositoryEntry::label)
                    .orElse(tag);
            HBox chip = new HBox(4, new Label(chipText), remove);
            chip.setStyle("-fx-background-color: #e8eef7; -fx-background-radius: 10; -fx-padding: 2 6 2 8;");
            chips.getChildren().add(chip);
        }
        if (selectedTags.isEmpty()) {
            Label empty = new Label("(no repositories selected)");
            empty.setStyle("-fx-text-fill: #888;");
            chips.getChildren().add(empty);
        }
    }
}
