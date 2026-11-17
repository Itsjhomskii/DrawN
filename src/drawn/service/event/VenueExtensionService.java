package drawn.service.event;

import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.ExtensionRequestStatus;
import drawn.model.event.VenueExtensionRequest;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentStatus;
import drawn.model.user.Customer;
import drawn.storage.VenueExtensionRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persists and validates customer requests to extend an event's approved venue booking. */
public final class VenueExtensionService {
    private final EventService events;
    private final ReservationService reservations;
    private final VenueExtensionRepository requests;
    private final Clock clock;

    public VenueExtensionService(EventService events, ReservationService reservations,
                                 VenueExtensionRepository requests, Clock clock) {
        this.events = Objects.requireNonNull(events);
        this.reservations = Objects.requireNonNull(reservations);
        this.requests = Objects.requireNonNull(requests);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized VenueExtensionRequest request(Customer organizer, String eventId, LocalTime newEndTime)
            throws IOException {
        Objects.requireNonNull(organizer, "Organizer cannot be null");
        Event event = events.eventsFor(organizer).stream().filter(candidate -> candidate.id().equals(eventId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Event not found for this account."));
        if (event.approvalStatus() != EventApprovalStatus.PUBLISHED)
            throw new IllegalArgumentException("Only a published event can request a time extension.");
        if (!event.date().atTime(event.startTime()).isAfter(LocalDateTime.now(clock)))
            throw new IllegalArgumentException("The event has already started or passed.");
        if (newEndTime == null || !newEndTime.isAfter(event.endTime()))
            throw new IllegalArgumentException("The new end time must be later than the current end time.");
        ReservationService.validatePricing(event.endTime(), newEndTime, drawn.model.event.ReservationPricing.HOURLY);

        VenueReservation booking = reservations.forCustomer(organizer).stream()
                .filter(reservation -> reservation.eventId().equals(event.id()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("The event's approved venue booking was not found."));
        if (booking.status() != BookingStatus.APPROVED && booking.status() != BookingStatus.CONFIRMED
                && booking.status() != BookingStatus.RESERVED)
            throw new IllegalArgumentException("The linked venue booking cannot be extended in its current status.");
        if (booking.paymentStatus() != PaymentStatus.PAID && booking.paymentStatus() != PaymentStatus.VERIFIED)
            throw new IllegalArgumentException("The linked venue booking must be fully paid before requesting an extension.");
        if (requests.hasPendingForEvent(event.id()))
            throw new IllegalArgumentException("An extension request is already awaiting administrator review.");

        reservations.ensureScheduleAvailable(booking.venueName(), booking.date(), booking.startTime(), newEndTime, booking.id());
        BigDecimal addOn = ReservationService.price(event.venue(), event.endTime(), newEndTime,
                drawn.model.event.ReservationPricing.HOURLY);
        VenueExtensionRequest request = new VenueExtensionRequest(UUID.randomUUID().toString(), booking.id(), event.id(),
                organizer.getId(), newEndTime, addOn, LocalDateTime.now(clock), ExtensionRequestStatus.PENDING, "");
        requests.save(request);
        return request;
    }

    public List<VenueExtensionRequest> requestsForEvent(Customer organizer, String eventId) throws IOException {
        boolean owned = events.eventsFor(organizer).stream().anyMatch(event -> event.id().equals(eventId));
        if (!owned) throw new IllegalArgumentException("Event not found for this account.");
        return requests.forEvent(eventId);
    }

    public void cancelPendingForEvent(String eventId) throws IOException {
        requests.cancelPendingForEvent(eventId);
    }
}
