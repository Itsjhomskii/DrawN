package drawn.model.event;

import java.math.BigDecimal;
import java.util.Objects;

/** Ticket category details for a ticketed event submission. */
public record TicketCategory(String name, BigDecimal price, int quantity) {
    public TicketCategory {
        Objects.requireNonNull(name, "Ticket category name cannot be null");
        name = name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Ticket category name cannot be blank.");
        Objects.requireNonNull(price, "Ticket price cannot be null");
        if (price.signum() < 0) throw new IllegalArgumentException("Ticket price cannot be negative.");
        if (quantity <= 0) throw new IllegalArgumentException("Ticket quantity must be positive.");
    }
}
