package com.example.aiteamconsole.ui;

import com.example.aiteamconsole.RepositoryEntry;
import com.example.aiteamconsole.Workspace;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Create and edit {@linkplain Workspace workspaces}: each workspace lists which registered repos belong to that product.
 */
public final class WorkspaceManagerDialog {

    private WorkspaceManagerDialog() {
    }

    public static void open(MainViewModel main, Window owner) {
        Stage dlg = new Stage();
        dlg.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            dlg.initOwner(owner);
        }
        dlg.setTitle("Workspaces");
        dlg.setMinWidth(560);
        dlg.setMinHeight(420);

        ListView<Workspace> list = new ListView<>(FXCollections.observableList(new ArrayList<>(main.workspaces)));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Workspace item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    int n = item.repositoryIds() == null ? 0 : item.repositoryIds().size();
                    setText(item.name().isBlank() ? "(unnamed) — " + n + " repos" : item.name() + " — " + n + " repos");
                }
            }
        });

        TextField nameField = new TextField();
        nameField.setPromptText("e.g. Acme API, Mobile app");

        VBox checkArea = new VBox(6);
        ScrollPane checkScroll = new ScrollPane(checkArea);
        checkScroll.setFitToWidth(true);
        checkScroll.setPrefViewportHeight(220);

        Label detailHint = new Label(
                "Pick at least one repository. Tasks tagged with those repos appear when this workspace is selected in the main bar.");
        detailHint.setWrapText(true);
        detailHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #606060;");

        AtomicBoolean guardDetail = new AtomicBoolean(false);

        Runnable rebuildChecks = () -> {
            checkArea.getChildren().clear();
            Workspace sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) {
                return;
            }
            if (main.repositories.isEmpty()) {
                checkArea.getChildren().add(new Label("No repositories yet — add them under Cursor / GitHub settings."));
                return;
            }
            List<UUID> selected = new ArrayList<>(sel.repositoryIds());
            for (RepositoryEntry r : main.repositories) {
                CheckBox cb = new CheckBox(r.label() + " — " + r.url());
                cb.setSelected(selected.contains(r.id()));
                cb.setUserData(r.id());
                cb.setWrapText(true);
                cb.selectedProperty().addListener((obs, was, now) -> {
                    Workspace cur = list.getSelectionModel().getSelectedItem();
                    if (cur == null) {
                        return;
                    }
                    List<UUID> ids = new ArrayList<>(cur.repositoryIds());
                    UUID rid = (UUID) cb.getUserData();
                    if (Boolean.TRUE.equals(now)) {
                        if (!ids.contains(rid)) {
                            ids.add(rid);
                        }
                    } else {
                        ids.remove(rid);
                    }
                    Workspace next = cur.withFields(nameField.getText(), ids);
                    int idx = list.getSelectionModel().getSelectedIndex();
                    if (idx >= 0) {
                        list.getItems().set(idx, next);
                    }
                    main.replaceWorkspace(next);
                });
                checkArea.getChildren().add(cb);
            }
        };

        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n == null) {
                nameField.clear();
                checkArea.getChildren().clear();
                return;
            }
            guardDetail.set(true);
            try {
                nameField.setText(n.name());
            } finally {
                guardDetail.set(false);
            }
            rebuildChecks.run();
        });

        nameField.textProperty().addListener((obs, o, n) -> {
            if (guardDetail.get()) {
                return;
            }
            Workspace sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) {
                return;
            }
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0) {
                return;
            }
            String newName = n == null ? "" : n;
            Workspace next = sel.withFields(newName, sel.repositoryIds());
            list.getItems().set(idx, next);
            main.replaceWorkspace(next);
        });

        Button addBtn = new Button("New workspace");
        addBtn.setOnAction(e -> {
            Workspace w = Workspace.create("New workspace", List.of());
            main.addWorkspace(w);
            list.getItems().add(w);
            list.getSelectionModel().selectLast();
        });

        Button deleteBtn = new Button("Delete");
        deleteBtn.setOnAction(e -> {
            Workspace sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) {
                return;
            }
            if (!main.dialogs().confirm(
                    "Delete workspace",
                    "Delete «%s»?".formatted(sel.name().isBlank() ? "unnamed" : sel.name()),
                    "Tasks and runs are not deleted; only this grouping is removed.")) {
                return;
            }
            main.removeWorkspace(sel.id());
            list.getItems().remove(sel);
            list.getSelectionModel().clearSelection();
        });

        Button closeBtn = new Button("Close");
        closeBtn.setDefaultButton(true);
        closeBtn.setOnAction(e -> dlg.close());

        GridPane detail = new GridPane();
        detail.setHgap(8);
        detail.setVgap(8);
        detail.addRow(0, new Label("Name"), nameField);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        detail.add(detailHint, 1, 1);
        detail.add(checkScroll, 1, 2);
        GridPane.setHgrow(checkScroll, Priority.ALWAYS);

        HBox listToolbar = new HBox(8, addBtn, deleteBtn);
        VBox left = new VBox(8, new Label("Workspaces"), list, listToolbar);
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox right = new VBox(10, new Label("Details"), detail);
        VBox.setVgrow(detail, Priority.ALWAYS);

        HBox body = new HBox(16, left, right);
        HBox.setHgrow(left, Priority.SOMETIMES);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setPrefWidth(200);

        Label topHint = new Label(
                "Repositories stay global (Settings → Cursor / GitHub). Workspaces only slice the UI: Tasks, Runs, Domain memory, and Pixel Office (Settings → Pixel Office…).");
        topHint.setWrapText(true);
        topHint.setStyle("-fx-text-fill: #555;");

        VBox root = new VBox(12, topHint, body, new HBox(8, closeBtn));
        root.setPadding(new Insets(16));
        VBox.setVgrow(body, Priority.ALWAYS);

        Scene scene = new Scene(root);
        dlg.setScene(scene);
        dlg.show();
    }
}
