package drawn.gui.views;

import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventType;
import drawn.model.event.EventVisibility;
import drawn.model.event.TicketCategory;
import drawn.model.event.Venue;
import drawn.model.event.VenueReservation;
import drawn.model.user.Customer;
import drawn.service.customer.CustomerWorkflowService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Create & publish an event on an approved, paid venue reservation (console createEvent). */
final class CreateEventPage {
    private static final int MAX_DESCRIPTION = 500;
    private final MainShell shell;
    private final ComboBox<VenueReservation> reservation = new ComboBox<>();
    private final ComboBox<EventType> type = new ComboBox<>();
    private final ComboBox<EventPurpose> purpose = new ComboBox<>();
    private final TextField name = Ui.field("Event name");
    private final TextArea description = new TextArea();
    private final TextField attendance = Ui.field("Expected attendance");
    private final RadioButton publicVisibility = new RadioButton("Public");
    private final RadioButton privateVisibility = new RadioButton("Private");
    private final TextField keyword = Ui.field("Private event access keyword");
    private final ObservableList<TicketCategory> categories = FXCollections.observableArrayList();

    CreateEventPage(MainShell shell) { this.shell = shell; }

    Node view() {
        Customer customer = shell.session().customer();
        String subtitle = "Turn an approved, paid venue reservation into a published event.";
        if (customer.getAge() < 16)
            return Ui.page("Create & Publish Event", subtitle, backCrumb(), Ui.muted("Customers must be at least 16 to submit an event."));
        List<VenueReservation> eligible;
        try {
            eligible = shell.services().workflow().reservationsReadyForEvent(customer);
        } catch (Exception ex) {
            return Ui.page("Create & Publish Event", subtitle, backCrumb(), Ui.muted("Event submission failed: " + Ui.message(ex)));
        }
        if (eligible.isEmpty()) {
            var request = Ui.primary("Request a Venue", () -> shell.showPage(MainShell.REQUEST_VENUE));
            request.setId("create-request-venue");
            return Ui.page("Create & Publish Event", subtitle, backCrumb(), Ui.card(
                    Ui.section("No approved, paid venue reservation is ready for event details yet."),
                    Ui.muted("Request and pay for a venue first, then wait for administrator approval."), Ui.actions(request)));
        }
        return Ui.page("Create & Publish Event", subtitle, form(eligible));
    }

    private Node form(List<VenueReservation> eligible) {
        reservation.setId("create-reservation");
        type.setId("create-type");
        purpose.setId("create-purpose");
        name.setId("create-name");
        description.setId("create-description");
        attendance.setId("create-attendance");
        privateVisibility.setId("create-private");
        keyword.setId("create-keyword");

        reservation.getItems().setAll(eligible);
        reservation.setConverter(converter(r -> r.venueName() + " | " + r.date() + " " + r.startTime() + "-" + r.endTime() + " | " + r.status()));
        type.getItems().setAll(EventType.values());
        type.setConverter(converter(Ui::label));
        purpose.getItems().setAll(EventPurpose.values());
        purpose.setConverter(converter(CustomerWorkflowService::displayPurpose));
        description.setPromptText("Event description (maximum 500 characters)");
        description.setWrapText(true);
        description.setPrefRowCount(4);
        description.getStyleClass().add("text-input");
        Label counter = Ui.muted("0/" + MAX_DESCRIPTION + " characters");
        description.textProperty().addListener((o, a, text) -> counter.setText(text.length() + "/" + MAX_DESCRIPTION + " characters"));
        reservation.valueProperty().addListener((o, a, chosen) -> attendance.setPromptText(chosen == null ? "Expected attendance"
                : CustomerWorkflowService.venueNamed(chosen.venueName()).map(v -> "Expected attendance (venue capacity " + v.capacity() + ")")
                .orElse("Expected attendance")));
        ToggleGroup visibility = new ToggleGroup();
        publicVisibility.setToggleGroup(visibility);
        privateVisibility.setToggleGroup(visibility);
        publicVisibility.setSelected(true);
        keyword.disableProperty().bind(privateVisibility.selectedProperty().not());
        reservation.getSelectionModel().selectFirst();
        type.getSelectionModel().selectFirst();
        purpose.getSelectionModel().selectFirst();

        GridPane details = Ui.form();
        Ui.row(details, "Venue reservation", reservation);
        Ui.row(details, "Event category", type);
        Ui.row(details, "Event purpose", purpose);
        Ui.row(details, "Event name", name);
        Ui.row(details, "Description", new VBox(4, description, counter));
        Ui.row(details, "Expected attendance", attendance);
        Ui.row(details, "Visibility", Ui.actions(publicVisibility, privateVisibility));
        Ui.row(details, "Access keyword", keyword);

        VBox tickets = ticketSection();
        tickets.visibleProperty().bind(purpose.valueProperty().isEqualTo(EventPurpose.TICKETED_EVENT));
        tickets.managedProperty().bind(tickets.visibleProperty());

        var publish = Ui.primary("Review & Publish", this::submit);
        publish.setId("create-publish");
        return new VBox(18, backCrumb(), Ui.card(Ui.section("Event details"), details), tickets, Ui.actions(publish));
    }

