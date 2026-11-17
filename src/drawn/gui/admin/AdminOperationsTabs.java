package drawn.gui.admin;

import drawn.model.payment.PaymentStatus;
import drawn.service.admin.AdminAuditLog;
import drawn.service.admin.AdminDashboardService;
import drawn.storage.TransactionJournal;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.List;

import static drawn.gui.admin.AdminView.column;
import static drawn.gui.admin.AdminView.field;
import static drawn.gui.admin.AdminView.label;
import static drawn.gui.admin.AdminView.section;
import static drawn.gui.admin.AdminView.table;

/**
 * Admin tabs backed by AdminDashboardService: venue and event approvals, ticket purchases, transactions,
 * revenue, recorded decisions and unfinished operations. Every decision goes through the service, reports its
 * outcome in the shared status line (no blocking pop-ups) and then rebuilds the screen from the CSV data.
 */
final class AdminOperationsTabs {
    private interface Loader { Node load() throws Exception; }
    private interface Decision { String run() throws Exception; }

    private final AdminDashboardService dashboard;
    private final String adminId;
    private final Label status;
    private final Runnable refresh;

    AdminOperationsTabs(AdminDashboardService dashboard, String adminId, Label status, Runnable refresh) {
        this.dashboard = dashboard; this.adminId = adminId; this.status = status; this.refresh = refresh;
    }

    List<Tab> tabs() {
        return List.of(tab("Venue Requests", this::venueRequests), tab("Event Approvals", this::eventApprovals),
                tab("Ticket Purchases", this::tickets), tab("Transactions", this::transactions),
                tab("Revenue", this::revenue), tab("Decisions", this::decisions),
                tab("Unfinished Operations", this::unfinished));
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
        TableView<AdminDashboardService.EventTicketSummary> summary = table(dashboard.ticketSummary(),
                "No tickets sold yet.", "admin-ticket-summary");
        column(summary, "Event", AdminDashboardService.EventTicketSummary::eventName);
        column(summary, "Sold", s -> String.valueOf(s.sold()));
        column(summary, "Cancelled", s -> String.valueOf(s.cancelled()));
        column(summary, "Remaining", s -> String.valueOf(s.remaining()));
        column(summary, "Active amount", s -> php(s.activeAmount()));
        return page("Every ticket purchase with its customer and event.", purchases, section("Per event"), summary);
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
        return page("Payments and wallet activity. A charge recorded in both the wallet and the payment ledger is shown once;"
                + " refunds are separate rows.", table);
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
