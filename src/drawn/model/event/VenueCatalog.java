package drawn.model.event;

import java.util.List;
import java.math.BigDecimal;

/** Generic starter venues; replace with approved venue data when available. */
public final class VenueCatalog {
    private static final List<Venue> VENUES = List.of(
            venue(1, VenueType.NON_COMMERCIAL, 50, "500.00", "2000.00", "2000.00"),
            venue(2, VenueType.NON_COMMERCIAL, 75, "650.00", "2600.00", "2600.00"),
            venue(3, VenueType.NON_COMMERCIAL, 100, "800.00", "3200.00", "3200.00"),
            venue(4, VenueType.NON_COMMERCIAL, 125, "950.00", "3800.00", "3800.00"),
            venue(5, VenueType.NON_COMMERCIAL, 150, "1100.00", "4400.00", "4400.00"),
            venue(6, VenueType.COMMERCIAL, 250, "2500.00", "10000.00", "10000.00"),
            venue(7, VenueType.COMMERCIAL, 350, "3500.00", "14000.00", "14000.00"),
            venue(8, VenueType.COMMERCIAL, 500, "5000.00", "20000.00", "20000.00"),
            venue(9, VenueType.COMMERCIAL, 750, "7500.00", "30000.00", "30000.00"),
            venue(10, VenueType.COMMERCIAL, 1000, "10000.00", "40000.00", "40000.00"));

    private VenueCatalog() { }

    private static Venue venue(int number, VenueType type, int capacity, String hourly, String morning, String afternoon) {
        return new Venue("Venue " + number, type, capacity, new BigDecimal(hourly),
                new BigDecimal(morning), new BigDecimal(afternoon));
    }

    public static List<Venue> all() {
        return VENUES;
    }
}
