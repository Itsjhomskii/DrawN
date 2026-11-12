package drawn.gui.admin;

import drawn.model.event.Event;
import drawn.service.admin.AdminService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/** Admin dashboard with session-only events and approved public submissions. */
public final class AdminView {
    public interface Host {
        List<Event> approvedPublicEvents() throws Exception;
        void setRoot(Parent root);
        void logout();
    }

    private final Host app;
    private final AdminService admin;
    private final BorderPane root = new BorderPane();

    public AdminView(Host app, AdminService admin) {
        this.app = app;
        this.admin = admin;
    }

    public Parent view() {
        root.getStyleClass().add("admin-shell");
        root.setId("admin-dashboard");
        Label brand = new Label("DrawN Admin");
        brand.getStyleClass().add("admin-brand");
        Button logout = new Button("Log out");
        logout.getStyleClass().add("admin-secondary");
        logout.setId("admin-logout");
        logout.setOnAction(event -> app.logout());
        HBox bar = new HBox(brand, grow(), logout);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("admin-topbar");
        root.setTop(bar);
        refresh();
        return root;
    }

    private void refresh() { root.setCenter(content()); }

    private Node content() {
        List<AdminService.LegacyEvent> added = admin.events();
        TableView<AdminService.LegacyEvent> addedTable = table(added, "No admin-created events yet.", "admin-events-table");
        column(addedTable, "Event", AdminService.LegacyEvent::name);
        column(addedTable, "Date", AdminService.LegacyEvent::date);
        column(addedTable, "Ticket price", event -> "PHP " + event.price().toPlainString());
        column(addedTable, "Tickets left", event -> String.valueOf(event.remaining()));

        Node approved;
        try {
            List<Event> publicEvents = app.approvedPublicEvents();
            TableView<Event> table = table(publicEvents, "No approved public submissions.", "admin-approved-events-table");
            column(table, "Event", Event::name);
            column(table, "Type", event -> event.type().name());
            column(table, "Purpose", event -> event.purpose().name());
            column(table, "Visibility", event -> event.visibility().name());
            column(table, "Schedule", event -> event.date() + " " + event.startTime() + "–" + event.endTime());
            column(table, "Venue", event -> event.venue().name());
            approved = table;
        } catch (Exception ex) {
            Label failure = new Label("Could not load approved public submissions: " + AdminApplication.message(ex));
            failure.getStyleClass().add("admin-muted");
            approved = failure;
        }

        VBox body = new VBox(18, heading(), addForm(), section("Admin-created events"), addedTable,
                section("Approved public submissions"), approved);
        body.getStyleClass().add("admin-content");
        VBox.setVgrow(addedTable, Priority.SOMETIMES);
        ScrollWrapper scroll = new ScrollWrapper(body);
        return scroll.node;
    }

    private Node heading() {
        Label title = new Label("Admin Dashboard");
        title.getStyleClass().add("admin-title");
        Label subtitle = new Label("Admin-created events last for this session only.");
        subtitle.getStyleClass().add("admin-subtitle");
        return new VBox(4, title, subtitle);
    }

    private Node addForm() {
        TextField name = field("Event name"); name.setId("admin-name");
        TextField date = field("Date (for example, 2026-12-31)"); date.setId("admin-date");
        TextField capacity = field("Ticket capacity"); capacity.setId("admin-capacity");
        TextField price = field("Ticket price (PHP)"); price.setId("admin-price");
        GridPane form = new GridPane();
        form.setHgap(14); form.setVgap(10);
        form.add(label("Event name"), 0, 0); form.add(name, 1, 0);
        form.add(label("Date"), 0, 1); form.add(date, 1, 1);
        form.add(label("Ticket capacity"), 0, 2); form.add(capacity, 1, 2);
        form.add(label("Ticket price (PHP)"), 0, 3); form.add(price, 1, 3);
        for (Node node : form.getChildren()) if (node instanceof TextField field) GridPane.setHgrow(field, Priority.ALWAYS);
        Button add = new Button("Add event");
        add.setId("admin-add"); add.getStyleClass().add("admin-primary");
        add.setOnAction(event -> add(name, date, capacity, price));
        VBox card = new VBox(14, section("Add event"), form, add);
        card.getStyleClass().add("admin-card");
        return card;
    }

    private void add(TextField name, TextField date, TextField capacity, TextField price) {
        try {
            String eventName = required(name.getText(), "Event name");
            String eventDate = required(date.getText(), "Date");
            int tickets = Integer.parseInt(required(capacity.getText(), "Ticket capacity"));
            if (tickets < 1) throw new IllegalArgumentException("Ticket capacity must be at least 1.");
            BigDecimal ticketPrice = new BigDecimal(required(price.getText(), "Ticket price"));
            admin.addEvent(eventName, eventDate, tickets, ticketPrice);
            AlertSupport.info("Event added", eventName + " was added to the admin event list.");
            refresh();
        } catch (Exception ex) {
            AdminApplication.error("Could not add event", AdminApplication.message(ex));
        }
    }

    private static String required(String text, String field) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(field + " is required.");
        return text.trim();
    }

    private static TextField field(String prompt) {
        TextField field = new TextField(); field.setPromptText(prompt); field.getStyleClass().add("admin-input"); return field;
    }
    private static Label label(String value) { Label label = new Label(value); label.getStyleClass().add("admin-muted"); return label; }
    private static Label section(String value) { Label label = new Label(value); label.getStyleClass().add("admin-section"); return label; }
    private static Region grow() { Region region = new Region(); HBox.setHgrow(region, Priority.ALWAYS); return region; }

    private static <T> TableView<T> table(List<T> rows, String empty, String id) {
        TableView<T> table = new TableView<>(FXCollections.observableArrayList(rows));
        table.setId(id); table.setPlaceholder(label(empty));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(Math.min(380, 44 + Math.max(rows.size(), 3) * 34));
        table.setMinHeight(150); table.getStyleClass().add("admin-table"); return table;
    }
    private static <T> void column(TableView<T> table, String name, Function<T, String> getter) {
        TableColumn<T, String> col = new TableColumn<>(name);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        col.setSortable(false); table.getColumns().add(col);
    }

    private static final class ScrollWrapper {
        final javafx.scene.control.ScrollPane node;
        ScrollWrapper(Node content) {
            node = new javafx.scene.control.ScrollPane(content);
            node.setFitToWidth(true); node.getStyleClass().add("admin-scroll");
        }
    }

    private static final class AlertSupport {
        static void info(String title, String message) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
            alert.setTitle("DrawN Admin"); alert.setHeaderText(title); alert.setContentText(message); alert.showAndWait();
        }
    }
}
