package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.gui.DrawNApplication;
import drawn.service.customer.CustomerAccessService;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Centered customer sign-in view. */
public final class LoginView {
    private final DrawNApplication app;
    private final AppServices services;
    private final AppSession session;

    public LoginView(DrawNApplication app, AppServices services, AppSession session) {
        this.app = app; this.services = services; this.session = session;
    }

    public Parent view() {
        Label error = new Label(); error.getStyleClass().add("form-error"); error.setWrapText(true);
        TextField contact = new TextField(); contact.setPromptText("Email address or 10-digit phone number"); contact.getStyleClass().add("text-input"); contact.setId("login-contact");
        PasswordField password = new PasswordField(); password.setPromptText("Password"); password.getStyleClass().add("text-input"); password.setId("login-password");
        Button login = new Button("Log In"); login.getStyleClass().add("primary-button"); login.setMaxWidth(Double.MAX_VALUE); login.setId("login-button");
        Hyperlink register = new Hyperlink("Don't have an account? Register"); register.setId("login-register");
        register.setOnAction(event -> app.setRootForView(new RegisterView(app, services, session).view()));
        login.setOnAction(event -> submit(contact, password, error));
        password.setOnAction(event -> submit(contact, password, error));
        contact.setOnAction(event -> password.requestFocus());

        Label brand = new Label("DrawN"); brand.getStyleClass().add("brand-title");
        Label tagline = new Label("From drawing to doing."); tagline.getStyleClass().add("secondary-text");
        Label adminHint = new Label("Admin? Sign in with the admin email."); adminHint.getStyleClass().add("secondary-text");
        VBox card = new VBox(14, brand, tagline, new Label("Sign in to your account"), contact, password, error, login, register, adminHint);
        card.setAlignment(Pos.CENTER_LEFT); card.getStyleClass().add("login-card"); card.setMaxWidth(390);
        StackPane root = new StackPane(card); root.setAlignment(Pos.CENTER); root.getStyleClass().add("app-root");
        return root;
    }

    private void submit(TextField contact, PasswordField password, Label error) {
        if (app.signInAdmin(contact.getText(), password.getText())) return;
        try {
            CustomerAccessService.AuthenticatedCustomer authenticated = services.customerAccess().authenticate(contact.getText(), password.getText());
            session.start(authenticated.account(), authenticated.customer());
            app.showShell();
        } catch (Exception ex) { error.setText(userMessage(ex)); }
    }

    static String userMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Something went wrong. Please try again." : message;
    }
}
