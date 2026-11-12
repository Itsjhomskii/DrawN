package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.DrawNApplication;
import drawn.model.event.Event;
import drawn.service.admin.AdminService;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import java.math.BigDecimal;
import java.util.List;

/** Admin dashboard: list events and add an in-memory event (console adminMenu). */
public final class AdminView {
    private final DrawNApplication app;
    private final AppServices services;
    private final BorderPane root = new BorderPane();

    public AdminView(DrawNApplication app, AppServices services) { this.app = app; this.services = services; }

    public Parent view() {
        root.getStyleClass().add("app-root");
        Label brand = new Label("DrawN Admin");
        brand.getStyleClass().add("sidebar-brand");
        var logOut = Ui.secondary("Log Out", app::showLogin);
        logOut.setId("admin-logout");
        HBox bar = Ui.actions(brand, Ui.grow(), logOut);
        bar.getStyleClass().add("top-bar");
        root.setTop(bar);
        refresh();
        return root;
    }

    private void refresh() { root.setCenter(content()); }

    private Node content() {
        List<AdminService.LegacyEvent> added = services.admin().events();
        TableView<AdminService.LegacyEvent> addedTable = Ui.table(added, "No admin-created events yet.");
        addedTable.setId("admin-events-table");
        Ui.column(addedTable, "Event", AdminService.LegacyEvent::name);
        Ui.column(addedTable, "Date", AdminService.LegacyEvent::date);
        Ui.column(addedTable, "Ticket price", event -> "PHP " + event.price().toPlainString());
        Ui.column(addedTable, "Tickets left", event -> String.valueOf(event.remaining()));

        Node approved;
        try {
            List<Event> events = services.events().publicApprovedEvents();
            TableView<Event> table = Ui.table(events, "No approved public submissions.");
            Ui.column(table, "Event", Event::name);
            Ui.column(table, "Type", event -> String.valueOf(event.type()));
            Ui.column(table, "Purpose", event -> String.valueOf(event.purpose()));
            Ui.column(table, "Visibility", event -> String.valueOf(event.visibility()));
            Ui.column(table, "Schedule", event -> event.date() + " " + event.startTime() + "–" + event.endTime());
            Ui.column(table, "Venue", event -> event.venue().name());
            approved = table;
        } catch (Exception ex) {
            approved = Ui.muted("Could not load approved public submissions: " + Ui.message(ex));
        }

        return Ui.page("Admin Dashboard", "Admin-created events last for this session only, as in the console app.",
                addForm(), Ui.section("Admin-created events"), addedTable, Ui.section("Approved public submissions"), approved);
    }

    private Node addForm() {
        TextField name = Ui.field("Event name");
        TextField date = Ui.field("Date (for example, 2026-12-31)");
        TextField capacity = Ui.field("Ticket capacity");
        TextField price = Ui.field("Ticket price (PHP)");
        name.setId("admin-name");
        date.setId("admin-date");
        capacity.setId("admin-capacity");
        price.setId("admin-price");
        GridPane form = Ui.form();
        Ui.row(form, "Event name", name);
        Ui.row(form, "Date", date);
        Ui.row(form, "Ticket capacity", capacity);
        Ui.row(form, "Ticket price (PHP)", price);
        var add = Ui.primary("Add Event", () -> add(name, date, capacity, price));
        add.setId("admin-add");
        return Ui.card(Ui.section("Add event"), form, Ui.actions(add));
    }

    private void add(TextField name, TextField date, TextField capacity, TextField price) {
        try {
            String eventName = Ui.required(name.getText(), "Event name");
            String eventDate = Ui.required(date.getText(), "Date");
            int tickets = Ui.whole(capacity.getText(), 1, Integer.MAX_VALUE, "Ticket capacity");
            BigDecimal ticketPrice = Ui.amount(price.getText(), "Ticket price");
            services.admin().addEvent(eventName, eventDate, tickets, ticketPrice);
            Ui.info("Event added.", eventName + " was added to the admin event list.");
            refresh();
        } catch (Exception ex) {
            Ui.error("Could not add event", Ui.message(ex));
        }
    }
}
