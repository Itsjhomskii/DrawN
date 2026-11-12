package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.model.event.Event;
import drawn.model.event.VenueReservation;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Read-only event detail dialog for Phase 5.1 discovery. */
public final class EventDetailsView {
    private final Event event; private final AppServices services; private final AppSession session; private final Runnable refresh;
    private final VenueReservation reservation;
    public EventDetailsView(Event event, AppServices services){this(event, services, null, () -> { }, null);}
    public EventDetailsView(Event event, AppServices services, AppSession session, Runnable refresh){this(event, services, session, refresh, null);}
    public EventDetailsView(Event event, AppServices services, AppSession session, Runnable refresh, VenueReservation reservation){this.event=event;this.services=services;this.session=session;this.refresh=refresh;this.reservation=reservation;}
    public void show(){
        boolean ticketed = event.purpose() == drawn.model.event.EventPurpose.TICKETED_EVENT
                && event.visibility() == drawn.model.event.EventVisibility.PUBLIC;
        boolean canBuy = ticketed && session != null && !event.organizerId().equals(session.customer().getId());
        ButtonType buy = new ButtonType("Buy tickets", ButtonBar.ButtonData.OK_DONE);
        Alert dialog=canBuy
                ? new Alert(Alert.AlertType.INFORMATION, "", buy, ButtonType.CLOSE)
                : new Alert(Alert.AlertType.INFORMATION);
        dialog.setTitle(event.name());dialog.setHeaderText(event.name());
        String schedule = reservation == null
                ? event.date() + " • " + CustomerText.time(event.startTime()) + "–" + CustomerText.time(event.endTime())
                : CustomerText.reservationSchedule(reservation);
        VBox details=new VBox(8,new Label(CustomerText.words(event.type())+" • "+schedule),new Label("Venue: "+event.venue().name()),new Label(event.description()));
        details.getChildren().forEach(node->{if(node instanceof Label label)label.setWrapText(true);});dialog.getDialogPane().setContent(details);dialog.getDialogPane().getStyleClass().add("dark-dialog");dialog.showAndWait();
        if (canBuy && dialog.getResult() == buy) {
            new TicketPurchaseDialog(event, services, session, refresh).show();
        }
    }
}
