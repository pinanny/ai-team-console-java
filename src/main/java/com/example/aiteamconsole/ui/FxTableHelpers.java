package com.example.aiteamconsole.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

public final class FxTableHelpers {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private FxTableHelpers() {
    }

    public static String formatOptionalTime(Instant instant) {
        return instant == null ? "—" : TIME_FORMAT.format(instant);
    }

    public static DateTimeFormatter timeFormat() {
        return TIME_FORMAT;
    }

    public static <T> TableColumn<T, String> textColumn(String title, Function<T, String> extractor, int width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(value -> new SimpleStringProperty(extractor.apply(value.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    public static <T> void autoSizeColumnsToContent(TableView<T> table) {
        Runnable resize = () -> {
            for (TableColumn<T, ?> column : table.getColumns()) {
                if ("fixed-width".equals(column.getUserData())) {
                    continue;
                }
                double max = textWidth(column.getText()) + 28;
                for (T row : table.getItems()) {
                    Object cellValue = column.getCellData(row);
                    String text = cellValue == null ? "" : cellValue.toString();
                    max = Math.max(max, textWidth(text) + 28);
                }
                double clamped = Math.min(Math.max(max, 60), 520);
                column.setPrefWidth(clamped);
            }
        };
        table.getItems().addListener((ListChangeListener<T>) change -> resize.run());
        resize.run();
    }

    public static double textWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return new Text(text).getLayoutBounds().getWidth();
    }

    public static VBox padded(VBox content) {
        content.setPadding(new Insets(12));
        VBox.setVgrow(content.getChildren().getFirst(), Priority.ALWAYS);
        return content;
    }

    public static <T> void attachSortedItems(TableView<T> table, ObservableList<T> backing) {
        FilteredList<T> filtered = new FilteredList<>(backing, item -> true);
        SortedList<T> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
    }

    public static StringConverter<com.example.aiteamconsole.AgentProfile> agentConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(com.example.aiteamconsole.AgentProfile agent) {
                return agent == null ? "" : "%s (%s)".formatted(agent.name(), agent.role().label());
            }

            @Override
            public com.example.aiteamconsole.AgentProfile fromString(String string) {
                return null;
            }
        };
    }
}
