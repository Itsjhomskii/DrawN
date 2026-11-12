package drawn.gui.admin;

import drawn.gui.DrawNApplication;
import javafx.application.Application;
import javafx.scene.control.Alert;

/** Legacy admin launcher; opens DrawN's shared login for both admin and customers. */
public final class AdminApplication {
    private AdminApplication() { }

    public static void main(String[] args) {
        Application.launch(DrawNApplication.class, args);
    }

    static void error(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("DrawN Admin");
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "Please try again." : ex.getMessage();
    }
}
