package drawn.service.admin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Demo admin login and the admin-created event list, shared by the console and the GUI.
 * Moved unchanged out of DrawN; the list lives in memory for the current run only.
 */
public final class AdminService {
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "234";

    private final List<LegacyEvent> events = new ArrayList<>();

    public static boolean isAdminLogin(String contact, String password) {
        return contact != null && password != null
                && contact.equalsIgnoreCase(ADMIN_USERNAME) && password.equals(ADMIN_PASSWORD);
    }

    public List<LegacyEvent> events() { return List.copyOf(events); }

    public LegacyEvent addEvent(String name, String date, int capacity, BigDecimal price) {
        LegacyEvent event = new LegacyEvent(name, date, capacity, price);
        events.add(event);
        return event;
    }

    /** An admin-created event with a simple ticket count. */
    public static final class LegacyEvent {
        private final String name;
        private final String date;
        private final BigDecimal price;
        private int remaining;

        private LegacyEvent(String name, String date, int capacity, BigDecimal price) {
            this.name = name;
            this.date = date;
            this.price = price;
            this.remaining = capacity;
        }

        public String name() { return name; }
        public String date() { return date; }
        public BigDecimal price() { return price; }
        public int remaining() { return remaining; }
        public void reserve(int quantity) { remaining -= quantity; }
    }
}
