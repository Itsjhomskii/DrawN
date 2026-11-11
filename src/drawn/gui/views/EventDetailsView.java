package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.model.event.Event;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Read-only event detail dialog for Phase 5.1 discovery. */
public final class EventDetailsView {
    private final Event event; private final AppServices services;
    public EventDetailsView(Event event, AppServices services){this.event=event;this.services=services;}
    public void show(){
        Alert dialog=new Alert(Alert.AlertType.INFORMATION);dialog.setTitle(event.name());dialog.setHeaderText(event.name());
        VBox details=new VBox(8,new Label(event.type()+" • "+event.date()+" • "+event.startTime()+"–"+event.endTime()),new Label("Venue: "+event.venue().name()),new Label(event.description()));
        details.getChildren().forEach(node->{if(node instanceof Label label)label.setWrapText(true);});dialog.getDialogPane().setContent(details);dialog.getDialogPane().getStyleClass().add("dark-dialog");dialog.showAndWait();
    }
}
