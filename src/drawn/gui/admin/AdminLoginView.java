package drawn.gui.admin;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

final class AdminLoginView {
    private final AdminApplication app;

    AdminLoginView(AdminApplication app) { this.app = app; }

    Parent view() {
        Label eyebrow = new Label("DRAWN  /  ADMINISTRATION");
        eyebrow.getStyleClass().add("admin-eyebrow");
        Label title = new Label("Admin sign in");
        title.getStyleClass().add("admin-title");
        Label subtitle = new Label("Sign in with your administrator credentials to manage events.");
        subtitle.getStyleClass().add("admin-subtitle");
        TextField username = new TextField();
        username.setPromptText("Admin username");
        username.setId("admin-login-username");
        username.getStyleClass().add("admin-input");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        password.setId("admin-login-password");
        password.getStyleClass().add("admin-input");
        Button signIn = new Button("Sign in");
        signIn.setId("admin-login-submit");
        signIn.getStyleClass().add("admin-primary");
        signIn.setMaxWidth(Double.MAX_VALUE);
        signIn.setOnAction(event -> app.signIn(username.getText(), password.getText()));
        password.setOnAction(event -> signIn.fire());
        VBox card = new VBox(16, eyebrow, title, subtitle, username, password, signIn);
        card.getStyleClass().add("admin-login-card");
        card.setMaxWidth(440);
        card.setPadding(new Insets(38));
        VBox shell = new VBox(card);
        shell.setId("admin-login-screen");
        shell.setAlignment(Pos.CENTER);
        shell.getStyleClass().add("admin-shell");
        return shell;
    }
}
