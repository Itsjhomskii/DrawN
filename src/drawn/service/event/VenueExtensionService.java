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
import java.util.Comparator;
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

    public List<VenueExtensionRequest> pendingRequests() throws IOException {
        return requests.findAll().stream().filter(request -> request.status() == ExtensionRequestStatus.PENDING)
                .sorted(Comparator.comparing(VenueExtensionRequest::requestedAt)).toList();
    }

    /** Approves only the schedule update. Extension payment policy is not defined, so this does not charge the wallet. */
    public synchronized VenueExtensionRequest approve(String requestId) throws IOException {
        VenueExtensionRequest request = findRequest(requestId);
        requirePending(request);
        Event event = events.allEvents().stream().filter(candidate -> candidate.id().equals(request.eventId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Extension event not found."));
        VenueReservation booking = reservations.find(request.reservationId());
        validateDecisionEligibility(request, event, booking);

        boolean alreadyApplied = event.endTime().equals(request.requestedEndTime())
                && booking.endTime().equals(request.requestedEndTime());
        if (!alreadyApplied) {
            if (!event.endTime().equals(booking.endTime()))
                throw new IllegalStateException("Event and reservation schedules differ; reconcile them before approving.");
            reservations.ensureScheduleAvailable(booking.venueName(), booking.date(), booking.startTime(),
                    request.requestedEndTime(), booking.id());
            LocalTime oldEnd = event.endTime();
            boolean eventChanged = false;
            boolean reservationChanged = false;
            try {
                events.extendPublishedEvent(event.id(), request.requestedEndTime());
                eventChanged = true;
                reservations.extendApprovedReservation(booking.id(), request.requestedEndTime());
                reservationChanged = true;
            } catch (IOException | RuntimeException failure) {
                if (reservationChanged) {
                    try { reservations.restoreApprovedReservationEndTime(booking.id(), oldEnd); }
                    catch (IOException | RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                }
                if (eventChanged) {
                    try { events.restorePublishedEventEndTime(event.id(), oldEnd); }
                    catch (IOException | RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                }
                throw failure;
            }
        }

        VenueExtensionRequest approved = withDecision(request, ExtensionRequestStatus.APPROVED, "Approved; no extension payment collected");
        requests.update(approved);
        return approved;
    }

    public synchronized VenueExtensionRequest decline(String requestId, String reason) throws IOException {
        VenueExtensionRequest request = findRequest(requestId);
        requirePending(request);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A decline reason is required.");
        VenueExtensionRequest declined = withDecision(request, ExtensionRequestStatus.DECLINED, reason);
        requests.update(declined);
        return declined;
    }

    private void validateDecisionEligibility(VenueExtensionRequest request, Event event, VenueReservation booking)
            throws IOException {
        if (!request.organizerId().equals(event.organizerId()) || !request.organizerId().equals(booking.organizerId())
                || !request.eventId().equals(booking.eventId()) || !request.reservationId().equals(booking.id()))
            throw new IllegalArgumentException("The extension is no longer linked to its original event and reservation.");
        if (event.approvalStatus() != EventApprovalStatus.PUBLISHED)
            throw new IllegalArgumentException("Only a published event can be extended.");
        if (booking.status() != BookingStatus.APPROVED && booking.status() != BookingStatus.CONFIRMED
                && booking.status() != BookingStatus.RESERVED)
            throw new IllegalArgumentException("The venue reservation is no longer approved.");
        if (booking.paymentStatus() != PaymentStatus.PAID && booking.paymentStatus() != PaymentStatus.VERIFIED)
            throw new IllegalArgumentException("The venue reservation is no longer fully paid.");
        if (!event.date().atTime(event.startTime()).isAfter(LocalDateTime.now(clock)))
            throw new IllegalArgumentException("The event has already started or passed.");
        if (!event.venue().name().equals(booking.venueName()) || !event.date().equals(booking.date())
                || !event.startTime().equals(booking.startTime()))
            throw new IllegalArgumentException("The event and venue reservation no longer match.");
        if (!event.endTime().equals(request.requestedEndTime())
                && !request.requestedEndTime().isAfter(event.endTime()))
            throw new IllegalArgumentException("The requested extension no longer extends the current schedule.");
    }

    private VenueExtensionRequest findRequest(String requestId) throws IOException {
        return requests.findAll().stream().filter(candidate -> candidate.id().equals(requestId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Venue extension request not found."));
    }

    private static void requirePending(VenueExtensionRequest request) {
        if (request.status() != ExtensionRequestStatus.PENDING)
            throw new IllegalArgumentException("Only pending extension requests can be decided.");
    }

    private static VenueExtensionRequest withDecision(VenueExtensionRequest request, ExtensionRequestStatus status,
                                                       String reason) {
        return new VenueExtensionRequest(request.id(), request.reservationId(), request.eventId(), request.organizerId(),
                request.requestedEndTime(), request.additionalCost(), request.requestedAt(), status, reason);
    }
}
