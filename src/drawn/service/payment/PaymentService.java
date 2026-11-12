package drawn.service.payment;

import drawn.model.event.BookingStatus;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.payment.PaymentTransaction;
import drawn.model.user.Customer;
import drawn.storage.AccountStore;
import drawn.storage.PaymentRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.TransactionJournal;
import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.ReservationPricing;
import drawn.model.event.Venue;
import drawn.storage.EventRepository;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.math.BigDecimal;

/** Full wallet payments for venue reservations, with an append-only audit ledger. */
public final class PaymentService {
    private final AccountStore accounts;
    private final ReservationRepository reservations;
    private final PaymentRepository ledger;
    private final TransactionJournal journal;
    private final EventRepository events;
    public PaymentService(AccountStore accounts, ReservationRepository reservations, PaymentRepository ledger,
                          TransactionJournal journal, EventRepository events) {
        this.accounts = Objects.requireNonNull(accounts); this.reservations = Objects.requireNonNull(reservations);
        this.ledger = Objects.requireNonNull(ledger); this.journal = Objects.requireNonNull(journal,
                "Transaction journal is required for payment operations."); this.events = events;
    }
    public VenueReservation payVenue(Customer customer, AccountStore.Account account, String reservationId) throws IOException {
        VenueReservation r = reservations.findById(reservationId);
        if (!r.organizerId().equals(customer.getId()) || !account.getId().equals(customer.getId()))
            throw new IllegalArgumentException("Reservation does not belong to this customer.");
        if (r.status() != BookingStatus.PENDING && r.status() != BookingStatus.APPROVED)
            throw new IllegalArgumentException("Only pending or approved reservations can be paid.");
        if (r.paymentStatus() != PaymentStatus.UNPAID) throw new IllegalArgumentException("Reservation is not unpaid.");
        String operation = "venuepay:" + r.id();
        List<String> payload = List.of(customer.getId(), r.id(), r.cost().toPlainString(), "venue-payment:" + r.id());
        journal.begin(operation, "VENUE_PAYMENT", payload);
        try { return completeVenuePayment(account, r, "venue-payment:" + r.id(), operation); }
        catch (IOException | RuntimeException ex) {
            if (!accounts.hasWalletTransaction(account.getId(), "PAYMENT:venue:" + r.id())
                    && !ledger.hasTransaction("venue-payment:" + r.id())) journal.state(operation, "FAILED");
            throw ex;
        }
    }

    /** Edits the schedule and price of a paid request before administrator approval. */
    public VenueReservation editPendingVenue(Customer customer, AccountStore.Account account, String reservationId,
                                             Venue venue, java.time.LocalDate date, java.time.LocalTime start,
                                             java.time.LocalTime end, ReservationPricing pricing) throws IOException {
        VenueReservation current = reservations.findById(reservationId);
        if (!current.organizerId().equals(customer.getId()) || !account.getId().equals(customer.getId()))
            throw new IllegalArgumentException("Reservation does not belong to this customer.");
        if (current.status() != BookingStatus.PENDING || !current.eventId().isBlank())
            throw new IllegalArgumentException("Only unapproved reservations without a linked event can be edited.");
        if (current.paymentStatus() != PaymentStatus.PAID)
            throw new IllegalArgumentException("Only paid reservations can be edited.");
        if (!current.venueName().equals(venue.name()))
            throw new IllegalArgumentException("The venue cannot be changed after the request is submitted.");
        if (date.isBefore(java.time.LocalDate.now())) throw new IllegalArgumentException("Reservation date cannot be in the past.");
        drawn.service.event.ReservationService.validatePricing(start, end, pricing);
        BigDecimal newCost = drawn.service.event.ReservationService.price(venue, start, end, pricing);
        BigDecimal difference = newCost.subtract(current.cost());
        if (difference.signum() > 0 && accounts.findAccount(account.getId()).getWalletBalance().compareTo(difference) < 0)
            throw new IllegalArgumentException("Insufficient wallet balance for the updated reservation fee.");
        String operation = "edit-venue:" + UUID.randomUUID();
        String ledgerId = "venue-edit-payment:" + UUID.randomUUID();
        List<String> payload = List.of(account.getId(), current.id(), date.toString(), start.toString(), end.toString(),
                pricing.name(), newCost.toPlainString(), difference.abs().toPlainString(),
                difference.signum() >= 0 ? "CHARGE" : "REFUND", ledgerId);
        journal.begin(operation, "VENUE_RESERVATION_EDIT", payload);
        return completeVenueEdit(account, current, payload, operation);
    }

