package drawn.gui.admin;

import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueType;
import drawn.model.payment.PaymentStatus;
import drawn.model.payment.PaymentPurpose;
import drawn.model.ticket.TicketPurchaseStatus;
import drawn.service.admin.AdminAuditLog;
import drawn.service.admin.AdminDashboardService;
import drawn.service.admin.VenueService;
import drawn.storage.TransactionJournal;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static drawn.gui.admin.AdminView.column;
import static drawn.gui.admin.AdminView.field;
import static drawn.gui.admin.AdminView.label;
import static drawn.gui.admin.AdminView.section;
import static drawn.gui.admin.AdminView.table;

/**
 * Admin tabs backed by VenueService (the bookable venue catalog) and AdminDashboardService: venue and event
 * approvals, ticket purchases, transactions, revenue, recorded decisions and unfinished operations. Every action goes through its service, reports its
 * outcome in the shared status line (no blocking pop-ups) and then rebuilds the screen from the CSV data.
 */
final class AdminOperationsTabs {
    private interface Loader { Node load() throws Exception; }
    private interface Decision { String run() throws Exception; }

    private final AdminDashboardService dashboard;
    private final VenueService venueService;
    private final String adminId;
    private final Label status;
    private final Runnable refresh;

    AdminOperationsTabs(AdminDashboardService dashboard, VenueService venueService, String adminId, Label status, Runnable refresh) {
        this.dashboard = dashboard; this.venueService = venueService;
        this.adminId = adminId; this.status = status; this.refresh = refresh;
    }

    List<Tab> tabs() {
        return List.of(tab("Venues", this::venues), tab("Venue Requests", this::venueRequests), tab("Event Approvals", this::eventApprovals),
                tab("Extension Requests", this::extensions),
                tab("Ticket Purchases", this::tickets), tab("Transactions", this::transactions),
                tab("Revenue", this::revenue), tab("Decisions", this::decisions),
                tab("Unfinished Operations", this::unfinished));
    }

    private Node venues() {
        TableView<VenueCatalog.Entry> table = table(venueService.venues(), "No venues in the catalog.", "admin-venues-table");
        column(table, "Venue", entry -> entry.venue().name());
        column(table, "Category", VenueCatalog.Entry::category);
        column(table, "Hosts", entry -> purposeLabel(entry.venue().type()));
        column(table, "Capacity", entry -> String.valueOf(entry.venue().capacity()));
        column(table, "Hourly rate", entry -> php(entry.venue().hourlyRate()));
        column(table, "Half-day rate", entry -> php(entry.venue().morningRate()));
        Button remove = secondary("Remove selected venue", "admin-remove-venue", () -> {
            Venue venue = selected(table).venue();
            venueService.remove(venue.name());
            return "Removed " + venue.name() + " from the venue catalog.";
        });
        return page("Venues organizers can request. Changes reach customers immediately and are saved for the next start. "
                + "A venue with active or upcoming reservations cannot be removed.", addVenueCard(), table, remove);
    }

    private Node addVenueCard() {
        TextField name = inputField("Venue name", "admin-venue-name");
        TextField category = inputField("Category, for example Hotel or Restaurant", "admin-venue-category");
        TextField capacity = inputField("Capacity (people)", "admin-venue-capacity");
        TextField hourly = inputField("Hourly rate (PHP)", "admin-venue-hourly");
        TextField halfDay = inputField("Half-day rate (PHP)", "admin-venue-half-day");
        ComboBox<VenueType> purpose = new ComboBox<>();
        purpose.setId("admin-venue-hosts");
        purpose.getItems().addAll(VenueType.values());
        purpose.setValue(VenueType.BOTH);
        purpose.setMaxWidth(Double.MAX_VALUE);
        purpose.setConverter(new StringConverter<VenueType>() {
            @Override public String toString(VenueType type) { return type == null ? "" : purposeLabel(type); }
            @Override public VenueType fromString(String text) { return null; }
        });

        String[] labels = {"Venue name", "Category", "Hosts", "Capacity", "Hourly rate (PHP)", "Half-day rate (PHP)"};
        Node[] inputs = {name, category, purpose, capacity, hourly, halfDay};
        GridPane form = new GridPane();
        form.setHgap(14); form.setVgap(10);
        for (int row = 0; row < inputs.length; row++) {
            form.add(label(labels[row]), 0, row);
            form.add(inputs[row], 1, row);
            GridPane.setHgrow(inputs[row], Priority.ALWAYS);
        }
        Button add = primary("Add venue", "admin-add-venue", () -> {
            Venue venue = venueService.add(name.getText(), category.getText(), purpose.getValue(),
                    whole(capacity, "Capacity"), amount(hourly, "Hourly rate"), amount(halfDay, "Half-day rate"));
            return "Added " + venue.name() + " to the venue catalog.";
        });
        VBox card = new VBox(14, section("Add venue"), form, add);
        card.getStyleClass().add("admin-card");
        return card;
    }

