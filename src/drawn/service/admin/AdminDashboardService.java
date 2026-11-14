package drawn.service.admin;

import drawn.model.event.Event;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.payment.PaymentTransaction;
import drawn.model.ticket.TicketPurchaseStatus;
import drawn.model.ticket.TicketType;
import drawn.service.event.ReservationService;
import drawn.service.payment.CommissionPolicy;
import drawn.storage.AccountStore;
import drawn.storage.EventRepository;
import drawn.storage.PaymentRepository;
import drawn.storage.TicketRepository;
import drawn.storage.TransactionJournal;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backend for the admin dashboard. It owns no data of its own: it reads the existing repositories,
 * delegates approvals to {@link ReservationService} (so its conflict, capacity and refund rules still
 * apply), and returns plain records that a console menu or a JavaFX screen can render directly.
 *
 * Passwords never leave this class: customers are exposed only as id + display name.
 */
public final class AdminDashboardService {
    private final ReservationService reservations;
    private final EventRepository events;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final AccountStore accounts;
    private final TransactionJournal journal;
    private final CommissionPolicy commission;
    private final AdminAuditLog audit;

    public AdminDashboardService(ReservationService reservations, EventRepository events, TicketRepository tickets,
                                 PaymentRepository payments, AccountStore accounts, TransactionJournal journal,
                                 CommissionPolicy commission, AdminAuditLog audit) {
        this.reservations = reservations; this.events = events; this.tickets = tickets;
        this.payments = payments; this.accounts = accounts; this.journal = journal;
        this.commission = commission; this.audit = audit;
    }

    // ---------------------------------------------------------------- read models

    /** A venue request awaiting an admin decision, with the customer and event it belongs to. */
    public record PendingApproval(String reservationId, String customerId, String customerName, String venueName,
                                  java.time.LocalDate date, java.time.LocalTime startTime, java.time.LocalTime endTime,
                                  BigDecimal cost, PaymentStatus paymentStatus, String eventId, String eventName) { }

    public record TicketRow(String purchaseId, String eventId, String eventName, String ticketType, String customerId,
                            String customerName, int quantity, BigDecimal amount, TicketPurchaseStatus status,
                            LocalDateTime timestamp) { }

    /** source is PAYMENT (payment-transactions.csv) or WALLET (wallet-transactions.csv). */
    public record TransactionRow(String source, String id, String customerId, String customerName, String description,
                                 BigDecimal amount, String status, LocalDateTime timestamp) { }

    public record RevenueSummary(BigDecimal venueRevenue, BigDecimal ticketGross, BigDecimal platformCommission,
                                 BigDecimal refunds) { }

    public record EventTicketSummary(String eventId, String eventName, int sold, int cancelled, int remaining,
                                     BigDecimal activeAmount) { }

    /** Null fields mean "do not filter on this". */
    public record TicketFilter(String eventId, String customerId, TicketPurchaseStatus status,
                               LocalDateTime from, LocalDateTime to) {
        public static final TicketFilter ALL = new TicketFilter(null, null, null, null, null);
    }

    public record TransactionFilter(String customerId, PaymentPurpose purpose, LocalDateTime from, LocalDateTime to) {
        public static final TransactionFilter ALL = new TransactionFilter(null, null, null, null);
    }

    // ---------------------------------------------------------------- approvals

    public List<PendingApproval> pendingApprovals() throws IOException {
        Map<String, String> names = customerNames();
        Map<String, Event> eventById = eventsById();
        List<PendingApproval> result = new ArrayList<>();
        for (VenueReservation r : reservations.pendingRequests()) {
            Event event = eventById.get(r.eventId());
            result.add(new PendingApproval(r.id(), r.organizerId(), names.getOrDefault(r.organizerId(), r.organizerId()),
                    r.venueName(), r.date(), r.startTime(), r.endTime(), r.cost(), r.paymentStatus(),
                    r.eventId(), event == null ? "" : event.name()));
        }
        return result;
    }

    public VenueReservation approve(String adminId, String reservationId) throws IOException {
        VenueReservation approved = reservations.approve(reservationId);
        audit.record(adminId, "APPROVE_RESERVATION", reservationId, approved.venueName() + " " + approved.date());
        return approved;
    }

    public VenueReservation decline(String adminId, String reservationId, String reason) throws IOException {
        VenueReservation declined = reservations.decline(reservationId, reason);
        audit.record(adminId, "DECLINE_RESERVATION", reservationId, reason);
        return declined;
    }

    // ---------------------------------------------------------------- tickets

    public List<TicketRow> tickets(TicketFilter filter) throws IOException {
        Map<String, String> names = customerNames();
        Map<String, Event> eventById = eventsById();
        Map<String, TicketType> typeById = new HashMap<>();
        for (String eventId : eventById.keySet())
            for (TicketType type : tickets.types(eventId)) typeById.put(type.id(), type);

        List<TicketRow> rows = new ArrayList<>();
        for (TicketRepository.Purchase p : tickets.allPurchaseRecords()) {
            if (filter.eventId() != null && !filter.eventId().equals(p.eventId())) continue;
            if (filter.customerId() != null && !filter.customerId().equals(p.purchaserId())) continue;
            if (filter.status() != null && filter.status() != p.status()) continue;
            if (filter.from() != null && p.timestamp().isBefore(filter.from())) continue;
            if (filter.to() != null && p.timestamp().isAfter(filter.to())) continue;
            Event event = eventById.get(p.eventId());
            TicketType type = typeById.get(p.ticketTypeId());
            rows.add(new TicketRow(p.id(), p.eventId(), event == null ? "(deleted event)" : event.name(),
                    type == null ? "(unknown)" : type.name(), p.purchaserId(),
                    names.getOrDefault(p.purchaserId(), p.purchaserId()), p.quantity(), p.amount(), p.status(),
                    p.timestamp()));
        }
        rows.sort(Comparator.comparing(TicketRow::timestamp).reversed());
        return rows;
    }