    private VenueReservation completeVenueEdit(AccountStore.Account account, VenueReservation current,
                                               List<String> payload, String operation) throws IOException {
        BigDecimal amount = new BigDecimal(payload.get(7));
        String adjustment = payload.get(8);
        String walletReference = "venue-edit:" + operation;
        if (amount.signum() > 0) {
            if (adjustment.equals("CHARGE")) {
                if (!accounts.hasWalletTransaction(account.getId(), "PAYMENT:" + walletReference))
                    accounts.charge(account, amount, walletReference);
            } else if (!accounts.hasWalletTransaction(account.getId(), "REFUND:" + walletReference)) {
                accounts.refund(account, amount, walletReference);
            }
            PaymentStatus transactionStatus = adjustment.equals("CHARGE") ? PaymentStatus.PAID : PaymentStatus.REFUNDED;
            if (!ledger.hasTransaction(payload.get(9))) ledger.append(new PaymentTransaction(payload.get(9), account.getId(),
                    current.id(), PaymentPurpose.VENUE_BOOKING, amount, transactionStatus, LocalDateTime.now()));
        }
        VenueReservation edited = new VenueReservation(current.id(), current.eventId(), current.organizerId(),
                current.venueName(), java.time.LocalDate.parse(payload.get(2)), java.time.LocalTime.parse(payload.get(3)),
                java.time.LocalTime.parse(payload.get(4)), ReservationPricing.valueOf(payload.get(5)),
                new BigDecimal(payload.get(6)), current.status(), current.paymentStatus(), current.declineReason());
        reservations.update(edited);
        journal.state(operation, "COMPLETED");
        return edited;
    }

    private VenueReservation completeVenuePayment(AccountStore.Account account, VenueReservation reservation,
                                                  String paymentId, String operation) throws IOException {
        String walletRef = "venue:" + reservation.id();
        if (!accounts.hasWalletTransaction(account.getId(), "PAYMENT:" + walletRef))
            accounts.charge(account, reservation.cost(), walletRef);
        if (!ledger.hasTransaction(account.getId(), reservation.id(), PaymentPurpose.VENUE_BOOKING, PaymentStatus.PAID)
                && !ledger.hasTransaction(paymentId)) ledger.append(new PaymentTransaction(paymentId, account.getId(),
                reservation.id(), PaymentPurpose.VENUE_BOOKING, reservation.cost(), PaymentStatus.PAID, LocalDateTime.now()));
        // A customer payment submits a request; it must never approve the request.  Keep a
        // pending request pending until an administrator makes the decision.  APPROVED is
        // retained here only for legacy records that were approved before this workflow.
        VenueReservation paid = reservation.withPayment(PaymentStatus.PAID);
        if (reservation.paymentStatus() != PaymentStatus.PAID)
            reservations.update(paid);
        journal.state(operation, "COMPLETED");
        return paid;
    }

    /** Customer self-service cancellation; refunds are recorded as separate ledger transactions. */
    public VenueReservation cancelReservation(Customer customer, AccountStore.Account account, String reservationId)
            throws IOException {
        if (!account.getId().equals(customer.getId()))
            throw new IllegalArgumentException("Reservation does not belong to this customer.");
        return cancelReservation(account, reservationId);
    }

    /** Cancels a reservation owned by the supplied account; used by event cancellation recovery. */
    public VenueReservation cancelReservation(AccountStore.Account account, String reservationId) throws IOException {
        VenueReservation current = reservations.findById(reservationId);
        if (!current.organizerId().equals(account.getId()))
            throw new IllegalArgumentException("Reservation does not belong to this customer.");
        if (current.status() == BookingStatus.CANCELLED) throw new IllegalArgumentException("Reservation is already cancelled.");
        if (current.status() == BookingStatus.EXPIRED || current.status() == BookingStatus.DECLINED)
            throw new IllegalArgumentException("This reservation cannot be cancelled in its current status.");
        if (current.paymentStatus() == PaymentStatus.PENDING_VERIFICATION)
            throw new IllegalArgumentException("Resolve the pending payment verification before cancelling this reservation.");
        if (!current.date().isAfter(java.time.LocalDate.now()))
            throw new IllegalArgumentException("Reservations for past or current dates cannot be cancelled.");
        if (events != null && !current.eventId().isBlank()) {
            Event event = events.findAll().stream().filter(e -> e.id().equals(current.eventId())).findFirst().orElse(null);
            if (event != null && (event.approvalStatus() == EventApprovalStatus.CANCELLED
                    || !event.date().isAfter(java.time.LocalDate.now())))
                throw new IllegalArgumentException("Reservations attached to cancelled or completed events cannot be cancelled.");
        }
        boolean refund = current.paymentStatus() == PaymentStatus.PAID || current.paymentStatus() == PaymentStatus.VERIFIED;
        String operation = "cancel-reservation:" + current.id();
        String refundTxId = "refund-venue:" + current.id();
        journal.begin(operation, "RESERVATION_CANCELLATION",
                List.of(account.getId(), current.id(), Boolean.toString(refund), refundTxId));
        return completeCancellation(account, current, refund, refundTxId, operation);
    }

