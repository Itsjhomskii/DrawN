package drawn.gui;

import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URL;

/** Small standalone check for JavaFX startup and the packaged external stylesheet. */
public final class JavaFxSmokeTestApplication extends Application {
    @Override
    public void start(Stage stage) {
        URL stylesheet = getClass().getResource("/drawn/gui/styles/theme.css");
        if (stylesheet == null) {
            throw new IllegalStateException("DrawN theme.css was not found on the runtime classpath.");
        }

        Label brand = new Label("DrawN");
        brand.getStyleClass().add("smoke-test-title");
        Label status = new Label("JavaFX and external CSS are running.");
        status.getStyleClass().add("smoke-test-status");

        VBox root = new VBox(12, brand, status);
        root.setAlignment(Pos.CENTER);
        root.getStyleClass().add("smoke-test-root");

        Scene scene = new Scene(root, 800, 500);
        scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setTitle("DrawN JavaFX Smoke Test");
        stage.setMinWidth(800);
        stage.setMinHeight(500);
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();

        System.out.println("Loaded DrawN stylesheet: " + stylesheet);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
