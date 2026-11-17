package drawn.service.admin;

import drawn.gui.AppServices;
import drawn.model.event.*;
import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.user.ContactMethod;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * End-to-end admin check on a throwaway data folder: a customer pays for two venues, the admin approves one and
 * declines the other, the customer sees both decisions, the decline is refunded exactly once, and a ticket sale is
 * traced to its buyer and event and counted once. Run with: java -ea -cp ... drawn.service.admin.AdminDashboardFlowTest
 */
public final class AdminDashboardFlowTest {
    private static final String ADMIN = "admin@drawn.com";

    private AdminDashboardFlowTest() { }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawn-admin-flow-");
        AppServices services = new AppServices(data);
        AdminDashboardService admin = services.adminDashboard();
        var owner = services.customerAccess().register("Jamie", "Organizer", LocalDate.now().minusYears(30),
                ContactMethod.EMAIL, "owner@example.test", "pass", new BigDecimal("100000.00"));
        var buyer = services.customerAccess().register("Taylor", "Buyer", LocalDate.now().minusYears(25),
                ContactMethod.EMAIL, "buyer@example.test", "pass", new BigDecimal("1000.00"));
        Venue venue = VenueCatalog.all().stream().filter(v -> v.type() == VenueType.BOTH).findFirst().orElseThrow();
        LocalDate date = LocalDate.now().plusDays(45);

        // 1. Customer pays for two venue requests; the admin sees both as PAID and still PENDING.
        var keep = services.venueWorkflow().requestAndPay(owner.customer(), owner.account(), venue, date,
                LocalTime.of(9, 0), LocalTime.of(11, 0), ReservationPricing.HOURLY);
        var drop = services.venueWorkflow().requestAndPay(owner.customer(), owner.account(), venue, date,
                LocalTime.of(14, 0), LocalTime.of(16, 0), ReservationPricing.HOURLY);
        var queue = admin.pendingApprovals();
        assert queue.size() == 2 : queue;
        for (var request : queue) {
            assert request.paymentStatus() == PaymentStatus.PAID : request;
            assert services.reservations().find(request.reservationId()).status() == BookingStatus.PENDING;
            assert request.customerName().equals("Jamie Organizer");
        }
        BigDecimal afterPaying = balance(services, owner.account().getId());

        // 2. Approve one, decline the other (a reason is required).
        admin.approve(ADMIN, keep.id());
        expectIllegal(() -> admin.decline(ADMIN, drop.id(), ""));
        admin.decline(ADMIN, drop.id(), "Venue under maintenance");
        expectIllegal(() -> admin.decline(ADMIN, drop.id(), "again"));
        assert admin.pendingApprovals().isEmpty();

        // 3. The customer sees both decisions.
        List<VenueReservation> mine = services.reservations().forCustomer(owner.customer());
        VenueReservation approved = mine.stream().filter(r -> r.id().equals(keep.id())).findFirst().orElseThrow();
        VenueReservation declined = mine.stream().filter(r -> r.id().equals(drop.id())).findFirst().orElseThrow();
        assert approved.status() == BookingStatus.APPROVED && approved.paymentStatus() == PaymentStatus.PAID : approved;
        assert declined.status() == BookingStatus.DECLINED && declined.paymentStatus() == PaymentStatus.REFUNDED : declined;
        assert declined.declineReason().equals("Venue under maintenance");

        // 4. Exactly one refund for the declined request: wallet, ledger and admin view all agree.
        long walletRefunds = services.accounts().loadTransactions(owner.account().getId()).stream()
                .filter(t -> t.type().equals("REFUND:venue:" + drop.id())).count();
        long ledgerRefunds = services.payment().history(owner.customer()).stream()
                .filter(p -> p.referenceId().equals(drop.id()) && p.status() == PaymentStatus.REFUNDED).count();
        var allRows = admin.transactions(AdminDashboardService.TransactionFilter.ALL);
        long adminRefunds = allRows.stream().filter(t -> t.referenceId().equals(drop.id()) && t.status().equals("REFUNDED")).count();
        long adminCharges = allRows.stream().filter(t -> t.referenceId().equals(drop.id()) && t.status().equals("PAID")).count();
        assert walletRefunds == 1 && ledgerRefunds == 1 : walletRefunds + " wallet / " + ledgerRefunds + " ledger refunds";
        assert adminRefunds == 1 : "admin shows " + adminRefunds + " refund rows for the declined request";
        assert adminCharges == 1 : "admin shows " + adminCharges + " charge rows for the declined request";
        long allForDrop = allRows.stream().filter(t -> t.referenceId().equals(drop.id())).count();
        assert allForDrop == 2 : "declined request should show one charge and one refund, not " + allForDrop + " rows";
        assert balance(services, owner.account().getId()).compareTo(afterPaying.add(drop.cost())) == 0 : "refund amount";
        var refundRow = allRows.stream().filter(t -> t.referenceId().equals(drop.id()) && t.status().equals("REFUNDED")).findFirst().orElseThrow();
        assert refundRow.purpose() == PaymentPurpose.VENUE_BOOKING && refundRow.related().startsWith("Venue: " + venue.name()) : refundRow;
        assert refundRow.customerName().equals("Jamie Organizer");

