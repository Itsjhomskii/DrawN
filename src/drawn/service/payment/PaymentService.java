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
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Full wallet payments for venue reservations, with an append-only audit ledger. */
public final class PaymentService {
    private final AccountStore accounts;
    private final ReservationRepository reservations;
    private final PaymentRepository ledger;
    public PaymentService(AccountStore accounts, ReservationRepository reservations, PaymentRepository ledger) {
        this.accounts = Objects.requireNonNull(accounts); this.reservations = Objects.requireNonNull(reservations);
        this.ledger = Objects.requireNonNull(ledger);
    }
    public VenueReservation payVenue(Customer customer, AccountStore.Account account, String reservationId) throws IOException {
        VenueReservation r = reservations.findById(reservationId);
        if (!r.organizerId().equals(customer.getId()) || !account.getId().equals(customer.getId()))
            throw new IllegalArgumentException("Reservation does not belong to this customer.");
        if (r.status() != BookingStatus.APPROVED) throw new IllegalArgumentException("Only approved reservations can be paid.");
        if (r.paymentStatus() != PaymentStatus.UNPAID) throw new IllegalArgumentException("Reservation is not unpaid.");
        String reference = "venue:" + r.id();
        accounts.charge(account, r.cost(), reference);
        VenueReservation paid = r.withPayment(PaymentStatus.PAID).withStatus(BookingStatus.CONFIRMED, "");
        try {
            ledger.append(new PaymentTransaction(UUID.randomUUID().toString(), customer.getId(), r.id(),
                    PaymentPurpose.VENUE_BOOKING, r.cost(), PaymentStatus.PAID, LocalDateTime.now()));
            reservations.update(paid);
            return paid;
        } catch (IOException | RuntimeException ex) {
            accounts.refund(account, r.cost(), "rollback:" + r.id());
            throw ex;
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
            accounts.refund(owner, current.cost(), "venue:" + current.id());
            ledger.append(new PaymentTransaction(UUID.randomUUID().toString(), owner.getId(), current.id(),
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