    private static TextField inputField(String prompt, String id) {
        TextField input = field(prompt);
        input.setId(id);
        return input;
    }

    private static int whole(TextField input, String label) {
        try { return Integer.parseInt(input.getText().trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(label + " must be a whole number."); }
    }

    private static BigDecimal amount(TextField input, String label) {
        try { return new BigDecimal(input.getText().trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(label + " must be a number."); }
    }

    private static String purposeLabel(VenueType type) {
        return switch (type) {
            case NON_COMMERCIAL -> "Social gatherings";
            case COMMERCIAL -> "Ticketed events";
            case BOTH -> "Both";
        };
    }

    private Node venueRequests() throws Exception {
        List<AdminDashboardService.PendingApproval> paid = dashboard.pendingApprovals().stream()
                .filter(r -> r.paymentStatus() == PaymentStatus.PAID || r.paymentStatus() == PaymentStatus.VERIFIED).toList();
        TableView<AdminDashboardService.PendingApproval> table = table(paid, "No paid venue requests are waiting.", "admin-venue-requests");
        column(table, "Customer", AdminDashboardService.PendingApproval::customerName);
        column(table, "Venue", AdminDashboardService.PendingApproval::venueName);
        column(table, "Date", r -> String.valueOf(r.date()));
        column(table, "Time", r -> r.startTime() + "–" + r.endTime());
        column(table, "Cost", r -> php(r.cost()));
        column(table, "Payment", r -> r.paymentStatus().name());
        column(table, "Status", r -> "PENDING");
        column(table, "Event", r -> r.eventName().isBlank() ? "(not linked yet)" : r.eventName());

        TextField reason = field("Decline reason (required to decline)");
        reason.setId("admin-decline-reason");
        Button approve = primary("Approve", "admin-approve-venue", () -> {
            var selected = selected(table);
            dashboard.approve(adminId, selected.reservationId());
            return "Approved " + selected.venueName() + " for " + selected.customerName() + ".";
        });
        Button decline = secondary("Decline and refund", "admin-decline-venue", () -> {
            var selected = selected(table);
            var declined = dashboard.decline(adminId, selected.reservationId(), reason.getText());
            return "Declined " + selected.venueName() + " for " + selected.customerName() + ". Payment: " + declined.paymentStatus() + ".";
        });
        HBox actions = new HBox(10, reason, approve, decline);
        HBox.setHgrow(reason, Priority.ALWAYS);
        return page("Paid venue requests waiting for a decision. Declining refunds the customer's wallet.", table, actions);
    }

    private Node eventApprovals() throws Exception {
        TableView<AdminDashboardService.PendingEvent> table = table(dashboard.pendingEvents(),
                "No customer-submitted events are waiting for review.", "admin-pending-events");
        column(table, "Event", AdminDashboardService.PendingEvent::name);
        column(table, "Organizer", AdminDashboardService.PendingEvent::organizerName);
        column(table, "Date", e -> String.valueOf(e.date()));
        column(table, "Venue", AdminDashboardService.PendingEvent::venueName);
        Button approve = primary("Approve event", "admin-approve-event", () -> {
            var selected = selected(table);
            dashboard.reviewEvent(adminId, selected.eventId(), true);
            return "Approved event " + selected.name() + ".";
        });
        Button reject = secondary("Reject event", "admin-reject-event", () -> {
            var selected = selected(table);
            dashboard.reviewEvent(adminId, selected.eventId(), false);
            return "Rejected event " + selected.name() + ".";
        });
        return page("Customer-submitted events. This is separate from venue approval.", table, new HBox(10, approve, reject));
    }

    private Node extensions() throws Exception {
        TableView<AdminDashboardService.PendingExtension> table = table(dashboard.pendingExtensions(),
                "No venue extension requests are waiting.", "admin-extension-requests");
        column(table, "Event", AdminDashboardService.PendingExtension::eventName);
        column(table, "Reservation ID", AdminDashboardService.PendingExtension::reservationId);
        column(table, "Customer", AdminDashboardService.PendingExtension::customerName);
        column(table, "Venue", AdminDashboardService.PendingExtension::venueName);
        column(table, "Date", r -> String.valueOf(r.date()));
        column(table, "Original schedule", r -> r.startTime() + "–" + r.currentEndTime());
        column(table, "Requested schedule", r -> r.startTime() + "–" + r.requestedEndTime());
        column(table, "Status", r -> r.status().name());
        column(table, "Estimated fee", r -> php(r.estimatedAdditionalCost()));

        TextField reason = field("Decline reason (required to decline)");
        reason.setId("admin-extension-decline-reason");
        Button approve = primary("Approve schedule extension", "admin-approve-extension", () -> {
            var selected = selected(table);
            dashboard.approveExtension(adminId, selected.requestId());
            return "Approved the schedule extension for " + selected.eventName()
                    + ". No additional payment was collected; extension billing is not configured.";
        });
        Button decline = secondary("Decline extension", "admin-decline-extension", () -> {
            var selected = selected(table);
            dashboard.declineExtension(adminId, selected.requestId(), reason.getText());
            return "Declined the extension for " + selected.eventName() + ".";
        });
        return page("Approval updates the event and venue schedule. The displayed fee is an estimate; no extension payment policy is configured, so this action does not charge the wallet.",
                table, new HBox(10, reason, approve, decline));
    }

    private Node tickets() throws Exception {
        TableView<AdminDashboardService.TicketRow> purchases = table(dashboard.tickets(AdminDashboardService.TicketFilter.ALL),
                "No ticket purchases yet.", "admin-ticket-purchases");
        column(purchases, "Purchased", t -> t.timestamp().toLocalDate().toString());
        column(purchases, "Customer", AdminDashboardService.TicketRow::customerName);
        column(purchases, "Event", AdminDashboardService.TicketRow::eventName);
        column(purchases, "Ticket", AdminDashboardService.TicketRow::ticketType);
        column(purchases, "Qty", t -> String.valueOf(t.quantity()));
        column(purchases, "Amount", t -> php(t.amount()));
        column(purchases, "Status", t -> t.status().name());
        column(purchases, "Purchase ID", AdminDashboardService.TicketRow::purchaseId);
        ComboBox<TicketPurchaseStatus> status = new ComboBox<>();
        status.setId("admin-ticket-status");
        status.setPromptText("All statuses");
        status.getItems().addAll(TicketPurchaseStatus.values());
        status.setConverter(new StringConverter<>() {
            @Override public String toString(TicketPurchaseStatus value) { return value == null ? "" : value.name(); }
            @Override public TicketPurchaseStatus fromString(String value) { return null; }
        });
        TextField eventId = inputField("Event ID (optional)", "admin-ticket-event-id");
        TextField customerId = inputField("Customer ID (optional)", "admin-ticket-customer-id");
        DatePicker from = new DatePicker(); from.setId("admin-ticket-from"); from.setPromptText("From date");
        DatePicker to = new DatePicker(); to.setId("admin-ticket-to"); to.setPromptText("To date");
        Button apply = filterButton("Apply filters", "admin-ticket-apply", () -> {
            LocalDate start = from.getValue(); LocalDate end = to.getValue();
            validateDateRange(start, end);
            var filter = new AdminDashboardService.TicketFilter(blankToNull(eventId.getText()),
                    blankToNull(customerId.getText()), status.getValue(), startOfDay(start), endOfDay(end));
            purchases.getItems().setAll(dashboard.tickets(filter));
            return "Ticket filters applied.";
        });
        Button clear = filterButton("Clear", "admin-ticket-clear", () -> {
            eventId.clear(); customerId.clear(); status.setValue(null); from.setValue(null); to.setValue(null);
            purchases.getItems().setAll(dashboard.tickets(AdminDashboardService.TicketFilter.ALL));
            return "Ticket filters cleared.";
        });
        GridPane filterForm = filtersGrid(
                new String[]{"Event ID", "Customer ID", "Status", "From", "To"},
                new Node[]{eventId, customerId, status, from, to});
        HBox filterActions = new HBox(8, apply, clear);
        TableView<AdminDashboardService.EventTicketSummary> summary = table(dashboard.ticketSummary(),
                "No tickets sold yet.", "admin-ticket-summary");
        column(summary, "Event", AdminDashboardService.EventTicketSummary::eventName);
        column(summary, "Sold", s -> String.valueOf(s.sold()));
        column(summary, "Cancelled", s -> String.valueOf(s.cancelled()));
        column(summary, "Remaining", s -> String.valueOf(s.remaining()));
        column(summary, "Active amount", s -> php(s.activeAmount()));
        return page("Every ticket purchase with its customer and event. Filters use event and customer IDs and the recorded purchase date.",
                filterForm, filterActions, purchases, section("Per event"), summary);
    }

    private Node transactions() throws Exception {
        TableView<AdminDashboardService.TransactionRow> table = table(
                dashboard.transactions(AdminDashboardService.TransactionFilter.ALL), "No transactions yet.", "admin-transactions");
        column(table, "Date", t -> t.timestamp().toLocalDate().toString());
        column(table, "Customer", AdminDashboardService.TransactionRow::customerName);
        column(table, "Purpose", t -> t.purpose() == null ? t.description() : t.purpose().name());
        column(table, "Status", AdminDashboardService.TransactionRow::status);
        column(table, "Amount", t -> php(t.amount()));
        column(table, "Related", t -> t.related().isBlank() ? "—" : t.related());
        column(table, "Reference", t -> t.referenceId().isBlank() ? "—" : t.referenceId());
        column(table, "Source", AdminDashboardService.TransactionRow::source);
        TextField customerId = inputField("Customer ID (optional)", "admin-transaction-customer-id");
        ComboBox<PaymentPurpose> purpose = new ComboBox<>();
        purpose.setId("admin-transaction-purpose"); purpose.setPromptText("All purposes");
        purpose.getItems().addAll(PaymentPurpose.values());
        purpose.setConverter(new StringConverter<>() {
            @Override public String toString(PaymentPurpose value) { return value == null ? "" : value.name(); }
            @Override public PaymentPurpose fromString(String value) { return null; }
        });
        DatePicker from = new DatePicker(); from.setId("admin-transaction-from"); from.setPromptText("From date");
        DatePicker to = new DatePicker(); to.setId("admin-transaction-to"); to.setPromptText("To date");
        Button apply = filterButton("Apply filters", "admin-transaction-apply", () -> {
            LocalDate start = from.getValue(); LocalDate end = to.getValue();
            validateDateRange(start, end);
            var filter = new AdminDashboardService.TransactionFilter(blankToNull(customerId.getText()),
                    purpose.getValue(), startOfDay(start), endOfDay(end));
            table.getItems().setAll(dashboard.transactions(filter));
            return "Transaction filters applied.";
        });
        Button clear = filterButton("Clear", "admin-transaction-clear", () -> {
            customerId.clear(); purpose.setValue(null); from.setValue(null); to.setValue(null);
            table.getItems().setAll(dashboard.transactions(AdminDashboardService.TransactionFilter.ALL));
            return "Transaction filters cleared.";
        });
        GridPane filterForm = filtersGrid(new String[]{"Customer ID", "Purpose", "From", "To"},
                new Node[]{customerId, purpose, from, to});
        HBox filterActions = new HBox(8, apply, clear);
        return page("Payments and wallet activity. A charge recorded in both the wallet and the payment ledger is shown once;"
                + " refunds are separate rows. Customer ID, purpose, and purchase date filters are supported.",
                filterForm, filterActions, table);
    }

    private static GridPane filtersGrid(String[] labels, Node[] controls) {
        GridPane grid = new GridPane(); grid.setHgap(10); grid.setVgap(8);
        for (int i = 0; i < controls.length; i++) {
            grid.add(label(labels[i]), i, 0);
            grid.add(controls[i], i, 1);
            GridPane.setHgrow(controls[i], Priority.ALWAYS);
        }
        return grid;
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static LocalDateTime startOfDay(LocalDate value) { return value == null ? null : value.atStartOfDay(); }
    private static LocalDateTime endOfDay(LocalDate value) {
        return value == null ? null : value.plusDays(1).atStartOfDay().minusNanos(1);
    }
    private static void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from))
            throw new IllegalArgumentException("To date must be on or after From date.");
    }

