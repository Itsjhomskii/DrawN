package drawn.gui;

import drawn.gui.views.LoginView;
import drawn.gui.views.MainShell;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;

/** JavaFX customer entry point. The console application remains drawn.DrawN. */
public final class DrawNApplication extends Application {
    private final AppSession session = new AppSession();
    private AppServices services;
    private Stage stage;
    private Scene scene;

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("DrawN — Event Organizing System");
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        try {
            services = new AppServices(Path.of("data"));
            services.recoverPending();
            showLogin();
            stage.setMaximized(true);
            stage.show();
        } catch (IOException | RuntimeException ex) {
            new Alert(Alert.AlertType.ERROR, "DrawN could not prepare its customer data: " + ex.getMessage()).showAndWait();
        }
    }

    public void showLogin() { setRootForView(new LoginView(this, services, session).view()); }
    public void showShell() { setRootForView(new MainShell(this, services, session).view()); }

    public void setRootForView(javafx.scene.Parent root) {
        if (scene == null) {
            scene = new Scene(root, 1100, 700);
            scene.getStylesheets().add(getClass().getResource("/drawn/gui/styles/theme.css").toExternalForm());
            stage.setScene(scene);
        } else scene.setRoot(root);
    }

    @Override public void stop() { session.clear(); }
}
