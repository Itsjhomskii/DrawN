package drawn.service.admin;

import drawn.gui.AppServices;
import drawn.model.event.*;
import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.ticket.TicketPurchaseStatus;
import drawn.model.ticket.TicketType;
import drawn.model.user.ContactMethod;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Disposable check of the admin dashboard backend. Run with: java -ea -cp ... drawn.service.admin.AdminDashboardServiceTest */
public final class AdminDashboardServiceTest {
    private AdminDashboardServiceTest() { }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawn-admin-dashboard-");
        AppServices services = new AppServices(data);
        AdminDashboardService admin = services.adminDashboard();
        var owner = services.customerAccess().register("Jamie", "Organizer", LocalDate.now().minusYears(30),
                ContactMethod.EMAIL, "owner@example.test", "pass", new BigDecimal("100000.00"));
        var buyer = services.customerAccess().register("Taylor", "Buyer", LocalDate.now().minusYears(25),
                ContactMethod.EMAIL, "buyer@example.test", "pass", new BigDecimal("1000.00"));
        Venue venue = VenueCatalog.all().stream().filter(v -> v.type() == VenueType.BOTH).findFirst().orElseThrow();
        LocalDate date = LocalDate.now().plusDays(45);

        // Approval queue: a paid venue request appears with the customer's display name.
        var toDecline = services.venueWorkflow().requestAndPay(owner.customer(), owner.account(), venue, date,
                LocalTime.of(9, 0), LocalTime.of(11, 0), ReservationPricing.HOURLY);
        var queue = admin.pendingApprovals();
        assert queue.size() == 1 : "expected one pending request";
        assert queue.get(0).customerName().equals("Jamie Organizer") : queue.get(0).customerName();
        assert queue.get(0).paymentStatus() == PaymentStatus.PAID;

        // Decline: reason required, refund issued, audit row written.
        try { admin.decline("admin@drawn.com", toDecline.id(), " "); throw new AssertionError("blank reason accepted"); }
        catch (IllegalArgumentException expected) { }
        var declined = admin.decline("admin@drawn.com", toDecline.id(), "Venue under maintenance");
        assert declined.status() == BookingStatus.DECLINED;
        assert admin.pendingApprovals().isEmpty();
        assert admin.revenueSummary().venueRevenue().signum() == 0 : "declined booking must not count as revenue";
        assert admin.revenueSummary().refunds().signum() > 0;

        // Approve a second request, create an event from it, sell tickets.
        var paid = services.venueWorkflow().requestAndPay(owner.customer(), owner.account(), venue, date,
                LocalTime.of(14, 0), LocalTime.of(16, 0), ReservationPricing.HOURLY);
        admin.approve("admin@drawn.com", paid.id());
        try { admin.approve("admin@drawn.com", paid.id()); throw new AssertionError("double approval accepted"); }
        catch (IllegalArgumentException expected) { }
        var draft = new EventDraft("Admin Test Concert", "Dashboard check", EventType.CONCERT,
                EventPurpose.TICKETED_EVENT, EventVisibility.PUBLIC, date, LocalTime.of(14, 0), LocalTime.of(16, 0), 20,
                venue, List.of(new TicketCategory("General", new BigDecimal("100.00"), 10)));
        Event event = services.eventCreationService().create(owner.customer(), draft, paid.id(), "");
        TicketType category = services.publishing().ticketTypes(event.id()).get(0);
        services.publishing().buyTicket(buyer.customer(), buyer.account(), event.id(), category.id(), 2);

        // Tickets: visible to admin, with names, and filterable.
        var tickets = admin.tickets(AdminDashboardService.TicketFilter.ALL);
        assert tickets.size() == 1;
        assert tickets.get(0).customerName().equals("Taylor Buyer") && tickets.get(0).eventName().equals("Admin Test Concert");
        assert tickets.get(0).quantity() == 2 && tickets.get(0).status() == TicketPurchaseStatus.ACTIVE;
        assert admin.tickets(new AdminDashboardService.TicketFilter(null, "nobody", null, null, null)).isEmpty();
        var summary = admin.ticketSummary().get(0);
        assert summary.sold() == 2 && summary.remaining() == 8 : summary;

        // Transactions: payment + wallet rows merged; purpose filter narrows to payments.
        var all = admin.transactions(AdminDashboardService.TransactionFilter.ALL);
        assert all.stream().anyMatch(t -> t.source().equals("WALLET"));
        assert all.stream().anyMatch(t -> t.source().equals("PAYMENT") && t.description().equals("TICKET_RESERVATION"));
        var ticketOnly = admin.transactions(new AdminDashboardService.TransactionFilter(null, PaymentPurpose.TICKET_RESERVATION, null, null));
        assert ticketOnly.size() == 1 && ticketOnly.get(0).source().equals("PAYMENT");

        // Revenue: ticket gross 200.00, commission 10% = 20.00.
        var revenue = admin.revenueSummary();
        assert revenue.ticketGross().compareTo(new BigDecimal("200.00")) == 0 : revenue;
        assert revenue.platformCommission().compareTo(new BigDecimal("20.00")) == 0 : revenue;

        // Audit trail has exactly the decline and the one successful approval.
        assert new AdminAuditLog(data, java.time.Clock.systemDefaultZone()).entries().size() == 2 : "expected decline + approve rows";
        assert admin.stuckOperations().isEmpty();
        System.out.println("AdminDashboardServiceTest passed.");
    }
}
