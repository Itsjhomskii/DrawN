package drawn.model.event;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/** A customer-owned event submission. */
public record Event(String id, String organizerId, String name, String description, EventType type,
                    EventPurpose purpose, EventVisibility visibility, LocalDate date, LocalTime startTime,
                    LocalTime endTime, int expectedAttendance, Venue venue, EventApprovalStatus approvalStatus,
                    LocalDateTime submittedAt, List<TicketCategory> ticketCategories) {
    public Event {
        id = requiredText(id, "Event ID");
        organizerId = requiredText(organizerId, "Organizer ID");
        name = requiredText(name, "Event name");
        description = requiredText(description, "Event description");
        Objects.requireNonNull(type, "Event type cannot be null");
        Objects.requireNonNull(purpose, "Event purpose cannot be null");
        Objects.requireNonNull(visibility, "Event visibility cannot be null");
        Objects.requireNonNull(date, "Event date cannot be null");
        Objects.requireNonNull(startTime, "Start time cannot be null");
        Objects.requireNonNull(endTime, "End time cannot be null");
        if (expectedAttendance <= 0) throw new IllegalArgumentException("Expected attendance must be positive.");
        Objects.requireNonNull(venue, "Venue cannot be null");
        Objects.requireNonNull(approvalStatus, "Approval status cannot be null");
        Objects.requireNonNull(submittedAt, "Submission time cannot be null");
        ticketCategories = List.copyOf(Objects.requireNonNull(ticketCategories, "Ticket categories cannot be null"));
    }

    private static String requiredText(String value, String field) {
        Objects.requireNonNull(value, field + " cannot be null");
        String result = value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank.");
        return result;
    }
}
