package com.example.aiteamconsole.ui;

import com.example.aiteamconsole.RepositoryEntry;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Multi-select of registered repositories only (no free-form tags). Values are
 * {@link RepositoryEntry#tag()} keys, stored on {@link com.example.aiteamconsole.AgentProfile}
 * or {@link com.example.aiteamconsole.AgentTask} as {@code repositoryTags}.
 * Empty selection means "all repositories in the registry" for URL resolution.
 */
public final class RegistryRepositoryPicker {

    private final VBox root = new VBox(6);
    private final Label caption = new Label();
    private final VBox checkBoxRows = new VBox(4);
    private final ScrollPane scroll = new ScrollPane(checkBoxRows);
    private final ObservableList<RepositoryEntry> source;
    private final Supplier<String> captionSupplier;

    public RegistryRepositoryPicker(ObservableList<RepositoryEntry> repositories) {
        this(repositories, () -> "Repositories (from GitHub registry; none checked = all in scope):");
    }

    public RegistryRepositoryPicker(ObservableList<RepositoryEntry> repositories, Supplier<String> captionSupplier) {
        this.source = repositories;
        this.captionSupplier = captionSupplier == null ? () -> "" : captionSupplier;
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(140);
        scroll.setMinHeight(100);
        root.getChildren().addAll(caption, scroll);
        repositories.addListener((ListChangeListener<RepositoryEntry>) c -> refresh());
        if (repositories instanceof FilteredList<RepositoryEntry> filtered) {
            filtered.predicateProperty().addListener((obs, o, n) -> refresh());
        }
        refresh();
    }

    /** Rebuild checkboxes from the current scoped repository list (e.g. after workspace change). */
    public void refresh() {
        List<String> keep = getSelectedTags();
        if (captionSupplier != null) {
            caption.setText(captionSupplier.get());
        }
        rebuild(source);
        setSelectedTags(keep);
    }

    private void rebuild(ObservableList<RepositoryEntry> repositories) {
        checkBoxRows.getChildren().clear();
        for (RepositoryEntry r : repositories) {
            String tag = r.tag();
            if (tag.isBlank()) {
                continue;
            }
            CheckBox cb = new CheckBox(r.label() + " — " + r.url());
            cb.setUserData(tag);
            cb.setWrapText(true);
            checkBoxRows.getChildren().add(cb);
        }
    }

    public Node node() {
        return root;
    }

    public void setSelectedTags(List<String> normalizedTags) {
        Set<String> want = new HashSet<>(normalizedTags == null ? List.of() : normalizedTags);
        for (Node n : checkBoxRows.getChildren()) {
            if (n instanceof CheckBox cb && cb.getUserData() instanceof String tag) {
                cb.setSelected(want.contains(tag));
            }
        }
    }

    public List<String> getSelectedTags() {
        return checkBoxRows.getChildren().stream()
                .filter(CheckBox.class::isInstance)
                .map(n -> (CheckBox) n)
                .filter(CheckBox::isSelected)
                .map(cb -> (String) cb.getUserData())
                .toList();
    }
}
