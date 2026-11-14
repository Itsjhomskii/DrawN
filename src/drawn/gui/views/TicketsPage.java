package drawn.gui.views;

import drawn.model.event.Event;
import drawn.service.event.EventPublishingService;
import drawn.storage.TicketRepository;
import javafx.scene.Node;
import javafx.scene.control.TableView;

import java.util.List;

/** Ticket purchases with buy and cancel actions (console ticketsMenu). */
final class TicketsPage {
    private final MainShell shell;

    TicketsPage(MainShell shell) { this.shell = shell; }

    Node view() {
        List<TicketRepository.Purchase> purchases;
        try {
            purchases = shell.services().publishing().purchases(shell.session().customer());
        } catch (Exception ex) {
            return Ui.page("My Tickets", "Your ticket purchases.", Ui.muted("Could not load tickets: " + Ui.message(ex)));
        }
        TableView<TicketRepository.Purchase> table = Ui.table(purchases, "No ticket purchases found.");
        table.setId("tickets-table");
        Ui.column(table, "Event", this::eventName);
        Ui.column(table, "Category", this::categoryName);
        Ui.column(table, "Tickets", purchase -> String.valueOf(purchase.quantity()));
        Ui.column(table, "Amount", purchase -> Ui.php(purchase.amount()));
        Ui.column(table, "Status", purchase -> String.valueOf(purchase.status()));

        var buy = Ui.primary("Buy Tickets", () -> shell.showPage(MainShell.EXPLORE));
        buy.setId("tickets-buy");
        var cancel = Ui.danger("Cancel Selected Purchase", () -> cancel(table.getSelectionModel().getSelectedItem()));
        cancel.setId("tickets-cancel");
        cancel.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        return Ui.page("My Tickets", "Tickets you bought. Cancelling a ticket refunds it to your wallet minus the cancellation fee.",
                Ui.actions(buy, cancel), table);
    }

    private String eventName(TicketRepository.Purchase purchase) {
        try { return shell.services().publishing().event(purchase.eventId()).name(); }
        catch (Exception ex) { return "Ticket purchase record could not be displayed."; }
    }

    private String categoryName(TicketRepository.Purchase purchase) {
        try { return shell.services().workflow().ticketCategoryName(purchase); }
        catch (Exception ex) { return "Ticket"; }
    }

    private void cancel(TicketRepository.Purchase selected) {
        if (selected == null) return;
        try {
            EventPublishingService.TicketCancellationQuote quote =
                    shell.services().publishing().cancellationQuote(shell.session().customer(), selected.id());
            Event event = shell.services().publishing().event(selected.eventId());
            String summary = event.name() + " | " + selected.quantity() + " ticket(s)\n\nCancellation summary: refund "
                    + Ui.php(quote.refundAmount()) + " | fee " + Ui.php(quote.cancellationFee()) + " (organizer "
                    + Ui.php(quote.organizerFee()) + ", admin " + Ui.php(quote.adminFee()) + ")";
            if (!Ui.confirm("Confirm ticket cancellation?", summary)) {
                Ui.info("Ticket cancellation cancelled.", "Nothing was changed.");
                return;
            }
            shell.services().publishing().cancelTicket(shell.session().customer(), shell.session().account(), selected.id());
            Ui.info("Ticket purchase cancelled.", "Your refund has been added to your wallet.");
            shell.showPage(MainShell.TICKETS);
        } catch (Exception ex) {
            Ui.error("Ticket cancellation failed", Ui.message(ex));
        }
    }
}
