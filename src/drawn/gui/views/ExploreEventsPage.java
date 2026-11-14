package drawn.gui.views;

import drawn.model.event.Event;
import drawn.model.event.EventPurpose;
import javafx.scene.Node;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** Public events and private-keyword events, with details and ticket purchase (console exploreEvents). */
final class ExploreEventsPage {
    private final MainShell shell;
    private final Event preselected;
    private final TableView<Event> table = Ui.table(List.of(), "No published events are available.");
    private final VBox detail = Ui.card(Ui.muted("Select an event to see its details."));

    ExploreEventsPage(MainShell shell, Event preselected) { this.shell = shell; this.preselected = preselected; }

    Node view() {
        table.setId("explore-table");
        Ui.column(table, "Event", Event::name);
        Ui.column(table, "Type", event -> Ui.label(event.type()));
        Ui.column(table, "Date", event -> String.valueOf(event.date()));
        Ui.column(table, "Time", event -> event.startTime() + " – " + event.endTime());
        Ui.column(table, "Venue", event -> event.venue().name());
        table.getSelectionModel().selectedItemProperty().addListener((o, old, event) -> showDetails(event));

        ToggleGroup source = new ToggleGroup();
        RadioButton publicEvents = new RadioButton("Public events");
        publicEvents.setId("explore-public");
        publicEvents.setToggleGroup(source);
        publicEvents.setSelected(true);
        RadioButton privateEvents = new RadioButton("Private event access");
        privateEvents.setId("explore-private");
        privateEvents.setToggleGroup(source);
        TextField keyword = Ui.field("Private event access keyword");
        keyword.setId("explore-keyword");
        var find = Ui.secondary("Find Private Event", () -> loadPrivate(keyword.getText()));
        find.setId("explore-find");
        HBox privateRow = Ui.actions(keyword, find);
        HBox.setHgrow(keyword, Priority.ALWAYS);
        privateRow.visibleProperty().bind(privateEvents.selectedProperty());
        privateRow.managedProperty().bind(privateEvents.selectedProperty());
        publicEvents.setOnAction(e -> loadPublic());
        privateEvents.setOnAction(e -> { table.getItems().clear(); showDetails(null); keyword.requestFocus(); });
        keyword.setOnAction(e -> loadPrivate(keyword.getText()));

        loadPublic();
        if (preselected != null) table.getItems().stream().filter(event -> event.id().equals(preselected.id())).findFirst()
                .ifPresent(event -> table.getSelectionModel().select(event));

        detail.setPrefWidth(380);
        detail.setMinWidth(340);
        table.setMinWidth(420);
        HBox body = new HBox(18, table, detail);
        HBox.setHgrow(table, Priority.ALWAYS);
        table.setPrefHeight(460);
        return Ui.page("Explore Events", "Browse published events, or enter a keyword to open a private event.",
                Ui.actions(publicEvents, privateEvents), privateRow, body);
    }

    private void loadPublic() {
        try {
            table.getItems().setAll(shell.services().publishing().publishedEvents());
        } catch (Exception ex) {
            table.getItems().clear();
            Ui.error("Could not explore events", Ui.message(ex));
        }
        showDetails(null);
    }

    private void loadPrivate(String keyword) {
        try {
            List<Event> found = shell.services().workflow().privateEvents(Ui.required(keyword, "Private event access keyword"));
            table.getItems().setAll(found);
            showDetails(null);
            if (found.isEmpty()) Ui.info("No events found", "No published events are available for that keyword.");
            else table.getSelectionModel().selectFirst();
        } catch (Exception ex) {
            Ui.error("Could not explore events", Ui.message(ex));
        }
    }

    private void showDetails(Event event) {
        if (event == null) { detail.getChildren().setAll(Ui.muted("Select an event to see its details.")); return; }
        detail.getChildren().setAll(new EventDetailsView(event, shell.services()).content());
        if (event.purpose() == EventPurpose.TICKETED_EVENT)
            detail.getChildren().add(new TicketPurchasePanel(shell, event).view());
    }
}
