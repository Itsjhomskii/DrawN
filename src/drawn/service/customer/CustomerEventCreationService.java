package drawn.service.customer;

import drawn.model.event.Event;
import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventVisibility;
import drawn.model.user.Customer;
import drawn.model.ticket.TicketType;
import drawn.service.event.EventService;
import drawn.service.event.EventPublishingService;
import drawn.storage.PrivateEventAccessRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates an event setup after its venue reservation has been approved. */
public final class CustomerEventCreationService {
    private final EventService events;
    private final EventPublishingService publishing;
    private final PrivateEventAccessRepository privateAccess;

    public CustomerEventCreationService(EventService events, EventPublishingService publishing,
                                        PrivateEventAccessRepository privateAccess) {
        this.events = Objects.requireNonNull(events);
        this.publishing = Objects.requireNonNull(publishing);
        this.privateAccess = Objects.requireNonNull(privateAccess);
    }

    public Event create(Customer customer, EventDraft draft, String reservationId, String privateKeyword) throws IOException {
        Event saved = null;
        try {
            saved = events.createPublishedForApprovedReservation(customer, draft, reservationId);
            if (draft.visibility() == EventVisibility.PRIVATE) privateAccess.save(saved.id(), privateKeyword);
            if (draft.purpose() == EventPurpose.TICKETED_EVENT) {
                String eventId = saved.id();
                LocalDateTime salesStart = LocalDateTime.now();
                LocalDateTime salesDeadline = draft.date().atTime(draft.startTime()).minusMinutes(1);
                List<TicketType> types = draft.ticketCategories().stream().map(category -> new TicketType(
                        UUID.randomUUID().toString(), eventId, category.name(), category.name() + " ticket",
                        category.price(), category.quantity(), 0, salesStart, salesDeadline)).toList();
                publishing.configureTickets(customer, saved.id(), types);
                publishing.publish(customer, saved.id());
            }
            return saved;
        } catch (IOException | RuntimeException failure) {
            if (saved != null) {
                try {
                    privateAccess.delete(saved.id());
                    events.discardFailedCustomerCreation(customer, saved.id(), reservationId);
                } catch (IOException | RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }
}
