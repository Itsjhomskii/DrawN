package drawn.service.event;

import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.event.ReservationPricing;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentStatus;
import drawn.model.user.Customer;
import drawn.storage.EventRepository;
import drawn.storage.ReservationRepository;
import drawn.service.payment.PaymentService;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reservation creation and approval rules. Admin UI may call the review methods. */
public final class ReservationService {
    private final EventRepository events;
    private final ReservationRepository reservations;
    private final Clock clock;
    private final PaymentService paymentService;
    public ReservationService(EventRepository events, ReservationRepository reservations) {
        this(events, reservations, Clock.systemDefaultZone(), null);
    }
    public ReservationService(EventRepository events, ReservationRepository reservations, Clock clock) {
        this(events, reservations, clock, null);
    }
    public ReservationService(EventRepository events, ReservationRepository reservations, Clock clock, PaymentService paymentService) {
        this.events = Objects.requireNonNull(events); this.reservations = Objects.requireNonNull(reservations);
        this.clock = Objects.requireNonNull(clock); this.paymentService = paymentService;
    }

    public VenueReservation request(Customer customer, Event event, ReservationPricing pricing) throws IOException {
        Objects.requireNonNull(customer); Objects.requireNonNull(event); Objects.requireNonNull(pricing);
        if (!event.organizerId().equals(customer.getId())) throw new IllegalArgumentException("Event does not belong to this customer.");
        if (reservations.findAll().stream().anyMatch(existing -> existing.eventId().equals(event.id())))
            throw new IllegalArgumentException("A reservation request already exists for this event.");
        if (event.date().isBefore(LocalDate.now(clock))) throw new IllegalArgumentException("Reservation date cannot be in the past.");
        if (event.expectedAttendance() > event.venue().capacity()) throw new IllegalArgumentException("Attendance exceeds venue capacity.");
        validatePricing(event.startTime(), event.endTime(), pricing);
        BigDecimal cost = price(event, pricing);
        VenueReservation booking = new VenueReservation(UUID.randomUUID().toString(), event.id(), customer.getId(),
                event.venue().name(), event.date(), event.startTime(), event.endTime(), pricing, cost,
                BookingStatus.PENDING, PaymentStatus.UNPAID, "");
        reservations.save(booking);
        return booking;
    }

    public List<VenueReservation> forCustomer(Customer customer) throws IOException {
        return reservations.findAll().stream().filter(r -> r.organizerId().equals(customer.getId())).toList();
    }
    public List<VenueReservation> all() throws IOException { return reservations.findAll(); }
    public List<VenueReservation> pendingRequests() throws IOException {
        return reservations.findAll().stream().filter(r -> r.status() == BookingStatus.PENDING).toList();
    }
    public VenueReservation find(String id) throws IOException { return reservations.findById(id); }

    public VenueReservation approve(String reservationId) throws IOException {
        VenueReservation r = reservations.findById(reservationId);
        if (r.status() != BookingStatus.PENDING) throw new IllegalArgumentException("Only pending requests can be approved.");
        Event event = eventById(r.eventId());
        for (VenueReservation other : reservations.findAll()) {
            if (other.id().equals(r.id()) || !other.venueName().equals(r.venueName()) || !other.date().equals(r.date())) continue;
            if ((other.status() == BookingStatus.APPROVED || other.status() == BookingStatus.CONFIRMED)
                    && overlaps(r.startTime(), r.endTime(), other.startTime(), other.endTime()))
                throw new IllegalArgumentException("Another approved reservation conflicts with this time.");
        }
        if (event.expectedAttendance() > event.venue().capacity()) throw new IllegalArgumentException("Venue capacity exceeded.");
        VenueReservation updated = r.withStatus(BookingStatus.APPROVED, "");
        reservations.update(updated); return updated;
    }

    public VenueReservation decline(String reservationId, String reason) throws IOException {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A decline reason is required.");
        VenueReservation r = reservations.findById(reservationId);
        if (r.paymentStatus() == PaymentStatus.PAID) {
            if (paymentService == null) throw new IllegalStateException("Use the payment-aware reservation service to decline a paid booking.");
            return paymentService.declineReservation(reservationId, reason);
        }
        if (r.status() == BookingStatus.DECLINED) throw new IllegalArgumentException("Reservation is already declined.");
        VenueReservation updated = r.withStatus(BookingStatus.DECLINED, reason);
        reservations.update(updated); return updated;
    }

    private Event eventById(String id) throws IOException {
        return events.findAll().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Linked event not found."));
    }
    public static BigDecimal price(Event event, ReservationPricing pricing) {
        validatePricing(event.startTime(), event.endTime(), pricing);
        if (pricing == ReservationPricing.HALF_DAY_MORNING) return event.venue().morningRate();
        if (pricing == ReservationPricing.HALF_DAY_AFTERNOON) return event.venue().afternoonRate();
        long minutes = Duration.between(event.startTime(), event.endTime()).toMinutes();
        return event.venue().hourlyRate().multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }
    public static void validatePricing(LocalTime start, LocalTime end, ReservationPricing pricing) {
        if (!end.isAfter(start)) throw new IllegalArgumentException("End time must be later than start time.");
        if (pricing == ReservationPricing.HOURLY
                && (start.isBefore(LocalTime.of(6, 0)) || end.isAfter(LocalTime.of(23, 0))))
            throw new IllegalArgumentException("Hourly reservations must fall between 06:00 and 23:00.");
        if (pricing == ReservationPricing.HALF_DAY_MORNING
                && (start.isBefore(LocalTime.of(6, 0)) || end.isAfter(LocalTime.NOON)))
            throw new IllegalArgumentException("Morning half-day reservations must fall between 06:00 and 12:00.");
        if (pricing == ReservationPricing.HALF_DAY_AFTERNOON
                && (start.isBefore(LocalTime.NOON) || end.isAfter(LocalTime.of(18, 0))))
            throw new IllegalArgumentException("Afternoon half-day reservations must fall between 12:00 and 18:00.");
    }
    private static boolean overlaps(LocalTime a1, LocalTime a2, LocalTime b1, LocalTime b2) {
        return a1.isBefore(b2) && b1.isBefore(a2);
    }
}
