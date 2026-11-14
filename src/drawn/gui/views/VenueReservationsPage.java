package drawn.gui.views;

import drawn.model.event.BookingStatus;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentStatus;
import drawn.service.customer.CustomerWorkflowService;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** Active venue requests with details and cancellation (console venueReservations). */
final class VenueReservationsPage {
    private final MainShell shell;
    private final VBox detail = Ui.card(Ui.muted("Select a venue request to see its details."));

    VenueReservationsPage(MainShell shell) { this.shell = shell; }

    Node view() {
        List<VenueReservation> active;
        try {
            active = shell.services().workflow().activeReservations(shell.session().customer());
        } catch (Exception ex) {
            return Ui.page("Venue Reservations", "Your venue requests.", Ui.muted("Could not load reservations: " + Ui.message(ex)));
        }
        TableView<VenueReservation> table = Ui.table(active, "No active venue requests found.");
        table.setId("reservations-table");
        Ui.column(table, "Venue", VenueReservation::venueName);
        Ui.column(table, "Date", reservation -> String.valueOf(reservation.date()));
        Ui.column(table, "Time", reservation -> reservation.startTime() + "-" + reservation.endTime());
        Ui.column(table, "Cost", reservation -> Ui.php(reservation.cost()));
        Ui.column(table, "Status", reservation -> reservation.status().name());
        Ui.column(table, "Payment", reservation -> Ui.label(reservation.paymentStatus()));
        table.getSelectionModel().selectedItemProperty().addListener((o, old, reservation) -> showDetails(reservation));

        var request = Ui.primary("Request a Venue", () -> shell.showPage(MainShell.REQUEST_VENUE));
        request.setId("open-request-venue");
        detail.setPrefWidth(380);
        detail.setMinWidth(340);
        table.setMinWidth(420);
        HBox body = new HBox(18, table, detail);
        HBox.setHgrow(table, Priority.ALWAYS);
        return Ui.page("Venue Reservations", "Paid venue requests wait for administrator approval before you can create an event.",
                Ui.actions(request), body);
    }

    private void showDetails(VenueReservation selected) {
        if (selected == null) { detail.getChildren().setAll(Ui.muted("Select a venue request to see its details.")); return; }
        detail.getChildren().setAll(Ui.section(selected.venueName()), Ui.details(
                "Date", String.valueOf(selected.date()),
                "Time", selected.startTime() + "-" + selected.endTime(),
                "Reservation cost", Ui.php(selected.cost()),
                "Pricing", CustomerWorkflowService.reservationCostExplanation(selected),
                "Reservation status", selected.status().name(),
                "Payment status", String.valueOf(selected.paymentStatus())));
        if (selected.status() == BookingStatus.PENDING && selected.paymentStatus() == PaymentStatus.PAID)
            detail.getChildren().add(Ui.muted("This paid request is awaiting administrator approval."));
        var cancel = Ui.danger("Cancel Reservation", () -> cancel(selected));
        cancel.setId("cancel-reservation");
        detail.getChildren().add(cancel);
    }

    private void cancel(VenueReservation selected) {
        if (!Ui.confirm("Cancel this reservation?", "Cancel this reservation and apply the eligible refund policy?")) {
            Ui.info("Reservation cancellation cancelled.", "Nothing was changed.");
            return;
        }
        try {
            VenueReservation cancelled = shell.services().payment().cancelReservation(shell.session().customer(),
                    shell.session().account(), selected.id());
            Ui.info("Reservation cancelled.", "Refund status: " + cancelled.paymentStatus());
            shell.showPage(MainShell.VENUES);
        } catch (Exception ex) {
            Ui.error("Reservation cancellation failed", Ui.message(ex));
        }
    }
}