    private VenueReservation completeCancellation(AccountStore.Account account, VenueReservation current,
                                                  boolean refund, String refundTxId, String operation) throws IOException {
        VenueReservation updated = current;
        if (refund) {
            String refundRef = "venue:" + current.id();
            if (!accounts.hasWalletTransaction(account.getId(), "REFUND:" + refundRef))
                accounts.refund(account, current.cost(), refundRef);
            if (!ledger.hasTransaction(account.getId(), current.id(), PaymentPurpose.VENUE_BOOKING, PaymentStatus.REFUNDED)
                    && !ledger.hasTransaction(refundTxId)) ledger.append(new PaymentTransaction(refundTxId,
                    account.getId(), current.id(), PaymentPurpose.VENUE_BOOKING, current.cost(),
                    PaymentStatus.REFUNDED, LocalDateTime.now()));
            updated = updated.withPayment(PaymentStatus.REFUNDED);
        }
        updated = updated.withStatus(BookingStatus.CANCELLED, "");
        if (current.status() != BookingStatus.CANCELLED || (refund && current.paymentStatus() != PaymentStatus.REFUNDED))
            reservations.update(updated);
        journal.state(operation, "COMPLETED");
        return updated;
    }

    /** Completes interrupted customer payment and cancellation operations from the write-ahead journal. */
    public void recoverPending() throws IOException {
        for (TransactionJournal.Entry entry : journal.unfinished()) {
            if (entry.kind().equals("VENUE_PAYMENT")) {
                List<String> p = entry.payload(); AccountStore.Account account = accounts.findAccount(p.get(0));
                VenueReservation r = reservations.findById(p.get(1));
                completeVenuePayment(account, r, p.get(3), entry.id());
            } else if (entry.kind().equals("VENUE_RESERVATION_EDIT")) {
                List<String> p = entry.payload();
                AccountStore.Account account = accounts.findAccount(p.get(0));
                VenueReservation reservation = reservations.findById(p.get(1));
                completeVenueEdit(account, reservation, p, entry.id());
            } else if (entry.kind().equals("RESERVATION_CANCELLATION")) {
                List<String> p = entry.payload(); AccountStore.Account account = accounts.findAccount(p.get(0));
                VenueReservation r = reservations.findById(p.get(1));
                completeCancellation(account, r, Boolean.parseBoolean(p.get(2)), p.get(3), entry.id());
            }
        }
    }
    public List<PaymentTransaction> history(Customer customer) throws IOException {
        return ledger.findByCustomer(customer.getId());
    }
    public void refundVenue(AccountStore.Account account, VenueReservation reservation) throws IOException {
        if (reservation.paymentStatus() != PaymentStatus.PAID) return;
        accounts.refund(account, reservation.cost(), "venue:" + reservation.id());
        VenueReservation refunded = reservation.withPayment(PaymentStatus.REFUNDED);
        reservations.update(refunded);
        ledger.append(new PaymentTransaction(UUID.randomUUID().toString(), account.getId(), reservation.id(),
                PaymentPurpose.VENUE_BOOKING, reservation.cost(), PaymentStatus.REFUNDED, LocalDateTime.now()));
    }

    public VenueReservation declineReservation(AccountStore.Account owner, String reservationId, String reason)
            throws IOException {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A decline reason is required.");
        VenueReservation current = reservations.findById(reservationId);
        if (current.status() == BookingStatus.DECLINED) throw new IllegalArgumentException("Reservation is already declined.");
        if (current.paymentStatus() == PaymentStatus.PAID) {
            if (!owner.getId().equals(current.organizerId())) throw new IllegalArgumentException("Wrong reservation owner.");
            String refundReference = "venue:" + current.id();
            String refundId = "refund-venue:" + current.id();
            // Both the wallet and payment ledger use stable references, making a retry after
            // an interrupted decline safe instead of issuing a second refund.
            if (!accounts.hasWalletTransaction(owner.getId(), "REFUND:" + refundReference))
                accounts.refund(owner, current.cost(), refundReference);
            if (!ledger.hasTransaction(owner.getId(), current.id(), PaymentPurpose.VENUE_BOOKING, PaymentStatus.REFUNDED)
                    && !ledger.hasTransaction(refundId))
                ledger.append(new PaymentTransaction(refundId, owner.getId(), current.id(),
                        PaymentPurpose.VENUE_BOOKING, current.cost(), PaymentStatus.REFUNDED, LocalDateTime.now()));
            current = current.withPayment(PaymentStatus.REFUNDED);
        }
        VenueReservation declined = current.withStatus(BookingStatus.DECLINED, reason);
        reservations.update(declined);
        return declined;
    }

    public VenueReservation declineReservation(String reservationId, String reason) throws IOException {
        VenueReservation current = reservations.findById(reservationId);
        AccountStore.Account owner = accounts.loadAccounts().stream()
                .filter(account -> account.getId().equals(current.organizerId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Reservation owner account not found."));
        return declineReservation(owner, reservationId, reason);
    }
}
