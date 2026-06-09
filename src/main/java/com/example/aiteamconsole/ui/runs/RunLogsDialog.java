package com.example.aiteamconsole.ui.runs;

import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.RunLogEntry;
import com.example.aiteamconsole.ui.FxTableHelpers;
import com.example.aiteamconsole.ui.MainViewModel;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Separate window for the selected run's log lines and result summary (opened from Runs tab).
 */
public final class RunLogsDialog {

    private RunLogsDialog() {
    }

    public static void open(RunsViewModel vm, Window owner) {
        if (vm.selected.get() == null) {
            vm.main().dialogs().showError("Select a run first.");
            return;
        }
        vm.refreshSelectedLogs();

        MainViewModel main = vm.main();
        Stage dlg = new Stage();
        dlg.initModality(Modality.NONE);
        if (owner != null) {
            dlg.initOwner(owner);
        }
        dlg.setMinWidth(520);
        dlg.setMinHeight(360);

        ListView<RunLogEntry> logList = new ListView<>(vm.selectedRunLogs);
        logList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(RunLogEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(FxTableHelpers.timeFormat().format(item.timestamp()) + "  " + item.message());
            }
        });

        Label resultSummary = new Label();
        resultSummary.setWrapText(true);
        resultSummary.setStyle("-fx-text-fill: #444;");

        Runnable refreshHeader = () -> {
            AgentRun run = vm.selected.get();
            if (run == null) {
                dlg.setTitle("Run log");
                resultSummary.setText("");
                return;
            }
            AgentRun latest = main.findRun(run.id()).orElse(run);
            dlg.setTitle("Run log — %s".formatted(main.taskTitle(latest.taskId())));
            String rs = latest.resultSummary();
            resultSummary.setText(rs == null || rs.isBlank() ? "" : "Result:\n" + rs);
        };
        refreshHeader.run();

        ChangeListener<AgentRun> onSelection = (obs, previous, current) -> {
            vm.refreshSelectedLogs();
            refreshHeader.run();
        };
        vm.selected.addListener(onSelection);

        ListChangeListener<AgentRun> runsListener = c -> {
            if (vm.selected.get() != null) {
                Platform.runLater(refreshHeader::run);
            }
        };
        main.agentRuns.addListener(runsListener);

        dlg.setOnHidden(e -> {
            vm.selected.removeListener(onSelection);
            main.agentRuns.removeListener(runsListener);
        });

        Button refreshStatus = new Button("Refresh status");
        refreshStatus.setOnAction(e -> vm.refreshRunStatus());

        Button close = new Button("Close");
        close.setOnAction(e -> dlg.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, refreshStatus, close);

        Label hint = new Label(
                "Window stays open while you pick another run in the table; log follows selection. "
                        + "Use Refresh status to poll the provider for new log lines.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #555;");

        VBox.setVgrow(logList, Priority.ALWAYS);
        logList.setPrefHeight(320);

        VBox root = new VBox(10, hint, new Label("Log"), logList, new Label("Summary"), resultSummary, actions);
        root.setPadding(new Insets(16));

        dlg.setScene(new Scene(root));
        dlg.show();
    }
}
