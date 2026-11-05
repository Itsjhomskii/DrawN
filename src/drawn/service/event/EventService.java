package drawn.service.event;

import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.TicketCategory;
import drawn.model.user.Customer;
import drawn.storage.EventRepository;

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
    private final Clock clock;

    public EventService(EventRepository repository) {
        this(repository, Clock.systemDefaultZone());
    }

    public EventService(EventRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Event repository cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    public Event submit(Customer organizer, EventDraft draft) throws IOException {
        Objects.requireNonNull(organizer, "Organizer cannot be null");
        Objects.requireNonNull(draft, "Event details cannot be null");
        LocalDate today = LocalDate.now(clock);
        if (!isAgeEligible(organizer.getDateOfBirth(), today)) {
            throw new IllegalArgumentException("Customers must be at least 16 to submit an event.");
        }
        if (draft.name() == null || draft.name().isBlank()) throw new IllegalArgumentException("Event name cannot be blank.");
        if (draft.description() == null || draft.description().isBlank()) {
            throw new IllegalArgumentException("Event description cannot be blank.");
        }
        if (draft.description().length() > 500) {
            throw new IllegalArgumentException("Event description cannot exceed 500 characters.");
        }
        if (draft.type() == null) throw new IllegalArgumentException("Event type is required.");
        if (draft.purpose() == null) throw new IllegalArgumentException("Event purpose is required.");
        if (draft.visibility() == null) throw new IllegalArgumentException("Event visibility is required.");
        if (draft.date() == null) throw new IllegalArgumentException("Event date is required.");
        if (draft.date().isBefore(today)) throw new IllegalArgumentException("Event date cannot be in the past.");
        if (draft.startTime() == null) throw new IllegalArgumentException("Event start time is required.");
        if (draft.endTime() == null) throw new IllegalArgumentException("Event end time is required.");
        if (!draft.endTime().isAfter(draft.startTime())) {
            throw new IllegalArgumentException("End time must be later than start time.");
        }
        if (draft.expectedAttendance() <= 0) {
            throw new IllegalArgumentException("Expected attendance must be a positive number.");
        }
        if (draft.venue() == null) throw new IllegalArgumentException("A venue must be selected.");
        if (!draft.venue().supports(draft.purpose())) {
            throw new IllegalArgumentException("The selected venue does not support that event purpose.");
        }
        if (draft.expectedAttendance() > draft.venue().capacity()) {
            throw new IllegalArgumentException("Expected attendance exceeds the selected venue capacity.");
        }

        List<TicketCategory> categories = List.copyOf(draft.ticketCategories());
        if (draft.purpose() == EventPurpose.SOCIAL_GATHERING && !categories.isEmpty()) {
            throw new IllegalArgumentException("Social gatherings cannot have ticket categories.");
        }
        Set<String> categoryNames = new HashSet<>();
        for (TicketCategory category : categories) {
            String key = category.name().toLowerCase(Locale.ROOT);
            if (!categoryNames.add(key)) {
                throw new IllegalArgumentException("Ticket category names must be unique.");
            }
        }

        Event event = new Event(UUID.randomUUID().toString(), organizer.getId(), draft.name(), draft.description(),
                draft.type(), draft.purpose(), draft.visibility(), draft.date(), draft.startTime(), draft.endTime(),
                draft.expectedAttendance(), draft.venue(), EventApprovalStatus.PENDING, LocalDateTime.now(clock),
                categories);
        repository.save(event);
        return event;
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
