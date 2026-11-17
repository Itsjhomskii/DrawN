package drawn.gui.views;

import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentStatus;
import drawn.service.customer.CustomerWorkflowService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Customer venue booking list with explicit reservation and linked-event states. */
final class VenueReservationsPage {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private final MainShell shell;
    private final VBox cards = new VBox(12);
    private List<VenueReservation> reservations = List.of();
    private String filter = "All";

    VenueReservationsPage(MainShell shell) { this.shell = shell; }

    Node view() {
        try {
            reservations = shell.services().reservationService().forCustomer(shell.session().customer());
        } catch (Exception ex) {
            return Ui.page("Venue Reservations", "Manage your bookings and track their approval status.",
                    Ui.muted("Could not load reservations: " + Ui.message(ex)));
        }

        Button request = Ui.primary("Request a Venue", () -> shell.showPage("Explore Venues"));
        request.setId("open-request-venue");
        HBox headingActions = new HBox(Ui.grow(), request);
        headingActions.setAlignment(Pos.CENTER_RIGHT);

        ToggleGroup group = new ToggleGroup();
        HBox filters = new HBox(8);
        filters.getStyleClass().add("reservation-filters");
        for (String label : List.of("All", "Pending", "Approved", "Declined", "Cancelled", "Other")) {
            ToggleButton button = new ToggleButton(label);
            button.setToggleGroup(group);
            button.setId("reservation-filter-" + label.toLowerCase(Locale.ROOT));
            button.getStyleClass().add("reservation-filter");
            button.setOnAction(event -> { filter = label; refresh(); });
            filters.getChildren().add(button);
            if (label.equals(filter)) button.setSelected(true);
        }
        cards.getStyleClass().add("reservation-list");
        refresh();
        return Ui.page("Venue Reservations", "Manage your bookings and track their approval status.", headingActions, filters, cards);
    }

    private void refresh() {
        List<VenueReservation> visible = reservations.stream().filter(this::matches).toList();
        if (visible.isEmpty()) {
            cards.getChildren().setAll(reservations.isEmpty() ? emptyReservations() : filteredEmpty());
            return;
        }
        cards.getChildren().setAll(visible.stream().map(this::reservationCard).toList());
    }

    private boolean matches(VenueReservation reservation) {
        return switch (filter) {
            case "Pending" -> reservation.status() == BookingStatus.PENDING || reservation.status() == BookingStatus.PENDING_PAYMENT;
            case "Approved" -> reservation.status() == BookingStatus.APPROVED || reservation.status() == BookingStatus.CONFIRMED;
            case "Declined" -> reservation.status() == BookingStatus.DECLINED;
            case "Cancelled" -> reservation.status() == BookingStatus.CANCELLED || reservation.status() == BookingStatus.EXPIRED;
            case "Other" -> reservation.status() == BookingStatus.RESERVED;
            default -> true;
        };
    }

    private VBox reservationCard(VenueReservation reservation) {
        Event linked = linkedEvent(reservation);
        VBox card = new VBox(12);
        card.getStyleClass().add("reservation-card");
        Label name = Ui.section(reservation.venueName());
        Label schedule = Ui.value(reservation.date().format(DATE) + "  •  " + CustomerText.time(reservation.startTime())
                + "–" + CustomerText.time(reservation.endTime()));
        Label total = new Label(Ui.php(reservation.cost()));
        total.getStyleClass().add("reservation-total");
        HBox badges = new HBox(8, badge(CustomerText.words(reservation.status()), reservationStatusClass(reservation.status())),
                badge(CustomerText.words(reservation.paymentStatus()), "payment-status"));
        if (linked != null) badges.getChildren().add(badge("Linked event · " + CustomerText.words(linked.approvalStatus()),
                linked.approvalStatus() == EventApprovalStatus.PUBLISHED ? "status-approved" : "status-neutral"));
        else if (!reservation.eventId().isBlank()) badges.getChildren().add(badge("Linked event", "status-neutral"));
        Label linkedName = reservation.eventId().isBlank() ? null : Ui.muted("Related event  •  "
                + (linked == null ? reservation.eventId() : linked.name()));
        HBox actions = new HBox(8, Ui.secondary("View Details", () -> showDetails(reservation, linked)));
        if (canCancel(reservation)) {
            Button cancel = Ui.danger("Cancel Reservation", () -> cancel(reservation));
            cancel.setId("cancel-reservation");
            actions.getChildren().add(cancel);
        }
        card.getChildren().addAll(name, schedule, new HBox(Ui.grow(), total), badges);
        if (linkedName != null) card.getChildren().add(linkedName);
        card.getChildren().add(actions);
        return card;
    }

    private VBox emptyReservations() {
        VBox empty = new VBox(10, Ui.section("No venue reservations yet."),
                Ui.muted("Explore available venues and book a space for your next event."),
                Ui.primary("Explore Venues", () -> shell.showPage("Explore Venues")));
        empty.setAlignment(Pos.CENTER_LEFT);
        empty.getStyleClass().addAll("empty-state", "empty-state-bubble");
        return empty;
    }

