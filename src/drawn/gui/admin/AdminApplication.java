package drawn.gui.admin;

import drawn.model.event.Event;
import drawn.service.admin.AdminService;
import drawn.service.event.EventService;
import drawn.storage.EventRepository;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.Parent;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Independent JavaFX launcher for the administrator dashboard. */
public final class AdminApplication extends Application {
    private Stage stage;
    private final AdminService admin = new AdminService();
    private EventService events;

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("DrawN — Admin");
        stage.setMinWidth(900);
        stage.setMinHeight(640);
        try {
            events = new EventService(new EventRepository(Path.of(System.getProperty("drawn.dataDir", "data"))));
            setRoot(new AdminLoginView(this).view());
            stage.show();
        } catch (RuntimeException ex) {
            error("DrawN could not prepare the admin dashboard", message(ex));
        }
    }

    void signIn(String username, String password) {
        if (!AdminService.isAdminLogin(username, password)) {
            error("Sign-in failed", "The admin username or password is incorrect.");
            return;
        }
        setRoot(new AdminView(this, admin).view());
        stage.setMaximized(true);
    }

    List<Event> approvedPublicEvents() throws IOException { return events.publicApprovedEvents(); }
    AdminService admin() { return admin; }

    void setRoot(Parent root) {
        if (stage.getScene() == null) {
            Scene scene = new Scene(root, 1100, 760);
            scene.getStylesheets().add(getClass().getResource("/drawn/gui/admin/admin.css").toExternalForm());
            stage.setScene(scene);
        } else stage.getScene().setRoot(root);
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

    public static void main(String[] args) { launch(args); }
}
