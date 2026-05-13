package com.example.aiteamconsole;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * Lightweight multi-tag picker without external deps. Combines a free-form text field, a dropdown of known tags,
 * and a chip-style FlowPane that shows the current selection with an X to remove each chip.
 */
public final class TagPicker implements TagSelectionControl {
    private final Supplier<List<String>> knownTagsSupplier;
    private final ObservableList<String> selectedTags = FXCollections.observableArrayList();
    private final ComboBox<String> known = new ComboBox<>();
    private final TextField freeForm = new TextField();
    private final FlowPane chips = new FlowPane(6, 6);
    private final VBox root;

    public TagPicker(Supplier<List<String>> knownTagsSupplier) {
        this.knownTagsSupplier = knownTagsSupplier;
        known.setPromptText("Pick a known tag");
        known.getItems().setAll(safeKnown());
        known.setOnAction(event -> {
            String chosen = known.getValue();
            if (chosen != null && !chosen.isBlank()) {
                addTag(chosen);
                known.getSelectionModel().clearSelection();
            }
        });

        freeForm.setPromptText("Type a new tag, then Add");
        Button add = new Button("Add");
        add.setOnAction(event -> {
            String typed = freeForm.getText();
            if (typed == null) {
                return;
            }
            for (String tag : typed.split("[,\\s]+")) {
                addTag(tag);
            }
            freeForm.clear();
        });

        chips.setPrefWrapLength(360);
        renderChips();

        HBox row = new HBox(8, known, freeForm, add);
        HBox.setHgrow(freeForm, Priority.ALWAYS);
        root = new VBox(6, row, chips);
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public List<String> selected() {
        return new ArrayList<>(selectedTags);
    }

    @Override
    public void setSelected(List<String> tags) {
        selectedTags.clear();
        if (tags == null) {
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
        renderChips();
    }

    @Override
    public void refreshAvailable() {
        String current = known.getValue();
        known.getItems().setAll(safeKnown());
        if (current != null) {
            known.getSelectionModel().select(current);
        }
    }

    private void addTag(String raw) {
        if (raw == null) {
            return;
        }
        String norm = raw.strip().toLowerCase();
        if (norm.isEmpty() || selectedTags.contains(norm)) {
            return;
        }
        selectedTags.add(norm);
        renderChips();
    }

    private void renderChips() {
        chips.getChildren().clear();
        for (String tag : selectedTags) {
            Button remove = new Button("×");
            remove.setStyle("-fx-padding: 0 6 0 6;");
            remove.setOnAction(event -> {
                selectedTags.remove(tag);
                renderChips();
            });
            HBox chip = new HBox(4, new Label(tag), remove);
            chip.setStyle("-fx-background-color: #e8eef7; -fx-background-radius: 10; -fx-padding: 2 6 2 8;");
            chips.getChildren().add(chip);
        }
        if (selectedTags.isEmpty()) {
            Label empty = new Label("(no repository names/tags selected)");
            empty.setStyle("-fx-text-fill: #888;");
            chips.getChildren().add(empty);
        }
    }

    private List<String> safeKnown() {
        try {
            List<String> raw = knownTagsSupplier.get();
            return raw == null ? List.of() : raw;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
