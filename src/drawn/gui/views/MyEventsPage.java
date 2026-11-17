package drawn.gui.views;

import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.ExtensionRequestStatus;
import drawn.model.event.VenueExtensionRequest;
import drawn.model.event.VenueReservation;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.util.List;

/** Organizer's published events and approved venue requests ready for event setup. */
final class MyEventsPage {
    private final MainShell shell;
    private final VBox eventDetail = Ui.card(Ui.muted("Select a published event to see its details."));
    private final VBox reservationDetail = Ui.card(Ui.muted("Select an approved venue request to continue."));

    MyEventsPage(MainShell shell) { this.shell = shell; }

    Node view() {
        List<Event> published;
        List<VenueReservation> approved;
        try {
            published = shell.services().eventService().eventsFor(shell.session().customer()).stream()
                    .filter(event -> event.approvalStatus() == EventApprovalStatus.PUBLISHED).toList();
            approved = shell.services().reservationService().forCustomer(shell.session().customer()).stream()
                    .filter(reservation -> (reservation.status() == BookingStatus.APPROVED
                            || reservation.status() == BookingStatus.CONFIRMED)
                            && reservation.paymentStatus() == drawn.model.payment.PaymentStatus.PAID
                            && reservation.eventId().isBlank()).toList();
        } catch (Exception ex) {
            return Ui.page("My Events", "Track published events and approved venue requests.",
                    Ui.muted("Could not load your events: " + Ui.message(ex)));
        }

        TableView<Event> publishedTable = Ui.table(published, "No published events yet.");
        publishedTable.setId("my-events-table");
        Ui.column(publishedTable, "Event", Event::name);
        Ui.column(publishedTable, "Date", event -> String.valueOf(event.date()));
        Ui.column(publishedTable, "Venue", event -> event.venue().name());
        Ui.column(publishedTable, "Status", event -> CustomerText.eventStatus(event));
        publishedTable.getSelectionModel().selectedItemProperty().addListener((o, old, event) -> showEvent(event));
        publishedTable.setMinWidth(430);
        eventDetail.setMinWidth(340);
        eventDetail.setPrefWidth(390);
        HBox publishedSection = new HBox(18, publishedTable, eventDetail);
        HBox.setHgrow(publishedTable, Priority.ALWAYS);

        TableView<VenueReservation> approvedTable = Ui.table(approved, "No approved venue requests are ready to publish.");
        approvedTable.setId("approved-reservations-table");
        Ui.column(approvedTable, "Venue", VenueReservation::venueName);
        Ui.column(approvedTable, "Date", reservation -> String.valueOf(reservation.date()));
        Ui.column(approvedTable, "Time", reservation -> CustomerText.time(reservation.startTime())
                + "–" + CustomerText.time(reservation.endTime()));
        Ui.column(approvedTable, "Cost", reservation -> Ui.php(reservation.cost()));
        Ui.column(approvedTable, "Status", reservation -> CustomerText.reservationStatus(reservation, null));
        approvedTable.getSelectionModel().selectedItemProperty().addListener((o, old, reservation) -> showApproved(reservation));
        reservationDetail.setMinWidth(340);
        reservationDetail.setPrefWidth(390);
        HBox approvedSection = new HBox(18, approvedTable, reservationDetail);
        HBox.setHgrow(approvedTable, Priority.ALWAYS);

        var create = Ui.primary("Create & Publish Event", () -> shell.showPage(MainShell.CREATE_EVENT));
        create.setId("open-create-event");
        return Ui.page("My Events", "Published events appear first. Approved venue requests are ready for event setup.",
                Ui.actions(create), Ui.section("Published events"), publishedSection,
                Ui.section("Approved venue requests"), approvedSection);
    }

