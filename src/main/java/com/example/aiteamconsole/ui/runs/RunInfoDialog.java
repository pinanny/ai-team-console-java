package com.example.aiteamconsole.ui.runs;

import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.ui.FxTableHelpers;
import com.example.aiteamconsole.ui.MainViewModel;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Modal window with provider / external ids and summary for one run (opened from Runs table double-click).
 */
public final class RunInfoDialog {

    private RunInfoDialog() {
    }

    public static void open(MainViewModel main, Window owner, AgentRun run) {
        if (run == null) {
            return;
        }
        AgentRun latest = main.findRun(run.id()).orElse(run);

        Stage dlg = new Stage();
        dlg.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            dlg.initOwner(owner);
        }
        dlg.setTitle("Run details — " + main.taskTitle(latest.taskId()));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        int r0 = 0;
        grid.add(new Label("Provider"), 0, r0);
        grid.add(new Label(latest.provider().toString()), 1, r0++);

        grid.add(new Label("External agent"), 0, r0);
        String ea = latest.externalAgentId() == null ? "" : latest.externalAgentId().strip();
        grid.add(new Label(ea.isEmpty() ? "—" : ea), 1, r0++);

        grid.add(new Label("External run"), 0, r0);
        String er = latest.externalRunId() == null ? "" : latest.externalRunId().strip();
        grid.add(new Label(er.isEmpty() ? "—" : er), 1, r0++);

        grid.add(new Label("Head branch"), 0, r0);
        String hb = latest.expectedHeadBranch() == null ? "" : latest.expectedHeadBranch().strip();
        grid.add(new Label(hb.isEmpty() ? "—" : hb), 1, r0++);

        grid.add(new Label("Started"), 0, r0);
        grid.add(new Label(FxTableHelpers.formatOptionalTime(latest.startedAt())), 1, r0++);

        grid.add(new Label("Completed"), 0, r0);
        grid.add(new Label(FxTableHelpers.formatOptionalTime(latest.completedAt())), 1, r0++);

        grid.add(new Label("PR"), 0, r0);
        HBox prRow = new HBox(8);
        String prUrl = latest.pullRequestUrl() == null ? "" : latest.pullRequestUrl().strip();
        if (prUrl.isEmpty()) {
            Label dash = new Label("—");
            dash.setStyle("-fx-text-fill: #888;");
            prRow.getChildren().add(dash);
        } else {
            Hyperlink link = new Hyperlink(prUrl.length() > 72 ? prUrl.substring(0, 69) + "…" : prUrl);
            link.setTooltip(new Tooltip(prUrl));
            link.setOnAction(e -> main.openUrl(prUrl));
            prRow.getChildren().add(link);
        }
        grid.add(prRow, 1, r0++);

        Label sumCaption = new Label("Result summary");
        TextArea summary = new TextArea();
        String rs = latest.resultSummary();
        summary.setText(rs == null ? "" : rs);
        summary.setEditable(false);
        summary.setWrapText(true);
        summary.setPrefRowCount(8);
        VBox.setVgrow(summary, Priority.ALWAYS);

        ScrollPane sumScroll = new ScrollPane(summary);
        sumScroll.setFitToWidth(true);
        sumScroll.setPrefViewportHeight(200);

        Button close = new Button("Close");
        close.setDefaultButton(true);
        close.setOnAction(e -> dlg.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, close);

        VBox root = new VBox(14, grid, sumCaption, sumScroll, actions);
        root.setPadding(new Insets(16));
        VBox.setVgrow(sumScroll, Priority.ALWAYS);

        dlg.setScene(new Scene(root, 560, 480));
        dlg.show();
    }
}
