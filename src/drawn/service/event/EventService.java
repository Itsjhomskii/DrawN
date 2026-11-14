package drawn.service.event;

import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.TicketCategory;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueReservation;
import drawn.model.event.BookingStatus;
import drawn.model.payment.PaymentStatus;
import drawn.model.user.Customer;
import drawn.storage.EventRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.TicketRepository;
import drawn.storage.PaymentRepository;
import drawn.model.payment.PaymentPurpose;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validates and persists customer event submissions. */
public final class EventService {
    private final EventRepository repository;
    private final ReservationRepository reservations;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final Clock clock;

    public EventService(EventRepository repository) {
        this(repository, null, null, null, Clock.systemDefaultZone());
    }

    public EventService(EventRepository repository, Clock clock) {
        this(repository, null, null, null, clock);
    }

    public EventService(EventRepository repository, ReservationRepository reservations) {
        this(repository, reservations, null, null, Clock.systemDefaultZone());
    }

    public EventService(EventRepository repository, ReservationRepository reservations, Clock clock) {
        this(repository, reservations, null, null, clock);
    }

    public EventService(EventRepository repository, ReservationRepository reservations, TicketRepository tickets) {
        this(repository, reservations, tickets, null, Clock.systemDefaultZone());
    }

    public EventService(EventRepository repository, ReservationRepository reservations, TicketRepository tickets, Clock clock) {
        this(repository, reservations, tickets, null, clock);
    }

