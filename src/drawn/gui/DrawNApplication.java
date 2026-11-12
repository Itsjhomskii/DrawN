package drawn.gui;

import drawn.gui.views.LoginView;
import drawn.gui.views.MainShell;
import drawn.gui.views.RegisterView;
import drawn.gui.admin.AdminView;
import drawn.service.admin.AdminService;
import drawn.model.event.Event;
import javafx.application.Application;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** JavaFX customer entry point. The console application remains drawn.DrawN. */
public final class DrawNApplication extends Application {
    private final AppSession session = new AppSession();
    private final AdminService adminService = new AdminService();
    private AppServices services;
    private Stage stage;
    private Scene scene;
    private boolean authTransitionRunning;
    private static final Duration AUTH_TRANSITION_HALF = Duration.millis(140);

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("DrawN — Event Organizing System");
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        try {
            services = new AppServices(Path.of(System.getProperty("drawn.dataDir", "data")));
            services.recoverPending();
            showLogin();
            stage.setMaximized(true);
            stage.show();
        } catch (IOException | RuntimeException ex) {
            new Alert(Alert.AlertType.ERROR, "DrawN could not prepare its customer data: " + ex.getMessage()).showAndWait();
        }
    }

    public void showLogin() { switchAuthForm(new LoginView(this, services, session).view(), false); }
    public void showRegister() { switchAuthForm(new RegisterView(this, services, session).view(), true); }
    public void showShell() { setRootForView(new MainShell(this, services, session).view()); }

    public boolean signInAdmin(String email, String password) {
        if (!AdminService.isAdminLogin(email, password)) return false;
        setRootForView(new AdminView(new AdminView.Host() {
            @Override public List<Event> approvedPublicEvents() throws IOException {
                return services.eventService().publicApprovedEvents();
            }
            @Override public void setRoot(Parent root) { setRootForView(root); }
            @Override public void logout() { showLogin(); }
        }, adminService).view());
        if (!scene.getStylesheets().contains(getClass().getResource("/drawn/gui/admin/admin.css").toExternalForm()))
            scene.getStylesheets().add(getClass().getResource("/drawn/gui/admin/admin.css").toExternalForm());
        stage.setMaximized(true);
        return true;
    }

    private void switchAuthForm(Parent nextRoot, boolean toRegister) {
        if (scene == null) { setRootForView(nextRoot); focusFirstField(nextRoot); return; }
        if (authTransitionRunning) return;
        Node oldCard = findById(scene.getRoot(), "auth-form-card");
        Node newCard = findById(nextRoot, "auth-form-card");
        if (oldCard == null || newCard == null) { setRootForView(nextRoot); return; }

        authTransitionRunning = true;
        double direction = toRegister ? 1 : -1;
        oldCard.setMouseTransparent(true);
        FadeTransition fadeOut = new FadeTransition(AUTH_TRANSITION_HALF, oldCard);
        fadeOut.setFromValue(1); fadeOut.setToValue(0); fadeOut.setInterpolator(Interpolator.EASE_BOTH);
        TranslateTransition slideOut = new TranslateTransition(AUTH_TRANSITION_HALF, oldCard);
        slideOut.setFromX(0); slideOut.setToX(direction * 24); slideOut.setInterpolator(Interpolator.EASE_BOTH);
        ParallelTransition exit = new ParallelTransition(fadeOut, slideOut);
        exit.setOnFinished(event -> {
            scene.setRoot(nextRoot);
            newCard.setOpacity(0);
            newCard.setTranslateX(-direction * 24);
            newCard.setMouseTransparent(false);
            FadeTransition fadeIn = new FadeTransition(AUTH_TRANSITION_HALF, newCard);
            fadeIn.setFromValue(0); fadeIn.setToValue(1); fadeIn.setInterpolator(Interpolator.EASE_BOTH);
            TranslateTransition slideIn = new TranslateTransition(AUTH_TRANSITION_HALF, newCard);
            slideIn.setFromX(-direction * 24); slideIn.setToX(0); slideIn.setInterpolator(Interpolator.EASE_BOTH);
            ParallelTransition enter = new ParallelTransition(fadeIn, slideIn);
            enter.setOnFinished(done -> {
                newCard.setOpacity(1); newCard.setTranslateX(0);
                authTransitionRunning = false;
                focusFirstField(nextRoot);
            });
            enter.play();
        });
        exit.play();
    }

    private static void focusFirstField(Parent root) {
        Node first = findById(root, "auth-first-field");
        if (first != null) javafx.application.Platform.runLater(first::requestFocus);
    }

    private static Node findById(Node node, String id) {
        if (id.equals(node.getId())) return node;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node match = findById(child, id);
                if (match != null) return match;
            }
        }
        return null;
    }

    public void setRootForView(Parent root) {
        if (scene == null) {
            scene = new Scene(root, 1100, 700);
            scene.getStylesheets().add(getClass().getResource("/drawn/gui/styles/theme.css").toExternalForm());
            stage.setScene(scene);
        } else scene.setRoot(root);
    }

    @Override public void stop() { session.clear(); }
}
