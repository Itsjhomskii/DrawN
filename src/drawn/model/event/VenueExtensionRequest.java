package drawn.model.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/** A persisted request to extend a published event's existing venue booking. */
public record VenueExtensionRequest(String id, String reservationId, String eventId, String organizerId,
                                    LocalTime requestedEndTime, BigDecimal additionalCost,
                                    LocalDateTime requestedAt, ExtensionRequestStatus status,
                                    String decisionReason) {
    public VenueExtensionRequest {
        id = required(id, "Request ID");
        reservationId = required(reservationId, "Reservation ID");
        eventId = required(eventId, "Event ID");
        organizerId = required(organizerId, "Organizer ID");
        Objects.requireNonNull(requestedEndTime, "Requested end time cannot be null");
        Objects.requireNonNull(additionalCost, "Additional cost cannot be null");
        if (additionalCost.signum() < 0 || additionalCost.scale() > 2)
            throw new IllegalArgumentException("Extension cost is invalid.");
        Objects.requireNonNull(requestedAt, "Request timestamp cannot be null");
        Objects.requireNonNull(status, "Request status cannot be null");
        decisionReason = decisionReason == null ? "" : decisionReason.trim();
    }

    private static String required(String value, String label) {
        Objects.requireNonNull(value, label + " cannot be null");
        String result = value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " cannot be blank.");
        return result;
    }
}
