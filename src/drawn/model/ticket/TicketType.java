package drawn.model.ticket;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public record TicketType(String id, String eventId, String name, String description, BigDecimal price, int quantity, int sold,
                         LocalDateTime salesStart, LocalDateTime salesDeadline) {
    public TicketType(String id, String eventId, String name, BigDecimal price, int quantity, int sold, LocalDateTime deadline) {
        this(id, eventId, name, "", price, quantity, sold, null, deadline);
    }
    public TicketType {
        Objects.requireNonNull(id); Objects.requireNonNull(eventId); Objects.requireNonNull(name);
        description = description == null ? "" : description.trim();
        if (name.isBlank()) throw new IllegalArgumentException("Ticket name cannot be blank.");
        Objects.requireNonNull(price);
        if (price.signum() < 0 || price.scale() > 2) throw new IllegalArgumentException("Ticket price is invalid.");
        if (quantity <= 0 || sold < 0 || sold > quantity) throw new IllegalArgumentException("Ticket quantity is invalid.");
        // A null sales window is the explicit legacy policy: open immediately and
        // remain available until an end time is known, without inventing a date.
        if (salesStart != null && salesDeadline != null && !salesDeadline.isAfter(salesStart))
            throw new IllegalArgumentException("Ticket sales end must be after sales start.");
    }
    public TicketType sold(int amount) { return new TicketType(id, eventId, name, description, price, quantity, sold + amount, salesStart, salesDeadline); }
    public int remaining() { return quantity - sold; }
}