    private HBox backCrumb() {
        return new HBox(8, BackNavigation.button(() -> shell.goBack(MainShell.MY_EVENTS)), Ui.muted("Back"));
    }

    private VBox ticketSection() {
        TextField categoryName = Ui.field("Category name");
        TextField price = Ui.field("Ticket price (₱)");
        TextField quantity = Ui.field("Available quantity");
        TextField salesStart = Ui.field("Sales open: YYYY-MM-DD HH:mm");
        TextField salesDeadline = Ui.field("Sales close: YYYY-MM-DD HH:mm");
        categoryName.setId("category-name");
        price.setId("category-price");
        quantity.setId("category-quantity");
        salesStart.setId("category-sales-start");
        salesDeadline.setId("category-sales-deadline");
        var add = Ui.secondary("Add Category", () -> addCategory(categoryName, price, quantity, salesStart, salesDeadline));
        add.setId("category-add");
        HBox inputs = new HBox(8, categoryName, price, quantity, salesStart, salesDeadline, add);
        inputs.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(categoryName, Priority.ALWAYS);
        HBox.setHgrow(salesStart, Priority.ALWAYS);
        HBox.setHgrow(salesDeadline, Priority.ALWAYS);

        TableView<TicketCategory> table = Ui.table(List.of(), "Add at least one ticket category.");
        table.setItems(categories);
        Ui.column(table, "Category", TicketCategory::name);
        Ui.column(table, "Price", category -> Ui.php(category.price()));
        Ui.column(table, "Quantity", category -> String.valueOf(category.quantity()));
        Ui.column(table, "Sales open", category -> timeText(category.salesStart()));
        Ui.column(table, "Sales close", category -> timeText(category.salesDeadline()));
        var remove = Ui.secondary("Remove Selected", () -> categories.remove(table.getSelectionModel().getSelectedItem()));
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        return Ui.card(Ui.section("Ticket categories"), Ui.muted("Total ticket quantity cannot exceed the venue capacity."),
                inputs, table, Ui.actions(remove));
    }

    private void addCategory(TextField categoryName, TextField price, TextField quantity,
                             TextField salesStart, TextField salesDeadline) {
        try {
            String label = Ui.required(categoryName.getText(), "Category name");
            if (categories.stream().anyMatch(category -> category.name().equalsIgnoreCase(label)))
                throw new IllegalArgumentException("Category names must be unique. Enter a different name.");
            VenueReservation selected = reservation.getValue();
            if (selected == null) throw new IllegalArgumentException("Choose an approved venue reservation first.");
            LocalDateTime defaultStart = LocalDateTime.now().withSecond(0).withNano(0);
            LocalDateTime defaultDeadline = selected.date().atTime(selected.startTime()).minusMinutes(1);
            LocalDateTime opens = salesStart.getText().isBlank() ? defaultStart : parseSalesTime(salesStart.getText(), "Sales opening time");
            LocalDateTime closes = salesDeadline.getText().isBlank() ? defaultDeadline : parseSalesTime(salesDeadline.getText(), "Sales closing time");
            categories.add(new TicketCategory(label, Ui.amount(price.getText(), "Ticket price"),
                    Ui.whole(quantity.getText(), 1, Integer.MAX_VALUE, "Available quantity"), opens, closes));
            categoryName.clear(); price.clear(); quantity.clear(); salesStart.clear(); salesDeadline.clear();
        } catch (Exception ex) {
            Ui.error("Could not add ticket category", Ui.message(ex));
        }
    }

