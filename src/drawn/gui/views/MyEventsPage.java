package drawn.gui.views;

import drawn.model.event.Event;
import drawn.model.event.VenueReservation;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** The customer's event plans and unlinked venue requests (console showMyEvents). */
final class MyEventsPage {
    private final MainShell shell;
    private final VBox detail = Ui.card(Ui.muted("Select an event to see its details."));

    MyEventsPage(MainShell shell) { this.shell = shell; }

    Node view() {
        List<Event> events;
        List<VenueReservation> unlinked;
        try {
            events = shell.services().workflow().activeEvents(shell.session().customer());
            unlinked = shell.services().workflow().unlinkedReservations(shell.session().customer());
        } catch (Exception ex) {
            return Ui.page("My Events", "Your event plans and venue requests.", Ui.muted("Could not load your events: " + Ui.message(ex)));
        }

        TableView<Event> table = Ui.table(events, "No active events found.");
        table.setId("my-events-table");
        Ui.column(table, "Event", Event::name);
        Ui.column(table, "Date", event -> String.valueOf(event.date()));
        Ui.column(table, "Venue", event -> event.venue().name());
        Ui.column(table, "Status", event -> Ui.label(event.approvalStatus()));
        table.getSelectionModel().selectedItemProperty().addListener((o, old, event) -> showDetails(event));

        TableView<VenueReservation> requests = Ui.table(unlinked, "Every venue request is linked to an event.");
        Ui.column(requests, "Venue", VenueReservation::venueName);
        Ui.column(requests, "Date", reservation -> String.valueOf(reservation.date()));
        Ui.column(requests, "Time", reservation -> reservation.startTime() + "-" + reservation.endTime());
        Ui.column(requests, "Cost", reservation -> Ui.php(reservation.cost()));
        Ui.column(requests, "Status", reservation -> Ui.label(reservation.status()));

        var create = Ui.primary("Create & Publish Event", () -> shell.showPage(MainShell.CREATE_EVENT));
        create.setId("open-create-event");
        detail.setPrefWidth(380);
        detail.setMinWidth(340);
        table.setMinWidth(420);
        HBox top = new HBox(18, table, detail);
        HBox.setHgrow(table, Priority.ALWAYS);
        return Ui.page("My Events", "Event plans you organize. Select one to view or cancel it.",
                Ui.actions(create), top, Ui.section("Venue requests not yet linked to an event"), requests);
    }

    private void showDetails(Event event) {
        if (event == null) { detail.getChildren().setAll(Ui.muted("Select an event to see its details.")); return; }
        detail.getChildren().setAll(new EventDetailsView(event, shell.services()).organizerContent());
        try {
            shell.services().workflow().reservationForEvent(shell.session().customer(), event.id()).ifPresent(booking ->
                    detail.getChildren().add(Ui.muted("Venue booking: " + booking.status().name() + " | " + Ui.php(booking.cost()))));
        } catch (Exception ex) {
            detail.getChildren().add(Ui.muted("Venue booking could not be loaded: " + Ui.message(ex)));
        }
        var cancel = Ui.danger("Cancel Event", () -> cancel(event));
        cancel.setId("cancel-event");
        detail.getChildren().add(cancel);
    }

    private void cancel(Event event) {
        if (!Ui.confirm("Cancel this event?", "Cancel " + event.name() + " and process any eligible refunds?")) {
            Ui.info("Event cancellation cancelled.", "Nothing was changed.");
            return;
        }
        try {
            shell.services().cancellation().cancel(shell.session().customer(), event.id());
            Ui.info("Event cancelled.", "Event and linked reservation cancelled; paid tickets and reservations were refunded.");
            shell.showPage(MainShell.MY_EVENTS);
        } catch (Exception ex) {
            Ui.error("Event action failed", Ui.message(ex));
        }
    }
}
