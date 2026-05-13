package com.example.aiteamconsole.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;

import java.util.Optional;

public final class AlertUserDialogs implements UserDialogs {

    @Override
    public void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("AI Team Console");
        alert.setHeaderText("Action failed");
        String full = message == null ? "" : message;
        if (full.length() > 480) {
            alert.setContentText(full.substring(0, 480) + "…\n\nFull text is in «Details» below.");
            TextArea area = new TextArea(full);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(18);
            ScrollPane scroll = new ScrollPane(area);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(280);
            alert.getDialogPane().setExpandableContent(scroll);
            alert.getDialogPane().setExpanded(true);
        } else {
            alert.setContentText(full);
        }
        alert.getDialogPane().setPrefWidth(560);
        alert.showAndWait();
    }

    @Override
    public void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("AI Team Console");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    @Override
    public boolean confirm(String title, String headerText, String contentText) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(title);
        confirm.setHeaderText(headerText);
        confirm.setContentText(contentText);
        Optional<ButtonType> answer = confirm.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }
}
