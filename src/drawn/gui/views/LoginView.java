package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.gui.DrawNApplication;
import drawn.service.customer.CustomerAccessService;
import drawn.service.admin.AdminService;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
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
        Label error = AuthenticationFormSupport.errorLabel();
        TextField contact = AuthenticationFormSupport.textField("Email address or 10-digit phone number");
        contact.setId("auth-first-field");
        PasswordInput password = AuthenticationFormSupport.passwordField("Password");
        HBox contactInput = AuthenticationFormSupport.iconField("fi fi-rr-envelope", contact);
        DashboardIcon contactIcon = (DashboardIcon) contactInput.getChildren().get(0);
        Label countryCode = new Label("+63  |");
        countryCode.getStyleClass().add("country-prefix");
        countryCode.setVisible(false);
        countryCode.setManaged(false);
        contactInput.getChildren().add(1, countryCode);
        Label contactLabel = AuthenticationFormSupport.fieldLabel("Email address");
        contact.textProperty().addListener((observable, oldValue, value) -> {
            boolean phone = value != null && !value.isBlank() && value.matches("\\d+");
            contactIcon.setIconClass(phone ? "fi fi-rr-mobile-button" : "fi fi-rr-envelope");
            countryCode.setVisible(phone);
            countryCode.setManaged(phone);
            contactLabel.setText(phone ? "Phone number" : "Email address");
            contact.setPromptText(phone ? "Phone Number" : "Email address or phone number");
        });
        Button login = new Button("Log In"); login.getStyleClass().addAll("primary-button", "auth-submit-button"); login.setMaxWidth(Double.MAX_VALUE);
        Hyperlink register = new Hyperlink("Don't Have an account? Register");
        register.setMaxWidth(Double.MAX_VALUE);
        register.setAlignment(Pos.CENTER);
        register.setOnAction(event -> app.showRegister());
        login.setOnAction(event -> submit(contact, password, error));
        password.setOnAction(event -> submit(contact, password, error));
        contact.setOnAction(event -> password.focusInput());

        Label heading = new Label("Log in to your account"); heading.getStyleClass().add("auth-title");
        heading.setMaxWidth(Double.MAX_VALUE);
        heading.setAlignment(Pos.CENTER);
        VBox contactGroup = new VBox(6, contactLabel, contactInput);
        contactGroup.getStyleClass().add("auth-field-group");
        VBox form = new VBox(14, heading,
                contactGroup,
                AuthenticationFormSupport.labeledField("Password", password),
                error, login, register);
        form.getStyleClass().add("auth-form");

        Label brand = new Label("DrawN"); brand.getStyleClass().add("auth-hero-brand");
        Label heroTitle = new Label("Every big thing\nstarts small."); heroTitle.getStyleClass().add("auth-hero-title");
        Label heroSubtitle = new Label("Log in and continue growing."); heroSubtitle.getStyleClass().add("auth-hero-subtitle");
        VBox photoCopy = new VBox(14, brand, heroTitle, heroSubtitle);
        photoCopy.setAlignment(Pos.CENTER_LEFT);
        photoCopy.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        photoCopy.getStyleClass().add("auth-hero-copy");

        return AuthenticationFormSupport.overlayLayout(form, "/drawn/gui/images/login-event.jpg", false, photoCopy);
    }

    private void submit(TextField contact, PasswordInput password, Label error) {
        if (AdminService.isAdminLogin(contact.getText(), password.text())) {
            app.signInAdmin(contact.getText(), password.text());
            return;
        }
        try {
            CustomerAccessService.AuthenticatedCustomer authenticated = services.customerAccess().authenticate(contact.getText(), password.text());
            session.start(authenticated.account(), authenticated.customer());
            app.showShell();
        } catch (Exception ex) { error.setText(AuthenticationFormSupport.userMessage(ex)); }
    }

    static String userMessage(Exception exception) {
        return AuthenticationFormSupport.userMessage(exception);
    }
}
