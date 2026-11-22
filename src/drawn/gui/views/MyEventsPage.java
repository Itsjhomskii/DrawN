package drawn.gui.views;

import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.ExtensionRequestStatus;
import drawn.model.event.VenueExtensionRequest;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentStatus;
import drawn.model.ticket.TicketType;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.ScrollPane;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Organizer events and reservations ready for complete-or-cancel event creation. */
final class MyEventsPage {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);
    private final MainShell shell;

    MyEventsPage(MainShell shell) { this.shell = shell; }

    Node view() {
        List<Event> events;
        List<VenueReservation> ready;
        try {
            events = shell.services().eventService().eventsFor(shell.session().customer()).stream()
                    .sorted(Comparator.comparing(Event::date).thenComparing(Event::startTime)).toList();
            ready = readyReservations();
        } catch (Exception ex) {
            return Ui.page("My Events", "Manage your published events and upcoming gatherings.",
                    Ui.muted("Could not load your organizer information: " + Ui.message(ex)));
        }

        FlowPane summaries = new FlowPane(14, 12);
        summaries.getStyleClass().add("organizer-summary-grid");
        summaries.setId("organizer-summary-grid");
        summaries.getChildren().setAll(
                summaryCard("Total Events", String.valueOf(events.size()), "fi fi-rr-calendar"),
                summaryCard("Published Events", String.valueOf(events.stream()
                        .filter(event -> event.approvalStatus() == EventApprovalStatus.PUBLISHED).count()), "fi fi-tr-ticket"),
                summaryCard("Ready to Organize", String.valueOf(ready.size()), "fi fi-rr-marker"));

        FlowPane eventCards = new FlowPane(16, 16);
        eventCards.getStyleClass().add("event-grid");
        eventCards.getStyleClass().add("organizer-event-grid");
        eventCards.setId("organizer-event-grid");
        eventCards.setMaxWidth(Double.MAX_VALUE);
        ComboBox<String> filter = new ComboBox<>();
        filter.getItems().setAll("All Events", "Published", "Pending", "Approved", "Declined", "Cancelled");
        filter.setValue("All Events");
        filter.setId("my-events-filter");
        Runnable refreshEvents = () -> {
            List<Event> visible = events.stream().filter(event -> matchesFilter(event, filter.getValue())).toList();
            if (visible.isEmpty()) {
                eventCards.getChildren().setAll(emptyEvents(events.isEmpty()
                        ? "Your events will appear here once you organize one."
                        : "No events match this status."));
            } else eventCards.getChildren().setAll(visible.stream().map(this::eventCard).toList());
            resizeCards(eventCards);
        };
        eventCards.widthProperty().addListener((o, old, width) -> resizeCards(eventCards));
        filter.valueProperty().addListener((o, old, value) -> refreshEvents.run());
        refreshEvents.run();

        HBox eventsHeader = new HBox(Ui.section("Your Events"), Ui.grow(), filter);
        eventsHeader.setAlignment(Pos.CENTER_LEFT);
        VBox eventsSection = new VBox(12, eventsHeader, eventCards);

        FlowPane reservationCards = new FlowPane(12, 12);
        reservationCards.getStyleClass().add("ready-reservation-list");
        reservationCards.setMaxWidth(Double.MAX_VALUE);
        if (ready.isEmpty()) {
            reservationCards.getChildren().add(Ui.muted("Approved and paid venue reservations waiting for an event will appear here."));
        } else {
            ready.forEach(reservation -> reservationCards.getChildren().add(readyReservationCard(reservation)));
        }
        reservationCards.widthProperty().addListener((o, old, width) -> resizeReadyCards(reservationCards));
        VBox readySection = new VBox(7,
                Ui.section("Ready to Organize"),
                Ui.muted("Approved and paid reservations waiting for an event."),
                reservationCards);
        readySection.getStyleClass().add("ready-to-organize-section");

        VBox content = new VBox(20,
                new VBox(4, Ui.title("My Events"), Ui.muted("Manage your published events and upcoming gatherings.")),
                summaries, eventsSection, readySection);
        content.getStyleClass().add("content-page");
        summaries.prefWidthProperty().bind(content.widthProperty());
        eventCards.prefWidthProperty().bind(content.widthProperty());
        reservationCards.prefWidthProperty().bind(content.widthProperty().subtract(32));
        summaries.widthProperty().addListener((o, old, width) -> resizeSummaryCards(summaries));
        resizeSummaryCards(summaries);
        return scrollable(content);
    }

    Node eventDetails(Event event) {
        HBox breadcrumb = new HBox(8, BackNavigation.button(() -> shell.goBack(MainShell.MY_EVENTS)),
                Ui.muted("My Events  ›  Event Details"));
        breadcrumb.setAlignment(Pos.CENTER_LEFT);

        StackPane photo = new StackPane(new DashboardIcon("fi fi-rr-calendar", 32));
        photo.getStyleClass().addAll("venue-image", "venue-detail-image", "organizer-event-detail-image");
        Label category = new Label(CustomerText.words(event.type()).toUpperCase(Locale.ROOT));
        category.getStyleClass().add("venue-category");
        VBox left = new VBox(12, photo, category, Ui.title(event.name()),
                Ui.details("Event type", CustomerText.words(event.type()),
                        "Venue", event.venue().name(),
                        "Date", event.date().format(DATE),
                        "Time", CustomerText.time(event.startTime()) + "–" + CustomerText.time(event.endTime()),
                        "Purpose", CustomerText.words(event.purpose()),
                        "Visibility", CustomerText.words(event.visibility())),
                Ui.card(Ui.section("About this event"), Ui.muted(event.description())));
        left.getStyleClass().add("venue-detail-left");

        Label status = statusBadge(event);
        VBox management = Ui.card(Ui.section("Management"), status,
                Ui.details("Expected attendance", String.valueOf(event.expectedAttendance()),
                        "Event status", CustomerText.eventStatus(event)));
        List<Node> actions = new java.util.ArrayList<>();
        if (canEdit(event)) {
            Button edit = Ui.secondary("Edit Event", () -> new EditEventDialog(shell.services(), shell.session(), event,
                    () -> shell.showPage(MainShell.MY_EVENTS)).show());
            edit.setId("edit-event");
            actions.add(edit);
        }
        if (canRequestExtension(event)) {
            Button extension = Ui.secondary("Request Time Extension", () -> requestExtension(event));
            extension.setId("request-event-extension");
            actions.add(extension);
        }
        if (canCancel(event)) {
            Button cancel = Ui.danger("Cancel Event", () -> cancel(event));
            cancel.setId("cancel-event");
            actions.add(cancel);
        }
        if (!actions.isEmpty()) {
            VBox actionList = new VBox(8);
            actionList.getChildren().setAll(actions);
            actions.stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .forEach(button -> button.setMaxWidth(Double.MAX_VALUE));
            management.getChildren().addAll(Ui.section("Actions"), actionList);
        }

        VBox ticketInfo = ticketInfo(event);
        VBox right = new VBox(16, management, ticketInfo);
        List<VenueExtensionRequest> extensions = extensionsFor(event);
        extensions.forEach(request -> right.getChildren().add(extensionSummary(request)));

        left.setMinWidth(0); right.setMinWidth(0);
        left.setMaxWidth(Double.MAX_VALUE); right.setMaxWidth(Double.MAX_VALUE);
        FlowPane layout = new FlowPane(24, 20, left, right);
        layout.getStyleClass().add("venue-detail-layout");
        layout.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(16, breadcrumb, layout);
        content.getStyleClass().add("content-page");
        content.getStyleClass().add("organizer-event-detail");
        layout.prefWidthProperty().bind(content.widthProperty());
        layout.widthProperty().addListener((o, old, width) -> resizeDetailColumns(layout, left, right));
        resizeDetailColumns(layout, left, right);
        return scrollable(content);
    }

    private List<VenueReservation> readyReservations() throws Exception {
        return shell.services().reservationService().forCustomer(shell.session().customer()).stream()
                .filter(reservation -> (reservation.status() == BookingStatus.APPROVED
                        || reservation.status() == BookingStatus.CONFIRMED)
                        && reservation.paymentStatus() == PaymentStatus.PAID
                        && reservation.eventId().isBlank())
                .sorted(Comparator.comparing(VenueReservation::date).thenComparing(VenueReservation::startTime)).toList();
    }

    private Node eventCard(Event event) {
        StackPane image = new StackPane(new DashboardIcon("fi fi-rr-calendar", 27));
        image.getStyleClass().addAll("venue-image", "organizer-event-image");
        Label type = new Label(CustomerText.words(event.type()).toUpperCase(Locale.ROOT));
        type.getStyleClass().add("venue-category");
        Label title = Ui.title(event.name());
        title.getStyleClass().add("event-card-title");
        HBox titleRow = new HBox(title, Ui.grow(), statusBadge(event));
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label venue = new Label(event.venue().name());
        venue.getStyleClass().add("secondary-text");
        Label schedule = new Label(event.date().format(DATE) + "  ·  " + CustomerText.time(event.startTime())
                + "–" + CustomerText.time(event.endTime()));
        schedule.getStyleClass().add("secondary-text");
        HBox actions = new HBox(8);
        actions.getStyleClass().add("event-card-actions");
        Button details = Ui.primary("View Details", () -> shell.showMyEventDetails(event));
        details.setId("event-details-" + safeId(event.id()));
        actions.getChildren().add(details);
        if (canEdit(event)) {
            Button edit = Ui.secondary("Edit Event", () -> new EditEventDialog(shell.services(), shell.session(), event,
                    () -> shell.showPage(MainShell.MY_EVENTS)).show());
            edit.setId("event-edit-" + safeId(event.id()));
            actions.getChildren().add(edit);
        }
        if (canCancel(event)) {
            Button cancel = Ui.danger("Cancel Event", () -> cancel(event));
            cancel.setId("event-cancel-" + safeId(event.id()));
            actions.getChildren().add(cancel);
        }
        actions.getChildren().stream().filter(Button.class::isInstance).map(Button.class::cast).forEach(button -> {
            HBox.setHgrow(button, Priority.ALWAYS);
            button.setMaxWidth(Double.MAX_VALUE);
        });
        VBox card = new VBox(8, image, type, titleRow, venue, schedule, actions);
        card.getStyleClass().addAll("venue-card", "organizer-event-card");
        card.setMinWidth(220);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private Node readyReservationCard(VenueReservation reservation) {
        StackPane image = new StackPane(new DashboardIcon("fi fi-rr-marker", 24));
        image.getStyleClass().addAll("venue-image", "ready-reservation-image");
        VBox info = new VBox(5, Ui.section(reservation.venueName()),
                Ui.muted(reservation.date().format(DATE) + "  ·  " + CustomerText.time(reservation.startTime())
                        + "–" + CustomerText.time(reservation.endTime())),
                Ui.muted("Reservation approved and paid"));
        HBox card = new HBox(14, image, info, Ui.grow());
        Button create = Ui.primary("Create Event", () -> shell.showPage(MainShell.CREATE_EVENT));
        create.setId("create-event-from-reservation-" + safeId(reservation.id()));
        card.getChildren().add(create);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("ready-reservation-card");
        HBox.setHgrow(info, Priority.ALWAYS);
        return card;
    }

    private VBox ticketInfo(Event event) {
        VBox card = Ui.card(Ui.section("Ticket categories"));
        card.setId("event-ticket-categories");
        if (event.purpose() != drawn.model.event.EventPurpose.TICKETED_EVENT) {
            card.getChildren().add(Ui.muted("This event does not use ticket categories."));
            return card;
        }
        try {
            List<TicketType> types = shell.services().publishing().ticketTypes(event.id());
            if (types.isEmpty()) card.getChildren().add(Ui.muted("No ticket categories are configured."));
            else for (TicketType ticket : types) {
                card.getChildren().add(Ui.details("Category", ticket.name(),
                        "Price", Ui.php(ticket.price()),
                        "Available", String.valueOf(ticket.remaining()),
                        "Sold", String.valueOf(ticket.sold())));
            }
        } catch (Exception ex) {
            card.getChildren().add(Ui.muted("Ticket categories could not be loaded: " + Ui.message(ex)));
        }
        return card;
    }

    private List<VenueExtensionRequest> extensionsFor(Event event) {
        try { return shell.services().venueExtensions().requestsForEvent(shell.session().customer(), event.id()); }
        catch (Exception ex) { return List.of(); }
    }

    private VBox extensionSummary(VenueExtensionRequest request) {
        String state = switch (request.status()) {
            case PENDING -> "Awaiting administrator review";
            case APPROVED -> "Approved";
            case DECLINED -> "Declined";
            case CANCELLED -> "Cancelled";
        };
        VBox summary = Ui.card(Ui.section("Time extension request"), Ui.details(
                "Requested end", CustomerText.time(request.requestedEndTime()),
                "Estimated added fee", Ui.php(request.additionalCost()), "Status", state),
                Ui.muted("Extension payment is not configured. Approval updates the schedule without charging the wallet."));
        if (!request.decisionReason().isBlank()) summary.getChildren().add(Ui.muted("Reason: " + request.decisionReason()));
        if (request.status() == ExtensionRequestStatus.PENDING)
            summary.getChildren().add(Ui.muted("The request is awaiting administrator review."));
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
            Ui.info("Extension request submitted", "Estimated added fee: " + Ui.php(request.additionalCost())
                    + ". Extension billing is not configured, so approval will update the schedule without charging your wallet.");
            shell.showPage(MainShell.MY_EVENTS);
        } catch (Exception ex) {
            Ui.error("Could not request time extension", Ui.message(ex));
        }
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

    private static boolean matchesFilter(Event event, String filter) {
        if (filter == null || filter.equals("All Events")) return true;
        return CustomerText.eventStatus(event).equalsIgnoreCase(filter);
    }

    private static boolean canEdit(Event event) {
        return event.approvalStatus() == EventApprovalStatus.PUBLISHED;
    }

    private static boolean canCancel(Event event) {
        EventApprovalStatus status = event.approvalStatus();
        boolean supported = status == EventApprovalStatus.PENDING || status == EventApprovalStatus.REJECTED
                || status == EventApprovalStatus.APPROVED || status == EventApprovalStatus.PUBLISHED
                || status == EventApprovalStatus.CANCELLATION_PENDING;
        return supported && event.date().isAfter(LocalDate.now());
    }

    private static boolean canRequestExtension(Event event) {
        return event.approvalStatus() == EventApprovalStatus.PUBLISHED
                && event.date().atTime(event.startTime()).isAfter(LocalDateTime.now());
    }

    private static Label statusBadge(Event event) {
        Label badge = new Label(CustomerText.eventStatus(event));
        badge.setId("event-status-" + safeId(event.id()));
        badge.getStyleClass().addAll("reservation-status", statusStyle(event.approvalStatus()));
        return badge;
    }

    private static String statusStyle(EventApprovalStatus status) {
        return switch (status) {
            case PUBLISHED, APPROVED -> "status-approved";
            case PENDING, CANCELLATION_PENDING, DRAFT -> "status-pending";
            case REJECTED -> "status-declined";
            case CANCELLED -> "status-cancelled";
        };
    }

    private static VBox summaryCard(String heading, String value, String icon) {
        Label label = new Label(heading);
        label.getStyleClass().add("organizer-summary-label");
        Label count = new Label(value);
        count.getStyleClass().add("organizer-summary-value");
        VBox text = new VBox(4, label, count);
        HBox row = new HBox(text, Ui.grow(), new DashboardIcon(icon, 25));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("organizer-summary-content");
        VBox card = new VBox(row);
        card.getStyleClass().add("organizer-summary-card");
        return card;
    }

    private static Node emptyEvents(String text) {
        VBox empty = new VBox(8, Ui.section("No events to show"), Ui.muted(text));
        empty.getStyleClass().addAll("empty-state", "organizer-empty-state");
        return empty;
    }

    private static void resizeCards(FlowPane cards) {
        double width = cards.getWidth();
        if (width <= 0) return;
        int columns = width >= 650 ? 2 : 1;
        double cardWidth = Math.max(260, (width - (columns - 1) * cards.getHgap() - 8) / columns);
        cards.getChildren().stream().filter(VBox.class::isInstance).map(VBox.class::cast)
                .forEach(card -> card.setPrefWidth(cardWidth));
    }

    private static void resizeReadyCards(FlowPane cards) {
        double width = cards.getWidth();
        if (width <= 0) return;
        cards.getChildren().stream().filter(HBox.class::isInstance).map(HBox.class::cast)
                .forEach(card -> card.setPrefWidth(width - 2));
    }

    private static void resizeSummaryCards(FlowPane cards) {
        double width = cards.getWidth();
        if (width <= 0) return;
        double cardWidth = Math.max(210, (width - 2 * cards.getHgap()) / 3 - 2);
        cards.getChildren().stream().filter(VBox.class::isInstance).map(VBox.class::cast)
                .forEach(card -> card.setPrefWidth(cardWidth));
    }

    private static void resizeDetailColumns(FlowPane layout, VBox left, VBox right) {
        double width = layout.getWidth();
        if (width <= 0) return;
        double columnWidth = width < 760 ? width : (width - layout.getHgap()) / 2;
        left.setPrefWidth(columnWidth);
        right.setPrefWidth(columnWidth);
    }

    private static String safeId(String value) { return value.replaceAll("[^A-Za-z0-9_-]", "-"); }

    private static ScrollPane scrollable(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        return scroll;
    }
}
