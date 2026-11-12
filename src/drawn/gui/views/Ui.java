package drawn.gui.views;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;

/** Shared presentation helpers: styled controls, tables, dialogs and text-to-value parsing. No business rules. */
public final class Ui {
    /** Confirmation and notice popups; swapped out by the GUI smoke test so it can run unattended. */
    public interface Dialogs {
        boolean confirm(String header, String body);
        void info(String header, String body);
        void error(String header, String body);
    }

    private static Dialogs dialogs = new FxDialogs();

    private Ui() { }

    public static void useDialogs(Dialogs replacement) { dialogs = replacement; }
    static boolean confirm(String header, String body) { return dialogs.confirm(header, body); }
    static void info(String header, String body) { dialogs.info(header, body); }
    static void error(String header, String body) { dialogs.error(header, body); }

    // ---- Text ----

    static Label title(String text) { return styled(new Label(text), "page-title"); }
    static Label section(String text) { return styled(new Label(text), "section-title"); }
    static Label muted(String text) { Label label = styled(new Label(text), "secondary-text"); label.setWrapText(true); return label; }
    static Label errorLabel() { Label label = styled(new Label(), "form-error"); label.setWrapText(true); return label; }
    static Label value(String text) { Label label = new Label(text); label.setWrapText(true); return label; }

    static String money(BigDecimal amount) { return amount == null ? "0.00" : amount.setScale(2).toPlainString(); }
    static String php(BigDecimal amount) { return "PHP " + money(amount); }
    static String label(Enum<?> value) { return value == null ? "" : value.name().replace('_', ' '); }

    static String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Something went wrong. Please try again." : message;
    }

    // ---- Controls ----

    static TextField field(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("text-input");
        return field;
    }

    static Button primary(String text, Runnable action) { return button(text, "primary-button", action); }
    static Button secondary(String text, Runnable action) { return button(text, "secondary-button", action); }
    static Button danger(String text, Runnable action) { return button(text, "danger-button", action); }

    private static Button button(String text, String style, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }

    static HBox actions(Node... nodes) {
        HBox box = new HBox(10, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    static Region grow() { Region region = new Region(); HBox.setHgrow(region, Priority.ALWAYS); return region; }

    // ---- Layout ----

    /** Standard page: title, subtitle and vertically stacked content inside a scroll area. */
    static Node page(String heading, String subtitle, Node... content) {
        VBox body = new VBox(18);
        body.getChildren().add(new VBox(4, title(heading), muted(subtitle)));
        body.getChildren().addAll(content);
        body.getStyleClass().add("content-page");
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        return scroll;
    }

    static VBox card(Node... content) {
        VBox card = new VBox(12, content);
        card.getStyleClass().add("panel-card");
        return card;
    }

    /** Two-column label/control form. */
    static GridPane form() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(170);
        ColumnConstraints controls = new ColumnConstraints();
        controls.setHgrow(Priority.ALWAYS);
        controls.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, controls);
        return grid;
    }

    static void row(GridPane grid, String label, Node control) {
        int row = grid.getRowCount();
        Label text = muted(label);
        grid.add(text, 0, row);
        grid.add(control, 1, row);
        if (control instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
    }

    /** Read-only detail lines ("Label: value") for a selected record. */
    static GridPane details(String... labelValuePairs) {
        GridPane grid = form();
        grid.getColumnConstraints().get(0).setMinWidth(120);
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) row(grid, labelValuePairs[i], value(labelValuePairs[i + 1]));
        return grid;
    }

    static VBox statTile(String label, String value) {
        Label number = styled(new Label(value), "stat-value");
        VBox tile = new VBox(4, number, muted(label));
        tile.getStyleClass().add("stat-tile");
        HBox.setHgrow(tile, Priority.ALWAYS);
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    // ---- Tables ----

    static <T> TableView<T> table(List<T> rows, String emptyMessage) {
        TableView<T> table = new TableView<>(FXCollections.observableArrayList(rows));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(muted(emptyMessage));
        table.setPrefHeight(Math.min(420, 44 + Math.max(rows.size(), 3) * 34));
        table.setMinHeight(150);
        return table;
    }

    static <T> void column(TableView<T> table, String heading, Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(heading);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setSortable(false);
        table.getColumns().add(column);
    }

    // ---- Parsing (same rules as the console's input readers) ----

    /** Non-negative amount with up to 2 decimal places, like DrawN.readPrice. */
    static BigDecimal amount(String text, String fieldName) {
        try {
            BigDecimal value = new BigDecimal(text.trim());
            if (value.signum() >= 0 && value.scale() <= 2) return value;
        } catch (NumberFormatException ignored) {
            // Fall through to the shared message below.
        }
        throw new IllegalArgumentException(fieldName + ": enter a non-negative amount with up to 2 decimal places.");
    }

    /** Whole number in range, like DrawN.readInt. */
    static int whole(String text, int minimum, int maximum, String fieldName) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException ignored) {
            // Fall through to the shared message below.
        }
        throw new IllegalArgumentException(fieldName + ": enter a whole number from " + minimum + " to " + maximum + ".");
    }

    static LocalTime time(String text, String fieldName) {
        try {
            return LocalTime.parse(text.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(fieldName + ": enter a valid time in HH:mm format.");
        }
    }

    static String required(String text, String fieldName) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) throw new IllegalArgumentException(fieldName + " is required.");
        return value;
    }

    private static <T extends Node> T styled(T node, String style) { node.getStyleClass().add(style); return node; }

    /** Real JavaFX alerts styled with the app theme. */
    private static final class FxDialogs implements Dialogs {
        @Override public boolean confirm(String header, String body) {
            Alert alert = alert(Alert.AlertType.CONFIRMATION, header, body);
            alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
            return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
        }
        @Override public void info(String header, String body) { alert(Alert.AlertType.INFORMATION, header, body).showAndWait(); }
        @Override public void error(String header, String body) { alert(Alert.AlertType.ERROR, header, body).showAndWait(); }

        private Alert alert(Alert.AlertType type, String header, String body) {
            Alert alert = new Alert(type);
            alert.setTitle("DrawN");
            alert.setHeaderText(header);
            Label content = new Label(body);
            content.setWrapText(true);
            content.setMaxWidth(460);
            content.setPadding(new Insets(4, 0, 4, 0));
            alert.getDialogPane().setContent(content);
            var stylesheet = Ui.class.getResource("/drawn/gui/styles/theme.css");
            if (stylesheet != null) alert.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
            alert.getDialogPane().getStyleClass().add("app-dialog");
            return alert;
        }
    }
}
