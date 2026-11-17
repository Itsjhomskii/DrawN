package drawn.model.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/** Ticket category details for a ticketed event submission. */
public record TicketCategory(String name, BigDecimal price, int quantity,
                             LocalDateTime salesStart, LocalDateTime salesDeadline) {
    public TicketCategory(String name, BigDecimal price, int quantity) {
        this(name, price, quantity, null, null);
    }

    public TicketCategory {
        Objects.requireNonNull(name, "Ticket category name cannot be null");
        name = name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Ticket category name cannot be blank.");
        Objects.requireNonNull(price, "Ticket price cannot be null");
        if (price.signum() < 0) throw new IllegalArgumentException("Ticket price cannot be negative.");
        if (quantity <= 0) throw new IllegalArgumentException("Ticket quantity must be positive.");
        if ((salesStart == null) != (salesDeadline == null))
            throw new IllegalArgumentException("Ticket sales opening and closing times must both be provided.");
        if (salesStart != null && !salesDeadline.isAfter(salesStart))
            throw new IllegalArgumentException("Ticket sales must close after they open.");
    }
}