    private VBox filteredEmpty() {
        VBox empty = new VBox(8, Ui.section("No bookings in this view"),
                Ui.muted("Try another status filter to see your reservations."));
        empty.getStyleClass().addAll("empty-state", "empty-state-bubble");
        return empty;
    }

    private void showDetails(VenueReservation reservation, Event linked) {
        Venue venue = VenueCatalog.all().stream().filter(item -> item.name().equals(reservation.venueName())).findFirst().orElse(null);
        String category = venue == null ? "Venue" : CustomerText.words(VenueCatalog.categoryOf(venue));
        List<Node> content = new ArrayList<>();
        content.add(Ui.section(reservation.venueName()));
        content.add(Ui.muted(category));
        content.add(Ui.details("Date", reservation.date().format(DATE),
                "Reserved hours", CustomerText.time(reservation.startTime()) + "–" + CustomerText.time(reservation.endTime()),
                "Total fee", Ui.php(reservation.cost()),
                "Pricing", CustomerWorkflowService.reservationCostExplanation(reservation),
                "Wallet payment", CustomerText.words(reservation.paymentStatus()),
                "Reservation status", CustomerText.words(reservation.status())));
        if (!reservation.declineReason().isBlank()) content.add(Ui.muted("Administrator's reason  •  " + reservation.declineReason()));
        if (!reservation.eventId().isBlank()) content.add(Ui.muted("Related event  •  "
                + (linked == null ? reservation.eventId() : linked.name())
                + (linked == null ? "" : "  •  Publication status: " + CustomerText.words(linked.approvalStatus()))));
        if (reservation.status() == BookingStatus.PENDING && reservation.paymentStatus() == PaymentStatus.PAID)
            content.add(Ui.muted("This paid request is awaiting administrator approval."));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Reservation details");
        ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(close);
        VBox details = new VBox(14);
        details.getStyleClass().add("reservation-detail-panel");
        details.getChildren().setAll(content);
        details.getChildren().add(0, new HBox(8, BackNavigation.button(dialog::close),
                Ui.muted("Venue Reservations  ›  Reservation Details")));
        if (canCancel(reservation)) {
            Button cancel = Ui.danger("Cancel Reservation", () -> { dialog.close(); cancel(reservation); });
            details.getChildren().add(cancel);
        }
        dialog.getDialogPane().setContent(details);
        dialog.getDialogPane().setPrefWidth(560);
        dialog.getDialogPane().getStyleClass().add("customer-dialog");
        dialog.getDialogPane().getStylesheets().add(VenueReservationsPage.class.getResource("/drawn/gui/styles/theme.css").toExternalForm());
        dialog.showAndWait();
    }

    private Event linkedEvent(VenueReservation reservation) {
        if (reservation.eventId().isBlank()) return null;
        try { return shell.services().eventService().eventsFor(shell.session().customer()).stream()
                .filter(event -> event.id().equals(reservation.eventId())).findFirst().orElse(null); }
        catch (Exception ex) { return null; }
    }

    private void cancel(VenueReservation reservation) {
        boolean refundDue = reservation.paymentStatus() == PaymentStatus.PAID || reservation.paymentStatus() == PaymentStatus.VERIFIED;
        String consequences = refundDue
                ? "This will cancel the reservation. Eligible cancellation returns " + Ui.php(reservation.cost())
                        + " to your wallet through the existing refund service."
                : "This will cancel the reservation. No wallet refund is due because the reservation has not been paid.";
        if (!Ui.confirm("Cancel this reservation?", consequences)) return;
        try {
            shell.services().payment().cancelReservation(shell.session().customer(), shell.session().account(), reservation.id());
            shell.showPage(MainShell.VENUES);
        } catch (Exception ex) { Ui.error("Reservation cancellation failed", Ui.message(ex)); }
    }

    private boolean canCancel(VenueReservation reservation) {
        if (reservation.status() == BookingStatus.CANCELLED || reservation.status() == BookingStatus.DECLINED
                || reservation.status() == BookingStatus.EXPIRED || reservation.paymentStatus() == PaymentStatus.PENDING_VERIFICATION
                || !reservation.date().isAfter(java.time.LocalDate.now())) return false;
        Event event = linkedEvent(reservation);
        return event == null || (event.approvalStatus() != EventApprovalStatus.CANCELLED
                && event.date().isAfter(java.time.LocalDate.now()));
    }

    private static Label badge(String text, String style) {
        Label badge = new Label(text);
        badge.getStyleClass().addAll("reservation-status", style);
        return badge;
    }

    private static String reservationStatusClass(BookingStatus status) {
        return switch (status) {
            case PENDING, PENDING_PAYMENT -> "status-pending";
            case APPROVED, CONFIRMED -> "status-approved";
            case DECLINED -> "status-declined";
            case CANCELLED, EXPIRED -> "status-cancelled";
            case RESERVED -> "status-neutral";
        };
    }
}