    private Node revenue() throws Exception {
        AdminDashboardService.RevenueSummary revenue = dashboard.revenueSummary();
        GridPane grid = new GridPane();
        grid.setHgap(24); grid.setVgap(10);
        String[][] rows = {{"Venue revenue", php(revenue.venueRevenue()), "admin-revenue-venue"},
                {"Ticket sales (gross)", php(revenue.ticketGross()), "admin-revenue-tickets"},
                {"Platform commission", php(revenue.platformCommission()), "admin-revenue-commission"},
                {"Refunds issued", php(revenue.refunds()), "admin-revenue-refunds"}};
        for (int i = 0; i < rows.length; i++) {
            Label value = new Label(rows[i][1]);
            value.setId(rows[i][2]); value.getStyleClass().add("admin-section");
            grid.add(label(rows[i][0]), 0, i); grid.add(value, 1, i);
        }
        VBox card = new VBox(14, section("Revenue"), grid);
        card.getStyleClass().add("admin-card");
        return page("From the payment ledger. Refunded payments are not counted as revenue.", card);
    }

    private Node decisions() throws Exception {
        TableView<AdminAuditLog.Decision> table = table(dashboard.decisions(), "No admin decisions recorded yet.", "admin-decisions");
        column(table, "When", AdminAuditLog.Decision::timestamp);
        column(table, "Admin", AdminAuditLog.Decision::admin);
        column(table, "Action", AdminAuditLog.Decision::action);
        column(table, "Target", AdminAuditLog.Decision::targetId);
        column(table, "Detail", AdminAuditLog.Decision::detail);
        return page("Read-only record of approvals and declines.", table);
    }