    /** Sold / cancelled / remaining per event. */
    public List<EventTicketSummary> ticketSummary() throws IOException {
        Map<String, Event> eventById = eventsById();
        Map<String, int[]> counts = new LinkedHashMap<>(); // [sold, cancelled]
        Map<String, BigDecimal> amounts = new HashMap<>();
        for (TicketRepository.Purchase p : tickets.allPurchaseRecords()) {
            int[] c = counts.computeIfAbsent(p.eventId(), k -> new int[2]);
            if (p.status() == TicketPurchaseStatus.ACTIVE) {
                c[0] += p.quantity();
                amounts.merge(p.eventId(), p.amount(), BigDecimal::add);
            } else if (p.status() == TicketPurchaseStatus.CANCELLED || p.status() == TicketPurchaseStatus.REFUNDED) {
                c[1] += p.quantity();
            }
        }
        List<EventTicketSummary> result = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            Event event = eventById.get(e.getKey());
            int remaining = tickets.types(e.getKey()).stream().mapToInt(TicketType::remaining).sum();
            result.add(new EventTicketSummary(e.getKey(), event == null ? "(deleted event)" : event.name(),
                    e.getValue()[0], e.getValue()[1], remaining,
                    amounts.getOrDefault(e.getKey(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)));
        }
        return result;
    }

    // ---------------------------------------------------------------- transactions

    /** Payment-ledger rows and wallet rows merged into one newest-first list. */
    public List<TransactionRow> transactions(TransactionFilter filter) throws IOException {
        Map<String, String> names = customerNames();
        List<TransactionRow> rows = new ArrayList<>();
        for (PaymentTransaction tx : payments.findAll()) {
            if (filter.customerId() != null && !filter.customerId().equals(tx.customerId())) continue;
            if (filter.purpose() != null && filter.purpose() != tx.purpose()) continue;
            rows.add(new TransactionRow("PAYMENT", tx.id(), tx.customerId(),
                    names.getOrDefault(tx.customerId(), tx.customerId()), tx.purpose().name(), tx.amount(),
                    tx.status().name(), tx.timestamp()));
        }
        // Wallet rows have no purpose, so a purpose filter means "payments only".
        if (filter.purpose() == null) {
            for (AccountStore.Account account : accounts.loadAccounts()) {
                if (filter.customerId() != null && !filter.customerId().equals(account.getId())) continue;
                for (AccountStore.Transaction tx : accounts.loadTransactions(account.getId()))
                    rows.add(new TransactionRow("WALLET", account.getId() + "@" + tx.timestamp(), account.getId(),
                            account.getFullName(), tx.type(), tx.amount(), "BALANCE " + tx.balanceAfter(), tx.timestamp()));
            }
        }
        rows.removeIf(r -> (filter.from() != null && r.timestamp().isBefore(filter.from()))
                || (filter.to() != null && r.timestamp().isAfter(filter.to())));
        rows.sort(Comparator.comparing(TransactionRow::timestamp).reversed());
        return rows;
    }

    /**
     * Same refund rule as OrganizerEarningsService: a refund row shares its referenceId with the original
     * payment, and a refunded payment no longer counts as revenue.
     */
    public RevenueSummary revenueSummary() throws IOException {
        List<PaymentTransaction> all = payments.findAll();
        Set<String> refunded = new HashSet<>();
        BigDecimal refunds = BigDecimal.ZERO;
        for (PaymentTransaction tx : all) {
            if (tx.status() == PaymentStatus.REFUNDED) { refunded.add(tx.purpose() + ":" + tx.referenceId()); refunds = refunds.add(tx.amount()); }
        }
        BigDecimal venue = BigDecimal.ZERO, ticket = BigDecimal.ZERO;
        for (PaymentTransaction tx : all) {
            boolean counts = tx.status() == PaymentStatus.PAID || tx.status() == PaymentStatus.VERIFIED;
            if (!counts || refunded.contains(tx.purpose() + ":" + tx.referenceId())) continue;
            if (tx.purpose() == PaymentPurpose.VENUE_BOOKING) venue = venue.add(tx.amount());
            else if (tx.purpose() == PaymentPurpose.TICKET_RESERVATION) ticket = ticket.add(tx.amount());
        }
        BigDecimal fee = ticket.multiply(commission.percent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        return new RevenueSummary(venue.setScale(2), ticket.setScale(2), fee, refunds.setScale(2));
    }

    /** Operations that began but never finished (for example, a crash mid-payment). */
    public List<TransactionJournal.Entry> stuckOperations() throws IOException {
        return journal.unfinished();
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, String> customerNames() throws IOException {
        Map<String, String> names = new HashMap<>();
        for (AccountStore.Account a : accounts.loadAccounts()) names.put(a.getId(), a.getFullName());
        return names;
    }

    private Map<String, Event> eventsById() throws IOException {
        Map<String, Event> map = new HashMap<>();
        for (Event e : events.findAll()) map.put(e.id(), e);
        return map;
    }
}
