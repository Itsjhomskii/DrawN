package drawn.model.event;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Unpersisted customer input passed to event business logic for validation. */
public record EventDraft(String name, String description, EventType type, EventPurpose purpose,
                         EventVisibility visibility, LocalDate date, LocalTime startTime, LocalTime endTime,
                         int expectedAttendance, Venue venue, List<TicketCategory> ticketCategories) {
    public EventDraft {
        ticketCategories = ticketCategories == null ? List.of() : List.copyOf(ticketCategories);
    }
}