    private void showEvent(Event event) {
        if (event == null) { eventDetail.getChildren().setAll(Ui.muted("Select a published event to see its details.")); return; }
        eventDetail.getChildren().setAll(new EventDetailsView(event, shell.services()).organizerContent(),
                Ui.muted("Status: " + CustomerText.eventStatus(event)));
        try {
            shell.services().venueExtensions().requestsForEvent(shell.session().customer(), event.id()).forEach(request ->
                    eventDetail.getChildren().add(extensionSummary(request)));
        } catch (Exception ex) {
            eventDetail.getChildren().add(Ui.muted("Extension requests could not be loaded: " + Ui.message(ex)));
        }
        eventDetail.getChildren().addAll(
                Ui.secondary("Edit Event", () -> new EditEventDialog(shell.services(), shell.session(), event,
                        () -> shell.showPage(MainShell.MY_EVENTS)).show()),
                Ui.secondary("Request Time Extension", () -> requestExtension(event)),
                Ui.danger("Cancel Event", () -> cancel(event)));
        eventDetail.getChildren().get(eventDetail.getChildren().size() - 3).setId("edit-event");
        eventDetail.getChildren().get(eventDetail.getChildren().size() - 2).setId("request-event-extension");
        eventDetail.getChildren().get(eventDetail.getChildren().size() - 1).setId("cancel-event");
    }

    private Node extensionSummary(VenueExtensionRequest request) {
        String state = switch (request.status()) {
            case PENDING -> "Awaiting administrator review";
            case APPROVED -> "Approved";
            case DECLINED -> "Declined";
            case CANCELLED -> "Cancelled";
        };
        VBox summary = Ui.card(Ui.section("Time extension request"), Ui.details(
                "Requested end", CustomerText.time(request.requestedEndTime()),
                "Added fee", Ui.php(request.additionalCost()),
                "Status", state));
        if (!request.decisionReason().isBlank()) summary.getChildren().add(Ui.muted("Reason: " + request.decisionReason()));
        if (request.status() == ExtensionRequestStatus.PENDING)
            summary.getChildren().add(Ui.muted("No additional amount has been charged while this request is pending."));
        return summary;
    }

    private void requestExtension(Event event) {
        TextInputDialog dialog = new TextInputDialog(event.endTime().toString());
        dialog.setTitle("Request event time extension");
        dialog.setHeaderText("Date, start time, and venue stay fixed.");
        dialog.setContentText("New end time (HH:mm, 24-hour clock). A two-hour gap is required before the next booking at this venue.");
        String input = dialog.showAndWait().orElse(null);
        if (input == null) return;
        try {
            LocalTime requestedEnd = Ui.time(input, "New end time");
            VenueExtensionRequest request = shell.services().venueExtensions()
                    .request(shell.session().customer(), event.id(), requestedEnd);
            Ui.info("Extension request submitted", "Additional fee: " + Ui.php(request.additionalCost())
                    + ". It will not be charged until administrator approval.");
            shell.showPage(MainShell.MY_EVENTS);
        } catch (Exception ex) {
            Ui.error("Could not request time extension", Ui.message(ex));
        }
    }

    private void showApproved(VenueReservation reservation) {
        if (reservation == null) { reservationDetail.getChildren().setAll(Ui.muted("Select an approved venue request to continue.")); return; }
        reservationDetail.getChildren().setAll(Ui.section(reservation.venueName()), Ui.details(
                "Date", String.valueOf(reservation.date()),
                "Time", CustomerText.time(reservation.startTime()) + "–" + CustomerText.time(reservation.endTime()),
                "Reservation cost", Ui.php(reservation.cost()),
                "Status", CustomerText.reservationStatus(reservation, null)));
        var publish = Ui.primary("Configure & Publish", () -> shell.showPage(MainShell.CREATE_EVENT));
        publish.setId("publish-approved-reservation");
        reservationDetail.getChildren().add(publish);
    }

    private void cancel(Event event) {
        if (!Ui.confirm("Cancel this event?", "Cancel “" + event.name() + "” and refund eligible ticket buyers?")) return;
        try {
            shell.services().cancellation().cancel(shell.session().customer(), event.id());
            Ui.info("Event cancelled", "Eligible ticket and venue refunds were processed.");
            shell.showPage(MainShell.MY_EVENTS);
        } catch (Exception ex) {
            Ui.error("Event cancellation failed", Ui.message(ex));
        }
    }
}
