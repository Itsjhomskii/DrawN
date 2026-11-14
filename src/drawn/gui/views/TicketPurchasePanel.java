package drawn.gui.views;

import drawn.model.event.Event;
import drawn.model.ticket.TicketType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.util.List;

/** Ticket category picker and purchase button for one published event (console buyTicketForEvent). */
final class TicketPurchasePanel {
    private final MainShell shell;
    private final Event event;

    TicketPurchasePanel(MainShell shell, Event event) { this.shell = shell; this.event = event; }

    Node view() {
        List<TicketType> types;
        try {
            types = shell.services().publishing().ticketTypes(event.id());
        } catch (Exception ex) {
            return Ui.muted("Ticket information could not be loaded: " + Ui.message(ex));
        }
        if (types.isEmpty()) return Ui.muted("Tickets are not available for this event.");

        ComboBox<TicketType> category = new ComboBox<>();
        category.setId("ticket-category");
        category.getItems().setAll(types);
        category.setConverter(new StringConverter<>() {
            @Override public String toString(TicketType type) {
                return type == null ? "" : type.name() + " | " + Ui.php(type.price()) + " | " + type.remaining() + " left | Sales end: " + type.salesDeadline();
            }
            @Override public TicketType fromString(String text) { return null; }
        });
        category.getSelectionModel().selectFirst();
        TextField quantity = Ui.field("Quantity");
        quantity.setId("ticket-quantity");
        quantity.setText("1");
        Label total = Ui.muted("");
        Runnable updateTotal = () -> {
            TicketType type = category.getValue();
            try {
                int count = Integer.parseInt(quantity.getText().trim());
                total.setText("Total: " + Ui.php(type.price().multiply(BigDecimal.valueOf(count))));
            } catch (RuntimeException ex) { total.setText("Total: —"); }
        };
        category.valueProperty().addListener((o, a, b) -> updateTotal.run());
        quantity.textProperty().addListener((o, a, b) -> updateTotal.run());
        updateTotal.run();

        Button buy = Ui.primary("Buy Tickets", () -> buy(category.getValue(), quantity.getText()));
        buy.setId("buy-tickets");
        GridPane form = Ui.form();
        Ui.row(form, "Ticket category", category);
        Ui.row(form, "Quantity", quantity);
        return new VBox(12, Ui.section("Buy tickets"), form, total, buy);
    }

    private void buy(TicketType type, String quantityText) {
        try {
            if (type == null) throw new IllegalArgumentException("Choose a ticket category.");
            int quantity = Ui.whole(quantityText, 1, Math.max(1, type.remaining()), "Quantity");
            BigDecimal total = type.price().multiply(BigDecimal.valueOf(quantity));
            if (!Ui.confirm("Confirm ticket purchase?", "Purchase summary: " + type.name() + " x " + quantity
                    + " | Total " + Ui.php(total))) {
                Ui.info("Purchase cancelled.", "No payment was taken.");
                return;
            }
            shell.services().publishing().buyTicket(shell.session().customer(), shell.session().account(), event.id(), type.id(), quantity);
            Ui.info("Ticket purchase completed.", "The charge is recorded in Wallet & Transactions.");
            shell.showEvent(event);
        } catch (Exception ex) {
            Ui.error("Ticket purchase failed", Ui.message(ex));
        }
    }
}