    public EventService(EventRepository repository, ReservationRepository reservations, TicketRepository tickets,
                        PaymentRepository payments, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Event repository cannot be null");
        this.reservations = reservations;
        this.tickets = tickets;
        this.payments = payments;
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    public Event saveDraft(Customer organizer, EventDraft draft) throws IOException {
        List<TicketCategory> categories = validateDraft(organizer, draft);

        Event event = new Event(UUID.randomUUID().toString(), organizer.getId(), draft.name(), draft.description(),
                draft.type(), draft.purpose(), draft.visibility(), draft.date(), draft.startTime(), draft.endTime(),
                draft.expectedAttendance(), draft.venue(), EventApprovalStatus.DRAFT, LocalDateTime.now(clock),
                categories);
        repository.save(event);
        return event;
    }

    public Event saveDraftForReservation(Customer organizer, EventDraft draft, String reservationId) throws IOException {
        Event event = saveDraft(organizer, draft);
        repository.linkReservation(event.id(), reservationId);
        return event;
    }

    /** Saves configured event details separately from the later publication action. */
    public Event createConfiguredForApprovedReservation(Customer organizer, EventDraft draft, String reservationId)
            throws IOException {
        return createForApprovedReservation(organizer, draft, reservationId, EventApprovalStatus.APPROVED);
    }

    /** Creates a complete customer event after its venue reservation has been approved. */
    public Event createPublishedForApprovedReservation(Customer organizer, EventDraft draft, String reservationId)
            throws IOException {
        return createForApprovedReservation(organizer, draft, reservationId, EventApprovalStatus.PUBLISHED);
    }

    private Event createForApprovedReservation(Customer organizer, EventDraft draft, String reservationId,
                                               EventApprovalStatus initialStatus) throws IOException {
        validateDraft(organizer, draft);
        if (draft.purpose() == EventPurpose.TICKETED_EVENT && draft.ticketCategories().isEmpty())
            throw new IllegalArgumentException("Commercial events need at least one ticket category.");
        if (reservations == null || payments == null)
            throw new IllegalStateException("Reservation validation is not configured.");

        VenueReservation reservation = reservations.findById(reservationId);
        validateReservationForNewEvent(organizer, draft, reservation);
        Event event = new Event(UUID.randomUUID().toString(), organizer.getId(), draft.name(), draft.description(),
                draft.type(), draft.purpose(), draft.visibility(), draft.date(), draft.startTime(), draft.endTime(),
                draft.expectedAttendance(), draft.venue(), initialStatus, LocalDateTime.now(clock),
                draft.ticketCategories());
        boolean saved = false;
        try {
            repository.save(event);
            saved = true;
            repository.linkReservation(event.id(), reservation.id());
            reservations.attachEvent(reservation.id(), event.id(), organizer.getId());
            return event;
        } catch (IOException | RuntimeException ex) {
            // The reservation payment belongs to the venue request, not to an unfinished event.
            // Undo only the event/link work so the approved reservation remains available.
            if (saved) {
                try { repository.delete(event.id()); }
                catch (IOException | RuntimeException rollbackFailure) { ex.addSuppressed(rollbackFailure); }
            }
            throw ex;
        }
    }

    /** Compensates a failed customer creation before the event becomes available to buyers. */
    public void discardFailedCustomerCreation(Customer organizer, String eventId, String reservationId) throws IOException {
        Event event = owned(organizer, eventId);
        if (tickets != null) tickets.discardUnpurchasedEvent(eventId);
        if (reservations != null) {
            VenueReservation reservation = reservations.findById(reservationId);
            if (reservation.eventId().equals(eventId)) reservations.detachEvent(reservationId, organizer.getId());
        }
        repository.delete(event.id());
    }

    public Event submitDraft(Customer organizer, String eventId) throws IOException {
        Event event = owned(organizer, eventId);
        if (event.approvalStatus() != EventApprovalStatus.DRAFT && event.approvalStatus() != EventApprovalStatus.REJECTED)
            throw new IllegalArgumentException("Only draft or rejected events can be submitted.");
        validateSubmittable(event, organizer);
        repository.updateStatus(eventId, EventApprovalStatus.PENDING);
        return withStatus(event, EventApprovalStatus.PENDING);
    }

    public Event editDraft(Customer organizer, String eventId, EventDraft draft) throws IOException {
        Event existing = owned(organizer, eventId);
        if (existing.approvalStatus() != EventApprovalStatus.DRAFT
                && existing.approvalStatus() != EventApprovalStatus.REJECTED)
            throw new IllegalArgumentException("Only draft or rejected events can be edited.");
        validateDraft(organizer, draft);
        Event updated = new Event(existing.id(), existing.organizerId(), draft.name().trim(), draft.description().trim(),
                draft.type(), draft.purpose(), draft.visibility(), draft.date(), draft.startTime(), draft.endTime(),
                draft.expectedAttendance(), draft.venue(), existing.approvalStatus(), existing.submittedAt(),
                draft.ticketCategories());
        repository.save(updated);
        return updated;
    }

    /** Updates event details while preserving the approved reservation's venue and schedule. */
    public Event editConfiguredEvent(Customer organizer, String eventId, EventDraft draft) throws IOException {
        Event existing = owned(organizer, eventId);
        if (existing.approvalStatus() != EventApprovalStatus.APPROVED
                && existing.approvalStatus() != EventApprovalStatus.PUBLISHED)
            throw new IllegalArgumentException("Only approved or published events can be edited.");
        EventDraft fixedSchedule = new EventDraft(draft.name(), draft.description(), draft.type(), draft.purpose(),
                draft.visibility(), existing.date(), existing.startTime(), existing.endTime(), draft.expectedAttendance(),
                existing.venue(), draft.ticketCategories());
        List<TicketCategory> categories = validateDraft(organizer, fixedSchedule);
        Event updated = new Event(existing.id(), existing.organizerId(), fixedSchedule.name().trim(),
                fixedSchedule.description().trim(), fixedSchedule.type(), fixedSchedule.purpose(),
                fixedSchedule.visibility(), existing.date(), existing.startTime(), existing.endTime(),
                fixedSchedule.expectedAttendance(), existing.venue(), existing.approvalStatus(), existing.submittedAt(), categories);
        repository.save(updated);
        return updated;
    }

    public void deleteDraft(Customer organizer, String eventId) throws IOException {
        if (reservations == null) throw new IllegalStateException("Reservation validation is not configured.");
        Event event = owned(organizer, eventId);
        if (event.approvalStatus() != EventApprovalStatus.DRAFT)
            throw new IllegalArgumentException("Only draft events can be deleted.");
        String linkedReservationId = repository.reservationId(eventId);
        List<VenueReservation> linked = reservations.findAll().stream()
                .filter(r -> r.eventId().equals(eventId) || r.id().equals(linkedReservationId)).toList();
        for (VenueReservation reservation : linked) {
            if (reservation.status() != BookingStatus.CANCELLED && reservation.status() != BookingStatus.DECLINED
                    && reservation.status() != BookingStatus.EXPIRED)
                throw new IllegalArgumentException("Cancel or resolve the associated reservation before deleting this draft.");
            if (reservation.paymentStatus() == PaymentStatus.PENDING_VERIFICATION
                    || reservation.paymentStatus() == PaymentStatus.PAID || reservation.paymentStatus() == PaymentStatus.VERIFIED)
                throw new IllegalArgumentException("Resolve the reservation payment or refund before deleting this draft.");
            if (payments != null) {
                List<drawn.model.payment.PaymentTransaction> history = payments.findAll().stream()
                        .filter(p -> p.referenceId().equals(reservation.id()) && p.purpose() == PaymentPurpose.VENUE_BOOKING).toList();
                boolean completedPayment = history.stream().anyMatch(p -> p.status() == PaymentStatus.PAID || p.status() == PaymentStatus.VERIFIED);
                boolean completedRefund = history.stream().anyMatch(p -> p.status() == PaymentStatus.REFUNDED);
                boolean pending = history.stream().anyMatch(p -> p.status() == PaymentStatus.PENDING_VERIFICATION);
                if (pending || (completedPayment && !completedRefund))
                    throw new IllegalArgumentException("Resolve the reservation payment or refund before deleting this draft.");
            }
        }
        repository.delete(eventId);
    }

    public List<Event> pendingReview() throws IOException {
        return repository.findAll().stream().filter(e -> e.approvalStatus() == EventApprovalStatus.PENDING).toList();
    }

    public void review(String eventId, boolean approved) throws IOException {
        Event event = repository.findAll().stream().filter(e -> e.id().equals(eventId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Event not found."));
        if (event.approvalStatus() != EventApprovalStatus.PENDING)
            throw new IllegalArgumentException("Only pending events can be reviewed.");
        repository.updateStatus(eventId, approved ? EventApprovalStatus.APPROVED : EventApprovalStatus.REJECTED);
    }

    public void publish(Customer organizer, String eventId) throws IOException {
        Event event = owned(organizer, eventId);
        if (event.approvalStatus() == EventApprovalStatus.PUBLISHED)
            throw new IllegalArgumentException("Event is already published.");
        if (event.approvalStatus() != EventApprovalStatus.DRAFT && event.approvalStatus() != EventApprovalStatus.PENDING
                && event.approvalStatus() != EventApprovalStatus.APPROVED)
            throw new IllegalArgumentException("This event cannot be published in its current status.");
        validateSubmittable(event, organizer);
        repository.updateStatus(eventId, EventApprovalStatus.PUBLISHED);
    }

    private Event owned(Customer organizer, String eventId) throws IOException {
        Objects.requireNonNull(organizer, "Customer cannot be null");
        return repository.findAll().stream().filter(e -> e.id().equals(eventId)
                && e.organizerId().equals(organizer.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Event not found for this account."));
    }

    private void validateSubmittable(Event event, Customer organizer) throws IOException {
        if (reservations == null) throw new IllegalStateException("Reservation validation is not configured.");
        if (payments == null) throw new IllegalStateException("Payment validation is not configured.");
        if (event.name().isBlank() || event.description().isBlank() || event.date().isBefore(LocalDate.now(clock))
                || !event.endTime().isAfter(event.startTime()) || event.expectedAttendance() <= 0)
            throw new IllegalArgumentException("Event details are incomplete or invalid.");
        if (VenueCatalog.all().stream().noneMatch(v -> v.name().equals(event.venue().name())
                && v.type() == event.venue().type() && v.supports(event.purpose())
                && event.expectedAttendance() <= v.capacity()))
            throw new IllegalArgumentException("The selected venue is missing or invalid for this event.");
        String reservationId = repository.reservationId(event.id());
        VenueReservation reservation = reservations.findAll().stream()
                .filter(r -> r.id().equals(reservationId) || (reservationId.isBlank() && r.eventId().equals(event.id())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("An associated reservation is required."));
        if (!reservation.organizerId().equals(organizer.getId()) || !reservation.eventId().equals(event.id()))
            throw new IllegalArgumentException("The reservation must belong to this customer and event.");
        boolean approved = reservation.status() == BookingStatus.APPROVED || reservation.status() == BookingStatus.CONFIRMED;
        if (!approved || (reservation.paymentStatus() != PaymentStatus.PAID
                && reservation.paymentStatus() != PaymentStatus.VERIFIED))
            throw new IllegalArgumentException("The associated reservation must be approved and fully paid.");
        List<drawn.model.payment.PaymentTransaction> reservationPayments = payments.findAll().stream()
                .filter(payment -> payment.customerId().equals(organizer.getId())
                        && payment.referenceId().equals(reservation.id()) && payment.purpose() == PaymentPurpose.VENUE_BOOKING)
                .toList();
        boolean recordedPayment = reservationPayments.stream().anyMatch(payment ->
                (payment.status() == PaymentStatus.PAID || payment.status() == PaymentStatus.VERIFIED)
                        && payment.amount().compareTo(reservation.cost()) == 0);
        boolean refunded = reservationPayments.stream().anyMatch(payment -> payment.status() == PaymentStatus.REFUNDED);
        if (!recordedPayment || refunded) throw new IllegalArgumentException("The associated reservation has no matching completed payment record.");
        if (!reservation.date().equals(event.date()) || !reservation.startTime().equals(event.startTime())
                || !reservation.endTime().equals(event.endTime()) || !reservation.venueName().equals(event.venue().name()))
            throw new IllegalArgumentException("The associated reservation must match the event venue and schedule.");
        for (VenueReservation other : reservations.findAll()) {
            if (other.id().equals(reservation.id()) || !other.venueName().equals(reservation.venueName())
                    || !other.date().equals(reservation.date())) continue;
            boolean active = other.status() == BookingStatus.APPROVED || other.status() == BookingStatus.CONFIRMED;
            boolean overlaps = reservation.startTime().isBefore(other.endTime())
                    && other.startTime().isBefore(reservation.endTime());
            if (active && overlaps) throw new IllegalArgumentException("The reservation conflicts with another approved booking.");
        }
    }

    private List<TicketCategory> validateDraft(Customer organizer, EventDraft draft) {
        Objects.requireNonNull(organizer, "Organizer cannot be null");
        Objects.requireNonNull(draft, "Event details cannot be null");
        LocalDate today = LocalDate.now(clock);
        if (!isAgeEligible(organizer.getDateOfBirth(), today)) throw new IllegalArgumentException("Customers must be at least 16 to submit an event.");
        if (draft.name() == null || draft.name().isBlank()) throw new IllegalArgumentException("Event name cannot be blank.");
        if (draft.name().trim().matches("\\d+")) throw new IllegalArgumentException("Event name cannot contain only numbers; enter a descriptive name.");
        if (draft.description() == null || draft.description().isBlank()) throw new IllegalArgumentException("Event description cannot be blank.");
        if (draft.description().length() > 500) throw new IllegalArgumentException("Event description cannot exceed 500 characters.");
        if (draft.description().trim().matches("\\d+")) throw new IllegalArgumentException("Event description cannot contain only numbers; describe the event.");
        Objects.requireNonNull(draft.type(), "Event type is required.");
        Objects.requireNonNull(draft.purpose(), "Event purpose is required.");
        Objects.requireNonNull(draft.visibility(), "Event visibility is required.");
        if (draft.date() == null || draft.date().isBefore(today)) throw new IllegalArgumentException("Event date is required and cannot be in the past.");
        Objects.requireNonNull(draft.startTime(), "Event start time is required.");
        Objects.requireNonNull(draft.endTime(), "Event end time is required.");
        if (!draft.endTime().isAfter(draft.startTime())) throw new IllegalArgumentException("End time must be later than start time.");
        if (draft.expectedAttendance() <= 0) throw new IllegalArgumentException("Expected attendance must be a positive number.");
        // Basic (16–17) organizers are limited to small, non-commercial gatherings.
        if (organizer.getAge() < 18 && (draft.purpose() == EventPurpose.TICKETED_EVENT || draft.expectedAttendance() > 50))
            throw new IllegalArgumentException("Organizers aged 16–17 may create only small non-commercial gatherings.");
        Objects.requireNonNull(draft.venue(), "A venue must be selected.");
        if (!draft.venue().supports(draft.purpose())) throw new IllegalArgumentException("The selected venue does not support that event purpose.");
        if (draft.expectedAttendance() > draft.venue().capacity()) throw new IllegalArgumentException("Expected attendance exceeds the selected venue capacity.");
        List<TicketCategory> categories = List.copyOf(draft.ticketCategories());
        if (draft.purpose() == EventPurpose.SOCIAL_GATHERING && !categories.isEmpty()) throw new IllegalArgumentException("Social gatherings cannot have ticket categories.");
        Set<String> categoryNames = new HashSet<>();
        for (TicketCategory category : categories) {
            if (!categoryNames.add(category.name().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Ticket category names must be unique.");
        }
        return categories;
    }

    private void validateReservationForNewEvent(Customer organizer, EventDraft draft, VenueReservation reservation)
            throws IOException {
        if (!reservation.organizerId().equals(organizer.getId()))
            throw new IllegalArgumentException("The reservation must belong to this customer.");
        if (!reservation.eventId().isBlank())
            throw new IllegalArgumentException("This reservation is already linked to another event.");
        if (reservation.status() != BookingStatus.APPROVED && reservation.status() != BookingStatus.CONFIRMED)
            throw new IllegalArgumentException("Your reservation is awaiting administrator approval.");
        if (reservation.paymentStatus() != PaymentStatus.PAID && reservation.paymentStatus() != PaymentStatus.VERIFIED)
            throw new IllegalArgumentException("The associated reservation must be fully paid.");
        if (!reservation.date().equals(draft.date()) || !reservation.startTime().equals(draft.startTime())
                || !reservation.endTime().equals(draft.endTime()) || !reservation.venueName().equals(draft.venue().name()))
            throw new IllegalArgumentException("The event must match the approved venue reservation schedule.");
        boolean recordedPayment = payments.findAll().stream().anyMatch(payment -> payment.customerId().equals(organizer.getId())
                && payment.referenceId().equals(reservation.id()) && payment.purpose() == PaymentPurpose.VENUE_BOOKING
                && (payment.status() == PaymentStatus.PAID || payment.status() == PaymentStatus.VERIFIED)
                && payment.amount().compareTo(reservation.cost()) == 0);
        boolean refunded = payments.findAll().stream().anyMatch(payment -> payment.referenceId().equals(reservation.id())
                && payment.purpose() == PaymentPurpose.VENUE_BOOKING && payment.status() == PaymentStatus.REFUNDED);
        if (!recordedPayment || refunded)
            throw new IllegalArgumentException("The associated reservation has no matching completed payment record.");
    }

    private static Event withStatus(Event e, EventApprovalStatus status) {
        return new Event(e.id(), e.organizerId(), e.name(), e.description(), e.type(), e.purpose(), e.visibility(),
                e.date(), e.startTime(), e.endTime(), e.expectedAttendance(), e.venue(), status, e.submittedAt(), e.ticketCategories());
    }

    public List<Event> eventsFor(Customer customer) throws IOException {
        return repository.findByOrganizer(customer.getId());
    }

    public long pendingCountFor(Customer customer) throws IOException {
        return eventsFor(customer).stream().filter(event -> event.approvalStatus() == EventApprovalStatus.PENDING).count();
    }

    public List<Event> publicApprovedEvents() throws IOException {
        return repository.findPublicApproved();
    }

    static boolean isAgeEligible(LocalDate dateOfBirth, LocalDate today) {
        return dateOfBirth != null && !dateOfBirth.isAfter(today)
                && Period.between(dateOfBirth, today).getYears() >= 16;
    }
}
