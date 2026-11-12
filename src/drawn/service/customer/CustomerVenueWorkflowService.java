package drawn.service.customer;

import drawn.model.event.ReservationPricing;
import drawn.model.event.Venue;
import drawn.model.event.VenueReservation;
import drawn.model.user.Customer;
import drawn.service.event.ReservationService;
import drawn.service.payment.PaymentService;
import drawn.storage.AccountStore;
import drawn.storage.ReservationRepository;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/** Shared paid venue-request workflow used by console and JavaFX customers. */
public final class CustomerVenueWorkflowService {
    private final AccountStore accounts;
    private final ReservationRepository reservations;
    private final ReservationService reservationService;
    private final PaymentService paymentService;

    public CustomerVenueWorkflowService(AccountStore accounts, ReservationRepository reservations,
                                        ReservationService reservationService, PaymentService paymentService) {
        this.accounts = Objects.requireNonNull(accounts);
        this.reservations = Objects.requireNonNull(reservations);
        this.reservationService = Objects.requireNonNull(reservationService);
        this.paymentService = Objects.requireNonNull(paymentService);
    }

    public VenueReservation requestAndPay(Customer customer, AccountStore.Account account, Venue venue,
                                          LocalDate date, LocalTime start, LocalTime end,
                                          ReservationPricing pricing) throws IOException {
        VenueReservation requested = reservationService.request(customer, venue, date, start, end, pricing);
        try {
            return paymentService.payVenue(customer, account, requested.id());
        } catch (IOException | RuntimeException paymentFailure) {
            try {
                VenueReservation current = reservations.findById(requested.id());
                if (current.paymentStatus() == drawn.model.payment.PaymentStatus.UNPAID
                        && !accounts.hasWalletTransaction(account.getId(), "PAYMENT:venue:" + requested.id())) {
                    reservations.delete(requested.id());
                }
            } catch (IOException | RuntimeException rollbackFailure) {
                paymentFailure.addSuppressed(rollbackFailure);
            }
            throw paymentFailure;
        }
    }
}
