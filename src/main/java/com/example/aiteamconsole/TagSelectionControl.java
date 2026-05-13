package com.example.aiteamconsole;

import javafx.scene.Node;

import java.util.List;

/** Shared surface for task repository tags vs. free-form agent tag pickers. */
public interface TagSelectionControl {
    Node node();

    List<String> selected();

    void setSelected(List<String> tags);

    void refreshAvailable();
}
