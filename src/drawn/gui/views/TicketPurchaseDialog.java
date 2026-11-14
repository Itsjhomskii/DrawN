package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.model.event.Event;
import drawn.model.ticket.TicketType;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Customer ticket checkout that delegates all charging and inventory rules to EventPublishingService. */
final class TicketPurchaseDialog {
    private final Event event;
    private final AppServices services;
    private final AppSession session;
    private final Runnable refresh;

    TicketPurchaseDialog(Event event, AppServices services, AppSession session, Runnable refresh) {
        this.event = event; this.services = services; this.session = session; this.refresh = refresh;
    }

    void show() {
        try {
            List<TicketType> available = services.publishing().ticketTypes(event.id()).stream()
                    .filter(type -> type.remaining() > 0)
                    .filter(type -> type.salesStart() == null || !LocalDateTime.now().isBefore(type.salesStart()))
                    .filter(type -> type.salesDeadline() == null || LocalDateTime.now().isBefore(type.salesDeadline()))
                    .toList();
            if (available.isEmpty()) {
                new Alert(Alert.AlertType.INFORMATION, "There are no tickets available for this event.").showAndWait();
                return;
            }

            ComboBox<TicketType> category = new ComboBox<>(); category.getItems().setAll(available); category.setMaxWidth(Double.MAX_VALUE);
            category.setConverter(new StringConverter<>() {
                @Override public String toString(TicketType type) {
                    return type == null ? "" : type.name() + " • PHP " + money(type.price()) + " • " + type.remaining() + " left";
                }
                @Override public TicketType fromString(String value) { return null; }
            });
            category.getSelectionModel().selectFirst();
            Spinner<Integer> quantity = new Spinner<>(); quantity.setEditable(true); quantity.setMaxWidth(110);
            Label total = new Label(); total.getStyleClass().add("event-card-title");
            Label error = AuthenticationFormSupport.errorLabel();
            Runnable updateTotal = () -> {
                TicketType selected = category.getValue();
                if (selected == null) return;
                int max = Math.max(1, selected.remaining());
                int current = quantity.getValueFactory() == null ? 1 : quantity.getValue();
                quantity.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, max, Math.min(current, max)));
                total.setText("Total: PHP " + money(selected.price().multiply(BigDecimal.valueOf(quantity.getValue()))));
            };
            category.valueProperty().addListener((observable, oldValue, newValue) -> updateTotal.run());
            quantity.valueProperty().addListener((observable, oldValue, newValue) -> {
                TicketType selected = category.getValue();
                if (selected != null && newValue != null) total.setText("Total: PHP " + money(selected.price().multiply(BigDecimal.valueOf(newValue))));
            });
            updateTotal.run();

            GridPane fields = new GridPane(); fields.setHgap(12); fields.setVgap(12); fields.setPadding(new Insets(8));
            fields.addRow(0, new Label("Ticket category"), category);
            fields.addRow(1, new Label("Quantity"), quantity);
            fields.add(total, 0, 2, 2, 1); fields.add(error, 0, 3, 2, 1);

            ButtonType checkout = new ButtonType("Review purchase", ButtonBar.ButtonData.OK_DONE);
            Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Buy tickets • " + event.name());
            dialog.getDialogPane().getButtonTypes().addAll(checkout, ButtonType.CANCEL);
            dialog.getDialogPane().setContent(fields);
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/drawn/gui/styles/theme.css").toExternalForm());
            dialog.getDialogPane().getStyleClass().add("customer-dialog");
            dialog.getDialogPane().lookupButton(checkout).addEventFilter(ActionEvent.ACTION, action -> {
                action.consume();
                TicketType selected = category.getValue();
                if (selected == null) { error.setText("Choose a ticket category."); return; }
                int count;
                try { quantity.commitValue(); count = quantity.getValue(); }
                catch (RuntimeException ex) { error.setText("Enter a valid ticket quantity."); return; }
                BigDecimal amount = selected.price().multiply(BigDecimal.valueOf(count));
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        selected.name() + " × " + count + "\nTotal: PHP " + money(amount)
                                + "\nWallet balance: PHP " + money(session.account().getWalletBalance()));
                confirm.setTitle("Confirm ticket payment"); confirm.setHeaderText("Review your purchase");
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                var submit = dialog.getDialogPane().lookupButton(checkout); submit.setDisable(true);
                try {
                    services.publishing().buyTicket(session.customer(), session.account(), event.id(), selected.id(), count);
                    dialog.setResult(checkout); dialog.close();
                    new Alert(Alert.AlertType.INFORMATION, "Ticket purchase completed. Your wallet and ticket records have been updated.").showAndWait();
                    refresh.run();
                } catch (IOException | RuntimeException ex) {
                    error.setText(LoginView.userMessage(ex));
                    submit.setDisable(false);
                }
            });
            dialog.showAndWait();
        } catch (IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Ticket categories could not be loaded: " + ex.getMessage()).showAndWait();
        }
    }

    private static String money(BigDecimal amount) { return amount.setScale(2).toPlainString(); }
}