    private Node unfinished() throws Exception {
        TableView<TransactionJournal.Entry> table = table(dashboard.stuckOperations(),
                "No unfinished operations.", "admin-unfinished");
        column(table, "Operation", TransactionJournal.Entry::id);
        column(table, "Kind", TransactionJournal.Entry::kind);
        column(table, "State", TransactionJournal.Entry::state);
        column(table, "Details", e -> String.join(", ", e.payload()));
        return page("Read-only. Operations that started but did not finish; they are completed on the next app start.", table);
    }

    // ---- helpers

    private Tab tab(String title, Loader loader) {
        Node content;
        try { content = loader.load(); }
        catch (Exception ex) { content = page("Could not load " + title.toLowerCase() + ": " + AdminApplication.message(ex)); }
        Tab tab = new Tab(title, new AdminView.ScrollWrapper(content).node);
        tab.setId("admin-tab-" + title.toLowerCase().replace(' ', '-'));
        return tab;
    }

    private static VBox page(String subtitle, Node... content) {
        Label note = label(subtitle);
        note.setWrapText(true);
        VBox body = new VBox(14, note);
        body.getChildren().addAll(content);
        body.getStyleClass().add("admin-content");
        return body;
    }

    private Button primary(String text, String id, Decision decision) { return button(text, id, "admin-primary", decision); }
    private Button secondary(String text, String id, Decision decision) { return button(text, id, "admin-secondary", decision); }

    private Button filterButton(String text, String id, Decision decision) {
        Button button = new Button(text);
        button.setId(id); button.getStyleClass().add("admin-secondary");
        button.setOnAction(e -> {
            try { show(decision.run(), false); }
            catch (Exception ex) { show(AdminApplication.message(ex), true); }
        });
        return button;
    }

    private Button button(String text, String id, String style, Decision decision) {
        Button button = new Button(text);
        button.setId(id); button.getStyleClass().add(style);
        button.setOnAction(e -> decide(decision));
        return button;
    }

    private void decide(Decision decision) {
        try {
            String done = decision.run();
            refresh.run();
            show(done, false);
        } catch (Exception ex) {
            show(AdminApplication.message(ex), true);
        }
    }

    private void show(String message, boolean error) {
        status.setText(error ? "Could not complete that: " + message : message);
    }

    private static <T> T selected(TableView<T> table) {
        T row = table.getSelectionModel().getSelectedItem();
        if (row == null) throw new IllegalArgumentException("Select a row first.");
        return row;
    }

    private static String php(BigDecimal amount) { return "PHP " + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(); }
}