    private static LocalDateTime parseSalesTime(String text, String label) {
        try { return LocalDateTime.parse(Ui.required(text, label).replace(' ', 'T')); }
        catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException(label + ": enter YYYY-MM-DD HH:mm.");
        }
    }

    private static String timeText(LocalDateTime value) {
        return value == null ? "Default" : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT));
    }

    private void submit() {
        try {
            Customer customer = shell.session().customer();
            VenueReservation booking = reservation.getValue();
            if (booking == null) throw new IllegalArgumentException("Choose a venue reservation.");
            EventPurpose chosenPurpose = purpose.getValue();
            Venue venue = CustomerWorkflowService.reservedVenue(booking, chosenPurpose);
            String eventName = Ui.required(name.getText(), "Event name");
            String text = Ui.required(description.getText(), "Event description");
            if (text.length() > MAX_DESCRIPTION)
                throw new IllegalArgumentException("Description is too long. Please shorten it to 500 characters or fewer.");
            int expected = Ui.whole(attendance.getText(), 1, venue.capacity(), "Expected attendance");
            EventVisibility visibility = privateVisibility.isSelected() ? EventVisibility.PRIVATE : EventVisibility.PUBLIC;
            String accessKeyword = visibility == EventVisibility.PRIVATE ? Ui.required(keyword.getText(), "Private event access keyword") : "";
            List<TicketCategory> tickets = chosenPurpose == EventPurpose.TICKETED_EVENT ? List.copyOf(categories) : List.of();
            if (chosenPurpose == EventPurpose.TICKETED_EVENT && tickets.isEmpty())
                throw new IllegalArgumentException("Add at least one ticket category.");
            CustomerWorkflowService.requireTicketsFitVenue(tickets, venue);
            EventDraft draft = new EventDraft(eventName, text, type.getValue(), chosenPurpose, visibility, booking.date(),
                    booking.startTime(), booking.endTime(), expected, venue, tickets);
            if (!Ui.confirm("Confirm and create this event?", review(draft))) {
                Ui.info("Event creation cancelled.", "Your venue reservation remains valid.");
                return;
            }
            shell.services().workflow().createAndPublishEvent(customer, draft, booking, accessKeyword);
            Ui.info("Event published.", draft.name() + " is now live.");
            shell.showPage(MainShell.MY_EVENTS);
        } catch (Exception ex) {
            Ui.error("Event submission failed", Ui.message(ex));
        }
    }

    /** The console's printDraft summary. */
    private static String review(EventDraft draft) {
        StringBuilder text = new StringBuilder()
                .append("Event name: ").append(draft.name())
                .append("\nDescription: ").append(draft.description())
                .append("\nEvent type: ").append(draft.type())
                .append("\nEvent purpose: ").append(CustomerWorkflowService.displayPurpose(draft.purpose()))
                .append("\nVisibility: ").append(draft.visibility())
                .append("\nEvent date: ").append(draft.date())
                .append("\nTime: ").append(draft.startTime()).append(" – ").append(draft.endTime())
                .append("\nExpected attendance: ").append(draft.expectedAttendance())
                .append("\nVenue: ").append(draft.venue().name());
        for (TicketCategory category : draft.ticketCategories())
            text.append(String.format(Locale.ROOT, "%n  %s | %s | %d available | sales %s–%s", category.name(),
                    Ui.php(category.price()), category.quantity(), timeText(category.salesStart()), timeText(category.salesDeadline())));
        return text.toString();
    }

    private static <T> StringConverter<T> converter(Function<T, String> text) {
        return new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : text.apply(value); }
            @Override public T fromString(String string) { return null; }
        };
    }
}
