package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.model.event.Event;
import drawn.model.event.TicketCategory;
import drawn.service.customer.CustomerWorkflowService;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.layout.VBox;

/** Read-only event details, as a reusable panel or a dialog. */
public final class EventDetailsView {
    private final Event event; private final AppServices services;
    public EventDetailsView(Event event, AppServices services){this.event=event;this.services=services;}

    /** Buyer-facing details (the console's printBuyerEventDetails). */
    public Node content() {
        return new VBox(10, Ui.section(event.name()), Ui.details(
                "Type", Ui.label(event.type()),
                "Purpose", CustomerWorkflowService.displayPurpose(event.purpose()),
                "Date", String.valueOf(event.date()),
                "Time", event.startTime() + " – " + event.endTime(),
                "Venue", event.venue().name(),
                "Description", event.description()));
    }

    /** Organizer-facing details (the console's printEventDetails). */
    public Node organizerContent() {
        VBox box = new VBox(10, Ui.section(event.name()), Ui.details(
                "Event type", Ui.label(event.type()),
                "Event purpose", CustomerWorkflowService.displayPurpose(event.purpose()),
                "Visibility", Ui.label(event.visibility()),
                "Event date", String.valueOf(event.date()),
                "Time", event.startTime() + " – " + event.endTime(),
                "Venue", event.venue().name(),
                "Description", event.description(),
                "Expected attendance", String.valueOf(event.expectedAttendance()),
                "Event status", Ui.label(event.approvalStatus())));
        for (TicketCategory category : event.ticketCategories())
            box.getChildren().add(Ui.muted("Ticket category: " + category.name() + " • " + Ui.php(category.price())
                    + " • quantity " + category.quantity()));
        return box;
    }

    public void show(){
        Alert dialog=new Alert(Alert.AlertType.INFORMATION);dialog.setTitle(event.name());dialog.setHeaderText(event.name());
        dialog.getDialogPane().setContent(content());dialog.getDialogPane().getStyleClass().add("app-dialog");dialog.showAndWait();
    }
}
