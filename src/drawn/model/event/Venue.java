package drawn.model.event;

import java.math.BigDecimal;
import java.util.Objects;

/** A selectable venue identified by its name. */
public record Venue(String name, VenueType type, int capacity, BigDecimal hourlyRate,
                    BigDecimal morningRate, BigDecimal afternoonRate) {
    public Venue(String name, VenueType type) {
        this(name, type, Integer.MAX_VALUE, new BigDecimal("1000.00"),
                new BigDecimal("5000.00"), new BigDecimal("5000.00"));
    }

    public Venue {
        Objects.requireNonNull(name, "Venue name cannot be null");
        name = name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Venue name cannot be blank.");
        Objects.requireNonNull(type, "Venue type cannot be null");
        if (capacity <= 0) throw new IllegalArgumentException("Venue capacity must be positive.");
        hourlyRate = validPrice(hourlyRate, "Hourly rate");
        morningRate = validPrice(morningRate, "Morning rate");
        afternoonRate = validPrice(afternoonRate, "Afternoon rate");
    }

    private static BigDecimal validPrice(BigDecimal price, String label) {
        Objects.requireNonNull(price, label + " cannot be null");
        if (price.signum() < 0 || price.scale() > 2) throw new IllegalArgumentException(label + " is invalid.");
        return price;
    }

    public boolean supports(EventPurpose purpose) {
        Objects.requireNonNull(purpose, "Event purpose cannot be null");
        return type == VenueType.BOTH
                || (purpose == EventPurpose.SOCIAL_GATHERING && type == VenueType.NON_COMMERCIAL)
                || (purpose == EventPurpose.TICKETED_EVENT && type == VenueType.COMMERCIAL);
    }
}
