package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.gui.DrawNApplication;
import drawn.model.user.ContactMethod;
import drawn.service.customer.CustomerAccessService;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;

import java.math.BigDecimal;

/** Registration and required initial wallet funding; persistence is delegated to AccountStore. */
public final class RegisterView {
    private final DrawNApplication app;
    private final AppServices services;
    private final AppSession session;
    public RegisterView(DrawNApplication app, AppServices services, AppSession session) { this.app=app; this.services=services; this.session=session; }

    public Parent view() {
        Label error = AuthenticationFormSupport.errorLabel();
        TextField first = AuthenticationFormSupport.textField("First name");
        first.setId("auth-first-field");
        TextField last = AuthenticationFormSupport.textField("Last name");
        first.setMaxWidth(Double.MAX_VALUE); last.setMaxWidth(Double.MAX_VALUE);
        TextField contact = AuthenticationFormSupport.textField("Email address");
        PasswordInput password = AuthenticationFormSupport.passwordField("Password");
        HBox contactInput = AuthenticationFormSupport.iconField("fi fi-rr-envelope", contact);
        DashboardIcon contactIcon = (DashboardIcon) contactInput.getChildren().get(0);
        Label contactLabel = AuthenticationFormSupport.fieldLabel("Email address");
        Label countryCode = new Label("+63  |"); countryCode.getStyleClass().add("country-prefix");
        countryCode.setVisible(false); countryCode.setManaged(false);
        contactInput.getChildren().add(1, countryCode);
        DatePicker dob = new DatePicker(); dob.setPromptText("Date of birth"); dob.getStyleClass().add("text-input");
        TextField funding = AuthenticationFormSupport.textField("Minimum PHP 500");
        ToggleGroup contactMethod = new ToggleGroup();
        DashboardIcon emailIcon = new DashboardIcon("fi fi-rs-check-circle", 18);
        DashboardIcon phoneIcon = new DashboardIcon("fi fi-rs-circle", 18);
        ToggleButton email = new ToggleButton("Email", emailIcon); email.setToggleGroup(contactMethod); email.setSelected(true);
        ToggleButton phone = new ToggleButton("Phone", phoneIcon); phone.setToggleGroup(contactMethod);
        email.getStyleClass().add("contact-method-toggle");
        phone.getStyleClass().add("contact-method-toggle");
        contactMethod.selectedToggleProperty().addListener((observable, oldToggle, selected) -> {
            emailIcon.setIconClass(selected == email ? "fi fi-rs-check-circle" : "fi fi-rs-circle");
            phoneIcon.setIconClass(selected == phone ? "fi fi-rs-check-circle" : "fi fi-rs-circle");
        });
        phone.setOnAction(e -> {
            if (!phone.isSelected()) phone.setSelected(true);
            contact.setPromptText("Phone Number");
            contactLabel.setText("Phone number");
            contactIcon.setIconClass("fi fi-rr-mobile-button");
            countryCode.setVisible(true); countryCode.setManaged(true);
        });
        email.setOnAction(e -> {
            if (!email.isSelected()) email.setSelected(true);
            contact.setPromptText("Email address");
            contactLabel.setText("Email address");
            contactIcon.setIconClass("fi fi-rr-envelope");
            countryCode.setVisible(false); countryCode.setManaged(false);
        });
        Button create = new Button("Create Account"); create.setMaxWidth(Double.MAX_VALUE); create.getStyleClass().addAll("primary-button", "auth-submit-button");
        create.setOnAction(e -> submit(first,last,dob,email.isSelected() ? ContactMethod.EMAIL : ContactMethod.PHONE,contact,password,funding,error));
        Hyperlink back = new Hyperlink("Already have an account? Log-in");
        back.setMaxWidth(Double.MAX_VALUE); back.setAlignment(Pos.CENTER);
        back.setOnAction(e -> app.showLogin());
        Label heading = new Label("Create account"); heading.getStyleClass().add("auth-title");
        heading.setMaxWidth(Double.MAX_VALUE); heading.setAlignment(Pos.CENTER);
        HBox firstLast = new HBox(12,
                AuthenticationFormSupport.labeledField("First name", first),
                AuthenticationFormSupport.labeledField("Last name", last));
        HBox.setHgrow(firstLast.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(firstLast.getChildren().get(1), Priority.ALWAYS);
        HBox.setHgrow(first, Priority.ALWAYS); HBox.setHgrow(last, Priority.ALWAYS);
        HBox contactMethodRow = new HBox(14, email, phone);
        VBox contactGroup = new VBox(6, contactLabel, contactInput);
        contactGroup.getStyleClass().add("auth-field-group");
        VBox fundingGroup = AuthenticationFormSupport.labeledField("Initial wallet funding", AuthenticationFormSupport.iconField("fi fi-rs-peso-sign", funding));
        VBox card = new VBox(14, heading,
                AuthenticationFormSupport.subtitle("Set up your customer account and initial wallet balance."),
                firstLast,
                AuthenticationFormSupport.labeledField("Date of birth", dob),
                AuthenticationFormSupport.labeledField("Contact preference", contactMethodRow),
                contactGroup,
                AuthenticationFormSupport.labeledField("Password", password),
                fundingGroup,
                error, create, back);
        card.setSpacing(7);
        card.getStyleClass().add("auth-form-register");
        card.getStyleClass().add("auth-form");
        Label brand = new Label("DrawN"); brand.getStyleClass().add("auth-hero-brand");
        Label heroTitle = new Label("Every big thing\nstarts small."); heroTitle.getStyleClass().add("auth-hero-title");
        Label heroSubtitle = new Label("Create your account and make room for memorable events."); heroSubtitle.getStyleClass().add("auth-hero-subtitle");
        VBox photoCopy = new VBox(14, brand, heroTitle, heroSubtitle);
        photoCopy.setAlignment(Pos.CENTER_LEFT);
        photoCopy.getStyleClass().add("auth-hero-copy");
        HBox.setHgrow(photoCopy, Priority.ALWAYS);
        return AuthenticationFormSupport.overlayLayout(card, "/drawn/gui/images/register-books.jpg", true, photoCopy);
    }
    private void submit(TextField first, TextField last, DatePicker dob, ContactMethod method, TextField contact,
                        PasswordInput password, TextField funding, Label error) {
        try {
            BigDecimal amount = new BigDecimal(funding.getText().trim());
            CustomerAccessService.AuthenticatedCustomer created = services.customerAccess().register(first.getText(), last.getText(), dob.getValue(), method, contact.getText(), password.text(), amount);
            session.start(created.account(), created.customer()); app.showShell();
        } catch (Exception ex) { error.setText(AuthenticationFormSupport.userMessage(ex)); }
    }
}