        // 5. A ticket sale is traced to its buyer and event and counted once.
        var draft = new EventDraft("Admin Flow Concert", "Admin trace check", EventType.CONCERT,
                EventPurpose.TICKETED_EVENT, EventVisibility.PUBLIC, date, LocalTime.of(9, 0), LocalTime.of(11, 0), 20,
                venue, List.of(new TicketCategory("General", new BigDecimal("100.00"), 10)));
        Event event = services.eventCreationService().create(owner.customer(), draft, keep.id(), "");
        var category = services.publishing().ticketTypes(event.id()).get(0);
        services.publishing().buyTicket(buyer.customer(), buyer.account(), event.id(), category.id(), 2);
        var ticket = admin.tickets(AdminDashboardService.TicketFilter.ALL).get(0);
        assert ticket.customerName().equals("Taylor Buyer") && ticket.eventName().equals("Admin Flow Concert") : ticket;
        var ticketRows = admin.transactions(AdminDashboardService.TransactionFilter.ALL).stream()
                .filter(t -> t.referenceId().equals(ticket.purchaseId())).toList();
        assert ticketRows.size() == 1 : "ticket payment shown " + ticketRows.size() + " times: " + ticketRows;
        var ticketRow = ticketRows.get(0);
        assert ticketRow.customerId().equals(buyer.account().getId()) && ticketRow.customerName().equals("Taylor Buyer");
        assert ticketRow.purpose() == PaymentPurpose.TICKET_RESERVATION && ticketRow.status().equals("PAID");
        assert ticketRow.amount().compareTo(new BigDecimal("200.00")) == 0;
        assert ticketRow.related().equals("Event: Admin Flow Concert") : ticketRow;
        assert admin.revenueSummary().ticketGross().compareTo(new BigDecimal("200.00")) == 0 : admin.revenueSummary();
        assert admin.revenueSummary().venueRevenue().compareTo(keep.cost()) == 0 : "only the approved venue counts";
        // Top-ups have no ledger twin, so they still show as wallet rows.
        assert admin.transactions(AdminDashboardService.TransactionFilter.ALL).stream()
                .anyMatch(t -> t.source().equals("WALLET") && t.purpose() == null);

        // 6. Event approval is separate from venue approval.
        var reviewDraft = new EventDraft("Pending Review Talk", "Needs admin review", EventType.CONCERT,
                EventPurpose.TICKETED_EVENT, EventVisibility.PUBLIC, date.plusDays(1), LocalTime.of(9, 0), LocalTime.of(11, 0), 20,
                venue, List.of(new TicketCategory("General", new BigDecimal("50.00"), 10)));
        // Submitting needs an approved, paid booking linked to the event.
        var reviewBooking = services.venueWorkflow().requestAndPay(owner.customer(), owner.account(), venue, date.plusDays(1),
                LocalTime.of(9, 0), LocalTime.of(11, 0), ReservationPricing.HOURLY);
        admin.approve(ADMIN, reviewBooking.id());
        Event pending = services.events().saveDraftForReservation(owner.customer(), reviewDraft, reviewBooking.id());
        services.reservations().attachEvent(owner.customer(), reviewBooking.id(), pending.id());
        services.events().submitDraft(owner.customer(), pending.id());
        assert admin.pendingEvents().stream().anyMatch(e -> e.eventId().equals(pending.id()) && e.organizerName().equals("Jamie Organizer"));
        admin.reviewEvent(ADMIN, pending.id(), false);
        assert admin.pendingEvents().isEmpty();
        assert services.events().eventsFor(owner.customer()).stream()
                .anyMatch(e -> e.id().equals(pending.id()) && e.approvalStatus() == EventApprovalStatus.REJECTED);
        expectIllegal(() -> admin.reviewEvent(ADMIN, pending.id(), true));

        // 7. Decisions are recorded, newest first; nothing is left unfinished.
        List<String> actions = admin.decisions().stream().map(AdminAuditLog.Decision::action).toList();
        assert actions.equals(List.of("REJECT_EVENT", "APPROVE_RESERVATION", "DECLINE_RESERVATION", "APPROVE_RESERVATION")) : actions;
        assert admin.decisions().get(2).detail().equals("Venue under maintenance");
        assert admin.stuckOperations().isEmpty();
        System.out.println("AdminDashboardFlowTest passed.");
    }

    private static BigDecimal balance(AppServices services, String accountId) throws Exception {
        return services.accounts().findAccount(accountId).getWalletBalance();
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void expectIllegal(Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
}
