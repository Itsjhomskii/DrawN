package drawn.gui.views;

import drawn.model.event.Event;
import drawn.model.ticket.TicketPurchaseStatus;
import drawn.storage.TicketRepository;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Customer-facing digital ticket wallet. Purchases remain grouped exactly as persisted. */
final class TicketWalletPage {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter PURCHASED = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.ENGLISH);
    private final MainShell shell;
    private String filter = "All Tickets";
    private TicketRepository.Purchase selected;

    TicketWalletPage(MainShell shell) { this.shell = shell; }

    Node view() {
        if (selected != null) return detailView(selected);
        List<TicketRepository.Purchase> purchases;
        try {
            purchases = shell.services().publishing().purchases(shell.session().customer()).stream()
                    .sorted(java.util.Comparator.comparing(TicketRepository.Purchase::timestamp).reversed()).toList();
        } catch (Exception ex) {
            return Ui.page("My Tickets", "Your experiences, all in one place.",
                    Ui.muted("Could not load tickets: " + Ui.message(ex)));
        }

        ToggleGroup group = new ToggleGroup();
        HBox filters = new HBox(10);
        filters.getStyleClass().add("ticket-filters");
        for (String name : List.of("All Tickets", "Upcoming", "Past", "Cancelled")) {
            ToggleButton button = new ToggleButton(name);
            button.setUserData(name);
            button.setToggleGroup(group);
            button.setId("my-tickets-filter-" + name.toLowerCase(Locale.ROOT).replace(' ', '-'));
            button.getStyleClass().add("ticket-filter");
            if (name.equals(filter)) group.selectToggle(button);
            button.setOnAction(event -> {
                filter = name;
                shell.showPage(MainShell.TICKETS);
            });
            filters.getChildren().add(button);
        }

        if (purchases.isEmpty()) return Ui.page("My Tickets", "Your experiences, all in one place.", filters, emptyTickets());

        FlowPane cards = new FlowPane(16, 16);
        cards.setId("digital-ticket-grid");
        cards.getStyleClass().add("digital-ticket-grid");
        cards.setMaxWidth(Double.MAX_VALUE);
        cards.widthProperty().addListener((observable, oldWidth, newWidth) -> resizeCards(cards));
        List<TicketRepository.Purchase> matching = purchases.stream().filter(this::matchesFilter).toList();
        if (matching.isEmpty()) {
            Button reset = Ui.secondary("Return to All Tickets", () -> { filter = "All Tickets"; shell.showPage(MainShell.TICKETS); });
            reset.setId("all-tickets-reset");
            VBox empty = new VBox(8, Ui.section("No tickets match this filter."),
                    Ui.muted("Your other ticket purchases are still available under All Tickets."), reset);
            empty.getStyleClass().addAll("ticket-empty-state", "empty-state-bubble");
            cards.getChildren().add(empty);
        } else {
            cards.getChildren().setAll(matching.stream().map(this::ticketCard).toList());
        }
        resizeCards(cards);
        return Ui.page("My Tickets", "Your experiences, all in one place.", filters, cards);
    }

    private boolean matchesFilter(TicketRepository.Purchase purchase) {
        if (filter.equals("All Tickets")) return true;
        boolean cancelled = purchase.status() == TicketPurchaseStatus.CANCELLED
                || purchase.status() == TicketPurchaseStatus.REFUNDED;
        if (filter.equals("Cancelled")) return cancelled;
        if (cancelled || purchase.status() == TicketPurchaseStatus.FAILED) return false;
        boolean past = eventHasEnded(purchase);
        return switch (filter) {
            case "Upcoming" -> !past;
            case "Past" -> past;
            default -> true;
        };
    }

    private boolean eventHasEnded(TicketRepository.Purchase purchase) {
        try {
            Event event = shell.services().publishing().event(purchase.eventId());
            return LocalDateTime.of(event.date(), event.endTime()).isBefore(LocalDateTime.now());
        } catch (Exception ex) {
            return false;
        }
    }

    private Node ticketCard(TicketRepository.Purchase purchase) {
        Event event = event(purchase);
        VBox visual = new VBox(8, new DashboardIcon("fi fi-rr-ticket-alt", 38),
                new Label(event == null ? "DrawN ticket" : CustomerText.words(event.type())));
        visual.setAlignment(Pos.CENTER);
        visual.getStyleClass().add("digital-ticket-image-placeholder");

        Label type = new Label(event == null ? "EVENT" : CustomerText.words(event.type()).toUpperCase(Locale.ROOT));
        type.getStyleClass().add("ticket-category");
        Label title = Ui.title(event == null ? "Event details unavailable" : event.name());
        title.getStyleClass().add("ticket-event-title");
        title.setWrapText(true);
        Label status = new Label(ticketStatus(purchase, event));
        status.getStyleClass().addAll("ticket-status", ticketStatusClass(purchase, event));
        HBox titleLine = new HBox(10, title, Ui.grow(), status);
        titleLine.setAlignment(Pos.CENTER_LEFT);

        String category;
        try { category = shell.services().workflow().ticketCategoryName(purchase); }
        catch (Exception ex) { category = "Ticket"; }
        VBox eventInfo = new VBox(7, type, titleLine,
                infoRow("fi fi-rr-marker", event == null ? "Venue information unavailable" : event.venue().name()),
                infoRow("fi fi-rr-calendar", event == null ? "Event schedule unavailable" : schedule(event)));
        Region divider = new Region();
        divider.getStyleClass().add("digital-ticket-divider");
        Label categoryLabel = smallLabel("TICKET CATEGORY");
        Label quantityLabel = smallLabel("QUANTITY");
        Label categoryValue = new Label(category);
        categoryValue.getStyleClass().add("ticket-field-value");
        Label quantityValue = new Label(purchase.quantity() + (purchase.quantity() == 1 ? " Ticket" : " Tickets"));
        quantityValue.getStyleClass().add("ticket-field-value");
        VBox categoryField = new VBox(3, categoryLabel, categoryValue);
        VBox quantityField = new VBox(3, quantityLabel, quantityValue);
        HBox ticketFields = new HBox(14, categoryField, quantityField);
        ticketFields.getStyleClass().add("ticket-fields-row");
        Label amount = new Label(Ui.php(purchase.amount()));
        amount.getStyleClass().add("ticket-amount");
        Label reference = new Label("REF. " + purchase.id());
        reference.getStyleClass().add("ticket-reference");
        reference.setWrapText(true);
        Button view = Ui.primary("View Ticket", () -> { selected = purchase; shell.showPage(MainShell.TICKETS); });
        view.setId("view-ticket-" + purchase.id());
        view.getStyleClass().add("ticket-view-button");
        VBox priceAndReference = new VBox(3, amount, reference);
        HBox lower = new HBox(10, priceAndReference, Ui.grow(), view);
        lower.setAlignment(Pos.CENTER_LEFT);
        VBox ticketInfo = new VBox(8, eventInfo, divider, ticketFields, lower);
        ticketInfo.getStyleClass().add("digital-ticket-content");
        HBox card = new HBox(14, visual, ticketInfo);
        card.getStyleClass().add("digital-ticket-card");
        HBox.setHgrow(ticketInfo, Priority.ALWAYS);
        return card;
    }

    private Node detailView(TicketRepository.Purchase purchase) {
        Event event = event(purchase);
        HBox breadcrumb = new HBox(8, BackNavigation.button(() -> {
            selected = null;
            shell.showPage(MainShell.TICKETS);
        }), Ui.muted("My Tickets  ›  Ticket Details"));
        breadcrumb.setAlignment(Pos.CENTER_LEFT);

        VBox ticket = new VBox(12);
        ticket.getStyleClass().add("ticket-details-ticket");
        Label category = new Label(event == null ? "DRAWN TICKET" : CustomerText.words(event.type()).toUpperCase(Locale.ROOT));
        category.getStyleClass().add("ticket-category");
        ticket.getChildren().addAll(category,
                Ui.title(event == null ? "Event details unavailable" : event.name()),
                Ui.muted(event == null ? "Venue information unavailable" : event.venue().name()),
                Ui.muted(event == null ? "Event schedule unavailable" : schedule(event)));
        Region divider = new Region();
        divider.getStyleClass().add("digital-ticket-divider");
        String ticketType;
        try { ticketType = shell.services().workflow().ticketCategoryName(purchase); }
        catch (Exception ex) { ticketType = "Ticket"; }
        ticket.getChildren().addAll(divider, Ui.details("Ticket category", ticketType,
                "Quantity", String.valueOf(purchase.quantity()),
                "Total paid", Ui.php(purchase.amount()),
                "Purchase reference", purchase.id(),
                "Purchase date", purchase.timestamp().format(PURCHASED),
                "Ticket status", ticketStatus(purchase, event)));

        VBox actions = new VBox(10);
        if (event != null) {
            Button viewEvent = Ui.secondary("View Event", () -> new EventDetailsView(event,
                    shell.services(), shell.session(), () -> shell.showPage(MainShell.TICKETS)).show());
            actions.getChildren().add(viewEvent);
        }
        if (purchase.status() == TicketPurchaseStatus.ACTIVE) {
            Button cancel = Ui.danger("Cancel Ticket", () -> cancel(purchase));
            cancel.setId("cancel-ticket");
            actions.getChildren().add(cancel);
        }
        VBox management = new VBox(12, Ui.section("Ticket actions"), actions);
        management.getStyleClass().add("ticket-details-panel");
        FlowPane layout = new FlowPane(18, 18, ticket, management);
        layout.getStyleClass().add("ticket-details-layout");
        layout.setMaxWidth(Double.MAX_VALUE);
        layout.widthProperty().addListener((observable, oldWidth, newWidth) -> resizeDetails(layout));
        resizeDetails(layout);
        return Ui.page("Ticket Details", "Your digital ticket and purchase information.", breadcrumb, layout);
    }

    private void cancel(TicketRepository.Purchase purchase) {
        try {
            var quote = shell.services().publishing().cancellationQuote(shell.session().customer(), purchase.id());
            String summary = "Cancel " + purchase.quantity() + (purchase.quantity() == 1 ? " ticket" : " tickets")
                    + "?\n\nRefund: " + Ui.php(quote.refundAmount()) + " | Cancellation fee: "
                    + Ui.php(quote.cancellationFee()) + " (organizer " + Ui.php(quote.organizerFee())
                    + ", admin " + Ui.php(quote.adminFee()) + ").";
            if (!Ui.confirm("Confirm ticket cancellation?", summary)) return;
            shell.services().publishing().cancelTicket(shell.session().customer(), shell.session().account(), purchase.id());
            selected = null;
            shell.showPage(MainShell.TICKETS);
        } catch (Exception ex) {
            Ui.error("Ticket cancellation failed", Ui.message(ex));
        }
    }

    private Event event(TicketRepository.Purchase purchase) {
        try { return shell.services().publishing().event(purchase.eventId()); }
        catch (Exception ex) { return null; }
    }

    private String ticketStatus(TicketRepository.Purchase purchase, Event event) {
        return switch (purchase.status()) {
            case ACTIVE -> event == null ? "Active" : eventHasEnded(purchase) ? "Past" : "Upcoming";
            case CANCELLED -> "Cancelled";
            case REFUNDED -> "Refunded";
            case PENDING -> "Pending";
            case FAILED -> "Failed";
        };
    }

    private String ticketStatusClass(TicketRepository.Purchase purchase, Event event) {
        return switch (purchase.status()) {
            case CANCELLED, FAILED -> "ticket-status-cancelled";
            case REFUNDED -> "ticket-status-refunded";
            case PENDING -> "ticket-status-pending";
            case ACTIVE -> event != null && eventHasEnded(purchase) ? "ticket-status-past" : "ticket-status-active";
        };
    }

    private static String schedule(Event event) {
        return event.date().format(DATE) + "  •  " + CustomerText.time(event.startTime()) + " – " + CustomerText.time(event.endTime());
    }

    private static Node infoRow(String icon, String text) {
        Label value = Ui.muted(text);
        value.setWrapText(true);
        HBox row = new HBox(7, new DashboardIcon(icon, 16), value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label smallLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("ticket-field-label");
        return label;
    }

    private VBox emptyTickets() {
        VBox empty = new VBox(12, new DashboardIcon("fi fi-rr-ticket-alt", 42),
                Ui.section("No tickets yet."),
                Ui.muted("Your next experience starts with discovering an event."),
                Ui.primary("Explore Events", () -> shell.showPage(MainShell.EXPLORE)));
        empty.setAlignment(Pos.CENTER);
        empty.getStyleClass().addAll("ticket-empty-state", "empty-state-bubble");
        return empty;
    }

    private static void resizeCards(FlowPane cards) {
        double width = cards.getWidth();
        if (width <= 0) return;
        int columns = width >= 560 ? 2 : 1;
        double cardWidth = Math.max(220, (width - (columns - 1) * cards.getHgap() - 8) / columns);
        cards.getChildren().stream().filter(HBox.class::isInstance).map(HBox.class::cast)
                .filter(card -> card.getStyleClass().contains("digital-ticket-card"))
                .forEach(card -> card.setPrefWidth(cardWidth));
    }

    private static void resizeDetails(FlowPane layout) {
        double width = layout.getWidth();
        if (width <= 0 || layout.getChildren().size() < 2) return;
        int columns = width >= 700 ? 2 : 1;
        double columnWidth = Math.max(280, (width - (columns - 1) * layout.getHgap() - 8) / columns);
        layout.getChildren().forEach(node -> {
            if (node instanceof VBox box) box.setPrefWidth(columnWidth);
        });
    }
}
