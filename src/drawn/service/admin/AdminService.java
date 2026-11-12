package drawn.service.admin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Session-scoped admin login and demo events for the separate admin GUI. */
public final class AdminService {
    private static final String ADMIN_USERNAME = "admin@drawn.com";
    private static final String ADMIN_PASSWORD = "admin";

    private final List<LegacyEvent> events = new ArrayList<>();

    public static boolean isAdminLogin(String username, String password) {
        return username != null && password != null
                && username.equalsIgnoreCase(ADMIN_USERNAME) && password.equals(ADMIN_PASSWORD);
    }

    public List<LegacyEvent> events() { return List.copyOf(events); }

    public LegacyEvent addEvent(String name, String date, int capacity, BigDecimal price) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Event name is required.");
        if (date == null || date.isBlank()) throw new IllegalArgumentException("Date is required.");
        if (capacity < 1) throw new IllegalArgumentException("Ticket capacity must be at least 1.");
        if (price == null || price.signum() < 0 || price.scale() > 2) {
            throw new IllegalArgumentException("Ticket price must be a non-negative amount with up to 2 decimal places.");
        }
        LegacyEvent event = new LegacyEvent(name.trim(), date.trim(), capacity, price);
        events.add(event);
        return event;
    }

    public record LegacyEvent(String name, String date, int capacity, BigDecimal price, int remaining) {
        private LegacyEvent(String name, String date, int capacity, BigDecimal price) {
            this(name, date, capacity, price, capacity);
        }
    }
}
