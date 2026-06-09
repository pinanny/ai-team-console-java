package com.example.aiteamconsole.ui.tasks;

import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.AgentTaskPrompts;
import com.example.aiteamconsole.ui.MainViewModel;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Modal dialog that shows the open questions extracted from a Product Analyst spec
 * and lets the user pick which ones to convert into new DRAFT tasks.
 *
 * <p>Usage:
 * <pre>{@code
 *   OpenQuestionsDialog.open(main, primaryStage(), parentTask);
 * }</pre>
 */
public final class OpenQuestionsDialog {

    private OpenQuestionsDialog() {
    }

    /**
     * Opens the dialog for the given SPEC_REVIEW task.
     * Parses open questions from the task description, shows checkboxes,
     * and on confirmation calls {@link MainViewModel#createTasksFromOpenQuestions}.
     *
     * <p>Does nothing (shows an error) if no open questions are found.
     */
    public static void open(MainViewModel main, Window owner, AgentTask parentTask) {
        List<String> questions = AgentTaskPrompts.extractOpenQuestions(parentTask.description());
        if (questions.isEmpty()) {
            main.dialogs().showError(
                    "No open questions found in this task's description.\n\n"
                            + "The Product Analyst output must contain a "
                            + "\"### 4. Open questions\" section with bullet points.");
            return;
        }

        Stage dlg = new Stage();
        dlg.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            dlg.initOwner(owner);
        }
        dlg.setTitle("Create tickets from open questions");
        dlg.setMinWidth(560);
        dlg.setMaxWidth(760);

        // Header
        Label header = new Label("Select open questions to convert into DRAFT tasks:");
        header.setStyle("-fx-font-weight: bold;");
        Label sub = new Label("Source: " + parentTask.displayTitle());
        sub.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        // Checkboxes — one per question
        List<CheckBox> checkBoxes = new ArrayList<>();
        VBox checkBoxContainer = new VBox(6);
        for (String question : questions) {
            CheckBox cb = new CheckBox(question);
            cb.setSelected(true);   // all selected by default
            cb.setWrapText(true);
            cb.setMaxWidth(Double.MAX_VALUE);
            checkBoxes.add(cb);
            checkBoxContainer.getChildren().add(cb);
        }

        ScrollPane scroll = new ScrollPane(checkBoxContainer);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(Math.min(questions.size() * 42.0 + 16, 360));
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // Select all / none
        Button selectAll = new Button("Select all");
        Button selectNone = new Button("Select none");
        selectAll.setOnAction(e -> checkBoxes.forEach(cb -> cb.setSelected(true)));
        selectNone.setOnAction(e -> checkBoxes.forEach(cb -> cb.setSelected(false)));
        HBox selectionControls = new HBox(8, selectAll, selectNone);

        // Confirm / Cancel
        Button create = new Button("Create tickets");
        create.setDefaultButton(true);
        create.setStyle("-fx-font-weight: bold;");
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);

        cancel.setOnAction(e -> dlg.close());
        create.setOnAction(e -> {
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < checkBoxes.size(); i++) {
                if (checkBoxes.get(i).isSelected()) {
                    selected.add(questions.get(i));
                }
            }
            if (selected.isEmpty()) {
                main.dialogs().showError("Select at least one question to create a ticket.");
                return;
            }
            dlg.close();
            int count = main.createTasksFromOpenQuestions(parentTask, selected);
            main.dialogs().showInfo(
                    count + " ticket" + (count == 1 ? "" : "s") + " created as DRAFT.\n"
                            + "You can find them in the task list with prefix OQ-.");
        });

        HBox actionBar = new HBox(8, create, cancel);
        actionBar.setStyle("-fx-padding: 4 0 0 0;");

        VBox root = new VBox(10,
                header,
                sub,
                scroll,
                selectionControls,
                actionBar);
        root.setPadding(new Insets(16));

        dlg.setScene(new Scene(root));
        dlg.showAndWait();
    }
}
