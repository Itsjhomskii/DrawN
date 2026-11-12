package drawn.gui.views;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

/** Password field with a hidden-by-default, in-place reveal toggle. */
final class PasswordInput extends HBox {
    private final PasswordField maskedField = new PasswordField();
    private final TextField visibleField = new TextField();
    private final StackPane editor = new StackPane(maskedField, visibleField);
    private final Button visibilityToggle = new Button();

    PasswordInput(String prompt) {
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        getStyleClass().add("password-input-row");

        DashboardIcon lock = new DashboardIcon("fi fi-rr-lock", 17);
        maskedField.setPromptText(prompt);
        visibleField.setPromptText(prompt);
        maskedField.getStyleClass().add("auth-password-field");
        visibleField.getStyleClass().add("auth-password-field");
        visibleField.textProperty().bindBidirectional(maskedField.textProperty());
        visibleField.setVisible(false);
        visibleField.setManaged(false);
        editor.setAlignment(Pos.CENTER_LEFT);
        editor.getStyleClass().add("password-editor");
        editor.getChildren().forEach(field -> HBox.setHgrow(field, Priority.ALWAYS));

        visibilityToggle.setGraphic(new DashboardIcon("fi fi-rr-eye-crossed", 17));
        visibilityToggle.setTooltip(new Tooltip("Show password"));
        visibilityToggle.setAccessibleText("Show password");
        visibilityToggle.getStyleClass().add("password-visibility-button");
        visibilityToggle.setOnAction(event -> toggleVisibility());

        getChildren().addAll(lock, editor, visibilityToggle);
        HBox.setHgrow(editor, Priority.ALWAYS);
        maskedField.focusedProperty().addListener((observable, oldValue, focused) -> setFocusStyle(focused));
        visibleField.focusedProperty().addListener((observable, oldValue, focused) -> setFocusStyle(focused));
    }

    String text() { return maskedField.getText(); }

    void focusInput() {
        (visibleField.isVisible() ? visibleField : maskedField).requestFocus();
    }

    void setOnAction(EventHandler<ActionEvent> handler) {
        maskedField.setOnAction(handler);
        visibleField.setOnAction(handler);
    }

    private void toggleVisibility() {
        boolean reveal = !visibleField.isVisible();
        int caretPosition = reveal ? maskedField.getCaretPosition() : visibleField.getCaretPosition();
        maskedField.setVisible(!reveal);
        maskedField.setManaged(!reveal);
        visibleField.setVisible(reveal);
        visibleField.setManaged(reveal);
        TextField activeField = reveal ? visibleField : maskedField;
        activeField.positionCaret(Math.min(caretPosition, activeField.getLength()));
        activeField.requestFocus();
        visibilityToggle.setGraphic(new DashboardIcon(reveal ? "fi fi-rr-eye" : "fi fi-rr-eye-crossed", 17));
        String action = reveal ? "Hide" : "Show";
        visibilityToggle.setTooltip(new Tooltip(action + " password"));
        visibilityToggle.setAccessibleText(action + " password");
    }

    private void setFocusStyle(boolean focused) {
        if (focused) {
            if (!getStyleClass().contains("input-focused")) getStyleClass().add("input-focused");
        } else if (!maskedField.isFocused() && !visibleField.isFocused()) {
            getStyleClass().remove("input-focused");
        }
    }
}
