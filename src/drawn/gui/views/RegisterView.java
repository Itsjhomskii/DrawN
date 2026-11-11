package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.gui.DrawNApplication;
import drawn.model.user.ContactMethod;
import drawn.service.customer.CustomerAccessService;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;

/** Registration and required initial wallet funding; persistence is delegated to AccountStore. */
public final class RegisterView {
    private final DrawNApplication app;
    private final AppServices services;
    private final AppSession session;
    public RegisterView(DrawNApplication app, AppServices services, AppSession session) { this.app=app; this.services=services; this.session=session; }

    public Parent view() {
        Label error = new Label(); error.getStyleClass().add("form-error"); error.setWrapText(true);
        TextField first = field("First name"), last = field("Last name"), contact = field("Email address");
        PasswordField password = new PasswordField(); password.setPromptText("Password"); password.getStyleClass().add("text-input");
        DatePicker dob = new DatePicker(); dob.setPromptText("Date of birth"); dob.getStyleClass().add("text-input");
        TextField funding = field("Initial wallet funding (minimum PHP 500)");
        ToggleGroup contactMethod = new ToggleGroup();
        RadioButton email = new RadioButton("Email"); email.setToggleGroup(contactMethod); email.setSelected(true);
        RadioButton phone = new RadioButton("Phone"); phone.setToggleGroup(contactMethod);
        phone.setOnAction(e -> contact.setPromptText("10-digit phone number"));
        email.setOnAction(e -> contact.setPromptText("Email address"));
        Button create = new Button("Create Account"); create.setMaxWidth(Double.MAX_VALUE); create.getStyleClass().add("primary-button");
        create.setOnAction(e -> submit(first,last,dob,email.isSelected() ? ContactMethod.EMAIL : ContactMethod.PHONE,contact,password,funding,error));
        Hyperlink back = new Hyperlink("Already have an account? Log In"); back.setOnAction(e -> app.showLogin());
        VBox card = new VBox(12, title("Create your DrawN account"), subtitle("Set up your customer account and initial wallet balance."),
                new HBox(10, first,last), dob, new HBox(14,email,phone), contact,password,funding,error,create,back);
        card.setAlignment(Pos.CENTER_LEFT); card.getStyleClass().add("login-card"); card.setMaxWidth(460);
        StackPane root=new StackPane(card); root.setAlignment(Pos.CENTER); root.getStyleClass().add("app-root"); return root;
    }
    private void submit(TextField first, TextField last, DatePicker dob, ContactMethod method, TextField contact,
                        PasswordField password, TextField funding, Label error) {
        try {
            BigDecimal amount = new BigDecimal(funding.getText().trim());
            CustomerAccessService.AuthenticatedCustomer created = services.customerAccess().register(first.getText(), last.getText(), dob.getValue(), method, contact.getText(), password.getText(), amount);
            session.start(created.account(), created.customer()); app.showShell();
        } catch (Exception ex) { error.setText(LoginView.userMessage(ex)); }
    }
    private static TextField field(String prompt) { TextField field=new TextField();field.setPromptText(prompt);field.getStyleClass().add("text-input");return field; }
    private static Label title(String text) { Label label=new Label(text);label.getStyleClass().add("page-title");return label; }
    private static Label subtitle(String text) { Label label=new Label(text);label.getStyleClass().add("secondary-text");return label; }
}
