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
import java.time.LocalDateTime;
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

    public synchronized VenueReservation request(Customer customer, Event event, ReservationPricing pricing) throws IOException {
        Objects.requireNonNull(customer); Objects.requireNonNull(event); Objects.requireNonNull(pricing);
        if (!event.organizerId().equals(customer.getId())) throw new IllegalArgumentException("Event does not belong to this customer.");
        if (reservations.findAll().stream().anyMatch(existing -> existing.eventId().equals(event.id())))
            throw new IllegalArgumentException("A reservation request already exists for this event.");
        if (event.date().isBefore(LocalDate.now(clock))) throw new IllegalArgumentException("Reservation date cannot be in the past.");
        if (event.expectedAttendance() > event.venue().capacity()) throw new IllegalArgumentException("Attendance exceeds venue capacity.");
        validatePricing(event.startTime(), event.endTime(), pricing);
        ensureScheduleAvailable(event.venue().name(), event.date(), event.startTime(), event.endTime(), null);
        BigDecimal cost = price(event, pricing);
        VenueReservation booking = new VenueReservation(UUID.randomUUID().toString(), event.id(), customer.getId(),
                event.venue().name(), event.date(), event.startTime(), event.endTime(), pricing, cost,
                BookingStatus.PENDING, PaymentStatus.UNPAID, "");
        reservations.save(booking);
        return booking;
    }

    public synchronized VenueReservation request(Customer customer, drawn.model.event.Venue venue, LocalDate date,
                                    LocalTime start, LocalTime end, ReservationPricing pricing) throws IOException {
        Objects.requireNonNull(customer); Objects.requireNonNull(venue); Objects.requireNonNull(date);
        if (date.isBefore(LocalDate.now(clock))) throw new IllegalArgumentException("Reservation date cannot be in the past.");
        validatePricing(start, end, pricing);
        ensureScheduleAvailable(venue.name(), date, start, end, null);
        Event pricingEvent = new Event("reservation-quote", customer.getId(), "Venue reservation", "Reservation quote",
                drawn.model.event.EventType.CONFERENCE,
                venue.type() == drawn.model.event.VenueType.COMMERCIAL ? drawn.model.event.EventPurpose.TICKETED_EVENT : drawn.model.event.EventPurpose.SOCIAL_GATHERING,
                drawn.model.event.EventVisibility.PRIVATE, date, start, end, 1, venue,
                drawn.model.event.EventApprovalStatus.DRAFT, java.time.LocalDateTime.now(clock), List.of());
        VenueReservation booking = new VenueReservation(UUID.randomUUID().toString(), "", customer.getId(), venue.name(),
                date, start, end, pricing, price(pricingEvent, pricing), BookingStatus.PENDING, PaymentStatus.UNPAID, "");
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
    public void attachEvent(Customer customer, String reservationId, String eventId) throws IOException {
        reservations.attachEvent(reservationId, eventId, customer.getId());
    }

    public synchronized VenueReservation approve(String reservationId) throws IOException {
        VenueReservation r = reservations.findById(reservationId);
        if (r.status() != BookingStatus.PENDING) throw new IllegalArgumentException("Only pending requests can be approved.");
        if (r.paymentStatus() != PaymentStatus.PAID && r.paymentStatus() != PaymentStatus.VERIFIED)
            throw new IllegalArgumentException("Only fully paid reservation requests can be approved.");
        Event event = r.eventId().isBlank() ? null : eventById(r.eventId());
        ensureScheduleAvailable(r.venueName(), r.date(), r.startTime(), r.endTime(), r.id());
        if (event != null && event.expectedAttendance() > event.venue().capacity()) throw new IllegalArgumentException("Venue capacity exceeded.");
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
        return price(event.venue(), event.startTime(), event.endTime(), pricing);
    }
    public static BigDecimal price(drawn.model.event.Venue venue, LocalTime start, LocalTime end,
                                   ReservationPricing pricing) {
        Objects.requireNonNull(venue); Objects.requireNonNull(pricing);
        validatePricing(start, end, pricing);
        if (pricing == ReservationPricing.HALF_DAY_MORNING) return venue.morningRate();
        if (pricing == ReservationPricing.HALF_DAY_AFTERNOON) return venue.afternoonRate();
        long minutes = Duration.between(start, end).toMinutes();
        return venue.hourlyRate().multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /** Ensures a venue booking has two clear hours from every other paid or approved booking at that venue. */
    public void ensureScheduleAvailable(String venueName, LocalDate date, LocalTime start, LocalTime end,
                                        String excludedReservationId) throws IOException {
        Objects.requireNonNull(venueName, "Venue name cannot be null");
        Objects.requireNonNull(date, "Date cannot be null");
        if (start == null || end == null || !end.isAfter(start))
            throw new IllegalArgumentException("End time must be later than start time.");
        LocalDateTime candidateStart = date.atTime(start);
        LocalDateTime candidateEnd = date.atTime(end);
        for (VenueReservation other : reservations.findAll()) {
            if (other.id().equals(excludedReservationId) || !other.venueName().equalsIgnoreCase(venueName)
                    || !blocksSchedule(other)) continue;
            LocalDateTime existingStart = other.date().atTime(other.startTime());
            LocalDateTime existingEnd = other.date().atTime(other.endTime());
            boolean enoughGap = !candidateStart.isBefore(existingEnd)
                    ? java.time.Duration.between(existingEnd, candidateStart).toHours() >= 2
                    : !existingStart.isBefore(candidateEnd)
                    && java.time.Duration.between(candidateEnd, existingStart).toHours() >= 2;
            if (!enoughGap)
                throw new IllegalArgumentException("Venue bookings need a two-hour setup and cleaning gap.");
        }
    }

    private static boolean blocksSchedule(VenueReservation reservation) {
        if (reservation.status() == BookingStatus.CANCELLED || reservation.status() == BookingStatus.DECLINED
                || reservation.status() == BookingStatus.EXPIRED) return false;
        return reservation.status() == BookingStatus.APPROVED || reservation.status() == BookingStatus.CONFIRMED
                || reservation.status() == BookingStatus.RESERVED
                || reservation.paymentStatus() == PaymentStatus.PAID
                || reservation.paymentStatus() == PaymentStatus.VERIFIED
                || reservation.paymentStatus() == PaymentStatus.PENDING_VERIFICATION;
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
}
