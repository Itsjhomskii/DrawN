package drawn.model.ticket;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public record TicketType(String id, String eventId, String name, BigDecimal price, int quantity, int sold,
                         LocalDateTime salesDeadline) {
    public TicketType {
        Objects.requireNonNull(id); Objects.requireNonNull(eventId); Objects.requireNonNull(name);
        if (name.isBlank()) throw new IllegalArgumentException("Ticket name cannot be blank.");
        Objects.requireNonNull(price);
        if (price.signum() < 0 || price.scale() > 2) throw new IllegalArgumentException("Ticket price is invalid.");
        if (quantity <= 0 || sold < 0 || sold > quantity) throw new IllegalArgumentException("Ticket quantity is invalid.");
        Objects.requireNonNull(salesDeadline);
    }
    public TicketType sold(int amount) { return new TicketType(id, eventId, name, price, quantity, sold + amount, salesDeadline); }
    public int remaining() { return quantity - sold; }
}
